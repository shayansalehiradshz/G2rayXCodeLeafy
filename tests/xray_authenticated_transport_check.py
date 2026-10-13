#!/usr/bin/env python3
"""Opt-in Linux acceptance: private bundle -> loopback SOCKS -> Codespaces edge."""

import argparse
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time
from urllib.parse import parse_qs, unquote, urlsplit
import uuid


def outbound(link):
    uri = urlsplit(link)
    parameters = {key: values[-1] for key, values in parse_qs(uri.query, keep_blank_values=True).items()}
    if uri.scheme != "vless" or parameters.get("security") != "tls":
        raise ValueError("Acceptance requires a VLESS/TLS link")
    identifier = str(uuid.UUID(unquote(uri.username or "")))
    transport = parameters.get("type")
    tls = {"serverName": parameters["sni"], "fingerprint": parameters.get("fp", "chrome")}
    if parameters.get("alpn"):
        tls["alpn"] = parameters["alpn"].split(",")
    stream = {"network": transport, "security": "tls", "tlsSettings": tls}
    if transport == "xhttp":
        settings = {"host": parameters["host"], "path": parameters.get("path", "/"),
                    "mode": parameters.get("mode", "packet-up")}
        if parameters.get("extra"):
            extra = json.loads(parameters["extra"])
            if not isinstance(extra, dict):
                raise ValueError("XHTTP extra must be an object")
            settings["extra"] = extra
        stream["xhttpSettings"] = settings
    elif transport == "ws":
        stream["wsSettings"] = {"path": parameters.get("path", "/ws"),
                                "headers": {"Host": parameters["host"]}}
    else:
        raise ValueError("Acceptance supports generated XHTTP/WS links only")
    return {
        "protocol": "vless", "tag": "proxy",
        "settings": {"vnext": [{"address": uri.hostname, "port": uri.port or 443,
                                  "users": [{"id": identifier, "encryption": "none"}]}]},
        "streamSettings": stream, "mux": {"enabled": False}
    }


def validate(core, bundle_path):
    bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    if bundle.get("schema_version") != 2:
        raise ValueError("Use the current schema-2 bundle, not the untuned legacy bundle")
    configs = bundle.get("configs")
    if not isinstance(configs, list) or not all(isinstance(link, str) for link in configs):
        raise ValueError("Bundle configs must be a list of links")
    if not any(parse_qs(urlsplit(link).query).get("type") == ["xhttp"] for link in configs):
        raise ValueError("Bundle must include a primary XHTTP link")
    for transport in ("xhttp", "ws"):
        link = next((link for link in configs if parse_qs(urlsplit(link).query).get("type") == [transport]), None)
        if link is None:
            print(f"SKIP: {transport} is not exported", flush=True)
            continue
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        with tempfile.TemporaryDirectory(prefix="g2ray-authenticated-check-") as directory:
            root = Path(directory)
            config = {
                "log": {"loglevel": "warning"},
                "inbounds": [{"listen": "127.0.0.1", "port": port, "protocol": "socks",
                              "settings": {"udp": False}}],
                "outbounds": [outbound(link)]
            }
            config_path = root / "config.json"
            config_path.write_text(json.dumps(config), encoding="ascii")
            config_path.chmod(0o600)
            with (root / "runtime.log").open("wb") as log:
                process = subprocess.Popen([str(core), "run", "-format", "json", "-c", str(config_path)],
                                           stdout=log, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 5
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError(f"{transport} test core failed to start")
                        try:
                            with socket.create_connection(("127.0.0.1", port), timeout=1):
                                break
                        except OSError:
                            pass
                        if time.monotonic() >= deadline:
                            raise RuntimeError(f"{transport} test listener did not open")
                        time.sleep(0.05)
                    for url, expected_status in (
                        ("https://www.gstatic.com/generate_204", "204"),
                        ("https://cp.cloudflare.com/generate_204", "204"),
                        ("https://example.com/", "200")
                    ):
                        body_path = root / "response.body"
                        started = time.monotonic()
                        response = subprocess.run([
                            "curl", "--silent", "--show-error", "--noproxy", "",
                            "--socks5-hostname", f"127.0.0.1:{port}",
                            "--connect-timeout", "10", "--max-time", "25", "--header", "Cache-Control: no-store",
                            "--output", str(body_path), "--write-out", "%{http_code}", url
                        ], capture_output=True, text=True, timeout=30)
                        if response.returncode != 0 or response.stdout != expected_status:
                            raise RuntimeError(f"{transport} HTTPS failed at {urlsplit(url).hostname}")
                        if expected_status == "200" and b"Example Domain" not in body_path.read_bytes():
                            raise RuntimeError(f"{transport} HTTPS returned unexpected content")
                        elapsed_ms = round((time.monotonic() - started) * 1000)
                        print(f"PASS: authenticated {transport} {urlsplit(url).hostname} HTTP {expected_status}, {elapsed_ms}ms",
                              flush=True)
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xray", type=Path, help="Verified Xray executable")
    parser.add_argument("bundle", type=Path, help="Private current Codespace bundle; never publish it")
    parser.add_argument("--live", action="store_true", help="Consent to authenticated Internet traffic")
    arguments = parser.parse_args()
    if not arguments.live:
        parser.error("This test requires --live and a running private server")
    try:
        validate(arguments.xray.resolve(strict=True), arguments.bundle.resolve(strict=True))
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"FAIL: {error}\n")


if __name__ == "__main__":
    main()

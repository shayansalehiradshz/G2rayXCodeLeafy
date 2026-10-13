#!/usr/bin/env python3
"""Opt-in Linux acceptance: private bundle -> loopback SOCKS -> Codespaces edge."""

import argparse
import json
import os
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
    if parameters.get("cs") and tls["fingerprint"] == "unsafe":
        tls["cipherSuites"] = parameters["cs"]
    stream = {"network": transport, "security": "tls", "tlsSettings": tls}
    stream["sockopt"] = {"tcpKeepAliveIdle": 25, "tcpKeepAliveInterval": 15, "tcpUserTimeout": 30000}
    if parameters.get("fm"):
        final_mask = json.loads(parameters["fm"])
        if not isinstance(final_mask, dict):
            raise ValueError("FinalMask must be an object")
        stream["finalmask"] = final_mask
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


def build_channels(bundle_path, transports, origin_config=None, origin_keepalive_idle=None,
                   origin_disable_fast_open=False):
    if origin_config is not None:
        server = json.loads(origin_config.read_text(encoding="utf-8"))
        direct = next((item for item in server.get("outbounds", [])
                       if item.get("tag") == "direct" and item.get("protocol") == "freedom"), None)
        if direct is None:
            raise ValueError("Server config must contain its direct freedom outbound")
        original_keepalive_idle = direct.get("streamSettings", {}).get("sockopt", {}).get("tcpKeepAliveIdle")
        if origin_keepalive_idle is not None:
            direct.setdefault("streamSettings", {}).setdefault("sockopt", {})["tcpKeepAliveIdle"] = origin_keepalive_idle
        if origin_disable_fast_open:
            direct.setdefault("streamSettings", {}).setdefault("sockopt", {})["tcpFastOpen"] = False
        sockopt = direct.get("streamSettings", {}).get("sockopt", {})
        print("Origin socket settings: " + json.dumps({key: sockopt.get(key) for key in (
            "tcpKeepAliveIdle", "tcpKeepAliveInterval", "tcpFastOpen", "tcpUserTimeout")}), flush=True)
        print(f"Origin original keepalive_idle={original_keepalive_idle}; override={origin_keepalive_idle}", flush=True)
        return [("origin-baseline", direct, server.get("dns"))]
    bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    if bundle.get("schema_version") != 2:
        raise ValueError("Use the current schema-2 bundle, not the untuned legacy bundle")
    configs = bundle.get("configs")
    if not isinstance(configs, list) or not all(isinstance(link, str) for link in configs):
        raise ValueError("Bundle configs must be a list of links")
    if not any(parse_qs(urlsplit(link).query).get("type") == ["xhttp"] for link in configs):
        raise ValueError("Bundle must include a primary XHTTP link")
    channels = []
    for transport in transports:
        link = next((link for link in configs if parse_qs(urlsplit(link).query).get("type") == [transport]), None)
        if link is None:
            if len(transports) == 1:
                raise ValueError(f"Requested {transport} is not exported")
            print(f"SKIP: {transport} is not exported", flush=True)
            continue
        channels.append((transport, outbound(link), None))
    return channels


def check_retained_curl(port):
    # One process owns both requests so curl can reuse its actual HTTP/2 connection.
    url = "https://www.gstatic.com/generate_204"
    response = subprocess.run([
        "curl", "--disable", "--silent", "--show-error", "--noproxy", "",
        "--socks5-hostname", f"127.0.0.1:{port}", "--http2", "--rate", "1/m",
        "--connect-timeout", "10", "--max-time", "25", "--header", "Cache-Control: no-store",
        "--write-out", "%{json}\n", "--output", os.devnull, url, "--output", os.devnull, url
    ], capture_output=True, text=True, timeout=120)
    results = [json.loads(line) for line in response.stdout.splitlines() if line.strip()]
    for index, result in enumerate(results):
        print("Independent curl HTTP/2: " + json.dumps({
            "phase": "initial" if index == 0 else "retained_pool",
            "status": result.get("http_code"), "protocol": result.get("http_version"),
            "new_connections": result.get("num_connects"), "elapsed_ms": round(result.get("time_total", 0) * 1000)
        }), flush=True)
    if response.returncode != 0 or len(results) != 2 or any(
        result.get("http_code") != 204 or result.get("http_version") != "2" or
        not 0 < result.get("time_total", 0) <= 6 for result in results
    ):
        raise RuntimeError("Independent curl retained HTTP/2 acceptance failed")
    print(f"Independent curl second request reused_connection={results[1].get('num_connects') == 0}", flush=True)


def validate(core, bundle_path, retained_probe=None, idle_seconds=70, transports=("xhttp", "ws"), origin_config=None,
             origin_keepalive_idle=None, runtime_log_dir=None, retained_curl=False, origin_disable_fast_open=False,
             loopback_baseline=False):
    for transport, proxy_outbound, dns in build_channels(
        bundle_path, transports, origin_config, origin_keepalive_idle, origin_disable_fast_open
    ):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        with tempfile.TemporaryDirectory(prefix="g2ray-authenticated-check-") as directory:
            root = Path(directory)
            config = {
                "log": {"loglevel": "debug" if runtime_log_dir is not None else "warning"},
                "policy": {"levels": {"0": {"connIdle": 900, "bufferSize": 512}}},
                "inbounds": [{"listen": "127.0.0.1", "port": port, "protocol": "socks",
                              "settings": {"udp": False}}],
                "outbounds": [proxy_outbound]
            }
            if dns is not None:
                config["dns"] = dns
            config_path = root / "config.json"
            config_path.write_text(json.dumps(config), encoding="ascii")
            config_path.chmod(0o600)
            if runtime_log_dir is None:
                log = (root / "runtime.log").open("wb")
            else:
                runtime_log_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
                log = tempfile.NamedTemporaryFile(prefix=f"{transport}-", suffix=".log",
                                                  dir=runtime_log_dir, mode="wb", delete=False)
                print(f"Private runtime log: {log.name}; do not publish raw logs", flush=True)
            with log:
                process = subprocess.Popen([str(core), "run", "-format", "json", "-c", str(config_path)],
                                           stdout=log, stderr=subprocess.STDOUT)
                print(f"Test core pid={process.pid} socks_port={port}", flush=True)
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
                    fresh_targets = () if loopback_baseline else (
                        ("https://www.gstatic.com/generate_204", "204"),
                        ("https://cp.cloudflare.com/generate_204", "204"),
                        ("https://example.com/", "200")
                    )
                    for url, expected_status in fresh_targets:
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
                    if retained_probe is not None:
                        print(f"Retained HTTP/2 check: transport={transport}; SOCKS only, not Android TUN", flush=True)
                        command = [str(retained_probe), "--socks-port", str(port), "--idle-seconds", str(idle_seconds)]
                        if loopback_baseline:
                            command.append("--loopback-baseline")
                        response = subprocess.run(
                            command,
                            timeout=idle_seconds + 300
                        )
                        if response.returncode != 0:
                            raise RuntimeError(f"{transport} retained HTTP/2 acceptance failed")
                    if retained_curl:
                        check_retained_curl(port)
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
    parser.add_argument("bundle", type=Path, nargs="?", help="Private current Codespace bundle; never publish it")
    parser.add_argument("--live", action="store_true", help="Consent to authenticated Internet traffic")
    parser.add_argument("--retained-probe", type=Path, help="Compiled xray_retained_http_check.go helper")
    parser.add_argument("--idle-seconds", type=int, default=70, help="Retained check idle interval (0-600)")
    parser.add_argument("--transport", choices=("all", "xhttp", "ws"), default="all",
                        help="Test all exported transports or isolate one failure")
    parser.add_argument("--origin-config", type=Path,
                        help="Isolate SOCKS-to-origin forwarding using the live server's freedom outbound and DNS")
    parser.add_argument("--origin-keepalive-idle", type=int,
                        help="Test-only origin keepalive-idle override (1-600 seconds); server config is not modified")
    parser.add_argument("--origin-disable-fast-open", action="store_true",
                        help="Disable TFO in the isolated origin test only; server config is not modified")
    parser.add_argument("--retained-curl", action="store_true",
                        help="Independent curl HTTP/2 reuse check with requests one minute apart")
    parser.add_argument("--loopback-baseline", action="store_true",
                        help="Use owned HTTP/2 loopback destinations instead of Internet endpoints; requires origin and retained probe")
    parser.add_argument("--runtime-log-dir", type=Path,
                        help="Save private isolated-core debug logs for failure investigation; never publish them")
    arguments = parser.parse_args()
    if not arguments.live:
        parser.error("This test requires --live and a running private server")
    if not 0 <= arguments.idle_seconds <= 600:
        parser.error("--idle-seconds must be from 0 to 600")
    if arguments.loopback_baseline and (
        arguments.origin_config is None or arguments.retained_probe is None or arguments.retained_curl
    ):
        parser.error("--loopback-baseline requires --origin-config and --retained-probe, without --retained-curl")
    if arguments.origin_config is not None:
        if arguments.bundle is not None or arguments.transport != "all":
            parser.error("--origin-config cannot be combined with a bundle or transport override")
        if arguments.origin_keepalive_idle is not None and not 1 <= arguments.origin_keepalive_idle <= 600:
            parser.error("--origin-keepalive-idle must be from 1 to 600")
    elif arguments.bundle is None:
        parser.error("Supply a private bundle or --origin-config")
    elif arguments.origin_keepalive_idle is not None or arguments.origin_disable_fast_open:
        parser.error("Origin socket overrides require --origin-config")
    try:
        retained_probe = arguments.retained_probe.resolve(strict=True) if arguments.retained_probe else None
        transports = ("xhttp", "ws") if arguments.transport == "all" else (arguments.transport,)
        bundle = arguments.bundle.resolve(strict=True) if arguments.bundle else None
        origin = arguments.origin_config.resolve(strict=True) if arguments.origin_config else None
        runtime_log_dir = arguments.runtime_log_dir.resolve() if arguments.runtime_log_dir else None
        validate(arguments.xray.resolve(strict=True), bundle, retained_probe=retained_probe,
                 idle_seconds=arguments.idle_seconds, transports=transports, origin_config=origin,
                 origin_keepalive_idle=arguments.origin_keepalive_idle, runtime_log_dir=runtime_log_dir,
                 retained_curl=arguments.retained_curl, origin_disable_fast_open=arguments.origin_disable_fast_open,
                 loopback_baseline=arguments.loopback_baseline)
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"FAIL: {error}\n")


if __name__ == "__main__":
    main()

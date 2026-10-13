#!/usr/bin/env python3
"""Opt-in generated server/client acceptance, without Android or GitHub's public edge."""

import argparse
from contextlib import ExitStack
import copy
import json
from pathlib import Path
import socket
import subprocess
import tempfile
from urllib.parse import urlsplit, urlunsplit

from xray_authenticated_transport_check import outbound, running_core, validate as validate_client


PROFILES = ("balanced", "low_latency", "streaming", "unstable_mobile", "low_overhead", "max_throughput")


def prepare_server(template, ports, certificate, key):
    server = copy.deepcopy(template)
    direct = next((item for item in server.get("outbounds", []) if item.get("tag") == "direct"), None)
    if direct is None or direct.get("protocol") != "freedom" or \
       direct.get("streamSettings", {}).get("sockopt", {}).get("tcpFastOpen") is not False:
        raise ValueError("Use a generated fixture with default origin TFO off")
    listeners = []
    for inbound in server.get("inbounds", []):
        if inbound.get("protocol") != "vless":
            continue
        stream = inbound.get("streamSettings", {})
        transport = stream.get("network")
        if transport not in ports or any(item["streamSettings"]["network"] == transport for item in listeners):
            raise ValueError("Fixture has unsupported or duplicate VLESS transports")
        inbound["listen"], inbound["port"] = "127.0.0.1", ports[transport]
        # Local TLS replaces edge TLS termination for this diagnostic only.
        stream["security"] = "tls"
        stream["tlsSettings"] = {
            "alpn": ["h2", "http/1.1"],
            "certificates": [{"certificateFile": str(certificate), "keyFile": str(key), "oneTimeLoading": True}]
        }
        listeners.append(inbound)
    if {item["streamSettings"]["network"] for item in listeners} != set(ports):
        raise ValueError("Generated fixture must include both requested listeners")
    server["inbounds"] = listeners
    server["log"] = {"loglevel": "warning", "access": "none"}
    return server


def prepare_bundle(template, ports, server):
    if template.get("schema_version") != 2 or not isinstance(template.get("configs"), list):
        raise ValueError("Use a generated schema-2 fixture bundle")
    bundle = copy.deepcopy(template)
    links = []
    seen = set()
    for link in bundle["configs"]:
        parsed = outbound(link)
        transport = parsed["streamSettings"]["network"]
        target = parsed["settings"]["vnext"][0]
        identifiers = {client["id"] for inbound in server["inbounds"]
                       if inbound.get("protocol") == "vless" and
                       inbound.get("streamSettings", {}).get("network") == transport
                       for client in inbound["settings"]["clients"]}
        if transport not in ports or target["users"][0]["id"] not in identifiers:
            raise ValueError("Fixture link and server credentials/transports do not match")
        if transport in seen:
            raise ValueError("Fixture bundle has duplicate transports")
        seen.add(transport)
        uri = urlsplit(link)
        links.append(urlunsplit(uri._replace(netloc=f"{uri.username}@127.0.0.1:{ports[transport]}")))
    if seen != set(ports):
        raise ValueError("Fixture bundle must include every server transport")
    bundle["configs"] = links
    return bundle


def allow_owned_origins(template, ports):
    if len(ports) != 3 or len(set(ports)) != 3 or any(type(port) is not int or not 1024 <= port <= 65535 for port in ports):
        raise ValueError("Owned HTTPS fixture requires three distinct non-privileged ports")
    server = copy.deepcopy(template)
    # Keep the production private-IP block; allow only this fixture's origins.
    server.setdefault("routing", {}).setdefault("rules", []).insert(0, {
        "type": "field", "network": "tcp", "ip": ["127.0.0.1/32"],
        "port": ",".join(map(str, ports)), "outboundTag": "direct"
    })
    direct = next((item for item in server.get("outbounds", [])
                   if item.get("tag") == "direct" and item.get("protocol") == "freedom"), None)
    if direct is None:
        raise ValueError("Owned HTTPS fixture requires the managed direct outbound")
    direct.setdefault("settings", {}).setdefault("finalRules", []).insert(0, {
        "action": "allow", "network": "tcp", "ip": ["127.0.0.1/32"], "port": ",".join(map(str, ports))
    })
    return server


def validate(core, helper, panel, profile, transports, idle_seconds, loopback_baseline=False, runtime_log_dir=None):
    with tempfile.TemporaryDirectory(prefix="g2ray-server-pair-") as directory:
        root = Path(directory)
        fixtures = root / "generated"
        fixtures.mkdir(mode=0o700)
        subprocess.run(["bash", str(panel), str(core), "--fixture-dir", str(fixtures)], check=True, timeout=180)
        certificate, key = root / "fixture-ca.pem", root / "fixture-key.pem"
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-nodes", "-days", "1",
            "-subj", "/CN=G2Ray owned acceptance fixture",
            "-addext", "subjectAltName=DNS:validation-space-443.app.github.dev,DNS:validation-space-8443.app.github.dev",
            "-addext", "basicConstraints=critical,CA:TRUE", "-keyout", str(key), "-out", str(certificate)
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=20)
        key.chmod(0o600)
        with ExitStack() as reservations:
            ports = {}
            for transport in ("xhttp", "ws"):
                listener = reservations.enter_context(socket.socket())
                listener.bind(("127.0.0.1", 0))
                ports[transport] = listener.getsockname()[1]
            origin_ports = []
            if loopback_baseline:
                for _ in range(3):
                    listener = reservations.enter_context(socket.socket())
                    listener.bind(("127.0.0.1", 0))
                    origin_ports.append(listener.getsockname()[1])
        template = json.loads((fixtures / f"{profile}-enabled.json").read_text(encoding="utf-8"))
        server = prepare_server(template, ports, certificate, key)
        if origin_ports:
            server = allow_owned_origins(server, origin_ports)
        if runtime_log_dir is not None:
            server["log"]["loglevel"] = "debug"
            runtime_log_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        bundle = prepare_bundle(json.loads((fixtures / f"{profile}.bundle.json").read_text(encoding="utf-8")), ports, server)
        server_path, bundle_path = root / "server.json", root / "bundle.json"
        server_path.write_text(json.dumps(server), encoding="ascii")
        bundle_path.write_text(json.dumps(bundle), encoding="ascii")
        server_path.chmod(0o600)
        bundle_path.chmod(0o600)
        subprocess.run([str(core), "run", "-test", "-format", "json", "-c", str(server_path)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=15)
        origins = "owned HTTP/2 origins" if loopback_baseline else "real Internet origins"
        print(f"PAIR SCOPE: generated profile={profile}; local TLS; {origins}; no public edge or Android", flush=True)
        log = (root / "server.log").open("wb") if runtime_log_dir is None else \
            tempfile.NamedTemporaryFile(prefix="server-pair-", suffix=".log", dir=runtime_log_dir, mode="wb", delete=False)
        if runtime_log_dir is not None:
            print(f"Private server runtime log: {log.name}; do not publish raw logs", flush=True)
        with log, running_core(core, server_path, ports.values(), log):
            validate_client(core, bundle_path, retained_probe=helper, idle_seconds=idle_seconds,
                            transports=transports, fixture_ca=certificate, loopback_baseline=loopback_baseline,
                            loopback_origin_ports=origin_ports, runtime_log_dir=runtime_log_dir)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xray", type=Path, help="Verified pinned Linux Xray binary")
    parser.add_argument("retained_probe", type=Path, help="Compiled xray_retained_http_check.go helper")
    parser.add_argument("--panel-fixture", type=Path, default=Path(__file__).with_name("g2ray_config_validation.sh"))
    parser.add_argument("--profile", choices=PROFILES, default="low_latency")
    parser.add_argument("--transport", choices=("all", "xhttp", "ws"), default="all")
    parser.add_argument("--idle-seconds", type=int, default=310)
    parser.add_argument("--live", action="store_true", help="Consent to authenticated real HTTPS test traffic")
    parser.add_argument("--loopback-baseline", action="store_true", help="Use only owned loopback HTTPS origins, without Internet traffic")
    parser.add_argument("--runtime-log-dir", type=Path, help="Save private owned-core debug logs for diagnosis; never publish raw logs")
    arguments = parser.parse_args()
    if (not arguments.live and not arguments.loopback_baseline) or not 0 <= arguments.idle_seconds <= 600:
        parser.error("Supply --live or --loopback-baseline, and --idle-seconds from 0 to 600")
    try:
        transports = ("xhttp", "ws") if arguments.transport == "all" else (arguments.transport,)
        validate(arguments.xray.resolve(strict=True), arguments.retained_probe.resolve(strict=True),
                 arguments.panel_fixture.resolve(strict=True), arguments.profile, transports, arguments.idle_seconds,
                 arguments.loopback_baseline, arguments.runtime_log_dir.resolve() if arguments.runtime_log_dir else None)
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"FAIL: {error}\n")


if __name__ == "__main__":
    main()

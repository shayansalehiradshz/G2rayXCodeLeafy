#!/usr/bin/env python3
"""Validate deterministic client-config rejection with an isolated Xray binary."""

import argparse
import copy
import json
from pathlib import Path
import subprocess
import tempfile


def validate(core):
    config = {
        "log": {"loglevel": "warning"},
        "inbounds": [{"listen": "127.0.0.1", "port": 10808, "protocol": "socks",
                      "settings": {"udp": False}}],
        "outbounds": [{
            "protocol": "vless",
            "settings": {"vnext": [{"address": "203.0.113.1", "port": 443,
                                     "users": [{"id": "00000000-0000-4000-8000-000000000001",
                                                "encryption": "none"}]}]},
            "streamSettings": {
                "network": "xhttp", "security": "tls",
                "tlsSettings": {"serverName": "validation.example", "alpn": ["h2"]},
                "xhttpSettings": {"host": "validation.example", "path": "/", "mode": "packet-up",
                                  "extra": {"scMaxEachPostBytes": 60000,
                                            "xmux": {"maxConnections": 3}}}
            }
        }]
    }
    cases = [("valid client", config, True)]
    invalid = copy.deepcopy(config)
    invalid["outbounds"][0]["streamSettings"]["xhttpSettings"]["extra"]["xmux"]["maxConnections"] = "invalid"
    cases.append(("invalid numeric XHTTP extra", invalid, False))
    invalid = copy.deepcopy(config)
    invalid["outbounds"][0]["streamSettings"]["tlsSettings"]["alpn"] = 42
    cases.append(("invalid TLS ALPN type", invalid, False))

    with tempfile.TemporaryDirectory(prefix="g2ray-client-validation-") as directory:
        path = Path(directory) / "client.json"
        for name, candidate, expected_success in cases:
            path.write_text(json.dumps(candidate), encoding="ascii")
            result = subprocess.run(
                [str(core), "run", "-test", "-format", "json", "-c", str(path)],
                capture_output=True, text=True, timeout=15
            )
            output = result.stdout + result.stderr
            if expected_success:
                if result.returncode != 0:
                    raise RuntimeError(f"Valid config was rejected: {output.strip()}")
            elif result.returncode == 0 or "infra/conf" not in output:
                raise RuntimeError(f"Expected a config-loader rejection: {output.strip()}")
            print(f"PASS: {name}; accepted={result.returncode == 0}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xray", type=Path, help="Verified Xray executable; no listeners are started")
    arguments = parser.parse_args()
    try:
        validate(arguments.xray.resolve(strict=True))
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"FAIL: {error}\n")


if __name__ == "__main__":
    main()

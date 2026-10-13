#!/usr/bin/env python3
"""Loopback-only XHTTP queue-expiry regression for a supplied Xray binary."""

import argparse
import http.client
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import uuid


def request(port, path, method="OPTIONS", body=None, timeout=5):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        connection.request(method, path + "?x_padding=X", body=body)
        response = connection.getresponse()
        response.read()
        return response.status
    finally:
        connection.close()


def validate(core):
    version = subprocess.check_output([str(core), "version"], text=True).splitlines()[0]
    print(version, flush=True)
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="g2ray-queue-validation-") as directory:
        root = Path(directory)
        config = {
            "log": {"loglevel": "warning"},
            "inbounds": [{
                "listen": "127.0.0.1", "port": port, "protocol": "vless",
                "settings": {"clients": [{"id": str(uuid.uuid4())}], "decryption": "none"},
                "streamSettings": {
                    "network": "xhttp",
                    "xhttpSettings": {
                        "path": "/queue-validation/", "mode": "packet-up",
                        "scMaxBufferedPosts": 2, "scMaxEachPostBytes": 64,
                        "xPaddingBytes": "1-1"
                    }
                }
            }],
            "outbounds": [{"protocol": "blackhole"}]
        }
        config_path = root / "config.json"
        config_path.write_text(json.dumps(config), encoding="ascii")
        subprocess.run([str(core), "run", "-test", "-format", "json", "-c", str(config_path)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        with (root / "runtime.log").open("wb") as log:
            process = subprocess.Popen([str(core), "run", "-format", "json", "-c", str(config_path)],
                                       stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 5
                while True:
                    if process.poll() is not None:
                        raise RuntimeError("Fixture core exited before opening its loopback listener")
                    try:
                        if request(port, "/queue-validation/") == 200:
                            break
                    except OSError:
                        pass
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Fixture loopback listener did not become ready")
                    time.sleep(0.05)

                session = str(uuid.uuid4())
                started = time.monotonic()
                for sequence in range(2):
                    status = request(port, f"/queue-validation/{session}/{sequence}", "POST", b"x")
                    if status != 200:
                        raise RuntimeError(f"Queue-fill POST {sequence} returned HTTP {status}")
                try:
                    status = request(port, f"/queue-validation/{session}/2", "POST", b"x", timeout=35)
                except TimeoutError:
                    elapsed = time.monotonic() - started
                    raise RuntimeError(f"Expired saturated queue still blocked after {elapsed:.1f}s") from None
                elapsed = time.monotonic() - started
                if status != 500 or not 28 <= elapsed <= 35:
                    raise RuntimeError(f"Unexpected queue expiration: HTTP {status} after {elapsed:.1f}s")
                print(f"PASS: expired saturated upload queue releases pending POST: HTTP {status}, {elapsed:.1f}s",
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
    parser.add_argument("xray", type=Path, help="Separately checksum-verified Xray executable")
    arguments = parser.parse_args()
    core = arguments.xray.resolve(strict=True)
    try:
        validate(core)
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"FAIL: {error}\n")


if __name__ == "__main__":
    main()

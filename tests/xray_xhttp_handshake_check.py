#!/usr/bin/env python3
"""Loopback-only diagnostic: observe XHTTP VLESS handshake timeout on an owned core."""
import argparse
import http.client
import json
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import time
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("xray", type=Path)
    args = parser.parse_args()
    core = args.xray.resolve(strict=True)
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="g2ray-server-deadline-") as td:
        root = Path(td)
        conf = {
            "log": {"loglevel": "warning"},
            "policy": {"levels": {"0": {"handshake": 1}}},
            "inbounds": [{
                "listen": "127.0.0.1", "port": port, "protocol": "vless",
                "settings": {"clients": [{"id": str(uuid.uuid4())}], "decryption": "none"},
                "streamSettings": {"network": "xhttp", "xhttpSettings": {
                    "path": "/deadline/", "mode": "packet-up", "xPaddingBytes": "1-1"
                }}
            }],
            "outbounds": [{"protocol": "blackhole"}]
        }
        config = root / "config.json"
        config.write_text(json.dumps(conf), encoding="ascii")
        subprocess.run([str(core), "run", "-test", "-format", "json", "-c", str(config)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        log = root / "xray.log"
        with log.open("wb") as output:
            proc = subprocess.Popen([str(core), "run", "-format", "json", "-c", str(config)],
                                    stdout=output, stderr=subprocess.STDOUT)
            try:
                ready_deadline = time.monotonic() + 10
                while True:
                    if proc.poll() is not None:
                        raise RuntimeError("Fixture core exited during startup")
                    try:
                        with socket.create_connection(("127.0.0.1", port), timeout=0.25):
                            break
                    except OSError:
                        if time.monotonic() >= ready_deadline:
                            raise RuntimeError("Fixture listener did not start")
                        time.sleep(0.05)
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                session = str(uuid.uuid4())
                conn.request("GET", f"/deadline/{session}?x_padding=X")
                resp = conn.getresponse()
                if resp.status != 200:
                    raise RuntimeError(f"Synthetic XHTTP GET returned {resp.status}")
                started = time.monotonic()
                try:
                    body = resp.read(1)
                except TimeoutError:
                    elapsed = time.monotonic() - started
                    print(f"EXPECTED FAILURE: response stayed open {elapsed:.2f}s with 1s VLESS handshake policy")
                    proc.send_signal(signal.SIGQUIT)
                    proc.wait(timeout=10)
                    output.flush()
                    stack = log.read_text(errors="replace")
                    for phrase in ("proxy/vless/inbound.(*Handler).Process", "splithttp.(*uploadQueue).Read"):
                        print(f"stack contains {phrase}: {phrase in stack}")
                    if "proxy/vless/inbound.(*Handler).Process" not in stack or "splithttp.(*uploadQueue).Read" not in stack:
                        raise RuntimeError("Could not verify the waiting VLESS/XHTTP stack")
                    return 1
                finally:
                    conn.close()
                elapsed = time.monotonic() - started
                if body or elapsed > 2.5:
                    raise RuntimeError(f"Unexpected response before deadline acceptance: {body!r} after {elapsed:.2f}s")
                print(f"PASS: uncompleted VLESS handshake closed in {elapsed:.2f}s")
                return 0
            finally:
                if proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())

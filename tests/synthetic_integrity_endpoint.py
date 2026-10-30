#!/usr/bin/env python3
"""Temporary, authenticated synthetic-data fixture. Never proxy arbitrary URLs."""
import argparse
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
from urllib.parse import parse_qs, urlsplit

CHUNK = bytes((i * 31 + 17) % 251 for i in range(32768))
MAX_BYTES = 8 * 1024 * 1024


def digest_for_length(length):
    digest = hashlib.sha256()
    while length:
        data = CHUNK[:min(length, len(CHUNK))]
        digest.update(data)
        length -= len(data)
    return digest.hexdigest()


class IntegrityHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    token = ""

    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, *_args):
        # Authorization headers, physical IPs and request queries are never logged.
        pass

    def authenticated(self):
        return hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + self.token)

    def reply(self, status, value):
        data = json.dumps(value, separators=(",", ":")).encode("ascii")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def reject(self, status):
        self.close_connection = True
        self.reply(status, {"error": "fixture request rejected"})

    def do_GET(self):
        if not self.authenticated():
            self.reject(403)
            return
        url = urlsplit(self.path)
        query = parse_qs(url.query)
        values = query.get("bytes", [])
        if url.path != "/download" or len(values) != 1 or not values[0].isdigit():
            self.reject(400)
            return
        length = int(values[0])
        if not 1 <= length <= MAX_BYTES:
            self.reject(413)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(length))
        self.send_header("X-Content-SHA256", digest_for_length(length))
        self.end_headers()
        remaining = length
        while remaining:
            chunk = CHUNK[:min(remaining, len(CHUNK))]
            self.wfile.write(chunk)
            remaining -= len(chunk)

    def do_POST(self):
        if not self.authenticated():
            self.reject(403)
            return
        length = self.headers.get("Content-Length", "")
        expected = self.headers.get("X-Content-SHA256", "")
        if (self.path != "/upload" or self.headers.get("Transfer-Encoding") or
                not length.isdigit() or not re.fullmatch(r"[a-f0-9]{64}", expected)):
            self.reject(400)
            return
        length = int(length)
        if not 1 <= length <= MAX_BYTES:
            self.reject(413)
            return
        digest = hashlib.sha256()
        remaining = length
        while remaining:
            data = self.rfile.read(min(remaining, 32768))
            if not data:
                self.reject(400)
                return
            digest.update(data)
            remaining -= len(data)
        actual = digest.hexdigest()
        matched = hmac.compare_digest(actual, expected)
        self.reply(200 if matched else 422, {"bytes": length, "sha256": actual, "matched": matched})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--token-file", required=True, type=Path)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Use a non-privileged port")
    token = args.token_file.read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[a-f0-9]{64}", token):
        parser.error("Token file must contain 32 random bytes encoded as lowercase hex")
    IntegrityHandler.token = token
    with ThreadingHTTPServer(("127.0.0.1", args.port), IntegrityHandler) as server:
        print("Synthetic integrity fixture ready on loopback", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()

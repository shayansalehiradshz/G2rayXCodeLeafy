import hashlib
import http.client
import json
from pathlib import Path
import sys
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from synthetic_integrity_endpoint import CHUNK, IntegrityHandler, digest_for_length
from http.server import ThreadingHTTPServer


class IntegrityEndpointTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        IntegrityHandler.token = "a" * 64
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), IntegrityHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_authentication_required(self):
        status, _, _ = self.request("GET", "/download?bytes=1")
        self.assertEqual(status, 403)

    def test_exact_download_length_and_digest(self):
        size = len(CHUNK) + 13
        status, headers, data = self.request("GET", f"/download?bytes={size}",
                                           headers={"Authorization": "Bearer " + IntegrityHandler.token})
        self.assertEqual(status, 200)
        self.assertEqual(data, CHUNK + CHUNK[:13])
        self.assertEqual(headers["X-Content-SHA256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(digest_for_length(size), hashlib.sha256(data).hexdigest())

    def test_upload_proves_received_bytes(self):
        data = b"synthetic payload\x00" * 100
        digest = hashlib.sha256(data).hexdigest()
        status, _, body = self.request("POST", "/upload", data,
            {"Authorization": "Bearer " + IntegrityHandler.token, "X-Content-SHA256": digest})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"bytes": len(data), "sha256": digest, "matched": True})

    def test_wrong_payload_digest_is_rejected(self):
        status, _, body = self.request("POST", "/upload", b"truncated",
            {"Authorization": "Bearer " + IntegrityHandler.token, "X-Content-SHA256": "0" * 64})
        self.assertEqual(status, 422)
        self.assertFalse(json.loads(body)["matched"])

    def test_large_and_ambiguous_requests_rejected(self):
        headers = {"Authorization": "Bearer " + IntegrityHandler.token}
        for path, expected in (("/download?bytes=8388609", 413), ("/download?bytes=1&bytes=2", 400), ("/other?bytes=1", 400)):
            self.assertEqual(self.request("GET", path, headers=headers)[0], expected)


if __name__ == "__main__":
    unittest.main()

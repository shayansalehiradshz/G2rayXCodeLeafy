"""No-network tests of private-bundle fidelity and isolated diagnostic channels."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import urlencode

from xray_authenticated_transport_check import outbound, build_channels, check_retained_curl


def link(transport="xhttp", **overrides):
    query = {"security": "tls", "sni": "validation.example", "host": "validation.example",
             "fp": "chrome", "type": transport, "path": "/", "alpn": "h2", "mode": "packet-up"}
    query.update(overrides)
    return "vless://00000000-0000-4000-8000-000000000001@203.0.113.1:443?" + urlencode(query)


class AuthenticatedTransportCheckTest(unittest.TestCase):
    def test_preserves_extra_fragment_and_unsafe_cipher_settings(self):
        mask = {"tcp": [{"type": "fragment", "settings": {"packets": "tlshello"}}]}
        extra = {"scMaxEachPostBytes": 60000, "xmux": {"hKeepAlivePeriod": 20}}
        result = outbound(link(fp="unsafe", fm=json.dumps(mask), cs="TLS_AES_128_GCM_SHA256", extra=json.dumps(extra)))
        stream = result["streamSettings"]
        self.assertEqual(mask, stream["finalmask"])
        self.assertEqual(extra, stream["xhttpSettings"]["extra"])
        self.assertEqual("TLS_AES_128_GCM_SHA256", stream["tlsSettings"]["cipherSuites"])
        self.assertEqual(30000, stream["sockopt"]["tcpUserTimeout"])

    def test_browser_fingerprint_does_not_claim_custom_cipher_override(self):
        result = outbound(link(cs="TLS_AES_128_GCM_SHA256"))
        self.assertNotIn("cipherSuites", result["streamSettings"]["tlsSettings"])

    def test_nonobject_extra_or_mask_is_rejected(self):
        for parameter in ("extra", "fm"):
            with self.subTest(parameter=parameter), self.assertRaises(ValueError):
                outbound(link(**{parameter: "[]"}))

    def test_explicit_missing_transport_is_not_a_false_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bundle.json"
            path.write_text(json.dumps({"schema_version": 2, "configs": [link()]}), encoding="ascii")
            with self.assertRaises(ValueError):
                build_channels(path, ("ws",))

    def test_origin_baseline_reads_only_freedom_outbound_and_dns(self):
        direct = {"protocol": "freedom", "tag": "direct", "settings": {"domainStrategy": "UseIPv4"},
                  "streamSettings": {"sockopt": {"tcpKeepAliveIdle": 30, "tcpKeepAliveInterval": 15}}}
        dns = {"servers": ["localhost"], "queryStrategy": "UseIPv4"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "server.json"
            path.write_text(json.dumps({"outbounds": [direct], "dns": dns,
                                        "inbounds": [{"private_fixture_value": "not copied"}]}), encoding="ascii")
            channels = build_channels(None, (), origin_config=path)
            self.assertEqual([("origin-baseline", direct, dns)], channels)
            self.assertNotIn("private_fixture_value", json.dumps(channels))

    def test_origin_baseline_cannot_silently_use_another_outbound(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "server.json"
            path.write_text('{"outbounds": [{"protocol": "blackhole", "tag": "direct"}]}', encoding="ascii")
            with self.assertRaises(ValueError):
                build_channels(None, (), origin_config=path)

    def test_origin_keepalive_experiment_changes_only_the_copy(self):
        original = {"outbounds": [{"protocol": "freedom", "tag": "direct",
                                   "streamSettings": {"sockopt": {"tcpKeepAliveIdle": 30,
                                                                 "tcpKeepAliveInterval": 15}}}]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "server.json"
            text = json.dumps(original)
            path.write_text(text, encoding="ascii")
            channels = build_channels(None, (), origin_config=path, origin_keepalive_idle=15,
                                      origin_disable_fast_open=True)
            self.assertEqual(15, channels[0][1]["streamSettings"]["sockopt"]["tcpKeepAliveIdle"])
            self.assertEqual(15, channels[0][1]["streamSettings"]["sockopt"]["tcpKeepAliveInterval"])
            self.assertFalse(channels[0][1]["streamSettings"]["sockopt"]["tcpFastOpen"])
            self.assertEqual(text, path.read_text(encoding="ascii"))

    def test_independent_curl_requires_two_fast_http2_responses(self):
        for overrides in ({}, {"http_code": 200}, {"http_version": "1.1"}, {"time_total": 6.001}):
            records = [dict(http_code=204, http_version="2", time_total=0.1, num_connects=1),
                       dict(http_code=204, http_version="2", time_total=0.02, num_connects=0)]
            records[1].update(overrides)
            with self.subTest(overrides=overrides), patch(
                "xray_authenticated_transport_check.subprocess.run"
            ) as run:
                run.return_value.returncode = 0
                run.return_value.stdout = "\n".join(json.dumps(record) for record in records)
                if overrides:
                    with self.assertRaises(RuntimeError):
                        check_retained_curl(12345)
                else:
                    check_retained_curl(12345)
                command = run.call_args.args[0]
                self.assertEqual("1/m", command[command.index("--rate") + 1])
                self.assertEqual(2, command.count("https://www.gstatic.com/generate_204"))
                self.assertNotIn("--insecure", command)


if __name__ == "__main__":
    unittest.main()

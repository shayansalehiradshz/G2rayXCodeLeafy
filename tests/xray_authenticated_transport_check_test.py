"""No-network tests of private-bundle fidelity and isolated diagnostic channels."""

import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch
from urllib.parse import urlencode

from xray_authenticated_transport_check import outbound, build_channels, check_retained_curl, running_core, with_fixture_ca


def link(transport="xhttp", **overrides):
    query = {"security": "tls", "sni": "validation.example", "host": "validation.example",
             "fp": "chrome", "type": transport, "path": "/", "alpn": "h2", "mode": "packet-up"}
    query.update(overrides)
    return "vless://00000000-0000-4000-8000-000000000001@203.0.113.1:443?" + urlencode(query)


class AuthenticatedTransportCheckTest(unittest.TestCase):
    def test_ws_early_data_preserves_nested_path_query_and_tls_identity(self):
        path = "/ws?ed=2560&token=a%2Bb"
        stream = outbound(link(transport="ws", path=path))["streamSettings"]
        self.assertEqual(path, stream["wsSettings"]["path"])
        self.assertEqual("validation.example", stream["wsSettings"]["headers"]["Host"])
        self.assertEqual("validation.example", stream["tlsSettings"]["serverName"])
        self.assertFalse(stream["tlsSettings"].get("allowInsecure", False))
        self.assertNotIn("maxEarlyData", stream["wsSettings"])
        self.assertNotIn("earlyDataHeaderName", stream["wsSettings"])

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

    def test_fixture_ca_preserves_strict_tls_and_does_not_mutate_original(self):
        original = outbound(link().replace("@203.0.113.1:", "@127.0.0.1:"))
        result = with_fixture_ca(original, Path("fixture-ca.pem"))
        tls = result["streamSettings"]["tlsSettings"]
        self.assertFalse(tls["allowInsecure"])
        self.assertEqual("verify", tls["certificates"][0]["usage"])
        self.assertEqual("chrome", tls["fingerprint"])
        self.assertNotIn("certificates", original["streamSettings"]["tlsSettings"])

    def test_fixture_ca_cannot_modify_real_or_non_tls_destinations(self):
        with self.assertRaises(ValueError):
            with_fixture_ca(outbound(link()), Path("fixture-ca.pem"))
        candidate = outbound(link().replace("@203.0.113.1:", "@127.0.0.1:"))
        candidate["streamSettings"]["security"] = "none"
        with self.assertRaises(ValueError):
            with_fixture_ca(candidate, Path("fixture-ca.pem"))

    def test_owned_core_cleans_up_when_probe_body_fails(self):
        with patch("xray_authenticated_transport_check.subprocess.Popen") as popen, \
             patch("xray_authenticated_transport_check.socket.create_connection"):
            process = popen.return_value
            process.pid = 42
            process.poll.return_value = None
            with self.assertRaisesRegex(RuntimeError, "probe failed"):
                with running_core(Path("xray"), Path("fixture.json"), (12345,), None):
                    raise RuntimeError("probe failed")
            process.terminate.assert_called_once()
            process.wait.assert_called_once_with(timeout=5)

    def test_owned_core_reaps_startup_failure_and_term_resistant_process(self):
        with patch("xray_authenticated_transport_check.subprocess.Popen") as popen:
            process = popen.return_value
            process.poll.return_value = 1
            process.wait.side_effect = [subprocess.TimeoutExpired("fixture", 5), 0]
            with self.assertRaisesRegex(RuntimeError, "failed to start"):
                with running_core(Path("xray"), Path("fixture.json"), (12345,), None):
                    self.fail("Exited core was accepted")
            process.terminate.assert_called_once()
            process.kill.assert_called_once()
            self.assertEqual(2, process.wait.call_count)

    def test_owned_core_requires_ports_and_bounds_socket_readiness(self):
        with patch("xray_authenticated_transport_check.subprocess.Popen") as popen:
            with self.assertRaises(ValueError):
                with running_core(Path("xray"), Path("fixture.json"), (), None):
                    self.fail("Empty readiness was accepted")
            popen.assert_not_called()
        with patch("xray_authenticated_transport_check.subprocess.Popen") as popen, \
             patch("xray_authenticated_transport_check.time.monotonic", side_effect=[0, 5]):
            popen.return_value.poll.return_value = None
            with self.assertRaisesRegex(RuntimeError, "did not open"):
                with running_core(Path("xray"), Path("fixture.json"), (12345,), None):
                    self.fail("Expired readiness was accepted")
            popen.return_value.terminate.assert_called_once()


if __name__ == "__main__":
    unittest.main()

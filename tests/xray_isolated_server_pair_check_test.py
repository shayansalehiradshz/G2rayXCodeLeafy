"""No-network contract tests for isolated server/client acceptance."""

import json
from pathlib import Path
import unittest
from urllib.parse import urlencode, urlsplit

from xray_authenticated_transport_check import outbound
from xray_isolated_server_pair_check import allow_owned_origins, prepare_bundle, prepare_server


IDENTIFIER = "00000000-0000-4000-8000-000000000001"


def template():
    return {
        "policy": {"levels": {"0": {"connIdle": 900, "bufferSize": 512}}},
        "dns": {"servers": ["localhost"]},
        "inbounds": [
            {"protocol": "vless", "port": 443, "listen": "0.0.0.0", "settings": {"clients": [{"id": IDENTIFIER}]},
             "streamSettings": {"network": "xhttp", "xhttpSettings": {"path": "/", "mode": "auto"}}},
            {"protocol": "vless", "port": 8443, "listen": "0.0.0.0", "settings": {"clients": [{"id": IDENTIFIER}]},
             "streamSettings": {"network": "ws", "wsSettings": {"path": "/ws", "heartbeatPeriod": 30}}},
            {"protocol": "dokodemo-door", "listen": "127.0.0.1", "port": 10085}
        ],
        "outbounds": [{"protocol": "freedom", "tag": "direct",
                       "streamSettings": {"sockopt": {"tcpFastOpen": False, "tcpKeepAliveIdle": 30}}}]
    }


def bundle():
    return {"schema_version": 2, "configs": [
        f"vless://{IDENTIFIER}@fixture.example:443?" + urlencode({
            "security": "tls", "sni": "validation.example", "host": "validation.example", "type": transport,
            "fp": "chrome", "alpn": "h2" if transport == "xhttp" else "http/1.1",
            "path": "/" if transport == "xhttp" else "/ws", "mode": "packet-up",
            **({"extra": '{"scMaxEachPostBytes":60000,"xmux":{"hKeepAlivePeriod":20}}'} if transport == "xhttp" else {})
        }) for transport in ("xhttp", "ws")
    ]}


class IsolatedServerPairTest(unittest.TestCase):
    def test_only_owned_listeners_and_tls_are_changed(self):
        original = template()
        result = prepare_server(original, {"xhttp": 12345, "ws": 12346}, Path("ca.pem"), Path("key.pem"))
        self.assertEqual(2, len(result["inbounds"]))
        self.assertEqual([12345, 12346], [item["port"] for item in result["inbounds"]])
        self.assertTrue(all(item["listen"] == "127.0.0.1" for item in result["inbounds"]))
        self.assertEqual(original["policy"], result["policy"])
        self.assertEqual(original["outbounds"], result["outbounds"])
        self.assertEqual(original["dns"], result["dns"])
        self.assertEqual(3, len(original["inbounds"]))

    def test_actual_links_preserve_tuning_and_identity_when_only_dial_address_changes(self):
        source = bundle()
        ports = {"xhttp": 12345, "ws": 12346}
        result = prepare_bundle(source, ports, template())
        self.assertEqual("fixture.example", urlsplit(source["configs"][0]).hostname)
        for original_link, replacement in zip(source["configs"], result["configs"]):
            original, replaced = outbound(original_link), outbound(replacement)
            self.assertEqual(original["streamSettings"], replaced["streamSettings"])
            self.assertEqual(original["settings"]["vnext"][0]["users"], replaced["settings"]["vnext"][0]["users"])
            self.assertEqual("127.0.0.1", urlsplit(replacement).hostname)
            self.assertEqual(ports[replaced["streamSettings"]["network"]], urlsplit(replacement).port)

    def test_missing_transport_and_wrong_uuid_are_not_false_passes(self):
        invalid = template()
        invalid["inbounds"] = invalid["inbounds"][:1]
        with self.assertRaises(ValueError):
            prepare_server(invalid, {"xhttp": 12345, "ws": 12346}, Path("ca.pem"), Path("key.pem"))
        invalid_bundle = json.loads(json.dumps(bundle()))
        invalid_bundle["configs"][0] = invalid_bundle["configs"][0].replace(IDENTIFIER, "00000000-0000-4000-8000-000000000002")
        with self.assertRaises(ValueError):
            prepare_bundle(invalid_bundle, {"xhttp": 12345, "ws": 12346}, template())

    def test_server_pair_requires_the_conservative_origin_policy(self):
        invalid = template()
        invalid["outbounds"][0]["streamSettings"]["sockopt"]["tcpFastOpen"] = True
        with self.assertRaises(ValueError):
            prepare_server(invalid, {"xhttp": 12345, "ws": 12346}, Path("ca.pem"), Path("key.pem"))

    def test_missing_bundle_transport_cannot_skip_half_the_pair(self):
        incomplete = bundle()
        incomplete["configs"] = incomplete["configs"][:1]
        with self.assertRaises(ValueError):
            prepare_bundle(incomplete, {"xhttp": 12345, "ws": 12346}, template())

    def test_duplicate_transport_cannot_count_as_two_routes(self):
        duplicate = bundle()
        duplicate["configs"] = [duplicate["configs"][0]] * 2
        with self.assertRaises(ValueError):
            prepare_bundle(duplicate, {"xhttp": 12345, "ws": 12346}, template())

    def test_loopback_exception_is_narrow_and_preserves_private_destination_block(self):
        original = template()
        original["routing"] = {"rules": [{"type": "field", "ip": ["geoip:private"], "outboundTag": "block"}]}
        original["outbounds"][0]["settings"] = {"finalRules": [{"action": "block", "ip": ["geoip:private"]}]}
        result = allow_owned_origins(original, [12001, 12002, 12003])
        self.assertEqual(original["routing"]["rules"], result["routing"]["rules"][1:])
        self.assertEqual(1, len(original["routing"]["rules"]))
        exception = result["routing"]["rules"][0]
        self.assertEqual(["127.0.0.1/32"], exception["ip"])
        self.assertEqual("tcp", exception["network"])
        self.assertEqual("12001,12002,12003", exception["port"])
        final_rules = result["outbounds"][0]["settings"]["finalRules"]
        self.assertEqual(original["outbounds"][0]["settings"]["finalRules"], final_rules[1:])
        self.assertEqual("allow", final_rules[0]["action"])
        self.assertEqual(exception["ip"], final_rules[0]["ip"])
        self.assertEqual(exception["port"], final_rules[0]["port"])
        self.assertEqual("tcp", final_rules[0]["network"])
        self.assertEqual(original["outbounds"][0]["streamSettings"], result["outbounds"][0]["streamSettings"])
        for ports in ([12001], [12001, 12001, 12003], [443, 12002, 12003]):
            with self.assertRaises(ValueError):
                allow_owned_origins(original, ports)


if __name__ == "__main__":
    unittest.main()

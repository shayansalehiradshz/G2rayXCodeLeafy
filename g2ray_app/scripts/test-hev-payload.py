"""Linux engine fixture: a socketpair substitutes for TUN; no external traffic."""

import ctypes
import faulthandler
import select
import socket
import struct
import sys
import threading
import time


def checksum(data):
    if len(data) % 2:
        data += b"\0"
    total = sum(struct.unpack(f"!{len(data) // 2}H", data))
    while total >> 16:
        total = (total & 0xFFFF) + (total >> 16)
    return (~total) & 0xFFFF


def packet(seq, ack, flags, payload=b""):
    source = socket.inet_aton("198.18.0.2")
    destination = socket.inet_aton("203.0.113.1")
    tcp = struct.pack("!HHIIBBHHH", 40000, 443, seq, ack, 5 << 4, flags, 65535, 0, 0)
    pseudo = source + destination + struct.pack("!BBH", 0, 6, len(tcp) + len(payload))
    tcp = tcp[:16] + struct.pack("!H", checksum(pseudo + tcp + payload)) + tcp[18:]
    ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 40 + len(payload), 1, 0, 64, 6, 0, source, destination)
    ip = ip[:10] + struct.pack("!H", checksum(ip)) + ip[12:]
    return ip + tcp + payload


def receive_exact(connection, size):
    data = b""
    while len(data) < size:
        chunk = connection.recv(size - len(data))
        if not chunk:
            raise RuntimeError("SOCKS fixture closed early")
        data += chunk
    return data


def exercise(library, with_payload):
    local, tun = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
    socks = socket.socket()
    socks.bind(("127.0.0.1", 0))
    socks.settimeout(5)
    port = socks.getsockname()[1]
    payload = b"real-upstream-payload"
    server_errors = []

    def server():
        try:
            connection, _ = socks.accept()
            with connection:
                connection.settimeout(5)
                version, methods = receive_exact(connection, 2)
                assert version == 5
                receive_exact(connection, methods)
                connection.sendall(b"\x05\x00")
                version, command, _, address_type = receive_exact(connection, 4)
                assert (version, command) == (5, 1)
                if address_type == 1:
                    receive_exact(connection, 4)
                elif address_type == 4:
                    receive_exact(connection, 16)
                else:
                    receive_exact(connection, receive_exact(connection, 1)[0])
                receive_exact(connection, 2)
                connection.sendall(b"\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00")
                receive_exact(connection, 7)
                connection.sendall(payload)
        except Exception as error:
            server_errors.append(error)

    server_thread = None
    if with_payload:
        socks.listen(1)
        server_thread = threading.Thread(target=server, daemon=True)
        server_thread.start()
    config = (
        "tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\n"
        f"socks5:\n  address: 127.0.0.1\n  port: {port}\n"
        "misc:\n  log-level: error\n  tcp-read-write-timeout: 2000\n"
    ).encode()
    engine_results = []
    engine = threading.Thread(target=lambda: engine_results.append(
        library.hev_socks5_tunnel_main_from_str(config, len(config), tun.fileno())
    ), daemon=True)
    print(f"Starting HEV payload fixture: upstream_available={with_payload}", flush=True)
    engine.start()
    try:
        time.sleep(0.15)
        local.send(packet(1000, 0, 0x02))
        deadline = time.monotonic() + 5
        syn_ack = None
        while time.monotonic() < deadline:
            if not select.select([local], [], [], 0.1)[0]:
                continue
            candidate = local.recv(65535)
            offset = (candidate[0] & 15) * 4
            if candidate[offset + 13] & 0x12 == 0x12:
                syn_ack = candidate
                break
        assert syn_ack is not None, "HEV did not emit its local SYN-ACK"
        offset = (syn_ack[0] & 15) * 4
        server_sequence = struct.unpack_from("!I", syn_ack, offset + 4)[0]
        if with_payload:
            local.send(packet(1001, server_sequence + 1, 0x10))
            local.send(packet(1001, server_sequence + 1, 0x18, b"request"))
            received = bytearray()
            expected_sequence = server_sequence + 1
            closed_to_app = False
            while time.monotonic() < deadline and not closed_to_app:
                if not select.select([local], [], [], 0.05)[0]:
                    continue
                response = local.recv(65535)
                offset = (response[0] & 15) * 4
                sequence = struct.unpack_from("!I", response, offset + 4)[0]
                data_offset = offset + (response[offset + 12] >> 4) * 4
                data = response[data_offset:]
                flags = response[offset + 13]
                if data and sequence == expected_sequence:
                    received.extend(data)
                    expected_sequence += len(data)
                if flags & 0x01 and sequence + len(data) == expected_sequence:
                    expected_sequence += 1
                    closed_to_app = True
                local.send(packet(1008, expected_sequence, 0x10))
            assert not server_errors, server_errors
            assert bytes(received) == payload, "HEV lost or changed response bytes"
            assert library.hev_socks5_tunnel_tcp_payload_stats() == len(payload)
            print("PASS: genuine SOCKS response payload increments the upstream counter", flush=True)
            assert closed_to_app, "Upstream EOF did not reach the app as a TCP FIN within five seconds"
            print("PASS: upstream EOF closes the app-facing flow after delivering all payload", flush=True)
        else:
            counters = [ctypes.c_size_t() for _ in range(4)]
            library.hev_socks5_tunnel_stats(*(ctypes.byref(value) for value in counters))
            assert counters[3].value >= 44, counters[3].value
            assert library.hev_socks5_tunnel_tcp_payload_stats() == 0
            print(f"PASS: local SYN-ACK adds {counters[3].value} TUN RX bytes but zero upstream payload", flush=True)
    finally:
        library.hev_socks5_tunnel_quit()
        engine.join(5)
        local.close()
        tun.close()
        socks.close()
        if server_thread:
            server_thread.join(5)
        assert not engine.is_alive(), "HEV did not stop"
        assert engine_results == [0], engine_results


if __name__ == "__main__":
    faulthandler.enable()
    core = ctypes.CDLL(sys.argv[1])
    core.hev_socks5_tunnel_main_from_str.argtypes = [ctypes.c_char_p, ctypes.c_uint, ctypes.c_int]
    core.hev_socks5_tunnel_main_from_str.restype = ctypes.c_int
    core.hev_socks5_tunnel_quit.argtypes = []
    core.hev_socks5_tunnel_quit.restype = None
    core.hev_socks5_tunnel_stats.argtypes = [ctypes.POINTER(ctypes.c_size_t)] * 4
    core.hev_socks5_tunnel_stats.restype = None
    core.hev_socks5_tunnel_tcp_payload_stats.argtypes = []
    core.hev_socks5_tunnel_tcp_payload_stats.restype = ctypes.c_ulonglong
    exercise(core, with_payload=False)
    exercise(core, with_payload=True)
    exercise(core, with_payload=False)
    print("PASS: HEV restarts safely after half-open and established TCP sessions", flush=True)

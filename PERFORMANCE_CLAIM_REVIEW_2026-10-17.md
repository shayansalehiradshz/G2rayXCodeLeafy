# Performance claims: evidence and regression boundaries

Reviewed baseline: `4a44cab`. The four supplied reports refer to an older
revision and mix useful experimental ideas with incorrect protocol assumptions.
No production tuning, UUID, server process, Worker, or Android binary was changed
in this pass. The changes improve diagnostics and protocol regression coverage.

User priorities remain browsing/search response and page completion, social
refresh/video continuity, then cloud-AI response and streaming continuity.

## Claim Decisions

| Claim | Finding and decision |
| --- | --- |
| Server DNS `"localhost"` always queries a nonexistent `127.0.0.1:53` | **False.** It selects Xray's system-resolver backend. Removing it does not fix the alleged defect. Keep the current resolver list pending comparative measurements. |
| Completing an HTTP POST starts `uplinkOnly` and kills a delayed AI response | **False for normal HTTP/2 requests.** Request END_STREAM does not close TCP. `uplinkOnly` applies after downlink closes; `downlinkOnly` applies after uplink closes. A genuine TCP half-close is different and still subject to policy. Add a real-core delayed POST/SSE regression instead of changing timers on this premise. |
| TUN MTU 1500 inevitably becomes a 1542-byte outer packet; 1400 is the universal mobile optimum | **False premise; optimum unmeasured.** HEV and native gVisor terminate application TCP and forward streams, rather than append fixed tunnel overhead to every original TCP packet. Keep 1500 without a captured PMTU failure or a controlled improvement. |
| Set `tcpCongestion: bbr` and enable TFO on all sockets for guaranteed speed | **Inapplicable and regression-prone here.** The live kernel offers only reno/cubic. Unsupported per-socket congestion configuration can fail dialing. TFO is deliberately off after retained-flow testing; do not undo that measured fix. |
| BBR strictly requires the fq qdisc | **Overstated.** Linux 6.8 BBR documents internal TCP pacing when fq is unavailable, with resource tradeoffs. This does not make BBR available in the current Codespace. |
| `tcp_notsent_lowat=16384` prioritizes AI packets over video and guarantees sub-millisecond latency | **False.** It limits unsent queue/write readiness, not application priority or reordering inside TCP. No representative improvement was measured. |
| Disabling slow-start-after-idle and increasing global buffers are verified Codespaces optimizations | **Unproven and not applicable to this read-only sysctl mount.** Slow-start-after-idle depends on RTO, not a universal 200 ms reset. Global buffers, TIME_WAIT reuse and orphan FIN_WAIT_2 lifetime are not interchangeable application-latency controls. No sysctl writes attempted. |
| Xray `bufferSize=512` is a TCP window/BDP bottleneck; use 1024/2048 everywhere | **Incorrect equivalence.** This is internal per-request buffering. Larger buffers can trade memory/backpressure for throughput; they do not establish lower latency. Existing streaming/max-throughput profiles already allow measured comparisons. Keep the explicit 512 KiB client and balanced defaults. |
| `xmux.maxConnections=3` permits only three HTTP requests | **False.** Connection pooling and logical request concurrency differ. The rebuttal's exact 300-stream capacity is also unsupported; peer limits vary. Neither 16 connections nor a 2 ms POST minimum has been shown faster on this path. Keep current tuning. |
| DNS `UseIP` automatically selects the fastest IPv4/IPv6 path | **False.** DNS family selection is not a fastest-route benchmark. The current freedom outbound separately uses IPv4; the live host has no IPv6 default route. This observation is not proof that all Codespaces can never support IPv6. Keep current egress behavior pending end-to-end dual-stack acceptance. |
| FakeDNS default-on guarantees 0.2 ms DNS and removes 250-500 ms from every first visit | **Unmeasured generalization.** The app already supports opt-in FakeDNS. Synthetic answers can remove some classic DNS round trips, but not app-owned DoH, all cache effects, or server-side resolution. Keep opt-in until device/engine/restart acceptance supports a default change. |
| All carrier NAT mappings expire in 20-30 seconds; 15/5 keepalive, 10-second user timeout and HEV 60 seconds are universally better | **Unproven.** More probes have battery costs; shortening the HEV read/write timeout can break valid silent streams. TCP user timeout bounds unacknowledged-data conditions, not every silent AI response. Keep existing 25/15 client keepalive, 30-second user timeout and 300-second HEV timeout. |
| Blocking UDP/443 guarantees HTTP/2 fallback in less than 10 ms | **Unproven.** Fallback is application-dependent; a blackhole is not an immediate reject. The existing QUIC tradeoff remains, without promising universal UDP/app coverage. VLESS can carry UDP payload over TCP; absence of public UDP ingress does not forbid all proxied UDP. |
| Rotate UUID to apply speed settings, or use bare `generate` to preserve it | **Wrong operational advice.** `generate --new-uuid` invalidates old links deliberately. With an existing config, bare `generate` requires an explicit choice. The current preservation command is `generate --keep-uuid`; no regeneration or restart was needed here. |

The supplied numerical improvements, exact carrier timeout assumptions, fixed
peer stream limits, guaranteed 429/503 outcomes and zero-regression claims have
no supporting measurements in those reports. Existing static checks passing is
not evidence that a proposed, unapplied settings change is safe or faster.
Normal video delivery is bursty; a perfectly flat speed graph is not the goal.

## Source Checks

Use pinned implementation for behavior, rather than assuming the newest docs
describe an older binary exactly:

- [Xray 26.7.28 local DNS backend](https://github.com/XTLS/Xray-core/blob/v26.7.28/app/dns/nameserver_local.go)
  and [system resolver client](https://github.com/XTLS/Xray-core/blob/v26.7.28/features/dns/localdns/client.go).
- [Xray policy definitions](https://xtls.github.io/en/config/policy.html),
  [pinned VLESS inbound](https://github.com/XTLS/Xray-core/blob/v26.7.28/proxy/vless/inbound/inbound.go),
  [outbound](https://github.com/XTLS/Xray-core/blob/v26.7.28/proxy/vless/outbound/outbound.go),
  and [HTTP/2 stream semantics](https://www.rfc-editor.org/rfc/rfc9113.html#name-stream-states).
- [Native TUN TCP handling](https://github.com/XTLS/Xray-core/blob/v26.7.28/proxy/tun/stack_gvisor.go).
  The locally pinned HEV 2.17.1 source uses `hev-socks5-session-tcp.c` for
  application TCP sessions and SOCKS forwarding.
- [XHTTP pool/concurrency implementation](https://github.com/XTLS/Xray-core/blob/v26.7.28/transport/internet/splithttp/mux.go)
  and [Linux socket option errors](https://github.com/XTLS/Xray-core/blob/v26.7.28/transport/internet/sockopt_linux.go).
- [Linux 6.8 BBR pacing notes](https://github.com/torvalds/linux/blob/v6.8/net/ipv4/tcp_bbr.c),
  [kernel TCP sysctl definitions](https://docs.kernel.org/networking/ip-sysctl.html),
  and [TCP user timeout scope](https://www.rfc-editor.org/rfc/rfc5482.html).

Relevant local implementation: `g2ray.sh` (`performance_profile_settings`,
`tcp_fast_open_outbound_enabled`, `XHTTP_TUNED_EXTRA_JSON`, DNS generation and
the `generate` command); `g2ray_app/.../vpn/XrayConfigBuilder.kt` (MTU, DNS,
policy, keepalive and existing multi-edge support); and `HevTunController.kt`
(300-second TCP timeout). See [the measured TFO rollout](DEVICE_TFO_ROLLOUT_2026-10-14.md)
before considering any origin Fast Open change.

## Read-Only Live Inspection

The active Codespace was inspected over authorized SSH without changing state:

- Core: Xray 26.7.28, Go 1.26.5, linux/amd64.
- Kernel: 6.8.0-1064-azure; congestion default cubic; available/allowed reno/cubic.
- `/proc/sys` is mounted read-only.
- Slow-start-after-idle 1; notsent low-water mark 4294967295; MTU probing 0.
- TCP receive autotuning limits: 4096 / 131072 / 6291456 bytes.
- TCP send autotuning limits: 4096 / 16384 / 4194304 bytes.
- System resolver configuration includes 127.0.0.53 and 168.63.129.16.
- No IPv6 default route. The existing engine remained running.

These are host observations, not phone-to-edge or cloud-AI latency measurements.
No raw credentials or live config links are included here.

## Changes and Verification

`tests/xray_retained_http_check.go` now records actual `ttfb_ms` using Go's
first-response-byte trace, independently of complete-body `elapsed_ms`.
Unobserved values are omitted, not manufactured as zero. A valid first byte
does not establish a complete response; status, HTTP/2 and bounded body checks
remain mandatory. TTFB is not AI time-to-first-token.

Its owned-loopback mode also sends a complete HTTP/2 POST, waits seven seconds
before replying, flushes the first SSE event, then waits another seven seconds
before completing. The normal Internet-probe mode does not send this request.
The existing CI server/client-pair gate runs the fixture through actual generated
XHTTP and WebSocket configs using the checksum-verified pinned core. This is
protocol regression coverage, not an artificial latency target for production.

Four new Go tests check separate first-byte/body timing, absent timing on
request failure, delayed POST/stream behavior, and rejection of a stream prefix
without its final event. The TTFB assertion failed before implementation; this
was a diagnostic gap, not proof of a production transport failure.

Local verification passed: 12 Go tests, 14 authenticated-transport Python tests
and 9 isolated-server-pair Python tests. The direct owned-loopback CLI completed
the two-event stream with approximately 7 seconds TTFB / 14 seconds total, as
deliberately programmed. Real-core forwarding and race detection are separate
hosted CI gates, not inferred from that direct result.

Reproduce from the repository root:

```bash
go test -race ./tests/xray_retained_http_check.go ./tests/xray_retained_http_check_test.go
python3 tests/xray_authenticated_transport_check_test.py
python3 tests/xray_isolated_server_pair_check_test.py
go build -o /tmp/g2ray-retained-http-probe tests/xray_retained_http_check.go
python3 tests/xray_isolated_server_pair_check.py /usr/local/bin/xray \
  /tmp/g2ray-retained-http-probe --loopback-baseline --idle-seconds 0
```

Use the documented toolchain and Linux for race/core fixtures. See the
[production matrix](g2ray_app/PRODUCTION_TEST_MATRIX.md) for full prerequisites.
No Android production source changed, so this pass requires no new APK.

## Remaining Acceptance

ADB listed no device. No new phone installation, real AI-provider stream,
social/video, sustained throughput, unplugged Doze or physical network-handoff
result is claimed. The previous Android responsiveness checks remain separate
evidence; they are not new phone measurements.

Before changing any default, compare one factor at a time on the same actual
phone/network: cold and reused web TTFB plus completion, feed refresh and video
stall duration, AI first-token and stream continuity, loaded latency and
idle/handoff recovery. Respect the agreed data budget. No finite test set can
prove zero drops or all-platform correctness, and Codespace suspension/edge
behavior remain external availability constraints.

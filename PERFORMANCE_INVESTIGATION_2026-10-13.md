# Performance and Retained-Flow Investigation: 13 October 2026

## Readiness Rating

**79/100**, a subjective engineering-readiness assessment, not a measured
bandwidth, packet-loss, latency, or comparison-client score. It is not 100:
the original live configuration failed idle-response acceptance, and the new
origin fix still requires public-route rollout and physical-device acceptance.

| Area | Score | Weight | Current evidence and limitation |
| --- | --- | --- | --- |
| Codespace server operations | 90 | 25% | Verified pinned Xray 26.7.28, healthy listener/probes/supervisor, prior Linux integration gates. Persistent stream behavior and the hosting lifecycle are not flawless. |
| Android client correctness | 78 | 30% | 335 unit tests, native contracts, built functional APK, prior installed-device recovery proof. Native/WS retained-flow failures remain; this revision is not device-tested. |
| Wake and recovery | 86 | 15% | Prior real direct-GitHub wake and active-VPN recovery proof. The configured Worker was unreachable from the phone; the user chose direct GitHub. |
| Actual tunnel experience | 68 | 30% | Fresh requests work, but retained HTTP/2 stalled on both transports in the new Linux comparison. Sustained media/AI, handoff, battery, and large transfers remain unverified. |

Weighted total: 79.2, rounded to 79. Discovering a failure is useful progress,
but is not a reason to award a higher performance score before fixing it.

## Fixed: Configuration Errors Were Treated as Network Failures

`XrayCoreManager.start()` already stopped after a core configuration rejection,
but `G2RayVpnService.startEngineAndVerify()` converted it into a generic,
retryable `RunningRouteResult`. The service could then attempt another packet
engine, restart, wake, and persistent retry without changing the rejected config.

The correction preserves a configuration failure and its nonretryable result
through startup and recovery. Missing core/API, malformed generated JSON, and
Xray's `infra/conf` decoder/builder errors require configuration/build changes.
Unknown native errors, listener readiness, port collisions, runtime ownership,
and ordinary network failures remain retryable. This is deliberately not a
rule that treats every native startup error as permanent.

Malformed optional backups are skipped. They do not replace the original
selected route's retry policy or prevent trying a subsequent valid profile.
This Android fix changes no transport default, native binary, port forwarding,
UUID, or key. The separate server Fast Open policy change is described below.

Verification:

- Four new core regression assertions failed with the old retry policy.
- The live pinned Xray binary accepted a valid isolated client config and
  rejected malformed numeric XHTTP-extra and TLS-ALPN types at config loading.
  This test never bound listeners or modified the running server.
- Six new core-manager cases and four service integration cases cover fatal
  rejection, transient failures, reset after a repaired config, engine/restart
  ordering, and invalid-backup handling.
- Full local Android run: 335 tests in 30 suites, zero failures/errors/skips;
  lint zero errors and 26 warnings; functional and instrumentation APK builds
  passed with the pinned native contract/hash gates.

Changed runtime files: `g2ray_app/app/src/main/java/com/g2ray/client/vpn/`
`XrayCoreManager.kt` and `G2RayVpnService.kt`. Tests are in
`XrayCoreManagerTest.kt`, `G2RayVpnServiceStartupTest.kt`, and
`tests/xray_client_failure_validation.py`.

## Retained HTTP/2 Failure Reproduced Without Android

The new Go standard-library diagnostic records actual HTTP/2 protocol,
connection identity, whether it was reused, observed idle duration, complete
bounded response content, and request elapsed time. It uses strict TLS and
does not convert a slow retained response into a pass because a fresh request
works. Local fixtures verify reuse versus replacement, response rejection,
the six-second boundary, proxy selection, and certificate-verification settings.

The Linux runs used a separately started pinned Xray client, the current
private bundle, and the public Codespaces route. Original running listeners,
server config, and supervisor were not restarted. Client policy and outer
socket options match the app's 900-second connIdle, 512 KiB buffer, 25/15-second
keepalive, and 30-second TCP user timeout. This does not duplicate Android DNS,
TUN, mobile NAT, radio suspension, or CPU sleep.

| Run | Initial requests | Actual idle | Retained pool | Fresh sockets |
| --- | --- | --- | --- | --- |
| XHTTP packet-up | All three HTTP/2 requests passed in 100-293 ms | 70,003 ms | gstatic timed out at 20,000 ms on the same connection; Cloudflare returned EOF at 9,999 ms on the same connection; example.com succeeded at 252 ms after replacing its connection. **Gate failed.** | All three passed in 80-458 ms. |
| WebSocket | All three HTTP/2 requests passed in 173-215 ms | 70,066 ms | All three reused connections timed out at 20,000-20,019 ms. **Gate failed.** | All three passed in 119-185 ms. |
| Direct host, no Xray or SOCKS | 40-62 ms | 70,035 ms | All three original connections passed in 9-14 ms. **Gate passed.** | 34-40 ms. |
| Xray SOCKS-to-origin only, original TFO on | 37-57 ms | 70,040 ms | All three original connections timed out at 20,000-20,019 ms. **Gate failed.** | 38-53 ms. |
| Same origin path, zero-idle control | 42-114 ms | 0 ms | Same connections passed in 9-40 ms. **Gate passed.** | 38-39 ms. |
| Origin TFO off, otherwise unchanged | 36-115 ms | 70,049 ms | Same connections passed in 9-40 ms. **Gate passed.** | 37-126 ms. |
| Origin TFO re-enabled, adjacent comparison | 36-57 ms | 70,056 ms | gstatic passed at 10 ms; Cloudflare/example.com timed out around 20 seconds. **Gate failed.** | 39-41 ms. |
| Origin TFO off, longer idle | 39-59 ms | 180,000 ms | Same connections passed in 11-18 ms. **Gate passed.** | 38-113 ms. |
| Owned loopback HTTP/2 origins, TFO on | 4-5 ms | 70,064 ms | Same connections passed in under 1 ms. **Gate passed.** | 4-5 ms. |

Independent curl corroborated the origin result with two HTTP/2 requests in
one process, scheduled one minute apart. With TFO on, its retained connection
timed out at 25,001 ms; with TFO off, it reused the connection and returned in
10 ms (initial 42 ms). This is not only a Go diagnostic-client symptom.

These failures do not require Android, screen-off, VLESS, or GitHub's public
edge. The tested TFO setting on the Internet-facing origin path is implicated;
the exact lower-level kernel/middlebox failure has not been established.
Loopback forwarding still worked with TFO on. A ptrace attachment to the owned
diagnostic core was denied by the container; no security setting was weakened.

Three other isolated candidates did not cure the failure: keepalive idle
15 seconds, `XRAY_BUF_SPLICE=disable`, and `XRAY_BUF_READV=disable`. Each kept
the original TFO setting and failed retained acceptance around 20 seconds.
They were not applied to production. Do not describe these failures as proof
that a different MTU, global restart, or heartbeat cures idle stalls.

## Fixed Policy: Origin TCP Fast Open Is Opt-In

The previous `auto` default enabled origin TFO whenever the kernel advertised
support. That checks socket capability, not compatibility of the actual
egress path. The new default is off. Explicit `auto` retains its capability
check, and `1` remains an experimental forced override. Unknown values fail
closed instead of falling back to auto enablement.

Generated configs explicitly set `tcpFastOpen: false`. Existing configs receive
the same policy during the existing atomic, preflight-validated startup
migration. Only the managed `direct` freedom outbound is changed: UUID,
keepalive settings, custom outbounds, ports, and imported links are preserved.
The new default regression failed before the fix. Behavior and real-core
configuration tests cover opt-in, migration, and override behavior.
If migration itself fails, startup now restores its preflight backup and
returns failure before stopping the current engine; it no longer silently
continues with an unmigrated configuration.

This can give up an origin setup optimization on a compatible network; the
measured priority here is avoiding the reproducible retained-response stall.
It is not a claim of increased sustained throughput or universal compatibility.
The running server remains unchanged until a controlled rollout is approved.

The pinned HEV fixture also delivered every upstream response byte and passed
upstream EOF to the app as a TCP FIN within its bounded test window. Local
SYN-ACK traffic still produced zero upstream-payload progress, and restart
fixtures passed. This excludes a blanket claim that HEV cannot propagate an
ordinary peer closure; it does not prove detection of a silently black-holed
stream or long-session stability.

## Tools and Evidence Boundaries

- `tests/xray_retained_http_check.go`: opt-in real HTTP/2 requests, retained and
  fresh pools, and a no-proxy direct-host baseline. Go 1.26.3 was used locally
  to test and cross-compile the isolated Linux helper. No Go dependency is
  added to the app or server runtime.
- `tests/xray_retained_http_check_test.go`: local HTTP/2 fixtures, no Internet.
  Six Windows non-race tests passed. Windows race compilation failed because its
  configured C compiler failed; hosted Linux runs the race gate separately.
- `tests/xray_authenticated_transport_check.py`: starts/stops only its own
  temporary client and keeps the existing default fresh-request check. The
  retained helper and single-transport choice are explicitly opt-in. Origin,
  loopback, and independent curl baselines isolate different layers. Eight
  no-network Python tests verify URI fidelity, origin-copy isolation, and the
  independent curl gate. Debug logs are private and must not be published.
- `g2ray_app/scripts/test-hev-payload.py`: strengthened to check exact payload
  delivery and app-facing FIN, not merely counter increments.
- Static CI includes real pinned-core rejection and Go race fixtures. Android
  CI includes the service/core tests and actual Linux HEV fixtures. Check the
  completed workflow result for the specific source commit; intent is not a pass.

Final local verification also passed all 66 static and 196 behavior checks,
65 Worker checks, Worker syntax and packaging dry-run, and the deterministic
mock benchmark budgets. The final behavior run used an unchanged test file;
an earlier in-progress run was invalidated by editing that file and was rerun,
not treated as a project regression or a passing suite. Linux integration
with the actual pinned core accepted all ten generated/migrated combinations
(five performance profiles, WS on/off), including the TFO policy migration.
Go vet and six non-race fixtures passed; the Linux race result remains a
separate hosted-CI gate.

[Android CI for runtime source commit 95fd2a1](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38402946237)
passed source/unit/lint/instrumentation compilation and the Linux HEV payload,
EOF, and restart fixtures. The first Static workflow caught a stale assertion
that required ignoring migration failure; the assertion now requires the
stricter guarded migration, backup restore, and preservation regression.
The unchanged final local static suite passed after that correction. The
corresponding final hosted Static workflow must be checked separately.

No new phone installation or acceptance test was performed: the user chose to
continue without device tests. The earlier installed-device record remains
[separate](DEVICE_RECOVERY_FOLLOWUP_2026-10-13.md). The newly built local debug
APK has SHA-256 `7f615992089948b08417c061ee0f7491a027844fcbfccda18a2db307a8dcdf72`;
it is not the previously installed artifact.

## Still Required

1. Roll out the verified origin setting with an approved controlled restart,
   then repeat both public transports against the unchanged idle gate. Preserve
   every failing run and a rollback config; do not regenerate credentials.
2. Verify the same fix on the phone, including Native and HEV, without weakening
   the idle gate or converting connection replacement into claimed reuse.
3. Test long video/social-media/AI sessions, uploads, actual network handoff,
   unplugged standby, and multiple OEM devices. Small probes are not those tests.
4. Verify address-family and UDP/QUIC compatibility rather than describing
   IPv4-only DNS or UDP/443 blocking as universal coverage.

[Issue #1 remains open](https://github.com/z2roozsh/G2rayXCodeLeafy/issues/1).
The target of fast, stable daily use is unchanged. This investigation is
progress toward it, not a declaration that all bugs or optimizations are done.

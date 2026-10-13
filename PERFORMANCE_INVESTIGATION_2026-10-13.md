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

## Generated Server/Client Pair Follow-Up

The corrected integration harness exported the real `low_latency` server
configuration and schema-2 links. A separate pinned server and client used
strict local TLS termination, the exported XHTTP packet-up tuning, the WS
HTTP/1.1 variant, and the new default origin TFO-off policy. The original
server config, forwarding listeners, and credentials were not modified.

| Transport | Measured idle gap | Retained-pool requests | Actual connection reuse | Fresh requests |
| --- | --- | --- | --- | --- |
| XHTTP | 310,006 ms | 17-67 ms; all three valid HTTP/2 responses | Cloudflare 17 ms and example.com 20 ms reused original IDs; gstatic used a replacement at 67 ms. | 29-60 ms; all three passed. |
| WS HTTP/1.1 | 310,006 ms | 17-19 ms; all three valid HTTP/2 responses | Cloudflare 17 ms and example.com 19 ms reused original IDs; gstatic used a replacement at 17 ms. | 18-44 ms; all three passed. |

Both retained-pool and fresh-request gates passed without weakening their
two-of-three/six-second boundary. The replacement socket is explicitly not
counted as proof that all three original connections survived. Earlier
scheduled 310-second tests had no completion record after a Codespace restart
and are excluded, not reported as passes. The repeat recorded outputs locally.

These numbers measure the Codespace's origin path through an isolated
server/client pair. They are not the phone's latency, a public-edge rollout,
Android standby acceptance, sustained throughput, or media rebuffering results.
The public-route and phone gates remain open, and the rating is unchanged.

The CI-only owned-origin fixture initially failed because the generated
`geoip:private` rule correctly rejected its loopback destinations. The
fixture now prepends a TCP exception for exactly three owned origin ports on
`127.0.0.1/32`, retaining every original routing rule. The pinned core also
independently blocks private addresses inside its freedom outbound; a second
failed run and private debug logs identified that check. The fixture adds the
same exact allow rule to freedom's `finalRules`, preserving existing rules
and the default block for other destinations. Both exceptions are confined to
a temporary fixture; no production rule is removed or relaxed. Internet pair
checks use neither exception. Go tests reject invalid/duplicate port lists
and verify that a failed origin bind reaps already-started listeners.
The corrected owned-origin pair passed for XHTTP and WS: all initial,
retained-pool, and fresh responses were valid HTTP/2, and all three retained
connections kept their original IDs. This was a zero-idle startup/protocol
check, not additional idle acceptance.

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
  Eight Windows non-race tests passed. Windows race compilation failed because its
  configured C compiler failed. The updated eight-fixture hosted Linux race
  gate passed for source commit `1140505`.
- `tests/xray_authenticated_transport_check.py`: starts/stops only its own
  temporary client and keeps the existing default fresh-request check. The
  retained helper and single-transport choice are explicitly opt-in. Origin,
  loopback, and independent curl baselines isolate different layers. Thirteen
  no-network Python tests verify URI fidelity, origin-copy isolation, the
  independent curl gate, strict loopback fixture TLS, and owned-process cleanup.
  Debug logs are private and must not be published.
- `tests/xray_isolated_server_pair_check.py`: generates the actual server
  profile and its schema-2 XHTTP/WS links, preserves policy and origin socket
  settings, and starts a separate server/client pair on loopback ports. A
  one-day private test CA replaces edge TLS termination without disabling
  certificate verification. The links retain UUID, SNI/Host, path, ALPN, and
  XHTTP extra; only their dial address/port changes. Seven no-network tests cover
  fixture isolation, link fidelity, and rejection of missing transports,
  duplicate transports, mismatched credentials, an unexpected origin TFO
  policy, and an overbroad owned-origin exception. An opt-in loopback-only
  variant exercises both generated transports without Internet test traffic.
  This is not GitHub public-edge or Android acceptance.
- `g2ray_app/scripts/test-hev-payload.py`: strengthened to check exact payload
  delivery and app-facing FIN, not merely counter increments.
- Static CI includes real pinned-core rejection and Go race fixtures. Android
  CI includes the service/core tests and actual Linux HEV fixtures. Check the
  completed workflow result for the specific source commit; intent is not a pass.

Final local verification also passed all 66 static and 196 behavior checks,
65 Worker checks, Worker syntax and packaging dry-run, and the deterministic
mock benchmark budgets. The final behavior run used an unchanged test file;
an earlier in-progress run was invalidated by editing that file and was rerun,
not treated as a project regression or a passing suite. Go vet and eight
non-race fixtures passed.

**Correction to the earlier profile/WS coverage claim:** the old integration
loop wrote the preference file without updating the in-memory profile and
used empty marker files that did not enable WebSocket. Its ten labeled cases
therefore did not prove five distinct profiles or WS-on acceptance. This was
a verification-harness defect, not evidence of a production profile defect.
The strengthened assertions failed on the old profile selection; the new
pair preflight also exposed the missing WS listener. The harness now uses the
real profile setter and WS toggles and asserts the generated policy values,
log level, and actual listener presence before and after migration. The pinned
Linux core accepted all **12** corrected cases: six profiles, including
`unstable_mobile`, each with WS enabled and disabled. These are configuration
checks, not twelve sustained-transfer tests.

[Android CI for runtime source commit 95fd2a1](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38402946237)
passed source/unit/lint/instrumentation compilation and the Linux HEV payload,
EOF, and restart fixtures. The first Static workflow caught a stale assertion
that required ignoring migration failure; the assertion now requires the
stricter guarded migration, backup restore, and preservation regression.
The unchanged final local static suite passed after that correction. The
[Final Static CI for commit 1b5c017](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38403589634)
passed, including the Linux Go race gate, pinned-core rejection/upload-expiry
fixtures, shell/Worker regressions, ShellCheck, and Worker packaging. Its old
profile matrix is superseded by the coverage correction above; the new
harness has its own verified result:

- [Static CI for source commit 1140505](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38451621014)
  passed 66 static, 196 behavior, and 65 Worker checks, mock benchmark budgets,
  all eight Go race fixtures, 13 authenticated-diagnostic and seven pair-contract
  Python tests, real pinned-core rejection/upload-expiry checks, all 12 corrected
  configuration cases, and both generated owned-origin transports. ShellCheck
  and Worker packaging also passed.
- [Android CI for source commit 1140505](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38451621092)
  passed unit/lint checks, instrumentation compilation, native build contracts,
  and Linux HEV payload, EOF, and restart fixtures. It did not install an APK,
  run device instrumentation, or certify video playback.

The live checkout was rechecked at `d217843`, with Xray 26.7.28 running and
origin `tcpFastOpen: true` still in its active config. The isolated TFO-off
acceptance must not be described as a deployed live fix. No owned diagnostic
core remained after the completed runs; the original server and supervisor
continued running.

No new phone installation or acceptance test was performed: the user chose to
continue without device tests. The earlier installed-device record remains
[separate](DEVICE_RECOVERY_FOLLOWUP_2026-10-13.md). The newly built local debug
APK has SHA-256 `7f615992089948b08417c061ee0f7491a027844fcbfccda18a2db307a8dcdf72`;
it is not the previously installed artifact.

## Claim Review: Readiness Audit of d217843

The submitted 68/100 assessment reviews `d217843`, not the source at
`5ae7612` used as the baseline of this follow-up. Its score and the earlier
79/100 score use different subjective rubrics; neither is a network measurement
or proof of superiority over another client. The external evidence ZIP was
not supplied here, so its private test logs are not independently certified.

| Claim | Assessment and action |
| --- | --- |
| Invalid ports, blank dial hosts, and malformed FinalMask are accepted | Confirmed against the current parser. Six new regression/positive-boundary tests produced four failures before the fix. Port validation now requires 1-65535, blank hosts fail before resolution, and both FinalMask and XHTTP extra use the existing strict Moshi reader to require one complete JSON object. |
| Idle failures and incomplete media/device acceptance remain blockers | Valid. The historical phone failures remain recorded, and Issue #1 stays open. Source fixes, fresh quorum success, and connection replacement are not proof that retained application flows work. |
| The idle root cause has not been isolated | Outdated at the configuration/path level: the origin-only on/off/on comparison and independent curl reproduction above isolated a TFO-sensitive failure without Android or the public edge. The exact kernel/middlebox mechanism and physical-phone cure are still unproven. |
| 325/331 Android and 193 behavior checks describe the current source | Snapshot-specific, not current. This follow-up ran 341 Android tests in 30 suites and 197 server behavior checks. The earlier profile/WS harness also did not establish its ten labeled cases; the corrected 12-case result above supersedes it. |
| Short transfers cannot prove instant video, loaded latency, or commercial parity | Valid. No new sustained media, competing-client, handoff, unplugged Doze, or battery dataset was produced in this follow-up. |
| UDP/443 and IPv4-preferring DNS restrict coverage | Valid source-visible policies, not proof that all UDP or IPv6 traffic is unsupported. They remain explicit acceptance boundaries; no blanket IPv6 block or speculative DNS default change was made. |
| Packet-up 60,000-byte/10-ms tuning implies about 48 Mbit/s | Correct only as a nominal per-upload-session scheduling budget before overhead. It is not measured capacity, an aggregate limit, or a download cap. No posting-rate change was made without loaded-latency and sustained-upload evidence. |
| Network-scoped metrics, bootstrap fallback, aggregate admission, and KV consistency need follow-up | Valid design boundaries, not newly reproduced failures. Their individual scope and safe next tests are listed below; they were not converted into untested defaults. |

The changed Android source is
`g2ray_app/app/src/main/java/com/g2ray/client/parser/VlessUriParser.kt:25`
(strict object helper) and `:138` (dial-host/port validation). Tests are in
`g2ray_app/app/src/test/java/com/g2ray/client/parser/VlessUriParserTest.kt`.
Valid boundary ports, encoded strings, and existing XHTTP normalization remain
covered. FinalMask syntax validity is not native-core schema compatibility.
No native payload, dependency, endpoint, account, or transport default changed.

A related server/client contract defect was also reproduced: the server's
old `jq -e 'type == "object"'` accepted multiple JSON documents when the final
document was an object, while the Android parser rejected them. The Python
fallback rejected multiple documents but accepted nonfinite numeric constants.
`g2ray.sh:4375` now requires exactly one object with finite numeric values in
both parser paths. The added behavior regression failed before the fix and
now checks both real parsers, malformed/trailing input, numeric overflow,
valid nested values, and preservation of the built-in tuning fallback.
The jq predicates used are supported by the
[jq 1.6 manual](https://jqlang.org/manual/v1.6/); a newer jq is not required.

Focused follow-up boundaries:

- `StoredG2RayProfile.kt:3` persists delay/throughput samples without a physical
  network-generation key. Network transitions refresh session state but do not
  add provenance to old stored measurements. A future change should retain
  historical values separately from eligible current-network scores, with
  handoff/migration regressions before it affects selection.
- `SocketProtector.kt:115` chooses the first preferred IPv4 bootstrap answer.
  Test a blackholed first answer and bounded alternate resolution/dialing before
  changing startup ownership, cancellation, or standby behavior.
- The pinned XHTTP handler has per-session queues but not a global pre-auth
  pending-session/request/byte budget. Define compatible rejection and cleanup
  semantics, then run controlled overload fixtures. It is not a client JSON
  tuning setting, and changing the native core requires renewed build contracts.
- Optional Worker KV history/counters are best effort, as already documented.
  [Cloudflare documents eventual consistency and no atomic read-modify-write
  transactions](https://developers.cloudflare.com/kv/concepts/how-kv-works/).
  Strict locking/accounting would need a deliberate coordination design;
  no claim of strict consistency was added.
- GitHub's documented [12-hour maximum active Codespace lifetime](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle)
  remains a hosting limit even during active use. Wake/restart cannot preserve
  existing remote TCP sessions. It is not a defect that a new keepalive fixes.

Local follow-up verification completed:

- Android: 341 tests, 30 suites, zero failures/errors/skips; lint zero errors
  and 26 warnings; instrumentation compiled; HEV/libXray build contracts passed.
- Server: 197 behavior checks and 66 static checks; all four relevant Bash
  files checked individually with `bash -n`; `git diff --check` passed.
- All six deterministic mock benchmark budgets passed. These are script
  execution checks, not network throughput measurements.

Hosted workflow results must be checked for the exact pushed revision. This
follow-up did not package/install a new APK, run phone instrumentation, restart
the live Codespace, or deploy a Worker. The earlier TFO-off rollout remains
pending approval; local parser success does not clear the idle-flow gate.

## Still Required

The [14 October follow-up](DEVICE_TFO_ROLLOUT_2026-10-14.md) now records the
explicitly approved live TFO-off rollout, both public-route retained checks,
and completed short and five-minute physical-device cases. The earlier
pending-approval statement above describes this investigation's original
state, not the current rollout. Negative evidence remains preserved.

1. Extend the completed public/phone cases to longer retained sessions and
   repeated resumes, preserving the response gate, every failing run, and
   rollback config. Do not describe socket replacement as original reuse.
2. Test bootstrap DNS alternatives and actual physical-network transitions on
   device; source regression checks alone do not certify ISP behavior.
3. Test long video/social-media/AI sessions, uploads, actual network handoff,
   unplugged standby, and multiple OEM devices. Small probes are not those tests.
4. Verify address-family and UDP/QUIC compatibility rather than describing
   IPv4-only DNS or UDP/443 blocking as universal coverage.

[Issue #1 remains open](https://github.com/z2roozsh/G2rayXCodeLeafy/issues/1).
The target of fast, stable daily use is unchanged. This investigation is
progress toward it, not a declaration that all bugs or optimizations are done.

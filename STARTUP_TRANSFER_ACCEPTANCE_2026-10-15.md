# Startup and Bounded Transfer Acceptance

Date: 15 October 2026, Asia/Tehran.

## Decision and Exact Scope

One narrow Native-startup improvement passed regression checks and an actual
phone canary. Bounded full-device transfers passed on both packet engines and
both transports. This is not a declaration of flawless operation, universal
compatibility, instantaneous cold wake, or commercial-VPN superiority.

The source baseline is `b0714f89df81935a68e5b35516a9fdb111f3fd99`.
The production change in this follow-up is limited to
`g2ray_app/app/src/main/java/com/g2ray/client/vpn/G2RayVpnService.kt`.
Its tested Git blob is `e42a65731152aaaf2dd2925272fcccb14d68e2de`.
The server and Worker production sources are unchanged, with blobs
`3e0347ab99bb772c09341a6b346837f528c7a678` and
`ad09908f90aca3dd2bd113540f88f791bd9a1238`, respectively.

The functional debug APK was installed in place and its installed-file SHA-256
was independently checked before and after acceptance:

`2615a50682fa1aa8f51bfe9436299baf8ffe74a285dfa91f1f67a5edcf68f3c7`.

The previous installed APK was copied over USB for rollback before replacement:
`2d5d757de94cd9df8287fc60b38a774c82bafbe6727947d9c3e10da815afe22f`.
No account/profile data was cleared. No subagents, lock-policy changes, radio
toggles, native dependency changes, or live engine restart were used.

The phone was a Galaxy A52s, Android 14, arm64, on LTE. Its saved settings were
Fast DNS on, IPv4-only on, DPI compatibility off, and seamless failover off.
Those are observed owner preferences, not new project defaults. Requested
packet engines were explicitly checked against the actual running engine.

## Verified Startup Finding and Change

Earlier traces on the baseline APK showed a Native startup where all three
independent full-device TLS handshakes timed out, yet the same core's SOCKS
route succeeded in 1,579 ms immediately afterwards. The service then replaced
that core. DNS took 6-12 ms, default-VPN status was true, and all attempted
addresses were IPv4. These observations do not support blaming DNS lookup,
IPv6 selection, or native-core launch time for that sample's delay.

After a retryable Native PacketEngine diagnosis backed by a healthy SOCKS
route, the service now makes exactly one fresh full-device quorum check before
replacing the core. It does not accept SOCKS health alone. Lifecycle ownership,
physical-network generation, cancellation, and core state gate this extra
check. Persistent failures still enter the existing recovery path. HEV policy,
probe deadlines/quorum, transport tuning, DNS, MTU, TLS, and socket protection
are unchanged. A genuine packet-path failure can cost an additional bounded
probe; this is not a blanket claim that every startup becomes faster.

The phone canary exercised the new branch: after a failed full-path check and
a successful 2,389-ms SOCKS diagnosis, the fresh full-device check succeeded in
2,128 ms without another native-core start. Earlier attempts in that same run
had failed both paths and entered direct-GitHub state/start/settling recovery.
Its total startup was 36,608 ms, so it is not a warm-start speedup measurement
or proof of a particular cold-host deadline. Separate warm Native checks
verified XHTTP startup in 4,039 ms and WS in 2,870 ms, without that recovery.

Regressions cover a successful same-core confirmation, persistent packet-path
failure, a network change, a stopped tunnel, cancellation, and the precise
engine/failure-policy gate. Two new regression cases failed before the change.
The underlying reason for intermittent first-TLS stalls is not established.

## Measured Full-Device Transfers

Each invocation requested exactly 8 MiB down and 2 MiB up through Android's
default VPN, not a protected/bypassing socket or a standalone SOCKS client.
Downloads were read to their exact length, rejecting truncation/oversize.
Transfers had no automatic HTTP retry or redirect. Independent complete HTTPS
requests were required before and after, with the existing six-second quorum.

| Actual engine / transport | Download time | Download sample | Upload time | Upload sample | Post-transfer HTTPS, ms | Terminal result |
| --- | --- | --- | --- | --- | --- | --- |
| Native / XHTTP | 3,952 ms | 16.98 Mbps | 9,189 ms | 1.83 Mbps | 308 / 399 / 461 | Passed |
| HEV / XHTTP | 3,824 ms | 17.55 Mbps | 8,632 ms | 1.94 Mbps | 314 / 315 / 339 | Passed |
| Native / WS | 4,142 ms | 16.20 Mbps | 2,992 ms | 5.61 Mbps | 281 / 321 / 326 | Passed |
| HEV / WS | 4,555 ms | 14.73 Mbps | 3,592 ms | 4.67 Mbps | 301 / 301 / 282 | Passed |

HTTPS endpoint order is gstatic, Cloudflare, and example.com. Rates are
`bytes * 8 / elapsed_ms / 1000`; setup time is included. They are individual
whole-request samples, not repeated median/p95 measurements, sustained
capacity ceilings, or evidence that WS always uploads faster. No transport
default was changed from these four samples. HEV TUN counters advanced; Native
continued to report its byte-counter source as unavailable rather than invent
traffic numbers.

The first three transfer runs used instrumentation APK SHA-256
`8d494dddb576c06e2bceb774c84552823edea0f6dfb943fa860d7452825a6fdf`.
The HEV/WS run used
`ab50bd7e125a52d832a549aa72daa8eef6a146633c9ab0bacd4027e3d28208cb`.
The functional APK above remained unchanged throughout.

## Current Retained-Connection Check

The same functional APK, using Native/XHTTP and the final instrumentation
package, completed 60,008 ms of background idle without reopening the VPN
activity. All three original HTTP/2 connections were reused. Complete replies
took 321 / 354 / 329 ms; deliberately fresh sockets took 692 / 849 / 752 ms.
The unchanged six-second quorum passed, startup took 4,236 ms, and the test
explicitly stopped and reached Disconnected. This was interactive idle, not
controlled screen-off or unplugged Doze.

The final installed instrumentation APK was independently hash-matched:
`d3320a38e5b0b514f4d6bc53653362da2624f6d49e6ee9b751a707cdfba672b9`.
Its five packaged/harness device checks passed: actual native ABI loading,
multi-route core start/stop, generated DNS/IPv6 contract, bounded body reading,
and honest playback-observation accounting. The body-reader and observation
regressions perform no network access; a real core start is a separate check.
A generated DNS/routing contract
is not a physical DNS-leak or IPv6-only-network certification.

The older four five-minute screen-off cases and repeated core-failure results
belong to the different APK identified in
[the previous acceptance record](CURRENT_DEVICE_ACCEPTANCE_2026-10-14.md).
They are not silently attributed to this APK. Historical long stalls and
interrupted tests remain in the record.

## Video Attempt and Data Budget

The owner approved up to 100 MB of controlled transfers/video. Four successful
transfers used 41,943,040 payload bytes total. No additional large transfers
were started after them. Payload counts do not include TLS/tunnel framing,
retransmissions, health checks, unrelated applications, or carrier accounting.

A separate real YouTube-app attempt used HEV/XHTTP and an explicitly selected
public video. The matching local media session first reported Playing after
7,608 ms. Prefetching reached the test's 12 MiB mobile-counter threshold before
the required 15-second observation. The player was paused and force-stopped;
the YouTube process was independently confirmed absent afterwards. The
counter delta during the video helper was 15,419,528 bytes: polling/counter
cache and shutdown can overshoot the threshold. The whole test's mobile delta
was 21,306,881 bytes, including setup and other applications.

The test reached a terminal assertion failure because the observation could
not complete within its budget. It is **not a playback-acceptance pass**, a
reproduced VPN drop, or proof of smooth video. Resolution/cache effects and
multi-minute playback were not established. No additional video was started.

The first helper inspected reported media-session position snapshots. The
final helper separately records those snapshots and counts adjacent observed
local Playing intervals; it does not credit an unobserved gap or claim decoded
frames. This distinction follows Android's
[PlaybackState update-time contract](https://developer.android.com/reference/android/media/session/PlaybackState#getLastPositionUpdateTime()).
Its no-network phone regression passed. The budget-limited video run
was not repeated against that revised helper and remains incomplete. Shell
media-session permission was dropped, no video settings were changed, and
the original empty probe-log property was restored.

## Automated Checks and Evidence Boundaries

The final full Android run completed 502 tests in 37 suites with zero failures,
errors, or skips. Lint completed with zero errors and 25 warnings. Functional
and instrumentation APK packaging and native provenance/build gates passed.
The later playback-observer-only test change was compiled, packaged, linted,
and exercised on the phone; production code/APK did not change.

An earlier full 500-test attempt had five RouteMeasurementContextTest timeouts.
Those failures are retained in the private verification record. The class
alone, a combined startup/measurement run, a full 500-test rerun, and the final
502-test run passed without timeout changes, disabled tests, or relaxed gates.
The cause of that intermittent test failure was not isolated; it is not
described as fixed or as proof of a production route-scan regression.

Fresh local server static checks passed 66 cases; Worker behavior passed 65
cases, syntax checks and packaging dry-run. Windows Git Bash checks are not
substituted for Linux runtime or physical-device evidence. Hosted workflow
results must be checked against the exact published revision, not inferred
from the baseline's green workflows.

The live source was independently verified at `b0714f8`, with actual Xray
26.7.28 (`5ca6f4b`, Go 1.26.5), PID 269, origin TFO off, and local XHTTP OPTIONS
HTTP 200. The successful verification compared config/UUID/export hashes and
PID across its source-only check. No engine restart was issued. An earlier
copy attempt failed because Windows scp quoted the destination incorrectly;
a first stdin sync then fast-forwarded source but rejected its obsolete
expected commit. Neither failed operation is reported as a successful check.

Recent Android exit records showed normal instrumentation-completion stops,
not a new crash/native crash/ANR. Final service inspection showed no remaining
test VPN service. This is not a guarantee that every OEM or long session is
crash-free. Private raw logs, APKs, settings, and credentials remain untracked.

## Remaining Acceptance

The supplied older AI claims are assessed in
[the claim review](AUDIT_CLAIM_REVIEW_2026-10-14.md). Python/FastAPI/NetLeafy
bottlenecks and guaranteed rewrite speedups refer to a different/unsupported
architecture. Existing DNS-backup and network-provenance fixes were not
reapplied or replaced with speculative defaults.

Still needed: repeated loaded-latency/throughput samples; representative video,
social-media, and AI-app long sessions; real Wi-Fi/mobile transitions; unplugged
Doze and battery measurements; broader DNS/IPv6/UDP compatibility; controlled
aggregate admission/resource testing; and an independent competing-client A/B.
The intermittent initial TLS behavior and full-suite timeout warrant further
evidence. [Issue #1 remains open](https://github.com/z2roozsh/G2rayXCodeLeafy/issues/1).
This follow-up improves one verified recovery decision; it does not claim
that every bug or possible optimization has been eliminated.

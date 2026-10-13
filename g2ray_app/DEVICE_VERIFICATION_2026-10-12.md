# Android Publication and Device Verification

## Scope

The Android source now lives in `g2ray_app` in `z2roozsh/G2rayXCodeLeafy`.
Open this folder in Android Studio. The former sibling `client_app` was
preserved as an archive; it is no longer the source of truth for this repository.

This is a bounded physical-device verification record, not a claim that the
VPN is flawless, faster than every other client, or production-certified.
No production tunnel setting was changed as part of the source import.

## Build and Publication

- Source import: `04bfaaf`; subsequent commits fixed SDK setup in CI, removed an
  obsolete nested workflow, added opt-in live tests, and preserved the executable
  Unix Gradle wrapper and LF line endings.
- Android source CI passed at `a444bd4`:
  [Android run](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38293350397).
- Server/Worker CI passed at the same revision:
  [Server run](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38293350451).
  This ran 66 static checks, 183 behavior tests, 60 Worker tests, syntax checks,
  ShellCheck's error gate, mock benchmark budgets, and a Worker dry run.
- Local `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, and
  `lintDebug` completed successfully. Unit tests: 265, zero failures/errors.
  Lint: zero errors, 26 warnings. Warnings were not represented as a clean lint.
- Installed the debug APK over the existing app without uninstalling it or
  clearing data. Saved accounts, keys, profiles, and preferences were preserved.
- Initial installed APK SHA-256 matched the locally built APK:
  `a31a6f50747471b4ef6ab8aab07cea96e4d9c50cd10d672f9f6c51d68159ba02`.
- The experimental WS-heartbeat candidate was also installed without clearing data; its
  installed SHA-256 matched the built APK:
  `d63e7691bc7acd5dedc4142c1c402a5153aab7d65724ea0c16b907ec44d6c337`.
  It was not retained after its repeat test failed.
- The restored baseline was rebuilt and installed after the experiment. Its
  installed SHA-256 matched the final locally built APK:
  `879275b41692a2d01df0fa224497d4bf35459774e8c8ed9cf3a22425c46d4967`.
- Native core pin: libXray `80263da83e96b2972455b0a94b13ee1a10e51391`,
  with the repository's Android controller patch and Go 1.26.5. HEV pin:
  `9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a`.
- APKs, the large AAR, local SDK settings, credentials, and raw device artifacts
  were not added to Git. The four HEV ABI binaries have pinned metadata.

## Physical Device and Live Server

Device: Samsung Galaxy A52s (SM-A528B), Android 14, arm64, mobile LTE.

Observed from the installed app:

- A genuinely stopped Codespace was started by pressing Start VPN using the
  saved Direct GitHub wake mode. The app then verified the route and connected
  with HEV. No CLI start was issued before this test.
- Ping-all completed with 25 of 26 profiles responding; failed profiles were
  distinguished from successful measurements.
- Route optimization verified 26 of 30 candidates and transfer-tested two
  candidates, then selected a measured route.
- The live Codespace engine/listener were healthy; local and edge probes were
  HTTP 200, and the supervisor was running. Its source was updated by a
  fast-forward-only pull and post-start ran successfully.
- The running server binary was Xray 26.5.9. The container pin is newer; the live
  binary was not silently upgraded during this check.
- Packaged-core instrumentation passed three tests on the phone: native library
  loading, Native TUN config parsing, and HEV-side multi-outbound core start/stop.

## Full-VPN Network Tests

`LiveVpnDeviceTest` uses ordinary unprotected sockets with no SOCKS proxy or
underlying-network binding. Android's default network is asserted to be the VPN.
It uses the saved profile without printing its URI or keys. It downloads 2 MiB,
uploads 64 KiB, verifies response content, and tests HTTPS before and after idle.
The retained pool lives for ten minutes; fresh sockets are checked separately.

The following are small transfer samples, not sustained bandwidth benchmarks:

| Run | Requested idle | 2 MiB download | 64 KiB upload | Retained HTTPS after idle | Fresh HTTPS after idle |
| --- | --- | --- | --- | --- | --- |
| HEV, retained pool | 300 s | 3,486 ms | 830 ms | 1,286-1,872 ms, 3/3 successful | 1,192-1,267 ms, 3/3 successful |
| Native, retained pool | 60 s | 3,602 ms | 832 ms | One 20,010 ms timeout; another response took 18,803 ms | 1,073-1,422 ms, 3/3 successful |
| Native, repeated | 60 s | 2,809 ms | 1,132 ms | One 20,012 ms timeout; another response took 19,038 ms | 1,217-1,569 ms, 3/3 successful |
| HEV, screen-off and activity reopen | 60 s | 2,733 ms | 769 ms | One 20,021 ms timeout; another response took 9,408 ms | 1,225-1,617 ms, 3/3 successful |
| HEV/WS, confirmed awake | 60 s | 3,221 ms | 644 ms | Three reused HTTP/2 connections timed out at about 20 s | 2,070-4,115 ms, 3/3 successful |
| HEV/XHTTP, confirmed awake | 60 s | 3,117 ms | 639 ms | Stale connections were replaced; 1,263-2,513 ms, 3/3 successful | 1,211-1,461 ms, 3/3 successful |
| HEV/WS, 20 s heartbeat, confirmed awake | 60 s | 3,615 ms | 875 ms | Cloudflare/Example reused connections: 324/342 ms; Google timed out at 20,007 ms | 2,074-2,563 ms, 3/3 successful |
| HEV/WS, heartbeat repeated | 60 s | 4,287 ms | 840 ms | Google: 265 ms; Cloudflare/Example timed out at 20,004/20,017 ms | 2,042-4,112 ms, 3/3 successful |

The idle column is the requested background delay. Activity state changes and
test-driven close/relaunch can add time before the first post-idle request;
these samples are not exact 60-second socket-lifetime benchmarks. The harness
now reports the actual elapsed gap separately for future runs.

HEV counters advanced from real TUN activity, including more than 2 MiB received.
Native correctly reported its exact traffic counter source as unavailable;
device-wide counters were not substituted as tunnel throughput.
The controlled awake tests temporarily kept the charging display on. HTTP/2
connection identity telemetry confirmed actual reuse in the failed WS test and
automatic replacement in the successful XHTTP test. A retained pool does not
by itself prove that its original sockets survived or were used successfully.
An additional HEV/XHTTP run passed with service-state tracing: the service stayed
Connected through idle and reuse checks until the explicit test cleanup. The
WS-heartbeat run likewise showed no hidden service restart during idle.

### Verified Gap: Retained-Connection Idle Stalls

The original live-test gate accepted two eventual successful HTTP responses.
That allowed a roughly 19-second response to count as a pass. The gate now
requires two of three endpoints to complete within six seconds, and still
records fresh-socket results before asserting a retained-pool failure.
It is opt-in; ordinary source CI does not contact private servers.
The tightened Native test also failed as expected: only one of three retained
endpoints completed within six seconds, while all three fresh-socket requests
were responsive. An instrumentation assertion failure is not an app crash.

The Native failure was reproduced twice before tightening the gate. HEV did not
show this behavior in its five-minute retained-pool test, but did reproduce an
idle stall in a later shorter screen-off test with activity recreation. This
is not a Native-only issue. The app's probe reset
only closes its own health-check clients, not third-party app connections.
Fresh-socket success does not prove an app's retained connection is healthy.

Root cause is not confirmed: this is evidence of a VPN-path connection-reuse
problem on this device/network, not proof of a particular upstream gVisor bug.
No speculative MTU change, global resume-time tunnel restart, or unverified
native-core patch was shipped to hide it. Auto/HEV remains the normal path;
Native remains a compatibility fallback. Neither engine is certified for
stall-free idle resume from this evidence.
Changing from XHTTP to WS is not a verified fix: the awake WS run failed while
the same-edge awake XHTTP run passed. XHTTP remains the normal transport.
This finding is tracked in [issue #1](https://github.com/z2roozsh/G2rayXCodeLeafy/issues/1).

To investigate further, reproduce with both opt-in engine tests, capture sanitized
connection reuse/close events and Native TCP lifecycle data, make one targeted
change, and rerun both engines. Keep the retained-pool failure in the evidence
even when new sockets or basic health probes succeed.

### Screen-off Test Harness

The initial externally screen-off run failed while the Android test harness
tried to force its stopped activity to `RESUMED`. It did not reach the post-idle
network assertions and is not counted as a VPN pass or a tunnel failure.
The harness now closes/reopens the activity normally, stops the service from
application context even if UI restoration fails, and counts real elapsed idle
time with `elapsedRealtime` so deep sleep does not extend the requested delay.
The revised short screen-off test reached the network assertions and failed the
retained-pool responsiveness gate while all three fresh-socket checks passed.
The test now also reports device interactivity and asserts that Android's default
network remains the VPN after activity recreation.
It can compare existing transports on the same edge/account without changing
saved selection, records HTTP protocol/connection identity, and traces service
state transitions without printing account IDs, links, or error strings.

### WS Heartbeat Experiment

The pinned core and [official WS documentation](https://xtls.github.io/en/config/transports/websocket.html)
support `heartbeatPeriod`, which sends WebSocket Ping frames. This was previously
omitted, so no application-level WS heartbeat was sent. TCP keepalive alone does
not guarantee that an HTTP proxy preserves an idle WebSocket stream.

A candidate set a 20-second period on WS outbounds only. The same-edge awake
comparison improved two retained HTTP/2 endpoints from timeouts to roughly
0.3-second responses, without a service restart. The third endpoint still timed
out. The repeat then failed on the other two endpoints. There was no repeatable
improvement, so the heartbeat candidate was reverted, including its installed
production configuration. It is not shipped as a fix. XHTTP is unchanged.
Neither one passing quorum nor a partial improvement proves all retained
sockets are healthy. The connection-reuse issue remains open.
The interval adds small control traffic and can cost mobile radio/battery time;
it is not a raw bandwidth or universal latency improvement.

One candidate instrumentation invocation overlapped APK replacement. Android's
exit reason was `PACKAGE UPDATED`, not a native/JVM crash. It was excluded and
rerun after the install completed. No crash/ANR log signature was found.

## Limits

- This is a debug build, not a signed/minified production release.
- No new app crash or ANR signature was found during these checks. This does not
  prove the absence of crashes on other devices or longer sessions.
- Phone pings on this LTE path remained around 1.3-1.5 seconds. A 300 ms target
  was not achieved or claimed. Codespace-side probes do not measure the phone's
  ISP path.
- Five-minute idle checks and small transfers do not certify hours of YouTube,
  Instagram feeds, AI streaming, background Doze, all IPv6 destinations, packet
  loss, roaming, Wi-Fi/mobile handoff, or every application.
- Cold wake was tested with Direct GitHub. Worker tests passed in CI, but this
  pass did not demonstrate phone-to-Worker cold wake on the live network.
- Codespace suspension/quota, ISP behavior, remote-site policies, and edge
  routing remain external dependencies. Follow `PRODUCTION_TEST_MATRIX.md`
  before making a public stability or release-readiness claim.

Raw logs, screenshots, account data, and device identifiers stayed local in
temporary storage, outside the public repository.

## Follow-up: 13 October 2026

The user requested continuing without further device tests. The subsequent
health/recovery changes and rebuilt HEV libraries have automated coverage only;
none of the earlier phone results above validates those new binaries.

A pinned-engine Linux fixture reproduced two defects and checks their fixes:
locally generated SYN-ACK packets are not evidence of upstream application
payload, and stopping HEV with a half-open TCP connection left stale lwIP state
that could abort the next start. The fixture now passes negative-payload,
positive-payload, and repeated-start cases. All four Android ABI libraries were
rebuilt from the patch, with hashes checked by the Gradle verification task.

These fixes do not establish the cause of the retained-connection idle stalls
above. Issue #1 remains open, pending targeted device/network retesting.
See [the audit follow-up](../AUDIT_FOLLOWUP_2026-10-13.md) for the distinction
between corrected code, automated checks, and unresolved acceptance work.

## Later Retest: 13 October 2026

The phone was subsequently reconnected and the rebuilt functional APK was
installed in place, preserving saved accounts and profiles. New full-device
VPN checks exercised both packet engines, retained and fresh HTTPS sockets,
screen-off idle, real transfers, and two consecutive core failures per engine.
Both engines recovered automatically twice and passed all three HTTPS probes
after each recovery. No new crash/ANR exit reason was observed.

Idle results were mixed: longer HEV screen-off and Native interactive runs
passed, but a shorter HEV/XHTTP repeat and the HEV/WS retained-pool comparison
failed the unchanged six-second responsiveness gate. The retained-connection
gap is still open, not cured by the payload-counter/restart fixes.

See [the dated verification report](../AUDIT_VERIFICATION_2026-10-13.md) for
exact measurements, failed as well as successful runs, audit claim
dispositions, and the limits of this retest. The earlier no-device-testing
section describes the earlier pass; it is not the status of this later retest.

## Post-Core-Upgrade Retest: 13 October 2026

The live server was subsequently upgraded from 26.5.9 to the verified 26.7.28
pin after independently reproducing an upload-queue expiry defect. The current
Android retry-policy revision was installed in place when the phone was
reconnected again. Three packaged-core smoke checks passed, and both packet
engines recovered from two actual core stops with successful HTTPS traffic.

HEV/XHTTP passed both a shorter interactive idle check and a controlled
five-minute screen-off check. Native/XHTTP still failed the unchanged retained
HTTP/2 responsiveness gate, with a timeout and a 13-second response while fresh
requests were fast. A HEV/WS repeat timed out on all three retained connections
while all fresh requests succeeded. The core queue upgrade is therefore not an
idle-stall cure.
Issue #1 stays open. See the
[production re-audit verification](../PRODUCTION_REAUDIT_VERIFICATION_2026-10-13.md)
for exact timings, source/phone boundaries, and remaining acceptance work.

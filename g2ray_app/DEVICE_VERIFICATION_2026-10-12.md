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
- Installed APK SHA-256 matched the locally built APK:
  `a31a6f50747471b4ef6ab8aab07cea96e4d9c50cd10d672f9f6c51d68159ba02`.
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

| Run | Idle | 2 MiB download | 64 KiB upload | Retained HTTPS after idle | Fresh HTTPS after idle |
| --- | --- | --- | --- | --- | --- |
| HEV, retained pool | 300 s | 3,486 ms | 830 ms | 1,286-1,872 ms, 3/3 successful | 1,192-1,267 ms, 3/3 successful |
| Native, retained pool | 60 s | 3,602 ms | 832 ms | One 20,010 ms timeout; another response took 18,803 ms | 1,073-1,422 ms, 3/3 successful |
| Native, repeated | 60 s | 2,809 ms | 1,132 ms | One 20,012 ms timeout; another response took 19,038 ms | 1,217-1,569 ms, 3/3 successful |

HEV counters advanced from real TUN activity, including more than 2 MiB received.
Native correctly reported its exact traffic counter source as unavailable;
device-wide counters were not substituted as tunnel throughput.

### Verified Gap: Native Retained-Connection Idle Stalls

The original live-test gate accepted two eventual successful HTTP responses.
That allowed a roughly 19-second response to count as a pass. The gate now
requires two of three endpoints to complete within six seconds, and still
records fresh-socket results before asserting a retained-pool failure.
It is opt-in; ordinary source CI does not contact private servers.
The tightened Native test also failed as expected: only one of three retained
endpoints completed within six seconds, while all three fresh-socket requests
were responsive. An instrumentation assertion failure is not an app crash.

The Native failure was reproduced twice before tightening the gate. HEV did not
show this behavior in its five-minute retained-pool test. The app's probe reset
only closes its own health-check clients, not third-party app connections.
Fresh-socket success does not prove an app's retained connection is healthy.

Root cause is not confirmed: this is evidence of a Native-path connection-reuse
problem on this device/network, not proof of a particular upstream gVisor bug.
No speculative MTU change, global resume-time tunnel restart, or unverified
native-core patch was shipped to hide it. Auto/HEV remains the normal path.
Native remains a compatibility fallback and is not certified for idle resume.

To investigate further, reproduce with the opt-in Native test, capture sanitized
connection reuse/close events and Native TCP lifecycle data, make one targeted
change, and rerun both engines. Keep the retained-pool failure in the evidence
even when new sockets or basic health probes succeed.

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

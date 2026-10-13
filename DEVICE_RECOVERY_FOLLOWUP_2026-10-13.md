# Physical-Device Recovery Follow-up: 13 October 2026

## Decision

The connected Galaxy A52s (SM-A528B, Android 14/API 34, arm64) exposed a real
recovery bug that source-only checks had missed. It is fixed and installed.
This does **not** certify flawless production behavior: retained connections
still fail in Native and WebSocket screen-off tests. Issue #1 remains open.

The prior verification reports remain historical records. This follow-up
supersedes the previous turn's statement that no phone test or installation
was performed; the user subsequently reconnected the device and authorized it.
No subagents were used. Existing accounts, keys, profile selection, and engine
preference were preserved. No app data was cleared and no native binary changed.

## Verified Root Cause and Fix

`SocketProtector.protectControlPlane()` protected a newly constructed Java
`Socket` before its native descriptor existed. On this phone:

- `VpnService.protect(Socket())` returned `false`.
- Initializing the socket through physical-network binding made protection
  return `true`.
- The production helper failed the new active-VPN instrumentation assertion.
- Outside the VPN, direct GitHub wake with the same saved account succeeded.
- Two service-only cold starts failed to verify within 180 seconds; the host
  remained stopped. A traced repeat reached GitHub recovery but could not use
  its rejected recovery socket.

The fix reads `socket.soLinger` to materialize its native descriptor, then
protects and binds it to the selected physical network **before** connecting.
No socket option value is changed. Protection rejection, missing network,
initialization errors, and binding errors still fail closed. No process-wide
binding or unprotected retry was added.

Android's own [socket-initialization fix](https://android.googlesource.com/platform/frameworks/base/+/5e9b4c7c338e31b9acc2e4d3fb877202e435a32e%5E%21/)
uses the same public getter to force descriptor creation. Its
[VpnService source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/VpnService.java)
shows that the Socket overload protects the underlying descriptor; the
[Network API](https://developer.android.com/reference/android/net/Network)
documents network-specific binding before connect.

The exact failing device regression passed after the fix. Direct GitHub
control-plane verification worked while VPN was active in 847 ms. The same
service cold-start path then woke the host and verified full-device traffic
in 55,193 ms, followed by a passing idle check. This is correctness evidence,
not an instant-cold-start promise.

## Phone Evidence

All live traffic used saved profiles on the same account and edge. The HTTPS
gate remains two of three independent endpoints within six seconds. Failures
are retained even when fresh connections pass. Small transfers are 2 MiB down
and 64 KiB up; they are not sustained-capacity or YouTube measurements.

| Scenario | Result and boundary |
| --- | --- |
| Packaged-core smoke | Three tests passed before the Java recovery-socket fix: actual native loading, config contract, and multi-outbound start/stop. Those native artifacts did not change. |
| Direct GitHub wake, VPN off | Stopped host became Available in 13,886 ms; repeat 16,145 ms. Available alone did not claim route readiness. |
| Normal Start VPN action, stopped host | Passed through the real ViewModel action, saved Auto engine, and selected XHTTP profile; full-device verification in 18,494 ms. |
| HEV/XHTTP, awake idle with VPN activity reopening | Passed. Actual idle gap 107,076 ms; retained-pool requests 872-1,362 ms after replacing stale sockets; fresh 1,054-1,104 ms. |
| Native/XHTTP, awake idle with VPN activity reopening | Passed this repeat. Actual gap 107,154 ms; retained-pool requests 655-717 ms after replacement; fresh 683-829 ms. Does not cancel earlier failures. |
| Fixed service-only cold start and HEV/XHTTP idle without reopening VPN UI | Passed. Verified start 55,193 ms; idle gap 60,605 ms; retained connection IDs unchanged, requests 309-558 ms; fresh 1,112-1,389 ms. |
| HEV/WS, screen-off interval, no VPN activity reopening | **Failed**. Idle gap 60,666 ms; retained requests timed out in 20,015-20,021 ms; fresh requests succeeded in 1,796-1,825 ms. |
| Native/XHTTP, screen-off interval, no VPN activity reopening | **Failed**. Idle gap 60,689 ms; two retained requests timed out in 20,018-20,021 ms; a replacement for the third succeeded in 1,002 ms; fresh 710-770 ms. |
| HEV/XHTTP, two actual core stops | Both recovered and passed three fresh HTTPS requests. Failure-to-verified-quorum durations 7,347 and 7,390 ms. |
| Native/XHTTP, two actual core stops | Both recovered and passed three fresh HTTPS requests. Failure-to-verified-quorum durations 4,813 and 6,982 ms. |
| First five-minute HEV/XHTTP attempt | **Failed before idle**. One initial HTTPS request timed out; the other two passed. Download took 8,249 ms and the following upload response timed out. No five-minute or screen-off acceptance can be inferred from this attempt. |
| Repeated five-minute HEV/XHTTP attempt, no VPN activity reopening | Passed with an actual 310,113 ms idle gap. Retained-pool requests completed in 1,092-2,156 ms after replacing stale connections; fresh requests took 960-1,081 ms. Warm verified startup took 2,832 ms. This repeat does not cancel the failed first transfer attempt. |
| Worker forced while VPN active | The 30-second diagnostic deadline expired; no usable response. This does not prove whether TCP, TLS, or backend response caused the delay. |
| Worker forced with VPN off | Failed after 45,503 ms, no HTTP status, `control_plane_unavailable=true`. The Windows public page returned HTTP 200; this is not phone reachability or authenticated wake proof. |

The short screen-off controllers sent Sleep and Wakeup 45 seconds apart.
Both affected tests recorded noninteractive state at their post-idle boundary.
The passing five-minute repeat had a controlled screen-off interval of about
295 seconds and was noninteractive at its post-idle boundary; traffic resumed
without reopening the VPN activity.
USB power, Android scheduling, lock-screen state, and instrumentation overhead
remain factors; these are not forced deep-Doze or unplugged battery tests.

ADB no longer listed the phone after the final five-minute run. A requested
final repeat of the three packaged-core tests therefore did not execute; it
is not counted as a pass. The completed installed-app live tests above and
the independently matched installed APK checksum remain the device evidence.

The inspected runtime log had no new JVM/native crash or ANR signature.
Recent Android exit records identified `finished inst` teardown, not a crash.
An instrumentation assertion or network timeout is still a failed acceptance
test, but it must not be mislabeled as an application crash. These observations
cannot prove that the app will never crash on another workload or device.

The user chose to continue with direct GitHub. The saved account was already
in direct mode, so no key, endpoint, or backend preference was changed. No
claim is made that the configured workers.dev hostname is reachable from this
phone network. A reachable alternate endpoint would need independent testing.

## Automated Verification and Rollout

Source fix: `1a382e7998ab73e6404bdd744f75f5e2d8134e2c`.

- Local: 325 Android unit tests in 29 suites, zero failures/errors/skips;
  lint zero errors and 26 warnings; functional APK and instrumentation APK
  builds passed with native hashes/API/build-contract gates enabled.
- [Android CI](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38382639554)
  passed source checks, lint, instrumentation compilation, and actual Linux
  HEV payload/restart fixtures.
- [Server/Worker CI](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38382639487)
  passed 193 server behavior, 66 static, and 65 Worker checks, mock budgets, ShellCheck, packaging
  dry-run, pinned-core checksum, upload-expiry reproduction, and real generated
  configuration validation. CI is not the phone acceptance record above.
- The live core reports Xray 26.7.28. Local and public server probes returned
  HTTP 200. The running Codespace checkout fast-forwarded without restart.
- The functional APK was installed in place and its installed checksum was
  independently compared with the local artifact: match.

APK SHA-256:
`77e1468f17ab34c8a1c30c8b437724e07c37db11c5b0bcd6418a2a7173181e06`.

Changed files are `SocketProtector.kt`, five focused tests in
`SocketProtectorTest.kt`, the opt-in `LiveVpnDeviceTest.kt`, and the production
test matrix. New device cases distinguish service-only startup from the real
Start VPN action, active-VPN control-plane calls from direct calls, and
background traffic from VPN-activity reopening. Diagnostics print stages and
booleans, not profile links, keys, or backend response bodies.

## Remaining Acceptance

[Retained-flow issue #1](https://github.com/z2roozsh/G2rayXCodeLeafy/issues/1)
is still open. Working fresh health probes cannot certify every application's
existing HTTP/2 connection. No speculative global reset, shortened stream
timeout, or new heartbeat is shipped as an idle-flow cure.

Keep Auto/HEV and XHTTP as the current normal path. Native and WS remain
compatibility choices, not proven equivalent for idle reuse. No claim of
universal client superiority, zero packet loss, all possible optimizations,
or production perfection follows from these tests. Sustained media/AI use,
large uploads/downloads, real Wi-Fi/mobile handoff, unplugged Doze, battery
cost, other OEMs, and longer unattended sessions still need acceptance.

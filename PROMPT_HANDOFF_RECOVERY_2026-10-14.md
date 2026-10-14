# Prompt Verification During Network Handoff

## Scope And Evidence

Source baseline: `76488eb1e9b2d04e75024f0927747c0e505bc0dd`.
This change targets Android verification after app resume, renewed idle
traffic, or an active stall. It does not change server/Worker settings,
transport tuning, failure thresholds, probe deadlines, DNS policy, or the
saved wake method. No subagents, device tests, media transfers, or live
engine restarts were performed for this change.

The periodic health monitor already checks the physical-network generation
after its probe. The prompt-verification path did not have equivalent
guards. It could also retain the lifecycle-operation mutex while probing
the old network, delaying verification of its replacement.

Three controlled service tests failed before the fix on API 31:

| Scenario | Observed pre-fix result | Risk |
| --- | --- | --- |
| Network generation changes during a failed prompt probe | A second confirmation probe started on the obsolete path. | Unnecessary probing and possible disruptive recovery after a handoff. |
| Network generation changes during a successful prompt probe | The service published Connected, routeReady, and tunnelVerified from the old result. | The replacement network can be shown as verified without a current check. |
| Physical handoff while a prompt probe is suspended | The old job remained active and retained the lifecycle mutex. | New-network verification can wait for the obsolete probe's deadline. |

Original failing XML is retained privately in
`data/verification/prompt-handoff-before-20261014.xml`. An earlier fixture
attempt omitted the required `encryption=none` URI field; that harness error
was corrected before these three actual assertion failures were reproduced.

The tests invoke the real service methods with a fake probe and bridge,
virtual coroutine time, a synthetic Network, and a file-descriptor fixture.
They do not start a native core, send Internet traffic, or reproduce an
actual ISP handoff.

## Changes

- Prompt verification refuses to start while no physical network is selected,
  including after waiting for the lifecycle-operation mutex.
- Each prompt check captures the physical-network generation and rejects
  obsolete results after probes, confirmation delays, and before publishing
  verified state on the main dispatcher.
- Transition verification stops retrying an obsolete network and reports a
  retryable dialer-binding result rather than accepting its success.
- A physical-network change cancels the outstanding prompt-verification job,
  allowing its cancellable probe to release the lifecycle mutex. The existing
  handoff job remains responsible for checking the replacement network.
- Stable-network failures still confirm and schedule recovery. The fix does
  not turn a real failed check into success or disable reconnection.

Changed production file:
`g2ray_app/app/src/main/java/com/g2ray/client/vpn/G2RayVpnService.kt`.
New regression class:
`g2ray_app/app/src/test/java/com/g2ray/client/vpn/G2RayVpnServiceReverificationTest.kt`.

## Verification

Nine scenarios pass on both API 24 and API 31, for 18 new regression cases:
obsolete failure, obsolete success, suspended-probe cancellation, loss of
all physical networks, a change during confirmation delay, a change while
main-dispatch publication is queued, stable success, stable failure, and
refusal to probe without a physical network. The cancellation test also
checks that the replacement network receives a fresh probe without a core
restart. Together with the three existing periodic-monitor tests, the
focused run passed 21 cases.

The full local cached-SDK run passed 465 cases in 36 suites, with zero
failures, errors, or skips among selected cases. Lint reported zero errors
and 25 warnings. Instrumentation Kotlin compilation and `git diff --check`
also passed:

```powershell
cd C:\Users\Shayan\Desktop\Work\7_G2ray\G2rayXCodeLeafy\g2ray_app
.\gradlew.bat -ProbolectricSdks=23,24,31,35,36 testDebugUnitTest lintDebug compileDebugAndroidTestKotlin --console=plain
```

The selector uses already-cached SDKs to avoid large downloads on the user's
metered connection. Hosted CI retains its declared SDK coverage. No existing
test assertion, deadline, or recovery threshold was weakened to get a pass.

## Acceptance Still Open

No functional APK was packaged or installed for this change; the user builds
the canonical `g2ray_app` project in Android Studio. Existing installed-APK
results do not validate this source revision.

This closes reproducible source-level handoff races. It does not establish
the cause or complete resolution of the previously measured retained-socket
idle stalls. Issue #1 remains open. Physical-device handoff, final HEV/WS
idle acceptance, current-APK crash/ANR review, long sessions, real media,
sustained upload/download, loaded latency, and battery behavior remain
unverified. The user's small-probe budget and decision to finish without
more device tests are preserved. No throughput or zero-drop claim follows
from these fixtures, and Codespaces hosting limits are unchanged.

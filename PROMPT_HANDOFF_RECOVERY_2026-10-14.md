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

## Initial Verification

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

## Cancelled Handoff Follow-Up

Follow-up baseline: `06e3f4038279b4c56d5f0813691d5e71a2623c24`.
The initial fix guarded prompt verification. Reviewing the separate handoff
job exposed another result-processing race: a cancelled handoff could
schedule a separately owned recovery job after its probe returned. That
recovery was not a child of the cancelled handoff.

The final pre-fix focused run executed 12 cases on API 24 and 31. Six failed
and six positive-control cases passed:

| Reproduced scenario | Observed before this follow-up |
| --- | --- |
| A successful probe replaces network 101 with 102 before returning | The old handoff was cancelled, but one bridge.stop call was recorded on the replacement route. |
| A failed probe replaces network 101 with 102 before returning | The old handoff was cancelled, but one bridge.stop call was recorded on the replacement route. |
| A successful probe removes all physical networks before returning | The cancelled handoff still claimed recovery ownership instead of leaving connectivity restoration to a current handoff. |

The recording bridge is a fixture, not an actual native engine. These are
real service-method executions with controlled callback timing; they do not
prove that this race caused the previously measured phone stalls.
The original XML is retained privately at
`data/verification/handoff-cancelled-core-stop-before-20261014.xml`.
An earlier selection ran five failing cases because its exact method filter
did not include the API-24 suffix for the network-loss scenario; the final
pre-fix run includes both SDKs for all six new scenarios.

Cancellation must be observed before subsequent synchronous actions.
The project's pinned kotlinx.coroutines 1.10.2 already supplies the
current-context `ensureActive` check; this follow-up adds no dependency or
library upgrade. The behavior is supported by the
[pinned Job implementation](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Job.kt)
and [official cancellation-check API documentation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/ensure-active.html).

The follow-up changes are:

- Transition verification checks cancellation at entry and after each probe.
- Each handoff captures its network generation and, after acquiring the
  lifecycle mutex, captures its lifecycle token and TUN identity.
- Old, cancelled, networkless, or lifecycle-obsolete handoffs cannot dispatch
  recovery or publish verified state.
- Final recovery/state checks use the same service monitor as
  `applyUnderlyingNetwork`, keeping the check and side effect atomic with
  actual network callbacks. No suspension is performed under that monitor.
- Existing confirmation delays, real-failure recovery, transport settings,
  wake preferences, and background probe traffic remain unchanged.

The expanded verification class now passes 30 cases on API 24 and 31. Its
12 new cases cover obsolete success, obsolete failure, complete network
loss, successful network return, confirmed failure on a stable replacement
network, and replacement while waiting for the lifecycle mutex. Tests also
require a fresh check of the newest network without an unnecessary core stop.
Including startup and periodic-monitor cases, the focused run passed 40.

The full follow-up local run passed 477 cases in 36 suites with zero
failures, errors, or skips among the selected cached SDKs. Lint reported
zero errors and 25 warnings. Instrumentation Kotlin compilation and
`git diff --check` passed. The command is unchanged from Initial Verification.
No existing assertion, timeout, or recovery threshold was weakened.

The Acceptance Still Open section remains applicable. No APK was packaged
or installed, no device tests or media transfers were performed, and the
retained-socket idle-stall issue remains open. This prevents a verified
source-level cause of unnecessary recovery during handoff; it is not a
measured throughput, ISP-latency, or uninterrupted-video improvement.

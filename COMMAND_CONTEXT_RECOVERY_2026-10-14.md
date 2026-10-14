# Current Selection For VPN Commands

## Scope And Reproductions

Source baseline: `680f8c5c9220dcdfa5dacb7189b9f04cea01eb29`.
This follow-up changes Android command handling, not transport tuning or the
server/Worker data path. No subagents, phone tests, media transfers, or live
engine restarts were requested or performed in this pass.

The ViewModel's account/profile/list presentation flows use
`SharingStarted.WhileSubscribed(5000)`. With no subscriber, they can retain
their initial empty value or a previous account's value after upstream
collection stops. Reading that cache in a command is not a fresh repository
lookup. The pinned kotlinx.coroutines 1.10.2 implementation confirms this
behavior; it is not a library defect.

Six regression cases failed before their corresponding fixes on the API-31
Robolectric fixture:

| Scenario | Observed pre-fix failure | User-facing risk |
| --- | --- | --- |
| Ping all with stored profiles but no list collector | No configs reported; native batch calls stayed at zero. | Ping all can be unavailable despite saved configs. |
| Failed-config cleanup after the UI list stops and the account changes | The fixture profile from the previous account was deleted. | Cleanup can remove a different Codespace's saved config. |
| Start VPN without account/profile UI collectors | No start intent was dispatched. | A valid stored config can be treated as missing. |
| Start VPN after the UI account/profile cache becomes stale | The recorded start intent targeted the previous account/profile. | The app can start the wrong Codespace's tunnel. |
| Wake without an account UI collector | No active account reported despite a saved selection. | Wake can incorrectly appear unconfigured/unavailable. |
| Manual commands during a latched account-selection write | Commands did not report the required pending-settings guard. | A command can observe the old selection while a switch is pending. |

The original failing XML is retained privately in:

- `data/verification/profile-command-snapshot-before-20261014.xml`
- `data/verification/account-command-context-before-20261014.xml`

These are controlled in-memory fixtures. No user profiles were deleted, no
actual VPN service was launched, and no GitHub/Worker wake request was sent.
The first new cleanup fixture used the wrong account display-field name;
that compilation error was corrected before the actual failure reproductions.

## Changes

- Start VPN and Wake capture the selected stored account and its matching
  profile rather than cached presentation-flow values.
- Wake progress and cancellation attach profile/account identity from stored
  selection, with an account-owner check on default profile attribution.
- Ping all reads current stored profiles for the selected account. The UI and
  command share the existing health/latency ranking; its scoring did not change.
- Failed-config cleanup scopes candidates to the current stored account and
  checks that selection is still the same before the asynchronous mutation.
  Existing network provenance, failure-count, and active-test guards remain.
- Manual ping, route optimization, and wake commands wait while an owned
  settings mutation is active. Existing saved-data failure guards remain.
- The presentation flows remain subscription-driven. No permanent UI collector,
  extra network polling, account migration, or new dependency was introduced.

## Verification

The two focused test classes passed all 40 cases. They cover API 24 and 31
for the profile-list scenarios, including an additional stale-account ping
case, plus API-31 recorded service intents and pending-write behavior.
Advancing the test scheduler past five seconds creates genuine stopped UI
collection; tests do not replace the cache with a manually injected fake list.
The service-intent fixture controls native availability and intercepts dispatch;
it does not establish native startup or physical-device VPN behavior.

The full local cached-SDK run passed 447 cases in 35 suites, with zero failures,
errors, or skips among selected cases. Lint reported zero errors and 25
warnings. Instrumentation Kotlin compilation also passed:

```powershell
cd C:\Users\Shayan\Desktop\Work\7_G2ray\G2rayXCodeLeafy\g2ray_app
.\gradlew.bat -ProbolectricSdks=23,24,31,35,36 testDebugUnitTest lintDebug compileDebugAndroidTestKotlin --console=plain
```

The local SDK selector avoids new large SDK downloads on the user's metered
connection. Hosted CI retains its unrestricted declared SDK matrix. No test
deadline, assertion, or acceptance threshold was weakened to obtain a pass.
`git diff --check` passed.

This is a source-only app update. No new functional APK was packaged or
installed in this pass; the user builds the canonical `g2ray_app` project in
Android Studio. Existing installed-APK results do not validate these changes.

## Remaining Acceptance

The earlier intermittent ping-test timeout has not been conclusively linked
to this newly reproduced cache defect. The focused and full suites now pass,
but that is not proof of the earlier timeout's cause.

Physical-device idle recovery, final HEV/WS acceptance, handoff, sustained
media/upload/download behavior, long sessions, and current-APK crash/ANR review
remain incomplete. The user's small-probe budget and decision to stop device
testing are preserved. Issue #1 remains open. These command fixes do not prove
a raw throughput improvement, lower measured ISP latency, or zero connection
drops, and do not remove Codespaces hosting limits.

## Reference

[Pinned kotlinx.coroutines 1.10.2 sharing strategy](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/flow/SharingStarted.kt)
documents subscription-controlled collection and retention of the replay cache.

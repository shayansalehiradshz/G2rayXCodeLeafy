# Route verification and recovery review - 15 October 2026

## Decision

This is a source- and build-verified reliability candidate, not a certification of
flawless operation or a stable production release. The supplied review identified
valid Android defects. Its 83/85 ratings are subjective readiness judgments, not
measurements of speed, uptime, or superiority to another client.

Reviewed base: `a84b5e3aa5ca9795455ef9989a152a774289ce89`.
The supplied patch's SHA-256 matched
`f9a54fc70fd0e6cabf47f67e900cdf456a8f360554128b57cd510bfcff0b93b2`, and
its applicability was checked against that exact base before any edits.
The changes were read, independently tested, and extended rather than treating
the attachment's claims or commands as instructions.

## Verified findings and fixes

Runtime paths below are relative to
`g2ray_app/app/src/main/java/com/g2ray/client/`.

| Finding | Correction and regression coverage |
| --- | --- |
| DNS exceptions can escape before ordinary saved IP backups are tried. | `vpn/G2RayVpnService.kt` returns retryable `EndpointResolution`, preserves cancellation, and avoids retrying DNS with a different packet engine. Startup tests cover an unmeasured IP backup without a Worker/GitHub wake. |
| An unresolved or rejected first backup can prevent another candidate from being tried. | Scan at most six candidates, skipping DNS-only/configuration failures without spending the single actual route attempt before wake. Preserve ranking for that attempt and return immediately on a physical-network race. |
| Optional native pings retain ownership across inner port retries while VPN startup waits. | `vpn/XrayCoreManager.kt` checks queued VPN ownership and caller context between completed native calls. `ui/G2RayViewModel.kt` forwards cancellation/network checks. Cleanup releases temporary files and the lease; in-flight JNI is not forcibly interrupted. |
| Successful HTTP headers alone can falsely prove route health. | Shared `vpn/RouteProbeResponse.kt` consumes complete small responses and rejects truncation, early EOF, mismatched lengths, or bodies over 64 KiB. SOCKS and full-VPN checks use the same rule and retain their call/quorum deadlines. |
| Full-path probes can accept physical/unknown default routing. | `vpn/VpnDataPathProbe.kt` waits for VPN default routing within its existing 4.5-second budget, then rejects a success if that routing is absent at completion. This applies in debug and release builds. |
| Startup/recovery proofs can become stale while a network changes or publication waits in the main queue. | Successful results carry their tested network generation. Final publication is serialized with network callbacks; an obsolete proof queues a fresh check without blindly restarting a healthy core. Startup, diagnosis, HEV repair, and Native confirmation are fenced. |
| A connected but unverified core can remain unverified after a successful direct handoff. | A fresh current-network handoff can publish verified state without requiring a preceding network-loss callback or another core start. |
| Late socket-binding results can be attributed to a replacement network. | `vpn/SocketProtector.kt` snapshots network/generation together, scopes errors to that generation, and rejects dialer/control-plane binds that finish across handoff. |
| The service can erase the replacement network's first genuine protection error. | Additional independent regression: recording an error as the new network is published failed because the service subsequently cleared it. Remove that redundant clear; generation filtering already excludes old errors. |

Before the runtime fixes, the first independent run had **16 failures out of
107 cases** covering response bodies/default routing, handoff publication,
socket attribution/completion, and native probe ownership/context. The initial
focused post-fix run passed **132/132**. DNS recovery cases were additionally
checked by tracing the exception paths and running the new startup regressions.

The additional new-network error regression failed before removal of the clear.
Real owned loopback HTTP tests also verify that stalled success bodies obey read
timeouts and that cancellation interrupts body reads on both probe paths. A
first timeout assertion needed to accept the JVM's "Read timed out" wording;
that was a fixture correction, not another production finding. Exact-limit and
unknown-length empty body controls prevent an unnecessarily strict reader.

## Local verification

| Gate | Result |
| --- | --- |
| Complete Android unit suite, two consecutive runs | **539 tests / 39 suites; zero failures, errors, or skips** on each run. |
| Build-logic tests, forced fresh execution | **14 passed**, zero failures/errors/skips. |
| Android lint | **Zero errors; 25 warning issues** in local XML. Dependency, API/target compatibility, and style advisories remain; the supplied review's 19-warning count is not this machine's result. |
| Instrumentation source | `compileDebugAndroidTestKotlin` passed; not executed on a phone. |
| Functional debug APK | `assembleFunctionalDebug`, bundled-core verification, HEV ABI/hash verification, and the pinned libXray builder contract passed. |
| Panel syntax/static/behavior | All four shell files passed syntax checks; **66 static / 197 behavior checks** passed in Windows Git Bash. |
| ShellCheck | Error-level check passed for panel, startup wrapper, and shell tests. |
| Worker | Syntax, **65 behavior tests**, and Wrangler dry-run packaging passed; no Worker deployment. |
| Mock benchmark budgets | **All six passed**; not tunnel throughput measurements. |
| Standalone diagnostic fixtures | **14 + 9 Python tests** and the Go retained-HTTP suite passed locally. Linux/race/native-runtime checks belong to hosted CI, not these Windows results. |
| Whitespace and scope | Clean diff check; no changes to server/Worker runtime source, config builder, native pins, MTU, DNS policy, transport defaults, or tuning. |

Final local APK:
`g2ray_app/app/build/outputs/apk/debug/app-debug.apk`.
SHA-256:
`bd823eb4e1931efc146a671d1a10a6a01d3ac53345c4c8c488156085324bb6a2`.
Its signing certificate matches the previously installed APK's certificate.
This is a functional debug artifact, not a signed production-release acceptance.
Native libraries, APKs, raw test logs, and private settings remain ignored.

The owner requested continuing **without device tests**. No new phone
installation, playback, throughput, handoff, Doze, crash, or battery result is
claimed for this revision. Previous device records still belong to their
recorded APK hashes; this patch has no measured bandwidth improvement.

## Remaining release gates

1. Install in place without clearing app data and confirm startup, ping-all,
   DNS-failing hostname to saved-IP recovery, cancellation, and direct-GitHub
   wake on the target phone. Verify Android's VPN-default selection on-device.
2. Test actual Wi-Fi/LTE handoffs, screen-off/app-return, blackholed routes, and
   repeated idle-resume. Keep [idle-stall issue #1](https://github.com/z2roozsh/G2rayXCodeLeafy/issues/1)
   open until the target-device acceptance resolves the intermittent stalls.
3. Complete bounded real YouTube/social-media playback and ChatGPT/Claude
   streaming sessions. Earlier single transfer samples and an unfinished video
   attempt do not establish sustained speed or uninterrupted playback.
4. Complete long sessions, unplugged Doze, loaded latency, another OEM, and a
   same-production-key minified release upgrade with saved settings preserved.
5. Measure IPv6-only and UDP/QUIC-dependent use cases. Existing IPv4-oriented DNS
   and UDP/443 restrictions are not universal platform coverage. Aggregate
   server resource admission also remains a separate acceptance/hardening task.

Real socket duplication/protection still needs Android kernel/device evidence;
Robolectric's owned descriptor fixtures prove ordering, not kernel routing.
Health endpoint completion is not proof that every app/page/stream will work.

[GitHub documents a maximum active Codespace lifetime of 12 hours](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle),
even during active use. Wake/recovery cannot preserve existing remote TCP
sessions when that host stops. Continuous hosting and independent exits are
required for stronger around-the-clock availability guarantees.

## Rechecking this candidate

From `g2ray_app`, with the Android SDK/JDK and pinned real AAR available:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleFunctionalDebug compileDebugAndroidTestKotlin
.\gradlew.bat -p buildSrc test --rerun-tasks
```

Follow [PRODUCTION_TEST_MATRIX.md](g2ray_app/PRODUCTION_TEST_MATRIX.md) for
device/release acceptance. Never replace a user's signing identity, uninstall
their app, or clear their data to make an upgrade pass. No live engine restart
is required by this Android-only runtime change.

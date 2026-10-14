# Route Discovery Cancellation And Resource Bounds

## Reproduced Problems

Base revision: `5f9bbcc116813e8c077ccad6421d7be78b18f6ff`.

`RouteCandidateDiscovery.queryDoh` used blocking `Call.execute()` without a
coroutine cancellation hook. `G2RayViewModel.startVpn` cancels discovery and
joins its job before starting the VPN. If the scan was waiting on DoH, that
join could wait for requests it no longer needed. A canceled or superseded
scan could also keep consuming HTTP resources after a network change.

The same method read a complete, unbounded response with `body.string()`.
Limiting candidate count did not limit response memory before JSON parsing.

Two new tests failed before the production change: timely cancellation and
oversized-response rejection. The original eight-test XML, including both
failures, is retained privately under
`data/verification/route-discovery-before-20261014.xml`; it is not published.

## Implementation

- Use OkHttp's asynchronous callback API and connect coroutine cancellation
  to `Call.cancel()`. Responses are closed on both successful and failed
  parsing; callbacks do not write account, selection, or measurement state.
- Give each DoH operation a five-second coroutine deadline, including time
  queued in OkHttp. A resolver's deadline returns no answers from that
  resolver, not a false failure of saved routes. Parent cancellation still
  propagates rather than becoming a successful discovery result.
- Limit a DoH JSON body to 64 KiB before parsing, checking both declared size
  and bytes read. Unknown-length/chunked bodies cannot bypass the limit.
- Preserve system answers, saved candidates, source labels, deduplication,
  the existing public-IP filter, hostname/candidate limits, and the curated
  ECS query set. Real libXray verification is still required before a newly
  discovered address is persisted as a usable profile.
- Reuse the provided client. Physical-network DNS, protected sockets,
  certificate verification, connection pools, and network-generation guards
  are unchanged. These are proxy-endpoint discovery queries, not a change
  to browsing DNS, FakeDNS, routing, MTU, or transport defaults.

The prior synthetic cancellation test was adapted to report its cancellation
cause inside `IOException`, as expected by asynchronous OkHttp callbacks.
The original propagation assertion remains. Additional tests directly check
that running and queued calls are canceled, rather than only observing that
the coroutine returned.

## Verification

Executed locally with the existing pinned AAR/toolchain:

```powershell
cd C:\Users\Shayan\Desktop\Work\7_G2ray\G2rayXCodeLeafy\g2ray_app
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
```

Final results: 423 tests in 34 suites, zero failures/errors/skips; lint zero
errors and 25 warnings. Functional debug and instrumentation packaging
passed, including `verifyHevBundle`, `verifyLibXrayBuildContract`, and
`verifyBundledCore`.

The ten discovery tests all passed. The cancellation case completed in
0.050 seconds in this controlled JVM run. The queued-request case, using one
OkHttp execution slot and seven requests, completed in 5.051 seconds and
confirmed all calls were canceled while retaining the saved candidate.
These timings include the tests themselves; they are not phone startup or
network-latency measurements. Known- and unknown-length oversized bodies
were rejected while distinct system and saved candidates were retained.

Debug APK SHA-256:
`d2cfcd9152f74eb2dfa875ae863593162e98b79a7c74265bba9e7d02d32c3d54`.
The APK was built, not installed. Earlier physical-phone acceptance belongs
to the previously installed APK, not this revision.

## Limits And Remaining Work

This fixes DoH operation cancellation and response bounds. The separate
blocking Android/system `getAllByName` branch is unchanged; its platform
cancellation/timeout behavior still needs a focused review. Likewise, a
blackholed first endpoint and early standby selection remain controlled
runtime/device scenarios, not newly certified by these JVM tests.

No phone/device tests, media session, sustained upload/download, or
loaded-latency test was run. The user's small-probe budget and request to
finish without more device testing remain in force. The server and Worker
runtime code, UUID/config/exports, and direct-GitHub wake preferences were
not changed. The broader objective remains open; this change is not a
flawless, zero-drop, universal-speed, or 100/100 certification.

## API Reference

The existing OkHttp 4.10.0 API was checked against its
[Call contract](https://github.com/square/okhttp/blob/parent-4.10.0/okhttp/src/main/kotlin/okhttp3/Call.kt)
and [RealCall implementation](https://github.com/square/okhttp/blob/parent-4.10.0/okhttp/src/main/kotlin/okhttp3/internal/connection/RealCall.kt).
Cancellation interrupts a call where possible; it is not proof that every
platform-native DNS operation can be interrupted immediately.

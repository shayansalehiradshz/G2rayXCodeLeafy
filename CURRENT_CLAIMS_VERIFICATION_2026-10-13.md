# Current Claim Verification: 13 October 2026

## Decision and Scope

Reviewed the supplied report and seven-file patch against `efae321`. The pasted
patch and downloaded patch have the same SHA-256, and the initial dry check
matched that revision. The evidence ZIP was not available. Its claimed results
were not adopted as our own. No subagents were used.

The reported recovery and URL defects are valid and are corrected here. One
additional regression in the proposed retry handling was reproduced and fixed
before publication. These are control-plane/recovery changes, not proof that
retained-flow stalls, video buffering, packet loss, or throughput are cured.
The product is still not certified for flawless production use.

## Reproduced Findings

| Finding | Evidence and result |
| --- | --- |
| Failed aliases restart an old cooldown | The new regression returned 180,000 ms instead of 120,000 ms after 60 s of failed backup work. A cached HTTP response was reinterpreted as a new hint. Hints are now captured once as elapsed-clock deadlines, including expiry at zero. |
| Worker timeout drops a known hint | A 600 s hint followed by a hung backup lost its retry metadata at a 500 ms deadline. The remaining 599,500 ms now survives the timeout, and the backup coroutine is cancelled. |
| Shared wake timeout drops an in-flight hint | The enclosing Worker/GitHub deadline could fire before the inner operation returned. Retry metadata is now published before fallback and retained outside each timeout body. Caller cancellation still propagates instead of being converted into a timeout. |
| Direct fallback restarts the Worker wait | A failed direct fallback consuming 60 s returned the original 180 s. It now returns 120 s; a healthy fallback still succeeds immediately. Returned direct timeouts also retain the earlier boundary. |
| Invalid URL escapes the typed result | Real Retrofit rejected `https://worker.example:65536` after the old normalizer accepted it. Invalid authorities/ports now fail as terminal configuration before network access. |
| Equivalent aliases are different or accept credentials | Uppercase scheme/host and explicit default port failed canonicalization; authority credentials were accepted. The existing OkHttp parser now validates/canonicalizes aliases; credentials in the URL are rejected. Nondefault HTTPS ports and bracketed IPv6 remain supported. |
| Proposed fix delays a recently successful wake | The candidate patch converted `wake_recently_succeeded` into a global cooldown: a 2.5 s route deadline returned an unnecessary 177,500 ms wait. That response suppresses another start, not health polling. It is now excluded from global recovery cooldown capture. Actual API rate limits retain their wait. |

Four regressions failed against the original implementation. Four further
timeout/fallback regressions failed after adding only the nullable metadata
contract and fake-clock injection, before implementing deadline accounting.
The successful-wake suppression regression then failed against the candidate
fix. All nine now pass without removing or weakening their assertions.

Further tests cover cancellation, expired/shorter/later hints, overflow, time
spent asleep in a simulated elapsed clock, a working backup, terminal errors,
invalid primary plus valid backup, and safe error messages. Pasted credentials
are not echoed in URL validation failures.

## Implementation

One small `WakeRetryCooldown` helper replaces duplicated relative-delay
bookkeeping. Worker, shared wake, and VPN recovery each retain the deadline at
their cancellation boundary. Progress carries the remaining delay before a
fallback that can hang. Terminal errors remain terminal; known delays survive
alternate-profile work and temporary loss of the underlying network.

Persistent retry uses short cancellable timer intervals and Android's elapsed
clock, which includes sleep, rather than restarting a full uptime-based wait
after resume. It adds no wake lock or network probe. Scheduling is still subject
to Android suspension and dispatch latency; this is not a measured battery or
physical Doze improvement. See [Android's clock documentation](https://developer.android.com/reference/android/os/SystemClock).

The API wait rules follow [GitHub's rate-limit guidance](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#handle-rate-limit-errors-appropriately).
The additional wake-only distinction comes from the actual Worker implementation
and its existing optional successful-wake suppression test.

Changed runtime files under `g2ray_app/app/src/main/java/com/g2ray/client/`:

- `network/WakeRetryPolicy.kt`: bounded monotonic accounting and elapsed-clock wait.
- `network/WorkerRouteCoordinator.kt`: capture once, early propagation, URL validation, and correct suppression scope.
- `network/CodespaceWakeCoordinator.kt`: preserve metadata through direct fallback and shared deadlines.
- `vpn/G2RayVpnService.kt`: preserve hints through service deadlines, alternate attempts, network loss, and persistent retry.

Changed tests under `g2ray_app/app/src/test/java/com/g2ray/client/network/`:

- `WorkerRouteCoordinatorTest.kt`: before/after URL, fallback, timeout, cancellation, and suppression coverage.
- `WakeRetryPolicyTest.kt`: deadline arithmetic, overflow, simulated sleep, and cancellation.
- `GitHubCodespacesApiTest.kt`: inject the matching fake clock without changing assertions.

Also updated `g2ray_app/PRODUCTION_TEST_MATRIX.md` and added this report.
No server settings, Worker implementation, native sources/binaries, dependencies,
credentials, UUIDs, or persisted account/profile schema were changed.

## The Other Recommendations

| Recommendation | Assessment and disposition |
| --- | --- |
| 1. Adaptive scoring / ML | Scoring, failures, variation, transfer penalties, and hysteresis already exist. No arbitrary mixed-unit scoring rewrite. Network/exit provenance remains useful follow-up. |
| 2. Automatic multi-Codespace continuity | Multiple accounts exist; independent-server continuity is a larger design, not replicated TCP state. No new infrastructure or automatic paid capacity created. |
| 3. More negotiated transports | HTTP/2, XHTTP, TLS/REALITY, and raw TCP are different layers. No unsupported transport added through HTTP forwarding. |
| 4. Network callbacks | Already present, with network binding and generation guards. No duplicate listener added. |
| 5. Adaptive keepalive | Several layers already have keepalive. The retained-flow cause is still unknown; no speculative frequency change. |
| 6. BBR/TFO | Congestion control exists; TFO is gated. Kernel/provider capabilities and measured loaded latency must precede tuning. |
| 7. MTU reduction | MTU 1500 alone is not a reproduced defect in this stream-proxy architecture. No magic MTU applied. |
| 8. Bandwidth intelligence | Bounded transfer samples and penalties already exist. They are not sustained-capacity measurements. |
| 9. App exclusions | Optional user policy, not an automatic performance fix. No apps excluded. |
| 10. Parallel DNS/cache | Parallel proxied DoH and caching already exist. No extra resolver or arbitrary TTL override. |
| 11. QUIC/Hysteria | Requires compatible reachable endpoints; public HTTP forwarding is not raw UDP hosting. No fictional speed gain claimed. |
| 12. Regions/warm standby | Requires actual ready hosts, phone measurements, and usage budgets. A stopped host is not warm. |
| 13. Telemetry | State, traffic, diagnostics, route measurements, and feedback already exist. Per-flow/provenance evidence remains needed. |
| 14. Pinning/rotation | Existing HTTPS and encrypted storage retained. No brittle certificate pinning or invented credential-rotation API. |
| 15. Flow/sealed state | Already implemented; no unrelated state-machine rewrite. |
| 16. Battery profiles | Needs physical measurements. No claim from simulated clocks that battery performance improved. |
| 17. QUIC migration/MPTCP | Needs end-to-end support; does not preserve arbitrary current server TCP sockets across host stops. |
| 18. Service-aware exits | Requires independent egresses and measurements, not just several IPs for one Codespace. |

## Verified Locally

- 320 Android tests in 28 suites; zero failures, errors, or skips on the final tree.
- Android lint: zero errors, 26 warnings. The external report's 12 warnings were not reproduced; none were suppressed to match its count.
- `assembleFunctionalDebug` and `assembleDebugAndroidTest` succeeded. The matching local AAR was available and the native hash/API/build-contract gates remained enabled.
- Server Bash syntax and 66 static checks passed on Windows; they are not Linux runtime proof.
- All 65 Worker behavior checks, syntax, and packaging dry-run passed. Dry-run did not deploy anything.

The initial candidate's 319-test pass did not expose the new suppression case;
that additional regression is included in the final 320-test run. A nullable-body
compile error in its guard was corrected before final validation/publication.

## Hosted Verification

Both workflows completed successfully on the fix commit
`5a98092285eaa27d493ce218e61d8a7252c69912`:

- [Static Tests](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38372592959): 66 static, 193 server behavior, and 65 Worker checks; deterministic mock budgets; error-level ShellCheck; and Worker packaging dry-run.
- The same run verified the official pinned-core download checksum, saturated upload queue expiry at 30 s, and real-Xray validation of all ten generated/migrated profile and WS combinations.
- [Android Tests](https://github.com/z2roozsh/G2rayXCodeLeafy/actions/runs/38372592758): unit tests, lint, instrumentation compilation, and native build-contract gates; actual Linux HEV fixtures distinguish a local 44-byte SYN-ACK from upstream payload and verify half-open/established-session restart cleanup.

These are hosted Linux/source/fixture results, not live Codespaces-edge or
physical-phone acceptance. CI's source-only Android job does not package the
functional APK; that build succeeded locally with the matching AAR.

## Installation and Checks

Open the canonical `g2ray_app` project in Android Studio and build/install the
updated app. A Git pull does not update an installed APK. Existing accounts,
profiles, keys, and server configs do not need re-importing or rotation.
These Kotlin changes require no Worker dashboard change or new binding.

From `g2ray_app`, with JDK 21 and the matching core AAR:

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleFunctionalDebug assembleDebugAndroidTest
```

For server/Worker checks, use the existing CI and commands in
[`PRODUCTION_TEST_MATRIX.md`](g2ray_app/PRODUCTION_TEST_MATRIX.md).
The functional debug APK is at `g2ray_app/app/build/outputs/apk/debug/app-debug.apk`.
Its SHA-256 is
`f0b14855acb63ef360da3a6075acf88fda38681604d83a9ce2ab9b16e08abc82`.
No APK installation or new phone acceptance was performed: the user explicitly
requested continuing without device tests.

## Still Open

[Issue #1](https://github.com/z2roozsh/G2rayXCodeLeafy/issues/1) remains open.
The prior phone record contains 13-20 s retained-flow failures despite working
fresh sockets. Nothing in this patch changes the packet engines, MTU, transport
settings, or third-party apps' retained sockets. No idle-stall cure is claimed.

Required acceptance remains paired retained/fresh flows, real handoff and Doze,
Stop during cooldown, live failed/hung recovery backends, long AI/video sessions,
large transfers, loaded latency, battery use, and wider devices/clients.

IPv4 DNS defaults, UDP/443 blocking, private-address/BitTorrent policies,
aggregate upload admission budgets, network/exit-scoped stored measurements,
bounded alternate DNS bootstrap, and best-effort KV state remain separate
coverage/design limits. Their existence does not justify untested defaults or
a new proxy/core fork in this narrowly scoped recovery fix.

The Codespace was stopped when checked and was not woken solely for these
Kotlin changes. That is not evidence of why it stopped or a live route-health
claim. GitHub's documented [12-hour active lifetime](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle#maximum-lifetime-of-a-codespace)
still limits uninterrupted service from one host. No numerical rating, universal
coverage, commercial superiority, or bug-free certification follows from these
checks.

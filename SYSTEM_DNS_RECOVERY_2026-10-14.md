# Bounded System DNS And Cancellation

## Reproduced Failure

Source baseline: `239d48e6e68a23d5ed5af6ebf819c47cc63ec59f`.

The previous change canceled DoH discovery requests, but the separate system
lookup still called `Network.getAllByName` synchronously. Starting VPN cancels
and joins discovery first, so an obsolete system lookup could delay startup.
Endpoint bootstrap and Worker/GitHub DNS also had no application-level answer
deadline. A new cancellation test failed before this implementation. Its
original XML is kept privately in
`data/verification/system-dns-before-20261014.xml`.

## Changes

- `NetworkDnsResolver` uses Android's asynchronous, cancellable `DnsResolver`
  API on API 29+. Queries retain the exact selected physical `Network`,
  normal platform caching/retry flags, and both available address families.
- The synchronous interface has a ten-second DNS-answer budget. A deadline
  cancels the platform query and reports a retryable `UnknownHostException`.
  Healthy cached answers return immediately; there is no fixed delay.
- API 24-28 uses a shared pool capped at four daemon workers, with no task
  backlog. A native resolver that ignores interruption can occupy a worker
  until Android finishes it, but cannot create unlimited workers or queued
  stale requests. Exhaustion fails explicitly and remains retryable.
- A shared `interruptibleDnsLookup` adapter connects coroutine cancellation
  to the blocking wait, restores cancellation when the synchronous API wraps
  an interruption, and rejects results returned after cancellation. Startup
  and discovery use this adapter. A canceled startup cannot continue into
  core launch, including when another local branch returns an IP backup.
- `SocketProtector` still refuses default-network fallback while the VPN is
  active without a selected physical network. Numeric endpoints bypass DNS;
  their TLS SNI/Host and exported/stored URI are not rewritten by this change.
- Existing lifecycle and network-generation checks remain in place.

These are bootstrap/control-plane lookups. Browsing DNS, FakeDNS, MTU,
transport tuning, XHTTP/WS link formats, account storage, and wake-backend
preferences are unchanged. The previous five-second DoH-operation deadline
and 64-KiB DoH-response bound remain separate.

## Verification

Local tests used already-cached Robolectric SDKs to avoid large downloads on
the user's metered connection:

```powershell
cd C:\Users\Shayan\Desktop\Work\7_G2ray\G2rayXCodeLeafy\g2ray_app
.\gradlew.bat -ProbolectricSdks=23,24,31,35,36 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
```

`robolectricSdks` is an optional test-only selector. It is not set in the
repository or CI. Robolectric's existing default fixture SDK includes 23;
this does not change the production minimum Android API of 24. The declared
full matrix additionally exercises API 28, 29, and 34 in hosted CI.

The cached-SDK local suite passed 437 cases in 35 suites, with zero failures,
errors, or skips among the selected cases. A fresh combined run also executed
all 88 build tasks successfully with `--rerun-tasks` before the final added
late-result test; that final test and the full local suite subsequently
passed. Lint reported zero errors and 25 warnings. Functional debug and
instrumentation APK packaging passed the pinned native hash/ABI/build gates.

Tests cover exact physical-network forwarding, IPv4/IPv6 results, empty and
failed answers, native-query deadline cancellation, late callback rejection,
coroutine cancellation and worker-thread interruption cleanup, legacy worker
exhaustion without queue growth, numeric bypass, and missing-network rejection.
Existing stale lifecycle/network DNS completions still must not start a core
or trigger an unrelated packet-engine fallback.

The cancellation adapter initially exposed an interruption/error-type mismatch
in a new test. That was fixed in the shared production adapter and applied to
both coroutine callers. Startup/socket test fixtures were updated to intercept
the new public DNS API and use fresh resolver instances for each Robolectric
shadow configuration; their original startup/identity assertions remain.

Two existing ping-all workflow tests hit their unchanged ten-second deadline
in one combined run. They passed individually, in a full standalone suite,
and in the fresh forced combined run. The root cause of those isolated
timeouts is not proven. No speculative ping implementation change or longer
deadline was used; diagnostic failure messages were added and retained.
Android CI now preserves JUnit/lint XML artifacts for later reproduction.

Debug APK SHA-256:
`79f482844e229ed489ae8f4dba9cbc59fa66abcb5655da459cdeb23dda93c92c`.
This APK was built, not installed. Older physical-device results are not
attributed to this revision. Source changes do not update an already installed
phone application.

## Acceptance Boundaries

No new phone, media, sustained-throughput, loaded-latency, or handoff test was
run. The user declined more device testing and authorized only small probes.
Platform/OEM behavior of the new API still needs physical acceptance. The
answer budget does not guarantee completion of every OS DNS retry, and old
native resolvers may outlive a canceled caller within the fixed worker cap.

The server and Worker runtime source, UUID/config/exports, native core pins,
and direct-GitHub wake selection are unchanged. No live engine restart is
required for the server checkout to receive these client-source changes.
Issue #1 and the broader performance/long-session acceptance remain open.
No instant-everywhere, zero-drop, zero-CAPTCHA, or 100/100 claim is made.

## References

- [Android DnsResolver API](https://developer.android.com/reference/android/net/DnsResolver):
  the API-29 address-query overload accepts an explicit network, executor,
  cancellation signal, and asynchronous callback.
- [Pinned kotlinx.coroutines 1.10.2 interruption implementation](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/jvm/src/Interruptible.kt):
  used to preserve cancellation without changing the synchronous OkHttp DNS
  error contract.

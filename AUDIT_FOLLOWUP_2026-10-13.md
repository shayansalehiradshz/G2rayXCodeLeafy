# Audit Follow-up: 13 October 2026

## Scope and Evidence

The supplied external review examined `04bfaaf`. This follow-up started from
`0f9ff1c` and checked the findings against the canonical Android source in
`g2ray_app`, server control plane, Worker, and pinned HEV source. The external
evidence ZIP was not supplied; its claimed reproductions are not counted as
tests performed here. No subagents or new phone tests were used.

"Corrected" below means the code path was changed and automated checks passed.
It does not mean every device, ISP, third-party app, or long session was tested.

## Finding Disposition

| ID | Disposition | Change or remaining boundary |
|---|---|---|
| F01 | Corrected; device acceptance pending | Added an atomic HEV SOCKS-to-TUN TCP payload counter. Local SYN-ACK/ACK/RST traffic no longer proves upstream health. Repeated failed health quorums eventually recover even while payload advances; payload grants a finite grace period, not unlimited suppression. Displayed TUN totals retain their original meaning. |
| F02 | Corrected | Normal schema-2 bundles preserve the original links, including XHTTP `extra`. An explicitly named `.legacy.json` schema-1 bundle strips advanced tuning only for older importers. Export hashing, cleanup, and rollback include the new format. Re-import the current bundle to update previously imported untuned links. |
| F03 | Corrected | An imported valid profile can start directly without Worker or GitHub credentials. A configured wake backend is used after a real route failure, not as a prerequisite for every connection. |
| F04 | Partly corrected | Generated and migrated server inbounds use routing-only sniffing, preserving explicit destinations. Client content sniffing is disabled normally; experimental FakeDNS retains its metadata mapping. Server policy sniffing still has a server-first inspection budget; that latency has not been eliminated. |
| F05 | Corrected on Linux | Deadline tasks own a process group, so TERM-resistant/reparented descendants are killed on timeout. Temporary-file or group initialization failure returns 125 instead of running unbounded. Intentionally detached managed daemons remain separate. |
| F06 | Corrected | Explicit unknown Worker IDs return 404, including single-node deployments. Canonical Codespace names remain accepted. Android rejects successful responses for a different or unidentified expected Codespace. |
| F07 | Corrected; service/device acceptance pending | Transient failures preserve desired-connected intent and schedule serialized retries with bounded, jittered backoff. Explicit Stop, VPN revocation, and configuration/auth/quota failures stop retrying. Each recovery attempt has a deadline; overall retries remain enabled until stopped. |
| F08 | Ownership guard added; original race not reproduced on a device | A process-wide native lease covers the entire VPN/probe lifetime, with VPN-start priority. Start preempts and drains background scans. An unconfirmed native stop retains ownership and cannot be overwritten by another run. |
| F09 | Corrected | A bounded alternative list reserves capacity for another transport when available, rather than spending the entire budget on distinct XHTTP IPs. |
| F10 | Coverage limitation retained | Default destination DNS still requests IPv4. IPv6-only names are not supported by that policy; IPv6 literals are not automatically equivalent to failed DNS. No blanket IPv6 blackhole was added. A dual-stack change needs both engine and exit-path testing. |
| F11 | Intentional policies / architecture | UDP 443, private-destination and BitTorrent restrictions remain. HTTP-based Codespaces forwarding is not a general public UDP endpoint. Universal app/protocol coverage is not claimed. |
| F12 | Open resource risk | Corrected the comment: 100 buffered posts is about 6 MB for tuned 60 KB clients but can approach 100 MB for legacy 1 MB posts before authentication. This is not a byte cap. Lowering only the count or rejecting old clients without a compatibility plan could recreate upload failures; a byte-budget core change remains future work. |
| F13 | Critical-path delays bounded | KV operations have a one-second deadline. Optional history/quota work is deferred with `waitUntil`; response flags no longer claim deferred writes completed. An unavailable authoritative registry returns retryable 503 instead of silently selecting dashboard defaults. Dashboard-only registry mode is available without making basic setup more complex. |
| F14 | Open best-effort storage limitation | KV read-modify-write history/cooldowns are still non-atomic and eventually consistent. Timeouts do not cancel an underlying KV write. History is not a ledger or a distributed lock; strict coordination would need a different storage primitive and migration. |
| F15 | Corrected | Partial test failures remain visible in stored health. Live standby routes need at least two fresh successful samples, no failed samples, and no stored error. A Quick 1/1 sample is provisional, not standby qualification. |
| F16 | Open | Persisted route/performance measurements are not fully scoped to a physical-network/exit epoch. Session handoff checks help but do not make older ISP measurements universally valid. Network-scoped cache migration remains needed. |
| F17 | Corrected with a bounded wait | Start cancels background optimization/delay jobs, drains their native ownership, then starts VPN. Blocking OkHttp transfer reads are cancelled with their coroutine. A synchronous native ping must finish its bounded operation before reuse; it is not force-killed unsafely. |
| F18 | Corrected | Snapshot the network generation after initial physical-network selection. Successful full-device startup proof no longer always incurs a redundant SOCKS proof; SOCKS remains a diagnostic for failed full-path checks. Listener readiness remains required. |
| F19 | Open | Endpoint bootstrap still selects a preferred address rather than retaining a full bounded DNS answer set. Multi-address bootstrap must preserve socket binding and avoid recursive DNS through a broken VPN. |
| F20 | Unreproduced risk retained | PID-only runtime-lock ownership can theoretically suffer PID reuse/stale-cleanup races. Existing process ownership tests pass, but they do not prove that interleaving impossible. No speculative lock rewrite was shipped. |
| F21 | Corrected at coordinator/attempt boundaries | Worker calls and sleeps respect remaining budgets. A shared outer deadline covers Worker-to-direct-GitHub fallback, and an entire service recovery attempt has a three-minute budget. This bounds cooperative network operations; it is not a guarantee that blocking JNI can be safely interrupted mid-call. |
| F22 | Measurement limitation retained | Small transfers and few delay samples do not establish throughput capacity, meaningful tail latency, packet-loss guarantees, or long-video reliability. Keep the production scenario matrix and actual-network comparisons as acceptance gates. |

## Additional Reproduced and Corrected Defects

1. **HEV restart abort:** a stopped half-open TCP connection had no HEV session
   yet, so cleanup left it in lwIP's global PCB list. Reinitializing lwIP's pools
   on the next start reused stale state and aborted on a TCP assertion. Shutdown
   now drains active, TIME_WAIT, and bound PCBs before removing the interface.
   The pinned-engine Linux fixture passes half-open, real-payload, and repeated
   restart scenarios. The four Android ABI libraries were rebuilt from the same
   patch. This is not a new Android crash reproduction or device acceptance.
2. **Interactive generation failure status:** feedback captured the status of
   a completed `if` rather than the failed generator. It now preserves the real
   nonzero status and has a regression test for both failure and success.

## Verification Performed

| Check | Result | Boundary |
|---|---|---|
| Android JVM/Robolectric tests | 276 tests, 27 suites, zero failures/errors/skips | Not Android service/device or real-network acceptance. |
| Android lint | Zero errors, 26 warnings | Warnings remain; not a warning-free release claim. |
| Android instrumentation source | `compileDebugAndroidTestKotlin` passed | Compiled, not executed on a phone. |
| HEV bundle and libXray build contract | Both Gradle verification tasks passed | All four rebuilt HEV ABIs and patch hashes checked; not new APK installation. |
| Native engine fixture | Local SYN-ACK: 44 TUN RX bytes, zero upstream payload; real SOCKS payload counted; restart scenarios passed | Linux, pinned HEV and static dependencies, socketpair instead of a privileged TUN. Added to CI. |
| Server static checks | 66 passed | Source contracts, not Internet traffic. |
| Server behavior checks | 186 passed in an isolated Linux Codespace directory | Current checkout copied to `/tmp`; no live config used by fixtures. |
| Real Xray config validation | 10 profile/WS combinations, generated and migrated, accepted by installed Xray 26.5.9 | Validation only; no listeners or port publishing. No engine upgrade. |
| ShellCheck | Error-level checks passed | Does not imply all warning-level findings are absent. |
| Mock control-plane benchmarks | All six budgets passed | Not Internet latency, throughput, packet loss, or video playback. |
| Worker | 63 behavior checks, syntax checks, packaging dry-run passed | Mocked APIs/KV; no dashboard deployment or phone-to-Worker cold-wake test. |

## Rollout and Remaining Acceptance

- Server changes can be pulled normally. Keep the current UUID when regenerating
  to apply the server sniffing migration without invalidating saved links.
- Rebuild the Android app in Studio to include the new Kotlin and HEV libraries.
  Existing APKs do not receive native changes from a server pull.
- Manually replace dashboard Worker code from `worker/codespace-waker/src/index.js`.
  Basic deployments still need only the existing token, wake key, and Codespace
  name; no new required variables. Nothing here auto-deploys a Worker.
- Previously observed retained-HTTP/2 idle stalls remain tracked in
  [issue #1](https://github.com/z2roozsh/G2rayXCodeLeafy/issues/1). These changes
  do not prove that issue fixed. Follow `g2ray_app/PRODUCTION_TEST_MATRIX.md` for
  idle resume, backgrounding, handoff, dead upstream, manual Stop during retry,
  cold wake, long video, and both packet engines when device tests resume.
- GitHub documents an automatic Codespace stop after 12 hours, including active
  use. Recovery cannot preserve every established stream across a host stop.
  [Codespace lifecycle](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle).

No 100/100, zero-drop, 300 ms, universal compatibility, or production-readiness
guarantee follows from these results. The completed work improves recovery
correctness and removes reproduced defects; the open items and physical-network
acceptance above remain visible rather than being replaced by a subjective score.

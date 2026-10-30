# XHTTP lifecycle and physical-phone follow-up

30 October 2026. Production changes: `bf3a224812624e181a0f1ca20f38a521708c5779`.
This is evidence, not a 100/100 score or a guarantee of perfect networking.

## Independent Claim Review

| Supplied claim | Reproduction and action |
| --- | --- |
| XHTTP setup ignores caller cancellation | Reproduced on the actual patched client and through its VLESS handler. Added an independent eight-second physical TCP/TLS setup budget, prompt canceled-waiter return, per-request cancellation and pipe/mux cleanup. Earlier physical setup deadlines remain effective. No whole-HTTP-client or established-stream timeout was added. |
| Failed XHTTP TLS leaves its socket open | Reproduced against an invalid local TLS peer. Failed setup now closes the underlying socket. Tests cover Chrome and native/unsafe TLS paths. |
| XHTTP read deadlines are no-ops | Confirmed on both pins. The isolated old server exceeded a one-second VLESS handshake limit and retained its waiting handler. The maintained server now has an equivalent per-session VLESS handshake guard, canceled after authentication or fallback. The candidate closes the incomplete session in 1.00 s. This does not claim that all general XHTTP read/write deadline methods are implemented. |
| Maintained server returns the wrong reader-close error | Confirmed and corrected with none/writer/reader/both precedence tests. No causal claim about historical stalls. |
| Optional WS early data races | Already independently reproduced in the preceding review. Not fixed or enabled by this work; normal saved WS profiles do not require it. Do not enable `?ed=` as a speculative speed optimization. |
| A keepalive-only XMUX object preserves the other defaults | False on the pin: actual configuration tests give zero connection/request/lifetime values for that partial object, versus 3 / 600-900 / 1800-3000 with the defaults. Diagnostic variants preserve all other effective values. |
| One tuning removal proves the best default | False. The previous removal changed several fields, and transfer, endpoint and carrier conditions matter. Saved links and factory defaults are unchanged. |
| Worker readiness always preserves its configured stability interval | A final recheck failed, and a deterministic clock/timer fixture reproduced premature readiness when only 10 ms remained for a required 25 ms interval. The Worker now returns unconfirmed readiness instead of shortening that interval. Health and warm-wake paths are covered; neither restarts an already available host. |

A further ownership test found that `stream-up` closed only its download request.
It failed before the correction; both upload and download requests now close and
the running-stream count returns to zero. A real HTTP/2 shared-connection fixture
also verifies that closing one stream does not interrupt another or its reuse.
Cancellation does not replay a user's upload or AI submission.

The implementation follows the [Go connection contract](https://pkg.go.dev/net#Conn)
and uses stream/request ownership rather than deadlines on a shared carrier.
The [upstream issue](https://github.com/XTLS/Xray-core/issues/6590) was corroborating
context; local red/green reproductions, not the issue alone, justified the fixes.

## Build, Source And Deployment

Client pins remain Xray `5ca6f4b7d4dc20a881d4330e498892697627ec0c`, libXray
`80263da83e96b2972455b0a94b13ee1a10e51391`, Go 1.26.10. All previous observer,
measurement, controller, WebSocket, retry and XHTTP integrity backports remain.

The incremental lifecycle patch is separately applied and attested by the native
builder, config-time checks and Gradle verification tasks. The native AAR was
rebuilt from source with matching metadata, not relabeled. Final hashes:

| Artifact | SHA-256 |
| --- | --- |
| Client lifecycle patch | `51C1A17ABE696BCCFD4C829983BD0671F5C11EB2C4C802C34E04C1D0FF5FAD39` |
| Native AAR | `2CC1874B2AD4AC972C33B8A4353D4E29A0F2C300FB55C176094CE39E7115763A` |
| Fresh debug APK | `4D33B2CF0ACF1694AFDE28262EAF498F76DA15480CA1E9ED6EDEA0AD6EA6C4C5` |
| Server handshake patch | `D74DC962F0573C52374493D97F59278B8F6A3AF6500CEEEA8F10A884112BA347` |
| Maintained server binary | `58346E6210AF0B885FEC3BD24C4BC1C55C9BE4CF0191C7F980E6AEBA4FBA376F` |

Server pin remains `1b1647cd9ddf0abc34b981d4611fc03bcac1ca74` / 26.10.21,
Go 1.27.3, with build marker `1b1647c-g2ray-lifecycle`. The committed Bash
build recipe and a separate Windows cross-build produced the same binary hash.
The approved live promotion kept the previous binary, config, UUID and receipt
for rollback. Parsed config and exact UUID bytes were unchanged, the actual
running `/proc/PID/exe` matched the candidate, and two public probes returned 200.

Both old-server and maintained-server isolated WS/XHTTP pairs passed using a
separately built client from the actual Android-pinned patched source, including
ten-second idle/reuse checks. The candidate also accepted the live configuration.
These are isolated loopback checks, not ISP or full-phone performance evidence.

CI now additionally pairs the actual maintained Docker image binary with the
actual patched Android-pinned client, retaining the checksum-verified official
server control. Server patch/builder/fixture changes trigger that workflow.

- [Android CI](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/41020691284): passed on the production revision. [The follow-up acceptance-guard revision](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/41022479791) and [owner-approved setup revision](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/41025297238) also passed; neither changes the native payload.
- [Final Android/native CI](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/41027434085): both jobs passed on `78f9a5e`, including the fixture routing guards. Later Worker/documentation changes do not modify the Android source or payload.
- [Server/Worker CI](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/41020702978): passed on the production revision, including the maintained image and real handshake acceptance.
- [Final server/Worker CI](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/41028100422): all four jobs passed on `422edac`, including Linux behavior, the corrected Worker stability checks, pinned-core acceptance, maintained-image verification, ShellCheck and packaging.
- Local Android: 789 unit tests, 56 suites, zero failures/errors; 41 build-logic tests; lint zero errors and 24 warnings; both APKs and native gates built successfully.
- Focused lifecycle races passed ten repetitions, and the full original XHTTP package passed. Explicit TLS budget, failed-socket cleanup, earlier deadlines, request close, packet cancellation, real VLESS cancellation and stream ownership have separate fixtures.
- 47 Python checks, 67 static checks, 71 Worker checks, syntax and critical ShellCheck checks passed. The final Worker suite passed twice after its new deterministic regression failed before the fix. Linux behavior tests passed in CI. The unchanged Git Bash behavior suite twice failed its stopped-parent deadline test on this Windows host; it is not reported as a local pass.

The additional Worker source fix is commit `422edac`. It does not change the
current direct-GitHub phone setup and was not deployed to Cloudflare. Syntax
checks and the local example-config packaging dry-run passed. For a
dashboard-managed Worker, publishing the updated `worker/codespace-waker/src/index.js`
is still a separate rollout; no Worker deployment is implied by a Codespace pull.

The first final APK contained incremental-packager dead space: 236.9 MB despite
only about 164 MB of referenced ZIP data. Preserving the generated package state
and repackaging produced a 164,049,466-byte APK. All 80 non-signature payload entries
have identical SHA-256 values to the previously installed candidate; this is a
packaging-size correction, not a measured tunnel-speed improvement.

## Physical-Phone Evidence

Samsung SM-A528B, Android 14, cellular network. Tests install with `adb install -r`
without clearing accounts or profiles. Temporary failover/auto-optimization changes
are backed up and restoration and selected-profile equality are asserted. No global
MTU, DNS, transport, packet-engine, tuning or radio default was changed.

Before the new native installation, the owner-approved same-edge WS selection
was completed and verified in the UI.
Its subsequent three-minute video test did not pass: about 65 s observed Playing,
eight buffering transitions, first play 13.47 s. A following saved-XHTTP comparison
reported first play 5.25 s and zero buffering, but reached its 512 MiB traffic cap
before the required playback interval. It is incomplete, not a pass. These were
sequential runs with uncontrolled video adaptation/cache, not matched bitrate trials.

An explicitly approved, temporary token-protected endpoint serves only deterministic
synthetic data, caps requests, and returns the received upload digest. It does not
proxy arbitrary URLs or retain uploaded content. Endpoint tests reject unauthenticated,
oversized and wrong-digest requests. A first HEV/WS control on the initial lifecycle
candidate completed five 8 MiB downloads and five 2 MiB uploads with exact endpoint-
confirmed hashes. The subsequent final AAR also includes the stream-up close fix.

One saved-XHTTP control overlapped the planned server restart. Its six failed
transfers remain recorded but are excluded from tuning comparisons; they cannot
identify a tuning cause or regression. The subsequent controlled matrix uses the
fixed server and final client, with a fixed edge and failover/auto-optimization off.

### Tuning And Final Acceptance

The completed eight-variant matrix used two windows (two, then three matched
download/upload pairs per variant). It changes POST size, POST interval and
keepalive only, using temporary
parsed URI copies and retaining every failed trial. Later checks assert the active
runtime profile still matches the controlled copy after recovery; diagnostics emit
only a tuning hash, not credentials. A setting is not promoted from its fastest run.

| POST bytes / interval ms / keepalive s | Window 1 verified transfers | Window 2 verified transfers |
| --- | --- | --- |
| 1,000,000 / 10 / 0 | 1/4 | 6/6 |
| 1,000,000 / 10 / 20 | 1/4 | 6/6 |
| 1,000,000 / 30 / 0 | 0/4 | 3/6 |
| 1,000,000 / 30 / 20 | 4/4 | 6/6 |
| 60,000 / 10 / 0 | 1/4 | 6/6 |
| 60,000 / 10 / 20 | 1/4 | 6/6 |
| 60,000 / 30 / 0 | 4/4 | 0/6 |
| 60,000 / 30 / 20 | 1/4 | 0/6 |

Total: 46/80 transfers completed with verified hashes; 34 timed out. No completed
transfer had a digest mismatch. These failures are not erased by the passing unit
tests or the earlier 64 upload controls. Time/order variation is substantial,
and this single device/network/endpoint experiment does not establish a universal
default or isolate a causal contribution from every field. Only the 1M/30/20
variant completed both entire windows; it proceeds to separate engine, failover
and video acceptance before any owner-approved separate profile is saved.

The first final-APK follow-ups completed all three pairs on Native/WS, Native/
XHTTP 1M/30/20, and HEV/XHTTP 1M/30/20 with failover enabled. A three-minute
HEV/WS video check with failover off then reported first play 6.21 s, 173.77 s
observed Playing, zero buffering transitions and no budget exhaustion. Resolution
was not independently measured; it is not a matched-bitrate proof that disabling
failover improves video. Original settings remain restored after each case.
The matched-duration HEV/XHTTP 1M/30/20 check with failover off also passed:
first play 4.77 s, 175.18 s observed Playing, zero buffering transitions, about
18.5 MB mobile traffic including background apps, no budget exhaustion.
Those startup samples exceed a proposed three-second target; neither sample
certifies a resolution, uncached startup, a percentile or another platform.

The same candidate with the original failover-enabled setting also passed a
three-minute video check: first play 5.62 s, 174.28 s observed Playing, no budget
exhaustion and zero reported buffering transitions. The owner approved saving a
separate copy only after Native, failover and video checks passed.

All ten final acceptance fixtures completed successfully, including Ping All
(24 results, 22 usable, 20.5 s), current saved-profile inspection (15 WS profiles,
zero using early data), and direct GitHub warm status (873 ms). GitHub reported
the host available, not verified data-plane readiness; no stopped-host/cold-boot
claim follows from that warm status check.

The short WS background-idle test retained all three inner HTTP/2 connections:
the resumed requests completed in 224/231/244 ms without new connects. Its actual
gap was 166.16 s, including ActivityScenario restoration, not an immediate probe
at exactly 120 s. A separate immediate-background probe is therefore included.
An eight-second mobile-data outage recovered automatically: tunnel verification
5.66 s after physical return, complete independent quorum by 10.44 s. That is a
successful bounded recovery sample, but misses proposed 5 s / 10 s targets and
does not establish a percentile or a real Wi-Fi/LTE handoff.

The separate XHTTP immediate-background check passed after exactly 120.008 s,
without activity restoration before probing. Its three retained-pool API requests
completed in 1,873/1,273/1,034 ms, but timing traces show replacement connections,
not survival of the original sockets. Fresh-socket controls then completed in
1,536/860/1,092 ms. This did not reproduce a 20-second hang in that short plugged-in
sample; it does not establish instant resume, deep Doze or overnight reliability.

After Native, failover and both video checks passed, the separately approved setup
action added and selected `Mobile checked XHTTP 1M-30-20` for the current Codespace.
It asserted that every original profile, all wake credentials and the settings
remained unchanged. The selected copy is not a global default or a rewrite of
server exports. The final persisted-copy test, using its saved URI without a
runtime tuning override and with failover enabled, completed another three 8 MiB
downloads and three 2 MiB uploads, all with correct remote-confirmed hashes.
It also confirmed that no user domain-bypass rule covers the owned endpoint.
Those test/setup guards are compiled, linted and physically exercised, not claims
that small probes prove complete page loading or maximum Internet bandwidth.
With failover enabled, individual flows may use compatible alternative routes;
these checks do not prove that every transfer used the selected primary edge.

All twelve final acceptance fixtures passed, plus the explicit owner setup and
saved-copy integrity actions. That count is separate from the 34 failed transfers
in the diagnostic tuning matrix; no failed trial was converted into a pass.

The final installed APK hash matches the fresh APK above; APK v2 signature
verification passed. For each of four ABIs, the AAR library matches Gradle's merged
input and the APK library matches its stripped output. Only arm64-v8a was tested
on physical hardware. The normal app was then connected through its existing
Quick Settings action, without adding/rearranging tiles. Home displayed the new
selected copy, `VPN connected and route verified`, `Route: Ready`, and `HEV TUN`.
No instrumentation was left running after that final user connection.

Temporary public port 9444 was restored to private; the authenticated fixture and
its owned local forward were stopped, and server/phone fixture tokens removed.
Only the existing VPN ports 443 and 8443 remain public. User accounts, server
configuration and UUID, original links, preferences and mobile-data settings were
preserved. Diagnostic data and rollback artifacts remain ignored and private.

## Remaining Boundaries

These fixes establish neither perfect performance nor universal compatibility.
Optional WS early data and general XHTTP deadline semantics remain limitations.
The separate Windows ChatGPT attachment failure is still unreproduced. Real Wi-Fi/
LTE handoff, unplugged overnight Doze, a second device, signed-release upgrades,
independent leak acceptance and long real AI sessions remain separate acceptance
work. A single Codespace cannot provide indefinite uninterrupted hosting. No video
result here certifies a particular resolution or a three-second startup guarantee.

# Approved Origin TFO Rollout and Phone Follow-up

Date: 14 October 2026, Asia/Tehran. The first rollout/check timestamps are
13 October UTC. Server and initial phone runtime source:
`7ce81075a7b46cafd3257bf965459beb47ad987a`. Android startup changes and their
separate follow-up checks are described below.

## Scope and Preservation

The user explicitly approved a controlled live restart to apply the tested
origin TCP Fast Open-off setting. The live checkout was clean at `d217843`
and fast-forwarded to `7ce8107`. Xray remains 26.7.28 (`5ca6f4b`, Go 1.26.5).

An owned, private rollout script took a config rollback copy, generated the
startup-migration candidate, and compared canonical JSON before stopping the
engine. The only config delta was `tcpFastOpen: true` to `false` on the managed
`direct` freedom outbound. Real-core config validation passed. The engine
changed from PID 243 to 62946, its listener/local XHTTP probe passed, and the
supervisor was checked. UUID-file and export-file hashes remained identical.
The rollback copy remains private under `data/tfo-rollback.yqrdMa/`.

The Android functional and instrumentation APKs built with the unchanged
pinned native artifacts and their verification gates. Both installed in
place, without clearing saved accounts, keys, or selected profiles. The
installed base APK independently matched this local SHA-256:

`5f4c0091942f9e3ab428de6a3c0a50f417b32956cccf621f4b53462e3d913c6c`.

The test phone is the connected Galaxy A52s, Android 14, arm64. User-authorized
unlocking was used when required; no PIN is stored in source, test scripts,
reports, or evidence logs. No subagents were used.

## Completed Phone Checks

- Three packaged-core tests passed: native loading, DNS/TUN configuration,
  and actual multi-outbound core start/stop.
- Direct GitHub wake/control-plane access with VPN off passed in 967 ms. The
  host was already available; `route_ready=false` did not claim a verified
  data path. This is not a new cold-start or Worker-reachability result.

Both idle tests used the existing full-device VPN harness, actual saved
profiles, unchanged health assertions, and `resumeVpnActivity=false`. The
screen was sent to sleep after the background-idle event. Each test recorded
noninteractive state at its post-idle boundary, with no VPN activity reopening.
Normal standby behavior was not disabled, so these are not forced
single-outbound acceptance results.

| Engine / selected transport | Actual idle | Retained HTTPS response times | Original connection IDs | Fresh HTTPS response times | Result |
| --- | --- | --- | --- | --- | --- |
| Native / XHTTP | 60,624 ms | 314 / 337 / 376 ms | All three unchanged | 740 / 1,127 / 861 ms | Passed |
| HEV / WS | 60,589 ms | 282 / 337 / 342 ms | All three unchanged | 1,801 / 2,186 / 2,124 ms | Passed |

These are the two engine/transport cases that previously produced 20-second
retained-flow failures. Their new successes are meaningful physical-device
evidence, not permission to erase the earlier failures or claim every
long-duration/app/network scenario is resolved.

Native/XHTTP initial 2 MiB download / 64 KiB upload completed in 1,516 / 2,382
ms. HEV/WS completed them in 4,089 / 874 ms, with HEV TUN byte counters advancing.
These are short transfer checks including setup, not sustained Mbps or video
buffering benchmarks. Warm verified startup was 8,253 ms on Native/XHTTP and
3,193 ms on HEV/WS; a subsequent Native repeat started in 2,580 ms. The first
Native attempt included an initial route-retry phase, which is not hidden.

Android exit records following these checks report `finished inst` force-stop
teardown, not a new crash or ANR. That does not certify absence of crashes
under different workloads or devices.

## Completed Public-Route Checks

A separate Linux Xray client used the private, unchanged current bundle and
the real public Codespaces edge. Strict TLS remained enabled. The standalone
HTTP/2 diagnostic records connection identity and reuse; it did not reopen
Android or restart the real server between checks.

| Exported transport | Actual idle | Retained response times | Original connection IDs | Fresh response times | Result |
| --- | --- | --- | --- | --- | --- |
| XHTTP | 70,038 ms | 42 / 143 / 99 ms | All three reused | 141 / 149 / 343 ms | Passed |
| WS | 70,030 ms | 17 / 119 / 83 ms | All three reused | 171 / 155 / 331 ms | Passed |

This closes the formerly missing public-route rollout step for the tested
bundle. It does not substitute for the phone's ISP path; the phone results
above are separate. All raw private run records remain ignored under `data/`.

## Completed Five-minute Phone Repeats

The first two longer runs had a 310-second background interval, a manually
triggered screen-off portion, and a noninteractive end boundary. Native/XHTTP
completed retained-pool requests in 777 / 741 / 858 ms; HEV/WS completed them
in 1,812 / 1,844 / 3,839 ms. All six original sockets were replaced. These
passed the unchanged response gate but do not prove either original socket
survival or a fully controlled five-minute screen-off interval.

The instrumentation harness was then extended to put the device to sleep
before starting its elapsed-realtime idle timer and assert noninteractivity
throughout the wait. No VPN activity is reopened before requests resume.
These four runs used the original runtime APK hash above and the updated
test-only APK. Normal standby behavior remained enabled.

| Engine / selected transport | Actual screen-off idle | Retained-pool response times | Original connection IDs | Fresh response times | Result |
| --- | --- | --- | --- | --- | --- |
| HEV / XHTTP | 310,009 ms | 1,067 / 1,412 / 1,433 ms | All replaced | 1,115 / 1,387 / 1,137 ms | Passed |
| Native / WS | 310,007 ms | 1,551 / 374 / 343 ms | First replaced; other two reused | 1,445 / 1,728 / 1,641 ms | Passed |
| Native / XHTTP | 310,008 ms | 1,067 / 1,234 / 858 ms | All replaced | 691 / 878 / 923 ms | Passed |
| HEV / WS | 310,009 ms | 3,597 / 1,818 / 2,128 ms | All replaced | 1,798 / 1,822 / 2,125 ms | Passed |

No 13-20-second retained stall reproduced in these runs. This is not an
instant-response claim: the slowest request still took 3.6 seconds. Verified
startup also varied from 3.6 to 11.9 seconds across these four runs. Debug-only
phase timings were added to diagnose startup rather than weakening health
verification. USB-powered screen-off is not unplugged deep Doze.

## Ping-all and Startup Follow-up

An opt-in device test called the same ViewModel method as the Home ping-all
action, using the 26 saved configs without a second activity optimizer scan.
It required every entry to enter testing state and finish with either a real
delay or a visible error, persist its result, preserve selection, and release
the native core. It passed in 29,483 ms: 25 successes and one explicit failure.
This is real action-method/native-path evidence, not a rendered button-click
or a guarantee that every saved config works.

Two additional startup defects were reproduced with failing source tests:
primary hostname DNS failure discarded a compatible working IP backup, and
a DNS completion after lifecycle invalidation could still start the core.
Startup now preserves compatible IP fallbacks, retains up to three distinct
physical-network DNS answers behind the existing seamless-failover setting,
and rechecks lifecycle and network generation before native startup. Original
SNI, Host, UUID, transport, tuning, and persisted selection remain intact.
Verified backup routes receive slots before additional answers from a hostname;
unrelated templates cannot become fallback routes. Cancellation still propagates.

A further regression failed because Auto tried a second packet engine after
a DNS-time physical-network change. This now returns the retryable network
failure to normal recovery instead. These are source regressions, not a live
blackholed-first-DNS-answer or handoff acceptance result.

The first startup-fix runtime installed in place matched SHA-256
`6e27f574adf8e8feaf93a72d9c23c6c9aa65620e2b148370499bfcabff7aefa6`.
Its three packaged-core tests passed. The ordinary ViewModel Start VPN path
verified Auto/HEV XHTTP in 4,523 ms, passed a controlled 60,008 ms screen-off
interval, and reused all three original HTTP/2 sockets with 313 / 321 / 359 ms
responses. Fresh requests completed in 2,109 / 1,489 / 1,525 ms. The subsequent
Auto network-change guard is checked separately before final installation.

The next runtime APK independently matched SHA-256
`eab00cb88ec61afbcd59d76d34800b50f8421a6220bd0d08df5f1b568b661bef`.
Its three packaged-core tests passed again. Actual native core stops recovered
twice in each engine, preserving selection and proving HTTPS after each stop:
HEV recovery plus the independent HTTPS checks completed in 9,744 / 7,199 ms;
Native completed them in 4,599 / 7,231 ms. These durations include detection,
verification, and three sequential HTTPS requests, not only core startup.

The protected direct-GitHub API check while VPN was active returned Ready in
852 ms. It used the already available host, so it is not cold-wake acceptance.
The Home ping-all method passed again in 27,441 ms with 25 successes and one
explicit failure, preserving selection and releasing the native runtime.
The ordinary UI-start check verified Auto/HEV XHTTP in 4,439 ms and passed
60,007 ms of controlled screen-off: original sockets were reused with 299 /
345 / 413 ms responses. Fresh responses took 1,076 / 1,130 / 2,158 ms.

Two separately timed repeats also passed: Native/WS after 60,008 ms with
289 / 316 / 313 ms retained responses, and UI/HEV XHTTP after 60,006 ms with
315 / 338 / 345 ms. All six original connections were reused. Native/WS
startup took 11,414 ms, including 7,968 ms in core startup and 2,745 ms in
full-path verification; endpoint resolution took 2 ms. UI/HEV startup took
5,415 ms, with 23 ms core startup and 2,097 ms full-path verification.

The phone's global `persist.log.tag=s` initially suppressed phase records.
A temporary `log.tag.G2RayVpnService=I` override made only this diagnostic tag
visible; it was restored to its original empty value after the checks. The
global setting was not changed. Stage timings narrow the Native slowdown but
do not by themselves distinguish lease wait from JNI initialization.

An independent source regression then reproduced optional native ping batches
continuing their second/third endpoint attempts while a VPN start waited for
the same runtime. The fix checks pending VPN ownership and coroutine
cancellation between completed native calls, releases its lease, and cleans
temporary configs. It never interrupts or force-stops an in-flight JNI call.
Additional core-stage timings distinguish ownership wait, setup, native
startup, and listener readiness; no numerical latency improvement is claimed
until the updated runtime is measured separately.

## Final Startup-priority Build

The startup-priority runtime installed without clearing app data and its
installed APK independently matched SHA-256
`97f101101c6dd88d1ef21652ced3c78683596265ce63a5b9961a3669489b6b41`.
All 357 Android tests in 30 suites passed, with zero failures/errors/skips.
Lint completed with zero errors and 26 warnings. Functional APK and
instrumentation packaging passed their unchanged native verification gates.
Four new regressions were observed failing before their fixes in this work:
primary DNS fallback, stale lifecycle completion, unnecessary Auto fallback
after a DNS-time handoff, and batch retries retaining priority over VPN start.
Other added tests check bounds, template preservation, cancellation, and cleanup.

The final native packaged-core suite passed all three tests. A probe-only
Native/WS run reached verified startup in 3,137 ms and passed its three initial
HTTPS requests in 1,448 / 1,381 / 1,589 ms. Its measured core lease wait was
1 ms, setup 1 ms, native startup 18 ms, listener readiness 3 ms, and full-path
verification 2,248 ms. This is a new measured sample, not a controlled proof
that the priority fix alone caused the difference from the earlier 11.4-second
startup.

ADB lost the phone during that run's controlled idle interval. No terminal
instrumentation result was received, so the final idle run is INCOMPLETE,
not passed. Subsequent planned priority-build recovery/UI/ping checks were
not started. The earlier completed runs above remain separate evidence.
On reconnection, inspect whether instrumentation remains active before
starting another run. The last tag-restore command could not reach the
disconnected phone; restore `log.tag.G2RayVpnService` to its prior empty value
before ordinary use. The global logging setting was never changed.

The added `smallProbesOnly` device-test option skips transfer samples without
relaxing HTTPS, idle response, lifecycle, or recovery assertions. Its final
probe-only run printed the transfer-skip event; no throughput claim is made.
The default transfer tests remain unchanged.

The unchanged server and Worker source passed 197 server behavior checks,
66 server static checks, all four Bash syntax checks, 65 Worker checks,
Worker JavaScript syntax and packaging dry-run, and all six deterministic
mock benchmark budgets. Windows Git Bash passes do not replace Linux CI or
the separate live Codespace results. Hosted results must be checked against
the exact pushed revision.

## Acceptance Limits

Only small probes and 2 MiB download / 64 KiB upload samples were used, per
the user's data budget. The completed tests do not certify sustained media/AI
streaming, loaded latency, actual handoff, unplugged deep Doze, battery cost,
other OEMs, or every exported WS ALPN/edge variant. Issue #1 remains open.
No native payload, dependency, account, endpoint, or transport default changed.

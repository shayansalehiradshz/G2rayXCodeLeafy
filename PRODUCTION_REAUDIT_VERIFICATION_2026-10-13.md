# Production Re-audit Verification: 13 October 2026

## Scope and Decision

Checked the supplied 14-file patch and report against `ccd763c`. The patch
applied cleanly in a dry check, but was reviewed and applied selectively through
the file-edit tool, not trusted on the basis of the other AI's claimed results.
The supplied evidence ZIP was not available. No subagents were used.

The deadline, retry-policy, and registry findings are valid. They are corrected
here, with further edge-case coverage. The older live-core queue defect was
independently reproduced and the approved live upgrade completed. The project
is still **not certified for flawless idle resume or a public production
stability claim**. The previous phone failures remain evidence, not replaced
by Linux or unit-test success.

## Verified Findings and Changes

| Finding | Verification and disposition |
| --- | --- |
| Deadline excludes EXIT cleanup | Reproduced before fixing: a one-second deadline returned success after a three-second EXIT trap. The entire wrapper lifetime is now monitored, not only status-file publication. |
| Cleanup failure can report success | Reproduced before fixing: EXIT status 39 was returned as 0. The wrapper's unsuccessful wait status now propagates. |
| Status-write failure can hide a command failure | Reproduced before fixing: a command exiting 37 returned 0 when status publication failed. The child explicitly exits with the command status. Tests repeat fast success/failure cases 25 times; process-group validation distinguishes completed children from live wrappers even after status publication. |
| Rate-limited GitHub 403 becomes a permanent VPN failure | Confirmed in the old coordinator/service chain. Typed retry eligibility and delay now survive Worker/direct-GitHub coordination and reach the persistent retry scheduler. Known rate limits remain retryable; authentication, quota, identity, and invalid-request failures do not. JVM tests cover the actual coordinator chain and delay calculation, not live rate-limited service recovery. |
| Server retry hints are ignored | Primary reset time, secondary cooldown, Worker body/header hints, and hinted GitHub 5xx failures now retain their delay. The later valid boundary wins; hints cannot shorten local backoff or overflow it. The scheduler's wait is cancellable and capped at 24 hours per hint, with local jitter retained. |
| Configuration errors retry endlessly | Missing backend settings, wrong Worker identity, known missing dashboard bindings, deleted/archived/failed host states, and terminal request codes retain terminal eligibility. Network transitions cannot override terminal failures or immediately bypass a returned retry hint. No-network waiting does not hide a returned permanent failure. |
| Worker ID/name collision selects another token/host | The new regression failed against the old Worker: an explicit ID selected an earlier node's matching name. Exact IDs now win, and a disabled exact ID cannot fall through to another name alias. Canonical-name fallback remains when no ID matches. |
| Recorded core is older than the pin | Confirmed live: Xray 26.5.9. An isolated two-post queue with a blocked third POST timed out after 35 seconds on 26.5.9; the separately verified 26.7.28 binary released it with HTTP 500 at 30 seconds. The approved live upgrade now uses 26.7.28. This is not proof of an idle-stall fix. |

The retry changes extend the supplied patch: Worker JSON also carries the
primary reset boundary; a shorter header cannot defeat a longer body/reset
hint; hinted GitHub 5xx failures do not undergo premature internal polling;
and a failed direct fallback cannot discard the original Worker wait. A
successful fallback may still establish a working tunnel without waiting for
an unavailable control plane. Ordinary standalone-profile startup is unchanged.

Final review reproduced another lost-hint path: a primary Worker's 180-second
cooldown became `null` after a second Worker alias failed. Failed aliases now
carry the latest monotonic retry deadline; time spent contacting a backup is
subtracted rather than restarting the wait. A healthy backup still succeeds
immediately, and an authentication/configuration failure stays terminal.
Regression cases cover absent, shorter, and longer backup hints, network
failure, elapsed time, and a successful or terminal backup.

The retry treatment follows
[GitHub's rate-limit guidance](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#handle-rate-limit-errors-appropriately).
The queue mechanism is visible in the official
[26.5.9 source](https://github.com/XTLS/Xray-core/blob/v26.5.9/transport/internet/splithttp/upload_queue.go)
and [26.7.28 source](https://github.com/XTLS/Xray-core/blob/v26.7.28/transport/internet/splithttp/upload_queue.go).
The independent loopback result, rather than that source comparison alone,
is the evidence for the reproduced queue failure here.

## Checks Performed

| Check | Result | Boundary |
| --- | --- | --- |
| Android JVM/Robolectric | 301 tests, 28 suites, no failures/errors/skips | Full rerun after the additional Worker-alias fix, not physical service/network acceptance. The lost-hint regression failed before that fix. |
| Android lint | Zero errors, 26 warnings | The external report's 13 warnings were not reproduced in this toolchain. No warning suppression or dependency change was made. |
| Functional Android debug APK | Built successfully with the matching locally present AAR | Packaged-core gates were retained. The external review's missing-AAR limitation describes its environment, not this local checkout. |
| Instrumentation source and installation | Compiled; functional and test APKs installed in place | The phone was absent during initial automated checks, then reconnected for the later device tests below. Saved app data was retained. No YouTube acceptance is claimed. |
| Packaged-core device smoke | Three tests passed | Libraries loaded on the actual arm64 device, including a three-outbound core start/stop. Not Internet or idle-flow proof. |
| Native inputs | HEV ABI/hash and libXray build-contract checks passed | Native source/libraries were not changed by this patch. |
| Linux server behavior | 193 checks passed | Isolated Codespace fixture, unmodified assertions, no outer-PID discovery adapter. Includes cleanup and status-write regressions. |
| Static, syntax, ShellCheck | 66 static checks, Bash syntax, error-level ShellCheck passed | Not every warning-level concern or live network scenario. |
| Mock budgets | All six passed | Control-plane execution budgets, not bandwidth or RTT. |
| Worker | 65 behavior checks, syntax and packaging dry-run passed | No dashboard deployment or authenticated live Worker wake in this pass. |
| Queue comparison | Old core failed the new regression; pinned core passed | Loopback, two one-byte posts plus one blocked request, no real UUID or public queue saturation. Initial fixture padding was rejected; that attempt was corrected and not counted as a reproduction. |
| Xray configs | Ten generated/migrated profile/WS combinations passed on each version | Twenty validations total; not long-stream acceptance. |
| Authenticated live traffic | XHTTP 3/3 and WS 3/3 HTTPS destinations before and after upgrade | Real private schema-2 bundle/tuning and strict TLS, through temporary loopback SOCKS clients. Codespace-to-edge path, not phone RTT or idle reuse. |
| CI on `26323fe` | Android and Static Tests passed | Android source/native fixture jobs and all three server/Worker jobs. Hosted checks do not replace the later phone idle failure. |

The new optional tests use Python's standard library, bind only loopback, and
do not print private URIs/keys. The authenticated check explicitly disables
curl's `NO_PROXY` bypass and requires `--live`. The new CI job downloads the
Dockerfile's exact core pin, checks the official SHA-256 digest, and reruns the
queue expiry and generated-config checks. It does not contact private tunnels.

## Live Core Rollout

After explicit user approval, the official Linux archive was SHA-256 checked,
then the candidate validated against the actual private config before stopping
anything. The runtime lock serialized maintenance and the supervisor was
paused. The old binary, UUID, and config were retained under the private
`data/core-upgrade-rollback-*` directory; Git ignores that directory.

The binary was replaced atomically and the engine restarted. The running
`/proc/<pid>/exe` hash matched the verified candidate, UUID/config hashes stayed
unchanged, the route became ready, and all six authenticated requests passed.
The supervisor was restored. No rollback was required. Existing profiles do
not need re-importing because the UUID/config were not rotated.

Observed post-upgrade fresh HTTPS timings were 237-417 ms for XHTTP and
196-261 ms for WS **from the Codespace**, not from the phone. Those six small
requests do not establish a performance improvement, packet-loss rate,
sustained throughput, or 300 ms mobile latency.

The initial source rollout fast-forwarded the live repository to `26323fe` and passed
doctor plus six authenticated requests without restarting the upgraded engine
or changing UUID/config. Both source CI workflows passed, including the new
pinned-core queue/config job. A follow-up replaces `head` with `sed -n '1p'`
when reading version banners: `head` could close the pipe early and cause
SIGPIPE under `pipefail`, failing an otherwise completed validation command.

## Post-Upgrade Device Retest

After the user reconnected the Galaxy A52s running Android 14, both APKs were
installed with `adb install -r`; no uninstall or data clearing was performed.
These tests use ordinary unprotected full-device VPN sockets, real transfers,
and the unchanged two-of-three-within-six-seconds HTTPS gate. Automatic standby
routes remained enabled, so these are not forced single-outbound comparisons.
The idle measurements below are from `26323fe`; the later alias-only retry
change does not modify packet handling or claim to cure those idle failures.

| Test | Result | Evidence boundary |
| --- | --- | --- |
| HEV/XHTTP idle, requested 60 s | Passed; actual gap 106,771 ms | Screen interactive at both recorded boundaries. Retained-pool requests completed in 1,097-1,186 ms and fresh requests in 1,057-1,095 ms. Connection telemetry shows stale connections replaced, not proof every socket survived. |
| HEV/XHTTP two actual core stops | Both recovered automatically | Failure through recovery and three successful HTTPS requests took 8,741 ms and 7,380 ms. The service retained desired connection intent. |
| Native/XHTTP two actual core stops | Both recovered automatically | The same complete recovery/probe checks took 10,025 ms and 5,929 ms. Not a live API rate-limit or cold-wake test. |
| Native/XHTTP idle, requested 60 s | **Failed**; actual gap 66,181 ms | Screen noninteractive at the recorded boundaries. Retained requests: one 20,011 ms timeout, one 13,376 ms response, one 818 ms response. All three fresh requests passed in 698-918 ms. |
| HEV/XHTTP controlled screen-off, requested 310 s | Passed; actual gap 316,182 ms | The controller kept the screen off for 295 s after the idle marker; interactivity was false at both recorded boundaries. Retained-pool requests completed in 1,091-2,152 ms after replacing old connections; fresh requests in 1,077-1,188 ms. USB-connected, not a forced deep-Doze or multi-hour test. |
| HEV/WS idle, requested 60 s | **Failed**; actual gap 66,405 ms | Screen noninteractive at both boundaries. All three reused HTTP/2 connections timed out at approximately 20 s; fresh requests passed in 1,799-2,095 ms. WS is not a verified idle-stall fix. |

The failing Native run stayed Connected until explicit test cleanup; telemetry
showed no hidden VPN restart during idle. The retained/fresh distinction is
essential: basic health probes or a working new connection do not prove that
another app's older HTTP/2 connection is responsive. The queue upgrade has not
eliminated this issue, and the successful HEV run does not negate the failure.
Small 2 MiB download/64 KiB upload samples passed in these runs; they are not
sustained-throughput or video-buffering benchmarks. Private logs remain local.

After the final Worker-alias change, the rebuilt APK and test APK were installed
again in place. All three packaged-core checks passed again. Two actual core
stops per engine also passed again, including successful full-VPN HTTPS after
each recovery: HEV took 9,553/6,984 ms and Native took 4,729/7,258 ms through the
complete recovery-plus-probes check. These do not exercise API throttling or
the alias network-error branches; those have the JVM regressions above.

Android's exit history showed instrumentation-finished force stops, not new
JVM/native crash or ANR exits. A pre-install low-memory event remains in the
history for a cached/background process. It is not erased or called a new VPN
crash, and these short runs do not certify survival under memory pressure.

## Use and Remaining Acceptance

- The connected phone has the updated functional app installed. For other
  devices, open `g2ray_app` in Android Studio and build/install; a server pull
  does not update the Android application.
- Manually replace Worker dashboard code with
  `worker/codespace-waker/src/index.js`. The existing variables and shared key
  remain valid; no new required bindings or Wrangler deployment are introduced.
- Normal regression commands remain `bash tests/g2ray_static_tests.sh`,
  `bash tests/g2ray_behavior_tests.sh`, `npm test` inside the Worker directory,
  and Android unit/lint/functional packaging tasks.
- Optional safe queue acceptance:
  `python3 tests/xray_upload_queue_validation.py /path/to/verified/xray`.
- Optional authenticated acceptance on a running private server:
  `python3 tests/xray_authenticated_transport_check.py /usr/local/bin/xray g2ray-codespace-bundle.json --live`.

[Issue #1](https://github.com/z2roozsh/G2rayXCodeLeafy/issues/1) stays open:
the earlier phone record contains 11-20-second idle stalls, and the later
Native/XHTTP and HEV/WS retests reproduced failures after the core upgrade.
No global screen-wake tunnel
bounce, unverified MTU/mux tuning, or weakened idle gate was introduced.

Aggregate upload/session budgets, IPv6/protocol coverage, network/exit-scoped
measurements, bounded alternate DNS bootstrap, and best-effort KV consistency
remain limitations from the prior audit. A core queue fix is not a global byte
budget. Still required: repeatable stall-free retained/fresh idle acceptance,
handoff, explicit Stop during API cooldown, Worker/direct cold wake with these
retry changes, long AI/video streams, sustained large transfers, and wider OEM
coverage. Follow the
[production matrix](g2ray_app/PRODUCTION_TEST_MATRIX.md).

GitHub's documented
[12-hour maximum active lifetime](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle#maximum-lifetime-of-a-codespace)
still prevents uninterrupted lifetime of one host, even during active use.
No finite code review or test suite proves an absence of bugs or superiority
over other VPN clients.

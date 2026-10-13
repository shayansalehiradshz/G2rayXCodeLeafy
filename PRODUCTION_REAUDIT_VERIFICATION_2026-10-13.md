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
| Android JVM/Robolectric | 298 tests, 28 suites, no failures/errors/skips | Actual rerun after final Kotlin changes, not physical service/network acceptance. |
| Android lint | Zero errors, 26 warnings | The external report's 13 warnings were not reproduced in this toolchain. No warning suppression or dependency change was made. |
| Functional Android debug APK | Built successfully with the matching locally present AAR | Packaged-core gates were retained. The external review's missing-AAR limitation describes its environment, not this local checkout. |
| Instrumentation source | Compiled | Phone absent from ADB throughout this pass; no new installation, phone, or YouTube test is claimed. |
| Native inputs | HEV ABI/hash and libXray build-contract checks passed | Native source/libraries were not changed by this patch. |
| Linux server behavior | 193 checks passed | Isolated Codespace fixture, unmodified assertions, no outer-PID discovery adapter. Includes cleanup and status-write regressions. |
| Static, syntax, ShellCheck | 66 static checks, Bash syntax, error-level ShellCheck passed | Not every warning-level concern or live network scenario. |
| Mock budgets | All six passed | Control-plane execution budgets, not bandwidth or RTT. |
| Worker | 65 behavior checks, syntax and packaging dry-run passed | No dashboard deployment or authenticated live Worker wake in this pass. |
| Queue comparison | Old core failed the new regression; pinned core passed | Loopback, two one-byte posts plus one blocked request, no real UUID or public queue saturation. Initial fixture padding was rejected; that attempt was corrected and not counted as a reproduction. |
| Xray configs | Ten generated/migrated profile/WS combinations passed on each version | Twenty validations total; not long-stream acceptance. |
| Authenticated live traffic | XHTTP 3/3 and WS 3/3 HTTPS destinations before and after upgrade | Real private schema-2 bundle/tuning and strict TLS, through temporary loopback SOCKS clients. Codespace-to-edge path, not phone RTT or idle reuse. |

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

## Use and Remaining Acceptance

- Open `g2ray_app` in Android Studio and build/install the updated app. Existing
  installations do not receive Kotlin retry fixes from a server pull.
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
the earlier phone record contains 11-20-second idle stalls. This pass did not
measure whether the core upgrade changes them. No global screen-wake tunnel
bounce, unverified MTU/mux tuning, or weakened idle gate was introduced.

Aggregate upload/session budgets, IPv6/protocol coverage, network/exit-scoped
measurements, bounded alternate DNS bootstrap, and best-effort KV consistency
remain limitations from the prior audit. A core queue fix is not a global byte
budget. Still required: both-engine retained/fresh idle tests, handoff, explicit
Stop during retry, Worker/direct cold wake, long AI/video streams, sustained
large transfers, and wider OEM coverage. Follow the
[production matrix](g2ray_app/PRODUCTION_TEST_MATRIX.md).

GitHub's documented
[12-hour maximum active lifetime](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle#maximum-lifetime-of-a-codespace)
still prevents uninterrupted lifetime of one host, even during active use.
No finite code review or test suite proves an absence of bugs or superiority
over other VPN clients.

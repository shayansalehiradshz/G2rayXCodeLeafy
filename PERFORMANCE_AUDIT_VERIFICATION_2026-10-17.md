# Performance audit claim verification - 17 October 2026

Reviewed input: the supplied "Performance Audit: Path to 100/100", based on
`80eaee1`. Our starting checkout was `f5aa470`; the Android production source
was unchanged between those revisions. This review did not use subagents.

## Decision

Do not apply the report's proposed patch wholesale. Its 56/100 and projected
84/100 are subjective scores, not measured performance or a defensible platform
ceiling. Three narrowly scoped improvements are justified and implemented:

1. An independent watchdog restores a dead server supervisor while the host is
   running. Existing startup/panel attach already restored it, so the claim
   that no restart path existed was too broad. The missing independent path
   was real.
2. Config-derived XHTTP mode lookup is cached by content fingerprint, with
   atomic writes and invalidation on config changes. Twenty unchanged lookups
   now require one jq parse in the regression fixture. This improves export
   work, not packet latency or Internet throughput.
3. Warm Worker wake reuses the primary route precheck, then waits and confirms
   the same port. Healthy warm wake needs two primary requests, not three.
   Optional ports are not re-probed or allowed to establish primary readiness.

No Android transport, buffer, DNS, keepalive, MTU, QUIC, or recovery-timer
defaults were changed. No new tuning knobs or dependencies were added.

## Claim-by-claim assessment

| ID | Assessment and action |
| --- | --- |
| T1: 512 KiB buffer imposes a 20 Mbps cap | Incorrect model. Xray's pipe buffer is an internal queue, not the TCP unacknowledged-byte window. Dividing it by measured HTTP response time does not establish a throughput cap. Raising buffers increases possible memory use. Keep existing profiles; require matched transfer/loaded-latency tests before changing the default. |
| T2: global 6 MB/s upload ceiling | Overstated. Full 60,000-byte POSTs spaced 10 ms apart imply an approximate per-logical-session pacing bound, not one shared cap across the whole VPN. Actual POST sizes, write time and concurrency matter. The recorded uplink samples do not establish that this bound is the bottleneck. Keep measured tuning instead of halving the interval speculatively. |
| T3: compare XHTTP modes automatically | Sustained A/B testing is worthwhile. A small transfer is not enough to validate streaming through a buffering edge, idle behavior, or long AI streams. Do not add background mode scans or change the default without those results. |
| T4: UDP/443 block and QUIC | The block is real. TCP-only public ingress does not itself prove that inner UDP cannot be carried through a tunnel; UDP-over-TCP still has head-of-line limitations. A universal fallback penalty was not measured. Do not change both ends' established UDP policy on this evidence. |
| L1: repeated jq mode parsing | Confirmed and fixed with `XHTTP_MODE_CACHE_FILE` and `xhttp_active_inbound_mode()`. Active restricted configs still determine exported mode; an auto inbound still follows the current user preference. |
| L2: enable FakeDNS globally | Already supported and was enabled on the tested owner's phone. It can avoid some uncached DNS work, not every connection's DNS RTT. Compatibility remains relevant. Keep the existing opt-in default rather than claiming every user gets a measured gain. |
| L3: remove server sniffing | Not a verified general speed fix. It also serves the configured protocol policy. OPTIONS requests do not exercise VLESS payload sniffing and cannot benchmark this change. Keep routing-only destination semantics and existing policy pending a real traffic comparison. |
| S1: historical retained-connection stalls | Real historical failure and an acceptance concern, not proof that every current session still stalls for 20 seconds. The newer candidate record includes HEV original-pool replies at 231/245/237 ms after 60 seconds and Native replacement-pool replies at 703/721/648 ms after 310 seconds. Neither proves universal idle reliability. Native lacks the HEV payload counters; silence while an AI thinks cannot safely be treated as a dead tunnel. No invented native stall heuristic was added. |
| S2: xmux retirement kills AI streams | Incorrect. Pinned upstream retirement removes a client from reuse, but closes it only after its running-request count reaches zero. Do not extend retirement ages on the premise of scheduled stream termination. |
| S3: twitchy scoring and failure detection | Server export ordering does not migrate a running client's established flows. Hard failures already have separate health/recovery handling. Changing smoothing, fallback timeout, or promoting the scan's last recorded success does not establish a faster usable phone route. Keep these policies pending a matched routing study. |
| S4: established quiet socket can defer repair | A stale established socket is a possible limitation, but three quiet observations cannot distinguish it from a valid quiet stream. Forced private/public port cycling would also interrupt legitimate sessions. Keep the active-session protection; do not call the proposed forced repair regression-free. |
| S5: fixed 55-second dead-socket lifetime | The sum of keepalive idle and TCP user timeout is not a universal wall-clock failure bound. HTTP/2 PING and application verification also participate. A shorter keepalive interval has battery/radio costs. No unmeasured timer reduction. |
| S6: ingress reordering never changes exit identity | Reordering ingress candidates does not choose another Codespace exit by itself. However, one cloud host may have multiple outbound NAT IPs, and geolocation providers can disagree. Existing exit diagnostics explicitly preserve these distinctions. No additional change needed. |
| R1: missing supervisor watchdog | Partly confirmed and fixed. `start_background_tasks()` already repaired missing/stale supervisors on attach/start. A separate token-owned `--background-watchdog` now checks every 30 seconds. It restores only a dead/unowned supervisor, not one with a merely delayed heartbeat, and never directly restarts Xray. |
| R2: exact 4.5-minute recovery / cooldown blackout | A sum of nominal constants is not a measured outage, and it omits readiness, active-session protection, bounded tasks and client recovery. Cooldowns limit disruptive repeated actions. Do not lower them solely on this arithmetic. |
| R3: Android always waits 90/180 seconds | Incorrect as a fixed timeline. `routeHealthCheckDelayMs()` uses five-second confirmation retries after failed checks without advancing payload, rather than waiting 30 seconds after each failure. Probe duration and state also matter. Existing tests cover this behavior. |
| R4: redundant warm Worker probes | Confirmed extra work and fixed. Reuse only the matching primary probe, retain two consecutive successes and the stability interval, include the precheck in attempts/deadline accounting, and return promptly when only an optional port is healthy. The saving is one request, not a demonstrated universal 1-1.5 seconds. |
| R5: reduce handoff verdict delay | Potential experiment, not a proven regression-free improvement. Physical network settling and stale-result fencing matter. Current outage measurements are bounded samples, not a repeated real Wi-Fi/LTE A/B. Keep the default pending a second working network and device testing. |
| R6: try more backups before wake | One pre-wake backup is deliberate: many addresses share the same stopped Codespace, so probing more can only postpone a needed wake. More alternatives already exist later in recovery. No blanket increase. |
| R7: no boot receiver means no reboot support | Incomplete. Android's always-on VPN system-start action and the saved selection are already handled. An extra boot receiver is not required to duplicate that path. Always-on is an OS/user setting, not silent permission to turn VPN on at every boot. |
| R8: rank on Instagram/AI homepages | Generic probes do not prove media or AI experience, but authenticated/anti-bot homepages are not interchangeable latency endpoints either. Use explicit workload acceptance, not automatic logged-out homepage scoring or replayed AI submissions. |

## Implementation and safety boundaries

Server files: `g2ray.sh`, `tests/g2ray_behavior_tests.sh`,
`tests/g2ray_static_tests.sh`, and `README.md`.

Worker files: `worker/codespace-waker/src/index.js`,
`tests/worker_behavior_tests.mjs`, and `worker/codespace-waker/README.md`.

The watchdog uses the existing startup lock and supervisor generation token,
plus a separately owned PID. A queued restart rechecks its observed token
inside the lock. An explicit background shutdown invalidates that intent; a
stale queued restart cannot revive it. A foreign PID is never signalled merely
because it appears in a state file. Concurrent startup and lost-watchdog
rearming are regression-tested. Shutdown state-cleanup failures are reported.
When a process environment is readable, a persisted heartbeat cannot override
a token mismatch; this closes a recycled-PID false-health/foreign-signal case.
Token inspection consumes the complete process environment instead of risking
an early grep exit/SIGPIPE under pipefail.

The watchdog does not revive a stopped Codespace or guarantee recovery if both
processes fail. Existing startup/attach repairs a missing watchdog. A stale
heartbeat alone is deliberately insufficient to kill an otherwise owned live
supervisor. This is dead-process recovery, not a proof that every possible hang
is solved.

Worker changes remain compatible with manual dashboard deployment: replace
the code with `worker/codespace-waker/src/index.js`; existing variables/keys
are unchanged. A source push or packaging dry-run is not Cloudflare deployment.

## Validation

- Isolated Linux copy on the authorized Codespace: 205 behavior checks, 66
  static checks, 68 initial Worker checks, nine exit-location groups, ShellCheck error
  level, and six deterministic mock benchmark budgets passed.
- The process fixture uses real owned processes and deliberately kills only
  isolated test supervisors/watchdogs. Its poll sleep is shortened for tests;
  production uses 30 seconds. It also tests concurrent starts, old heartbeats,
  stale/foreign PIDs, explicit shutdown and state-write failures.
- Mode-cache regressions cover parse reuse, config replacement/corruption/
  deletion, auto-mode preferences, bad cache contents, write failure, and a
  concurrent config swap.
- Worker regressions require exactly two primary warm requests, stable spacing,
  reset after an intervening 404, deadline rejection, and optional-port isolation.
- Final Worker stress regression: an optional probe delayed 300 ms could
  consume a 150 ms primary confirmation budget in the first candidate. The
  regression failed before the follow-up fix. Primary confirmation now runs
  concurrently with optional diagnostics on its own deadline. All 69 Worker
  checks, 66 static checks and the packaging dry-run passed after that fix.
- Local Worker packaging dry-run passed without deployment.
- Before/after Linux reproduction: the deployed baseline accepted a current
  heartbeat for an unrelated live PID; the candidate rejected it. Both used
  isolated state and an owned test sleep process, not a production PID.
- Five alternating 30-lookup control-plane microbenchmarks measured baseline
  811/847/838/823/838 ms and cached candidate 135/97/96/98/101 ms. These are
  lookup timings on this host, not end-to-end export or Internet speed gains.
- Android rerun: 683 tests across 47 suites, zero failures/errors/skips. Build
  logic: 14 tests, zero failures/errors. Lint: zero errors, 25 warning issues.
  No Android production source changed and no new APK/device acceptance is
  claimed from this rerun.

Two local server runs were invalidated by concurrent source/test edits: one
file-equality check and one interrupted command name. They are retained in the
ignored verification logs, not counted as passes. The frozen Windows rerun
then passed all 205 server behavior checks.
An initial archive-only Linux static run lacked Git metadata; the rerun used an
isolated Git index with the actual tracked executable mode. No production test
was weakened to accommodate these setup issues.
An initial before/after harness tried to source the readonly panel globals
twice in one shell; it was corrected to load baseline functions in separate
subshells before the successful comparison above.

Private raw logs are under ignored `data/verification/performance-audit-20261017/`.
The prior device observations cited above remain in
`g2ray_app/CLAIM_REVIEW_2026-10-17.md`; they are not new device results from this
server/Worker patch. ADB currently lists no phone.

## Rollout evidence

The first source rollout, `7bfbb02`, passed both hosted workflows:
[server/pinned-core CI](https://github.com/z3roozsh/G2rayXCodeLeafy/actions/runs/38995329251)
and [Android/native CI](https://github.com/z3roozsh/G2rayXCodeLeafy/actions/runs/38995336598).
These runs precede the final optional-probe timing regression/fix described
above; final-source hosted results are linked in the handoff response.

The authorized active Codespace was fast-forwarded without changing its
config, UUID, Xray PID or process start time. Only an idle owned supervisor was
terminated for the live fault check. The watchdog restored it in 22 seconds;
the replacement supervisor and watchdog both passed ownership checks. Local
XHTTP remained HTTP 200 at 0 ms and edge XHTTP HTTP 200 at 153 ms. Those are
Codespace-side OPTIONS samples, not phone performance measurements.

No live Xray restart, UUID rotation, Android settings change, phone install,
Cloudflare deployment, or user-data reset was performed in this review. The
final Worker code still needs the normal manual dashboard deployment; its
environment variables and shared wake key are unchanged.

## Remaining acceptance

Do not convert these changes into a new speed guarantee or a 100/100 score.
The open work remains repeated natural unplugged idle/Doze, real Wi-Fi/LTE
handoffs, extended media and real AI streams, independent exit containment,
signed/minified upgrade acceptance and a second Android device. Matched
transport and loaded-latency A/B testing is required before further default
tuning. Current good samples do not establish a carrier RTT floor.

GitHub documents a mandatory stop after 12 active hours, even with a different
idle timeout. No in-container watchdog can override that host lifecycle.

## Primary sources checked

- [Pinned Xray pipe implementation](https://raw.githubusercontent.com/XTLS/Xray-core/v26.7.28/transport/pipe/impl.go): queue consumption wakes writers; it is not a TCP ACK window.
- [Pinned xmux lifecycle](https://raw.githubusercontent.com/XTLS/Xray-core/v26.7.28/transport/internet/splithttp/mux.go): retired clients close only after running requests finish.
- [Pinned XHTTP dialer](https://raw.githubusercontent.com/XTLS/Xray-core/v26.7.28/transport/internet/splithttp/dialer.go): per-session upload batching, spacing and request dispatch.
- [Android VPN lifecycle](https://developer.android.com/develop/connectivity/vpn#always-on): OS always-on service startup and user-controlled policy.
- [Codespaces lifecycle](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle): idle and maximum-lifetime limits.

# Audit Verification and Device Retest: 13 October 2026

## Scope

Reviewed the supplied audit against `1982b1c`, not against its patch alone.
Canonical Android source is `g2ray_app`. No subagents were used. This pass
includes new physical-device checks of the rebuilt Android/HEV binaries;
the earlier [audit follow-up](AUDIT_FOLLOWUP_2026-10-13.md) did not include them.

## Claim Disposition

| Claim | Evidence and action |
| --- | --- |
| Health monitoring stays dead after automatic reconnect | Not valid in this revision. `G2RayVpnService.markTunnelVerified()` already calls `startCoreHealthMonitor()` at line 1619. The persistent-retry success path clears its own job before calling that method. No redundant production call was added. A new opt-in device test stops the real core twice and verifies automatic recovery twice with both packet engines. This exercises automatic recovery, not every persistent-backoff branch. |
| Degraded latency samples move their own trigger threshold | Reproduced and fixed in `g2ray.sh:3378`. With a healthy 100 ms baseline, two 600 ms observations must trigger refresh. Previously the first degraded sample raised the baseline to 150 ms, so the second missed the +500 ms condition. Degraded samples no longer update the healthy baseline; healthy samples still adapt it. The audit's 400 ms example would not meet the original +500 ms threshold. |
| A default-on TUN restart after 120 seconds screen-off fixes idle stalls | Not established and not shipped. Screen-off alone does not prove dead NAT state, and a global restart interrupts healthy streams, uploads, and calls. The proposed opt-out has no Settings control in the patch. New device runs include both idle passes and failures; none proves this diagnosis or a universal 1-2 second reconnect. |
| Process-group initialization failure reaches an unbounded deadline wait | Not reproduced. Existing code verifies the group and returns 125 on initialization failure (`g2ray.sh:721`). Added a regression test for that guard; descendant and TERM-resistant writer tests still pass. The proposed fallback kills the parent before walking descendants and still ends in an unconditional `wait`, so its claimed bounded behavior is not reliable. No speculative production deadline change was applied. |
| Optional legacy-bundle failure invalidates a successful current export | Reproduced and fixed in `g2ray.sh:5602`. Current schema-2 export success is independent of the optional legacy write. Failure logs a warning and attempts to remove the stale legacy artifact. Mandatory primary/metadata failures remain errors; this is not a workaround for a full disk. |
| A missing bundle argument aborts under nounset | Valid latent bug. Missing, empty, and whitespace-only links now return validation status 2 before changing existing artifacts. No empty successful bundle is emitted. |

Five new server regressions cover the baseline threshold, optional legacy
failure, invalid input, primary-write failure, and rejected process-group
initialization. Fixture reset also removes the legacy artifact so tests do not
depend on execution order. The two functional regressions were first observed
against the pre-fix code, then passed after their respective fixes.

## Automated Verification

| Check | Result | Evidence boundary |
| --- | --- | --- |
| Server static suite | 66 passed | Source contracts, not phone traffic. |
| Server behavior suite | 191 passed | Real Linux Codespace, isolated `/tmp` source/test fixture; no live config used by tests. |
| Xray config validation | 10 generated/migrated profile and WS combinations passed | Installed Xray 26.5.9 config validation, not sustained transport acceptance. |
| Shell syntax and ShellCheck | `bash -n` and error-level checks passed | Warning-level findings are not claimed absent. |
| Mock benchmark | All six budgets passed | Control-plane execution budgets, not Internet latency or throughput. |
| Worker | 63 behavior checks, syntax check, packaging dry-run passed | No Worker source change or dashboard deployment in this pass; live Worker cold wake not retested. |
| Android unit tests | 276 tests in 27 suites, zero failures/errors/skips | Forced rerun, not a cached success or device test. |
| Android lint | Zero errors, 26 warnings | Not a warning-free or signed-release claim. |
| Android packaging | Functional debug and Android-test APKs built and installed in place | Existing accounts/profiles retained; no uninstall or data clear. |
| Native build contract | HEV bundle and libXray checks passed | Previous native Linux fixture was not newly rerun locally in this pass. |
| Packaged core on phone | Three smoke tests passed | Actual native loading/config/start-stop, separate from authenticated Internet traffic below. |

The first static-fixture attempt lacked Git metadata needed by the static
checks; the fixture was corrected and the complete suite rerun. That setup
failure is not counted as a passing application check.

## Physical Phone Results

Samsung Galaxy A52s, Android 14, arm64, same saved private account/edge.
Requests used the ordinary full-device VPN path, not protected sockets or a
test SOCKS proxy. The normal automatic standby configuration remained enabled;
the selected transport is not proof that every connection used one fixed
outbound. No new private links, keys, device serials, or raw logs are published.

Each network phase needs two of three independent HTTPS endpoints to finish
within 6,000 ms. Retained HTTP/2 connection identity is logged; fresh sockets
are checked separately. Passing eventual HTTP responses do not bypass the
responsiveness gate. Idle gaps below include activity reopen overhead.

| Run | Actual idle gap | Retained HTTPS | Fresh HTTPS | Result |
| --- | --- | --- | --- | --- |
| HEV, selected XHTTP, short idle | 106,777 ms | 3/3, 1,067-1,425 ms | 3/3, 1,064-1,289 ms | Pass |
| HEV, selected XHTTP, controlled screen-off | 316,910 ms | 3/3, 1,059-1,420 ms | 3/3, 1,015-1,401 ms | Pass; screen-off/Dozing observed |
| HEV, selected XHTTP, short idle repeat | 66,613 ms | One timeout at 20,022 ms; another response at 11,079 ms | 2/3 within limit; one timeout at 20,539 ms | Fail; not a retained-only failure in this run |
| Native, selected XHTTP, longer idle | 357,046 ms | 3/3, 684-836 ms | 3/3, 681-834 ms | Pass; screen was interactive, not a controlled screen-off run |
| HEV, selected WS, short idle | 106,748 ms | Three reused HTTP/2 requests timed out at about 20 seconds | 3/3, 1,800-3,588 ms | Fail |

All completed initial transfer samples downloaded the full 2 MiB and uploaded
64 KiB successfully. HEV's real TUN totals advanced; Native did not substitute
device-wide counters as tunnel speed. These samples are functional checks,
not sustained capacity benchmarks or proof of upstream payload on every flow.

The separate `selectedProfileRecoversAfterRepeatedCoreFailures` test directly
stops the actual native Xray core, without stopping the service or changing
desired-connected intent. It passed with both HEV and Native, each with two
failures and two automatic verified recoveries. All three fresh HTTPS checks
succeeded after each recovery. Failure-to-recovery-plus-three-probes took:

- HEV: 7,697 ms and 7,797 ms.
- Native: 8,707 ms and 7,357 ms.

These are bounded local-core recovery results, not stopped-Codespace wake or
arbitrary upstream outage results. Recovery and idle tests are independent so
an idle failure cannot prevent the recovery test from exercising its cases.
The original idle assertion remains unchanged.

No new app crash/native-abort/ANR exit reason was observed. Android recorded
normal instrumentation teardown as user-requested force stop. Test assertion
failures are not app crashes. This does not certify other devices or long runs.

## Repository and Remaining Work

The remote `perf/jitter-aware-route-scoring` branch was an ancestor of `main`,
had no unique commits/diff, and had no open PR. It was removed at the user's
request. GitHub now has only `main`; unrelated local worktree branches and the
user's untracked diagnostic document were not modified.

The retained-connection stall remains **open** in
[issue #1](https://github.com/z2roozsh/G2rayXCodeLeafy/issues/1).
A longer passing idle run does not cancel the shorter failing runs. Keep XHTTP
as the normal transport; neither switching to WS nor replacing the packet
engine is a verified cure. Do not force a global restart on every screen wake
or weaken the six-second gate to hide the symptom.

Release acceptance still needs a confirmed targeted idle fix and repeatable
results, long video/AI-streaming soak, Wi-Fi/mobile handoff, large transfers,
Worker and direct-GitHub cold wake, and wider Android/OEM coverage. Existing
resource-budget, route-cache epoch, and best-effort KV limitations in the prior
audit remain open. No 100/100, zero-drop, 300 ms, or superiority-to-other-clients
claim follows from this pass. See
[the production test matrix](g2ray_app/PRODUCTION_TEST_MATRIX.md).

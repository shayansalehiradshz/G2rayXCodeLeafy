# Claim review and phone checks

Date: 18 October 2026. Reviewed baseline: `4987870`.
No subagents were used. Private profiles, credentials, device identifiers and
raw diagnostic logs are not included in this document.

## Changes and verified findings

**Server startup barrier: verified defect, fixed.** In `run_with_deadline()`
(`g2ray.sh`), the child previously waited for a nonempty startup file without
checking whether its parent still existed. A Linux reproduction stopped the
parent immediately after the child fork, killed the parent, and observed the
child still running without launching its command. The new regression failed
against the baseline before this change.

The wait now checks the captured parent PID, its clock/deadline, and a bounded
polling backstop before accepting the startup signal. It exits without launching
work when initialization loses its owner or exceeds its budget. Separate tests
cover a dead parent and a still-living, SIGSTOP-paused parent. Both pass on Linux.
Normal completion, failure statuses, cleanup deadlines, clock failure and
process-group termination regressions also remain passing.

**Acceptance harness: verified reporting defect, fixed.** Live device tests
waited for verified Connected or Error but overlooked terminal Blocked states.
A stopped-host attempt therefore waited 180 seconds after Blocked. Startup,
core-recovery and network-recovery waits now fail on Blocked as well, with the
actual service reason in their assertion. This improves evidence and failure
reporting; it does not change production recovery policy. The revised harness
compiled and passed lint; the stopped-host case was not repeated after the owner
requested finishing without further account changes/tests.

**Experimental route acceptance: added.** The new opt-in
`experimentalRouteComparedWithoutChangingSavedConfiguration()` test compares
matching direct and experimental VLESS profiles on the physical network in
direct/experimental/experimental/direct order. Each round uses three independent
HTTPS probes through the packaged native core. It checks unchanged selection,
unchanged preferences and no leftover core before evaluating acceptance. No
profile is imported and no alternate saved route can substitute for a failed
experimental route. See `g2ray_app/PRODUCTION_TEST_MATRIX.md` for its input and
explicit limits.

Production Android code, native libraries, Worker source, transport/DNS defaults,
UUID generation, exported link schema and Xray configuration generation were
not changed in this follow-up.

## Phone evidence

Device: Samsung SM-A528B, Android 14. The installed production APK still matches
the previously verified local APK:

```text
504626fd0cf9c835f63ec8a4665aaf36f686275164bb33e9854737602b528e51
```

Only the test APK was updated. Current saved settings observed during these
tests: Fast DNS on, IPv4-only on, DPI compatibility off, seamless failover on;
Auto used HEV. These are the owner's saved choices, not newly selected defaults.

| Check | Actual result |
| --- | --- |
| Installed diagnostic timing smoke | Passed, without network access. |
| Existing XHTTP full-device startup | Verified in 3,179 ms; all three HTTPS endpoints passed. |
| Background/activity resume | Actual gap 166,250 ms; retained-pool complete responses 1,011 / 943 / 992 ms; fresh responses 1,035 / 1,076 / 1,027 ms. Passed. |
| Ping All | 26/26 saved configs returned real delays in 22,617 ms; persistence and unchanged selection assertions passed. |
| Repeated native-core failure | Both cycles recovered automatically; full post-repair HTTPS checks completed in 6,645 / 7,203 ms. |
| Settings/help/back navigation | Passed on the device. |
| Share, QR and Copy | Passed, with unchanged profile selection. |
| Large import draft and rotation | Passed; Parse Configs stayed visible and draft survived recreation. |
| Experimental Cloudflare entrance | Direct WS rounds each passed 3/3 probes, medians 2,141 / 2,117 ms; experimental rounds each had 0/3 successful probes. Experimental acceptance correctly failed. |
| Loaded transfers | Could not reach the workload: startup entered Blocked. This is not a load/throughput pass. |
| Extended YouTube run | Not performed after the host became unavailable. |

The Cloudflare entrance had previously passed three authenticated requests from
an isolated client in the Codespace. Its connector reported four ready QUIC
connections. That proves server-side deployment/protocol compatibility, not
phone reachability. It remains a separate experiment, not a recommended default.

Retained-pool timing includes retries/new connections when the HTTP library
replaced an idle connection; it does not prove that one original TCP socket
survived. The background check was screen-on/USB-powered, not unplugged Doze.
These are individual request/test samples, not p95 performance guarantees.

Android exit history during this session showed instrumentation-requested stops,
not a newly observed application crash. This does not certify all-device crash
freedom.

## Host availability and rollout boundary

During the longer-test attempt, GitHub reported the active Codespace as Shutdown.
One explicit start request returned HTTP 402 with a billing issue preventing
startup. No billing setting was changed and no repeated start loop was used.
The owner chose to finish the review and keep the phone's account selection
unchanged rather than test another saved account.

The server fix was verified in a separate Linux source snapshot before the host
stopped. Publication and deployment are different: the active runtime cannot
pick up this source change until GitHub allows it to start and its checkout is
updated. The current app binary did not need replacement. No loaded/video result
or successful live-server rollout is claimed after the billing block.

## Automated verification

- 712 freshly executed Android unit-test cases; zero failures/errors/skips.
- 14 freshly executed build-logic tests; zero failures/errors/skips.
- Final Android instrumentation packaging and lint succeeded; lint reports zero
  errors and 22 warnings, not zero warnings.
- 212 Linux server behavior assertions passed, including both new regressions.
- 66 static assertions passed locally and in the Linux source snapshot.
- Linux error-level ShellCheck passed.
- 69 Worker assertions passed; Worker source was not changed or redeployed.
- `git diff --check` passed.

An initial Windows/Git Bash behavior run hit the existing one-second cleanup
deadline test during concurrent compilation; it was not counted as a full pass.
The target-Linux suite passed. The first Linux static run used an archive
snapshot without Git metadata and correctly failed its executable-index check.
Restoring the snapshot's tracked executable mode and Git index allowed the
unchanged static assertions to pass. Neither assertion nor deadline was weakened.

## Review claims: accepted, corrected or deferred

| Claim | Assessment |
| --- | --- |
| 95/100 and 100% production complete | Subjective and unsupported as universal readiness/availability claims. The real startup-wait defect and billing stop are counterexamples to declaring nothing remains. |
| No compiler/lint warnings | Incorrect: this fresh lint run has 22 warnings and zero errors. A passing build is not a warning-free build. |
| Zero IP leaks / 100% US appearance | Not established. Full-tunnel defaults and OS blocking are useful controls, not proof covering every crash, client, browser and geolocation database. |
| Cloudflare Tunnel is HTTP/2-only and cannot improve speed | Incorrect. Its auto transport selects QUIC with HTTP/2 fallback, and the live connector used QUIC. Improvement is network-dependent; this phone comparison did not pass. |
| Ordinary Origin Rules Host/SNI overrides require Enterprise | Confirmed in the current availability table. This limitation does not apply to reaching the listener through a local connector. |
| Cloudflare video/large-file terms matter | Confirmed as a real service-terms consideration. Do not turn that into either a blanket exemption for Tunnel or an unqualified claim about every use/plan. |
| CPU is categorically limited to two cores | Not established for every Codespace; configuration minimums and the current machine are not a universal throughput ceiling. |
| All idle delays are carrier NAT and always about one second | Unsupported causal/general timing claim. This run improved the evidence, not the proof of one universal cause. |
| Billing rollover/wake is always 10-15 seconds | Unsupported. GitHub scheduling, account access and billing can prevent startup entirely. |
| Destination-aware ranking is the biggest proven optimization | Hypothesis. Client probes already use real authenticated HTTPS traffic. Benefits and probe cost for specific applications must be measured before changing selection/defaults. |
| Future foreign-schema quota metadata should be trusted | Rejected. Supported links can be imported while unsupported metadata stays ignored; it must not overwrite account usage with an unknown schema. |
| Invalid saved bypass rules are deleted | Incorrect: the reader falls back to full tunnel without erasing persisted data. This deliberate behavior is already documented. |
| The inner child-liveness check is unreachable | Incorrect: a child can exit between the loop-condition check and its body. Removing the check would require a separate race analysis, not this assertion. |
| Parent can die before publishing startup readiness | Confirmed and fixed with a failing-before regression. |
| Current Python-3, quota, routing-read, tile and fixture-cache fixes exist | Confirmed in the source and the relevant passing suites. |
| Codespaces has a 12-hour active lifetime | Confirmed in current GitHub documentation; no client setting removes that hosting limit. |

Sources checked on 18 October 2026:

- [Cloudflare Tunnel run parameters and protocols](https://developers.cloudflare.com/tunnel/reference/run-parameters/#protocol).
- [Cloudflare Origin Rules availability](https://developers.cloudflare.com/rules/origin-rules/#availability).
- [Cloudflare CDN-specific terms](https://www.cloudflare.com/service-specific-terms-application-services/).
- [GitHub Codespaces lifecycle](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle).

## Remaining acceptance

Extended video and real AI streams, idle-versus-loaded response tails, unplugged
deep Doze, actual Wi-Fi/LTE handoffs, signed upgrades and second-device coverage
remain separate acceptance tasks. There is no evidence here justifying another
speculative transport, DNS, fragmentation or buffering-default change.

# Recovery claim review and candidate verification: 17 October 2026

## Reviewed and Installed Revision

The supplied reviews cover `d52614d` and `971d22f`. The current-source patch was
reviewed against `971d22f31789264d21b69e8f17631847d9368ae7`, not stacked on the
older patches. Its SHA-256 matches the supplied report:

`f9c8998c3c49d62dbcce522a5723fd7229d71e1df28f81c05e4528c6fa9af92b`

All 53 manifest-listed entries in the latest evidence archive passed their
checksum checks. Its 47 JUnit suites contain 677 tests with no failures, errors,
or skips. Those are supplied review results, not this implementation's results.

The integrated production and test changes are published as
`80eaee1563edfe62f9989e902c68fab15e8d0421`. Additional local controls bring this
revision's Android test total to 683. No native core, production server, Worker,
MTU, DNS policy, health timeout, or default transport was changed.

The functional debug APK was installed in place on the same Galaxy A52s,
Android 14, arm64, LTE. The previous APK was preserved privately and the signing
certificates matched before replacement. No uninstall, data clearing, account
retargeting, credential change, or lock-policy change was performed on the phone.
Saved accounts/profiles and the owner's existing settings loaded successfully.

Installed APK SHA-256, independently read back from the phone:

`8e3c8ef1995b3bb3c65f1840775c0e1a6b139bbb7c8d2b5278eacfa3f71ec517`

Instrumentation APK SHA-256:

`0cddda5e8afe67d39e4883c9421bb7849ae5d1a23d8633dded01c7d33328bcdc`

## Confirmed Fixes

| Claim | Independent finding and integration |
| --- | --- |
| Retargeted accounts can retain another Codespace's links | Confirmed. Manual and system startup did not recheck a recognized origin after editing an account. Recheck before caching and before engine startup; reject a conflict as configuration failure while capture remains held. Filter stale backups before ranking and limiting. Preserve saved links and generic/custom-front compatibility. |
| Wake can hide a retained Blocked warning | Confirmed. A successful wake could publish routeReady=true and replace the warning without starting a forwarder. Preserve Blocked service state; backend wake status remains separately available. |
| Home hides OS blocking policy while Blocked | Confirmed by source. Keep the OS-reported label visible while retaining the guard. Do not confuse in-process capture with Android lockdown. |
| An obsolete periodic probe can delay a handoff | Confirmed. It holds the lifecycle mutex needed by replacement-network verification. Cancel the old periodic request on actual loss/replacement and rearm monitoring when lifecycle ownership permits. Same-network callbacks retain their valid request. |
| A stopped core retains old local backoff on a replacement network | Confirmed. Permit early retry only for an available changed network with Error/Disconnected core, never before a mandatory Retry-After. A running core must first get a fresh route proof. |
| A callback before retry dispatch can be missed | Confirmed. Capture the network generation before launching the retry coroutine; refresh the baseline after subsequent attempts. |
| Loaded-test completion does not prove request overlap | Confirmed as an assertion gap. Record completed monotonic request intervals and require a complete responsive probe within a completed bulk request. This is test correctness, not a production speed change. |
| Exit-location fixture can stop early under pipefail | The early-closing grep pipeline is avoidable. Capture the text before checking it; all nine fixture groups now complete. Production exit diagnostics are unchanged. |

The pre-fix focused run executed 124 tests and failed 15. Thirteen failures
demonstrated stale-start, Blocked-state, periodic-probe, or retry-order behavior.
Two additional failures referenced a new backup-filter helper absent from the
baseline; those are not counted as independent reproduced bugs. The original
output and failing XML remain preserved. Existing tests were not weakened.

Additional controls verify unchanged/unavailable networks, mandatory cooldown
boundaries, default wait behavior, extreme minimum waits, valid matching and
custom profiles, and missing-account rejection. Service cases run on API 24/31.

## Fresh Source and Build Verification

- 683 Android unit/Robolectric tests, 47 suites, zero failures/errors/skips.
- 14 freshly rerun build-logic tests, zero failures/errors/skips.
- Lint: zero errors, 25 existing warnings in this environment. The supplied
  archive's lower warning counts are not reproduced locally or treated as a
  performance metric; no warnings were suppressed to match them.
- Functional debug and instrumentation APK packaging passed native ABI/hash
  and libXray build-contract gates.
- Six on-device core/harness checks passed: real native loading, generated
  DNS/TUN contract, real multi-outbound start/stop, shell-stdin, video limits,
  and eight assertions within the interval-boundary test.
- Hosted Android CI passed for the exact fix commit, including the pinned
  core's validation of all 32 generated configuration variants and native
  payload/restart fixtures: [Android run](https://github.com/z3roozsh/G2rayXCodeLeafy/actions/runs/38990042150).
- Hosted server/Worker CI passed: 66 static checks, 197 behavior checks,
  66 Worker checks, nine exit-location groups, diagnostic/pinned-core fixtures,
  ShellCheck, mock benchmark budgets, and Worker packaging dry-run:
  [server/Worker run](https://github.com/z3roozsh/G2rayXCodeLeafy/actions/runs/38990051914).

## Candidate Phone Measurements

These use the installed candidate above, with the owner's Fast DNS and
IPv4-only settings enabled, DPI compatibility off, seamless failover on.
The two engine/transport combinations use saved profiles on the same edge and
account. All requests use ordinary full-device VPN routing, not protected
control-plane sockets. Times are complete small HTTPS responses, not ICMP ping,
isolated edge RTT, first byte, page paint, or first AI token.

### Loaded Requests

Each workload completes twelve 8 MiB downloads and 2 MiB uploads, 120 MiB payload
total. The stricter gate requires request-lifetime overlap; it does not establish
simultaneous payload bytes or link saturation.

| Pair | Workload time | Three-endpoint quorums | Fully contained responsive probes |
| --- | --- | --- | --- |
| Native/XHTTP | 35,215 ms | 33 | 80 |
| HEV/WebSocket | 60,961 ms | 50 | 131 |

Per-endpoint nearest-rank median / p95 / maximum, milliseconds:

| Pair | gstatic | Cloudflare | example.com |
| --- | --- | --- | --- |
| Native/XHTTP | 244 / 619 / 763 | 256 / 370 / 412 | 257 / 416 / 430 |
| HEV/WebSocket | 224 / 933 / 1,038 | 247 / 969 / 1,271 | 240 / 816 / 1,247 |

All individual recorded probes in these two runs were usable and responsive.
Do not turn these sequential samples into a universal default-transport winner,
a long-run p95, or proof of a production speed increase from this patch.

### LTE Outage and Local Core Failures

Both tests independently observed genuine physical-network loss during an
eight-second mobile-data interruption, preserved connected intent, and recovered
without another Start tap. Data was originally enabled and restored afterward.
The revised harness separates verification, first fresh response, and full
three-response completion after the observed network return.

| Pair | Tunnel verified | First fresh response complete | Fresh quorum complete | Two core-repair cycles |
| --- | --- | --- | --- | --- |
| Native/XHTTP | 3,377 ms | 3,881 ms | 5,003 ms | 7,069 / 6,615 ms |
| HEV/WebSocket | 4,483 ms | 5,968 ms | 9,006 ms | 11,915 / 7,106 ms |

The core-repair cycles include diagnosis and three independent requests.
They are not all below the proposed ten-second target. One LTE cycle per pair
is not a repeated handoff/p95 study. No second working network was available.

The first Native loaded startup took 25,580 ms and invoked direct GitHub wake.
Read-only server logs confirm a genuine headless Codespace resume during that
wait. It is retained as a cold-host observation, not hidden as an outlier or
called instant. Later Native warm starts took 2,242 / 2,442 ms; HEV starts took
2,837 / 2,529 / 2,458 ms in the load/outage/core runs.

HEV/WebSocket's 60,006 ms idle check reused its original HTTP/2 connections with
231 / 245 / 237 ms responses. Fresh responses took 2,971 / 1,735 / 1,729 ms.
The first Native 310-second attempt failed after about 83 seconds because the
screen became interactive, before post-idle requests. Preserve that failed
controlled interval; it is not evidence of an upstream stall or an idle pass.

The separate Native repeat completed 310,007 ms with the screen noninteractive
and the VPN activity still stopped. Retained-pool responses took
703 / 721 / 648 ms, and fresh responses 521 / 707 / 639 ms. All three retained
requests replaced their expired original connections. The run reached an
explicit passing terminal result without reopening the VPN UI. This is
USB-powered screen-off, not a new forced-Doze or unplugged sleep result.
Phone-wide counters advanced by 34,060,855 received / 6,763,198 transmitted bytes
during the full run, including other apps or tethered traffic; this does not
establish a completely silent upstream tunnel throughout the idle interval.

## Server and Evidence Scope

The live server still runs Xray 26.7.28; config/UUID file hashes are unchanged.
Its clean `a985121` checkout has identical production server, devcontainer,
post-start, and Worker source to this revision. No manual production restart
or configuration regeneration was needed for these Android changes.

Raw logs, prior APK, regression XML, and device records remain private and ignored
under `data/verification/claim-review-971d22f-20261017/`. No private configs,
credentials, account data, or screenshots are published by this report.

Final process-exit records showed seven additional entries, all normal
instrumentation start/finish force-stops. No new app/native crash or ANR exit
was recorded. The installed APK still matched its candidate hash. Cleanup left
mobile data enabled, force-idle disabled, and no app foreground service. The
test VPN was stopped cleanly; saved user data was not cleared.

## Remaining Release Work

The numeric rating and three-second video target in the supplied reports are
proposals, not measured readiness percentages or guaranteed network outcomes.
This candidate closes reproduced source defects, not every release gate.
The earlier three-minute YouTube and short AI-app results belong to the earlier
APK and have not been silently assigned to this one.

Real Wi-Fi/LTE handoff, natural unplugged Doze, repeated multi-hour/overnight
sessions, independent IP/DNS/IPv6/STUN containment, signed/minified same-key
upgrade, another device, sustained media/feed refresh, and complete real-provider
AI streams remain separate acceptance work. Existing application TCP sessions
cannot generally be preserved across host loss or replayed safely by a tunnel.

Android OS blocking across process death/reboot requires the user's system
policy, not just retained app capture; see [Android VPN documentation](https://developer.android.com/develop/connectivity/vpn).
GitHub documents a mandatory 12-hour active Codespace lifetime; see
[Codespace lifecycle](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle).
No code change can honestly turn those constraints into a flawless, uninterrupted
single-Codespace service. Keep speculative transport/default tuning out of this
fix until matched-path measurements identify a reproducible bottleneck.

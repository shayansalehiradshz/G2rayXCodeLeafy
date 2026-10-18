# New account deployment and phone verification

Date: 18 October 2026. Baseline: `1ff5815`. No subagents were used.
Credentials, live links, raw device logs and import artifacts stay outside Git.

## Setup

The new `poulcarlsen53/G2rayXCodeLeafy` fork and its East US 2 Codespace were
configured with Xray 26.7.28, XHTTP and persistent WebSocket fallback. Both
forwarded ports were public. The existing supervisor and independent watchdog
were running. A headless restart preserved the UUID and restored the listeners.
Windows and Codespace-side authenticated HTTPS checks passed both transports.

The phone now has a separate `poulcarlsen53` account with 22 imported configs
and direct GitHub wake. Both older accounts and their profiles were preserved.
The token's GitHub identity was verified before saving it through the app's
encrypted storage; the actual cold wake below proved start permission.
Cloudflare Worker and Tunnel configuration were not changed.

The main Android APK was not replaced: its SHA-256 still matches the baseline
`504626fd0cf9c835f63ec8a4665aaf36f686275164bb33e9854737602b528e51`.
Only the instrumentation APK was updated. Existing preferences stayed Auto,
Fast DNS on, IPv4-only on, DPI compatibility off and seamless failover on.
These are observed user preferences, not a new default recommendation.

## Real phone results

Device: Samsung SM-A528B, Android 14, mobile data, USB-connected and screen-on.
All timings below are individual samples, not percentiles or guarantees.

| Check | Result |
| --- | --- |
| New account import | All 22 configs parsed and imported; both old accounts preserved. Compact text import was exercised, not camera scanning. |
| Ping All | 22/22 successful in 17,776 ms, with persisted results and unchanged selection. |
| Cold UI wake | After an explicit host stop and confirmed Shutdown, the app's Start VPN woke it through GitHub and reached verified Connected in 40,410 ms. No CLI start or editor attachment substituted for the app. |
| Idle and activity resume | Passed after an actual 106,120 ms idle/relaunch gap. Retained-pool HTTPS responses: 226/233/256 ms; fresh requests: 1,495/1,739/2,309 ms. |
| Native/XHTTP repeated core failures | Both cycles recovered automatically; recovery plus three complete HTTPS checks took 7,046/6,912 ms. |
| HEV/WebSocket repeated core failures | Both cycles recovered automatically; recovery plus three complete HTTPS checks took 10,938/12,410 ms. |
| Mobile-data outage | An eight-second outage preserved connected intent. Verified Connected returned 4,890 ms after the physical network; all three complete HTTPS checks finished by 9,404 ms. Mobile data was restored by the test and checked enabled. |
| Direct wake control plane while connected | Passed through protected physical-network sockets; GitHub readiness returned in 992 ms. |
| Bounded Native/XHTTP transfers | Exact 8 MiB download completed in 2,469 ms; 2 MiB upload returned success in 3,151 ms. Three subsequent HTTPS requests passed. These short transfers are not sustained-speed or video acceptance. |

The HTTPS checks validated expected status/body behavior through Android's
default VPN, not a separate SOCKS bypass. Core-recovery timing includes the
subsequent HTTPS checks; it is not just time until the UI says Connected.
Retained pools may replace connections, so those timings do not prove survival
of one original TCP socket. No crash or ANR was observed in this test window;
recorded process stops were instrumentation-requested, not crash evidence.

An additional experimental-harness invocation found the older Cloudflare test
input still present, rather than exercising missing-input handling. Its old
direct and experimental routes each returned 0/3 successes. The test correctly
failed its direct-control quorum; that is not a valid new-route speed comparison
and is not counted as a pass or as a failure of this new account. The old test
file was retained with shell-owner-only permissions. No experimental profile
was imported or selected.

## Review changes and verification

The four minor suggestions in the supplied review were beneficial:

- A missing/blank experimental-route input now has an actionable precondition.
- The four comparison rounds have a 180-second coroutine timeout, while keeping
  per-probe cancellation. This does not forcibly interrupt a wedged JNI call.
- A deadline-fixture failure message now describes its actual assertion.
- The matrix separates the experimental entrance from browsing-first selection
  and describes request samples rather than promising distinct endpoints.

There was no production Android, Worker, dependency, DNS, export-format or
transport-default change in this follow-up.

Fresh verification: 712 Android unit tests in 48 suites, zero failures/errors/
skips; instrumentation packaging succeeded; lint had zero errors and 25 warning
occurrences. All 66 static checks, 212 Linux server behavior checks and 69 Worker
checks passed. `git diff --check` passed.

The Windows/Git Bash behavior run failed the SIGSTOP-paused-parent timing test.
The same test and the full suite passed in an isolated tracked-source snapshot
on the Linux deployment platform; no deadline or assertion was weakened. The
isolated run did not read or modify the live server's config or identity.

## Claims not accepted as proven optimizations

- The review's SHIP verdict is scoped to its diff, not proof of universal
  reliability, speed, zero leaks or completion of all acceptance work.
- A two-core machine and GitHub edge alone do not establish a measured maximum
  throughput. No unmeasured performance ceiling is claimed here.
- Destination-specific ranking may be worth an experiment, but neither its
  benefit nor battery/probe cost was established. It was not enabled by guess.
- Missing native UDP forwarding is not the same as VLESS being unable to carry
  UDP inside its transport. Existing UDP/443 policy was not changed speculatively.
- A Worker deployment is not required for the verified direct GitHub wake path.
- The older Cloudflare entrance remains an unadopted experiment, not a fallback
  silently added to the new account.

## Remaining limits

These checks do not establish overnight unplugged Doze, a real Wi-Fi/LTE
handoff, signed-release upgrade behavior, independent multi-device leak tests,
extended video or actual authenticated AI-stream acceptance. They do establish
the specific import, wake, idle, recovery and control-plane behavior above.

GitHub quota and the documented maximum 12-hour active Codespace lifetime still
apply. Neither a client retry nor this deployment removes those hosting limits:
[GitHub lifecycle documentation](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle).

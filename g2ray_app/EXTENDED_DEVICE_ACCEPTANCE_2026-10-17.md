# Extended physical-device acceptance: 17 October 2026

## Scope

This follows the [small-probe device record](DEVICE_ACCEPTANCE_2026-10-17.md)
after the owner removed the data limit and authorized a brief LTE interruption.
The device is the same Samsung Galaxy A52s (SM-A528B), Android 14, arm64, on LTE.
There is no second working network for a Wi-Fi/LTE handoff test.

Product application source remains `6b21d6c56cb5d3a71d47fde1ac64ac3036b60f82`;
the intervening `d52614d` adds acceptance documentation only. The installed
functional APK was not replaced in this pass. Its independently re-read SHA-256
still is:

`d7d5ae391b9c7c7d8189cc64d36ba13b53471e11a02b14000b4f83be2afc6cae`

Only instrumentation and acceptance documentation changed. No production VPN,
server, Worker, DNS, MTU, or transport tuning was changed to obtain these results.
The owner's Fast DNS and IPv4-only settings remain enabled; these are not new
defaults. DPI compatibility remains off and seamless failover remains on.
Engine/transport overrides exercise saved profiles on the same account and edge.

## Measured Transfers

Each row is one complete 8 MiB download and 2 MiB upload, with exact response-byte
validation and independent successful HTTPS checks before and after. Mbps is
payload bytes times eight divided by whole-request elapsed time, in decimal
megabits per second. These are individual samples, not sustained ceilings or a
controlled comparison with another client.

| Phone engine / transport | Download time / rate | Upload time / rate |
| --- | --- | --- |
| Native / XHTTP | 2,294 ms / 29.25 Mbps | 1,227 ms / 13.67 Mbps |
| HEV / XHTTP | 3,764 ms / 17.83 Mbps | 1,312 ms / 12.79 Mbps |
| HEV / WebSocket | 3,992 ms / 16.81 Mbps | 2,636 ms / 6.36 Mbps |
| Native / WebSocket | 3,044 ms / 22.05 Mbps | 2,713 ms / 6.18 Mbps |

## Responsive Traffic During Transfers

The new opt-in loaded check completed twelve 8 MiB downloads / 2 MiB uploads
(120 MiB payload) per engine/transport pair, alongside complete small HTTPS
requests through the default VPN. Both runs passed without a recovery-state
transition during the workload. Every recorded response was usable and within
the unchanged six-second responsiveness limit.

| Pair | Elapsed | Three-endpoint quorums | Complete responses |
| --- | --- | --- | --- |
| Native / XHTTP | 42,632 ms | 35 | 105 |
| HEV / WebSocket | 57,078 ms | 49 | 147 |

Per-endpoint nearest-rank latency statistics, in milliseconds:

| Pair | Endpoint | Median | p95 | Maximum |
| --- | --- | --- | --- | --- |
| Native / XHTTP | gstatic | 242 | 275 | 387 |
| Native / XHTTP | example.com | 254 | 277 | 410 |
| Native / XHTTP | Cloudflare | 273 | 1,110 | 3,182 |
| HEV / WebSocket | gstatic | 233 | 841 | 985 |
| HEV / WebSocket | example.com | 239 | 377 | 856 |
| HEV / WebSocket | Cloudflare | 237 | 506 | 1,013 |

The slower Cloudflare responses are retained, not discarded. These are complete
small-response times, not ICMP ping, isolated edge RTT, first byte, or a full
bufferbloat study. Repeated bounded transfers also do not prove flat throughput
or absence of saw-tooth behavior in a long single flow.

## Real LTE Loss and Automatic Recovery

Both tests observed the physical Internet network actually disappear after an
eight-second mobile-data interruption. The VPN invalidated its verified state,
retained connected intent, and returned to verified Connected without another
Start tap. Fresh HTTPS requests then succeeded. Data was originally enabled and
was restored by both on-device and host-side cleanup.

| Pair | Observed loss after command | Network return through fresh HTTPS proof | Total including outage |
| --- | --- | --- | --- |
| Native / XHTTP | 411 ms | 4,871 ms | 13,116 ms |
| HEV / WebSocket | 209 ms | 9,114 ms | 17,347 ms |

The recovery column includes the three sequential fresh HTTPS responses; it is
not just the state-transition time. Post-return response triples (gstatic,
Cloudflare, example.com) were 529 / 500 / 579 ms for Native and
1,475 / 1,497 / 1,539 ms for HEV. These are real LTE-loss/return checks, not a
Wi-Fi/LTE handoff or a promise of instantaneous recovery on every network.

## Five-Minute Native Idle and Forced Doze

Native/XHTTP completed 310,007 ms with the display noninteractive and VPN
activity stopped. During that interval the host explicitly entered Android deep
idle, observed `IDLE` for 75 seconds, then released the forced state in `finally`.
No VPN activity was reopened for the post-idle requests.

Retained-pool requests completed in 564 / 764 / 758 ms; fresh requests completed
in 564 / 749 / 806 ms. Event traces show replacement connections for each
retained-pool request: recovery succeeded, but the original sockets did not
survive unchanged. The test reached its explicit `idle_resume_success=true`
and passing terminal result. This adds the missing current-source Native
five-minute check; the earlier HEV five-minute result remains separately recorded.

This is a USB-powered, explicitly forced Doze observation. It does not establish
natural unplugged Doze behavior, a full sleep cycle, or battery consumption.

## YouTube and Cloud-AI Apps

Two actual three-minute YouTube samples passed with advancing playback position,
at least 150 seconds observed local Playing, zero reported post-start buffering
transitions, and successful HTTPS requests afterward. The runs used different
official public Blender videos to reduce cache reuse. No resolution or account
setting was changed.

| Pair | First local Playing after launch | Observed Playing | Position advance | Reported rebuffers |
| --- | --- | --- | --- | --- |
| Native / XHTTP | 4,025 ms | 175,870 ms | 175,982 ms | 0 |
| HEV / WebSocket | 5,226 ms | 174,730 ms | 174,459 ms | 0 |

The phone-wide mobile-byte deltas were 61,211,264 and 91,450,629 respectively;
they include other applications, not only video. Both stayed below their
explicit 256 MiB guard. Two Native screenshots were also inspected to confirm
visually advancing video and a populated page. Media-session telemetry is not
decoded-frame, resolution, long-session, or future no-buffering proof.

The installed ChatGPT and Claude apps each visibly completed a short harmless
diagnostic exchange through the running normal VPN. ChatGPT used Temporary
chat; Claude used Incognito chat. The Claude complete reply was visible in a
screenshot captured 5.8 seconds after sending, an upper bound for that UI sample,
not measured first-token time. ChatGPT's custom rendering defeated automated
text polling, but its complete reply was confirmed visually after switching back
from Claude. No valid ChatGPT timing or long-stream completion is claimed.
An earlier longer ChatGPT response was not conclusively observed to completion.

## Harness Corrections and Evidence Boundaries

The first LTE test failed because `UiAutomation.executeShellCommand` does not
interpret semicolon-separated shell commands; physical loss never occurred.
The test failed its real-loss assertion instead of falsely reporting recovery.
The corrected helper sends the script to `sh` through
`executeShellCommandRw` stdin, closes both descriptors, and has an independent
multiple-command regression. The corrected Native and HEV outage runs passed.
The failed attempt remains in `native-xhttp-outage-and-loaded.txt`; the valid
Native result is in `native-xhttp-outage-fixed.txt`.

An initial transfer invocation omitted the correct `liveTransfers` opt-in and
was skipped. It is not counted as a pass; all four rows above are separate
explicit successful transfer runs. Failed AI text-observation attempts also
remain preserved rather than being relabeled as automated successes.

The video-duration/budget argument regression and shell-stdin regression each
passed on this phone. The final packaged instrumentation APK SHA-256 is:

`9a69062b81cd61181f8c45bacc6c054343228a4979543c0b9f6ab5bafbc5b8cd`

All raw outputs and screenshots remain private and ignored under
`data/verification/extended-device-20261017/`. Screenshots can contain personal
recommendations or account context and must not be published. The production
matrix records the explicit opt-ins and safety preconditions for repeating tests.

Final Android process-exit inspection showed fourteen additional entries since
the previous record, all normal instrumentation start/finish force-stops. No
new app crash, native crash, or ANR exit was recorded in this bounded pass.
Cleanup left mobile data enabled, forced idle disabled, and no app foreground
service. The test VPN was stopped; no production server restart was performed.

## Live Server Cross-Check

A final read-only SSH check still found Xray 26.7.28 (`5ca6f4b`, Go 1.26.5),
origin TCP Fast Open off, DNS `UseIPv4`, and routing `AsIs`. Config and UUID file
hashes remain identical to the preceding device record. The checkout remains
clean at `a9851211f947d20747dc3e9ba76d4ccbe387e4ea`, whose server runtime files
match the tested product source. Local XHTTP returned HTTP 200 in 0.417 ms;
public XHTTP returned HTTP 200 in 133.437 ms. These are Codespace-side OPTIONS
samples, not phone latency or throughput. Nothing was regenerated or restarted.
The active GitHub repository still has only its `main` branch.

## Local Source Gates

The final forced Gradle rerun executed all 653 Android unit tests across 47
suites, with zero failures, errors, or skips. Lint reran with zero errors and
25 existing warnings. All 14 build-logic tests also reran successfully.
Instrumentation packaging completed successfully before device installation;
the production APK was not replaced during any active test.

From `g2ray_app`, the final local regression commands were:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --rerun-tasks --console=plain --no-build-cache --max-workers=2
.\gradlew.bat -p buildSrc test --rerun-tasks --console=plain --no-build-cache --max-workers=2
```

## Remaining Acceptance

These results substantially extend the tested browsing, media, AI-app, idle,
load, and recovery scenarios. They are not a universal flawless-VPN claim.
Real Wi-Fi/LTE handoff, natural unplugged Doze, signed-release upgrade,
multi-hour sessions, sustained high-resolution media, long AI streams,
Instagram/X feed acceptance, and independent leak testing remain open.
There was no second network available for a real handoff test. A comparative
advantage over another client requires a matched-network A/B measurement.

# Transfer-stall investigation and audit verification

Date: 29 October 2026. Reviewed base: `69b81a516e29721efe854017f764d3598058e0e3`.

## Decision

The supplied audit correctly identifies an unresolved acceptance failure and a
gap in the test diagnostics. It does not establish a Native-engine, DNS,
balancer, carrier, or server-memory root cause. This pass reproduced failed
transfers on the physical phone and narrowed their failed stages. It did not
prove a repair of the underlying intermittent fault or historical regression.

This is not a claim of a flawless, fastest, or universally compatible VPN.
Successful source tests do not replace the failed phone samples below.

## Changes made

- `VpnProbeTimingTrace.kt`: bounded request retention as well as event retention;
  monotonic start/end, local request ID, opaque OkHttp connection identity,
  protocol, acquisition without a connect attempt, HTTP status, request-body
  completion and response-body completion. It records neither full URLs nor
  addresses, headers, bodies, credentials or exception messages. Callbacks do no
  diagnostic I/O. These are HTTP-client events, not Xray outbound identifiers.
- Debug-only `SyntheticTransferRecorder.kt`: terminal intervals on success,
  failure and cancellation. Original exceptions propagate; clock/report failures
  cannot replace the outcome. Failed transfers are not credited as successful.
- `LiveVpnDeviceTest.kt`: per-request stage traces for synthetic HTTPS probes and
  bulk transfers, failed probe intervals, and download bytes read before an
  error. Existing strict size checks, deadlines, explicit-engine assertions,
  completed-transfer accounting and no-retry bulk policy remain intact.
- Debug-only `SyntheticProfileVariants.kt`: makes a runtime-only XHTTP copy with
  `extra` removed for an explicit `xhttpTuning=core-default` diagnostic. It
  reparses and checks that other parsed fields did not change. Saved profiles,
  UUID, SNI, Host, path, account and selection are not rewritten. Default test
  behavior still uses the saved link; this is not a new app preference.
- `scripts/analyze_vpn_load.py`: one-log, local analysis of fixed synthetic
  endpoints. Reports every attempted probe, slow valid replies, invalid replies,
  errors, completed/failed bulk intervals, partial-body observations and stage
  summaries. Trace lines are not counted as additional probe attempts. Legacy
  success lines do not double-count new terminal events. Unknown intervals never
  prove overlap. Added regression tests and a CI step.

The recorder and profile-clone helpers are in `src/debug`, not release source.
Normal browsing is not instrumented. No MTU, engine/core version, buffer size,
DNS policy, recovery threshold or balancer strategy was changed in this pass.

## Physical-phone protocol

Samsung SM-A528B, Android 14/API 34, existing mobile-data network. No emulator,
uninstall, account switch, data clearing or deliberate live-origin restart.
Both APKs were installed with data-preserving `adb install -r`.

The first block used a prespecified shuffle (seed 6929) of eight conditions,
then the reverse order: two runs per engine/transport/failover cell. Each fully
completed run used twelve 8 MiB downloads and twelve 2 MiB uploads, plus small
HTTPS responses while the bulk requests ran. Failed trials were retained, not
replaced by passing reruns. Every trial required the requested engine and a
verified Android VPN network. The origin was already running: these are not
cold-wake measurements.

Fast DNS on, IPv4-only on and DPI compatibility off were held constant. The
selected XHTTP link contained saved custom `extra`; changing the server's export
preference alone does not change that imported link. Temporary failover settings
were restored and the saved selection checked after each test, with a separate
restore check after failed trials.

First block main APK SHA-256:
`496E2D43475F4E3885E77627D275BAE429636C503E682C8B7FAF8DDD83C093F8`.

Native AAR SHA-256, unchanged throughout:
`13C661AF821ECCB49014A6D25A5DF425DB6B1BBA16DC1F03267D4FF17D087A39`.

| Trial | Engine | Transport | Failover | Result | Completed bulk requests | Failed bulk requests | Google probe n | Google valid total-response p95 / max ms |
| --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: |
| 01 | HEV | XHTTP | off | pass | 24 | 0 | 33 | 589 / 611 |
| 02 | Native | XHTTP | off | fail | 10 | 1 | 13 | 467 / 467 |
| 03 | HEV | WS | off | pass | 24 | 0 | 46 | 891 / 980 |
| 04 | Native | WS | off | pass | 24 | 0 | 43 | 942 / 974 |
| 05 | HEV | WS | on | fail | 0 | 1 | 28 | 656 / 665 |
| 06 | HEV | XHTTP | on | pass | 24 | 0 | 28 | 1280 / 2021 |
| 07 | Native | WS | on | fail | 0 | 1 | 29 | 642 / 669 |
| 08 | Native | XHTTP | on | fail | 7 | 1 | 29 | 625 / 690 |
| 09 | Native | XHTTP | on | pass | 24 | 0 | 29 | 1347 / 1980 |
| 10 | Native | WS | on | pass | 24 | 0 | 47 | 901 / 1171 |
| 11 | HEV | XHTTP | on | fail | 0 | 1 | 23 | 519 / 662 |
| 12 | HEV | WS | on | fail | 0 | 1 | 27 | 244 / 244 |
| 13 | Native | WS | off | fail | 13 | 1 | 31 | 1028 / 1030 |
| 14 | HEV | WS | off | pass | 24 | 0 | 38 | 937 / 1153 |
| 15 | Native | XHTTP | off | pass | 24 | 0 | 25 | 742 / 795 |
| 16 | HEV | XHTTP | off | pass | 24 | 0 | 27 | 1619 / 2240 |

Result: **9/16 loaded trials passed, 7 failed**. These are outcomes of a specific
repeated stress workload, not an estimate of daily app failure probability.
Google here means the complete small `www.gstatic.com/generate_204` response,
not search results or page readiness. Percentiles use nearest rank; modest
per-run counts and different run durations do not establish engine rankings.

### Failed stages

- Trial 02: download received HTTP 200 at approximately 439 ms, then failed with
  `ProtocolException` at 1742 ms on an acquired pooled HTTP/1.1 connection.
  The failure occurred after headers, during body consumption. It was not a
  30-second DNS or cold-start wait. Failover was off.
- Trials 05, 07, 11, 12: a fresh connection completed DNS/TLS and received HTTP
  200 after approximately 1.2-2.2 seconds. Body consumption then failed at the
  unchanged 30-second call deadline. Both engines and both transports appeared.
- Trial 08: a reused connection finished sending the request body at 27 ms,
  then timed out waiting for the response at approximately 20 seconds.
- Trial 13: upload failed with `IOException` after approximately 1.7 seconds,
  after the request-body-end callback on a pooled connection.

Small HTTPS probes could remain responsive while a bulk connection failed.
That contradicts treating a healthy quorum as proof that every flow works. It
also argues against restarting all active streams whenever one destination or
connection misbehaves. This instrumentation does not identify the failing
Xray outbound or which hop lost/truncated data.

Failover-on had 5/8 failed trials; failover-off had 2/8. This association is worth
investigating, not a causal diagnosis or a reason to silently disable failover
for every user. A balancer is not required for all observed failures. Two trials
per cell are insufficient to choose a universal default.

## Direct-origin control

After the first phone block, a separate bounded Python HTTP/1.1 control fetched
the same synthetic endpoint directly from the Codespace, without the phone,
Android TUN, VLESS or GitHub inbound edge. It completed all eight 8 MiB downloads
and eight 2 MiB uploads. Download elapsed times were 82-154 ms; upload response
times were 40-126 ms. Every response status was 200 and every download size was
verified. Upload response bodies were empty. This is a sequential control with
a different HTTP implementation, not a matched measurement of maximum phone
throughput or proof that the endpoint never fails.

The live origin reported Xray 26.10.21, local OPTIONS 200, origin TCP Fast Open
false, eight threads and 44,512 KiB RSS in one post-block snapshot. PID 287 was
unchanged from the pre-block snapshot. This does not prove absence of transient
resource pressure or a long-term memory leak. No live runtime config was changed.

## Saved tuning versus core defaults

A second prespecified forward/reverse block used XHTTP with failover **off**,
two runs of each engine/tuning cell, on the same phone/network. The core-default
variant was a runtime clone, not a saved-profile edit. It reported `extra_present=false`;
the saved variant reported `extra_present=true`. Failover was held off so template
changes did not also change the set of compatible backup profiles.

Main APK SHA-256 for this block and the final installation:
`30EA5D68CE3CA5136664A359B783B738F5C6EBC1C20AEA88E07979883E9030CC`.
Instrumentation APK SHA-256:
`E7CA527CF6F8D5DAC1AC0446DBC0733B734FF2E26CFF995DDB0D8915FFB713C8`.
Native AAR was unchanged. All runs received exactly the expected bytes on each
of their twelve completed downloads; the strict reader was not relaxed.

| Trial | Engine | Tuning | Result | Mean 8 MiB download ms | Mean 2 MiB upload ms | Google valid total-response p95 / max ms |
| --- | --- | --- | --- | ---: | ---: | ---: |
| 01 | HEV | saved | pass | 2500 | 1062 | 765 / 1241 |
| 02 | Native | core defaults | pass | 2204 | 8860 | 528 / 586 |
| 03 | HEV | core defaults | pass | 2617 | 8724 | 516 / 688 |
| 04 | Native | saved | pass | 2256 | 1056 | 637 / 1149 |
| 05 | Native | saved | pass | 2166 | 1042 | 513 / 515 |
| 06 | HEV | core defaults | pass | 2205 | 8791 | 499 / 1442 |
| 07 | Native | core defaults | pass | 3302 | 8822 | 498 / 4574 |
| 08 | HEV | saved | pass | 2357 | 1034 | 1096 / 1283 |

**8/8 passed, 192 completed bulk requests.** The saved-tuning uploads took about
1.0-1.1 seconds versus 8.7-8.9 seconds for core defaults in this window. That is
good reason not to blindly remove the owner's saved tuning. Some loaded small
responses were faster with core defaults, but run durations and upload/download
mix differ; this is not proof of a universally better latency setting. No global
default was changed. The slower upload variant is not silently selected for the
owner.

Across both blocks, **17/24 loaded trials passed**. All eight second-block passes
remain separate from the earlier seven failures; this does not establish that
reinstallation, timing, tuning, engine choice or failover caused or fixed them.
No failed bulk interval in the first block had byte-progress diagnostics yet;
the later body-byte observations must not be retroactively attributed to it.

## Claim review and conservative decisions

| Audit point | Assessment | Action |
| --- | --- | --- |
| Missing failed bulk intervals | Confirmed diagnostic defect | Fixed and tested; failed runs remain visible. |
| Need DNS/TCP/TLS/header/body/reuse traces | Useful and feasible | Added bounded synthetic-request traces. OkHttp identity is explicitly not an outbound tag. |
| 50 ms optional-DNS grace can omit a slow backup | Real policy tradeoff, not demonstrated stall cause | Kept selected-route startup behavior. No new background cache, stale-result path or healthy-core restart without evidence and lifecycle testing. Failover-off failures do not require optional backup lookup. |
| Native UID activity can extend periodic grace | Conditional source behavior; not an observed 150-second outage | Do not equate UID activity with payload verification. No unmeasured threshold reduction or teardown policy change. Controlled UID/control-traffic fault injection remains useful. |
| Least-ping/one-target observation may not predict bulk behavior | Valid limitation; actual failed outbound remains unknown | Preserve strategy pending isolated real-core fault tests or trustworthy per-outbound evidence. No API or tag was invented. |
| IPv4 DNS / UDP-443 policy limits coverage | Deliberate compatibility limits, not universal coverage | Do not enable direct fallback, blanket IPv6 rejection or new protocols as a stall fix. Broader IPv6/UDP app acceptance remains incomplete. |
| XHTTP buffering / upstream memory report | Reason to profile, not a measured cause here | No speculative cap, pipe-budget or core change. A count cap is not a global memory bound. |
| Existing source, CI and phone evidence | Useful but scenario-specific | New physical-device failures take precedence over a universal green/readiness claim. No numerical rating assigned. |

## Verification and reproduction

Local checks completed on this source:

- 776 Android unit tests across 53 suites: zero failures, errors or skips.
- Debug app and instrumentation APK builds succeeded; native bundle gates passed.
- Android lint: zero errors, 24 warnings. Warnings are not represented as clean.
- New log analyzer: seven tests, including partial-body accounting, failure
  intervals, exact overlap boundaries, slow valid replies and double-counting.
- Existing Python fixtures: 26 tests passed; 67 server static checks and 69 Worker
  behavior checks passed. Linux behavior and native real-core checks also run in
  the repository's hosted workflows; local mocks are not live tunnel acceptance.
- Physical packaged-listener regression and strict transfer-reader regression
  passed. The latter still rejects truncated/oversized bodies, stopping after
  at most one excess byte.

Windows build command from `g2ray_app`, with the existing Android Studio JDK:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --max-workers=2
```

Local log analysis from repository root:

```powershell
python tests/analyze_vpn_load_test.py
python scripts/analyze_vpn_load.py <one-private-instrumentation-log>
```

Raw phone logs, trial manifests and operational scripts remain in ignored local
`data/verification/options-2026-10-29/`; they are not published with credentials
or working VPN links. Stage timing is not a real webpage, media or AI completion
measurement. A later passing run does not erase an earlier timeout.

## Still required

Identify the intermittent per-flow failure, reproduce the cause in a focused
fixture, and validate a correction without harming working streams. Useful next
evidence includes actual per-outbound behavior under a controlled bad route,
whole-path versus endpoint controls and a second physical network/device.

Overnight unplugged Doze, real Wi-Fi/LTE handoff, sustained real media, long AI
streams, independent IPv6/DNS/WebRTC containment and signed-release upgrades
remain separate acceptance work. No new YouTube result is claimed in this pass.

GitHub documents a maximum 12-hour Codespace active lifetime. A single Codespace
cannot guarantee uninterrupted hosting indefinitely. That platform constraint
does not explain the body-stage failures in these short, already-running-host
tests. See [GitHub lifecycle documentation](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle).

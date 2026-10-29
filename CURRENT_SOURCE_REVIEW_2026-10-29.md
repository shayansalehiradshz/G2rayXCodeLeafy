# Current-Source Claim Review - 29 October 2026

## Scope

Reviewed both supplied AI reports against local `7b5618c`, not just their older
review target `14367d8`. Implemented fixes are in `0b978c4`. No subagent or
emulator was used. Source tests, native fixtures, live server checks and phone
results are separate evidence categories; none establishes a flawless VPN or a
universal latency/throughput improvement.

## Claim Decisions

| Report item | Assessment and action |
| --- | --- |
| 1. Native UID bytes can falsely verify a failed route | Confirmed. Prompt reverification now requires a successful probe or HEV upstream payload evidence. Native UID bytes remain only a bounded periodic activity hint; they cannot publish fresh verification. Dialer/packet-engine failures still bypass this grace. |
| 2. Batch probes follow redirects / wrong 200 at a 204 endpoint | Redirect defect reproduced with real pinned `PingBatch`, not only extracted code. A shared measurement-client copy now rejects redirects without mutating its caller. Exact-204 validation was already present at our base revision and remains in force. |
| 3. SOCKS/full-device health accepts unexpected successful pages | Confirmed. Both clients disable redirects. The shared bounded body reader checks the original target, exact 204 for `generate_204`, and Apple's expected 200/Success content. The two-of-three quorum and TLS checks are unchanged. |
| 4. Old-network/expired failures exclude live exploration | Confirmed. Only fresh, same-network failures exclude an exploratory route. Expired, other-network and unknown-provenance measurements are unmeasured, within the existing three-route cap. A recent session failure now also overrides an older stored success. |
| 5. Optional DNS delays an already-ready primary | Confirmed. Optional DNS gets at most 50 ms once the selected endpoint is ready, rather than another 500 ms. The bounded primary/fallback budget is otherwise preserved. A sufficiently late primary can still miss that startup; waiting indefinitely or restarting healthy traffic would be worse. Debug logs record counts, elapsed time and selected-endpoint inclusion without addresses or credentials. |
| 6. Reimport need not activate newly exported tuning | True but intentional selection preservation, not lost data. Imports do not silently replace a working selection. Home now distinguishes XHTTP mode and `custom extra` versus `core defaults` on selected/listed profiles. Distinct URI/tuning variants remain distinct. |
| 7. Hostname fallback has no reserved live slot | True policy boundary, not a universal routing defect. The three-slot pool favors verified routes. No healthy measured route was displaced merely to guarantee a hostname slot. DNS-derived IPs are not a separate censorship-proof transport. |
| 8. Observer error exits bypass transport cleanup | Defensive fix applied: close idle connections with a defer immediately after transport creation. Real pinned-core fixtures cover successful/error/timed-out observations, closed sockets and detached results. This does not prove the old phone leaked sockets or caused the reported high ping. |
| 9. Engine/tuning defaults need matched comparisons | Agreed. Saved engine/tuning settings and transport defaults were not changed. Current device checks are regression evidence, not proof that one engine or tuned/untuned packet-up is universally faster. |
| 10. IPv4 DNS preference and UDP/443 block limit coverage | Correct compatibility boundaries. No speculative IPv6/DNS change, QUIC bypass or weaker TLS was introduced. End-to-end IPv6-only and UDP-only coverage still needs explicit acceptance. |
| 11. Worker uncertainty, CRLF and unsafe native cleanup | Ambiguous Worker wake 5xx reconciliation was already fixed in `7764e70`; its tests remain. The reviewer correctly withdrew the blanket autocrlf assertion. Native cleanup now additionally requires standalone, matching-origin, ownership-marked build clones and rejects symlinks/worktrees/personal checkouts before reset. |

Android documents UID RX counters as traffic across interfaces, not validated
tunnel payload: [TrafficStats API](https://developer.android.com/reference/android/net/TrafficStats#getUidRxBytes(int)).

## Verification

- Seven focused Kotlin regressions failed before their fixes; corrected production
  code passes them. Existing deadline/body assertions were retained, with the
  loopback fixture updated to supply endpoint-correct responses.
- Real Go single/shared/batch measurements reject wrong statuses and redirects;
  timeout/cancellation checks and JNI-facing failure mapping pass. Tests explicitly
  select the patched local Xray core, not an unpatched module-cache copy.
- Android: **761 tests across 50 suites**, zero failures/errors/skips.
- Build logic: **14 passed**. Android lint: **zero errors, 23 warnings**.
- Functional debug APK and instrumentation APK packaged successfully. Existing
  native artifact/API/provenance and HEV gates passed.
- **67 server static**, **213 Linux server behavior**, **69 Worker**, and **26
  Python** checks passed. The behavior suite ran unmodified in the actual Codespace
  using its isolated fixtures; it did not restart the production engine.
- **40 generated configurations** accepted by real Xray in the Codespace.
- **11 native build workspace checks** pass, including preserving unrelated files
  and rejecting unmarked/wrong-origin/symlink/worktree paths.
- CI now runs actual native measurement/batch/observer fixtures with the Go race
  detector, along with the workspace safety fixture.

Hosted checks on `0b978c4` passed:
[Android/native checks](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/40813191124),
[server/static checks](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/40813205695).

Private raw receipts and rollback APK/AAR copies are under
`data/verification/review-7b5618c-2026-10-29/`, excluded from Git. Two initial
recovery invocations omitted their explicit opt-in and were skipped; they are not
counted as passes. Correctly opted-in device runs are recorded separately.

## Artifact Identity

Installed debug APK SHA-256, matched against the phone's `base.apk`:

```text
F70B419CB0B7F4133A612888B802DB0C4BB11AB42F0753FE6FF466907899CDE0
```

Rebuilt native AAR SHA-256:

```text
13C661AF821ECCB49014A6D25A5DF425DB6B1BBA16DC1F03267D4FF17D087A39
```

Go remains 1.26.10, with the existing pinned libXray/Xray/mobile revisions. Only
the reviewed native patches changed. Build metadata records both patch hashes.
The active server remains Xray 26.10.21, balanced/packet-up, link tuning off;
local/public route checks returned 200 and supervisor/watchdog were healthy.

## Device Evidence

Test phone: Samsung SM-A528B, Android 14, mobile data. Accounts, credentials,
selected config and saved HEV/Fast-DNS/IPv4-only/seamless settings were preserved.
Explicit engine/transport arguments exercised alternatives without saving them.
The selected XHTTP profile actually contains **custom extra settings**, confirmed
on the phone's new label. The server's tuning-off export preference does not
retroactively change that saved profile. These are not tuned-versus-untuned A/B
results; the saved working profile was not replaced.

- Packaged native cores load and a multi-route core starts/stops successfully.
- Ping All completed 24 profiles in **17.916 seconds**: 23 valid, one failed.
  Results were visible/persisted and selection was unchanged.
- Native and HEV both verified XHTTP and WebSocket full-device traffic.
  These four individual startups were **4.738-5.418 seconds**, not a startup p95.
- Two deliberate core stops per engine recovered without manual Start. Native
  detection/repair plus the subsequent HTTPS quorum took **7.697/7.818 seconds**;
  HEV took **8.016/8.760 seconds**.
- Both engines passed at least three minutes of background idle, then complete
  requests from both retained pools and explicitly fresh sockets. Native retained-
  pool responses were **486-637 ms**, HEV **747-1,329 ms**. Transparent replacement
  connections were observed; this is successful client recovery, not proof that
  every original TCP socket survived.
- Each engine recovered from an actual eight-second mobile-data outage. After
  the physical network returned, verified state took **3.392 seconds Native /
  4.002 seconds HEV**; completion of the three-response HTTPS check took **4.968 /
  6.371 seconds** respectively. Mobile data was restored.
- Both loaded tests completed 12 alternating 8 MiB downloads and 2 MiB uploads:
  **120 MiB per engine**, 240 MiB total application payload. All **216** HTTPS
  workload-loop responses were valid, with zero failed/invalid responses. **170**
  completed entirely inside actual bulk requests, independently checked against
  the recorded intervals, rather than merely while a worker was scheduled.

| Complete requests entirely inside bulk transfers | Count | Median | Nearest-rank p95 | Maximum |
| --- | --- | --- | --- | --- |
| Native/XHTTP | 77 | 280 ms | 350 ms | 460 ms |
| HEV/XHTTP | 93 | 258 ms | 306 ms | 366 ms |

Individual 8 MiB download samples were **24.43-36.22 Mbps Native** and
**22.43-35.12 Mbps HEV**. Individual 2 MiB upload samples were **12.94-25.00 Mbps
Native** and **4.69-24.71 Mbps HEV**. These use complete request duration and
decimal Mbps, not a promise of sustained capacity, link saturation, or an engine
ranking from one sequential pair.

- Share/QR/copy, large-draft import/rotation, settings Back/help and direct GitHub
  warm-host control-plane checks passed. The latter took **834 ms** and correctly
  did not claim route readiness from API availability alone. This was not a cold
  host boot and no Worker is configured for this account.
- Normal warm app reopens measured **172/193/196 ms** by Android's activity-launch
  timing, versus a **2.137-second** cold app launch. Both idle harness runs included
  approximately 46 seconds in the close/relaunch portion beyond the requested
  180-second wait. That test-only lifecycle overhead remains unexplained; it is
  not counted as network-request time or presented as a reproduced tunnel stall.
- **20 real-device instrumentation test executions passed**, excluding the two
  skipped setup attempts. Native libraries loaded; no crash-buffer entry was
  present, and inspected process exits were normal instrumentation cleanup.
  This does not establish crash freedom across untested scenarios.
- Final screenshots/XML confirmed a ready HEV tunnel and fitting config labels.
  The phone was left connected with its saved settings, mobile data on and original
  stay-awake setting restored. Android outside-VPN blocking was not enabled or
  changed; process-death/reboot leak containment is not claimed.

## Remaining Evidence Limits

No synthetic score, pass count or six-second test ceiling proves instant browsing.
Current samples do not establish a new speed gain, an engine ranking, complete
IPv6/UDP coverage, all-app compatibility, uninterrupted AI sessions, overnight
unplugged Doze, signed-release upgrades or a second-device acceptance. Codespace
quota/lifecycle and carrier/provider behavior remain outside app guarantees.

No default was changed solely on an AI prediction. The changes improve correctness
of health/ranking/recovery decisions and native build safety while preserving the
working tunnel configuration.

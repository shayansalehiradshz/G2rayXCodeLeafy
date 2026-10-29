# Phone Verification and Recovery Fixes - 29 October 2026

## Scope and Outcome

Verified the current source, native bundle, live Codespace and a real Samsung
SM-A528B running Android 14 on mobile data. No emulator or subagent was used.
The tested cases passed, including full-device HTTPS, idle resume, repeated core
recovery, physical LTE outage, loaded requests and bounded YouTube playback.
This is scenario-specific evidence, not proof of a flawless or universally fastest VPN.

Base revision: `14367d86e6087ecd6494f36443a4c347133043b0`.
Fixes: `7764e70` (wake reconciliation) and `d6e726d` (native measurements/build).
The unrelated local `DIAGNOSTICS_REVIEW_2026-09-06.md` was not changed or committed.

## Implemented Fixes

- Treat an ambiguous Worker wake HTTP 500-599 as potentially accepted. Reconcile
  through health rather than sending another wake to an alias. Preserve Retry-After,
  cancellation, deadlines and uncertainty passed to direct GitHub recovery.
- Stop immediately for known permanent Worker configuration failures, including
  when backup URLs exist. Read-only health requests can still use backup endpoints.
- Require HTTP 204 for native `generate_204` measurements, including query strings.
  Other probe targets retain their normal 2xx policy. Redirects and HTTP errors
  remain invalid. Fixtures also cover timeout and cancellation.
- Normalize both disposable native source checkouts to LF, including reused
  checkouts, without changing global Git settings. Real Git fixtures cover
  `core.autocrlf=true` and explicit CRLF text checkout.
- Run the native measurement fixtures during AAR construction and in CI against
  the pinned, actually patched libXray source. CI also runs the checkout fixtures.
- Update the device harness to accept Auto's actual engine and distinguish
  approximate native UID counters from HEV's real TUN counters. Explicit-engine
  tests still reject exercising the wrong engine.

Three Worker regressions failed before the fix. The native fixtures separately
reproduced HTTP 200 and 201 being accepted at a `generate_204` path before its fix.

## Artifact Identity

Final installed debug APK SHA-256, checked against the phone's `base.apk`:

```text
1ACBDBEC7ED8096142ED6CE9E9A25305BF2FB3F272FC6315EACA931AAF53FF71
```

Final libXray AAR SHA-256:

```text
42A4F680D75DC191ED8A7849C63A2826A8E45F165CB8E7E98E7CE39EAE39F4F5
```

The AAR was rebuilt with Go 1.26.10 and the existing pinned libXray/Xray/mobile
revisions. The native measurement patch hash is
`CC9E9B4E4C2DC5C1764903CFFF999CF2E73B7FE4AC37011A6268F0119649274F`.
All existing native bundle/build-contract gates passed.

Earlier startup/idle/loaded/video measurements used the original AAR
`05528CFBEF8A2A97DEB1D30D26C219B37715EC3919C2D2085A8D238A082843A3`.
The intermediate wake-fixed APK was
`9C1E31E933561764C373F8B63B269E8139D344806D98D160F0BBEBEFAA670CBC`.
Those observations are separated from final-APK results below.

## Automated Verification

| Check | Result |
| --- | --- |
| Android unit tests | 751 tests, 48 suites, zero failures/errors |
| Build-logic tests | 14 passed |
| Android lint | Zero errors, 23 warnings |
| Debug APK and instrumentation packaging | Successful; real native bundle verified |
| Server static checks | 67 passed |
| Linux server behavior checks | 213 passed in the actual Codespace's isolated test fixtures |
| Worker behavior checks | 69 passed |
| Python fixtures | 26 passed, including three native checkout checks |
| Patched Go measurement fixtures | Passed during native build and independently afterward |

The server remains Xray 26.10.21. Its local and public OPTIONS probes returned
200; the supervisor and watchdog were running. No engine/config/UUID/port change
or live restart was needed. The Android native core remains its separate tested pin.

## Final-APK Device Evidence

Saved settings were preserved: HEV, Fast DNS on, IPv4-only on, DPI compatibility
off, seamless failover on. Explicit test arguments exercised Native separately;
the selected account, profiles and credentials were not replaced.

- Packaged cores loaded and the multi-route core started/stopped successfully.
- Ping all completed 24 profiles in 17.9 seconds: 22 valid results and two failures,
  with visible results persisted and selection unchanged. Earlier scans had 23/24;
  these are separate network samples, not proof the stricter rule caused a failure.
- Direct GitHub warm-host status/wake completed in 919 ms. `route_ready=false`
  correctly means GitHub availability alone is not a working data-path proof.
  Independent tunnel tests supplied that proof. This was not a cold Codespace boot.
- HEV/XHTTP survived an eight-second real mobile-data outage without manual Start.
  After the physical network returned: verified state in 4,295 ms, first complete
  HTTPS response in 5,450 ms, and three-response quorum in 7,808 ms.
- Two HEV/XHTTP native-core stops recovered automatically. Detection, repair and
  the subsequent full HTTPS quorum together took 7,550 and 7,873 ms.
- Final Native/XHTTP loaded test completed 12 alternating 8 MiB downloads and
  2 MiB uploads (120 MiB payload). There were 93 completed probe requests and zero
  failed/invalid responses; 74 completed entirely inside actual bulk requests.

| Final loaded-request sample | Count | Median | Nearest-rank p95 | Maximum |
| --- | --- | --- | --- | --- |
| All requests in the workload loop | 93 | 256 ms | 465 ms | 888 ms |
| Entirely inside a bulk request | 74 | 256.5 ms | 465 ms | 888 ms |
| Entirely inside a download | 60 | 254.5 ms | 295 ms | 888 ms |
| Entirely inside an upload | 14 | 284 ms | 522 ms | 522 ms |

Individual final transfer samples were 30.0-37.0 Mbps down and 12.7-25.4 Mbps up.
These include request setup and use decimal Mbps. The test demonstrates request
overlap, not continuous link saturation or the maximum possible bandwidth.
Probe durations are complete small HTTPS responses, not ICMP RTT or isolated TTFB.
The test's six-second failure ceiling is not being presented as a low-latency SLA.

The final HEV/XHTTP YouTube observation lasted three minutes: first matching local
Playing state at 5,347 ms, 174,633 ms of adjacent observed Playing intervals, zero
reported post-start buffering transitions, and no budget cutoff. Mobile counter
delta was 46,328,102 bytes, including other apps. Post-video HTTPS checks passed.
The player was paused/stopped afterward; its resolution and decoded frames were
not independently measured. This does not establish instant or 4K playback.

## Earlier Same-Session Controls

All four explicit HEV/Native and XHTTP/WS startup combinations verified and carried
three complete HTTPS responses. Initial verified-start samples were 7,679 ms
(Native/XHTTP), 3,975 ms (HEV/WS), 3,144 ms (HEV/XHTTP), and 3,392 ms (Native/WS).
These small, sequential samples are not an engine ranking or reliable startup p95.

After 180 seconds in the background without reopening the app, Native/XHTTP's
retained-pool requests completed in 720-760 ms and fresh controls in 552-701 ms.
Connection identities showed replacement sockets; this is not proof the original
sockets survived. HEV/WS reused its connections in 243-272 ms; fresh controls were
1,514-1,968 ms. The previous 20-second hang was not reproduced in these checks.

Earlier loaded-loop samples also had zero failed responses: Native/XHTTP 102
requests, median 266 ms, p95 398 ms; HEV/XHTTP 96 requests, median 268 ms, p95 372 ms.
An earlier three-minute Native/XHTTP video had 176,282 ms of observed Playing,
first Playing at 3,630 ms, and zero reported rebuffer transitions. These results
belong to the original AAR, not silently to the final replacement.

The intermediate wake-fixed APK passed two Native/XHTTP core recoveries and an
LTE outage: verified state 3,242 ms after network return, first complete response
at 3,734 ms, full quorum at 4,866 ms. The saved HEV UI startup/60-second idle path,
settings/help navigation and large-import draft rotation also passed.

## Evidence and Handoff

Raw local evidence is gitignored under `data/verification/phone-2026-10-29/`.
It contains separate logs for each run, pre-fix failures, build results, hashes
and calculated loaded-response distributions. There were 28 successful
instrumentation test executions, including repeated engine/build variants, not
28 independent real-world coverage guarantees.

App exit-history entries retrieved during testing were normal instrumentation
completion/force-stop events; the crash buffer was empty. No crash/ANR was observed
in these scenarios. This is not an absence-of-crashes guarantee.

The final app was started normally and showed `VPN connected and route verified`.
Mobile data remained enabled. The temporary USB stay-awake setting was restored
to its original zero; no lock policy, account, credential or saved setting changed.

## Remaining Evidence Limits

No matched old-APK versus new-APK network experiment establishes the cause of the
historical ping regression. Do not infer it from these sequential samples.
No new transport, buffer, MTU, fragmentation or DNS default was changed on a guess.

Overnight unplugged Doze, real Wi-Fi/LTE handoff (no second network available),
long real AI streams, independent leak/exit-country containment, signed-release
upgrade and a second device still need their own acceptance. Cold host boot was
not retested by stopping the active Codespace. Platform lifetime/quota, provider
behavior, carrier congestion and app/CDN behavior remain outside a code-only guarantee.

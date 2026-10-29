# WebSocket reliability, cancellation, and physical-phone verification

Reviewed and implemented 29 October 2026. Production-code revision:
`60d59a1a71139b8af7a0179ee60fe978851ceca7`. This is a reproducibility record,
not a claim of perfect networking or a numerical readiness score.

## Findings Independently Checked

| Claim | Reproduction | Decision |
| --- | --- | --- |
| WebSocket discards data returned with EOF | The real Gorilla frame fixture lost all 8,192 final bytes on the original Android pin; a synthetic reader also failed. | Fixed in the Android core and maintained server. |
| WS handshake ignores its derived deadline | Chrome and unsafe paths received almost 60 seconds instead of the configured eight; a silent peer waited 9.5 seconds until the outer deadline. | Pass Gorilla's context into the dial and TLS handshake; keep the existing eight-second limit. |
| Failed WS TLS handshake retains its socket | The original invalid-TLS fixture returned without closing its dialed connection. | Close on handshake and hostname-verification failure. |
| VLESS retry continues after cancellation | The actual outbound handler missed the 500 ms cancellation bound before dial, during dial, and during backoff. | Separately attested context-aware retry; preserve uncanceled retry behavior. |
| Optional WS early data has a close/read race | Race detector reproduced it on the original pinned source. | Not fixed by the main WS patch. None of this phone's 15 saved WS profiles enables it. Do not enable `?ed=...` for production pending a separately reviewed state-machine fix. |
| A score or one test block proves AAA quality | There is no such inference from the supplied measurements. | Reject numerical certainty and universal performance claims. |

The byte-preservation fix follows the [Go Reader contract](https://pkg.go.dev/io#Reader).
The injected EOF test is not a packet trace proving the cause of an earlier phone stall.
The cancellation fix concerns obsolete connection setup, not resumable uploads or
automatic replay of a user's AI request.

## Native And Build Evidence

- Android source pin remains `5ca6f4b7d4dc20a881d4330e498892697627ec0c`;
  libXray remains `80263da83e96b2972455b0a94b13ee1a10e51391`, Go 1.26.10.
- Focused patched WS race checks passed three repetitions; the actual local WSS
  final-frame control passed 20 repetitions without claiming the entire upstream
  TLS-server package is race-clean.
- Focused retry/cancellation checks passed 20 race repetitions, including the
  real VLESS outbound. The complete retry package and outbound package also passed
  under the race detector, retaining their original legacy-retry tests.
- Both server reader regressions failed against exact source
  `1b1647cd9ddf0abc34b981d4611fc03bcac1ca74` / 26.10.21 and passed 20 focused
  race repetitions with the reader correction and Go 1.27.3.
- The Windows cross-build and the committed Bash maintained-server builder
  produced the same Linux binary hash. Owned WS/XHTTP client/server checks passed
  with both stock and candidate servers and the separately built Android-pinned
  client. These are loopback compatibility checks, not Android TUN or ISP tests.
- One isolated-server preflight initially failed because its staging directory
  lacked `geoip.dat`. Giving both controls the live server's existing asset
  directory resolved it. No routing or test assertion was weakened.
- Local Android verification: 786 unit tests in 56 suites, zero failures/errors/
  skips; 36 build-logic tests; lint zero errors and 24 warnings; debug and
  instrumentation APK builds and both native verification gates succeeded.
- 42 Python tests, 67 server static checks, and 69 Worker checks passed.
- [Android CI](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/40941725104)
  and [server/static CI](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/40941742059)
  both completed successfully on the exact production-code revision. This includes
  the maintained Docker image build, Linux behavior checks, native fixtures,
  real generated configurations, option matrix, ShellCheck, and Worker packaging.

## Artifacts And Rollout

| Artifact | SHA-256 |
| --- | --- |
| Final native AAR | `52743B91D037CD8B75CA9E2CB3F0E8022A17769D7533A4867C8D4A35D96D6D31` |
| Final debug APK | `2C8EE2D6DFEC8C42B76B10003859627C39734B84E5A1FEFFB52CB18A117E0C76` |
| Instrumentation APK | `3D1A86F89635B34FB3B76612B77D1B7CA58FC44C41BA3117C54B9C1A90C3DEC4` |
| Maintained Linux server | `4AFD7BECB3EE994AC998437FB66BE41B194AF0E383B41B6CC781CBE0443315AB` |
| Android WS patch | `A1CE3EDF74083E6AFFDC12CBC4E16B19311EB79563D6922F0A2ACCDC45DBFDD0` |
| Android cancellation patch | `E65B3222057B918229A758D41BA12CB5AE58B61140977C7CA319DCEAE82F824B` |
| Server reader patch | `B2B15A1309369AB44EE362E83F056C67E91EF8851C8A385FABF3310A46F3B2C4` |

Installed both APKs with `adb install -r`, without clearing app data. Pulled the
installed `base.apk` and verified it matches the final APK hash above. All four
AAR native libraries match Gradle's merged inputs; all four packaged APK libraries
match the stripped outputs. Stripping legitimately changes the raw library hashes.

The approved live server promotion preserved the parsed configuration and exact
UUID bytes. Its running `/proc/PID/exe` hash matches the maintained server above;
two consecutive public route probes returned HTTP 200. The preceding binary and
private configuration rollback copies remain available. Container rebuilds now
include the same source pin, reader fix, regression tests, and provenance receipt;
this adds build-time work, not wake-time compilation. The official release is
checksum verified and retained in the image for controlled rollback.

No MTU, DNS defaults, selected account/profile, custom XHTTP `extra`, balancing
strategy, radio policy, or default transport was changed to produce these fixes.

## Acceptance Boundaries

### Final Phone Upload Matrix

Samsung SM-A528B, Android 14, arm64-v8a, on the same cellular path. This block
used the final installed APK and the promoted server. Each row alternated four
full-TUN and four loopback-SOCKS 2 MiB uploads through the same core. There was
no direct-Internet bypass, HTTP redirect, automatic HTTP retry, or discarded
failed upload. Preference and selected-profile restoration assertions passed.

| Engine | Initial transport | Failover | Completed upload controls | Failures |
| --- | --- | --- | --- | --- |
| HEV | XHTTP | Off | 8 | 0 |
| HEV | XHTTP | On | 8 | 0 |
| HEV | WS | Off | 8 | 0 |
| HEV | WS | On | 8 | 0 |
| Native | XHTTP | Off | 8 | 0 |
| Native | XHTTP | On | 8 | 0 |
| Native | WS | Off | 8 | 0 |
| Native | WS | On | 8 | 0 |

Total: 64/64 HTTP upload controls completed, 128 MiB synthetic payload. With
failover enabled, the initial profile's transport does not prove the outbound
used by every live connection. This is a bounded test block, not an everyday
failure-rate estimate, remote payload-hash verification, or a speed guarantee.
Four packaged-core/profile-inspection device checks also passed.

### Sustained Transfers, Recovery, And Video

These checks used the final APK and promoted server. Failed runs remain in the
private evidence; neither timeouts nor failed first attempts were discarded.

| Check | Observed result |
| --- | --- |
| HEV/WS, failover off, concurrent browsing and transfers | 12 complete 8 MiB downloads plus 12 complete 2 MiB uploads; 120 MiB payload in 61.5 s, 111 complete responsive probes overlapping bulk requests; passed. |
| Native/WS, failover off, same workload | 12 complete downloads plus 12 complete uploads; 120 MiB in 74.3 s, 161 overlapping probes; passed. |
| HEV/XHTTP, saved custom extra, same workload | First 8 MiB download hit its 30 s deadline after receiving 299,673 bytes; failed. |
| Native/XHTTP, saved custom extra, same workload | First 8 MiB download hit its 30 s deadline after 442,368 bytes; failed. |
| HEV/XHTTP, temporary clone without extra | All 12 downloads and 12 uploads completed; 120 MiB in 120.9 s, 302 overlapping probes; passed. Warm 8 MiB downloads mostly took 1.9-2.5 s, but 2 MiB uploads took about 6.9-7.5 s. Saved profile unchanged. |
| Two forced native-core failures under HEV/XHTTP | Automatic recovery and subsequent independent three-endpoint checks passed; 13.2 s and 9.5 s including those checks. |
| HEV/WS and Native/XHTTP short background idle | Both passed retained-pool and fresh-socket checks. The configured idle interval was 120 s; the recorded gap also includes activity restoration. This was plugged-in, screen-on idle, not deep Doze. |
| Eight-second LTE outage under HEV/XHTTP | User-connected intent retained; VPN verified 4.28 s after physical network return, subsequent full three-endpoint quorum completed by 6.87 s; radio settings restored. |
| YouTube under saved HEV/XHTTP with failover | 180 s bounded sample: first Playing state at 8.19 s, 171.7 s observed Playing, zero reported buffering transitions, approximately 84 MB mobile traffic including background apps; passed the existing sample contract, not the proposed three-second startup goal. Resolution was not independently measured. |

The failed XHTTP downloads kept making small byte progress until near their
deadline. They are slow-transfer failures, not evidence of 30 seconds with zero
progress. Other small HTTPS requests continued to complete, and the service did
not spontaneously tear down the healthy flows. The successful YouTube sample
also prevents generalizing the synthetic endpoint failure to every video/CDN.

Removing extra changed multiple tuning fields at once. This is a strong lead
about the saved optional tuning on this path, not a controlled identification of
one offending field or proof that the same tradeoff holds on every ISP. Factory
link tuning already defaults off; no global default or saved URI was silently
changed. WS had the better completed bulk-transfer block here, while core-default
XHTTP traded faster downloads for slower uploads. Neither benchmark establishes
page readiness or a universal transport ranking.

The owner approved leaving the phone on the tested WS profile. ADB disconnected
before the selection tap could execute, and the owner then requested no further
device tests. **That selection change was not completed.** The current saved
selection remains unchanged; the tested same-edge WS entry is
`G2rayXCodeLeafy|shayansalehiradshz-ws-ip1-h2` at `20.85.77.48:443`.
No final WS YouTube playback or post-selection connection was verified.

### Earlier Controls

Before the new WS APK, the already-repaired XHTTP APK completed two eight-upload
controls (HEV with failover off; Native with failover on) and a short Native idle
check. The latter replaced its old pooled connections rather than proving they
survived unchanged. Old-core Native/WS and new-client/stock-server Native/WS also
each completed eight uploads. These are controls, not proof of a speed increase.

Small HTTPS responses, full 2 MiB HTTP upload acknowledgments, exact local writer
counts, byte hashes in a deterministic native fixture, and remotely verified
payload hashes are different evidence. The public upload control does not return
a server-side payload hash; it must not be described as end-to-end hash acceptance.
Warm small-response timings are not ICMP ping, full page readiness, video quality,
or a guarantee of uninterrupted AI streams.

The Windows browser's quickly rejected ChatGPT attachment remains a separate,
unreproduced issue. The earlier Telegram Saved Messages acknowledgments used the
previous APK and are not evidence for this final build. Real Wi-Fi/LTE handoffs,
unplugged overnight Doze, signed-release upgrades, a second phone, long AI sessions,
and all-platform coverage are not certified by this work. Neither are optional
WS early data, transparent resumption of every interrupted stream, or indefinite
uptime from a single Codespace.

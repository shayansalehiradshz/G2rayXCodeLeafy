# Option and interaction verification - 29 October 2026

## Scope

Verified code: `01841cee54481d87a85ae3e76a2b09dc21f704e3`.
The accompanying follow-up adds a reversible failover/load test and this record;
it does not change the runtime data path.
Priorities: browsing responsiveness, social/video loading, and reliable recovery.
Device: physical Samsung SM-A528B, Android 14, mobile data. No emulator was used.

This is an evidence record, not a claim of universal compatibility, zero packet
loss, or the fastest VPN. A valid configuration is not proof of a working route.

## Changes

- Added exhaustive preference/configuration interaction tests and real-core CI
  validation instead of changing working packet-path defaults speculatively.
- Added opt-in, reversible physical-device option tests. Backups contain only
  the settings changed by the test, never account credentials or config links.
  An interrupted settings test has a separate restore selector.
- Labeled connection switches and per-app checkboxes for accessibility.
- Clarified that DPI compatibility is experimental and can prevent a working
  route from connecting. It remains off by default.
- Corrected the server menu's upload-tuning description: off is the browsing
  default; tuning remains an explicit upload A/B option.
- Scoped YouTube test cleanup to Android's current user instead of targeting
  every profile on a multi-user/Secure Folder device.

No MTU, buffers, socket timeouts, DNS defaults, XHTTP pacing, native library,
server credentials or live UUID were changed in this pass.

## Automated coverage

| Layer | Verified result | Boundary |
|---|---|---|
| Preferences | 1,536 combinations: three engine preferences, six binary settings, eight routing combinations | Persistence/contracts, not 1,536 live-network runs |
| Generated client configurations | 1,408 combinations across both packet engines, DNS/IPv4/DPI/balancer flags, four domain policies, four XHTTP modes with/without extra, and three WS ALPN variants | All accepted by real Xray 26.7.28 and 26.10.21; syntax does not prove edge support |
| Server options | 36 profile/override/WS combinations and 342 link/export combinations | Isolated generated configurations and real-core validation; no live engine mutation |
| Server transitions | Both low-overhead/latency-focus toggle orders | Mutually exclusive; original profile and UUID retained |
| Android unit tests | 765 tests across 51 suites, zero failures/errors/skips | Includes the matrices, not 765 hardware scenarios |
| Android builder contracts | Explicit exclusions, own-app exclusion protection, missing apps, IPv4/dual-stack on SDK 24 and 31 shadows | Builder calls, not independent leak tests |
| Build logic | 14 tests, zero failures | Fresh rerun |
| Android build/lint | Functional debug APK and instrumentation APK built; zero lint errors, 23 warnings | Not signed-release upgrade certification |
| Server regressions | 67 static checks, 213 behavior checks | Isolated behavior fixtures |
| Worker regressions | 69 checks | Simulated GitHub/route/storage responses; no new Worker deployment |
| Python fixtures | 26 checks | Protocol/diagnostic and checkout fixtures |

Hosted checks for the verified source are green:
[Android Tests](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/40824790315)
and [Static Tests](https://github.com/shayansalehiradshz/G2rayXCodeLeafy/actions/runs/40824801211).
The hosted job also explicitly reports all 1,408 option configs validated.

## Physical-device options

All 48 normal-path combinations passed: Auto/HEV/Native x XHTTP/WS x Fast DNS
on/off x IPv4-only on/off x same-Codespace failover on/off. Diagnostics was also
varied during these runs. Auto actually used Native on this device.

Each case checked the preferences consumed by the real service, a verified VPN,
Android VPN routing, three complete fresh HTTPS responses, three warm responses,
and unchanged account/profile/key selection. All 288 response attempts succeeded.

| Requested engine | Cases passed | Fresh median / p95 | Warm median / p95 | Verified startup median / maximum |
|---|---|---|---|---|
| Auto | 16/16 | 1,366 / 2,155 ms | 327 / 851 ms | 4,131 / 8,451 ms |
| HEV | 16/16 | 2,154 / 4,196 ms | 328 / 498 ms | 6,043 / 7,421 ms |
| Native | 16/16 | 1,139 / 1,826 ms | 319 / 487 ms | 4,340 / 8,398 ms |

These are small complete HTTPS-response timings, not ICMP ping, full-page load
times, sustained throughput, or a randomized engine benchmark. Options and
network conditions varied. Do not select global defaults from these medians.
Peak process PSS during repeated starts was approximately 387 MiB; that is not
a steady-state memory or battery benchmark.

Additional physical checks passed:

- Five UI tests: every connection switch off/on, all engine choices, recreation
  persistence, help/back navigation, QR sharing/copy, large import drafts,
  per-app row and checkbox actions, routing save, and invalid-domain rejection.
- Four live domain-bypass cases: both packet engines, each with Fast DNS off/on.
  The deliberately bypassed domain matched the physical exit. This was an
  explicit temporary bypass, not an accidental leak.
- Quick Settings tile: verified start, verified stop, unchanged selection and
  restored tile arrangement.
- Ping All: all 24 saved configs completed in 20.73 s; 22 returned successful
  measurements and two returned failures. Failed routes were not labeled fast
  or silently deleted. Selection was preserved.

## Compatibility findings

DPI compatibility did **not** work on this tested carrier/edge. Its normal
handshake baseline worked, while the built-in fragment recipe timed out on
both transports. Simpler fragmentation also failed in the component comparison.
Native TLS without fragmentation worked for XHTTP but not the tested WS variant.
The two DPI-enabled startup failures remain recorded; they are not counted as
successful option combinations. Leave this experimental feature off here.

The source matrix validates the other 48 DPI-enabled configuration combinations,
but they were not all individually replayed through this incompatible network.
The default unfiltered live matrix remains strict rather than accepting failure.

XHTTP stream-up/stream-one remain experimental. Their link/configuration
contracts were checked, not newly certified through GitHub's HTTP edge.
Packet-up remains the compatibility default. A front-domain export likewise
does not prove that Cloudflare routing has been provisioned for that hostname.

## YouTube and external pauses

The public [Big Buck Bunny sample](https://www.youtube.com/watch?v=YE7VzlLtp-4)
was observed through the real YouTube app and local MediaSession, not a download
substitute. Quality was automatic; cache was not cleared and the run order was
not randomized. These samples do not establish 4K capacity or sustained speed.

- Native: 180-second sample, first Playing at 5.56 s, 174.34 s observed Playing,
  zero reported buffering transitions; post-video HTTPS quorum passed.
- HEV, with an interfering caller-ID app manually stopped by the owner for this
  controlled test: 180-second sample, first Playing at 5.04 s, 174.87 s observed
  Playing, zero reported buffering transitions; post-video HTTPS quorum passed.
- Two earlier HEV samples failed continuous-play acceptance because the player
  changed to Paused. Android's audio log independently recorded the other app
  requesting ringtone audio focus at each pause. The VPN remained connected.
  These failures were retained, not reclassified as successful runs.
- The caller-ID app was reopened immediately after the controlled test. Its
  settings/permissions were not changed. The VPN does not suppress other apps'
  audio focus or automatically resume video over a call interruption.

[Android documents audio-focus preemption and media pausing](https://developer.android.com/media/optimize/audio-focus).
The timestamps and controlled repeat support an external audio interruption in
these samples; they do not explain every historical video stall.

## Browsing during transfers

Each successful load run completed twelve 8-MiB downloads and twelve 2-MiB
uploads (120 MiB total), with independent complete HTTPS requests inside bulk
request intervals. This proves overlap, not sustained link saturation.

| Engine / failover | Result | Overlapping responsive requests | Warm probe median / p95 / max |
|---|---|---|---|
| HEV / on | Passed, 36.95 s workload | 76 | 261 / 455 / 1,114 ms |
| Native / on, first attempt | Failed during first download: 30-second call timeout | Not accepted | 258 / 13,502 / 13,502 ms |
| Native / on, immediate repeat | Passed, 36.14 s workload | 79 | 266 / 388 / 552 ms |
| Native / off | Passed, 45.24 s workload | 57 | 418 / 721 / 1,329 ms |
| HEV / off | Passed, 49.09 s workload | 61 | 426 / 963 / 1,664 ms |

All complete during-transfer probes, including slow ones, are included in these
latency summaries. The failed Native attempt had two Google responses taking
12.25 and 13.50 seconds while the other endpoints remained responsive. Its bulk
download timed out. The VPN stayed connected; the test then stopped it for
cleanup. This was not relabeled successful because a repeat passed.

The same settings subsequently passed, as did the single-route controls. This
does not establish whether the outlier was the carrier, an edge, the endpoint,
the native packet engine, or a combination. It is an unresolved performance
outlier and prevents a claim of flawless loaded latency. The run order was not
randomized, so the table is not evidence to disable failover globally. The
owner's original HEV preference and failover setting were preserved.

## Repeating checks

Build/install the matching debug and test APKs, unlock the phone and grant VPN
permission first. These live tests stop/restart the VPN; do not run them under
Android always-on. Never overlap APK installation with instrumentation.

```powershell
cd g2ray_app
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

With one physical USB device attached, run the normal-path matrix:

```powershell
adb -d shell am instrument --user 0 -w -r -e class com.g2ray.client.vpn.OptionMatrixDeviceTest#allDataPathSwitchEngineAndTransportCombinations -e liveOptions true -e optionDpi false com.g2ray.client.test/androidx.test.runner.AndroidJUnitRunner
```

For a controlled load test, use selector
`OptionMatrixDeviceTest#loadedTrafficWithExplicitFailoverSettingRestoresPreferences`
with `liveOptions=true`, `liveVpn=true`, `smallProbesOnly=true`,
`liveLoadedTransfers=true`, `optionFailover=false` or `true`, and
`engine=hev` or `xray_native`. Each successful run consumes at least 120 MiB.
The default option matrix without the `optionDpi` filter includes all 96 cases
and remains capable of failing on an incompatible DPI recipe.

If a settings-changing run is interrupted, reconnect and run selector
`OptionMatrixDeviceTest#restoreSettingsAfterInterruptedOptionRun` with
`liveOptions=true` before running further option tests. It restores the private
backup rather than replacing accounts or credentials.

On Linux, server option validation uses only a temporary fixture:

```bash
bash tests/g2ray_option_matrix.sh /usr/local/bin/xray
```

## Preservation and remaining boundaries

The option/UI tests restored their settings, routing rules, selection, Quick
Settings layout, mobile-data state and temporary stay-awake setting. Accounts,
keys and imported config URIs were not rewritten. Test diagnostics remained
private under the ignored `data/verification/options-2026-10-29` directory.
Invalid runs caused by the test launcher's APK-install overlap or ADB argument
quoting were retained separately and excluded from accepted network results.

The active Codespace pulled the verified commit without an intentional engine
restart or UUID change. The real server core is 26.10.21. The installed client
native bundle was unchanged (SHA-256
`13C661AF821ECCB49014A6D25A5DF425DB6B1BBA16DC1F03267D4FF17D087A39`).
The installed main APK hash is
`466588EB0057AB50CC653E49D34083E5E1A5A4C92B63E3ADFBD22317352E5E8A`.

Still not universal acceptance: all carriers/sites, independent IPv6/DNS/WebRTC
containment, OS always-on/lockdown/reboot combinations, actual QR-camera scans,
unplugged overnight Doze, a second working network for real Wi-Fi/LTE handoff,
signed-release upgrades, sustained Instagram/Telegram sessions, and long real
cloud-AI conversations. Destructive account/key actions were not tried against
the owner's saved data. Automatic discovery's UI/persistence and source tests
were checked without mutating the saved profile inventory in a live scan.

No benchmark here proves instantaneous cold pages or video startup. The measured
tails and approximately five-second cold YouTube starts remain visible rather
than being hidden by a numerical readiness rating.

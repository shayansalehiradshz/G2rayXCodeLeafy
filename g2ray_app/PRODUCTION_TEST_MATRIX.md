# Production Candidate Test Matrix

Run these checks on the same physical Android phone and ISP used for daily traffic. Use the same VLESS URI for this app and v2rayNG; otherwise the comparison is not valid.

## Automated gates

```powershell
./gradlew.bat testDebugUnitTest assembleFunctionalDebug lintDebug
./gradlew.bat connectedDebugAndroidTest
./gradlew.bat bundleRelease
```

`assembleDebug` and `assembleFunctionalDebug` must verify the bundled libXray
hash and supported Java API, every HEV ABI, and the pinned native-build
toolchain contract. The device test must report that both libXray and HEV load
on the phone's real ABI.
`bundleRelease` must run the same libXray patch, AAR hash, HEV ABI/hash, and
pinned-builder checks before signing.
The packaged-core device smoke test must also start and stop a three-outbound
least-ping config; compiling its JSON alone is not enough to validate the JNI
and pinned Xray runtime boundary.

Source startup regressions must distinguish config-loader rejection from a
temporary port/resource failure. A rejected selected config must not loop
through packet engines, restarts, or wake requests. A malformed backup must
not prevent trying a valid subsequent backup or overwrite the original
route's transient retry policy.

Endpoint bootstrap regressions must preserve a compatible IP fallback when
the selected hostname's DNS fails, bound distinct physical-network DNS
answers to three, and preserve the complete transport template. A stale DNS
completion after Stop or network replacement must not start an obsolete core.
A physical-network DNS race is retryable recovery, not an Auto packet-engine
fallback. Disabling seamless failover must retain the single-route behavior.
Native batch scans must observe cancellation and queued VPN ownership between
completed calls, skip optional retries, and release the lease and tempfiles.
Never force-stop an in-flight JNI call to make VPN startup appear faster.

## Isolated Linux retained-flow diagnosis

This opt-in check uses the private current bundle and a separate loopback
Xray process. It does not restart the real server or test Android TUN/Doze.
It distinguishes real HTTP/2 connection reuse from a replacement socket and
keeps the same two-of-three-within-six-seconds response gate.
The diagnostic needs Go on the build machine, not the running server. Go is
not installed in every Codespace. Alternatively cross-compile this standalone
helper for Linux/amd64 on a machine with Go (`GOOS=linux GOARCH=amd64 CGO_ENABLED=0`)
and transfer that helper privately; it is not an app/server runtime dependency.

```bash
go build -o /tmp/g2ray-retained-http-probe tests/xray_retained_http_check.go
python3 tests/xray_authenticated_transport_check.py /usr/local/bin/xray \
  g2ray-codespace-bundle.json --live --transport xhttp \
  --retained-probe /tmp/g2ray-retained-http-probe --idle-seconds 70
```

Run `--transport ws` separately so an XHTTP failure does not hide WebSocket
results. Compare with the same host's direct baseline, without any proxy:

```bash
/tmp/g2ray-retained-http-probe --direct-baseline --idle-seconds 70
```

Isolate the running server's origin socket settings without using VLESS or the
public edge. The helper copies only its `direct` freedom outbound and DNS into
its own temporary core; it does not modify the server config:

```bash
python3 tests/xray_authenticated_transport_check.py /usr/local/bin/xray \
  --origin-config data/config.json --live \
  --retained-probe /tmp/g2ray-retained-http-probe --idle-seconds 70
```

Repeat with `--origin-disable-fast-open` as a one-factor experiment, and with
`--loopback-baseline` to isolate forwarding from Internet egress. For an
independent HTTP/2 client, replace `--retained-probe ... --idle-seconds ...`
with `--retained-curl` (requires curl with HTTP/2, `%{json}`, and `--rate`).
`--runtime-log-dir /private/test/logs` saves debug logs; never publish them.

Fast Open is off by default for both new configs and existing-config startup
migration. Kernel capability alone is not enough to enable it. Explicit opt-in
must pass retained-flow and transfer acceptance on the actual network path.

Check the generated server and its actual exported tuning in a separate,
loopback-only server/client pair before changing the live server:

```bash
python3 tests/xray_isolated_server_pair_check.py /usr/local/bin/xray \
  /tmp/g2ray-retained-http-probe --live --transport xhttp --idle-seconds 310
```

Repeat with `--transport ws` independently. Run from the repository root; the
check requires Bash, jq, OpenSSL, curl, and the compiled retained helper. Its
configuration preflight covers six actual profiles with WS on and off and
asserts policy values and listener presence, rather than trusting case labels.
The data test uses `low_latency` unless `--profile` selects another profile.
It preserves generated UUID, Host/SNI, path, ALPN, and XHTTP extra, changing
only the dial address/port to owned loopback listeners. A private fixture CA
models TLS termination with strict certificate verification; WS uses the
exported HTTP/1.1 variant. It does not model GitHub's buffering/proxy behavior,
Android TUN, the phone's ISP, Doze, or other WS ALPN variants. An interrupted
run is incomplete, not a pass; record the actual idle gap and connection IDs.

CI can exercise the same pair with only three owned loopback HTTPS origins:

```bash
python3 tests/xray_isolated_server_pair_check.py /usr/local/bin/xray \
  /tmp/g2ray-retained-http-probe --loopback-baseline --idle-seconds 0
```

This fixture adds a TCP routing exception only for its three selected
`127.0.0.1` origin ports; the generated private-IP block and other routing rules
remain intact. The pinned freedom outbound requires the same narrow exception
in its `finalRules`; other private destinations retain their default block.
Neither exception is used for Internet pair tests or written to the live
config. A zero-idle CI pass verifies startup and protocol plumbing, not idle
recovery; the separate 310-second acceptance remains required.

These commands contact real HTTPS endpoints. Do not publish the private bundle
or raw runtime configs. The [13 October investigation](../PERFORMANCE_INVESTIGATION_2026-10-13.md)
records failures as well as passes; a passing direct or SOCKS check cannot
certify the phone's mobile-data path, six-hour sessions, or video playback.

## Opt-in live device regressions

Save a working profile, grant VPN permission, and install both debug APKs
before starting. Do not replace APKs during instrumentation. These tests start
and stop the VPN and transfer real traffic; ordinary source CI skips them.

For a limited mobile-data budget, add `-e smallProbesOnly true`. This skips
the 2 MiB download / 64 KiB upload sample; it does not change the independent
HTTPS quorum, retained/fresh response deadline, lifecycle, or recovery gates.
HEV counters must still advance after verified upstream requests, but this
mode is not a throughput or transferred-byte accuracy check. The ordinary
transfer/counter checks remain enabled by default.

From an Android SDK terminal, run the idle test separately for each engine:

```powershell
adb shell am instrument -w -r -e class 'com.g2ray.client.vpn.LiveVpnDeviceTest#selectedProfileCarriesTrafficAndRecoversAfterBackgroundIdle' -e liveVpn true -e engine hev -e idleSeconds 310 com.g2ray.client.test/androidx.test.runner.AndroidJUnitRunner
```

Repeat with `-e engine xray_native`. An optional `-e transport ws` uses a
matching saved profile on the same account/edge without changing selection.
Record screen interactivity and the actual elapsed gap; requested idle alone
does not prove a screen-off test. Retained and fresh HTTPS each need two of
three endpoints within six seconds. Failed retained checks must remain visible
even when fresh sockets work.

Repeat with `-e resumeVpnActivity false` so the data requests resume while the
VPN activity remains stopped. This models returning to a different app rather
than opening the VPN UI and triggering its foreground re-verification.

Add `-e screenOff true -e resumeVpnActivity false` for controlled screen-off.
The harness sends the sleep key through
[UiAutomation.executeShellCommand](https://developer.android.com/reference/android/app/UiAutomation#executeShellCommand(java.lang.String)),
closes its returned descriptor, and starts the idle timer only after the device
is noninteractive. It asserts screen-off throughout the elapsed interval.
Do not touch or wake the phone during this test. USB-powered standby is not
unplugged deep Doze; those are separate acceptance cases.

With the VPN stopped, check the real Home ping-all action independently:

```powershell
adb shell am instrument -w -r -e class 'com.g2ray.client.vpn.LiveVpnDeviceTest#selectedCodespacePingAllCompletesWithVisibleResults' -e livePing true com.g2ray.client.test/androidx.test.runner.AndroidJUnitRunner
```

This invokes the actual ViewModel method without an activity optimizer
starting a competing scan. Every config must enter testing and finish with
a persisted real delay or explicit error; selection must remain unchanged and
the native core must stop. A visible failure is a valid completed result, but
the test requires at least one working route. This is not a rendered UI-click
test. It uses small native health requests, not a sustained throughput benchmark.

Test the ordinary Start VPN path separately with the Codespace stopped:

```powershell
adb shell am instrument -w -r -e class 'com.g2ray.client.vpn.LiveVpnDeviceTest#selectedProfileStartsThroughUiAndSurvivesIdle' -e liveVpn true -e idleSeconds 60 com.g2ray.client.test/androidx.test.runner.AndroidJUnitRunner
```

This uses the selected profile and saved engine preference through the actual
ViewModel action. The service-only test above remains separate for restoration
and recovery coverage; passing the UI path must not hide a service-start failure.

With VPN stopped, `selectedAccountWakesOnPhysicalNetwork` and `-e liveWake true`
checks the saved wake backend directly. With a ready Codespace,
`selectedAccountControlPlaneWorksWhileVpnIsActive` and `-e liveVpn true` checks
the production protected socket and real recovery API while VPN is active.
The optional `-e wakeBackend WORKER` or `GITHUB_DIRECT` selects a test backend
without changing saved account settings. These opt-in calls can start a host.
No keys, raw profile links, or backend error bodies are printed.

Debug builds also log `startup_phase`, engine, elapsed milliseconds, and success
for underlying-network wait, endpoint resolution, native startup, HEV attach,
probe reset, and full-path verification, plus core lease/setup/native/listener
stages. Use those timings before changing
timeouts or attributing slow startup to a component. Release builds do not emit
these phase records, and the records include no endpoints, URIs, or credentials.
If device logging suppresses the tag, temporarily enable only
`log.tag.G2RayVpnService=I` and restore its previous value after capture; do not
change global logging or infer stage durations from missing records.

Run core-failure recovery independently, again with both engines:

```powershell
adb shell am instrument -w -r -e class 'com.g2ray.client.vpn.LiveVpnDeviceTest#selectedProfileRecoversAfterRepeatedCoreFailures' -e liveVpn true -e liveCoreRecovery true -e engine hev com.g2ray.client.test/androidx.test.runner.AndroidJUnitRunner
```

This stops the actual native core twice and requires automatic verified
recovery plus an HTTPS quorum after each stop. It does not simulate every
persistent-backoff branch, a stopped Codespace, or a failed Worker. Normal
automatic standby routes remain enabled unless explicitly disabled for a
separate controlled experiment. Never label a selected-transport result as
forced single-outbound proof.

## Six-hour soak

1. Start with the Codespace stopped and press Start VPN once with **Worker** mode and once with **GitHub** mode. Record time until route verification succeeds and confirm both paths use the same selected profile.
2. Stream 1080p video for 60 minutes.
3. Leave the phone idle with the screen off for 30 minutes, then browse immediately.
4. Switch Wi-Fi to mobile data and back while the VPN is connected. Confirm a
   fresh end-to-end VPN data-path quorum succeeds on each network and no stale
   dialer-binding error tears down an otherwise working tunnel.
5. Upload and download a file larger than 1 GB.
6. Stop the Codespace during the session. Confirm the app enters recovery, wakes it through the Worker, and reconnects without losing the selected profile. Repeat with Worker unavailable and **Auto** mode; the direct GitHub fallback must be attempted only after a Worker control-plane failure.
7. Test process death separately from Android's user-requested Force stop.
   For process death, verify sticky-service restoration preserves the selected
   profile and re-verifies the data path. For Force stop, do not expect automatic
   resurrection: reopen the app explicitly and verify a user-requested Start VPN
   succeeds without stale Connected state.

Record every disconnect, recovery duration, browser DNS failure, video stall, and false Connected state. A production candidate has zero false Connected states and recovers bounded external failures without manual panel access.

## DNS and address-family compatibility

Run each case with both **HEV** and **Native** engines:

1. With **Fast DNS** off, open several uncached sites and confirm DNS stays
   inside the tunnel.
2. Enable **Fast DNS**, reconnect, repeat the uncached-site test, and record
   time to first content. Test TLS, plain HTTP, and an app using raw TCP.
3. Stop the VPN after Fast DNS use. Confirm normal mobile networking and DNS
   recover immediately; if an app retains a `240.0.0.0/12` answer, leave the
   feature off for release on that Android version.
4. With IPv4-only compatibility off, verify both IPv4 and IPv6 test endpoints.
5. Enable IPv4-only compatibility and reconnect. Confirm IPv4 works, IPv6 is
   blocked rather than bypassing the VPN, and a broken-IPv6 test app falls back
   promptly to IPv4.

Keep both DNS/address-family switches off by default until these cases pass on the target Android
versions and OEM devices.

## DPI compatibility fallback

1. Leave **DPI compatibility** off and confirm the normal XHTTP profile keeps
   its `chrome` fingerprint and has no `finalmask` in the generated config.
2. On a network where that same route is blocked by SNI/DPI, enable DPI
   compatibility, reconnect, and verify the generated config uses `unsafe` plus
   the two TCP fragment masks.
3. Compare connection setup and sustained transfer with the switch off and on.
   Keep it off when it does not restore a blocked route; it is not a throughput
   optimization.
4. Import a link carrying percent-encoded `fm` and `cs` parameters. Confirm the
   generated config preserves them and rejects malformed FinalMask JSON before
   attempting VPN startup.

## Automatic route optimization

1. Stop the VPN with a ready Codespace, enable **Auto-optimize routes**, and
   reopen the app. Confirm one bounded scan starts after the short foreground
   delay and no second scan starts within 30 minutes.
2. Confirm every newly added IP config preserves UUID, SNI, Host, transport,
   path, ALPN, fingerprint, FinalMask, and cipher-suite settings from its source
   link. Only the dial IP and display name may change.
3. Make one DNS-advertised candidate unreachable. It must not be saved. Make
   every candidate unreachable or stop the Codespace; the scan must preserve
   all existing delay health and selection.
4. Run **Optimize** manually with two working routes whose delays differ by less
   than 10 percent/25 ms. The current route must remain selected. A materially
   faster verified route may become active.
5. Start a Quick scan or Optimize and immediately press Start VPN. Startup must
   cancel the scan, drain its bounded native call, and then connect without
   racing the process-global libXray instance. Repeat with Android Always-on.
6. Confirm **Ping all** returns one visible result per config. Block the first
   204 endpoint for one route and verify only that failed entry is retried and
   the button completes instead of remaining stuck.
7. Confirm automatic optimization transfer-tests at most the current route and
   one challenger; manual Optimize tests at most three. A route with unknown
   throughput must not beat a transfer-tested route merely because the unknown
   sample contributes no cost.
8. With **Seamless route failover** on and three recently measured compatible
   IP configs, connect and verify the packaged Xray config starts. Interrupt the
   preferred edge, open a new browser connection, and confirm Xray chooses a
   backup without rebuilding the Android VPN interface. Then stop the entire
   Codespace and confirm the outer wake/recovery path still runs.

## XHTTP import and recovery races

1. Import a direct server VLESS link containing percent-encoded `extra`.
   Confirm import succeeds and both Quick scan and VPN startup use those
   settings. Repeat with HEV and Native. Import an untuned link and confirm it
   remains untuned; the legacy Android bundle intentionally omits `extra`.
2. Confirm malformed, oversized, non-object, and trailing JSON are rejected
   before core startup. Generated edge variants must retain the original
   `extra`; differently tuned profiles must not enter one seamless balancer.
3. Resume an idle app while a periodic health poll is due, then repeat during
   a Wi-Fi/mobile handoff. Confirm no duplicate probe reset causes a false
   recovery, and an old-network failure cannot stop the new working route.
4. Stop/reconnect while traffic numbers update. Connection state must not
   revert to Connected because of a late sample, and an unverified or Native
   tunnel must not display old HEV measurements as new traffic.

Automated tests cover the configuration contract and deterministic race
interleavings. These cases still require packaged-core and physical-device
acceptance; they are not declared passed merely because unit tests pass.

## Fresh checkout without the core AAR

1. In an isolated source checkout, leave the checked-in `libXray.properties`
   present and omit the ignored `app/libs/libXray.aar`. Run unit tests, lint,
   `verifyHevBundle`, and `verifyLibXrayBuildContract`; they must succeed.
2. Run `verifyBundledCore` in that checkout. It must fail clearly because the
   AAR is missing. APK packaging must retain its dependency on this gate.
3. Supply the matching AAR and rerun `verifyBundledCore`. Its checksum, patched
   controller contract, and Java API verification must all succeed.

Coreless tests do not prove VPN functionality and must never be reported as a
packaged-core smoke test.

## Always-on and lockdown

Run this section on Android 8.1 or newer after saving a working profile.

1. Enable **Always-on VPN**, reboot the phone, and verify Android starts the service without opening the app. The saved profile must reconnect and pass route verification.
2. Enable **Block connections without VPN**, stop the Codespace, and reconnect. Worker and direct-GitHub control-plane calls must still reach their services while browsers remain blocked until the tunnel is verified.
3. Repeat the screen-off, Wi-Fi/mobile handoff, app force-stop, and Codespace-wake cases with lockdown enabled.
4. Verify the G2Ray app itself has normal network access after connection and that its control-plane requests do not loop through the tunnel.
5. Remove or invalidate the saved profile. Always-on startup must show a clear configuration error rather than a false Connected state.

Do not advertise always-on/lockdown as production-tested until every case passes on at least one Google Android device and one target OEM device.

## Direct GitHub control-plane checks

- Verify a fine-grained token with **Codespaces: read** and **Codespaces lifecycle: write** can read an `Available` Codespace without issuing a start request.
- Verify a `Shutdown` Codespace receives exactly one start request, then is polled until `Available`.
- Verify invalid token, missing permission, quota (`402`), missing Codespace (`404`), and API rate-limit responses are shown as configuration/service errors rather than route failures.
- Verify `Available` is not displayed as a healthy tunnel until the selected VLESS profile passes the real route probe.
- Disable or remove the Worker and confirm **GitHub** mode still wakes the Codespace; restore the Worker and confirm **Auto** prefers it.
- Return a rate-limited GitHub 403/429 with a future reset or retry delay.
  Desired-connected intent must stay enabled, and automatic retry must not run
  before the later server/local boundary. Repeat through the Worker with both
  header and body/reset hints, and with a hinted 503 followed by direct-backend
  failure. Explicit Stop during that wait must stop future attempts.
- Return a permission 403, invalid-request 422, wrong Codespace identity,
  missing dashboard token binding, or deleted host state. The app must show a
  configuration/service error rather than an endless automatic retry. Repeat
  while the underlying network disappears or changes.
- Return a long retry hint, then let a Worker alias or direct fallback fail or
  hang. The remaining wait must consume elapsed time and survive every enclosing
  deadline; it must not restart from the original response. Explicit Stop must
  cancel the wait and any in-flight fallback. Repeat after sleep and network loss.
- Return `wake_recently_succeeded` with a long start-suppression delay. Health
  polling and local route recovery must remain possible; this is not a global
  API-rate cooldown. Repeat with a real GitHub rate-limit response to verify
  that its mandatory delay is still honored.
- Reject malformed Worker authorities and credentials in a URL before network
  access. Canonicalize equivalent host/scheme/default-port spellings. A bad
  primary must not discard an explicitly configured valid backup.

## Pinned server core acceptance

Verify the installed/running core, not only the Dockerfile or repository pin.
A Git pull does not replace an already installed binary.

```bash
python3 tests/xray_upload_queue_validation.py /path/to/verified/xray
bash tests/g2ray_config_validation.sh /path/to/verified/xray
python3 tests/xray_authenticated_transport_check.py /usr/local/bin/xray g2ray-codespace-bundle.json --live
```

Run from the server repository root. The first test binds loopback only and
requires a saturated incomplete upload session to release its pending POST at
expiry. The last uses private current links and performs real authenticated
Internet requests; do not publish the bundle. Preserve UUID/config and an old
binary rollback copy during any approved upgrade. Passing these checks does
not close phone idle-resume acceptance or the aggregate resource-budget gap.

## v2rayNG A/B comparison

For each client, run three alternating 15-minute rounds with the same config and physical network. Record median and p95 for:

- time to first successful web page;
- real-delay probe;
- download and upload throughput;
- 1080p video rebuffer count;
- idle-resume delay after 15 minutes;
- packet loss from the same test endpoint.

Do not claim one client is faster unless repeated medians and p95 values show it. Server-side route probes cannot substitute for measurements from the phone's ISP path.

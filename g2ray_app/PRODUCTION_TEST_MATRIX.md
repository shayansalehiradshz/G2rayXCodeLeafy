# Production Candidate Test Matrix

Run these checks on the same physical Android phone and ISP used for daily traffic. Use the same VLESS URI for this app and v2rayNG; otherwise the comparison is not valid.

The [17 October physical-device record](DEVICE_ACCEPTANCE_2026-10-17.md) identifies
the installed build, completed checks, interrupted attempts, and remaining
acceptance. Its small HTTPS probes do not replace the broader tests below.
The [extended 17 October record](EXTENDED_DEVICE_ACCEPTANCE_2026-10-17.md) adds
bounded transfers, loaded responsiveness, real LTE-outage recovery, three-minute
YouTube samples, short real AI-app exchanges, and Native screen-off/forced Doze.
It also retains the incomplete and failed harness observations and their limits.

## Automated gates

```powershell
./gradlew.bat testDebugUnitTest assembleFunctionalDebug lintDebug
./gradlew.bat -p buildSrc test
./gradlew.bat connectedDebugAndroidTest
./gradlew.bat bundleRelease
```

`assembleDebug` and `assembleFunctionalDebug` must verify the bundled libXray
hash and supported Java API, every HEV ABI, and the pinned native-build
toolchain contract. The device test must report that both libXray and HEV load
on the phone's real ABI.
`bundleRelease` must run the same libXray patch, AAR hash, HEV ABI/hash, and
pinned-builder checks before signing.
Release packaging must also run `verifyReleasePersistenceSchema` against the
actual R8 mapping, kept-member report, and removed-member report. The five
persisted Moshi models and their three enums must
retain their names, constructors, fields, and enum constants. A mapping with
renamed or removed persisted fields must fail the build. Keep this check and
its contract synchronized when adding a new persisted model or field.
The mapping gate verifies schema preservation, not on-device deserialization.
For release acceptance, install a correctly signed release over a previous
release made with the same signing key, preserve existing app data, and check
accounts, wake keys/modes, profiles, selected account/profile, and route history
after process death and relaunch. Never replace a user's installed app with a
disposable-key verification APK or uninstall it to make signing succeed.
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
Single-profile probes must make the same checks inside port retries and before
another endpoint. Summary, quick-scan fallback, and direct-start callers must
forward cancellation/network checks through every completed native call.
Never force-stop an in-flight JNI call to make VPN startup appear faster.

If no compatible live group resolves, startup must return a retryable
EndpointResolution result so ordinary unmeasured saved IP profiles remain
reachable. Scan at most six backup candidates; skip DNS-only/config-rejection
failures without increasing the one actual route attempt before wake. Preserve
ranking for that single attempt. A physical-network race must return before
another packet engine, backup, or Worker/GitHub wake is attempted.

Both full-VPN and SOCKS health checks must consume complete small successful
responses within the existing call/quorum deadlines. Truncation, early EOF,
and bodies over 64 KiB (including unknown-length bodies) must fail; complete
small bodies and empty HTTP 204 must pass. These probes establish endpoint
health, not complete Google pages, AI streams, or video playback.
The full-VPN check must also wait until Android selects VPN default routing,
including that wait in its unchanged 4.5-second budget. Physical/unknown
default routing must send no health requests or produce verified success.
Losing VPN default routing before the check completes must reject its result.
This routing requirement applies to debug and release builds.
Owned loopback responses that stall after success headers must honor read
deadlines and cancellation. Complete known/unknown-length responses at the
64 KiB boundary and empty unknown-length responses must remain valid.

Successful startup, recovery, and Native startup-confirmation results must
carry the physical-network generation they tested. Recheck it atomically with
network callbacks at final verified-state publication, including while the
result waits in the main queue. Reject an obsolete result and verify the new
path without restarting a healthy core. A fresh successful handoff must also
verify an already unverified connected core. Do not attribute an old network's
late socket-bind/protection error to its replacement or let it mask a real
current failure. Reject dialer/control-plane binds that finish across handoff.
Publishing a replacement network must not clear its first genuine dialer error.
Descriptor ordering fixtures simulate binding; real descriptor protection and
duplication still require packaged-core/device acceptance.

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

The retained helper reports `ttfb_ms` from the first response-byte trace event,
separately from `elapsed_ms`, which includes reading and validating the complete
bounded body. Missing first-byte observations are omitted, not reported as a
fake zero. TTFB is not page-completion time or an AI model's time to first token.

The owned loopback fixture also completes an HTTP/2 POST body, waits seven
seconds before the first SSE event, and waits another seven seconds before the
final event. Both events must arrive. This runs through each generated
XHTTP/WebSocket server/client pair in CI, adding about 14 seconds per pair.
It guards against treating an HTTP/2 request END_STREAM as TCP upload EOF;
it does not test genuine TCP half-close behavior, a real AI provider, GitHub's
edge, Android lifecycle, or media playback.

The `--live` variants contact Internet HTTPS endpoints; `--loopback-baseline`
uses owned local origins instead. Do not publish the private bundle or raw
runtime configs. The [13 October investigation](../PERFORMANCE_INVESTIGATION_2026-10-13.md)
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
Accepted engine arguments are exactly `auto`, `hev`, and `xray_native`;
an unknown value must fail rather than silently testing Auto. The separate
`explicitEngineArgumentsCannotSilentlyExerciseAnotherEngine` regression checks
this contract without contacting the network. Record the engine printed by
the run, not just the intended command label.
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

Before attributing an improvement to adaptive learning, run the
[network-provenance acceptance](../NETWORK_AWARE_ROUTE_LEARNING_2026-10-14.md).
Old or different-network measurements must remain historical, not current
latency/throughput or verified standby evidence. Test both ordinary handoff and
switch-away-and-back during ping-all; a mixed-path run must not change saved
health or selection. Confirm callback cleanup and legacy-data preservation.
These source fixtures do not replace actual physical-network acceptance.

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

## Saved settings failure and recovery

Exercise read/open/write failures with the injected unit-test stores. Do not
delete a real Keystore alias, truncate encrypted preferences, or clear user
data to manufacture these failures on a phone.

1. Fail the secure-store open or a VPN-preference read during app startup.
   The UI must show an unavailable-settings state instead of crashing. It
   must not save presentation defaults, start a tunnel, or launch probes with
   unknown settings. The original encryption scheme/file/key alias must stay
   unchanged; no plaintext fallback or automatic reset is allowed.
2. Fail a legacy migration read/write and shared-Worker migration write.
   Preserve the original records, report failure, and reject ordinary writes
   until a full reload succeeds. Retry must retain profile/account/key IDs
   after a confirmed migration, without duplicating records on replay.
3. Fail parsing after an earlier JSON field has parsed successfully. A reload
   must retain the complete last-good in-memory snapshot, not publish a
   mixture of old and partially loaded records. Do not overwrite malformed
   source data or include its contents in error messages.
4. Import a valid legacy profile with no Worker or wake key. It must migrate
   without inventing a blank wake key. An unsupported legacy profile must be
   preserved and copyable; its advisory must not block manual profile import.
5. Simulate a commit that changes memory but fails its acknowledgement, then
   repeat with a failed rollback. Restore only touched keys and block affected
   reads/all new writes while restoration is unconfirmed. Unrelated VPN
   settings must remain readable after an optional scan-history write fails.
   A checked storage exception must also roll back the repository's published
   snapshot. Service profile/intent reads and writes must use the Application's
   same guarded store, not construct a fresh instance to bypass restoration.
6. Repeat Reload while a reload is in flight. It must not create a spurious
   preferences error. Completion requires the public ready/error state to
   settle, not merely a finished job or an idle spinner.
7. With a previously verified tunnel running, fail a new UI preference read.
   The UI must not invalidate or stop that tunnel. Stop must remain usable.
   Reloading settings requires stopping the VPN/finishing active operations;
   no background reload may rewrite the active session's configuration.

Unit tests verify simulated failures, default Application/ViewModel wiring,
and Stop-intent dispatch. They do not prove physical Keystore fault recovery,
rendered UI behavior, actual native shutdown, or real device fault tolerance.
Repeat normal upgrade/relaunch, legacy-config copying, and Stop/reload on the
target phone without destructive fault injection before release acceptance.

## Multi-Codespace import isolation

1. Save two different Codespaces with distinct wake credentials. Keep the first
   active, open its import screen, choose the second as the import destination,
   and import only the second's configs. Settings must show the second's saved
   list; Home and existing credentials must retain the first's active selection.
2. Select configs from both Codespaces for a single import. The operation must
   reject the whole selected batch, show its error beside the import action, and
   preserve existing profiles and wake keys. Uncheck the nonmatching configs and
   retry successfully without re-pasting everything.
3. Try a name that contains the target as a prefix, a lookalike DNS suffix, and
   conflicting canonical SNI/Host identities. None may pass as the target.
4. Import a trusted custom-front config with no GitHub origin identity. Verify
   confirmation names the chosen destination, Cancel writes nothing, and Import
   retains that explicit binding. Confirmation must not bypass a known wrong
   Codespace identity. This does not prove custom-front network compatibility.
5. Change the destination after an import error, double-tap Import, and leave the
   screen during a delayed storage operation. Do not duplicate imports, change
   the active VPN account, or let a late callback pop an unrelated screen.
6. In a test Worker with a second dashboard slot but no `GITHUB_TOKEN_2`, both
   health and wake must return `missing_github_token_binding` without contacting
   GitHub using the primary token. Restore the assigned token and verify only
   that token is used. Do not modify a live Worker's credentials for this test.
7. Import a canonical link for Codespace A, then edit its account to Codespace B.
   Manual and system startup must retain capture and reject the stale link before
   starting an engine or caching the session. Matching B links and explicitly
   bound custom fronts must still work. Old links must remain saved without
   crowding valid backups out of the attempt budget or crossing account boundaries.

Source tests cover association and atomicity. Picker layout, confirmation, and
navigation still require physical-device acceptance; compilation is not that
acceptance.

## Always-on and lockdown

Run this section on Android 8.1 or newer after saving a working profile.

1. Enable **Always-on VPN**, reboot the phone, and verify Android starts the service without opening the app. The saved profile must reconnect and pass route verification.
2. Enable **Block connections without VPN**, stop the Codespace, and reconnect. Worker and direct-GitHub control-plane calls must still reach their services while browsers remain blocked until the tunnel is verified.
3. Repeat the screen-off, Wi-Fi/mobile handoff, app force-stop, and Codespace-wake cases with lockdown enabled.
4. Verify the G2Ray app itself has normal network access after connection and that its control-plane requests do not loop through the tunnel.
5. Remove or invalidate the saved profile. Always-on startup must show a clear configuration error rather than a false Connected state.
6. Stop a normal, non-always-on session, leaving its saved profile selected. Enable always-on in Android settings without starting G2Ray manually. Android's `android.net.VpnService` start must restore that selection, even though the old session cache was cleared.
7. On Android 10+, an in-app Stop command must not discard the always-on session. The Home action opens Android VPN settings; disabling always-on there restores normal manual Stop behavior. On older Android versions, verify the OEM's Stop behavior separately because the service cannot query `isAlwaysOn` or `isLockdownEnabled`.
8. With blocking enabled, capture traffic on a controlled access point before startup, during core failure, after process death, through network handoff, and after reboot. No test-site IPv4/IPv6, DNS, or WebRTC/STUN packets may reach the physical network directly. Distinguish the VPN provider's outer connection and deliberate GitHub/Worker/bootstrap control-plane traffic from application bypasses. Repeat with both engines and both transports.
9. On an IPv6-capable connection, repeat with IPv4-only compatibility both off and on. Unrouted IPv6 must remain blocked, not escape. A resolver or browser leak-test result alone is not sufficient to prove crash/boot-time containment.

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

## Warm-start timing isolation

With a saved working profile, VPN permission granted, and the Codespace already
available, run a small-response startup-only check:

```bash
adb shell am instrument -w -r -e liveVpn true -e engine xray_native -e transport xhttp -e smallProbesOnly true -e class com.g2ray.client.vpn.LiveVpnDeviceTest#selectedProfileStartsWithSmallProbes com.g2ray.client.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -v threadtime -s G2RayVpnService:I G2RayVpnProbe:I '*:S'
```

Repeat with `hev`, and then the saved matching `ws` profile. Require a terminal
instrumentation result, the actual requested engine, verified default-VPN
startup, and the unchanged complete-HTTPS quorum. This test explicitly stops
after startup checks and does not certify idle survival or cold-host wake.
It refuses to run without `smallProbesOnly=true`.

Debug builds record bounded per-request DNS, connect, TLS, and response-header
event times, DNS address-family counts (never addresses), the attempted
connection family, and default-VPN status at the start/end of the probe. Event
callbacks capture timing only; diagnostic logging occurs outside those
callbacks. A pending or truncated trace is labeled, not treated as completed.
Connect time contains TLS time; do not sum nested intervals as independent
latency. No credentials, complete URLs, addresses, or exception messages enter
the timing record. The terminal `call_cancelled` flag distinguishes cancellation
from an uncancelled failure; it does not by itself identify the cancellation
reason. The quorum intentionally cancels remaining requests after it decides.
Release builds do not attach this timing listener.
If the phone suppresses the new tag, record its old property value and
temporarily enable capture using `adb shell setprop log.tag.G2RayVpnProbe I`.
Restore the previous value afterwards; for an originally empty value use
`adb shell 'setprop log.tag.G2RayVpnProbe ""'`. An empty capture is not timing
evidence: require actual per-request records or the no-network packaged
factory regression `debugDataPathClientAttachesPerRequestTimingWithoutNetworkAccess`.

## Bounded full-device transfers

Obtain a data budget first. With a selected working profile and VPN permission,
run this opt-in check once per intended engine/transport combination:

```bash
adb shell am instrument -w -r -e liveVpn true -e liveTransfers true -e engine xray_native -e transport xhttp -e class com.g2ray.client.vpn.LiveVpnDeviceTest#selectedProfileCarriesBoundedTransferSamples com.g2ray.client.test/androidx.test.runner.AndroidJUnitRunner
```

Each invocation requests exactly 8 MiB down and 2 MiB up through Android's
default VPN, plus small independent HTTPS checks before/after. It stops rather
than automatically retrying or following a redirect. The body reader rejects
truncation and stops on an oversized response; its packaged no-network
regression is `transferReaderRejectsTruncatedAndOversizedBodies`. A failed
invocation still consumes data and must count toward the budget. Preserve
considerable headroom for TLS/tunnel framing, retransmissions, DNS, health
checks, and other phone applications; payload bytes are not carrier billing.

Require a terminal result, exact byte counts, actual engine/default-VPN proof,
and post-transfer HTTPS responsiveness. Compute the whole-request sample rate
as `bytes * 8 / elapsed_ms / 1000` Mbps. It includes request setup and is not a
long-duration throughput ceiling. These checks do not establish actual YouTube
buffering, Instagram feed behavior, packet loss, or superiority to another
client. Test those separately within the remaining budget.

For concurrent loaded latency, use the same explicit `liveVpn=true` selection
with `liveLoadedTransfers=true`, `smallProbesOnly=true`, and
`LiveVpnDeviceTest#selectedProfileKeepsResponsiveDuringConcurrentTransfers`.
The stricter harness records `request_start_ms` / `request_end_ms` and requires
`overlapped_probes > 0`: at least one complete responsive HTTPS probe must fall
inside a completed bulk request's interval. A running coroutine alone cannot
prove that. This establishes request-lifetime overlap, not simultaneous payload
bytes or saturation. Repeat the candidate on-device; earlier records do not
retroactively pass this new gate. The pure boundary regression is
`LiveVpnDeviceTest#loadedOverlapRequiresACompleteProbeInsideAnActualTransferRequest`.
It transfers twelve 8 MiB downloads / 2 MiB uploads (120 MiB payload total), while
independent complete-body HTTPS quorums must stay within the existing six-second
response gate. Request times, completed byte counts, and the number of overlapping
checks are reported. This is not a full bufferbloat study or a guarantee of
latency under every competing workload.

Only with explicit permission for a temporary mobile-data interruption, use
`liveNetworkRecovery=true`, `liveVpn=true`, `smallProbesOnly=true`, and
`LiveVpnDeviceTest#selectedProfileRecoversAfterMobileDataOutage`. It requires
Android 12+, originally enabled data, and LTE as the only Internet connection.
An on-device shell command disables data for eight seconds, then re-enables it; a separate
non-cancellable cleanup also restores data. The test must observe genuine
physical-network loss, invalidation of route verification, automatic restoration
without Start VPN, and a fresh HTTPS quorum. Keep a host-side `finally` that
runs `adb shell svc data enable` as an additional guard. This tests loss/return
of LTE, not a Wi-Fi/LTE handoff. Never silently enable a radio that was originally
disabled or change another network to satisfy its preconditions.
The return event records monotonic `clock_ms`; the separate tunnel-verified
event precedes fresh HTTPS requests. Compare the first complete responsive
request interval with that return clock, and report full-quorum completion
separately. Do not treat the combined three-request time as state recovery alone.
The restoration script is sent to `sh` through `executeShellCommandRw` stdin;
`executeShellCommand` does not interpret chained shell syntax. The independent
`deviceShellScriptExecutesMultipleCommandsThroughStdin` regression validates
this path without changing a radio or accessing the Internet.

An Android 10+ opt-in YouTube observation is available with `liveVideo=true`,
`smallProbesOnly=true`, and an explicit public `videoUrl`/`videoTitle`, using
`selectedProfilePlaysBoundedYoutubeSample`. Unlock the phone first. It observes
the real YouTube app's matching local media session for at most 60 seconds,
stops at a 12 MiB device-mobile-counter threshold, and force-stops only the
launched YouTube app during cleanup to end prefetching. Shell media-session
permission is temporary and dropped afterwards. The counter includes other
applications, may be cached, and can overshoot while the player is stopping;
reserve substantial extra budget. It refuses unavailable/reset counters.

Acceptance requires at least 15 seconds of adjacent observations of local
Playing state and no reported post-start buffering transition. Media-session
position snapshots and any unobserved time gaps do not establish decoded
frames or uninterrupted playback. A budget-limited run that cannot complete
the observation is not a pass. No video resolution, app settings, account
data, or lock policy is changed. Repeat controlled visual/video acceptance
separately before claiming smooth high-resolution or long-session playback.

With an explicitly larger data allowance, `-e videoDurationSeconds 180
-e videoBudgetMiB 256` extends that same test to three minutes with a 256 MiB
phone-wide mobile-counter stop. Supported durations are 60-600 seconds and
budgets are 12-2048 MiB; invalid values fail instead of silently changing the
test. Defaults remain 60 seconds / 12 MiB / 15 observed Playing seconds.
Extended runs require at least duration minus 30 seconds of observed Playing,
and still reject any reported post-start buffering transition. The packaged
`youtubeLimitsKeepBudgetDefaultsAndRequireLongerPlaybackForExtendedRuns` test
checks these limits without Internet traffic. Larger allowances do not turn
media-session telemetry into decoded-frame, resolution, or long-session proof.

## US Exit and Capture Retention

The desired country is US; no wrong-country incident was reported in this review.
On each selected Codespace compare option 12 / `--exit-location --json` with
browser observations through the VPN. Record the IP and country per provider,
separately for IPv4/IPv6 when available. Different country labels for the same IP
must not be called a phone-IP leak. Never use an ingress edge address, DNS/CDN
address, browser language or account region as proof of the source exit country.

From an independent application, observe the physical network during startup,
wake, terminal configuration failure, native/HEV repair, retry, and deliberate
disconnect. While capture is retained, ordinary traffic must forward via the
configured VPN or fail, never fall back to the physical exit. A Blocked state
must offer Retry and explicit disconnect; editing/reloading saved settings must
not release the guard. Test Stop/revoke/destruction during startup/replacement
and both unchanged and changed IPv4-only settings. Source/pipe-descriptor tests
do not establish real Android routing or native descriptor ownership.

Repeat with Always-on + Block connections without VPN through process death and
reboot. Without that OS policy, no app guard survives process death. Verify wake
and physical bootstrap remain usable, and distinguish those authorized control
connections from application browsing. No physical-device pass is claimed yet
for the capture-retention revision.

While Blocked, manually wake the selected Codespace via Worker and direct
GitHub separately. The retained capture must keep the physical-network callback
and protected control-plane binding, even if profile/preference validation
failed before core startup. Losing/replacing that network must update the wake
binding without publishing Starting, starting an implicit retry, or releasing
capture. Explicit Stop/revoke must still unregister the callback and close TUN.

Exercise service replacement while the retired instance's cleanup is delayed.
Its eventual completion must not clear the replacement's socket binding,
notification, OS policy status or connection state. The source fixtures cover
both Android 7 and Android 12; real process death/system restart remains a
separate device gate. A successful manual wake alone must not claim that the
VPN has started or that the full data path is verified.

While Blocked, both successful and failed manual wakes must preserve the
service warning, unverified state, and routeReady=false until a real VPN retry.
Home must retain the OS-reported blocking label while holding that guard;
the label must not equate in-process capture with Android lockdown.

During an outstanding periodic probe, replace or lose the physical network.
The obsolete request must cancel and release lifecycle ownership promptly,
and periodic monitoring must resume after a successful handoff. A callback
for the same network must not cancel a valid probe. A stopped core can retry
early when a usable replacement arrives, including before its retry coroutine
starts, but never before a mandatory Retry-After. A running core must first
get a fresh route proof instead of an unnecessary restart.

## v2rayNG A/B comparison

For the browsing-first route-selection revision, also follow
[the responsiveness review](RESPONSIVENESS_REVIEW_2026-10-17.md). Confirm that
the optimizer uses repeated finalist samples, does not replace a stable route
with a one-off quick winner, discards a mid-scan network change, and is cancelled
by Start VPN. Ping All remains a quick scan, not a sustained-performance test.
While Blocked, optional native ping tests must ask for Stop; protected manual
wake must still work. Record real page/feed/video/AI-stream timings separately.

For each client, run three alternating 15-minute rounds with the same config and physical network. Record median and p95 for:

- time to first successful web page;
- real-delay probe;
- download and upload throughput;
- 1080p video rebuffer count;
- idle-resume delay after 15 minutes;
- packet loss from the same test endpoint.

Do not claim one client is faster unless repeated medians and p95 values show it. Server-side route probes cannot substitute for measurements from the phone's ISP path.

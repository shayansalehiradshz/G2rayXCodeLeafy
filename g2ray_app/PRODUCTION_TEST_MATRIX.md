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
5. Start a Quick scan and immediately press Start VPN. Startup must wait rather
   than racing the process-global libXray ping instance.
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

## v2rayNG A/B comparison

For each client, run three alternating 15-minute rounds with the same config and physical network. Record median and p95 for:

- time to first successful web page;
- real-delay probe;
- download and upload throughput;
- 1080p video rebuffer count;
- idle-resume delay after 15 minutes;
- packet loss from the same test endpoint.

Do not claim one client is faster unless repeated medians and p95 values show it. Server-side route probes cannot substitute for measurements from the phone's ISP path.

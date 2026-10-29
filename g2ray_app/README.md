# G2Ray Client

This is the Android Studio project under `g2ray_app/` in the
`G2rayXCodeLeafy` repository or your fork. Open this directory as the project root.

An Android full-device VPN client for personal testing with G2Ray instances running on GitHub Codespaces.

The app can wake/check your Codespace through either the shared Worker or GitHub's
Codespaces API, import local VLESS subscriptions, and start an Android `VpnService`
backed by the official XTLS `libXray` Android wrapper.

## Usage

1. Open the app and navigate to **Settings**.
2. Add the exact GitHub **Codespace name**.
3. Choose a wake method per Codespace:
   - **Auto** (recommended): use the shared Worker first, then contact GitHub
     directly if the Worker is unreachable.
   - **Worker**: use only the shared Worker URL and wake key configured in the
     shared Worker screen.
   - **GitHub**: use no Worker. Paste a GitHub token with Codespaces read and
     Codespaces lifecycle admin write permission.
4. Import your G2ray VLESS config by pasting the `vless://...` URI.
5. Keep **Settings > Connection settings > Engine** on **Auto** unless you are
   comparing engines, then return to Home.
6. Tap **Start VPN**. The app probes the selected route before waking the
   Codespace, so an already-working route does not wait on a control plane.
7. Approve Android's VPN permission prompt.

Connection options and their information buttons are under **Settings > Connection
settings**. Each config's **Share** button offers Copy link and an offline QR.
The import screen also scans individual or all-config QRs. See the
[daily-use workflows and options](WORKFLOWS_AND_OPTIONS_2026-10-18.md) for account
switching, local usage estimates, Quick Settings, bypass routing, and protocol
tradeoffs.

Wake setup is optional for an imported profile whose server is already running.
Start VPN tries that profile directly even when no Worker or GitHub token is
configured. Automatic server wake requires a configured wake method only when
the server actually needs recovery.

### Importing multiple Codespaces

The import screen has its own Codespace destination selector. Choosing a target
there does not switch the active VPN account or move its credentials. Import
only the selected destination's configs; a mixed selection is rejected without
partial writes, and the error stays beside the import action.

Canonical `app.github.dev` SNI/Host values must identify the exact Codespace, not
merely contain its name. The hostname identifies a Codespace, not its GitHub
owner. Custom fronts with no recognizable GitHub origin require explicit
binding confirmation. That confirmation cannot override an identified,
different Codespace. After a successful import, Settings shows the destination's
profile list; Home keeps its previous active selection until you change it.

Changing an account's Codespace name does not rewrite its existing VLESS links.
Startup and automatic backup selection recheck recognized Codespace identities
against the account's current name. A conflicting selected profile remains
Blocked with a configuration error. Import or select a matching config, then
use Retry VPN. Old links remain saved for repair; explicitly bound custom
fronts without a recognized origin remain compatible, not authenticated.

### Blocking traffic outside the VPN

Start VPN hands connection ownership to the service immediately, without a
UI-side ping/wake preflight. Capture is installed before settings/native/wake
waits. Repairs retain the capture interface; terminal errors stop forwarding
and show **Blocked** with **Retry VPN** and **Disconnect VPN** controls. They
must not silently restore the phone's direct Internet exit. OS blocking remains
necessary if the process dies or Android cannot create/retain the interface.

Open **Settings > Connection settings > Android VPN protection**, select G2Ray in Android's VPN settings,
then enable **Always-on VPN** and **Block connections without VPN**. Both are
system-controlled settings: the app cannot silently enable them. Without OS
blocking, traffic may use the normal network after the VPN stops or its process
dies. On Android 10+, Home displays the OS-reported blocking state while the
service is running or retaining a Blocked guard; older versions show **Check Android settings**, not an
unverified protection claim.

A successful manual Codespace wake does not start VPN or verify its data path.
While Blocked, wake updates preserve the service warning until an actual VPN retry.
Network transitions cancel obsolete prompt and periodic health requests.
A surviving core is verified before replacement; a stopped core can retry
early on a usable new network without bypassing a server Retry-After.

Android's always-on start restores the currently selected saved profile, including
after a normal app Stop cleared the previous session cache. On Android 10+, the
app defers an always-on Stop request to the system settings rather than closing
the interface behind Android's policy. Change or disable always-on in those
settings when you intentionally want to disconnect.

Full-device routing is different from being undetectable: a datacenter exit,
accounts, cookies, browser characteristics, and granted location permissions can
still identify you or suggest VPN use. See the [leak-protection review and
acceptance checklist](../IP_LEAK_PROTECTION_2026-10-16.md) for Android, Windows,
WebRTC, IPv6, and failure-case checks. These checks have not yet been accepted on
your phone with OS blocking enabled.

### Direct GitHub wake

Direct mode uses GitHub's authenticated Codespaces REST API from the phone. A
fine-grained token should be limited to the account/repositories you need and
grant **Codespaces: read** plus **Codespaces lifecycle: write**. A classic token
with the `codespace` scope is also accepted by GitHub. The token is stored in the
app's encrypted preferences and is never sent to the Worker.

The direct API confirms the Codespace lifecycle state, then the VPN verifies the
actual forwarded VLESS route. `Available` from GitHub alone is not treated as a
healthy tunnel; route settling, port exposure, and Xray startup can still take
time. Direct mode is a control-plane fallback, not a way to bypass GitHub quota
or Codespaces routing.

OAuth Device Flow is intentionally not required by this app. It would require a
separately registered GitHub OAuth app and client ID, which would make setup less
portable rather than simpler.

## VPN Engine

- **Auto** is the default. It tries Xray's native TUN path first to avoid the
  extra HEV userspace forwarding hop, verifies the full route, and falls back
  once to HEV if the native packet path cannot become healthy.
- **HEV** keeps the choice explicit for testing. It uses loopback SOCKS between
  HEV and Xray and exposes true VPN TUN upload/download counters.
- **Native** uses Xray's Android TUN inbound directly. It is the default Auto
  data path. When Android exposes per-UID counters, the app shows an explicitly
  approximate rate for it; those counters can include small control-plane bytes
  and are not a tunnel-only benchmark. If the counters are unavailable, the UI
  says so instead of inventing a number.

Physical-device tests found retained-connection idle stalls in Native and a
HEV screen-off case. Fresh connections still worked. Both engines now use
bounded wake/reverification, and Native also has a coarse physical-byte stall
hint; neither is a guarantee of stall-free idle resume. See the
[verification record](DEVICE_VERIFICATION_2026-10-12.md).

Both engines use a 1500-byte Android VPN MTU. The physical network and the
Codespaces edge still determine the final latency and throughput, so compare
them on the same phone, network, and VLESS profile before choosing one.

Three compatibility switches are available beside the engine selector and apply
to the next connection:

- **Fast DNS (experimental)** is off by default. When enabled, Xray answers DNS
  locally from a bounded FakeDNS pool and recovers the original hostname at
  both the Native and HEV ingress. This can remove one proxied DNS exchange
  from a new hostname, but some apps may retain fake addresses after the VPN is
  stopped; turn it off if post-disconnect networking behaves incorrectly.
- **IPv4-only compatibility** is off by default. It removes IPv6 consistently
  from Android's VPN interface, Xray's native TUN, and HEV. Android then blocks
  IPv6 instead of letting it bypass the VPN. Use it only when a network or app
  repeatedly stalls on an unusable IPv6 path.
- **DPI compatibility (experimental)** is off by default. It fragments the TLS
  startup writes and uses native Go TLS to help routes blocked by SNI/DPI. It is
  a reachability fallback, not a speed mode, and may slightly increase connection
  setup time. Leave it off whenever the normal profile works.

Imported VLESS links may also carry PattNG/v2rayNG-compatible `fm` (FinalMask)
and `cs` (cipher-suite) query fields. The app validates and preserves them;
`cs` is accepted only with the `unsafe` native-Go TLS fingerprint because uTLS
fingerprints override cipher selection.

XHTTP links can also carry an `extra` JSON object. The app validates its syntax
and preserves it for VPN connections, delay tests, and discovered edge variants.
Different tuning objects remain separate transport templates, so seamless
failover does not silently mix tuned and untuned profiles. No tuning is injected
when the imported link has no `extra`. The server's current schema-2 Android
bundle preserves this field exactly as direct links do. Only the separately
named `.legacy.json` bundle omits it for older clients. Refresh exports and
re-import the current bundle to replace older untuned imports. Core-specific settings must still be
accepted by the bundled Xray version, and throughput improvements need phone-side
measurement rather than an assumption.

### DNS and delay measurements

By default the VPN captures the IPv4 and IPv6 default routes, intercepts ordinary DNS
traffic sent to port 53, and sends the built-in DNS-over-HTTPS requests through
the VLESS route. This prevents the common DNS leak where an ISP resolver sees
the domain names you look up outside the encrypted tunnel. It is strong
protection, not an absolute guarantee. Hard-coded DNS-over-HTTPS and DNS-over-TLS
destinations are still subject to full-device routing; selecting another resolver
does not by itself bypass the VPN. This app does not enable Android's bypass API
or exclude other apps. Device/OEM behavior, boot, process death, Private DNS, and
network transitions still require packet-level acceptance with OS blocking enabled.
The app does not claim DNS protection is a replacement for a security audit.

Home **Quick scan** uses one bounded real route sample per saved config and is
intentionally fast. Modern libXray builds test up to five tagged configs in one
batch and retry only failed entries against alternate 204 endpoints; older
cores fall back to the sequential path. A successful one-sample result is
marked provisional. Partial failures remain attached to the result and do not
qualify that config as a verified live standby. The
per-config real-delay action uses three samples and reports median, p95, and
jitter. These measurements are from the phone and therefore reflect the
user's actual ISP path better than Codespace-side route probes, but they are
not throughput tests.

**Auto-optimize routes** is enabled by default and runs at most once every 30
minutes while the VPN is stopped and the selected Codespace was last known
ready. It combines the server-exported IPs with bounded system, Cloudflare DoH,
Google DoH, and a small curated set of Google EDNS Client Subnet views for the
exact Codespaces hostname, then keeps only
configs that complete a real libXray request from the phone. It adds at most 12
new verified configs per pass and tests at most 30 total. Automatic passes run
bounded 384 KiB download and 64 KiB upload samples on the current route and its
strongest challenger when their saved samples are stale; manual Optimize tests
up to three candidates. The last measured route rates are visible beside each
config and contribute to selection together with median, tail latency, jitter,
failures, and hysteresis. A full-batch failure does not change saved health.
This improves route coverage and phone-side route choice; the bounded samples
are comparative signals, not a speed-test or throughput guarantee.

**Seamless route failover** is enabled by default. When at least two recent,
phone-verified configs share the selected config's exact Codespace and
transport settings, the running Xray config carries up to three distinct edge
addresses. Xray probes them concurrently and uses least-ping selection for new
connections, with the user's selected route as the startup fallback. The
outer service recovery remains responsible for a stopped Codespace or total
route failure. Disable the switch to use one outbound and zero observatory
traffic.

The foreground service keeps the selected profile across failures. If the live
route stops responding, it performs bounded local recovery, then wakes and
polls the Codespace through the configured control plane before rebuilding the
tunnel. Worker and direct-GitHub control-plane sockets used by the foreground
service bypass the VPN so a dead data tunnel cannot block its own recovery. If a
direct GitHub wake only reaches `Available`, the service continues bounded route
verification while Codespaces forwarding settles. If control-plane readiness
times out, the app tests a bounded set of imported alternate profiles, reserving
capacity for an alternate transport as well as different edge addresses.
A successful backup becomes the selected profile for the next connection.
Returning to the app re-verifies a healthy route and restarts or repairs a stale
core without changing the foreground service to non-sticky mode. HEV expires a
truly idle TCP mapping after five minutes so an app reconnects instead of
reusing a stale local flow. The outer VLESS socket starts TCP keepalive after 25
idle seconds and retries every 15 seconds to help retain short-lived mobile
carrier NAT mappings. This favors idle-resume responsiveness over minimizing
radio wakeups.
Route re-verification and upstream TCP payload checks detect a dead shared
tunnel. HEV's displayed TUN counters still include packet headers and local
TCP control packets; those packets are not proof of Internet connectivity.
Only payload read from SOCKS can extend the health-check grace period, and even
that grace is finite: repeated failed health quorums eventually request recovery.
Transient recovery failures retain the user's desired-connected intent and
retry with capped backoff instead of stopping permanently. Authentication,
configuration, and quota errors need user action. Explicitly pressing Stop
clears sticky recovery state and cancels scheduled retries.

Start VPN cancels background route optimization and waits for its native probe
to finish safely. A process-wide runtime lease also prevents Android Always-on
startup from overlapping a temporary scan core. A blocking native ping can still
take its bounded timeout; cancellation does not unsafely kill the native thread.

Importing the exact same VLESS URI again reuses the existing stored profile
instead of accumulating duplicates and discarding its measured delay history.

## Production Core

Unit tests can compile without `libXray.aar` so UI, storage, Worker wake, and
config tests run on any development machine. Installable debug and release
builds require the official Android AAR and matching
`app/libs/libXray.properties`:

1. Install Go 1.26.10, Python, Android command-line tools, and Android NDK
   28.2.13676358. The build script pins `gomobile` and `gobind` to the same
   tested `x/mobile` revision, prevents upstream `@latest` replacement, and
   attests both tools again after the build. It also removes local source paths
   and nondeterministic linker build IDs so clean builds are byte-reproducible.
2. Build the AAR:

```powershell
.\scripts\build-libxray-aar.ps1
```

The default build is pinned to the libXray commit recorded in
`app/libs/libXray.properties`. Override it only after a different commit passes
the real-device production matrix:

```powershell
.\scripts\build-libxray-aar.ps1 -LibXrayRef "<tested-libxray-commit-sha>"
```

3. Confirm `app/libs/libXray.aar` and `app/libs/libXray.properties` exist. The
   build script records the exact commit, Go version, mobile-tool revisions,
   controller-, observer-, measurement-, XHTTP-concurrency-, and XHTTP-close-error
   patch hashes, plus the AAR SHA-256. The XHTTP backport fixes upload buffer
   ownership and concurrent response-body ownership without changing the core
   version or transport tuning. The builder runs focused byte-integrity and
   lifecycle fixtures; CI repeats them under Go's race detector.
   Functional-debug and release builds
   reject a missing, stale, mismatched, or Java-API-incompatible core, and
   release builds also verify every bundled HEV ABI against its pinned metadata.
4. Configure a real release keystore outside the repository. Release tasks
   refuse to create a publishable artifact without all four variables:

```powershell
$env:G2RAY_RELEASE_KEYSTORE = "C:\path\to\release-keystore.jks"
$env:G2RAY_RELEASE_STORE_PASSWORD = "<store-password>"
$env:G2RAY_RELEASE_KEY_ALIAS = "<key-alias>"
$env:G2RAY_RELEASE_KEY_PASSWORD = "<key-password>"
```

Keep the keystore and passwords out of Git and CI logs. The release signing
configuration is read only from the process environment and is never written
to the project.

5. Build the pinned HEV JNI libraries for every supported Android ABI:

```powershell
.\scripts\build-hev-tun.ps1
```

The HEV build is pinned to stable tag `2.17.1`, targets Android API 24 to match
the app's `minSdk`, and verifies the Android API note in every generated native
library before it replaces the bundled binaries. The app also verifies HEV's
boolean start/stop/runtime JNI contract and the five-field payload-stats patch
hash so an API-incompatible native upgrade cannot silently produce an unusable
APK. The first four stats fields retain their original meaning. On Linux,
`bash scripts/test-hev-payload.sh` builds the pinned engine in a temporary
directory and tests synthetic TCP versus actual SOCKS payload without a phone
or a privileged TUN device. It also stops and restarts the engine with half-open
and established TCP sessions, checking the patched lwIP shutdown cleanup. CI
runs this Linux fixture separately from Android source checks. Neither check
is Android device acceptance.

6. Build and test the app:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat testDebugUnitTest assembleFunctionalDebug lintDebug
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat bundleRelease
```

`assembleDebug` and the `assembleFunctionalDebug` convenience task both require
a verified `libXray.aar`, verified HEV binaries for every supported ABI, and the
pinned native-build contract. This prevents Android Studio from producing an
installable but nonfunctional VPN APK. Coreless development remains available
through unit-test tasks. Publish only the signed release AAB.
Use the release Android App Bundle for distribution so stores deliver only the
phone's ABI instead of a universal APK containing all native architectures.
Follow `PRODUCTION_TEST_MATRIX.md` before describing a build as production-ready.

## On-device Network Verification

The ordinary instrumentation suite checks packaged native loading and config
startup without contacting a real proxy server. `LiveVpnDeviceTest` is a separate,
opt-in check of the full Android VPN path, not just the local SOCKS listener.

Select a working saved profile, grant VPN permission in the app, stop VPN, and
connect one test phone. The Codespace must already be available. This test uses
the saved profile without printing its link or credentials, starts and stops
the service, and does not change the persisted engine preference. It transfers
2 MiB down and 64 KiB up, checks three HTTPS endpoints before and after background
idle with both a retained connection pool and fresh sockets, and verifies real
HEV receive counters. Each phase requires two of three endpoints to complete
within six seconds; an eventual 200 response after a long stall does not pass.
This is a regression ceiling, not a latency guarantee. It consumes mobile data.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=com.g2ray.client.vpn.LiveVpnDeviceTest#selectedProfileCarriesTrafficAndRecoversAfterBackgroundIdle" `
  "-Pandroid.testInstrumentationRunnerArguments.liveVpn=true" `
  "-Pandroid.testInstrumentationRunnerArguments.engine=hev" `
  "-Pandroid.testInstrumentationRunnerArguments.idleSeconds=300"
```

Repeat with `engine=xray_native` to check Native independently. Without
`liveVpn=true`, the test is skipped. The repository's root Android CI compiles
these tests and runs unit tests and lint; it does not claim phone, long-video,
network-handoff, or long-session coverage.
The optional `transport=ws` or `transport=xhttp` runner argument selects an
already-saved profile on the same edge and account, without changing the saved
selection. It fails if no matching profile exists. Sanitized connection IDs and
HTTP protocol names distinguish retained connections from new ones. Wake the
phone before an awake baseline; the test reports whether the device is interactive.

Use `resumeVpnActivity=false` to resume traffic without reopening the VPN UI.
Separate opt-in checks exercise the real Start VPN action, direct wake, and
protected recovery while VPN is active; see [the test matrix](PRODUCTION_TEST_MATRIX.md).
The [13 October device follow-up](../DEVICE_RECOVERY_FOLLOWUP_2026-10-13.md)
records the verified recovery-socket fix and the still-failing retained-flow cases.

## Notes

- Generated configs and wake secrets are private. Do not publish them in a repository.
- The VPN routes full-device IPv4 and IPv6 traffic through Android `VpnService`
  unless IPv4-only compatibility is enabled.
- Xray socket protection is registered through `libXray` so the tunnel's own upstream sockets do not loop back into the VPN.
- If the selected profile or upstream network cannot handle some IPv6 destinations, Android will still send those packets into the VPN instead of leaking them outside the tunnel.

This client improves control-plane recovery and full-device lifecycle handling,
but it cannot make the underlying Codespaces/Xray data path inherently faster
than another client using the same VLESS profile and core. For real-money
trading, emergency work, or other high-stakes traffic, use a paid provider with
an uptime/latency SLA and a tested failover; Codespaces suspension, quota,
public-port routing, mobile radio changes, and ISP loss remain outside this
project's control.

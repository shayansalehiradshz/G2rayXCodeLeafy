# Daily-use workflows and architectural choices

Date: 18 October 2026. This describes the Android/QR workflow changes, not a claim
that every possible network or app has passed acceptance.

## Defaults and controls

The existing working defaults are deliberately preserved: Auto packet engine,
automatic route optimization on, same-Codespace failover on, Fast DNS off,
IPv4-only compatibility off, and DPI compatibility off. New bypass routing and
optional diagnostics are off. Saved preferences are not silently reset.

Home puts the VPN control first. Technical switches live under Settings >
Connection settings. The information buttons explain what each option does,
when to try it, and its limitations. An engine is not universally fastest;
Fast DNS and fragmentation are compatibility experiments, not free speed boosts.

Share on a config opens Copy link / Show QR code. Both preserve the original
link, including advanced XHTTP parameters. The QR is generated offline. A config
contains server access credentials; neither sharing path adds a wake key or a
GitHub token. Oversized links offer Copy rather than an unreadable QR.

Settings > a Codespace > Configs > Import provides Parse Configs and Scan QR above
a bounded-height text field. Input/parsed selections and credential drafts survive
rotation in memory. Credentials and huge imports are not written into Android
saved-instance bundles. Draft survival after process death is not promised.

The panel's View Config screen can show one compact all-config QR, also saved as
`data/qr/all-configs.png` and `data/qr/all-configs.txt`. It contains a gzip-compressed
offline bundle, not a subscription URL. This app imports it; other clients may
only understand individual VLESS QRs. A bundle too large for a practical QR keeps
the individual QRs and normal bundle/text exports. Scan does not auto-import:
review the destination Codespace and selected configs first.

## Accounts, usage, and switching

Keep each Codespace attached to its own account and appropriate wake credentials.
The hostname identifies the Codespace, not its GitHub owner; importing links must
not guess an owner or reuse another account's token. Existing origin validation
and explicit import destination selection remain in place.

Home's Codespace picker can stop the current VPN, select another saved Codespace,
and connect it. Old profiles/accounts are retained. A missing config or failed
stop produces an error rather than starting an unrelated account. Android
always-on must be managed before switching because its service restart policy
otherwise competes with an intentional disconnect. In-flight app connections
cannot migrate between different servers; apps must reconnect.

GitHub Free's 120 core-hours correspond to approximately 60 active hours on a
2-core machine. This is shared across Codespaces billed to the same account,
not a separate entitlement for each Codespace. Storage also consumes included
usage. See [GitHub's billing documentation](https://docs.github.com/en/billing/concepts/product-billing/github-codespaces).

The panel's counter is only this project's local runtime estimate. Importing an
all-config bundle can copy its timestamped usage snapshot. Alternatively use the
Codespace's Usage screen to enter the panel time. Home projects it only within the
same UTC month, explicitly assuming the host kept running when selected. A stopped
host, other Codespaces, billing plans, and clock changes can make it inaccurate.
An expired snapshot becomes unknown; it does not invent the next month's billing.
Only an explicit provider quota/billing response is labelled as a confirmed block.
No API polling, background billing scraper, or automatic account-creation loop is
added. Near-budget warnings and manual switching meet the requested rollover
workflow without speculative automatic server changes during streams.

## Quick access and routing

Settings > Connection settings > Add tile asks Android to add G2Ray VPN to Quick
Settings on supported versions. Older versions use Android's tile editor. The
tile uses the normal VPN permission/start path. While connected, it disconnects;
with always-on enabled it opens the app instead of fighting Android's policy.
The foreground notification also offers Disconnect, or Manage VPN for always-on.

Settings > Bypass routing supports `.ir` suffix rules, explicit domain suffixes,
and exclusions for installed launchable apps. All are opt-in and require a
disconnect before applying. Excluded apps and matched domains deliberately use
the local connection, so their IP/country can differ from the Codespace exit.
Keep bypass off when a consistent VPN country matters.

Domain bypass uses routing-only hostname inspection only when requested. It
does not rewrite an explicit destination IP. Literal IP traffic, encrypted
hostname information, and apps doing independent encrypted DNS may not match.
DNS from apps inside the VPN stays on its proxied resolver path. Per-app bypass
uses Android's disallowed-application API; apps hidden from launcher discovery
are not offered by this first UI. Uninstalled exclusions do not prevent startup.
With Android's Block connections without VPN, excluded apps may have no network:
see [Android VPN routing](https://developer.android.com/develop/connectivity/vpn).

Optional diagnostics retain at most 200 connection-transition events in memory.
They use categorical errors, not raw error strings, browsing hosts, links, keys,
or packets. Off cancels collection and clears the buffer. It adds no packet-level
work and no ongoing collector while disabled. Native throughput remains marked
unavailable rather than fabricating byte counters.

## Speed, hosting, and protocol questions

1. **Premium VPN comparisons:** automatic server wake and project-specific
   recovery are useful advantages. They do not prove higher speed, lower latency,
   or broader coverage than Nord, Express, Windscribe, or another client. Compare
   the same phone/network, repeat measurements, and separate fresh/warm TTFB,
   loaded latency, playback stalls, handoff, and battery use.
2. **Cold starts:** existing startup first tries the working saved route, uses
   direct GitHub or the Worker only for recovery, and does not put optional
   diagnostics ahead of primary readiness. Avoiding needless wake/port/core
   restarts helps; code cannot remove GitHub scheduling and host startup time.
   A Codespace also has a documented 12-hour maximum active lifetime; see
   [GitHub lifecycle](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle).
3. **Additional packages:** none is installed by default. A bigger tuning daemon,
   continuous speed test, parallel second proxy core, or packet capture consumes
   resources without an established benefit. Existing bounded probes, server
   diagnostics, and controlled isolated tests are the appropriate first tools.
4. **Cloudflare domains:** a custom hostname for the waker can help reachability
   when `workers.dev` is blocked. It does not shorten GitHub boot. A carefully
   configured data-plane reverse proxy is a separate A/B experiment: it adds a
   network leg and may be faster or slower. A CNAME by itself does not arrange
   the GitHub origin Host/SNI. Current ordinary Origin Rules do not include
   Free-plan Host/SNI overrides; check [the features table](https://developers.cloudflare.com/rules/origin-rules/).
5. **More configs:** independent reachable edge IPs and genuinely different
   compatible transports are useful. Renaming the same link or adding dozens of
   ALPN permutations does not add network capacity. Existing discovery measures
   and bounds candidates from the phone; failed probes and network-specific
   history must not be presented as universal speed measurements.
6. **Other wake methods:** the app already supports a Worker and direct GitHub.
   The Worker itself calls GitHub. CLI, browser, and SSH attachment also depend
   on GitHub's authorization/lifecycle; an ordinary stopped data port cannot
   start its own host. Adding browser automation is more fragile than the
   [supported lifecycle API](https://docs.github.com/en/rest/codespaces/codespaces#start-a-codespace-for-the-authenticated-user).
7. **Adaptation:** existing route history/ranking and bounded same-host failover
   adapt to measured network conditions. It is not learning what sites you visit
   or training a model. Continuous large scans or frequent best-ping switching
   during active media can make consistency worse. No such scanner was added.
8. **Other cores:** sing-box and other proxy engines can serve appropriate HTTP
   transports, but changing engines requires parser, feature, JNI, DNS, routing,
   and recovery parity plus new acceptance tests. Co-running cores needs separate
   ports and adds memory/ownership complexity; it is not an automatic speed win.
   See [sing-box inbound types](https://sing-box.sagernet.org/configuration/inbound/).
9. **Protocol layers:** Clash is a client/configuration ecosystem, gRPC is an
   HTTP transport, and VLESS/VMess/Trojan/Shadowsocks are proxy protocols. Adding
   a name is not a new low-latency path. Some can be wrapped in supported HTTP
   transports with server/client changes; no untested protocol was advertised
   or enabled in this app.
10. **UDP/QUIC/WireGuard/Hysteria:** GitHub's normal public forwarded web address
    is not a public UDP socket or arbitrary raw TCP listener. WireGuard uses UDP
    and Hysteria2 uses QUIC/UDP, so neither is a drop-in replacement behind that
    address. UDP payload can be encapsulated inside a compatible proxy's TCP
    transport; that does not become end-to-end QUIC and can suffer TCP loss
    coupling. A separate relay/overlay or different ingress changes the hosting
    architecture. Sources: [GitHub forwarding](https://docs.github.com/en/codespaces/developing-in-a-codespace/forwarding-ports-in-your-codespace),
    [WireGuard protocol](https://www.wireguard.com/protocol/),
    [Hysteria protocol](https://v2.hysteria.network/docs/developers/Protocol/).
11. **WARP/MASQUE:** installing WARP as the Codespace's outbound route changes
    the exit and adds another provider/hop; it does not fix phone-to-GitHub
    latency. It can change apparent country and break lifecycle/control-plane
    access without policy routing. WARP on the phone is a different VPN and
    ordinarily competes for Android's VPN slot. A Cloudflare-managed overlay
    requires enrollment/configuration and is not the same public GitHub ingress.
    See [Cloudflare client architecture](https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/configure/route-traffic/client-architecture/).

## Review reconciliation

The supplied UI reviews correctly identified missing Settings system-back
handling, unreachable key management, invisible operation-blocking feedback,
rotation-lost drafts, a buried VPN control, oversized import input, missing
notification actions, and dark-system-bar mismatch. Those paths are addressed.

Hardcoded English limits translation but does not make localization structurally
impossible. A complete translation/resource migration remains separate work.
RTL support and touch target sizes need visual/device evidence, not a blanket
pass inferred from grep. A nested IconButton does not inherently activate its
parent card: the new device share test checks selection stability instead of
accepting that claim. Raw wake-key visibility remains the owner's explicit
existing preference. No inferred speed rating, invented throughput value, or
generic MTU/keepalive change is treated as evidence.

## Verification for this revision

Source/build checks completed on 18 October 2026:

- 703 Android unit tests in 48 suites: zero failures, errors, or skipped tests.
- Android lint: zero errors and 26 warning issues. These are not a clean-lint
  claim; dependency/target updates and the remaining existing warnings are
  separate work, not reasons to weaken the checks.
- Functional debug APK and instrumentation APK built with the matching pinned
  native inputs. Fourteen build-logic tests passed.
- All 40 generated VPN configurations passed the real pinned Xray 26.7.28
  configuration loader, including both engines, DNS modes, IPv4 settings, and
  optional domain bypass. This validates configurations, not every live route.
- Server: 66 static checks and 207 behavior checks passed. Worker: 69 checks
  passed. Mocked server benchmarks stayed within their six budgets; those are
  control-plane overhead checks, not Internet throughput measurements.

The matching debug APK was installed over the previous debug build on the
connected Galaxy A52s / Android 14 without clearing app data. Its installed
bytes matched the built APK SHA-256:

```text
5dea4aeb22ff028f103e3c4fbf7687e6a6245ffe18c87003024f9b0a16152f0a
```

Actual phone checks:

- Three UI tests passed: Settings back navigation and help, Share/QR/Copy without
  changing selection, and a 400-line import with visible Parse and rotation-safe
  draft. A screenshot exposed a redundant native action bar; the manifest now
  disables it, and the repeated UI test asserts that the native bar is absent.
- Three packaged-core smoke tests passed against the phone's actual ABI.
- Quick Settings addition, connection, and disconnection worked. The foreground
  notification's Disconnect action worked. The final phone state was a verified
  HEV connection, not a test-induced disconnected or blocked state.
- Startup and retained-client-pool idle probes passed. UI startup verification
  took 7,589 ms in the final sample. After an actual 106,255 ms idle interval,
  retained-pool HTTPS requests took 1,026 / 758 / 997 ms; fresh ones took
  812 / 760 / 1,008 ms. Pool reuse does not mean every TCP socket stayed open.
  The idle interval includes instrumentation activity-relaunch overhead; it is
  not a measured recovery delay. This is not deep-Doze acceptance.
- Explicit domain bypass matched the physical connection's public exit in four
  runs: HEV and Native, each with Fast DNS enabled and disabled. The test restored
  original preferences. No public IP is included in this record.
- Two injected local-core failures recovered automatically on the final APK.
  Full HTTPS quorums completed 11,287 ms and 6,343 ms after their respective
  failure/recovery starts. Their individual follow-up HTTPS responses took
  763-1,085 ms. Earlier candidate samples were 6,423 / 7,655 ms, illustrating
  variation rather than a fixed recovery guarantee. No real Codespace process
  was restarted for these tests.
- The scanner opened the real camera activity, and cancellation returned to
  Import. This does not establish an optical scan from a second display.
- At the final check, the Android crash buffer contained no entry for this
  package. That is a bounded observation, not proof that the app cannot crash.

Saved Auto/Fast-DNS/IPv4 preferences were preserved, not reset to fresh-install
defaults. Android always-on and lockdown settings were not changed. Private
device logs, screenshots, old/new APKs, and raw configuration data remain under
the gitignored `data/verification/ui-features-20261018/` directory.

The final APK also labels command feedback as Last action, with start/stop
requests explicitly acknowledged instead of retaining a misleading Starting
status after the service is Ready. Its UI, core, idle, and recovery checks were
repeated after that correction. Account-switch unit regressions cover missing
configs, always-on policy, ordered Stop/select/Start, competing Start, and
cancellation before a replacement account starts. The server QR regression
first failed, then passed after stale PNG removal when the encoder is absent.

Still separate acceptance gates: optical bulk-QR scanning, an excluded app's
independent UID traffic, Android lockdown combinations, real cross-account
quota exhaustion, forced cold host boot, signed-release upgrade, another device,
deep Doze/handoff, and extended media/AI-stream sessions on this APK. Earlier
revision results do not retroactively pass those gates. This task did not restart
the active server, rotate its UUID, deploy a Worker, or claim new throughput gains.
The new panel all-config QR requires pulling this server revision and refreshing
exports; the installed client already accepts that format.

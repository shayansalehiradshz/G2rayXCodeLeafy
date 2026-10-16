# Live Codespace rollout and US-exit verification

## Scope

On 16 October 2026, the owner authorized direct access to the active Codespace
for `z3roozsh/G2rayXCodeLeafy` and approved GitHub's additional Codespaces scope.
This follows the source/CI review in [US_EXIT_REVIEW_2026-10-16.md](US_EXIT_REVIEW_2026-10-16.md).
No new VPN runtime code or tuning change was introduced during this rollout.

## Deployment

- GitHub's authenticated Codespaces API reports the active host as Available in
  **EastUs2**. The earlier assumption that its region was unverified is now
  resolved for this host; no region change or host recreation was needed.
- Its clean `main` checkout was fast-forwarded from `525fc08` to `eddaae7`.
  Later changes to this verification record are documentation-only.
- The deployed binary is **Xray 26.7.28**, matching the image pin. No binary
  upgrade or engine/container restart was needed.
- The production engine PID remained **238** throughout the source update,
  supervisor refresh, health probes and authenticated transport tests. The
  SHA-256 hashes of the live configuration and UUID file remained unchanged.
- Only the stale background supervisor was refreshed. It reported running,
  version=ok, ownership token present and a fresh heartbeat afterwards.
- Both forwarded ports **443 and 8443 were already public**. No manual exposure
  repair or additional public SSH port was introduced.
- The existing low-latency profile, IPv4-oriented server DNS/egress, XHTTP link
  tuning and origin TCP Fast Open-off configuration were preserved.

SSH succeeded using GitHub CLI with an explicit IPv4 loopback target. Earlier
default-loopback attempts failed before login. No global SSH configuration was
modified. One Windows stdin invocation completed its update/checks but returned
a CRLF-related final `exit` error; a fresh LF-normalized invocation successfully
rechecked the commit, hashes, engine PID and runtime health. The failed exit is
not counted as a successful command.

## Live Results

The doctor reports the listener open, local XHTTP HTTP 200 and external XHTTP
HTTP 200. One observed local/edge probe pair was **1 ms / 131 ms**. These probes
measure from the Codespace, not the phone.

IPinfo and Country.is independently returned **US** and the same IPv4 address.
Both providers were then queried through authenticated **XHTTP** and
**WebSocket** VLESS clients on the Codespace. All four observations matched the
direct host observation. Raw live links, UUIDs and credentials are not published
in this report. This establishes current sampled US egress, not universal
agreement between all geolocation databases or a permanently fixed NAT address.

The small authenticated HTTPS and retained HTTP/2 checks used the currently
exported private schema-2 bundle and the real deployed Xray binary. Each test
client used an owned temporary loopback listener/configuration and was stopped
afterwards; the production engine was left running.

| Transport | Google 204 | Cloudflare 204 | Example website 200 | Retained HTTP/2 initial / later |
| --- | --- | --- | --- | --- |
| XHTTP | 267 ms | 235 ms | 133 ms | 177 / 32 ms |
| WebSocket | 122 ms | 117 ms | 205 ms | 80 / 12 ms |

For each retained check, one curl process started its requests one minute apart.
Both responses were HTTP/2 204, and the later request opened **zero new
connections**, confirming actual pool reuse in these samples. These are
Codespace-side SOCKS/edge checks, not Android TUN, mobile-ISP, throughput, media,
long-session or client-superiority measurements.

The host's direct IPv6 exit probe could not connect. This is an IPv6 coverage
limit, not evidence that a phone's physical IPv6 escaped. Android's default
capture/IPv4-only family-blocking behavior still needs real-device acceptance.

## Additional Checks and Phone Boundary

- Deployed configuration accepted by the actual Xray binary.
- Panel/post-start Bash syntax and error-level ShellCheck passed on the host.
- All nine isolated exit-diagnostic fixture groups passed on the host.
- Existing source verification remains 622 Android tests, 197 server behavior,
  66 static and 66 Worker checks, with both hosted workflows passing at the
  recorded source revision; this is not a second live full-suite claim.
- The functional debug APK and matching instrumentation APK are built locally.
  App SHA-256: `97be6a8c038635590ee3f20635bcc8635af3ae7ab107c0781b039a56a900e05e`.
  Test SHA-256: `d3320a38e5b0b514f4d6bc53653362da2624f6d49e6ee9b751a707cdfba672b9`.
- ADB reported no connected device during the final checks. Neither APK was
  installed in this pass, and no phone PIN or lock setting was used/changed.

Phone installation must preserve the existing signing identity and saved data.
Actual app startup/wake, US-exit observations from independent applications,
capture retention, Always-on/Block connections without VPN, both packet engines,
idle resume, handoff, Doze and sustained media/AI streams remain device work.
No zero-leak, bug-free, instant-recovery or uninterrupted-session guarantee is
made from these server-side samples.

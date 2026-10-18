# A second entrance to the same VPN

This is a setup guide for an optional experiment, not a deployed feature or a
promise of better speed. Checked against the source at `2fc7148` and current
provider documentation on 18 October 2026. Keep the working direct profiles.

## What the phrase means

The **control plane** is the button that wakes the Codespace. The **data plane**
is the connection carrying browsing, videos, and AI traffic. A custom domain for
the existing wake Worker changes only the control plane.

A reverse proxy is another entrance which forwards the actual tunnel traffic
to Xray. There are two different arrangements:

```text
Current:
Phone -> GitHub public edge -> Xray in Codespace -> website

Cloudflare front of the existing GitHub route:
Phone -> Cloudflare -> GitHub public edge -> Xray -> website

Cloudflare Tunnel connector:
Phone -> Cloudflare -> cloudflared inside Codespace -> Xray -> website
```

The second arrangement adds a provider hop but might have a better route from
the phone. The third replaces GitHub's public forwarding leg with a connector.
Both can help or hurt. Neither changes the origin's CPU, wakes a stopped host,
eliminates host limits, or makes video buffers/AI streams immune to disruption.
With the existing origin egress unchanged, destination traffic still leaves
through the Codespace, not through a new Cloudflare WARP exit.

**A/B** means keep the current route as A, add a separate experimental route B,
and compare them on the same phone/network without changing other settings.

## Option 1: the existing WebSocket front

Use this only when the Cloudflare account has the required origin overrides.
Ordinary Origin Rules currently provide **Host/SNI overrides on Enterprise**,
not on Free/Pro/Business. A CNAME alone does not configure GitHub's origin
identity. See [Cloudflare's availability table](https://developers.cloudflare.com/rules/origin-rules/).

1. Use an unused hostname such as `vpn-test.example.com`, not the wake hostname.
2. In the Codespace panel, inspect `20) Cloudflare WS Front`. It prints the
   exact current WS origin, normally `<codespace>-8443.app.github.dev`. WS must
   already be enabled; enabling it when absent can require an engine change.
3. Create a **proxied CNAME** from the test hostname to that origin. Configure
   HTTPS with **Full (strict)** origin validation. Match only the test hostname
   in an Origin Rule; set its Host header to the GitHub WS origin. Cloudflare
   documents that this also sets the origin SNI to that Host value. The public
   GitHub forwarding endpoint is port **443**, not container port 8443.
4. Keep WebSockets enabled. Do not put browser-login challenges, redirect rules,
   or response caching in the tunnel's path. Scope any compatible configuration
   to the test hostname; do not disable protection across the rest of the zone.
5. Save the test hostname in option 20 and refresh exports. Import a generated
   `ws-front` profile into the correct Codespace account in the app. Its public
   SNI and Host are the test hostname; the proxy handles the GitHub identity on
   the origin leg. Do not rotate the UUID or delete the direct profiles.

This mode still traverses GitHub's public edge. No Worker code is needed.
Provider support is not proof of VPN compatibility: actual authenticated data
transfer must work before calling the new route usable.

## Option 2: a named Cloudflare Tunnel

This does not need the Enterprise Host-override feature above because the
connector reaches Xray locally. It adds a separate `cloudflared` process to the
Codespace. It has **not** been installed or accepted on this project.

1. In Cloudflare, open **Networking > Tunnels**, create a named tunnel, and
   choose the Codespace's Linux architecture. Install `cloudflared` from the
   official package/download linked by the dashboard. Pin and record the tested
   version before relying on it. Do not blindly install a systemd service inside
   a container that may not run systemd. The connector must be able to reach
   Cloudflare on outbound port 7844; verify connectivity before assuming it works.
2. Start its connector using the dashboard's token locally on the Codespace.
   Keep the token out of chat, screenshots, Git, and command history. For an
   initial foreground experiment, the supported environment-variable form is:

   ```bash
   read -r -s -p 'Cloudflare tunnel token: ' TUNNEL_TOKEN
   printf '\n'
   export TUNNEL_TOKEN
   cloudflared tunnel --no-autoupdate run
   unset TUNNEL_TOKEN
   ```

   Keep that terminal running during the comparison. This is not persistent
   lifecycle integration; closing it or stopping the host can stop this route.
3. Add a **Published application** route for `vpn-test.example.com`. For the
   current optional plain-HTTP WS listener, set the Service URL to
   **`http://127.0.0.1:8443`**. Verify the actual listener/port first if its
   settings differ. This is HTTP on a local socket even though the public client
   connection uses HTTPS. Do not select `tcp://` for ordinary VLESS-over-WS
   clients: Cloudflare's raw TCP publishing has different client requirements.
4. Keep the existing WS path, normally `/ws`. Do not add an interactive Access
   login page: the current VPN client has no browser-login/service-token flow
   for this route. VLESS credentials must remain required by Xray. Publish only
   the intended listener, not the panel, diagnostics, or Xray API.
5. Select the correct Codespace account in the app. Make a test copy of a working
   WS link, changing its server address, TLS SNI, and WS Host to
   `vpn-test.example.com`; keep port **443**, UUID, WS path, and the working
   fingerprint/ALPN settings, then import that copy. Alternatively, option 20
   can export those hostname profiles: the connector route replaces the CNAME-to-GitHub instructions in
   that screen. That field only constructs links; it does not create a tunnel.
6. Only after a measured improvement, integrate connector startup, owned-process
   supervision, token storage, bounded logs, and readiness into the lifecycle.
   A foreground demo or generic service-install command does not do that work.
   Native GitHub-route health/wake checks still check the GitHub route, not
   connector readiness; automatically managing both needs explicit integration.

Start with WS for this isolated comparison. Do not assume existing XHTTP modes
will work unchanged through another HTTP proxy: Cloudflare documents response
buffering considerations for Tunnel. An OPTIONS response does not prove a long
download/upload or AI stream is working. Connector QUIC is only the
Cloudflare-to-connector leg, not end-to-end phone-to-website QUIC.

Sources: [setup](https://developers.cloudflare.com/tunnel/get-started/),
[routing and supported services](https://developers.cloudflare.com/tunnel/concepts/routing/),
[token/run options](https://developers.cloudflare.com/tunnel/reference/run-parameters/),
[streaming troubleshooting](https://developers.cloudflare.com/tunnel/troubleshooting/).

## Decide with measurements, not the domain name

Compare A/B/A/B repeatedly with the same origin, phone, network, DNS/engine
settings, and destination set. Record cold/warm first-byte time, typical and
slow-tail responses, upload/download, latency during transfers, idle resume,
video stalls, and complete AI streams. A single ping or HTTP 101 handshake is
not acceptance. Check the observed destination exit IP/country separately.

Prefer fewer stalls and better response tails over a one-off best ping. A new
route should not become the default if it is merely faster once. Cloudflare
documents that network updates and idle timeouts can terminate WebSockets;
adding it does not create a no-drops guarantee. See [WebSocket limits](https://developers.cloudflare.com/network/websockets/).

Rollback: select a retained direct profile, stop only the connector started for
this experiment, and remove only the test hostname/route if no longer needed.
Clearing option 20 stops exporting its front profiles; it does not remove an
external DNS record or Tunnel.

**Service terms matter:** owning a domain does not grant unlimited CDN use for
video-heavy proxy traffic. Cloudflare's application-service terms restrict
video/large-file CDN use on ordinary plans. Establish permitted product/usage
before deployment; do not assume encryption, personal volume, or a Tunnel is
an exemption. See [Cloudflare's terms](https://www.cloudflare.com/service-specific-terms-application-services/).

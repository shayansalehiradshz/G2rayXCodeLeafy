# G2ray Codespace Waker

Personal test and educational use only: this helper is provided for private recovery experiments and learning. Do not use it to publish generated configs or operate a public access service. Use it only in ways that comply with applicable laws, GitHub Codespaces policies, Cloudflare policies, and network rules.

This Cloudflare Worker gives you a manual wake page for one or more GitHub Codespaces through GitHub's official Codespaces API. The `GET /wake` page is public so it can load in your browser, but `POST /wake` and the `/api/*` actions require your wake secret.

It does not expose VLESS configs, keep the Codespace alive forever, or bypass quota, billing, deletion, or account restrictions.

The browser page is a small mobile-friendly health dashboard. It has a large **Start Codespace** button, a **Check Health** action, route latency/status cards, copyable status text, a stop-polling button, a route history summary, a latency trend, and auto-refresh while the route is still settling.

After GitHub accepts the start request, the Worker waits for the Codespace state to become available, then probes the public `app.github.dev` XHTTP route. If the response says `route_ready: true`, the external route answered a usable XHTTP probe and the VLESS configs should usually be usable. If it says `route_ready: false`, follow the returned `next_action`; HTTP `404` usually means GitHub's port route is still settling, while HTTP `0` usually means DNS or the app route did not answer.

When the start request is accepted but the route is still settling, the API returns HTTP `202` with `ok: true`, `route_ready: false`, `retry_after_seconds`, `poll_after_seconds`, and a `Retry-After` header. The browser page keeps polling health until the route becomes ready or you stop checking.

## 1. Create a GitHub Token

Recommended path:

1. Open <https://github.com/settings/tokens/new?scopes=codespace>.
2. Give it a clear name, such as `G2ray Codespace Waker`.
3. Choose an expiration you can remember.
4. Keep only the `codespace` scope selected.
5. Generate the token and copy it once.

Token types:

- Classic personal access token with the `codespace` scope.
- Fine-grained token that can access the repo and has Codespaces lifecycle/admin write permission, if that option is available in your account.

Keep this token private. Do not commit it to git.

If you are using the G2ray panel, open **Option 15: Recovery / Waker Setup**. It detects the Codespace name and lets you reuse your existing shared wake key or generate the first one.

Do not paste the GitHub token into G2ray. Put the token directly into Cloudflare as the `GITHUB_TOKEN` secret. If the panel generates the first shared wake key, save it and use that exact value as Cloudflare `WAKE_SECRET` and in Android. Paste the same existing key when setting up later Codespaces.

## 2. Configure In The Cloudflare Dashboard

No Wrangler or KV setup is required.

1. Open **Workers & Pages**, create or open your Worker, and click **Edit code**.
2. Replace all code with `worker/codespace-waker/src/index.js`, then click **Deploy**.
3. Open **Settings > Variables and Secrets** and add exactly these three values:

| Type | Name | Value |
| --- | --- | --- |
| Plaintext | `CODESPACE_NAME` | Your exact GitHub Codespace name |
| Secret | `GITHUB_TOKEN` | Token belonging to the account that owns that Codespace |
| Secret | `WAKE_SECRET` | One shared wake key used by the Worker and Android app |

To add another Codespace to the same Worker, add two more dashboard variables:

| Type | Name | Value |
| --- | --- | --- |
| Plaintext | `CODESPACE_2_NAME` | Second exact Codespace name |
| Secret | `GITHUB_TOKEN_2` | Token belonging to its GitHub account |

Continue with `CODESPACE_3_NAME` / `GITHUB_TOKEN_3` when needed, up to slot 10. Keep the original `WAKE_SECRET`; do not create a different wake key for each Codespace. The Worker dashboard and Android app use the real Codespace name as the selection ID.

The authenticated endpoint `POST /api/codespaces` returns only safe fields: selection ID, label, Codespace name, ports, and priority. It never returns GitHub tokens.

### Optional Wrangler Or KV Registry

Wrangler configuration remains available for automated deployments, but it is not part of the recommended manual setup. The KV registry in [`codespaces.registry.example.json`](codespaces.registry.example.json) remains an advanced option for custom labels, priorities, ports, or more than ten entries. Dashboard variables take effect automatically when no valid KV registry is present.

If KV is bound only for optional history and Codespaces are configured with
dashboard variables, set the optional Plaintext variable
`CODESPACE_REGISTRY_SOURCE=dashboard` to skip registry storage reads. Leave it
unset when using a KV registry. Registry operations have a one-second deadline;
unavailable registry storage returns HTTP 503 instead of silently selecting a
different Codespace. An unknown explicitly requested ID/name returns HTTP 404,
even with one configured Codespace. Update older apps' saved selection to the
real Codespace name rather than relying on arbitrary legacy IDs.

If you choose the advanced KV registry, each entry declares its own token binding and route ports. The first port in `route_ports` is the primary XHTTP route and gates `route_ready`; later ports receive one bounded diagnostic probe, not a full settling loop. These are XHTTP OPTIONS checks, not WebSocket handshake tests: an HTTP 400 on the WS port does not establish that WebSocket traffic is broken. Keep `[443]` for normal setup. Do not list the WS port first or treat it as proof that the XHTTP route is ready.

The tracked example enables Workers Logs. Successful and failed requests emit a
structured event with `request_id`, route path, HTTP status, duration, and
Cloudflare colo; responses return the same ID in `x-g2ray-request-id`. Set
`WORKER_REQUEST_LOGS=0` only when you intentionally want to suppress successful
request events. Errors are still logged.

Optional: add `CODESPACE_PORT` as a **Plaintext** variable only if you changed the panel's `XRAY_PORT`. Leave it unset for the default port `443`.

Optional: add `CODESPACE_FORWARDING_DOMAIN` as a **Plaintext** variable only if GitHub changes the Codespaces forwarding domain from `app.github.dev`. The Worker also accepts `CODESPACE_PORT_FORWARDING_DOMAIN` and `GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN`.

Optional: set `WAKE_FAST_PATH=0` as a **Plaintext** variable to disable the default warm-route fast path. With the default `WAKE_FAST_PATH=1`, `/wake` first does a cheap route probe; if the route is already stable and GitHub says the Codespace is Available, it returns success without calling GitHub's `/start` API again.

After GitHub accepts a cold start, the Worker returns HTTP 202 immediately and the app/dashboard polls Health until the Codespace and forwarded route are ready. An individual Health route check is bounded to about eight seconds. This avoids false mobile timeouts without pretending GitHub's forwarding route settles faster. You can override `ROUTE_WAIT_MS`, `ROUTE_POLL_INTERVAL_MS`, `ROUTE_FETCH_TIMEOUT_MS`, and `ROUTE_POLL_AFTER_SECONDS` as Plaintext variables if a measured deployment needs different route-check timing.

### Optional Worker custom domain

If a phone or ISP cannot reach your `workers.dev` hostname, attach a hostname you own directly to this Worker, for example `wake.example.com`, then save `https://wake.example.com` as the Worker URL in both the panel and Android account. This is a Worker custom domain, not a proxy in front of the Codespaces tunnel: it needs no Codespaces Host/SNI override and it does not change VLESS transport behavior. In Cloudflare, open **Workers & Pages > your Worker > Settings > Domains & Routes > Add > Custom Domain**. The hostname must not already have a CNAME record. Test the new URL from the affected phone before replacing `workers.dev` everywhere.

Optional history: create a Cloudflare KV namespace and bind it as `WAKER_KV`. Without this binding, the dashboard still works but shows history as disabled. With `WAKER_KV`, it stores recent wake/health events, route HTTP status, latency, route wait time, and last failures under a per-Codespace key. Identical health polls are sampled with `HEALTH_HISTORY_SAMPLE_MS` (default 5 minutes). Unchanged quota-incident state, including repeated accepted-start timestamps, is sampled with `QUOTA_INCIDENT_SAMPLE_MS` (default 15 minutes); state transitions and the first accepted start are written immediately. A warm route check is not recorded as a GitHub start. Cron observation and scheduler timestamps share one incident write. History/alerts and optional cooldowns have their own writes, so this is not a guarantee against exhausting KV limits. Failed-auth IPv6 addresses share a `/64` budget. KV remains eventually consistent: this is a best-effort anti-spam guard, not a strict distributed rate limiter.

Quota survival history also uses `WAKER_KV`. When GitHub returns HTTP `402`, the Worker records the first quota block, latest quota block, estimated monthly reset, retention/deletion fields from GitHub, and the next later successful wake or health check. This helps confirm that the same Codespace survived into the next monthly reset.

Optional quota survival Cron: add a Cloudflare Cron Trigger only if you want automatic low-frequency checks while quota is blocked, then set `QUOTA_SURVIVAL_CRON_ENABLED=true` as a **Plaintext** variable. The Worker is disabled by default for scheduled events, checks at most about daily before reset, and only attempts a wake near the estimated monthly reset window.

An automatic wake consumes Codespaces compute time even if no VPN client connects. Leave this option disabled for on-demand-only operation.

Optional alerts:

- Add `DISCORD_WEBHOOK_URL` as a **Secret** variable to notify a Discord channel.
- Add `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` as **Secret** variables to notify Telegram.

Alerts are sent for wake attempts when a wake succeeds, when the route is ready, when the route remains stuck at HTTP `404`, and when GitHub rejects the token with `401` or `403`. Manual **Check Health** calls record history when KV is enabled; if history contains a previous stuck route, a later health check can send one route-ready transition alert when the route becomes usable. Repeated identical health checks are deduped or sampled instead of filling history with noise.

## 3. Verify The Dashboard

1. Visit your Worker URL, for example `https://your-worker.your-subdomain.workers.dev`.
2. Paste the same `WAKE_SECRET` used in Cloudflare and Android.
3. Confirm your Codespace appears in the selector.
4. Click **Check Health**. If it is shut down, click **Start Codespace**.

An immediate `unauthorized` response means the entered key does not exactly match Cloudflare's `WAKE_SECRET`. A `missing_github_token_binding` response means the selected Codespace does not have its matching `GITHUB_TOKEN` variable. A GitHub `404` means the Codespace name/token account pairing is wrong or inaccessible; it is different from a route-probe HTTP `404`, which means the Codespaces forwarding route is still settling.

The panel accepts the Worker URL with or without `https://`, and with or without `/wake`. Return to **Option 15: Recovery / Waker Setup**, paste the URL, and run its Worker test.

For filtered networks, create a second Worker endpoint with the same variables and shared wake key, then save it as Android's Backup Worker URL.

### Optional Wrangler Deployment

The repository still supports automated Wrangler deployment for users who choose it:

```bash
npm ci
cp wrangler.toml.example wrangler.toml
npx --no-install wrangler secret put GITHUB_TOKEN
npx --no-install wrangler secret put WAKE_SECRET
npm run deploy
```

## 4. Start The Codespace Manually

Recommended CLI call:

```bash
read -rsp "Wake secret: " WAKE_SECRET; echo
curl --config - <<EOF
request = "POST"
url = "https://g2ray-codespace-waker.YOUR_SUBDOMAIN.workers.dev/wake"
header = "Authorization: Bearer ${WAKE_SECRET}"
EOF
unset WAKE_SECRET
```

In multi-Codespace mode, target a non-default Codespace by adding a JSON body.
This keeps the shared wake key unchanged while selecting its matching GitHub
token inside the Worker:

```bash
read -rsp "Wake secret: " WAKE_SECRET; echo
curl --config - <<EOF
request = "POST"
url = "https://g2ray-codespace-waker.YOUR_SUBDOMAIN.workers.dev/wake"
header = "Authorization: Bearer ${WAKE_SECRET}"
header = "Content-Type: application/json"
data = "{\"codespace_id\":\"your-second-codespace-name\"}"
EOF
unset WAKE_SECRET
```

With the simple dashboard-variable setup, use the exact value of
`CODESPACE_2_NAME`. Advanced KV registry users should use the registry entry's
custom `id` instead.

Opening the Worker URL in a browser and entering the wake secret in the form is preferred because it keeps the secret out of shell history. The `curl --config -` form also keeps the expanded secret out of process arguments; use it only on machines you trust.

Replacing the primary Codespace requires changing `CODESPACE_NAME` and `GITHUB_TOKEN`. To retain it and add a standby, add the next numbered dashboard pair such as `CODESPACE_2_NAME` and `GITHUB_TOKEN_2`; the Worker URL and shared wake key remain unchanged. Add the new Codespace in Android, import only its local bundle, and select it when you need to switch.

For automation that only needs GitHub state and wants to avoid an external route probe, call `POST /api/health?route=false`. Normal browser health checks should use `/api/health` so the dashboard can report route readiness.

## Expected Responses

- `200` with `ok: true`: GitHub accepted or handled the start request.
- `route_ready: true`: the XHTTP route returned HTTP `200` during the Worker wait window, matching the pinned Xray handler's successful `OPTIONS` response and the panel classifier. HTTP `400` is treated as an error, not readiness.
- `route_ready: false` with HTTP `404`: the Codespace started, but the GitHub route has not settled yet.
- `route_ready: false` with HTTP `0`: DNS, TLS, or the app route did not answer before the Worker timeout.
- `route_failure_reason`: machine-readable route reason such as `route_settling_404`, `timeout_or_unreachable`, `dns_tls_or_network_unreachable`, `edge_or_origin_error`, or `ready`.
- `retry_after_seconds` / `poll_after_seconds`: how long the browser or automation should wait before checking again.
- `next_action`: the fastest manual recovery step to try next.
- `next_action_code`: stable machine-readable action code such as `retry_vless_config`, `wait_route_or_recover`, `rotate_github_token`, `wait_github_rate_limit`, or `wait_for_quota_reset`.
- `quota_blocked: true`: GitHub returned HTTP `402`, so quota or billing is blocking the start.
- `quota_reset_estimate_utc`: a calendar-month reset estimate using the next first-of-month UTC. Verify the exact reset in GitHub Billing; GitHub billing data is authoritative.
- `retention_period_minutes` / `retention_expires_at`: GitHub Codespaces retention fields when the API returns them.
- `retention_risk`: `safe`, `warning`, `urgent`, or `unknown` based on `retention_expires_at`.
- `survival_next_action`: what to do to preserve the same Codespace/configs through quota reset.
- `notification_status: "none"`: no Discord/Telegram alert was needed, or no alert channel is configured.
- `notification_status: "deferred"` / `notifications_deferred: true`: Discord or Telegram delivery is running after the response through Cloudflare `waitUntil`; check Worker logs if an alert does not arrive.
- `notification_status: "failed"`: notification delivery was attempted synchronously and `notification_errors` contains the delivery error.
- `history_deferred: true`: history and quota-incident work were queued through Cloudflare `waitUntil`, so the dashboard response did not wait for optional storage. `quota_incident_recorded: false` while deferred is not a failed wake; inspect a later history response for persistence. Optional KV operations are time-bounded. KV history/cooldowns remain best-effort and non-transactional: concurrent requests may lose history or repeat a start. They are not an authoritative billing ledger or a strict distributed lock.
- `401`: Wrong wake secret, or GitHub rejected the stored token. Check the JSON `error`, `reason`, or `token_warning` field to tell which side rejected the request.
- `402`: GitHub quota or billing blocked the start. If the option is available, mark the Codespace as **Keep codespace**, wait for quota reset or adjust GitHub billing settings, then start the same Codespace again. Org-owned or policy-managed Codespaces may have retention rules that override or hide this option.
- `403`: GitHub token was accepted but cannot access Codespaces, commonly because the `codespace` scope is missing. The response reason may be `github_token_scope_missing`.
- `404`: Codespace name is wrong or the token cannot access it.
- `429`: Too many wrong wake-secret attempts, optional successful-wake cooldown, or GitHub rate limiting. Check `reason`, `retry_after_seconds`, and any `github_rate_limit_*` fields.

Optional anti-spam setting: with `WAKER_KV` configured, set `WAKE_COOLDOWN_SECONDS` as a **Plaintext** variable if you want a successful wake to block repeated wake calls for a short period. Leave it unset to allow immediate manual retries. Any nonzero value below `60` is treated as `60` because Cloudflare KV expiration TTLs require at least 60 seconds. For strict abuse protection on a public endpoint, use Cloudflare's native rate limiting or a Durable Object gate in addition to KV.

## Security Notes

- Do not put `GITHUB_TOKEN` or `WAKE_SECRET` in `wrangler.toml`.
- Do not commit `.dev.vars*`, `.env`, or `.env.*`.
- Prefer the `Authorization: Bearer ...` header over putting secrets in URLs.
- Rotate the GitHub token if the Worker or Cloudflare account is compromised.

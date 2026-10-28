const GITHUB_API_VERSION = "2022-11-28";
const DEFAULT_CODESPACE_PORT = 443;
// The Codespaces edge can take minutes to settle, but holding one Worker
// request open for 35 seconds makes mobile wake UX look hung. Return an honest
// short 202 and let the client/dashboard poll using the supplied hint instead.
const ROUTE_WAIT_MS = 8000;
const ROUTE_POLL_INTERVAL_MS = 2000;
const ROUTE_READY_STABLE_PROBES = 2;
const ROUTE_READY_STABLE_SLEEP_MS = 1000;
const FETCH_TIMEOUT_MS = 10000;
const ROUTE_FETCH_TIMEOUT_MS = 4000;
const HISTORY_KEY_PREFIX = "history:";
const QUOTA_INCIDENT_KEY_PREFIX = "quota-incident:";
const CODESPACE_REGISTRY_KEY = "codespace-registry:v1";
const QUOTA_CRON_CURSOR_KEY = "quota-cron-cursor:v1";
const MAX_REGISTERED_CODESPACES = 20;
const MAX_DASHBOARD_CODESPACES = 10;
const HISTORY_LIMIT = 50;
const KV_OPERATION_TIMEOUT_MS = 1000;
const FAILED_AUTH_KEY_PREFIX = "failed-auth:";
const SUCCESSFUL_WAKE_KEY_PREFIX = "successful-wake:";
const FAILED_AUTH_WINDOW_SECONDS = 600;
const FAILED_AUTH_MAX_ATTEMPTS = 10;
const DEFAULT_ROUTE_POLL_AFTER_SECONDS = 5;
const QUOTA_CRON_DAILY_INTERVAL_MS = 23 * 60 * 60 * 1000;
const QUOTA_CRON_NEAR_RESET_INTERVAL_MS = 60 * 60 * 1000;
const QUOTA_CRON_NEAR_RESET_WINDOW_MS = 6 * 60 * 60 * 1000;

export default {
  async fetch(request, env, ctx) {
    return observedFetch(request, env, ctx);
  },

  async scheduled(controller, env, ctx) {
    if (!quotaSurvivalCronEnabled(env)) return;
    const task = handleQuotaSurvivalCron(controller, env);
    if (ctx && typeof ctx.waitUntil === "function") {
      ctx.waitUntil(task);
    } else {
      await task;
    }
  }
};

async function observedFetch(request, env, ctx) {
  const startedAt = Date.now();
  const url = new URL(request.url);
  const requestId = request.headers.get("cf-ray") ||
    (globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID() : `${startedAt}-${Math.random().toString(16).slice(2)}`);
  let response;
  try {
    response = await routeFetchRequest(request, env, ctx, url);
  } catch (error) {
    console.error(JSON.stringify({
      event: "worker_request_failed",
      request_id: requestId,
      method: request.method,
      path: url.pathname,
      duration_ms: Date.now() - startedAt,
      error: shortError(error)
    }));
    response = json({ ok: false, error: "internal_error", request_id: requestId }, 500);
  }

  const headers = new Headers(response.headers);
  headers.set("x-g2ray-request-id", requestId);
  const requestEvent = JSON.stringify({
    event: "worker_request",
    request_id: requestId,
    method: request.method,
    path: url.pathname,
    status: response.status,
    duration_ms: Date.now() - startedAt,
    colo: request.cf?.colo || null
  });
  if (response.status >= 500) {
    console.error(requestEvent);
  } else if (response.status >= 400) {
    console.warn(requestEvent);
  } else if (requestLogsEnabled(env)) {
    console.log(requestEvent);
  }
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers
  });
}

function requestLogsEnabled(env = {}) {
  const value = String(env.WORKER_REQUEST_LOGS ?? "1").trim().toLowerCase();
  return !["0", "false", "no", "off"].includes(value);
}

async function routeFetchRequest(request, env, ctx, url = new URL(request.url)) {
    if ((url.pathname === "/" || url.pathname === "/wake") && request.method === "GET") {
      return html(renderDashboard());
    }

    if (url.pathname === "/api/wake" && request.method === "POST") {
      return handleWake(request, env, ctx);
    }

    if (url.pathname === "/api/health" && request.method === "POST") {
      return handleHealth(request, env, ctx);
    }

    if (url.pathname === "/api/history" && request.method === "POST") {
      return handleHistory(request, env);
    }

    if (url.pathname === "/api/codespaces" && request.method === "POST") {
      return handleCodespaces(request, env);
    }

    if (url.pathname === "/wake" && request.method === "POST") {
      return handleWake(request, env, ctx);
    }

    if (url.pathname === "/wake" || url.pathname.startsWith("/api/")) {
      return json({ ok: false, error: "method_not_allowed" }, 405);
    }

    return json({ ok: false, error: "not_found" }, 404);
}

async function handleWake(request, env, ctx) {
  const context = await requireAuthorizedContext(request, env);
  if (!context.ok) return json(context.body, context.status, retryHeaders(context.body));

  const cooldown = await rateLimitSuccessfulWake(context.codespaceName, env);
  if (cooldown) return json(cooldown.body, cooldown.status, retryHeaders(cooldown.body));

  const warmRoute = wakeFastPathEnabled(env)
    ? await alreadyWarmCodespaceData(context.codespaceName, context.token, env, context.routePorts)
    : null;
  const data = withSurvivalFields(
    warmRoute?.data || await startCodespaceData(context.codespaceName, context.token, env, warmRoute?.status, context.routePorts),
    env
  );
  addCodespaceIdentity(data, context);
  data.next_action = data.next_action || nextActionForWake(data, data.route_probe || {});
  data.next_action_code = data.next_action_code || nextActionCodeFor(data, data.route_probe || {});
  const responseStatus = responseStatusFor(data);
  applyResponseHints(data, responseStatus, env);
  const event = await enrichEventWithHistoryContext(env, eventFromResult("wake", context.codespaceName, data));
  const sideEffects = await queueHistorySideEffects(env, event, data, ctx);
  const responseData = withQuotaIncidentResponseFields(data, sideEffects.quota_incident);
  const notifications = await queueNotifications(env, event, ctx);
  await rememberSuccessfulWake(context.codespaceName, env, responseData);

  return json({
    ...responseData,
    history_enabled: Boolean(env.WAKER_KV),
    history_recorded: sideEffects.history_recorded,
    history_deferred: sideEffects.deferred,
    quota_incident_recorded: sideEffects.quota_incident_recorded,
    quota_drought_active: sideEffects.quota_incident
      ? sideEffects.quota_incident.quota_drought_active === true
      : responseData.quota_blocked === true,
    notification_status: notifications.status,
    notifications_deferred: notifications.deferred,
    notification_errors: notifications.errors
  }, responseStatus, retryHeaders(responseData));
}

function wakeFastPathEnabled(env = {}) {
  const value = String(env.WAKE_FAST_PATH ?? "1").trim().toLowerCase();
  return !["0", "false", "no", "off"].includes(value);
}

async function alreadyWarmCodespaceData(name, token, env, routePorts = [codespacePort(env)]) {
  const status = await getCodespaceStatus(name, token, env);
  if (!isCodespaceAvailable(status)) return { status, data: null };
  // A GitHub start call cannot repair a Codespace that is already Available.
  // Keep that distinction honest: report the existing edge state and let the
  // caller poll rather than issuing a needless /start request.
  const routeData = await probeCodespaceRoutes(name, routePorts, env, { warm: true });
  // The primary precheck is attempt one, not a third redundant request.
  // An optional port can never seed the primary route's stability check.
  routeData.route_probe.fast_path_precheck_attempts = 1;
  const routeReady = routeData.route_ready;

  return {
    status,
    data: {
      ...status,
      ok: true,
      wake_fast_path: true,
      start_accepted: false,
      route_ready: routeReady,
      route_probe: routeData.route_probe,
      route_probes: routeData.route_probes,
      preferred_route_port: routeData.preferred_route_port,
      next_action: routeReady
        ? "Try the same VLESS config again."
        : nextActionForWake(status, routeData.route_probe),
      message: routeReady
        ? "Codespace is already available and the XHTTP route is usable."
        : "Codespace is already available, but the XHTTP route is still settling. No GitHub start request was sent."
    }
  };
}

async function handleHealth(request, env, ctx) {
  const context = await requireAuthorizedContext(request, env);
  if (!context.ok) return json(context.body, context.status, retryHeaders(context.body));

  const url = new URL(request.url);
  const checkRoute = url.searchParams.get("route") !== "false";
  const codespaceName = context.codespaceName;
  const status = await getCodespaceStatus(codespaceName, context.token, env);
  // A forwarded-port probe cannot become useful until GitHub has brought the
  // Codespace online. Skipping it while shutdown keeps Health cheap and avoids
  // presenting a stale-route 404 as though it were an Xray problem.
  const routeChecked = status.ok && checkRoute && isCodespaceAvailable(status);
  const skippedRouteReason = !status.ok
    ? "github_status_not_ok"
    : !checkRoute
      ? "route_check_skipped"
      : "codespace_not_available";
  const routeData = routeChecked
    ? await probeCodespaceRoutes(codespaceName, context.routePorts, env)
    : {
        route_ready: false,
        preferred_route_port: context.routePorts[0],
        route_probe: {
          url: routeUrl(codespaceName, context.routePorts[0], env),
          usable: false,
          http_status: null,
          latency_ms: null,
          attempts: 0,
          waited_ms: 0,
          stable_probes: 0,
          error: skippedRouteReason,
          route_failure_reason: skippedRouteReason === "route_check_skipped" ? "not_checked" : skippedRouteReason
        },
        route_probes: []
      };
  const data = withSurvivalFields({
    ...status,
    route_check_requested: checkRoute,
    route_checked: routeChecked,
    route_ready: routeChecked
      ? routeData.route_ready
      : status.ok && checkRoute
        ? false
        : null,
    route_probe: routeData.route_probe,
    route_probes: routeData.route_probes,
    preferred_route_port: routeData.preferred_route_port,
    next_action: status.ok && !checkRoute
      ? "Route probe skipped. Use Check Health normally when you need route readiness."
      : nextActionForWake({ ...status, route_ready: routeData.route_ready }, routeData.route_probe),
    next_action_code: status.ok && !checkRoute
      ? "route_check_skipped"
      : nextActionCodeFor({ ...status, route_ready: routeData.route_ready }, routeData.route_probe),
    message: status.ok && !checkRoute
      ? "GitHub state checked; route probe skipped by request."
      : routeData.route_ready
        ? context.routePorts.length === 1
          ? "Codespace is available and the XHTTP route is usable."
          : "Codespace is available and at least one configured route is usable."
        : healthMessage(status, routeData.route_probe)
  }, env);
  addCodespaceIdentity(data, context);
  const responseStatus = responseStatusFor(data);
  applyResponseHints(data, responseStatus, env);
  const event = await enrichEventWithHistoryContext(env, eventFromResult("health", codespaceName, data));
  const sideEffects = await queueHistorySideEffects(env, event, data, ctx);
  const responseData = withQuotaIncidentResponseFields(data, sideEffects.quota_incident);
  const notifications = await queueNotifications(env, event, ctx);

  return json({
    ...responseData,
    history_enabled: Boolean(env.WAKER_KV),
    history_recorded: sideEffects.history_recorded,
    history_deferred: sideEffects.deferred,
    quota_incident_recorded: sideEffects.quota_incident_recorded,
    quota_drought_active: sideEffects.quota_incident
      ? sideEffects.quota_incident.quota_drought_active === true
      : responseData.quota_blocked === true,
    notification_status: notifications.status,
    notifications_deferred: notifications.deferred,
    notification_errors: notifications.errors
  }, responseStatus, retryHeaders(responseData));
}

async function handleHistory(request, env) {
  const context = await requireAuthorizedContext(request, env, { githubToken: false });
  if (!context.ok) return json(context.body, context.status, retryHeaders(context.body));

  const history = await readHistory(env, context.codespaceName);
  const quotaIncident = await readQuotaIncident(env, context.codespaceName);
  return json({
    ok: true,
    codespace_id: context.codespaceId,
    codespace_label: context.codespaceLabel,
    codespace: context.codespaceName,
    history_enabled: Boolean(env.WAKER_KV),
    quota_incident: quotaIncident,
    history
  }, 200);
}

async function handleCodespaces(request, env) {
  const auth = await requireWakeSecret(request, env);
  if (!auth.ok) return json(auth.body, auth.status, retryHeaders(auth.body));

  const registry = await readCodespaceRegistry(env);
  if (registry.unavailable) return json({ ok: false, error: "codespace_registry_unavailable", retry_after_seconds: 5 }, 503);
  const entries = registry.entries
    .filter((entry) => entry.enabled)
    .map((entry) => ({
      id: entry.id,
      label: entry.label,
      codespace: entry.name,
      route_ports: entry.routePorts,
      priority: entry.priority,
      is_default: entry.id === registry.defaultCodespaceId
    }));
  return json({
    ok: true,
    multi_codespace: registry.multi,
    default_codespace_id: registry.defaultCodespaceId,
    codespaces: entries
  });
}

async function requireAuthorizedContext(request, env, options = {}) {
  const requireGithubToken = options.githubToken !== false;
  const auth = await requireWakeSecret(request, env);
  if (!auth.ok) return auth;

  const requestedId = await readRequestedCodespaceId(request);
  const registry = await readCodespaceRegistry(env);
  if (registry.unavailable) {
    return { ok: false, status: 503, body: {
      ok: false, error: "codespace_registry_unavailable", retry_after_seconds: 5,
      next_action: "Registry storage is unavailable. Retry shortly; dashboard-only deployments may set CODESPACE_REGISTRY_SOURCE=dashboard."
    } };
  }
  if (!registry.entries.length) {
    return {
      ok: false,
      status: 500,
      body: {
        ok: false,
        error: "missing_codespace_name",
        next_action: "Add CODESPACE_NAME as a plaintext Worker variable, then retry."
      }
    };
  }
  const entry = selectCodespaceEntry(registry, requestedId);
  if (!entry) {
    return {
      ok: false,
      status: requestedId ? 404 : 400,
      body: {
        ok: false,
        error: requestedId ? "codespace_not_registered" : "codespace_id_required",
        next_action: requestedId
          ? "Add this Codespace to the Worker registry, then retry."
          : "Choose a Codespace before waking or checking health."
      }
    };
  }

  const token = String(env[entry.tokenBinding] || "").trim();
  if (requireGithubToken && !token) {
    return {
      ok: false,
      status: 500,
      body: {
        ok: false,
        error: "missing_github_token_binding",
        codespace_id: entry.id,
        next_action: "Configure the GitHub token secret assigned to this Codespace, then retry."
      }
    };
  }

  return {
    ok: true,
    codespaceId: entry.id,
    codespaceLabel: entry.label,
    codespaceName: entry.name,
    routePorts: entry.routePorts,
    token
  };
}

async function requireWakeSecret(request, env) {
  const suppliedSecret = await readSuppliedSecret(request);
  if (!env.WAKE_SECRET) {
    return {
      ok: false,
      status: 500,
      body: {
        ok: false,
        error: "missing_wake_secret",
        next_action_code: "configure_wake_secret",
        next_action: "Configure the WAKE_SECRET Cloudflare secret, then retry."
      }
    };
  }
  if (!(await secretsEqual(suppliedSecret, env.WAKE_SECRET))) {
    const limited = await rateLimitFailedAuth(request, env);
    if (limited) return limited;
    return {
      ok: false,
      status: 401,
      body: {
        ok: false,
        error: "unauthorized",
        next_action_code: "fix_wake_secret",
        next_action: "The wake key does not match Cloudflare WAKE_SECRET. Use the same exact key in the Worker page, panel, and Android app."
      }
    };
  }
  return { ok: true };
}

async function readRequestedCodespaceId(request) {
  const url = new URL(request.url);
  const queryValue = String(url.searchParams.get("codespace_id") || "").trim();
  if (queryValue) return queryValue;
  const contentType = request.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) return "";
  try {
    const body = await request.clone().json();
    return body && typeof body === "object" ? String(body.codespace_id || "").trim() : "";
  } catch {
    return "";
  }
}

async function readCodespaceRegistry(env) {
  if (env.WAKER_KV && String(env.CODESPACE_REGISTRY_SOURCE || "").trim().toLowerCase() !== "dashboard") {
    try {
      const text = await withKvDeadline(() => env.WAKER_KV.get(CODESPACE_REGISTRY_KEY));
      if (text) {
        const parsed = JSON.parse(text);
        const entries = normalizeRegistryEntries(parsed && parsed.codespaces);
        if (entries.length) {
          const configuredDefault = String(parsed.default_codespace_id || "").trim();
          return {
            multi: true,
            defaultCodespaceId: entries.some((entry) => entry.id === configuredDefault && entry.enabled)
              ? configuredDefault
              : entries.find((entry) => entry.enabled)?.id || null,
            entries
          };
        }
      }
    } catch (error) {
      console.warn("codespace_registry_read_failed", shortError(error));
      // Do not silently replace an unavailable registry with another identity.
      return { multi: true, defaultCodespaceId: null, entries: [], unavailable: true };
    }
  }

  const dashboardEntries = readDashboardCodespaceEntries(env);
  if (!dashboardEntries.length) return { multi: false, defaultCodespaceId: null, entries: [] };
  return {
    multi: dashboardEntries.length > 1,
    defaultCodespaceId: dashboardEntries[0].id,
    entries: dashboardEntries
  };
}

async function withKvDeadline(operation) {
  let timer;
  try {
    return await Promise.race([
      Promise.resolve().then(operation),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error("KV operation timed out")), KV_OPERATION_TIMEOUT_MS);
      })
    ]);
  } finally {
    clearTimeout(timer);
  }
}

function readDashboardCodespaceEntries(env) {
  const entries = [];
  const ids = new Set();
  const names = new Set();
  for (let slot = 1; slot <= MAX_DASHBOARD_CODESPACES; slot += 1) {
    const suffix = slot === 1 ? "" : `_${slot}`;
    const name = String(env[`CODESPACE${suffix}_NAME`] || "").trim();
    if (!name) continue;
    const configuredId = String(env[`CODESPACE${suffix}_ID`] || "").trim();
    const id = configuredId || name;
    if (!/^[a-z0-9][a-z0-9-]*$/i.test(name) ||
        !/^[a-z0-9][a-z0-9_-]{0,63}$/i.test(id) ||
        ids.has(id) || names.has(name)) continue;
    ids.add(id);
    names.add(name);
    const portValue = slot === 1
      ? env.CODESPACE_PORT
      : env[`CODESPACE_${slot}_PORT`];
    entries.push({
      id,
      label: String(env[`CODESPACE${suffix}_LABEL`] || name).trim().slice(0, 96) || name,
      name,
      tokenBinding: slot === 1 ? "GITHUB_TOKEN" : `GITHUB_TOKEN_${slot}`,
      routePorts: normalizeRoutePorts([portValue || DEFAULT_CODESPACE_PORT]),
      priority: slot - 1,
      enabled: true
    });
  }
  return entries;
}

function normalizeRegistryEntries(value) {
  if (!Array.isArray(value)) return [];
  const ids = new Set();
  const names = new Set();
  const entries = [];
  for (const raw of value.slice(0, MAX_REGISTERED_CODESPACES)) {
    if (!raw || typeof raw !== "object") continue;
    const id = String(raw.id || "").trim();
    const name = String(raw.name || "").trim();
    const tokenBinding = String(raw.token_binding || "").trim();
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/i.test(id) ||
        !/^[a-z0-9][a-z0-9-]*$/i.test(name) ||
        !/^(?:GITHUB_TOKEN|GITHUB_TOKEN_[A-Z0-9_]+)$/.test(tokenBinding) ||
        ids.has(id) || names.has(name)) continue;
    ids.add(id);
    names.add(name);
    entries.push({
      id,
      name,
      label: String(raw.label || name).trim().slice(0, 96) || name,
      tokenBinding,
      routePorts: normalizeRoutePorts(raw.route_ports),
      priority: normalizePriority(raw.priority),
      enabled: raw.enabled !== false
    });
  }
  return entries;
}

function normalizeRoutePorts(value) {
  const ports = Array.isArray(value) ? value : [DEFAULT_CODESPACE_PORT];
  const result = [];
  for (const raw of ports) {
    const port = Number.parseInt(String(raw), 10);
    if (Number.isInteger(port) && port > 0 && port <= 65535 && !result.includes(port)) result.push(port);
    if (result.length >= 4) break;
  }
  return result.length ? result : [DEFAULT_CODESPACE_PORT];
}

function normalizePriority(value) {
  const parsed = Number.parseInt(String(value), 10);
  return Number.isInteger(parsed) ? Math.max(-1000, Math.min(1000, parsed)) : 0;
}

function selectCodespaceEntry(registry, requestedId) {
  const id = String(requestedId || registry.defaultCodespaceId || "").trim();
  const explicit = registry.entries.find((entry) => entry.id === id);
  if (explicit) return explicit.enabled ? explicit : null;
  return registry.entries.find((entry) => entry.name === id && entry.enabled) || null;
}

function addCodespaceIdentity(data, context) {
  if (!data || !context) return data;
  data.codespace_id = context.codespaceId;
  data.codespace_label = context.codespaceLabel;
  return data;
}

async function rateLimitFailedAuth(request, env) {
  if (!env.WAKER_KV) return null;
  const key = failedAuthKey(request);
  const blocked = {
    ok: false,
    status: 429,
    body: {
      ok: false,
      error: "too_many_failed_wake_secret_attempts",
      reason: "worker_wake_secret_rate_limited",
      retry_after_seconds: FAILED_AUTH_WINDOW_SECONDS
    }
  };
  try {
    const raw = await withKvDeadline(() => env.WAKER_KV.get(key));
    const count = Number.parseInt(raw || "0", 10) || 0;
    // Already at/over the limit: block WITHOUT another KV write, so a single
    // attacker hammering one IP cannot drain the daily free-tier KV write quota.
    if (count >= FAILED_AUTH_MAX_ATTEMPTS) return blocked;
    await withKvDeadline(() => env.WAKER_KV.put(key, String(count + 1), { expirationTtl: FAILED_AUTH_WINDOW_SECONDS }));
    if (count + 1 > FAILED_AUTH_MAX_ATTEMPTS) return blocked;
  } catch {
    return null;
  }
  return null;
}

async function rateLimitSuccessfulWake(codespaceName, env) {
  if (!env.WAKER_KV) return null;
  const seconds = configuredCooldownSeconds(env, "WAKE_COOLDOWN_SECONDS", 0, 3600);
  if (!seconds) return null;
  const key = SUCCESSFUL_WAKE_KEY_PREFIX + encodeURIComponent(codespaceName);
  try {
    const raw = await withKvDeadline(() => env.WAKER_KV.get(key));
    if (!raw) return null;
    return {
      ok: false,
      status: 429,
      body: {
        ok: false,
        status: 429,
        codespace: codespaceName,
        reason: "wake_recently_succeeded",
        retry_after_seconds: seconds,
        message: "A wake request already succeeded recently. Wait before sending another start request."
      }
    };
  } catch {
    return null;
  }
}

async function rememberSuccessfulWake(codespaceName, env, data) {
  if (!env.WAKER_KV || !data || data.ok !== true) return false;
  const seconds = configuredCooldownSeconds(env, "WAKE_COOLDOWN_SECONDS", 0, 3600);
  if (!seconds) return false;
  if (data.route_ready !== true && data.start_accepted !== true) return false;
  const key = SUCCESSFUL_WAKE_KEY_PREFIX + encodeURIComponent(codespaceName);
  try {
    await withKvDeadline(() => env.WAKER_KV.put(key, new Date().toISOString(), { expirationTtl: seconds }));
    return true;
  } catch {
    return false;
  }
}

function failedAuthKey(request) {
  const ip = request.headers.get("cf-connecting-ip")
    || request.headers.get("x-forwarded-for")
    || "unknown";
  let identity = String(ip).split(",")[0].trim() || "unknown";
  if (identity.includes(":")) {
    try {
      // URL validates and canonicalizes IPv6 before grouping rotating interface
      // addresses. This bounds one subnet's KV writes, not a distributed attack.
      const canonical = new URL(`http://[${identity}]/`).hostname.slice(1, -1);
      const [left, right = ""] = canonical.split("::");
      const head = left ? left.split(":") : [];
      const tail = right ? right.split(":") : [];
      const groups = canonical.includes("::")
        ? [...head, ...Array(8 - head.length - tail.length).fill("0"), ...tail]
        : head;
      identity = groups.slice(0, 4).join(":") + "::/64";
    } catch {
      identity = "unknown";
    }
  }
  return FAILED_AUTH_KEY_PREFIX + encodeURIComponent(identity);
}

async function secretsEqual(supplied, expected) {
  if (!supplied || !expected) return false;
  const left = await sha256Hex(String(supplied));
  const right = await sha256Hex(String(expected));
  if (left.length !== right.length) return false;
  let diff = 0;
  for (let i = 0; i < left.length; i += 1) {
    diff |= left.charCodeAt(i) ^ right.charCodeAt(i);
  }
  return diff === 0;
}

async function sha256Hex(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function startCodespaceData(name, token, env, priorStatus = null, routePorts = [codespacePort(env)]) {
  const endpoint = `https://api.github.com/user/codespaces/${encodeURIComponent(name)}/start`;
  let res, body;
  try {
    const result = await githubFetchWithRetry(endpoint, {
      method: "POST",
      headers: githubHeaders(token)
    }, FETCH_TIMEOUT_MS, env);
    res = result.response;
    body = parseBody(result.text);
  } catch (error) {
    return {
      ok: false,
      status: 0,
      codespace: name,
      reason: "github_start_request_unreachable",
      error: shortError(error),
      message: "GitHub start API did not answer before the timeout."
    };
  }

  const githubFailure = githubFailureForResponse(res, body, name);
  if (githubFailure) {
    if (githubFailure.reason === "quota_or_billing_blocked") {
      const status = priorStatus && priorStatus.ok
        ? priorStatus
        : await getCodespaceStatus(name, token, env);
      return {
        ...githubFailure,
        state: status.state || null,
        pending_operation: status.pending_operation ?? null,
        last_used_at: status.last_used_at || null,
        idle_timeout_minutes: status.idle_timeout_minutes || null,
        location: status.location || null,
        retention_period_minutes: status.retention_period_minutes ?? null,
        retention_expires_at: status.retention_expires_at || null,
        route_ready: false,
        route_probe: null
      };
    }
    return githubFailure;
  }

  const accepted = res.ok || res.status === 202 || res.status === 304 || res.status === 409;
  if (!accepted) {
    return {
      ok: false,
      status: res.status,
      codespace: name,
      reason: "github_start_request_failed",
      detail: githubErrorDetail(body),
      message: "GitHub did not accept the Codespace start request."
    };
  }

  // A cold Codespace can need far longer than a mobile HTTP timeout to become
  // Available and publish its forwarded route. Return accepted progress now;
  // the dashboard and Android client poll /api/health with bounded backoff.
  const firstPort = routePorts[0] || codespacePort(env);
  const startBody = body && typeof body === "object" ? body : {};
  return {
    ok: true,
    status: res.status,
    start_accepted: true,
    codespace: name,
    reason: "codespace_start_accepted",
    state: startBody.state || priorStatus?.state || "Starting",
    pending_operation: startBody.pending_operation ?? true,
    last_used_at: startBody.last_used_at || priorStatus?.last_used_at || null,
    idle_timeout_minutes: startBody.idle_timeout_minutes || priorStatus?.idle_timeout_minutes || null,
    location: startBody.location || priorStatus?.location || null,
    retention_period_minutes: startBody.retention_period_minutes ?? priorStatus?.retention_period_minutes ?? null,
    retention_expires_at: startBody.retention_expires_at || priorStatus?.retention_expires_at || null,
    github_wait_ms: 0,
    github_wait_attempts: 0,
    route_check_requested: false,
    route_checked: false,
    route_ready: false,
    route_probe: {
      url: routeUrl(name, firstPort, env),
      usable: false,
      http_status: null,
      latency_ms: null,
      attempts: 0,
      waited_ms: 0,
      stable_probes: 0,
      error: "codespace_starting",
      route_failure_reason: "not_checked"
    },
    route_probes: [],
    preferred_route_port: firstPort,
    retry_after_seconds: 3,
    poll_after_seconds: 3,
    next_action: "GitHub accepted the start request. Wait for the Codespace to finish starting; this page or app will check health again automatically.",
    next_action_code: "wait_codespace_available",
    message: "Codespace start request accepted. Route readiness will be checked separately so this request does not time out."
  };
}

async function getCodespaceStatus(codespaceName, token, env) {
  const endpoint = `https://api.github.com/user/codespaces/${encodeURIComponent(codespaceName)}`;
  let res, body;
  try {
    const result = await githubFetchWithRetry(endpoint, {
      method: "GET",
      headers: githubHeaders(token)
    }, FETCH_TIMEOUT_MS, env);
    res = result.response;
    body = parseBody(result.text);
  } catch (error) {
    return {
      ok: false,
      status: 0,
      codespace: codespaceName,
      reason: "github_status_request_unreachable",
      error: shortError(error),
      message: "GitHub status API did not answer before the timeout."
    };
  }

  const githubFailure = githubFailureForResponse(res, body, codespaceName);
  if (githubFailure) return githubFailure;

  if (!res.ok) {
    return {
      ok: false,
      status: res.status,
      codespace: codespaceName,
      reason: res.status === 404
        ? "codespace_not_found_or_token_cannot_access_it"
        : "github_status_request_failed",
      detail: githubErrorDetail(body),
      message: "GitHub status request failed."
    };
  }

  return {
    ok: true,
    status: res.status,
    codespace: codespaceName,
    state: body && typeof body === "object" ? body.state || null : null,
    pending_operation: body && typeof body === "object" ? Boolean(body.pending_operation) : null,
    last_used_at: body && typeof body === "object" ? body.last_used_at || null : null,
    idle_timeout_minutes: body && typeof body === "object" ? body.idle_timeout_minutes || null : null,
    location: body && typeof body === "object" ? body.location || null : null,
    retention_period_minutes: body && typeof body === "object" ? body.retention_period_minutes ?? null : null,
    retention_expires_at: body && typeof body === "object" ? body.retention_expires_at || null : null,
    message: "Codespace status loaded."
  };
}

function githubFailureForResponse(res, body, codespaceName) {
  const rateFields = githubRateFields(res);
  if (res.status === 429) {
    return {
      ok: false,
      status: res.status,
      codespace: codespaceName,
      reason: "github_rate_limited",
      detail: githubErrorDetail(body),
      ...rateFields,
      message: "GitHub API rate limit reached. Wait for the reset window, then try again."
    };
  }

  if (res.status === 402) {
    return {
      ok: false,
      status: res.status,
      codespace: codespaceName,
      reason: "quota_or_billing_blocked",
      detail: githubErrorDetail(body),
      message: "GitHub quota or billing blocked the Codespace start request."
    };
  }

  if (res.status === 401) {
    return {
      ok: false,
      status: res.status,
      codespace: codespaceName,
      reason: "github_token_rejected_or_missing_scope",
      detail: githubErrorDetail(body),
      token_warning: "GitHub token rejected or expired",
      message: "GitHub token rejected or expired. Rotate the token or add the codespace scope."
    };
  }

  if (res.status === 403) {
    const message = String(body && typeof body === "object" ? body.message || "" : body || "").toLowerCase();
    if (rateFields.github_rate_limit_remaining === 0) {
      return {
        ok: false,
        status: 429,
        codespace: codespaceName,
        reason: "github_rate_limited",
        detail: githubErrorDetail(body),
        ...rateFields,
        message: "GitHub API rate limit reached. Wait for the reset window, then try again."
      };
    }
    if (rateFields.retry_after_seconds || message.includes("secondary rate limit") || message.includes("abuse detection")) {
      return {
        ok: false,
        status: 429,
        codespace: codespaceName,
        reason: "github_secondary_rate_limited",
        detail: githubErrorDetail(body),
        ...rateFields,
        message: "GitHub temporarily throttled this token. Wait, then try again."
      };
    }
    if (
      message.includes("resource not accessible") ||
      message.includes("must have the codespace scope") ||
      message.includes("missing scope") ||
      message.includes("requires codespace")
    ) {
      return {
        ok: false,
        status: res.status,
        codespace: codespaceName,
        reason: "github_token_scope_missing",
        detail: githubErrorDetail(body),
        token_warning: "GitHub token is missing the codespace scope",
        message: "GitHub token was accepted, but it cannot access Codespaces. Create a classic token with the codespace scope."
      };
    }
    return {
      ok: false,
      status: res.status,
      codespace: codespaceName,
      reason: "github_forbidden",
      detail: githubErrorDetail(body),
      message: "GitHub rejected the request with HTTP 403. Check GitHub policy, account access, token permissions, and organization restrictions."
    };
  }

  if (res.status === 404) {
    return {
      ok: false,
      status: res.status,
      codespace: codespaceName,
      reason: "codespace_not_found_or_token_cannot_access_it",
      detail: githubErrorDetail(body),
      message: "Codespace not found, or the token cannot access it."
    };
  }

  return null;
}

function githubRateFields(res) {
  const retryAfter = parseNullableInt(res.headers.get("retry-after"));
  const reset = parseNullableInt(res.headers.get("x-ratelimit-reset"));
  const remaining = parseNullableInt(res.headers.get("x-ratelimit-remaining"));
  const limit = parseNullableInt(res.headers.get("x-ratelimit-limit"));
  const resource = res.headers.get("x-ratelimit-resource") || null;
  return {
    retry_after_seconds: retryAfter,
    retry_after_epoch: reset,
    github_rate_limit_remaining: remaining,
    github_rate_limit_limit: limit,
    github_rate_limit_resource: resource
  };
}

function parseNullableInt(value) {
  if (value == null || value === "") return null;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : null;
}

function githubHeaders(token) {
  return {
    accept: "application/vnd.github+json",
    authorization: `Bearer ${token}`,
    "x-github-api-version": GITHUB_API_VERSION,
    "user-agent": "g2ray-codespace-waker"
  };
}

function responseStatusFor(data) {
  if (data.ok && data.start_accepted && data.route_ready !== true) return 202;
  if (
    data.ok &&
    (data.start_accepted || data.wake_fast_path) &&
    data.route_ready === false &&
    (isRouteSettlingStatus(data.route_probe) || data.route_probe?.error === "route_stability_not_confirmed")
  ) return 202;
  if (data.ok) return 200;
  if ([401, 402, 403, 404, 429].includes(data.status)) return data.status;
  return 502;
}

function applyResponseHints(data, status, env) {
  if (!data || typeof data !== "object") return data;
  if (status === 202 && !data.retry_after_seconds) {
    data.retry_after_seconds = configuredNumber(env, "ROUTE_POLL_AFTER_SECONDS", DEFAULT_ROUTE_POLL_AFTER_SECONDS, 1, 120);
  }
  if ((status === 202 || shouldPollRouteResponse(data)) && !data.poll_after_seconds) {
    data.poll_after_seconds = data.retry_after_seconds || configuredNumber(env, "ROUTE_POLL_AFTER_SECONDS", DEFAULT_ROUTE_POLL_AFTER_SECONDS, 1, 120);
  }
  return data;
}

function shouldPollRouteResponse(data) {
  return Boolean(data && (data.ok || data.start_accepted) && data.route_ready === false && data.quota_blocked !== true);
}

function retryHeaders(data) {
  let retryAfter = data && Number.parseInt(String(data.retry_after_seconds || ""), 10);
  if (data && data.retry_after_epoch &&
      (data.github_rate_limit_remaining == null || data.github_rate_limit_remaining === 0)) {
    const epoch = Number.parseInt(String(data.retry_after_epoch), 10);
    if (Number.isFinite(epoch)) {
      retryAfter = Math.max(Number.isFinite(retryAfter) ? retryAfter : 0, Math.ceil((epoch * 1000 - Date.now()) / 1000));
    }
  }
  if (!Number.isFinite(retryAfter) || retryAfter <= 0) return {};
  return { "retry-after": String(Math.min(retryAfter, 86400)) };
}

function withSurvivalFields(data, env) {
  const now = currentDate(env);
  const quotaBlocked = data.reason === "quota_or_billing_blocked" || Number(data.status) === 402;
  const retentionPeriod = normalizeNullableNumber(data.retention_period_minutes);
  const retentionExpires = data.retention_expires_at || null;
  const retentionRisk = retentionRiskFor(retentionExpires, retentionPeriod, now);
  const resetEstimate = nextMonthlyQuotaResetIso(now);
  const survivalNextAction = survivalNextActionFor({
    ...data,
    quota_blocked: quotaBlocked,
    retention_period_minutes: retentionPeriod,
    retention_expires_at: retentionExpires,
    retention_risk: retentionRisk,
    quota_reset_estimate_utc: resetEstimate
  });
  const next = {
    ...data,
    quota_blocked: quotaBlocked,
    quota_reset_estimate_utc: resetEstimate,
    retention_period_minutes: retentionPeriod,
    retention_expires_at: retentionExpires,
    retention_risk: retentionRisk,
    survival_next_action: survivalNextAction
  };
  if (quotaBlocked) {
    next.route_checked = false;
    next.route_ready = false;
    next.route_probe = null;
    next.next_action = survivalNextAction;
  }
  return next;
}

function withQuotaIncidentResponseFields(data, incident) {
  if (!data || !incident || incident.quota_drought_active !== true) return data;
  if (!incident.quota_reset_estimate_utc) return data;
  const next = {
    ...data,
    quota_drought_active: true,
    quota_reset_estimate_utc: incident.quota_reset_estimate_utc
  };
  next.survival_next_action = survivalNextActionFor(next);
  if (next.quota_blocked === true) {
    next.next_action = next.survival_next_action;
  }
  return next;
}

function currentDate(env, scheduledTime) {
  const candidate = env && env.TEST_NOW_UTC ? Date.parse(env.TEST_NOW_UTC) : Number.NaN;
  if (Number.isFinite(candidate)) return new Date(candidate);
  if (Number.isFinite(scheduledTime)) return new Date(scheduledTime);
  return new Date();
}

function nextMonthlyQuotaResetIso(now) {
  const reset = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1, 0, 0, 0));
  return reset.toISOString().replace(".000Z", "Z");
}

function normalizeNullableNumber(value) {
  if (value === null || value === undefined || value === "") return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function retentionRiskFor(retentionExpiresAt, retentionPeriodMinutes, now) {
  if (!retentionExpiresAt) return retentionPeriodMinutes == null ? "unknown" : "safe";
  const expires = Date.parse(retentionExpiresAt);
  if (!Number.isFinite(expires)) return "unknown";
  const remaining = expires - now.getTime();
  if (remaining <= 3 * 24 * 60 * 60 * 1000) return "urgent";
  if (remaining <= 7 * 24 * 60 * 60 * 1000) return "warning";
  return "safe";
}

function survivalNextActionFor(data) {
  if (data.quota_blocked) {
    return `Quota or billing is blocking starts. If GitHub shows Keep codespace for this Codespace, enable it now, then retry after the estimated quota reset at ${data.quota_reset_estimate_utc}. Verify the exact reset in GitHub Billing.`;
  }
  if (data.reason === "codespace_not_found_or_token_cannot_access_it" || Number(data.status) === 404) {
    return "Codespace is missing or the token cannot access it. If GitHub deleted it, create a new Codespace and generate new configs.";
  }
  if (data.retention_risk === "urgent" || data.retention_risk === "warning") {
    return "If GitHub shows Keep codespace for this Codespace, enable it so the same domain/configs can survive until quota resets. Org retention policy may override this.";
  }
  if (data.retention_risk === "unknown") {
    return "Open GitHub Codespaces and confirm this Codespace can be marked Keep before quota runs out.";
  }
  return "No quota survival action needed now. For best safety, use Keep codespace if GitHub offers it before quota runs out.";
}

function isRouteSettlingStatus(routeProbe) {
  const status = Number(routeProbe && routeProbe.http_status || 0);
  return status === 0 || status === 404;
}

function isRouteReadyProbe(routeProbe) {
  return Boolean(
    routeProbe &&
    routeProbe.usable === true &&
    Number(routeProbe.stable_probes || 0) >= ROUTE_READY_STABLE_PROBES &&
    !routeProbe.error
  );
}

function isCodespaceAvailable(status) {
  const state = String(status && status.state || "").toLowerCase();
  return Boolean(status && status.ok && (state === "available" || state === "running") && status.pending_operation !== true);
}

function nextActionForWake(status, routeProbe) {
  if (status.reason === "github_rate_limited" || status.reason === "github_secondary_rate_limited") {
    return "GitHub is throttling this token. Wait for the retry/reset window, then press Check Health or Start Codespace again.";
  }
  if (status.reason === "github_token_rejected_or_missing_scope") {
    return "Rotate the GitHub token and make sure it has the codespace scope.";
  }
  if (status.reason === "github_token_scope_missing") {
    return "Create a new classic GitHub token with the codespace scope, then update Worker secret GITHUB_TOKEN.";
  }
  if (status.reason === "github_forbidden") {
    return "Check GitHub policy or access for this account and Codespace. Rotate the token only if GitHub says the token is invalid or missing the codespace scope.";
  }
  if (!status.ok) return "Open GitHub Codespaces or rotate the GitHub token, then try the Worker again.";
  if (!isCodespaceAvailable(status)) return "Wait for GitHub to finish starting the Codespace, then press Check Health.";
  if (routeProbe.error === "route_stability_not_confirmed") {
    return "The route answered once, but stability was not confirmed yet. Wait a few seconds, then press Check Health or Start Codespace again.";
  }
  if (routeProbe.usable) return "Try the same VLESS config again.";
  if (routeProbe.route_failure_reason === "edge_or_origin_error") return "GitHub's edge or origin returned a server error. Wait briefly, then Check Health; if it persists, open the panel and Recover Now.";
  if (routeProbe.route_failure_reason === "timeout_or_unreachable" || routeProbe.route_failure_reason === "dns_tls_or_network_unreachable") return "The app.github.dev route did not answer from Cloudflare. Open the Codespace once, then check port visibility and panel diagnostics.";
  if (routeProbe.http_status === 404) return "The Codespace is available, but the app.github.dev route is still 404. Headless post-start recovery should keep repairing it; press Check Health again shortly. Open the panel only if it stays 404 for several minutes.";
  if (routeProbe.http_status === 0) return "The app.github.dev route did not resolve or answer. Open the Codespace once, then check port 443 visibility and panel diagnostics.";
  return "Check panel option 14 Diagnostics; if XHTTP is not usable, use option 6 Recover Now.";
}

function nextActionCodeFor(status, routeProbe) {
  if (status.quota_blocked || status.reason === "quota_or_billing_blocked" || Number(status.status) === 402) {
    return "wait_for_quota_reset";
  }
  if (status.reason === "github_rate_limited" || status.reason === "github_secondary_rate_limited") {
    return "wait_github_rate_limit";
  }
  if (status.reason === "github_token_rejected_or_missing_scope" || status.reason === "github_token_scope_missing") {
    return "rotate_github_token";
  }
  if (status.reason === "github_forbidden") {
    return "check_github_policy_or_access";
  }
  if (status.reason === "codespace_not_found_or_token_cannot_access_it" || Number(status.status) === 404) {
    return "codespace_missing_or_inaccessible";
  }
  if (!status.ok) return "check_github_or_token";
  if (!isCodespaceAvailable(status)) return "wait_codespace_available";
  if (routeProbe.error === "route_stability_not_confirmed") return "wait_route_stability";
  if (routeProbe.usable) return "retry_vless_config";
  if (routeProbe.route_failure_reason === "edge_or_origin_error") return "wait_or_recover_route";
  if (routeProbe.route_failure_reason === "timeout_or_unreachable" || routeProbe.route_failure_reason === "dns_tls_or_network_unreachable") {
    return "open_codespace_check_port";
  }
  if (routeProbe.http_status === 404) return "wait_route_or_recover";
  if (routeProbe.http_status === 0) return "check_dns_port_visibility";
  return "inspect_panel_diagnostics";
}

function healthMessage(status, routeProbe) {
  if (status.token_warning) return status.message;
  if (!status.ok) return status.message || "GitHub status is not available.";
  if (!isCodespaceAvailable(status)) {
    return "Codespace is shut down or still starting, so the app.github.dev route was not probed.";
  }
  if (isRouteReadyProbe(routeProbe)) return "Codespace is available and the XHTTP route is usable.";
  if (routeProbe.error === "route_stability_not_confirmed") {
    return "Codespace is available, but the XHTTP route answered only once and still needs another stable probe.";
  }
  if (routeProbe.http_status === 404) {
    return "Codespace exists, but the app.github.dev route is still settling or not routed.";
  }
  if (routeProbe.http_status === 0) {
    return "Codespace status loaded, but the app.github.dev route did not answer.";
  }
  return "Codespace status loaded, but the XHTTP route is not usable yet.";
}

function eventFromResult(kind, codespace, data) {
  const routeChecked = data.route_checked === false ? false : Boolean(data.route_probe);
  const routeReady = routeChecked && data.route_ready != null ? data.route_ready === true : null;
  const routeProbe = routeChecked ? data.route_probe : null;
  const routeProbeDuration = routeProbe ? routeProbe.latency_ms : null;
  return {
    ts: new Date().toISOString(),
    kind,
    ok: Boolean(data.ok),
    codespace_id: data.codespace_id || null,
    codespace,
    github_status: data.status || null,
    state: data.state || null,
    route_checked: routeChecked,
    route_ready: routeReady,
    route_http_status: routeProbe ? routeProbe.http_status : null,
    route_latency_ms: routeReady === true ? routeProbeDuration : null,
    route_probe_duration_ms: routeProbeDuration,
    route_waited_ms: routeProbe ? routeProbe.waited_ms : null,
    route_attempts: routeProbe ? routeProbe.attempts : null,
    reason: data.reason || null,
    next_action_code: data.next_action_code || null,
    quota_blocked: data.quota_blocked === true,
    quota_reset_estimate_utc: data.quota_reset_estimate_utc || null,
    retention_expires_at: data.retention_expires_at || null,
    retention_risk: data.retention_risk || null,
    token_warning: data.token_warning || null,
    message: data.message || null
  };
}

async function enrichEventWithHistoryContext(env, event) {
  if (!env.WAKER_KV || event.kind !== "health") return event;
  try {
    const existing = await readHistory(env, event.codespace);
    const previousRouteState = existing.find((item) => item && typeof item.route_ready === "boolean");
    const previousWasStuck =
      previousRouteState &&
      previousRouteState.route_ready === false &&
      (
        previousRouteState.route_http_status === 404 ||
        previousRouteState.route_http_status === 0 ||
        previousRouteState.route_failure_reason === "route_settling_404"
      );
    if (event.route_ready === true && previousWasStuck) {
      return {
        ...event,
        route_ready_transition: true,
        previous_route_http_status: previousRouteState.route_http_status ?? null
      };
    }
  } catch {
    return event;
  }
  return event;
}

async function queueHistorySideEffects(env, event, data, ctx) {
  if (!env.WAKER_KV) {
    return {
      history_recorded: false,
      quota_incident_recorded: false,
      quota_incident: null,
      deferred: false
    };
  }

  if (ctx && typeof ctx.waitUntil === "function") {
    ctx.waitUntil(Promise.all([
      recordQuotaIncident(env, event, data),
      recordHistory(env, { ...event, history_recorded_at: new Date().toISOString() })
    ]).catch((error) => {
      console.warn("history_side_effect_failed", shortError(error));
    }));
    return {
      history_recorded: false,
      quota_incident_recorded: false,
      quota_incident: null,
      deferred: true
    };
  }

  const quotaIncident = await recordQuotaIncident(env, event, data);
  const historyRecorded = await recordHistory(env, { ...event, history_recorded_at: new Date().toISOString() });
  return {
    history_recorded: historyRecorded,
    quota_incident_recorded: quotaIncident.recorded,
    quota_incident: quotaIncident.incident,
    deferred: false
  };
}

async function recordHistory(env, event) {
  if (!env.WAKER_KV) return false;

  try {
    const existing = await readHistory(env, event.codespace);
    if (!shouldStoreHistoryEvent(env, event, existing)) return true;
    const next = [event, ...existing].slice(0, HISTORY_LIMIT);
    await withKvDeadline(() => env.WAKER_KV.put(historyKey(event.codespace), JSON.stringify(next)));
    return true;
  } catch {
    return false;
  }
}

function shouldStoreHistoryEvent(env, event, existing) {
  if (event.kind !== "health") return true;
  const previous = Array.isArray(existing) ? existing[0] : null;
  if (!previous || previous.kind !== "health") return true;
  if (event.route_ready_transition) return true;
  const same =
    previous.ok === event.ok &&
    previous.state === event.state &&
    previous.reason === event.reason &&
    previous.route_ready === event.route_ready &&
    previous.route_http_status === event.route_http_status &&
    previous.next_action_code === event.next_action_code;
  if (!same) return true;
  const sampleMs = configuredMs(env, "HEALTH_HISTORY_SAMPLE_MS", 300000, 0, 3600000);
  if (sampleMs === 0) return false;
  const previousTs = Date.parse(previous.ts || "");
  const eventTs = Date.parse(event.ts || "");
  if (!Number.isFinite(previousTs) || !Number.isFinite(eventTs)) return true;
  return eventTs - previousTs >= sampleMs;
}

async function recordQuotaIncident(env, event, data, cronFields = null) {
  if (!env.WAKER_KV) return { recorded: false, incident: null };
  try {
    const nowIso = currentDate(env).toISOString();
    const existing = await readQuotaIncident(env, event.codespace) || {};
    const existingResetEstimate = existing.quota_reset_estimate_utc || null;
    const incomingResetEstimate = data.quota_reset_estimate_utc || null;
    const quotaResetEstimate = existing.quota_drought_active === true && existingResetEstimate
      ? existingResetEstimate
      : incomingResetEstimate || existingResetEstimate;
    const incident = {
      ...existing,
      codespace: event.codespace,
      last_observed_at: nowIso,
      quota_reset_estimate_utc: quotaResetEstimate,
      retention_period_minutes: data.retention_period_minutes ?? existing.retention_period_minutes ?? null,
      retention_expires_at: data.retention_expires_at || existing.retention_expires_at || null,
      retention_risk: data.retention_risk || existing.retention_risk || "unknown",
      ...cronFields
    };

    if (data.quota_blocked) {
      incident.first_quota_blocked_at = incident.first_quota_blocked_at || nowIso;
      incident.latest_quota_blocked_at = nowIso;
      incident.quota_drought_active = true;
      incident.same_codespace_exists = data.reason === "codespace_not_found_or_token_cannot_access_it" ? false : true;
    } else if (data.ok) {
      incident.same_codespace_exists = true;
      if ((event.kind === "wake" || event.kind === "cron_wake") && data.start_accepted === true) {
        incident.last_successful_start_at = nowIso;
      }
      if (event.kind === "health" || event.kind === "cron_health") {
        incident.last_successful_health_at = nowIso;
      }
      const isWakeRecovery = (event.kind === "wake" || event.kind === "cron_wake") &&
        (data.start_accepted === true || data.route_ready === true);
      const resetMs = Date.parse(incident.quota_reset_estimate_utc || "");
      const nowMs = Date.parse(nowIso);
      const resetHasPassed = !Number.isFinite(resetMs) || (Number.isFinite(nowMs) && nowMs >= resetMs);
      const isPostResetRouteReadyHealth =
        (event.kind === "health" || event.kind === "cron_health") &&
        data.route_ready === true &&
        resetHasPassed;
      if (isWakeRecovery || isPostResetRouteReadyHealth) {
        incident.quota_drought_active = false;
      } else if (incident.quota_drought_active !== true) {
        incident.quota_drought_active = false;
      }
    } else if (data.reason === "codespace_not_found_or_token_cannot_access_it") {
      incident.same_codespace_exists = false;
    }

    if (!shouldWriteQuotaIncident(env, existing, incident, nowIso)) {
      return { recorded: false, incident: Object.keys(existing).length ? existing : incident };
    }

    await withKvDeadline(() => env.WAKER_KV.put(quotaIncidentKey(event.codespace), JSON.stringify(incident)));
    return { recorded: true, incident };
  } catch {
    return { recorded: false, incident: null };
  }
}

function shouldWriteQuotaIncident(env, existing, incident, nowIso) {
  if (!existing || Object.keys(existing).length === 0) return true;
  if (!existing.last_successful_start_at && incident.last_successful_start_at) return true;

  const stateFields = [
    "codespace",
    "quota_reset_estimate_utc",
    "retention_period_minutes",
    "retention_expires_at",
    "retention_risk",
    "quota_drought_active",
    "same_codespace_exists",
    "first_quota_blocked_at",
    "last_cron_check_at",
    "last_cron_wake_at",
    "last_cron_post_reset_wake_at"
  ];
  if (stateFields.some((field) => existing[field] !== incident[field])) return true;

  const sampleMs = configuredMs(
    env,
    "QUOTA_INCIDENT_SAMPLE_MS",
    900000,
    60000,
    86400000
  );
  const previousMs = Date.parse(existing.last_observed_at || "");
  const currentMs = Date.parse(nowIso || "");
  return !Number.isFinite(previousMs) ||
    !Number.isFinite(currentMs) ||
    currentMs - previousMs >= sampleMs;
}

async function readHistory(env, codespace) {
  if (!env.WAKER_KV) return [];

  try {
    const text = await withKvDeadline(() => env.WAKER_KV.get(historyKey(codespace)));
    const parsed = text ? JSON.parse(text) : [];
    return Array.isArray(parsed) ? parsed.slice(0, HISTORY_LIMIT) : [];
  } catch {
    return [];
  }
}

async function readQuotaIncident(env, codespace) {
  if (!env.WAKER_KV) return null;

  try {
    const text = await withKvDeadline(() => env.WAKER_KV.get(quotaIncidentKey(codespace)));
    const parsed = text ? JSON.parse(text) : null;
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function historyKey(codespace) {
  const suffix = String(codespace || "unknown").trim() || "unknown";
  return HISTORY_KEY_PREFIX + encodeURIComponent(suffix);
}

function quotaIncidentKey(codespace) {
  const suffix = String(codespace || "unknown").trim() || "unknown";
  return QUOTA_INCIDENT_KEY_PREFIX + encodeURIComponent(suffix);
}

function quotaSurvivalCronEnabled(env) {
  const value = String(env && env.QUOTA_SURVIVAL_CRON_ENABLED || "").trim().toLowerCase();
  return value === "1" || value === "true" || value === "yes";
}

async function handleQuotaSurvivalCron(controller, env) {
  const now = currentDate(env, controller && controller.scheduledTime);
  if (!env.WAKER_KV) return { ok: false, skipped: "missing_kv" };
  const registry = await readCodespaceRegistry(env);
  const selected = await selectDueQuotaCronCodespace(env, registry, now);
  if (!selected) return { ok: true, skipped: "no_due_quota_drought" };

  const { entry, incident } = selected;
  const codespaceName = entry.name;

  const nearReset = quotaCronNearReset(incident, now);
  if (nearReset && !quotaCronWakeAllowed(incident, now)) {
    if (resultNeedsCronStamp(incident, now)) {
      incident.last_cron_check_at = now.toISOString();
      await withKvDeadline(() => env.WAKER_KV.put(quotaIncidentKey(codespaceName), JSON.stringify(incident)));
    }
    return { ok: true, skipped: "quota_cron_near_reset_wake_already_attempted" };
  }
  const data = withSurvivalFields(
    nearReset
      ? await startCodespaceData(codespaceName, String(env[entry.tokenBinding] || ""), env, null, entry.routePorts)
      : await getCodespaceStatus(codespaceName, String(env[entry.tokenBinding] || ""), env),
    env
  );
  addCodespaceIdentity(data, { codespaceId: entry.id, codespaceLabel: entry.label });
  const event = eventFromResult(nearReset ? "cron_wake" : "cron_health", codespaceName, data);
  await recordHistory(env, {
    ...event,
    history_recorded_at: now.toISOString()
  });
  const cronFields = { last_cron_check_at: now.toISOString() };
  if (nearReset) {
    if (quotaCronPostReset(incident, now)) {
      cronFields.last_cron_post_reset_wake_at = now.toISOString();
      cronFields.last_cron_post_reset_wake_reset_estimate_utc = incident.quota_reset_estimate_utc || null;
    } else {
      cronFields.last_cron_wake_at = now.toISOString();
      cronFields.last_cron_wake_reset_estimate_utc = incident.quota_reset_estimate_utc || null;
    }
  }
  // Persist the observation and scheduler stamps together: one write per key,
  // also avoiding KV's one-write-per-second same-key limit.
  await recordQuotaIncident(env, event, data, cronFields);
  return { ok: true, near_reset: nearReset, quota_blocked: data.quota_blocked === true };
}

async function selectDueQuotaCronCodespace(env, registry, now) {
  const entries = registry.entries.filter((entry) => entry.enabled && String(env[entry.tokenBinding] || "").trim());
  if (!entries.length) return null;
  let cursor = 0;
  try {
    cursor = Number.parseInt(await withKvDeadline(() => env.WAKER_KV.get(QUOTA_CRON_CURSOR_KEY)) || "0", 10) || 0;
  } catch {}

  for (let offset = 0; offset < entries.length; offset += 1) {
    const index = (cursor + offset) % entries.length;
    const entry = entries[index];
    const incident = await readQuotaIncident(env, entry.name);
    if (!incident || incident.quota_drought_active !== true || !quotaCronCheckAllowed(incident, now)) continue;
    try {
      await withKvDeadline(() => env.WAKER_KV.put(QUOTA_CRON_CURSOR_KEY, String((index + 1) % entries.length)));
    } catch {}
    return { entry, incident };
  }
  return null;
}

function quotaCronCheckAllowed(incident, now) {
  const last = Date.parse(incident.last_cron_check_at || "");
  if (!Number.isFinite(last)) return true;
  const interval = quotaCronNearReset(incident, now)
    ? QUOTA_CRON_NEAR_RESET_INTERVAL_MS
    : QUOTA_CRON_DAILY_INTERVAL_MS;
  return now.getTime() - last >= interval;
}

function quotaCronNearReset(incident, now) {
  const reset = Date.parse(incident.quota_reset_estimate_utc || "");
  if (!Number.isFinite(reset)) return false;
  return now.getTime() >= reset - QUOTA_CRON_NEAR_RESET_WINDOW_MS;
}

function quotaCronPostReset(incident, now) {
  const reset = Date.parse(incident.quota_reset_estimate_utc || "");
  return Number.isFinite(reset) && now.getTime() >= reset;
}

function quotaCronWakeAllowed(incident, now) {
  const resetEstimate = incident.quota_reset_estimate_utc || null;
  if (quotaCronPostReset(incident, now)) {
    return true;
  }
  return incident.last_cron_wake_reset_estimate_utc !== resetEstimate;
}

function resultNeedsCronStamp(incident, now) {
  const last = Date.parse(incident.last_cron_check_at || "");
  return !Number.isFinite(last) || now.getTime() - last >= QUOTA_CRON_NEAR_RESET_INTERVAL_MS;
}

async function sendNotifications(env, event) {
  if (!shouldNotify(event)) return [];
  if (!hasNotificationChannels(env)) return [];

  const text = notificationText(event);
  const tasks = [];

  if (env.DISCORD_WEBHOOK_URL) {
    tasks.push(
      fetchWithTimeout(env.DISCORD_WEBHOOK_URL, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ content: text })
      }, FETCH_TIMEOUT_MS).then((res) => res.ok ? null : `discord_http_${res.status}`)
        .catch((error) => `discord_${shortError(error)}`)
    );
  }

  if (env.TELEGRAM_BOT_TOKEN && env.TELEGRAM_CHAT_ID) {
    const endpoint = `https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage`;
    tasks.push(
      fetchWithTimeout(endpoint, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          chat_id: env.TELEGRAM_CHAT_ID,
          text,
          disable_web_page_preview: true
        })
      }, FETCH_TIMEOUT_MS).then((res) => res.ok ? null : `telegram_http_${res.status}`)
        .catch((error) => `telegram_${shortError(error)}`)
    );
  }

  const results = await Promise.all(tasks);
  return results.filter(Boolean);
}

async function queueNotifications(env, event, ctx) {
  if (!shouldNotify(event)) return { status: "none", deferred: false, errors: [] };
  if (!hasNotificationChannels(env)) return { status: "none", deferred: false, errors: [] };

  const task = sendNotifications(env, event).then((errors) => {
    if (errors.length) {
      console.warn("notification_errors", errors.join(","));
    }
    return errors;
  });

  if (ctx && typeof ctx.waitUntil === "function") {
    ctx.waitUntil(task.catch((error) => {
      console.warn("notification_failed", shortError(error));
    }));
    return { status: "deferred", deferred: true, errors: [] };
  }

  const errors = await task;
  return { status: errors.length ? "failed" : "sent", deferred: false, errors };
}

function hasNotificationChannels(env) {
  return Boolean(env.DISCORD_WEBHOOK_URL || (env.TELEGRAM_BOT_TOKEN && env.TELEGRAM_CHAT_ID));
}

function shouldNotify(event) {
  if (event.reason === "github_token_rejected_or_missing_scope" || event.reason === "github_token_scope_missing") return true;
  if (event.route_ready_transition) return true;
  if (event.kind !== "wake") return false;
  if (event.route_ready) return true;
  return event.route_http_status === 404 || !event.ok;
}

function notificationText(event) {
  const route = event.route_http_status
    ? `route HTTP ${event.route_http_status}${event.route_latency_ms === null ? "" : `, ${event.route_latency_ms}ms`}`
    : "route not checked";
  return [
    `G2ray Codespace Waker: ${event.kind}`,
    `codespace=${event.codespace}`,
    `ok=${event.ok}`,
    `state=${event.state || "unknown"}`,
    `route_ready=${event.route_ready}`,
    route,
    event.reason ? `reason=${event.reason}` : null,
    event.route_ready_transition ? `transition=route_ready_after_${event.previous_route_http_status || "stuck"}` : null,
    event.token_warning ? `warning=${event.token_warning}` : null,
    event.message ? `message=${event.message}` : null
  ].filter(Boolean).join("\n");
}

function shortError(error) {
  return error && error.message ? String(error.message).slice(0, 80) : "failed";
}

async function readSuppliedSecret(request) {
  const auth = bearerSecret(request);
  if (auth) return auth;

  const contentType = request.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    try {
      const body = await request.clone().json();
      return body && typeof body === "object" ? String(body.wake_secret || "") : "";
    } catch {
      return "";
    }
  }

  if (contentType.includes("application/x-www-form-urlencoded") || contentType.includes("multipart/form-data")) {
    try {
      const form = await request.clone().formData();
      return String(form.get("wake_secret") || "");
    } catch {
      return "";
    }
  }

  return "";
}

function bearerSecret(request) {
  const auth = request.headers.get("authorization") || "";
  const match = auth.match(/^Bearer\s+(.+)$/i);
  return match ? match[1] : "";
}

function parseBody(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text.slice(0, 1000);
  }
}

function githubErrorDetail(body) {
  if (!body || typeof body !== "object") return null;
  return {
    message: body.message || null,
    documentation_url: body.documentation_url || null
  };
}

function codespacePort(env) {
  const value = Number.parseInt(String(env.CODESPACE_PORT || DEFAULT_CODESPACE_PORT), 10);
  return Number.isInteger(value) && value > 0 && value <= 65535 ? value : DEFAULT_CODESPACE_PORT;
}

function configuredMs(env, name, fallback, min, max) {
  const value = Number.parseInt(String(env && env[name] != null ? env[name] : fallback), 10);
  if (!Number.isInteger(value)) return fallback;
  if (value < min) return min;
  if (value > max) return max;
  return value;
}

function configuredNumber(env, name, fallback, min, max) {
  return configuredMs(env, name, fallback, min, max);
}

function configuredCooldownSeconds(env, name, fallback, max) {
  const value = configuredNumber(env, name, fallback, 0, max);
  return value > 0 && value < 60 ? 60 : value;
}

async function probeCodespaceRoutes(name, routePorts, env, options = {}) {
  const ports = normalizeRoutePorts(routePorts);
  const probes = await Promise.all(ports.map(async (port, index) => {
    const startedAt = Date.now();
    // Only the primary XHTTP route gates readiness. An optional WS endpoint
    // cannot pass OPTIONS, so do not spend a full settling loop on it.
    let probe;
    if (index > 0) {
      probe = await probeXhttpRoute(name, port, 1, startedAt, env);
    } else if (options.warm === true) {
      const firstProbe = await probeXhttpRoute(name, port, 1, startedAt, env);
      // Confirm concurrently with optional diagnostics, on this port's budget.
      probe = firstProbe.usable
        ? await waitForXhttpRoute(name, port, env, firstProbe, startedAt)
        : firstProbe;
    } else {
      probe = await waitForXhttpRoute(name, port, env);
    }
    return { ...probe, port };
  }));
  const primary = probes.find((probe) => probe.port === ports[0]) || probes[0];
  const primaryReady = isRouteReadyProbe(primary);
  const preferred = probes.find((probe) => isRouteReadyProbe(probe))
    || probes.find((probe) => probe.usable)
    || probes[0];
  return {
    // The first configured port is the primary XHTTP route. Optional ports
    // (for example the WS fallback on 8443) are reported and can be selected
    // as alternatives, but must not make the primary XHTTP health claim true.
    route_ready: primaryReady,
    route_probe: primaryReady ? preferred : primary,
    route_probes: probes,
    preferred_route_port: preferred?.port || ports[0],
    primary_route_port: ports[0]
  };
}

async function waitForXhttpRoute(name, port, env, initialProbe = null, startedAt = Date.now()) {
  const routeWaitMs = configuredMs(env, "ROUTE_WAIT_MS", ROUTE_WAIT_MS, 1, 300000);
  const pollIntervalMs = configuredMs(env, "ROUTE_POLL_INTERVAL_MS", ROUTE_POLL_INTERVAL_MS, 1, 60000);
  const stableSleepMs = configuredMs(env, "ROUTE_READY_STABLE_SLEEP_MS", ROUTE_READY_STABLE_SLEEP_MS, 0, 10000);
  const deadline = startedAt + routeWaitMs;
  let attempts = 0;
  let stableProbes = 0;
  let last = {
    url: routeUrl(name, port, env),
    usable: false,
    http_status: 0,
    latency_ms: null,
    attempts,
    waited_ms: 0,
    stable_probes: stableProbes,
    error: "not_checked"
  };

  while (attempts === 0 || Date.now() < deadline) {
    attempts += 1;
    last = initialProbe
      ? { ...initialProbe, attempts, waited_ms: Date.now() - startedAt }
      : await probeXhttpRoute(name, port, attempts, startedAt, env);
    initialProbe = null;
    if (last.usable) {
      stableProbes += 1;
      last.stable_probes = stableProbes;
      if (stableProbes >= ROUTE_READY_STABLE_PROBES) return last;
      if (stableSleepMs > 0) {
        const delay = Math.min(stableSleepMs, Math.max(0, deadline - Date.now()));
        if (delay > 0) await sleep(delay);
      }
      continue;
    }
    stableProbes = 0;
    last.stable_probes = stableProbes;

    if (Date.now() + pollIntervalMs > deadline) break;
    await sleep(pollIntervalMs);
  }

  if (last.usable && stableProbes < ROUTE_READY_STABLE_PROBES) {
    return {
      ...last,
      stable_probes: stableProbes,
      error: "route_stability_not_confirmed",
      route_failure_reason: "route_stability_not_confirmed"
    };
  }
  return last;
}

async function probeXhttpRoute(name, port, attempts = 1, startedAt = Date.now(), env = {}) {
  const url = routeUrl(name, port, env);
  const probeStartedAt = Date.now();
  try {
    const timeoutMs = configuredMs(env, "ROUTE_FETCH_TIMEOUT_MS", ROUTE_FETCH_TIMEOUT_MS, 100, 30000);
    const res = await fetchWithTimeout(url, { method: "OPTIONS", redirect: "manual" }, timeoutMs);
    return {
      url,
      usable: isRouteStatusUsable(res.status),
      http_status: res.status,
      latency_ms: Date.now() - probeStartedAt,
      attempts,
      waited_ms: Date.now() - startedAt,
      error: null,
      route_failure_reason: routeFailureReason(res.status, null, res.headers.get("location"))
    };
  } catch (error) {
    const reason = routeFailureReason(0, error);
    return {
      url,
      usable: false,
      http_status: 0,
      latency_ms: Date.now() - probeStartedAt,
      attempts,
      waited_ms: Date.now() - startedAt,
      error: shortError(error),
      route_failure_reason: reason
    };
  }
}

async function fetchWithTimeout(input, init = {}, timeoutMs = FETCH_TIMEOUT_MS, consumeResponse = null) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(input, {
      ...init,
      signal: controller.signal
    });
    // Keep the deadline alive through GitHub's body read. A response header
    // alone does not complete an API request. Status-only probes discard the
    // unused body so redirects/errors do not retain Worker connection slots.
    if (consumeResponse) return await consumeResponse(response);
    try { await response.body?.cancel(); } catch {}
    return response;
  } finally {
    clearTimeout(timer);
  }
}

async function githubFetchWithRetry(input, init = {}, timeoutMs = FETCH_TIMEOUT_MS, env = {}) {
  // A failed POST can still have started the Codespace. Only retry reads here;
  // callers can reconcile an uncertain start using the status endpoint.
  const method = String(init.method || "GET").toUpperCase();
  const attempts = method === "GET" || method === "HEAD"
    ? configuredNumber(env, "GITHUB_API_RETRY_ATTEMPTS", 2, 1, 4)
    : 1;
  let lastError = null;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const result = await fetchWithTimeout(input, init, timeoutMs, async (response) => {
        if (attempt < attempts && shouldRetryGithubResponse(response)) {
          try { await response.body?.cancel(); } catch {}
          return { response, text: "" };
        }
        return { response, text: await response.text() };
      });
      if (attempt < attempts && shouldRetryGithubResponse(result.response)) {
        await sleep(githubRetryDelayMs(env, attempt));
        continue;
      }
      return result;
    } catch (error) {
      lastError = error;
      if (attempt >= attempts) break;
      await sleep(githubRetryDelayMs(env, attempt));
    }
  }
  throw lastError || new Error("github_fetch_failed");
}

function shouldRetryGithubResponse(response) {
  const status = Number(response && response.status || 0);
  return status === 502 || status === 503 || status === 504;
}

function githubRetryDelayMs(env, attempt) {
  const base = configuredMs(env, "GITHUB_API_RETRY_BACKOFF_MS", 500, 0, 5000);
  return Math.min(5000, base * Math.max(1, attempt));
}

function routeUrl(name, port, env = {}) {
  return `https://${name}-${port}.${codespacesForwardingDomain(env)}/`;
}

function codespacesForwardingDomain(env = {}) {
  const raw = env.CODESPACE_FORWARDING_DOMAIN
    || env.CODESPACE_PORT_FORWARDING_DOMAIN
    || env.GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN
    || "app.github.dev";
  const value = String(raw)
    .trim()
    .replace(/^https?:\/\//i, "")
    .replace(/\/.*$/, "")
    .replace(/\.+$/, "")
    .toLowerCase();
  if (!/^[a-z0-9.-]+$/.test(value) || !value.includes(".")) return "app.github.dev";
  return value;
}

function isRouteStatusUsable(status) {
  // The pinned Xray XHTTP handler returns 200 for OPTIONS before parsing a
  // tunnel request. HTTP 400 is an error/generic edge response, not readiness.
  return status === 200;
}

function routeFailureReason(status, error, location = null) {
  if (isRouteStatusUsable(status)) return "ready";
  if (status === 404) return "route_settling_404";
  if (status === 0) {
    const message = shortError(error).toLowerCase();
    if (message.includes("abort") || message.includes("time") || message.includes("timeout")) return "timeout_or_unreachable";
    return "dns_tls_or_network_unreachable";
  }
  if (status >= 500) return "edge_or_origin_error";
  if (status === 401 || status === 403) return "auth_or_visibility_blocked";
  if (status >= 300 && status < 400) {
    try {
      const target = new URL(location);
      if (target.protocol === "https:" && target.hostname === "github.com" &&
          (target.pathname === "/login" || target.pathname.startsWith("/login/"))) {
        return "auth_or_visibility_blocked";
      }
    } catch {}
    return "unexpected_redirect";
  }
  return "unexpected_http_status";
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: securityHeaders({
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...extraHeaders
    }, "json")
  });
}

function html(markup, status = 200) {
  return new Response(markup, {
    status,
    headers: securityHeaders({
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store"
    }, "html")
  });
}

function securityHeaders(headers, kind) {
  const csp = kind === "html"
    ? "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; img-src 'self' data:; connect-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'"
    : "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
  return {
    ...headers,
    "content-security-policy": csp,
    "permissions-policy": "camera=(), geolocation=(), microphone=()",
    "referrer-policy": "no-referrer",
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY"
  };
}

function renderDashboard() {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>G2ray Codespace Waker</title>
<style>
  :root {
    color-scheme: dark;
    --bg: #101214;
    --panel: #181c20;
    --panel-2: #20262c;
    --text: #eef2f4;
    --muted: #9ca8b3;
    --good: #22c55e;
    --warn: #f59e0b;
    --bad: #ef4444;
    --line: #303842;
    --accent: #38bdf8;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--text);
    font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    line-height: 1.45;
  }
  main {
    width: min(900px, 100%);
    margin: 0 auto;
    padding: 20px;
  }
  h1 { margin: 0 0 6px; font-size: 30px; letter-spacing: 0; }
  h2 { margin: 0 0 12px; font-size: 18px; letter-spacing: 0; }
  h3 { margin: 16px 0 10px; font-size: 15px; letter-spacing: 0; }
  p { margin: 0 0 14px; color: var(--muted); }
  .card {
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: 8px;
    padding: 16px;
    margin: 14px 0;
  }
  label { display: block; color: var(--muted); font-size: 14px; margin-bottom: 8px; }
  input, select {
    width: 100%;
    border: 1px solid var(--line);
    border-radius: 6px;
    background: #0b0d0f;
    color: var(--text);
    font: inherit;
    padding: 12px;
  }
  .actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
    margin-top: 12px;
  }
  button {
    min-height: 46px;
    border: 0;
    border-radius: 6px;
    background: var(--panel-2);
    color: var(--text);
    font: inherit;
    font-weight: 700;
    cursor: pointer;
  }
  button.primary { background: var(--accent); color: #061017; }
  button:disabled { cursor: wait; opacity: .6; }
  .grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .metric {
    background: var(--panel-2);
    border-radius: 6px;
    padding: 12px;
    min-height: 78px;
  }
  .metric span { display: block; color: var(--muted); font-size: 12px; }
  .metric strong { display: block; margin-top: 6px; font-size: 18px; word-break: break-word; }
  .good { color: var(--good); }
  .warn { color: var(--warn); }
  .bad { color: var(--bad); }
  ol { margin: 0; padding-left: 22px; color: var(--muted); }
  li.active { color: var(--accent); }
  li.done { color: var(--good); }
  pre {
    white-space: pre-wrap;
    word-break: break-word;
    background: #0b0d0f;
    border: 1px solid var(--line);
    border-radius: 6px;
    padding: 12px;
    overflow: auto;
  }
  .history {
    display: grid;
    gap: 8px;
  }
  .event {
    border: 1px solid var(--line);
    border-radius: 6px;
    padding: 10px;
    color: var(--muted);
  }
  .trend-row {
    display: grid;
    grid-template-columns: 130px 1fr 72px;
    align-items: center;
    gap: 10px;
    color: var(--muted);
    font-size: 13px;
  }
  .trend-track {
    height: 10px;
    border-radius: 999px;
    background: #0b0d0f;
    overflow: hidden;
  }
  .trend-bar {
    height: 100%;
    min-width: 2px;
    background: var(--accent);
  }
  @media (max-width: 640px) {
    main { padding: 14px; }
    h1 { font-size: 25px; }
    .actions, .grid { grid-template-columns: 1fr; }
    button { min-height: 52px; }
  }
</style>
</head>
<body>
<main>
  <h1>G2ray Codespace Waker</h1>
  <p>Mobile wake UI for your registered GitHub Codespaces. Actions require your wake secret; the page starts the selected Codespace, checks its app.github.dev route, and shows whether configs should be usable.</p>

  <section class="card">
    <h2>Start</h2>
    <label for="secret">Wake secret</label>
    <input id="secret" type="password" autocomplete="off" placeholder="Paste WAKE_SECRET">
    <label for="codespace">Codespace</label>
    <select id="codespace" disabled><option value="">Enter wake secret to load Codespaces</option></select>
    <div class="actions">
      <button id="start" class="primary" type="button">Start Codespace</button>
      <button id="health" type="button">Check Health</button>
      <button id="history" type="button">Load History</button>
      <button id="copy" type="button">Copy Status</button>
      <button id="stop" type="button">Stop Polling</button>
    </div>
  </section>

  <section class="card">
    <h2>Health dashboard</h2>
    <div class="grid">
      <div class="metric"><span>GitHub state</span><strong id="githubState">Not checked</strong></div>
      <div class="metric"><span>Route</span><strong id="routeState">Not checked</strong></div>
      <div class="metric"><span>Last checked</span><strong id="lastChecked">Not checked</strong></div>
      <div class="metric"><span>Action code</span><strong id="actionCode">Not checked</strong></div>
      <div class="metric"><span>Latency</span><strong id="latency">Not checked</strong></div>
      <div class="metric"><span>Idle timeout</span><strong id="idleTimeout">Not checked</strong></div>
      <div class="metric"><span>Last used</span><strong id="lastUsed">Not checked</strong></div>
      <div class="metric"><span>Last failure</span><strong id="lastFailure">None seen</strong></div>
      <div class="metric"><span>Next action</span><strong id="nextAction">Not checked</strong></div>
    </div>
  </section>

  <section class="card">
    <h2>Quota Survival</h2>
    <div class="grid">
      <div class="metric"><span>Quota block</span><strong id="quotaBlocked">Not checked</strong></div>
      <div class="metric"><span>Estimated reset</span><strong id="quotaReset">Not checked</strong></div>
      <div class="metric"><span>Retention risk</span><strong id="retentionRisk">Not checked</strong></div>
      <div class="metric"><span>Retention expires</span><strong id="retentionExpires">Not checked</strong></div>
      <div class="metric"><span>Quota drought</span><strong id="quotaDrought">Not checked</strong></div>
      <div class="metric"><span>Survival action</span><strong id="survivalAction">Not checked</strong></div>
    </div>
  </section>

  <section class="card">
    <h2>Progress</h2>
    <ol id="progress">
      <li>Waiting for a wake or health check</li>
    </ol>
  </section>

  <section class="card">
    <h2>Result</h2>
    <pre id="result">No result yet.</pre>
  </section>

  <section class="card">
    <h2>History</h2>
    <p id="historyNote">History appears when WAKER_KV is configured.</p>
    <h3>Route history summary</h3>
    <div id="historySummary" class="grid">
      <div class="metric"><span>Samples</span><strong>Not loaded</strong></div>
      <div class="metric"><span>Route ready</span><strong>Not loaded</strong></div>
      <div class="metric"><span>HTTP 404</span><strong>Not loaded</strong></div>
      <div class="metric"><span>Wake success</span><strong>Not loaded</strong></div>
      <div class="metric"><span>Best latency</span><strong>Not loaded</strong></div>
      <div class="metric"><span>Last stuck route</span><strong>Not loaded</strong></div>
      <div class="metric"><span>Quota blocks</span><strong>Not loaded</strong></div>
      <div class="metric"><span>Quota drought</span><strong>Not loaded</strong></div>
    </div>
    <h3>Latency trend</h3>
    <div id="latencyTrend" class="history"></div>
    <h3>Recent events</h3>
    <div id="historyList" class="history"></div>
  </section>
</main>

<script>
const secretInput = document.getElementById("secret");
const codespaceSelect = document.getElementById("codespace");
const resultEl = document.getElementById("result");
const progressEl = document.getElementById("progress");
const historyList = document.getElementById("historyList");
const historyNote = document.getElementById("historyNote");
const historySummary = document.getElementById("historySummary");
const latencyTrend = document.getElementById("latencyTrend");
let lastStatusText = "";
let polling = false;
let pollTimer = null;
let pollAfterSeconds = 5;

document.getElementById("start").addEventListener("click", startWake);
document.getElementById("health").addEventListener("click", checkHealth);
document.getElementById("history").addEventListener("click", loadHistory);
document.getElementById("copy").addEventListener("click", copyStatus);
document.getElementById("stop").addEventListener("click", stopPolling);
secretInput.addEventListener("change", loadCodespaces);
secretInput.addEventListener("paste", () => setTimeout(loadCodespaces, 0));

function wakeSecret() {
  return secretInput.value.trim();
}

function setButtons(disabled) {
  for (const id of ["start", "health", "history", "copy"]) {
    document.getElementById(id).disabled = disabled;
  }
}

function setProgress(items, activeIndex) {
  progressEl.innerHTML = "";
  items.forEach((item, index) => {
    const li = document.createElement("li");
    li.textContent = item;
    if (index < activeIndex) li.className = "done";
    if (index === activeIndex) li.className = "active";
    progressEl.appendChild(li);
  });
}

function selectedCodespaceId() {
  return codespaceSelect.value.trim() || null;
}

async function callApi(path, includeCodespace = true) {
  const secret = wakeSecret();
  if (!secret) throw new Error("Wake secret is required.");
  const res = await fetch(path, {
    method: "POST",
    headers: {
      "authorization": "Bearer " + secret,
      "accept": "application/json",
      "content-type": "application/json"
    },
    body: includeCodespace ? JSON.stringify({ codespace_id: selectedCodespaceId() }) : undefined
  });
  const data = await res.json().catch(() => ({ ok: false, error: "invalid_json_response" }));
  if (!res.ok && !data.status) data.status = res.status;
  return data;
}

async function loadCodespaces() {
  if (!wakeSecret()) return false;
  try {
    const data = await callApi("/api/codespaces", false);
    if (!data.ok) throw new Error(data.next_action || data.error || "Could not load Codespaces");
    const previous = selectedCodespaceId();
    codespaceSelect.innerHTML = "";
    for (const entry of data.codespaces || []) {
      const option = document.createElement("option");
      option.value = entry.id;
      option.textContent = entry.label + " (" + entry.codespace + ")";
      option.selected = entry.id === (previous || data.default_codespace_id);
      codespaceSelect.appendChild(option);
    }
    codespaceSelect.disabled = codespaceSelect.options.length === 0;
    return !codespaceSelect.disabled;
  } catch (error) {
    codespaceSelect.innerHTML = "<option value=\"\">Codespace load failed</option>";
    codespaceSelect.disabled = true;
    renderError(error);
    return false;
  }
}

async function ensureCodespaceSelection() {
  if (!codespaceSelect.disabled && selectedCodespaceId()) return true;
  return loadCodespaces();
}

async function startWake() {
  polling = true;
  setButtons(true);
  const steps = ["Authenticating wake secret", "Calling GitHub start API", "Codespace started", "Checking XHTTP route", "Route ready or settling"];
  setProgress(steps, 0);
  try {
    if (!(await ensureCodespaceSelection())) return;
    setProgress(steps, 1);
    const data = await callApi("/api/wake");
    setProgress(steps, data.route_ready ? 4 : data.start_accepted ? 3 : data.ok ? 4 : 1);
    renderResult(data);
    if (shouldPollRoute(data)) {
      scheduleHealthPoll();
    }
  } catch (error) {
    renderError(error);
  } finally {
    setButtons(false);
  }
}

async function checkHealth() {
  setButtons(true);
  setProgress(["Authenticating wake secret", "Checking GitHub state", "Probing XHTTP route", "Showing result"], 1);
  try {
    if (!(await ensureCodespaceSelection())) return;
    const data = await callApi("/api/health");
    setProgress(["Authenticating wake secret", "Checking GitHub state", "Probing XHTTP route", "Showing result"], 3);
    renderResult(data);
    if (polling && shouldPollRoute(data)) {
      scheduleHealthPoll();
    }
  } catch (error) {
    renderError(error);
  } finally {
    setButtons(false);
  }
}

async function loadHistory() {
  setButtons(true);
  try {
    if (!(await ensureCodespaceSelection())) return;
    const data = await callApi("/api/history");
    renderHistory(data);
  } catch (error) {
    renderError(error);
  } finally {
    setButtons(false);
  }
}

async function copyStatus() {
  if (!lastStatusText) return;
  try {
    await navigator.clipboard.writeText(lastStatusText);
  } catch (error) {
    renderError(error);
  }
}

function shouldPollRoute(data) {
  return Boolean(data && (data.ok || data.start_accepted) && data.route_ready !== true && data.quota_blocked !== true);
}

function scheduleHealthPoll() {
  if (!polling) return;
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = setTimeout(checkHealth, Math.max(1, pollAfterSeconds) * 1000);
}

function stopPolling() {
  polling = false;
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = null;
  setProgress(["Polling stopped manually"], 0);
}

function renderResult(data) {
  const route = data.route_probe || {};
  const routeReady = data.route_ready === true;
  const checkedAt = new Date().toISOString();
  pollAfterSeconds = Number.isFinite(Number(data.poll_after_seconds || data.retry_after_seconds))
    ? Number(data.poll_after_seconds || data.retry_after_seconds)
    : 5;
  document.getElementById("githubState").textContent = data.state || (data.ok ? "Available" : "Problem");
  document.getElementById("routeState").textContent = routeReady ? "Route ready" : route.http_status ? "HTTP " + route.http_status : "Not reachable";
  document.getElementById("routeState").className = routeReady ? "good" : route.http_status === 404 ? "warn" : "bad";
  document.getElementById("lastChecked").textContent = checkedAt;
  document.getElementById("actionCode").textContent = data.next_action_code || "unknown";
  document.getElementById("latency").textContent = route.latency_ms == null ? "Unknown" : route.latency_ms + "ms";
  document.getElementById("idleTimeout").textContent = data.idle_timeout_minutes ? data.idle_timeout_minutes + " minutes" : "Unknown";
  document.getElementById("lastUsed").textContent = data.last_used_at || "Unknown";
  document.getElementById("lastFailure").textContent = routeSettlingFailureText(data, route, routeReady);
  document.getElementById("nextAction").textContent = data.next_action || "No action suggested";
  document.getElementById("quotaBlocked").textContent = data.quota_blocked ? "Quota blocked" : "No block seen";
  document.getElementById("quotaBlocked").className = data.quota_blocked ? "bad" : "good";
  document.getElementById("quotaReset").textContent = data.quota_reset_estimate_utc || "Unknown";
  document.getElementById("retentionRisk").textContent = data.retention_risk || "Unknown";
  document.getElementById("retentionRisk").className = data.retention_risk === "urgent" ? "bad" : data.retention_risk === "warning" ? "warn" : "";
  document.getElementById("retentionExpires").textContent = data.retention_expires_at || "Not scheduled / unknown";
  document.getElementById("quotaDrought").textContent = data.quota_drought_active ? "Active" : "No active drought";
  document.getElementById("survivalAction").textContent = data.survival_next_action || "Not checked";
  resultEl.textContent = JSON.stringify(data, null, 2);
  lastStatusText = [
    "G2ray Codespace Waker",
    "ok=" + Boolean(data.ok),
    "codespace=" + (data.codespace || "unknown"),
    "state=" + (data.state || "unknown"),
    "route_ready=" + routeReady,
    "route_http_status=" + (route.http_status || "unknown"),
    "latency_ms=" + (route.latency_ms == null ? "unknown" : route.latency_ms),
    "checked_at=" + checkedAt,
    "next_action_code=" + (data.next_action_code || "unknown"),
    "quota_blocked=" + Boolean(data.quota_blocked),
    "retention_risk=" + (data.retention_risk || "unknown"),
    "quota_reset_estimate_utc=" + (data.quota_reset_estimate_utc || "unknown"),
    "survival_next_action=" + (data.survival_next_action || ""),
    "next_action=" + (data.next_action || ""),
    "message=" + (data.message || data.error || "")
  ].join("\\n");
  if (routeReady) {
    polling = false;
    if (pollTimer) clearTimeout(pollTimer);
    pollTimer = null;
  }
}

function routeSettlingFailureText(data, route, routeReady) {
  const tokenRejected = data.token_warning || data.reason === "github_token_rejected_or_missing_scope";
  if (tokenRejected) return "GitHub token rejected or expired";
  if (data.ok && !routeReady && data.route_probe) {
    return route.http_status
      ? "Route not ready (HTTP " + route.http_status + ")"
      : "Route not reachable yet";
  }
  return data.ok ? "None seen" : (data.reason || data.error || "Unknown failure");
}

function renderHistory(data) {
  historyList.innerHTML = "";
  latencyTrend.innerHTML = "";
  historySummary.innerHTML = "";
  if (data && data.ok === false) {
    const message = data.reason || data.error || (data.status ? "HTTP " + data.status : "history_request_failed");
    historyNote.textContent = "History request failed: " + message;
    const item = document.createElement("div");
    item.className = "event";
    item.textContent = data.message || data.token_warning || message;
    historyList.appendChild(item);
    resultEl.textContent = JSON.stringify(data, null, 2);
    return;
  }
  historyNote.textContent = data.history_enabled
    ? "Recent wake and health events."
    : "History is disabled because WAKER_KV is not configured.";
  const events = data.history || [];
  renderHistorySummary(events, data.quota_incident || null);
  renderLatencyTrend(events);
  for (const event of events) {
    const item = document.createElement("div");
    item.className = "event";
    item.textContent = [
      event.ts,
      event.ts ? "age=" + ageText(event.ts) : "",
      event.kind,
      "ok=" + event.ok,
      event.next_action_code ? "action=" + event.next_action_code : "",
      "route_ready=" + event.route_ready,
      "http=" + (event.route_http_status || "unknown"),
      "latency=" + (event.route_latency_ms == null ? "unknown" : event.route_latency_ms + "ms"),
      "waited=" + (event.route_waited_ms == null ? "unknown" : event.route_waited_ms + "ms"),
      event.reason || ""
    ].filter(Boolean).join(" | ");
    historyList.appendChild(item);
  }
}

function renderHistorySummary(events, quotaIncident) {
  const summary = summarizeHistory(events);
  const cards = [
    ["Samples", String(summary.samples)],
    ["Route ready", summary.ready + " / " + summary.routeSamples],
    ["HTTP 404", String(summary.http404)],
    ["Wake success", summary.wakeOk + " ok / " + summary.wakeFail + " fail"],
    ["Best latency", summary.bestLatency == null ? "None" : summary.bestLatency + "ms"],
    ["Last stuck route", summary.lastStuck || "None seen"],
    ["Quota blocks", String(summary.quotaBlocks)],
    ["Quota drought", quotaIncident && quotaIncident.quota_drought_active ? "Active" : "Inactive / none"]
  ];
  for (const [label, value] of cards) {
    const card = document.createElement("div");
    card.className = "metric";
    const span = document.createElement("span");
    span.textContent = label;
    const strong = document.createElement("strong");
    strong.textContent = value;
    card.appendChild(span);
    card.appendChild(strong);
    historySummary.appendChild(card);
  }
}

function summarizeHistory(events) {
  const summary = {
    samples: events.length,
    routeSamples: 0,
    ready: 0,
    http404: 0,
    wakeOk: 0,
    wakeFail: 0,
    quotaBlocks: 0,
    bestLatency: null,
    lastStuck: ""
  };
  for (const event of events) {
    const routeChecked = event.route_checked === true && event.route_ready !== null && event.route_ready !== undefined;
    if (routeChecked) {
      summary.routeSamples += 1;
      if (event.route_ready === true) summary.ready += 1;
      if (event.route_http_status === 404) summary.http404 += 1;
    }
    if (event.kind === "wake" && event.ok) summary.wakeOk += 1;
    if (event.kind === "wake" && !event.ok) summary.wakeFail += 1;
    if (event.quota_blocked === true || event.reason === "quota_or_billing_blocked") summary.quotaBlocks += 1;
    if (routeChecked && Number.isFinite(event.route_latency_ms)) {
      summary.bestLatency = summary.bestLatency == null
        ? event.route_latency_ms
        : Math.min(summary.bestLatency, event.route_latency_ms);
    }
    if (routeChecked && !summary.lastStuck && event.route_ready !== true && (event.route_http_status === 404 || event.route_http_status === 0)) {
      const waited = event.route_waited_ms == null ? "" : ", waited " + event.route_waited_ms + "ms";
      summary.lastStuck = (event.ts || "unknown time") + " HTTP " + (event.route_http_status || 0) + waited;
    }
  }
  return summary;
}

function renderLatencyTrend(events) {
  const latencyEvents = events
    .filter((event) => Number.isFinite(event.route_latency_ms))
    .slice(0, 12)
    .reverse();
  if (latencyEvents.length === 0) {
    const empty = document.createElement("div");
    empty.className = "event";
    empty.textContent = "No latency samples yet.";
    latencyTrend.appendChild(empty);
    return;
  }
  const max = Math.max(...latencyEvents.map((event) => event.route_latency_ms), 1);
  for (const event of latencyEvents) {
    const row = document.createElement("div");
    row.className = "trend-row";
    const label = document.createElement("span");
    label.textContent = shortTime(event.ts);
    const track = document.createElement("div");
    track.className = "trend-track";
    const bar = document.createElement("div");
    bar.className = "trend-bar";
    bar.style.width = Math.max(4, Math.round((event.route_latency_ms / max) * 100)) + "%";
    track.appendChild(bar);
    const value = document.createElement("span");
    value.textContent = event.route_latency_ms + "ms";
    row.appendChild(label);
    row.appendChild(track);
    row.appendChild(value);
    latencyTrend.appendChild(row);
  }
}

function shortTime(value) {
  if (!value) return "unknown";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value).slice(0, 16);
  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function ageText(value) {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return "unknown";
  const seconds = Math.max(0, Math.round((Date.now() - timestamp) / 1000));
  if (seconds < 60) return seconds + "s";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return minutes + "m";
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return hours + "h";
  return Math.floor(hours / 24) + "d";
}

function renderError(error) {
  resultEl.textContent = JSON.stringify({
    ok: false,
    error: error && error.message ? error.message : "request_failed"
  }, null, 2);
}
</script>
</body>
</html>`;
}

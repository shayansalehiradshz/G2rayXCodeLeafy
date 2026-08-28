import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import worker from "../worker/codespace-waker/src/index.js";

const originalFetch = globalThis.fetch;
if (!globalThis.crypto) {
  globalThis.crypto = webcrypto;
}

function makeRequest(path, secret = "secret", body = undefined) {
  return new Request(`https://worker.example${path}`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${secret}`,
      ...(body === undefined ? {} : { "content-type": "application/json" })
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
}

function makeKv() {
  const store = new Map();
  const putOptions = new Map();
  let putCalls = 0;
  return {
    async get(key) {
      return store.get(key) || null;
    },
    async put(key, value, options = undefined) {
      putCalls += 1;
      store.set(key, value);
      putOptions.set(key, options || null);
    },
    putCallCount() {
      return putCalls;
    },
    dump() {
      return Object.fromEntries(store.entries());
    },
    putOptions(key) {
      return putOptions.get(key) || null;
    },
    dumpPutOptions() {
      return Object.fromEntries(putOptions.entries());
    }
  };
}

function baseEnv(overrides = {}) {
  return {
    WAKE_SECRET: "secret",
    GITHUB_TOKEN: "github-token",
    CODESPACE_NAME: "behavior-space",
    ROUTE_READY_STABLE_SLEEP_MS: "0",
    WORKER_REQUEST_LOGS: "0",
    ...overrides
  };
}

async function responseJson(response) {
  return JSON.parse(await response.text());
}

async function testFailedSecretRateLimit() {
  const env = baseEnv({ WAKER_KV: makeKv() });
  let last;
  for (let i = 0; i < 11; i += 1) {
    last = await worker.fetch(makeRequest("/api/health", "wrong-secret"), env, {});
  }
  const body = await responseJson(last);
  assert.equal(last.status, 429);
  assert.equal(last.headers.get("retry-after"), "600");
  assert.equal(body.reason, "worker_wake_secret_rate_limited");
  assert.equal(body.retry_after_seconds, 600);
  assert.equal(env.WAKER_KV.putOptions("failed-auth:unknown").expirationTtl, 600);
  console.log("PASS: Worker rate-limits repeated bad wake secrets");
}

async function testFailedSecretRateLimitCapsKvWrites() {
  const env = baseEnv({ WAKER_KV: makeKv() });
  for (let i = 0; i < 30; i += 1) {
    await worker.fetch(makeRequest("/api/health", "wrong-secret"), env, {});
  }
  // Once an IP is over the limit the Worker must stop writing to KV, so a single
  // flooding IP cannot drain the free-tier daily write quota.
  assert.ok(
    env.WAKER_KV.putCallCount() <= 10,
    `expected KV writes capped at the attempt limit, got ${env.WAKER_KV.putCallCount()}`
  );
  console.log("PASS: Worker caps KV writes for a flooding IP instead of writing on every request");
}

async function testMissingWakeSecretIsConfigurationError() {
  const response = await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({ WAKE_SECRET: "", WAKER_KV: makeKv() }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 500);
  assert.equal(body.ok, false);
  assert.equal(body.error, "missing_wake_secret");
  assert.equal(body.next_action_code, "configure_wake_secret");
  console.log("PASS: Worker reports missing wake secret as configuration error");
}

async function testMissingCodespaceNameIsActionable() {
  const response = await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({ CODESPACE_NAME: "" }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 500);
  assert.equal(body.error, "missing_codespace_name");
  assert.match(body.next_action, /CODESPACE_NAME/);
  console.log("PASS: Worker reports missing CODESPACE_NAME with dashboard guidance");
}

async function testGithubRateLimitClassification() {
  const resetEpoch = Math.ceil(Date.now() / 1000) + 120;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({ message: "API rate limit exceeded" }), {
        status: 403,
        headers: {
          "content-type": "application/json",
          "x-ratelimit-remaining": "0",
          "x-ratelimit-reset": String(resetEpoch)
        }
      });
    }
    if (url.includes("app.github.dev")) {
      return new Response("", { status: 404 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/health"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 429);
  assert.equal(Number(response.headers.get("retry-after")) > 0, true);
  assert.equal(body.reason, "github_rate_limited");
  assert.equal(body.retry_after_epoch, resetEpoch);
  assert.match(body.next_action, /GitHub is throttling/);
  assert.equal(body.next_action_code, "wait_github_rate_limit");
  console.log("PASS: Worker classifies GitHub primary rate limits");
}

async function testGithubScopeFailureClassification() {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({ message: "Resource not accessible by personal access token" }), {
        status: 403,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/health"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 403);
  assert.equal(body.reason, "github_token_scope_missing");
  assert.match(body.next_action, /codespace scope/);
  console.log("PASS: Worker classifies token scope failures distinctly");
}

async function testGithubUnknownForbiddenClassificationIsNeutral() {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({ message: "Organization policy blocks this request" }), {
        status: 403,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/health"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 403);
  assert.equal(body.reason, "github_forbidden");
  assert.equal(body.token_warning ?? null, null);
  assert.equal(body.next_action_code, "check_github_policy_or_access");
  assert.match(body.next_action, /policy or access/i);
  console.log("PASS: Worker classifies unknown GitHub 403 without false token-rotation guidance");
}

async function testWakeSettlingIncludesRetryMetadata() {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(JSON.stringify({ state: "Available" }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      return new Response("", { status: 404 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/wake"), baseEnv({
    ROUTE_WAIT_MS: "1",
    ROUTE_POLL_INTERVAL_MS: "1"
  }), {});
  const body = await responseJson(response);
  assert.equal(response.status, 202);
  assert.equal(response.headers.get("retry-after"), "5");
  assert.equal(body.route_ready, false);
  assert.equal(body.retry_after_seconds, 5);
  assert.equal(body.poll_after_seconds, 5);
  assert.equal(body.route_probe.route_failure_reason, "route_settling_404");
  assert.equal(body.next_action_code, "wait_route_or_recover");
  assert.match(body.next_action, /headless/i);
  console.log("PASS: Worker settling responses include retry and route failure metadata");
}

async function testHealthCanSkipRouteProbe() {
  let routeCalls = 0;
  const kv = makeKv();
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/health?route=false"), baseEnv({ WAKER_KV: kv }), {});
  const body = await responseJson(response);
  assert.equal(response.status, 200, JSON.stringify(body));
  assert.equal(body.route_checked, false);
  assert.equal(body.route_ready, null);
  assert.equal(body.next_action_code, "route_check_skipped");
  assert.equal(routeCalls, 0);
  const history = await responseJson(await worker.fetch(makeRequest("/api/history"), baseEnv({ WAKER_KV: kv }), {}));
  assert.equal(history.history[0].route_checked, false);
  assert.equal(history.history[0].route_ready, null);
  assert.equal(history.history[0].route_http_status, null);
  assert.equal(history.history[0].route_probe_duration_ms, null);
  assert.equal(history.history[0].route_waited_ms, null);
  assert.equal(history.history[0].route_attempts, null);
  console.log("PASS: Worker health can skip route probing for cheap status checks");
}

async function testHealthDoesNotProbeRouteWhileCodespaceIsShutdown() {
  let routeCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Shutdown",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      throw new Error(`shutdown Codespace must not probe route: ${url}`);
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/health"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.state, "Shutdown");
  assert.equal(body.route_checked, false);
  assert.equal(body.route_ready, false);
  assert.equal(body.route_probe.http_status, null);
  assert.equal(body.route_probe.error, "codespace_not_available");
  assert.equal(body.next_action_code, "wait_codespace_available");
  assert.match(body.message, /not probed/i);
  assert.equal(routeCalls, 0);
  console.log("PASS: Worker health skips expensive route probing while Codespace is shutdown");
}

async function testHealthSkipRoutePreservesGithubFailureGuidance() {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({ message: "Resource not accessible by personal access token" }), {
        status: 403,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/health?route=false"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 403);
  assert.equal(body.reason, "github_token_scope_missing");
  assert.match(body.message, /codespace scope/i);
  assert.match(body.next_action, /codespace scope/i);
  assert.doesNotMatch(body.message, /route probe skipped/i);
  console.log("PASS: Worker route-skipped health preserves GitHub failure guidance");
}

async function testAuthorizedWakeCooldownIsOptional() {
  let routeCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(JSON.stringify({ state: "Available" }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({ WAKER_KV: makeKv(), WAKE_COOLDOWN_SECONDS: "1" });
  const first = await worker.fetch(makeRequest("/api/wake"), env, {});
  const firstBody = await responseJson(first);
  assert.equal(first.status, 200);
  assert.equal(firstBody.route_ready, true);

  const second = await worker.fetch(makeRequest("/api/wake"), env, {});
  const secondBody = await responseJson(second);
  assert.equal(second.status, 429);
  assert.equal(second.headers.get("retry-after"), "60");
  assert.equal(secondBody.reason, "wake_recently_succeeded");
  assert.equal(secondBody.retry_after_seconds, 60);
  assert.equal(env.WAKER_KV.putOptions("successful-wake:behavior-space").expirationTtl, 60);
  assert.equal(routeCalls >= 2, true);
  console.log("PASS: Worker optional wake cooldown prevents repeated successful wake spam");
}

async function testGithubHttp429Classification() {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({ message: "Too many requests" }), {
        status: 429,
        headers: {
          "content-type": "application/json",
          "retry-after": "60",
          "x-ratelimit-reset": "1780000123"
        }
      });
    }
    if (url.includes("app.github.dev")) {
      return new Response("", { status: 404 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/health"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 429);
  assert.equal(body.reason, "github_rate_limited");
  assert.equal(body.retry_after_seconds, 60);
  assert.equal(body.retry_after_epoch, 1780000123);
  assert.match(body.next_action, /GitHub is throttling/);
  console.log("PASS: Worker classifies GitHub HTTP 429 rate limits");
}

async function testGithubStartRetriesTransientServerFailure() {
  let startCalls = 0;
  let cancelledResponseBodies = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      startCalls += 1;
      if (startCalls === 1) {
        return new Response(new ReadableStream({
          cancel() {
            cancelledResponseBodies += 1;
          }
        }), {
          status: 503,
          headers: { "content-type": "application/json" }
        });
      }
      return new Response(JSON.stringify({ state: "Available" }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/wake"), baseEnv({
    WAKE_FAST_PATH: "0",
    GITHUB_API_RETRY_BACKOFF_MS: "0"
  }), {});
  const body = await responseJson(response);
  assert.equal(response.status, 202);
  assert.equal(startCalls, 2);
  assert.equal(cancelledResponseBodies, 1);
  assert.equal(body.start_accepted, true);
  assert.equal(body.route_ready, false);
  assert.equal(body.next_action_code, "wait_codespace_available");
  console.log("PASS: Worker retries transient GitHub start server failures once");
}

async function testQuotaBlockIncludesSurvivalFields() {
  const kv = makeKv();
  let statusCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(JSON.stringify({ message: "Payment required" }), {
        status: 402,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      statusCalls += 1;
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Shutdown",
        pending_operation: false,
        retention_period_minutes: 43200,
        retention_expires_at: "2026-06-20T00:00:00Z",
        last_used_at: "2026-06-10T00:00:00Z"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(
    makeRequest("/api/wake"),
    baseEnv({ WAKER_KV: kv, TEST_NOW_UTC: "2026-06-15T12:00:00Z" }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 402);
  assert.equal(body.reason, "quota_or_billing_blocked");
  assert.equal(body.quota_blocked, true);
  assert.equal(body.route_checked, false);
  assert.equal(body.route_ready, false);
  assert.equal(body.quota_reset_estimate_utc, "2026-07-01T00:00:00Z");
  assert.equal(body.retention_period_minutes, 43200);
  assert.equal(body.retention_expires_at, "2026-06-20T00:00:00Z");
  assert.equal(body.retention_risk, "warning");
  assert.match(body.survival_next_action, /Keep codespace/);
  assert.equal(statusCalls, 1);
  const history = await responseJson(await worker.fetch(makeRequest("/api/history"), baseEnv({ WAKER_KV: kv }), {}));
  assert.equal(history.history[0].route_checked, false);
  assert.equal(history.history[0].route_ready, null);
  console.log("PASS: Worker quota blocks include survival fields");
}

async function testRetentionMissingFieldsAreUnknown() {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-06-10T00:00:00Z"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({ TEST_NOW_UTC: "2026-06-15T12:00:00Z" }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.quota_blocked, false);
  assert.equal(body.retention_period_minutes, null);
  assert.equal(body.retention_expires_at, null);
  assert.equal(body.retention_risk, "unknown");
  assert.equal(body.quota_reset_estimate_utc, "2026-07-01T00:00:00Z");
  console.log("PASS: Worker treats missing retention fields as unknown");
}

async function testMonthlyResetEstimateCrossesYear() {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(JSON.stringify({ message: "Payment required" }), {
        status: 402,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Shutdown",
        pending_operation: false
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(
    makeRequest("/api/wake"),
    baseEnv({ TEST_NOW_UTC: "2026-12-31T23:00:00Z" }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 402);
  assert.equal(body.quota_reset_estimate_utc, "2027-01-01T00:00:00Z");
  console.log("PASS: Worker monthly reset estimate crosses year boundary");
}

async function testKvQuotaIncidentHistoryRecordsBlockedAndRecovery() {
  const kv = makeKv();
  let routeReady = false;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start") && !routeReady) {
      return new Response(JSON.stringify({ message: "Payment required" }), {
        status: 402,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("/start")) {
      return new Response(JSON.stringify({ state: "Available" }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: routeReady ? "Available" : "Shutdown",
        pending_operation: false,
        retention_period_minutes: 43200,
        retention_expires_at: routeReady ? null : "2026-06-20T00:00:00Z",
        last_used_at: "2026-06-10T00:00:00Z"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({ WAKER_KV: kv, TEST_NOW_UTC: "2026-06-15T12:00:00Z" });
  let response = await worker.fetch(makeRequest("/api/wake"), env, {});
  let body = await responseJson(response);
  assert.equal(response.status, 402);
  assert.equal(body.quota_incident_recorded, true);
  assert.equal(body.quota_drought_active, true);

  routeReady = true;
  env.TEST_NOW_UTC = "2026-07-01T00:05:00Z";
  response = await worker.fetch(makeRequest("/api/wake"), env, {});
  body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.quota_incident_recorded, true);
  assert.equal(body.quota_drought_active, false);

  response = await worker.fetch(makeRequest("/api/history"), env, {});
  body = await responseJson(response);
  assert.equal(body.quota_incident.first_quota_blocked_at, "2026-06-15T12:00:00.000Z");
  assert.equal(body.quota_incident.latest_quota_blocked_at, "2026-06-15T12:00:00.000Z");
  assert.equal(body.quota_incident.last_successful_start_at, "2026-07-01T00:05:00.000Z");
  assert.equal(body.quota_incident.quota_drought_active, false);
  assert.equal(body.quota_incident.same_codespace_exists, true);
  console.log("PASS: Worker KV quota incident history records blocked and recovery states");
}

async function testQuotaIncidentKeepsResetEstimateDuringActiveDroughtHealthChecks() {
  const kv = makeKv();
  await kv.put("quota-incident:behavior-space", JSON.stringify({
    codespace: "behavior-space",
    quota_drought_active: true,
    first_quota_blocked_at: "2026-06-15T12:00:00.000Z",
    latest_quota_blocked_at: "2026-06-15T12:00:00.000Z",
    quota_reset_estimate_utc: "2026-07-01T00:00:00Z"
  }));
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        retention_period_minutes: 43200,
        last_used_at: "2026-07-01T00:05:00Z"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({ WAKER_KV: kv, TEST_NOW_UTC: "2026-07-01T00:05:00Z" });
  const response = await worker.fetch(makeRequest("/api/health?route=false"), env, {});
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.quota_drought_active, true);
  assert.equal(body.quota_reset_estimate_utc, "2026-07-01T00:00:00Z");
  const history = await responseJson(await worker.fetch(makeRequest("/api/history"), env, {}));
  assert.equal(history.quota_incident.quota_drought_active, true);
  assert.equal(history.quota_incident.quota_reset_estimate_utc, "2026-07-01T00:00:00Z");
  console.log("PASS: Worker preserves active quota incident reset estimate across cheap health checks");
}

async function testQuotaIncidentRouteReadyHealthBeforeResetDoesNotClearDrought() {
  const kv = makeKv();
  await kv.put("quota-incident:behavior-space", JSON.stringify({
    codespace: "behavior-space",
    quota_drought_active: true,
    first_quota_blocked_at: "2026-06-15T12:00:00.000Z",
    latest_quota_blocked_at: "2026-06-15T12:00:00.000Z",
    quota_reset_estimate_utc: "2026-07-01T00:00:00Z"
  }));
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        retention_period_minutes: 43200,
        last_used_at: "2026-06-20T00:00:00Z"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({ WAKER_KV: kv, TEST_NOW_UTC: "2026-06-20T00:00:00Z" });
  const response = await worker.fetch(makeRequest("/api/health"), env, {});
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, true);
  assert.equal(body.quota_drought_active, true);
  const history = await responseJson(await worker.fetch(makeRequest("/api/history"), env, {}));
  assert.equal(history.quota_incident.quota_drought_active, true);
  assert.equal(history.quota_incident.quota_reset_estimate_utc, "2026-07-01T00:00:00Z");
  console.log("PASS: Worker keeps active quota drought through pre-reset route-ready health checks");
}

async function testRepeatedHealthDoesNotRewriteUnchangedQuotaIncident() {
  const kv = makeKv();
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        retention_period_minutes: 43200,
        last_used_at: "2026-07-01T00:05:00Z"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({
    WAKER_KV: kv,
    TEST_NOW_UTC: "2026-07-01T00:05:00Z",
    QUOTA_INCIDENT_SAMPLE_MS: "900000"
  });
  const first = await worker.fetch(makeRequest("/api/health?route=false"), env, {});
  const firstBody = await responseJson(first);
  assert.equal(first.status, 200);
  assert.equal(firstBody.quota_incident_recorded, true);
  const writesAfterFirst = kv.putCallCount();

  env.TEST_NOW_UTC = "2026-07-01T00:05:05Z";
  const second = await worker.fetch(makeRequest("/api/health?route=false"), env, {});
  const secondBody = await responseJson(second);
  assert.equal(second.status, 200);
  assert.equal(secondBody.quota_incident_recorded, false);
  assert.equal(kv.putCallCount(), writesAfterFirst);
  console.log("PASS: Worker samples unchanged quota incidents instead of writing on every health poll");
}

async function testHistoryWorksWithoutGithubToken() {
  const kv = makeKv();
  await kv.put("history:behavior-space", JSON.stringify([
    {
      ts: "2026-06-01T00:00:00.000Z",
      kind: "health",
      codespace: "behavior-space",
      ok: true,
      status: 200
    }
  ]));
  const response = await worker.fetch(makeRequest("/api/history"), baseEnv({
    GITHUB_TOKEN: "",
    WAKER_KV: kv
  }), {});
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.ok, true);
  assert.equal(body.history_enabled, true);
  assert.equal(body.history.length, 1);
  console.log("PASS: Worker history works with valid wake secret even when GitHub token is missing");
}

async function testScheduledQuotaCronIsDisabledAndThrottledBeforeReset() {
  const kv = makeKv();
  await kv.put("quota-incident:behavior-space", JSON.stringify({
    quota_drought_active: true,
    quota_reset_estimate_utc: "2026-07-01T00:00:00Z"
  }));
  let statusCalls = 0;
  let startCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      startCalls += 1;
      return new Response(JSON.stringify({ message: "should not start before reset window" }), {
        status: 500,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      statusCalls += 1;
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Shutdown",
        pending_operation: false,
        retention_period_minutes: 43200,
        retention_expires_at: "2026-06-25T00:00:00Z"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const disabledPromises = [];
  await worker.scheduled(
    { scheduledTime: Date.parse("2026-06-15T00:00:00Z") },
    baseEnv({ WAKER_KV: kv }),
    { waitUntil(promise) { disabledPromises.push(promise); } }
  );
  assert.equal(disabledPromises.length, 0);
  assert.equal(statusCalls, 0);

  const env = baseEnv({
    WAKER_KV: kv,
    QUOTA_SURVIVAL_CRON_ENABLED: "true",
    TEST_NOW_UTC: "2026-06-15T00:00:00Z"
  });
  const waitUntilPromises = [];
  await worker.scheduled(
    { scheduledTime: Date.parse("2026-06-15T00:00:00Z") },
    env,
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  await Promise.all(waitUntilPromises);
  await worker.scheduled(
    { scheduledTime: Date.parse("2026-06-15T01:00:00Z") },
    env,
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  await Promise.all(waitUntilPromises);
  assert.equal(statusCalls, 1);
  assert.equal(startCalls, 0);
  const incident = JSON.parse(await kv.get("quota-incident:behavior-space"));
  assert.equal(incident.quota_drought_active, true);
  assert.equal(incident.last_successful_health_at, "2026-06-15T00:00:00.000Z");
  console.log("PASS: Worker scheduled quota survival cron is disabled by default and throttled before reset");
}

async function testScheduledQuotaCronAttemptsOneNearResetWake() {
  const kv = makeKv();
  await kv.put("quota-incident:behavior-space", JSON.stringify({
    quota_drought_active: true,
    quota_reset_estimate_utc: "2026-07-01T00:00:00Z"
  }));
  let startCalls = 0;
  let statusCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      startCalls += 1;
      return new Response(JSON.stringify({ message: "Payment required" }), {
        status: 402,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      statusCalls += 1;
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Shutdown",
        pending_operation: false,
        retention_period_minutes: 43200,
        retention_expires_at: "2026-07-20T00:00:00Z"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({
    WAKER_KV: kv,
    QUOTA_SURVIVAL_CRON_ENABLED: "true",
    TEST_NOW_UTC: "2026-06-30T19:00:00Z"
  });
  let waitUntilPromises = [];
  await worker.scheduled(
    { scheduledTime: Date.parse("2026-06-30T19:00:00Z") },
    env,
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  await Promise.all(waitUntilPromises);

  env.TEST_NOW_UTC = "2026-06-30T20:00:00Z";
  waitUntilPromises = [];
  await worker.scheduled(
    { scheduledTime: Date.parse("2026-06-30T20:00:00Z") },
    env,
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  await Promise.all(waitUntilPromises);

  assert.equal(startCalls, 1);
  assert.equal(statusCalls, 1);
  const incident = JSON.parse(await kv.get("quota-incident:behavior-space"));
  assert.equal(incident.quota_drought_active, true);
  assert.equal(incident.last_cron_wake_reset_estimate_utc, "2026-07-01T00:00:00Z");
  console.log("PASS: Worker scheduled quota survival cron attempts only one near-reset wake");
}

async function testScheduledQuotaCronRetriesAfterResetIfPreResetWakeStillBlocked() {
  const kv = makeKv();
  await kv.put("quota-incident:behavior-space", JSON.stringify({
    quota_drought_active: true,
    quota_reset_estimate_utc: "2026-07-01T00:00:00Z"
  }));
  let startCalls = 0;
  let routeCalls = 0;
  let afterReset = false;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      startCalls += 1;
      if (!afterReset) {
        return new Response(JSON.stringify({ message: "Payment required" }), {
          status: 402,
          headers: { "content-type": "application/json" }
        });
      }
      return new Response(JSON.stringify({ state: "Available" }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: afterReset ? "Available" : "Shutdown",
        pending_operation: false,
        retention_period_minutes: 43200,
        retention_expires_at: "2026-07-20T00:00:00Z"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({
    WAKER_KV: kv,
    QUOTA_SURVIVAL_CRON_ENABLED: "true",
    ROUTE_READY_STABLE_SLEEP_MS: "0",
    TEST_NOW_UTC: "2026-06-30T19:00:00Z"
  });
  let waitUntilPromises = [];
  await worker.scheduled(
    { scheduledTime: Date.parse("2026-06-30T19:00:00Z") },
    env,
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  await Promise.all(waitUntilPromises);

  afterReset = true;
  env.TEST_NOW_UTC = "2026-07-01T00:05:00Z";
  waitUntilPromises = [];
  await worker.scheduled(
    { scheduledTime: Date.parse("2026-07-01T00:05:00Z") },
    env,
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  await Promise.all(waitUntilPromises);

  assert.equal(startCalls, 2);
  assert.equal(routeCalls, 0);
  const incident = JSON.parse(await kv.get("quota-incident:behavior-space"));
  assert.equal(incident.quota_drought_active, false);
  assert.equal(incident.last_successful_start_at, "2026-07-01T00:05:00.000Z");
  console.log("PASS: Worker quota cron retries after reset if a pre-reset wake was still quota-blocked");
}

async function testScheduledQuotaCronKeepsRetryingAfterResetWhileStillBlocked() {
  const kv = makeKv();
  await kv.put("quota-incident:behavior-space", JSON.stringify({
    quota_drought_active: true,
    quota_reset_estimate_utc: "2026-07-01T00:00:00Z"
  }));
  let startCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      startCalls += 1;
      return new Response(JSON.stringify({ message: "Payment required" }), {
        status: 402,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Shutdown",
        pending_operation: false,
        retention_period_minutes: 43200,
        retention_expires_at: "2026-07-20T00:00:00Z"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({
    WAKER_KV: kv,
    QUOTA_SURVIVAL_CRON_ENABLED: "true",
    TEST_NOW_UTC: "2026-06-30T19:00:00Z"
  });
  for (const iso of [
    "2026-06-30T19:00:00Z",
    "2026-07-01T00:05:00Z",
    "2026-07-01T01:10:00Z"
  ]) {
    env.TEST_NOW_UTC = iso;
    const waitUntilPromises = [];
    await worker.scheduled(
      { scheduledTime: Date.parse(iso) },
      env,
      { waitUntil(promise) { waitUntilPromises.push(promise); } }
    );
    await Promise.all(waitUntilPromises);
  }

  assert.equal(startCalls, 3);
  const incident = JSON.parse(await kv.get("quota-incident:behavior-space"));
  assert.equal(incident.quota_drought_active, true);
  assert.equal(incident.quota_reset_estimate_utc, "2026-07-01T00:00:00Z");
  assert.equal(incident.last_cron_post_reset_wake_reset_estimate_utc, "2026-07-01T00:00:00Z");
  console.log("PASS: Worker quota cron keeps retrying after reset while still blocked");
}

async function testWakeFailureIncludesNextAction() {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({ message: "Bad credentials" }), {
        status: 401,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/wake"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 401);
  assert.equal(body.reason, "github_token_rejected_or_missing_scope");
  assert.match(body.next_action, /Rotate the GitHub token/);
  assert.equal(body.next_action_code, "rotate_github_token");
  console.log("PASS: Worker wake failures include actionable next_action");
}

async function testHealthRejectsHttp400RouteAsUnusable() {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240,
        location: "EastUs"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      return new Response("", { status: 400 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/health"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, false);
  assert.equal(body.route_probe.http_status, 400);
  assert.equal(body.next_action_code, "inspect_panel_diagnostics");
  assert.equal(body.message, "Codespace status loaded, but the XHTTP route is not usable yet.");
  console.log("PASS: Worker rejects HTTP 400 because the pinned XHTTP OPTIONS handler succeeds with 200");
}

async function testOptionalPortCannotMasqueradeAsPrimaryXhttpReadiness() {
  const kv = makeKv();
  await kv.put("codespace-registry:v1", JSON.stringify({
    default_codespace_id: "primary",
    codespaces: [{
      id: "primary",
      label: "Primary Codespace",
      name: "primary-space",
      token_binding: "GITHUB_TOKEN_PRIMARY",
      route_ports: [443, 8443]
    }]
  }));

  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "primary-space",
        state: "Available",
        pending_operation: false
      }), { status: 200, headers: { "content-type": "application/json" } });
    }
    if (url.includes("primary-space-443.app.github.dev")) return new Response("", { status: 404 });
    if (url.includes("primary-space-8443.app.github.dev")) return new Response("", { status: 200 });
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(
    makeRequest("/api/health", "secret", { codespace_id: "primary" }),
    baseEnv({
      WAKER_KV: kv,
      GITHUB_TOKEN_PRIMARY: "primary-token",
      ROUTE_WAIT_MS: "1",
      ROUTE_POLL_INTERVAL_MS: "1"
    }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, false);
  assert.equal(body.route_probe.port, 443);
  assert.equal(body.route_probe.http_status, 404);
  assert.equal(body.preferred_route_port, 8443);
  assert.equal(body.route_probes.find((probe) => probe.port === 8443).http_status, 200);
  console.log("PASS: Worker does not claim primary XHTTP readiness from an optional port");
}

async function testWakeRequiresStableRouteReadiness() {
  let routeCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(JSON.stringify({ state: "Available" }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      const status = routeCalls === 1 ? 200 : routeCalls === 2 ? 404 : 200;
      return new Response("", { status });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/wake"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, true);
  assert.equal(body.route_probe.attempts, 3);
  assert.equal(body.route_probe.stable_probes, 2);
  console.log("PASS: Worker rejects transient single route success");
}

async function testHealthWaitsBetweenStableRouteProbes() {
  const routeCallTimes = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(JSON.stringify({ state: "Available" }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCallTimes.push(Date.now());
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({ ROUTE_READY_STABLE_SLEEP_MS: "25" }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, true);
  assert.equal(body.route_probe.stable_probes, 2);
  assert.equal(routeCallTimes.length >= 2, true);
  assert.equal(routeCallTimes[1] - routeCallTimes[0] >= 20, true);
  console.log("PASS: Worker health waits between stable route-readiness probes");
}

async function testRouteFetchTimeoutOverrideIsApplied() {
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        idle_timeout_minutes: 120
      }), { status: 200, headers: { "content-type": "application/json" } });
    }
    if (url.includes("app.github.dev")) {
      return new Promise((resolve, reject) => {
        init.signal?.addEventListener(
          "abort",
          () => reject(new DOMException("Aborted", "AbortError")),
          { once: true }
        );
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const startedAt = Date.now();
  const response = await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({
      ROUTE_FETCH_TIMEOUT_MS: "100",
      ROUTE_WAIT_MS: "150",
      ROUTE_POLL_INTERVAL_MS: "10"
    }),
    {}
  );
  const elapsedMs = Date.now() - startedAt;
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, false);
  assert.equal(body.route_probe.route_failure_reason, "timeout_or_unreachable");
  assert.equal(elapsedMs < 1000, true);
  console.log("PASS: Worker applies the configured per-probe route fetch timeout");
}

async function testHealthDoesNotClaimReadyFromSingleDeadlineProbe() {
  let routeCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(JSON.stringify({ state: "Available" }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({ ROUTE_WAIT_MS: "5", ROUTE_READY_STABLE_SLEEP_MS: "25" }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, false);
  assert.equal(body.route_probe.usable, true);
  assert.equal(body.route_probe.http_status, 200);
  assert.equal(body.route_probe.stable_probes, 1);
  assert.equal(body.route_probe.error, "route_stability_not_confirmed");
  assert.equal(body.route_probe.route_failure_reason, "route_stability_not_confirmed");
  assert.equal(routeCalls, 1);
  console.log("PASS: Worker health does not claim route ready from one deadline-limited probe");
}

async function testHealthRequiresStableRouteReadiness() {
  let routeCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({ ROUTE_WAIT_MS: "5", ROUTE_READY_STABLE_SLEEP_MS: "25" }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, false);
  assert.equal(body.route_probe.usable, true);
  assert.equal(body.route_probe.stable_probes, 1);
  assert.equal(body.route_probe.error, "route_stability_not_confirmed");
  assert.equal(body.route_probe.route_failure_reason, "route_stability_not_confirmed");
  assert.doesNotMatch(body.message, /route is usable/i);
  assert.equal(routeCalls, 1);
  console.log("PASS: Worker health requires stable route readiness");
}

async function testColdWakeReturnsAcceptedProgressWithoutHoldingRequestOpen() {
  let statusCalls = 0;
  const kv = makeKv();
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(JSON.stringify({ state: "Starting" }), {
        status: 202,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      statusCalls += 1;
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Starting",
        pending_operation: true,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(
    makeRequest("/api/wake"),
    baseEnv({ WAKER_KV: kv }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 202);
  assert.equal(body.ok, true);
  assert.equal(body.start_accepted, true);
  assert.equal(body.reason, "codespace_start_accepted");
  assert.equal(body.route_ready, false);
  assert.equal(body.next_action_code, "wait_codespace_available");
  assert.match(body.next_action, /finish starting/i);
  assert.equal(body.github_wait_attempts, 0);
  assert.equal(statusCalls, 1);
  const history = await responseJson(await worker.fetch(makeRequest("/api/history"), baseEnv({ WAKER_KV: kv }), {}));
  assert.equal(history.history[0].route_checked, false);
  console.log("PASS: Cold wake returns accepted progress without holding the request open");
}

async function testHistorySeparatesFailedProbeDurationFromReadyLatency() {
  const kv = makeKv();
  let ready = false;
  let routeCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: ready ? 200 : 404 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({ WAKER_KV: kv, ROUTE_WAIT_MS: "5", ROUTE_POLL_INTERVAL_MS: "1" }),
    {}
  );
  let history = await responseJson(await worker.fetch(makeRequest("/api/history"), baseEnv({ WAKER_KV: kv }), {}));
  assert.equal(history.history[0].route_ready, false);
  assert.equal(history.history[0].route_latency_ms, null);
  assert.equal(Number.isFinite(history.history[0].route_probe_duration_ms), true);

  ready = true;
  await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({ WAKER_KV: kv, ROUTE_READY_STABLE_SLEEP_MS: "0" }),
    {}
  );
  history = await responseJson(await worker.fetch(makeRequest("/api/history"), baseEnv({ WAKER_KV: kv }), {}));
  assert.equal(history.history[0].route_ready, true);
  assert.equal(Number.isFinite(history.history[0].route_latency_ms), true);
  assert.equal(Number.isFinite(history.history[0].route_probe_duration_ms), true);
  assert.equal(routeCalls >= 3, true);
  console.log("PASS: Worker history separates failed probe duration from ready-route latency");
}

async function testDashboardIncludesRouteHistorySummaryUi() {
  const response = await worker.fetch(new Request("https://worker.example/wake"), baseEnv(), {});
  const html = await response.text();
  assert.equal(response.status, 200);
  assert.match(html, /Route history summary/);
  assert.match(html, /Quota Survival/);
  assert.match(html, /quotaBlocked/);
  assert.match(html, /quotaReset/);
  assert.match(html, /latencyTrend/);
  assert.match(html, /renderHistorySummary/);
  assert.match(html, /historySummary\.innerHTML = "";/);
  assert.match(html, /History request failed:/);
  assert.match(html, /event\.route_checked === true/);
  console.log("PASS: Worker dashboard includes route history summary UI");
}

async function testHistoryRejectsBadSecretClearly() {
  const response = await worker.fetch(makeRequest("/api/history", "wrong-secret"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 401);
  assert.equal(body.ok, false);
  assert.equal(body.error, "unauthorized");
  assert.equal(body.next_action_code, "fix_wake_secret");
  assert.match(body.next_action, /WAKE_SECRET/);
  console.log("PASS: Worker history rejects bad wake secret clearly");
}

async function testResponsesIncludeSecurityHeaders() {
  const htmlResponse = await worker.fetch(new Request("https://worker.example/wake"), baseEnv(), {});
  assert.equal(htmlResponse.headers.get("x-content-type-options"), "nosniff");
  assert.equal(htmlResponse.headers.get("referrer-policy"), "no-referrer");
  assert.equal(htmlResponse.headers.get("x-frame-options"), "DENY");
  assert.ok(htmlResponse.headers.get("x-g2ray-request-id"));
  assert.match(htmlResponse.headers.get("content-security-policy") || "", /frame-ancestors 'none'/);

  const jsonResponse = await worker.fetch(makeRequest("/api/history", "wrong-secret"), baseEnv(), {});
  assert.equal(jsonResponse.headers.get("x-content-type-options"), "nosniff");
  assert.equal(jsonResponse.headers.get("referrer-policy"), "no-referrer");
  assert.match(jsonResponse.headers.get("content-security-policy") || "", /default-src 'none'/);
  console.log("PASS: Worker responses include security headers");
}

async function testWakeQueuesNotificationsWithWaitUntil() {
  let routeCalls = 0;
  let notificationCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(JSON.stringify({ state: "Available" }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 200 });
    }
    if (url.includes("discord.example")) {
      notificationCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const waitUntilPromises = [];
  const response = await worker.fetch(
    makeRequest("/api/wake"),
    baseEnv({ DISCORD_WEBHOOK_URL: "https://discord.example/hook" }),
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, true);
  assert.equal(body.notification_status, "deferred");
  assert.equal(body.notifications_deferred, true);
  assert.deepEqual(body.notification_errors, []);
  assert.equal(waitUntilPromises.length, 1);
  await Promise.all(waitUntilPromises);
  assert.equal(routeCalls >= 2, true);
  assert.equal(notificationCalls, 1);
  console.log("PASS: Worker queues notifications with waitUntil");
}

async function testWakeReportsNoNotificationsWhenChannelsAreUnconfigured() {
  let routeCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(JSON.stringify({
        state: "Available"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const waitUntilPromises = [];
  const response = await worker.fetch(
    makeRequest("/api/wake"),
    baseEnv(),
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, true);
  assert.equal(body.notification_status, "none");
  assert.equal(body.notifications_deferred, false);
  assert.deepEqual(body.notification_errors, []);
  assert.equal(waitUntilPromises.length, 0);
  assert.equal(routeCalls >= 2, true);
  console.log("PASS: Worker reports no notifications when channels are unconfigured");
}

async function testHealthQueuesTokenFailureNotificationWithWaitUntil() {
  let routeCalls = 0;
  let notificationCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({ message: "Bad credentials" }), {
        status: 401,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 404 });
    }
    if (url.includes("discord.example")) {
      notificationCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const waitUntilPromises = [];
  const response = await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({ DISCORD_WEBHOOK_URL: "https://discord.example/hook" }),
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  const body = await responseJson(response);
  assert.equal(response.status, 401);
  assert.equal(body.reason, "github_token_rejected_or_missing_scope");
  assert.equal(body.notification_status, "deferred");
  assert.equal(body.notifications_deferred, true);
  assert.deepEqual(body.notification_errors, []);
  assert.equal(waitUntilPromises.length, 1);
  await Promise.all(waitUntilPromises);
  assert.equal(routeCalls, 0);
  assert.equal(notificationCalls, 1);
  console.log("PASS: Worker queues health token-failure notifications with waitUntil");
}

async function testHealthQueuesMissingScopeNotificationAndDoesNotCountRouteCheck() {
  const kv = makeKv();
  let routeCalls = 0;
  let notificationCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({ message: "Resource not accessible by personal access token" }), {
        status: 403,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 404 });
    }
    if (url.includes("discord.example")) {
      notificationCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({ WAKER_KV: kv, DISCORD_WEBHOOK_URL: "https://discord.example/hook" });
  const waitUntilPromises = [];
  const response = await worker.fetch(
    makeRequest("/api/health"),
    env,
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  const body = await responseJson(response);
  assert.equal(response.status, 403);
  assert.equal(body.reason, "github_token_scope_missing");
  assert.equal(body.route_checked, false);
  assert.equal(body.route_ready, null);
  assert.equal(body.notification_status, "deferred");
  assert.equal(waitUntilPromises.length, 2);
  await Promise.all(waitUntilPromises);
  const history = await responseJson(await worker.fetch(makeRequest("/api/history"), baseEnv({ WAKER_KV: kv }), {}));
  assert.equal(history.history[0].route_checked, false);
  assert.equal(history.history[0].route_ready, null);
  assert.equal(routeCalls, 0);
  assert.equal(notificationCalls, 1);
  console.log("PASS: Worker notifies missing-scope health failures without counting a route probe");
}

async function testDeferredNotificationFailureIsMarkedDeferred() {
  let routeCalls = 0;
  let notificationCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(JSON.stringify({
        state: "Available"
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 200 });
    }
    if (url.includes("discord.example")) {
      notificationCalls += 1;
      return new Response("bad webhook", { status: 500 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const waitUntilPromises = [];
  const response = await worker.fetch(
    makeRequest("/api/wake"),
    baseEnv({ DISCORD_WEBHOOK_URL: "https://discord.example/hook" }),
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, true);
  assert.equal(body.notification_status, "deferred");
  assert.equal(body.notifications_deferred, true);
  assert.deepEqual(body.notification_errors, []);
  assert.equal(waitUntilPromises.length, 1);
  await Promise.all(waitUntilPromises);
  assert.equal(routeCalls >= 2, true);
  assert.equal(notificationCalls, 1);
  console.log("PASS: Worker marks deferred notification failures as deferred");
}

async function testHistorySideEffectsCanDeferWithWaitUntil() {
  const kv = makeKv();
  let routeCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const waitUntilPromises = [];
  const response = await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({ WAKER_KV: kv }),
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.history_recorded, false);
  assert.equal(body.history_deferred, true);
  assert.equal(waitUntilPromises.length, 1);
  await Promise.all(waitUntilPromises);
  const history = await responseJson(await worker.fetch(makeRequest("/api/history"), baseEnv({ WAKER_KV: kv }), {}));
  assert.equal(history.history.length, 1);
  assert.equal(history.history[0].next_action_code, "retry_vless_config");
  assert.equal(routeCalls >= 2, true);
  console.log("PASS: Worker defers KV history side effects through waitUntil");
}

async function testRegistryAcceptsPrimaryGithubTokenBinding() {
  const kv = makeKv();
  await kv.put("codespace-registry:v1", JSON.stringify({
    default_codespace_id: "primary",
    codespaces: [{
      id: "primary",
      label: "Primary",
      name: "primary-space",
      token_binding: "GITHUB_TOKEN",
      route_ports: [443]
    }]
  }));
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url.includes("api.github.com/user/codespaces/primary-space")) {
      assert.equal(init.headers?.authorization, "Bearer primary-token");
      return new Response(JSON.stringify({
        name: "primary-space",
        state: "Available",
        pending_operation: false
      }), { status: 200, headers: { "content-type": "application/json" } });
    }
    if (url.includes("app.github.dev")) return new Response("", { status: 200 });
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(
    makeRequest("/api/health", "secret", { codespace_id: "primary" }),
    baseEnv({ WAKER_KV: kv, GITHUB_TOKEN: "primary-token" }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200, JSON.stringify(body));
  assert.equal(body.codespace, "primary-space");
  console.log("PASS: Worker registry accepts the primary GITHUB_TOKEN binding");
}

async function testWorkerDeduplicatesNoisyHealthHistory() {
  const kv = makeKv();
  let routeCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({ WAKER_KV: kv });
  for (let i = 0; i < 2; i += 1) {
    const waitUntilPromises = [];
    const response = await worker.fetch(
      makeRequest("/api/health"),
      env,
      { waitUntil(promise) { waitUntilPromises.push(promise); } }
    );
    assert.equal(response.status, 200);
    await Promise.all(waitUntilPromises);
  }
  const history = await responseJson(await worker.fetch(makeRequest("/api/history"), env, {}));
  assert.equal(history.history.length, 1);
  assert.equal(routeCalls >= 4, true);
  console.log("PASS: Worker deduplicates noisy identical health history");
}

async function testHealthRouteReadyTransitionNotification() {
  const kv = makeKv();
  await kv.put("history:behavior-space", JSON.stringify([{
    ts: "2026-06-01T00:00:00.000Z",
    kind: "wake",
    ok: true,
    codespace: "behavior-space",
    state: "Available",
    route_ready: false,
    route_http_status: 404,
    next_action_code: "wait_route_or_recover"
  }]));
  let notificationCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      return new Response("", { status: 200 });
    }
    if (url.includes("discord.example")) {
      notificationCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({ WAKER_KV: kv, DISCORD_WEBHOOK_URL: "https://discord.example/hook" });
  const waitUntilPromises = [];
  const response = await worker.fetch(
    makeRequest("/api/health"),
    env,
    { waitUntil(promise) { waitUntilPromises.push(promise); } }
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, true);
  assert.equal(body.notification_status, "deferred");
  assert.equal(waitUntilPromises.length, 2);
  await Promise.all(waitUntilPromises);
  const secondWaitUntilPromises = [];
  const secondResponse = await worker.fetch(
    makeRequest("/api/health"),
    env,
    { waitUntil(promise) { secondWaitUntilPromises.push(promise); } }
  );
  const secondBody = await responseJson(secondResponse);
  assert.equal(secondResponse.status, 200);
  assert.equal(secondBody.route_ready, true);
  assert.equal(secondBody.notification_status, "none");
  await Promise.all(secondWaitUntilPromises);
  const history = await responseJson(await worker.fetch(makeRequest("/api/history"), baseEnv({ WAKER_KV: kv }), {}));
  const transitions = history.history.filter((item) => item.route_ready_transition);
  assert.equal(transitions.length, 1);
  assert.equal(transitions[0].previous_route_http_status, 404);
  assert.equal(notificationCalls, 1);
  console.log("PASS: Worker notifies once when health observes route-ready transition");
}

async function testHealthRouteUrlUsesForwardingDomainOverride() {
  let probedUrl = "";
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("ports.example.test")) {
      probedUrl = url;
      return new Response("", { status: 200 });
    }
    if (url.includes("app.github.dev")) {
      throw new Error(`route probe ignored forwarding domain: ${url}`);
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(
    makeRequest("/api/health"),
    baseEnv({ CODESPACE_FORWARDING_DOMAIN: "ports.example.test" }),
    {}
  );
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.route_ready, true);
  assert.equal(body.route_probe.url, "https://behavior-space-443.ports.example.test/");
  assert.equal(probedUrl, "https://behavior-space-443.ports.example.test/");
  console.log("PASS: Worker route probes honor forwarding-domain overrides");
}

async function testWakeSkipsGithubStartWhenAlreadyAvailableAndRouteReady() {
  let startCalls = 0;
  let statusCalls = 0;
  let routeCalls = 0;
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      if (url.endsWith("/start") || init.method === "POST") {
        startCalls += 1;
        return new Response(JSON.stringify({ message: "start should be skipped" }), {
          status: 500,
          headers: { "content-type": "application/json" }
        });
      }
      statusCalls += 1;
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/wake"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 200);
  assert.equal(body.ok, true);
  assert.equal(body.route_ready, true);
  assert.equal(body.wake_fast_path, true);
  assert.equal(body.start_accepted, false);
  assert.equal(startCalls, 0);
  assert.equal(statusCalls, 1);
  assert.equal(routeCalls >= 2, true);
  console.log("PASS: Worker wake skips GitHub start when Codespace route is already warm");
}

async function testWakeSkipsGithubStartWhenCodespaceIsAvailableButRouteSettling() {
  let startCalls = 0;
  let statusCalls = 0;
  let routeCalls = 0;
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      if (url.endsWith("/start") || init.method === "POST") {
        startCalls += 1;
        throw new Error("available Codespace must not receive a redundant start request");
      }
      statusCalls += 1;
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        last_used_at: "2026-05-30T00:00:00Z",
        idle_timeout_minutes: 240
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }
    if (url.includes("app.github.dev")) {
      routeCalls += 1;
      return new Response("", { status: 404 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const response = await worker.fetch(makeRequest("/api/wake"), baseEnv(), {});
  const body = await responseJson(response);
  assert.equal(response.status, 202);
  assert.equal(body.ok, true);
  assert.equal(body.wake_fast_path, true);
  assert.equal(body.start_accepted, false);
  assert.equal(body.route_ready, false);
  assert.equal(body.route_probe.http_status, 404);
  assert.equal(body.next_action_code, "wait_route_or_recover");
  assert.equal(startCalls, 0);
  assert.equal(statusCalls, 1);
  assert.equal(routeCalls, 1);
  console.log("PASS: Worker wake does not restart an available Codespace just because its route is settling");
}

async function testSimpleDashboardSetupAcceptsRealCodespaceName() {
  const authorizations = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      authorizations.push(init.headers?.authorization || "");
      return new Response(JSON.stringify({
        name: "behavior-space",
        state: "Available",
        pending_operation: false,
        idle_timeout_minutes: 120
      }), { status: 200, headers: { "content-type": "application/json" } });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv();
  const directoryResponse = await worker.fetch(makeRequest("/api/codespaces"), env, {});
  const directory = await responseJson(directoryResponse);
  assert.equal(directoryResponse.status, 200);
  assert.equal(directory.multi_codespace, false);
  assert.equal(directory.default_codespace_id, "behavior-space");
  assert.equal(directory.codespaces[0].id, "behavior-space");

  const healthResponse = await worker.fetch(
    makeRequest("/api/health?route=false", "secret", { codespace_id: "behavior-space" }),
    env,
    {}
  );
  const health = await responseJson(healthResponse);
  assert.equal(healthResponse.status, 200);
  assert.equal(health.codespace_id, "behavior-space");
  assert.equal(health.codespace, "behavior-space");
  const staleIdResponse = await worker.fetch(
    makeRequest("/api/health?route=false", "secret", { codespace_id: "legacy-default" }),
    env,
    {}
  );
  const staleIdHealth = await responseJson(staleIdResponse);
  assert.equal(staleIdResponse.status, 200);
  assert.equal(staleIdHealth.codespace_id, "behavior-space");
  assert.deepEqual(authorizations, ["Bearer github-token", "Bearer github-token"]);
  console.log("PASS: Worker simple dashboard setup accepts the real Codespace name");
}

async function testDashboardVariablesSupportMultipleCodespacesWithoutKv() {
  const authorizations = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      authorizations.push(init.headers?.authorization || "");
      return new Response(JSON.stringify({
        name: "standby-space",
        state: "Available",
        pending_operation: false,
        idle_timeout_minutes: 120
      }), { status: 200, headers: { "content-type": "application/json" } });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  const env = baseEnv({
    CODESPACE_2_NAME: "standby-space",
    GITHUB_TOKEN_2: "standby-token"
  });
  const directoryResponse = await worker.fetch(makeRequest("/api/codespaces"), env, {});
  const directory = await responseJson(directoryResponse);
  assert.equal(directoryResponse.status, 200);
  assert.equal(directory.multi_codespace, true);
  assert.equal(directory.codespaces.length, 2);
  assert.equal(directory.codespaces[1].id, "standby-space");

  const healthResponse = await worker.fetch(
    makeRequest("/api/health?route=false", "secret", { codespace_id: "standby-space" }),
    env,
    {}
  );
  const health = await responseJson(healthResponse);
  assert.equal(healthResponse.status, 200);
  assert.equal(health.codespace_id, "standby-space");
  assert.deepEqual(authorizations, ["Bearer standby-token"]);
  console.log("PASS: Worker dashboard variables support multiple Codespaces without KV");
}

async function testWorkerRegistrySelectsBoundTokenAndReturnsSafeDirectory() {
  const kv = makeKv();
  await kv.put("codespace-registry:v1", JSON.stringify({
    default_codespace_id: "alpha",
    codespaces: [
      {
        id: "alpha",
        label: "Primary Codespace",
        name: "alpha-space",
        token_binding: "GITHUB_TOKEN_ALPHA",
        route_ports: [443, 8443],
        priority: 10
      },
      {
        id: "beta",
        label: "Quota standby",
        name: "beta-space",
        token_binding: "GITHUB_TOKEN_BETA",
        route_ports: [443]
      }
    ]
  }));
  const authorizations = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url.includes("api.github.com")) {
      authorizations.push(init.headers?.authorization || "");
      return new Response(JSON.stringify({
        name: "beta-space",
        state: "Available",
        pending_operation: false,
        idle_timeout_minutes: 120
      }), { status: 200, headers: { "content-type": "application/json" } });
    }
    if (url.includes("app.github.dev")) return new Response("", { status: 200 });
    throw new Error(`unexpected fetch ${url}`);
  };
  const env = baseEnv({
    WAKER_KV: kv,
    GITHUB_TOKEN_ALPHA: "alpha-token",
    GITHUB_TOKEN_BETA: "beta-token"
  });
  const directoryResponse = await worker.fetch(makeRequest("/api/codespaces"), env, {});
  const directory = await responseJson(directoryResponse);
  assert.equal(directoryResponse.status, 200);
  assert.equal(directory.multi_codespace, true);
  assert.equal(directory.codespaces.length, 2);
  assert.equal(directory.codespaces[0].token_binding, undefined);
  assert.deepEqual(directory.codespaces[0].route_ports, [443, 8443]);

  const healthResponse = await worker.fetch(makeRequest("/api/health", "secret", { codespace_id: "beta" }), env, {});
  const health = await responseJson(healthResponse);
  assert.equal(healthResponse.status, 200);
  assert.equal(health.codespace_id, "beta");
  assert.equal(health.codespace_label, "Quota standby");
  assert.equal(health.codespace, "beta-space");
  assert.equal(health.preferred_route_port, 443);
  assert.deepEqual(authorizations, ["Bearer beta-token"]);

  const jsonAuthResponse = await worker.fetch(
    makeRequest("/api/health", "", { wake_secret: "secret", codespace_id: "alpha" }),
    env,
    {}
  );
  const jsonAuth = await responseJson(jsonAuthResponse);
  assert.equal(jsonAuthResponse.status, 200);
  assert.equal(jsonAuth.codespace_id, "alpha");
  assert.deepEqual(authorizations, ["Bearer beta-token", "Bearer alpha-token"]);

  const unknownResponse = await worker.fetch(makeRequest("/api/health", "secret", { codespace_id: "missing" }), env, {});
  const unknown = await responseJson(unknownResponse);
  assert.equal(unknownResponse.status, 404);
  assert.equal(unknown.error, "codespace_not_registered");
  console.log("PASS: Worker registry isolates Codespaces, token bindings, and route ports");
}

try {
  await testFailedSecretRateLimit();
  await testFailedSecretRateLimitCapsKvWrites();
  await testMissingWakeSecretIsConfigurationError();
  await testMissingCodespaceNameIsActionable();
  await testGithubRateLimitClassification();
  await testGithubScopeFailureClassification();
  await testGithubUnknownForbiddenClassificationIsNeutral();
  await testWakeSettlingIncludesRetryMetadata();
  await testHealthCanSkipRouteProbe();
  await testHealthDoesNotProbeRouteWhileCodespaceIsShutdown();
  await testHealthSkipRoutePreservesGithubFailureGuidance();
  await testAuthorizedWakeCooldownIsOptional();
  await testGithubHttp429Classification();
  await testGithubStartRetriesTransientServerFailure();
  await testQuotaBlockIncludesSurvivalFields();
  await testRetentionMissingFieldsAreUnknown();
  await testMonthlyResetEstimateCrossesYear();
  await testKvQuotaIncidentHistoryRecordsBlockedAndRecovery();
  await testQuotaIncidentKeepsResetEstimateDuringActiveDroughtHealthChecks();
  await testQuotaIncidentRouteReadyHealthBeforeResetDoesNotClearDrought();
  await testRepeatedHealthDoesNotRewriteUnchangedQuotaIncident();
  await testHistoryWorksWithoutGithubToken();
  await testScheduledQuotaCronIsDisabledAndThrottledBeforeReset();
  await testScheduledQuotaCronAttemptsOneNearResetWake();
  await testScheduledQuotaCronRetriesAfterResetIfPreResetWakeStillBlocked();
  await testScheduledQuotaCronKeepsRetryingAfterResetWhileStillBlocked();
  await testWakeFailureIncludesNextAction();
  await testHealthRejectsHttp400RouteAsUnusable();
  await testOptionalPortCannotMasqueradeAsPrimaryXhttpReadiness();
  await testWakeRequiresStableRouteReadiness();
  await testHealthWaitsBetweenStableRouteProbes();
  await testRouteFetchTimeoutOverrideIsApplied();
  await testHealthDoesNotClaimReadyFromSingleDeadlineProbe();
  await testHealthRequiresStableRouteReadiness();
  await testColdWakeReturnsAcceptedProgressWithoutHoldingRequestOpen();
  await testHistorySeparatesFailedProbeDurationFromReadyLatency();
  await testDashboardIncludesRouteHistorySummaryUi();
  await testHistoryRejectsBadSecretClearly();
  await testResponsesIncludeSecurityHeaders();
  await testWakeQueuesNotificationsWithWaitUntil();
  await testWakeReportsNoNotificationsWhenChannelsAreUnconfigured();
  await testHealthQueuesTokenFailureNotificationWithWaitUntil();
  await testHealthQueuesMissingScopeNotificationAndDoesNotCountRouteCheck();
  await testDeferredNotificationFailureIsMarkedDeferred();
  await testHistorySideEffectsCanDeferWithWaitUntil();
  await testWorkerDeduplicatesNoisyHealthHistory();
  await testHealthRouteReadyTransitionNotification();
  await testHealthRouteUrlUsesForwardingDomainOverride();
  await testWakeSkipsGithubStartWhenAlreadyAvailableAndRouteReady();
  await testWakeSkipsGithubStartWhenCodespaceIsAvailableButRouteSettling();
  await testSimpleDashboardSetupAcceptsRealCodespaceName();
  await testDashboardVariablesSupportMultipleCodespacesWithoutKv();
  await testWorkerRegistrySelectsBoundTokenAndReturnsSafeDirectory();
  await testRegistryAcceptsPrimaryGithubTokenBinding();
} finally {
  globalThis.fetch = originalFetch;
}

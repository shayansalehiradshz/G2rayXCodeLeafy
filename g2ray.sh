#!/bin/bash

set -euo pipefail

readonly G2RAY_ID="G2ray Panel v1.4.3"
BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_PATH="$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || printf '%s/%s' "$BASE_DIR" "$(basename "${BASH_SOURCE[0]}")")"
SCRIPT_NAME="$(basename "$SCRIPT_PATH")"

detect_project_repo_default() {
    local remote url slug upstream_ref upstream_remote
    upstream_ref=$(git -C "$BASE_DIR" rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || true)
    upstream_remote="${upstream_ref%%/*}"
    for remote in "$upstream_remote" origin poul shaun upstream; do
        [[ -n "$remote" && "$remote" != "$upstream_ref" ]] || continue
        url=$(git -C "$BASE_DIR" remote get-url "$remote" 2>/dev/null || true)
        [[ -n "$url" ]] || continue
        slug=$(printf '%s' "$url" \
            | sed -E 's#^git@github.com:##; s#^https://github.com/##; s#\.git$##')
        if [[ "$slug" =~ ^[^/]+/[^/]+$ ]]; then
            printf '%s\n' "$slug"
            return 0
        fi
    done
    printf 'Code-Leafy/G2rayXCodeLeafy\n'
}

PROJECT_REPO="${G2RAY_PROJECT_REPO:-$(detect_project_repo_default)}"
RAW_BASE_URL="${G2RAY_RAW_BASE_URL:-https://raw.githubusercontent.com/${PROJECT_REPO}/main}"

G2RAY_BENCH_PREINIT_TMP="${G2RAY_BENCH_PREINIT_TMP:-}"
# Benchmarks isolate to a throwaway runtime dir by default. Only an explicit
# `--live` argument runs them against the real data/logs (which would reassert
# port visibility and rewrite exports). This keeps plain `g2ray.sh bench` safe.
G2RAY_BENCH_WANTS_LIVE=0
case "${1:-}" in
    --bench|bench)
        for _bench_arg in "$@"; do
            [[ "$_bench_arg" == "--live" ]] && G2RAY_BENCH_WANTS_LIVE=1
        done
        if [[ "$G2RAY_BENCH_WANTS_LIVE" != "1" ]]; then
            umask 077
            if ! G2RAY_BENCH_PREINIT_TMP=$(mktemp -d "${TMPDIR:-/tmp}/g2ray-bench.XXXXXX"); then
                echo "Could not create temporary benchmark runtime directory." >&2
                exit 1
            fi
            G2RAY_DATA_DIR="$G2RAY_BENCH_PREINIT_TMP/data"
            G2RAY_LOG_DIR="$G2RAY_BENCH_PREINIT_TMP/logs"
        fi
        ;;
esac

GREEN='\033[1;32m'; WHITE='\033[1;37m'; RED='\033[1;31m'
YELLOW='\033[1;33m'; DIM='\033[2m'; NC='\033[0m'; B='\033[1m'

DATA_DIR="${G2RAY_DATA_DIR:-$BASE_DIR/data}"
CONFIG_FILE="$DATA_DIR/config.json"
UUID_FILE="$DATA_DIR/uuid.txt"
BG_TASKS_PID="$DATA_DIR/bg_tasks.pid"
BG_TASKS_VERSION_FILE="$DATA_DIR/bg_tasks.version"
BG_TASKS_LOCK_DIR="$DATA_DIR/bg_tasks.lock"
RUNTIME_LOCK_DIR="$DATA_DIR/runtime.lock"
BG_TASKS_TOKEN_FILE="$DATA_DIR/bg_tasks.token"
BG_TASKS_HEARTBEAT_FILE="$DATA_DIR/bg_tasks.heartbeat"
BG_WATCHDOG_PID_FILE="$DATA_DIR/bg_tasks.watchdog.pid"
ROUTE_SETTLE_PID_FILE="$DATA_DIR/route_settle.pid"
ROUTE_SETTLE_TOKEN_FILE="$DATA_DIR/route_settle.token"
RESUME_GAP_FILE="$DATA_DIR/resume_gap.txt"
WAKER_METADATA_FILE="$DATA_DIR/waker_metadata.txt"
WAKER_PROMPT_FILE="$DATA_DIR/.waker_setup_prompted"
ROUTE_BAD_COUNT_FILE="$DATA_DIR/xhttp_route_bad_count"
ROUTE_LATENCY_BASELINE_FILE="$DATA_DIR/route_latency_baseline"
ROUTE_DEGRADED_REQUEST_FILE="$DATA_DIR/route_degraded_request"
ROUTE_DEGRADED_REFRESH_STAMP_FILE="$DATA_DIR/route_degraded_refresh_stamp"
UPDATE_AUDIT_FILE="$DATA_DIR/update_audit.log"
EDGE_BAD_COUNT_FILE="$DATA_DIR/edge_bad_count"
EDGE_RECONNECT_STAMP_FILE="$DATA_DIR/edge_reconnect_last"
ROUTE_HEALTH_FILE="$DATA_DIR/route_candidate_health.tsv"
ROUTE_STATS_FILE="$DATA_DIR/route_candidate_stats.tsv"
ROUTE_COOLDOWN_FILE="$DATA_DIR/route_candidate_cooldowns.tsv"
ROUTE_REFRESH_STATE_FILE="$DATA_DIR/route_refresh_state.tsv"
ROUTE_STATE_LOCK_FILE="$DATA_DIR/route-state.lock"
ROUTE_REFRESH_REQUEST_LOCK_FILE="$DATA_DIR/route-refresh-request.lock"
TRAFFIC_STATS_LOCK_FILE="$DATA_DIR/traffic-stats.lock"
EXPORT_LOCK_FILE="$DATA_DIR/config-exports.lock"
DNS_CANDIDATE_CACHE_FILE="$DATA_DIR/dns_candidate_cache.tsv"
BOOT_STATUS_FILE="$DATA_DIR/boot_status.json"
XHTTP_PATH_CACHE_FILE="$DATA_DIR/xhttp_path_cache"
XHTTP_MODE_CACHE_FILE="$DATA_DIR/xhttp_mode_cache"
XHTTP_MODE_FILE="$DATA_DIR/xhttp_mode.txt"
XHTTP_LINK_TUNING_FILE="$DATA_DIR/xhttp_link_tuning.txt"
ANTI_SLEEP_DISABLED_FILE="$DATA_DIR/anti_sleep_mode_disabled"
LOW_OVERHEAD_FILE="$DATA_DIR/low_overhead_mode"
LOW_OVERHEAD_DISABLED_FILE="$DATA_DIR/low_overhead_mode_disabled"
LOW_OVERHEAD_PREV_PROFILE_FILE="$DATA_DIR/low_overhead_previous_profile.txt"
LATENCY_FOCUS_FILE="$DATA_DIR/latency_focus_mode"
LATENCY_FOCUS_DISABLED_FILE="$DATA_DIR/latency_focus_mode_disabled"
LATENCY_FOCUS_PREV_PROFILE_FILE="$DATA_DIR/latency_focus_previous_profile.txt"
WS_FALLBACK_FILE="$DATA_DIR/ws_fallback_mode"
WS_FALLBACK_DISABLED_FILE="$DATA_DIR/ws_fallback_mode_disabled"
WS_FRONT_DOMAIN_FILE="$DATA_DIR/ws_front_domain.txt"
DOMAIN_LINK_EXPORT_FILE="$DATA_DIR/export_domain_link.txt"
LAST_GOOD_ROUTE_FILE="$DATA_DIR/last_good_route.txt"
PINNED_ROUTE_FILE="$DATA_DIR/pinned_route.txt"
MANUAL_ROUTE_CANDIDATES_FILE="$DATA_DIR/manual_route_candidates.txt"
BLACKLISTED_ROUTE_CANDIDATES_FILE="$DATA_DIR/blacklisted_route_candidates.txt"
ROUTE_SETTLING_HISTORY_FILE="$DATA_DIR/route_settling_history.tsv"
PORT_PUBLIC_STAMP_FILE="$DATA_DIR/port_public_last"
ROUTE_REPAIR_STAMP_FILE="$DATA_DIR/route_repair_last"
QUOTA_CYCLE_FILE="$DATA_DIR/quota_cycle.txt"
PERFORMANCE_PROFILE_FILE="$DATA_DIR/performance_profile.txt"
XRAY_PID_FILE="$DATA_DIR/xray.pid"
SAVED_BYTES_FILE="$DATA_DIR/saved_bytes.json"
SESSION_BYTES_FILE="$DATA_DIR/session_bytes.json"
ACTIVE_TRAFFIC_STAMP_FILE="$DATA_DIR/active_traffic_last"
TOTAL_UPTIME_FILE="$DATA_DIR/total_uptime_sec.txt"
SESSION_START_FILE="$DATA_DIR/session_start.txt"
# In-process lifecycle evidence used by transactional config rollback. This is
# deliberately not persisted: it describes only the current start attempt.
START_XRAY_LAST_PHASE="idle"
LOG_DIR="${G2RAY_LOG_DIR:-$BASE_DIR/logs}"
ROUTE_SETTLE_LOG_FILE="$LOG_DIR/route-settle.log"
LOG_FILE="$LOG_DIR/g2ray.log"
STRUCTURED_LOG_FILE="$LOG_DIR/g2ray-events.jsonl"
DIAGNOSTIC_LOG_FILE="$LOG_DIR/g2ray-diagnostics.log"
LOG_ROTATE_STAMP_FILE="$DATA_DIR/log_rotate_last"
STRUCTURED_LOG_ROTATE_STAMP_FILE="$DATA_DIR/structured_log_rotate_last"
DIAGNOSTIC_LOG_ROTATE_STAMP_FILE="$DATA_DIR/diagnostic_log_rotate_last"
LOG_CODE_VERSION_FILE="$DATA_DIR/log_code_version"
QR_DIR="$DATA_DIR/qr"
MOBILE_CONFIG_FILE="$BASE_DIR/configs-to-copy-for-mobile.txt"
SUBSCRIPTION_FILE="$BASE_DIR/configs-subscription-base64.txt"
CONFIG_META_FILE="$BASE_DIR/configs-meta.json"
CODESPACE_BUNDLE_FILE="$BASE_DIR/g2ray-codespace-bundle.json"
EXPORT_INPUT_HASH_FILE="$DATA_DIR/export_input.hash"
CODESPACE_ENV_JSON_FILE="${G2RAY_CODESPACE_ENV_JSON_FILE:-/workspaces/.codespaces/shared/environment-variables.json}"
CODESPACE_SHARED_ENV_FILE="${G2RAY_CODESPACE_SHARED_ENV_FILE:-/workspaces/.codespaces/shared/.env}"
XRAY_BIN="/usr/local/bin/xray"
XRAY_PORT="${XRAY_PORT:-443}"
[[ "$XRAY_PORT" =~ ^[0-9]+$ && "$XRAY_PORT" -gt 0 && "$XRAY_PORT" -le 65535 ]] || XRAY_PORT=443
WS_PORT="${G2RAY_WS_PORT:-8443}"
[[ "$WS_PORT" =~ ^[0-9]+$ && "$WS_PORT" -gt 0 && "$WS_PORT" -le 65535 ]] || WS_PORT=8443
if [[ "$WS_PORT" == "$XRAY_PORT" ]]; then
    [[ "$XRAY_PORT" == "8443" ]] && WS_PORT=8444 || WS_PORT=8443
fi
# Keep the advanced WebSocket fallback alive through idle HTTP intermediaries.
# XHTTP remains the primary transport; this only affects an enabled WS inbound.
WS_HEARTBEAT_PERIOD_SEC=30
# Server-side reorder window for packet-up upload POSTs. Tuned links send many
# 60 KB POSTs instead of a few 1 MB ones; Xray tears the session down once more
# than this many arrive out of order, so keep a margin above its default of 30
# for jittery mobile paths. This is a count limit, not a byte cap: ~6 MB for
# tuned 60 KB POSTs, but ~100 MB for legacy 1 MB POSTs, before authentication.
XHTTP_SERVER_MAX_BUFFERED_POSTS=100
CODESPACES_EDGE_PORT="${G2RAY_CODESPACES_EDGE_PORT:-443}"
PORT_FORWARDING_DOMAIN="${G2RAY_PORT_FORWARDING_DOMAIN:-${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}}"
PORT_FORWARDING_DOMAIN="${PORT_FORWARDING_DOMAIN#https://}"
PORT_FORWARDING_DOMAIN="${PORT_FORWARDING_DOMAIN#http://}"
PORT_FORWARDING_DOMAIN="${PORT_FORWARDING_DOMAIN%%/*}"
PORT_FORWARDING_DOMAIN="${PORT_FORWARDING_DOMAIN#.}"
[[ "$PORT_FORWARDING_DOMAIN" =~ ^[A-Za-z0-9.-]+$ && "$PORT_FORWARDING_DOMAIN" == *.* ]] || PORT_FORWARDING_DOMAIN="app.github.dev"
DEFAULT_FALLBACK_IPS="${G2RAY_DEFAULT_FALLBACK_IPS:-20.69.79.91 20.85.77.48 20.120.56.11 20.125.70.28 20.90.66.7 20.103.221.187 20.207.70.99}"
MAX_FALLBACK_LINKS="${G2RAY_MAX_FALLBACK_LINKS:-30}"
WS_MAX_FALLBACK_LINKS="${G2RAY_WS_MAX_FALLBACK_LINKS:-3}"
ROUTE_MONITOR_MAX_CANDIDATES="${G2RAY_ROUTE_MONITOR_MAX_CANDIDATES:-40}"
WIDE_DNS_DISCOVERY="${G2RAY_WIDE_DNS_DISCOVERY:-1}"
WIDE_DNS_ECS_SUBNETS="${G2RAY_WIDE_DNS_ECS_SUBNETS:-0.0.0.0/0 1.1.1.0/24 8.8.8.0/24 9.9.9.0/24 208.67.222.0/24 94.140.14.0/24 185.228.168.0/24}"
WIDE_DNS_MAX_ECS_SUBNETS="${G2RAY_WIDE_DNS_MAX_ECS_SUBNETS:-12}"
DIAGNOSTIC_MAX_FALLBACK_PROBES="${G2RAY_DIAGNOSTIC_MAX_FALLBACK_PROBES:-12}"
SELF_HEAL_EDGE_RECONNECT_THRESHOLD="${G2RAY_EDGE_RECONNECT_THRESHOLD:-3}"
SELF_HEAL_RECONNECT_COOLDOWN_SEC="${G2RAY_RECONNECT_COOLDOWN_SEC:-300}"
ROUTE_WAIT_SEC="${G2RAY_ROUTE_WAIT_SEC:-120}"
FORCE_RECONNECT_ROUTE_WAIT_SEC="${G2RAY_FORCE_RECONNECT_ROUTE_WAIT_SEC:-60}"
HEADLESS_ROUTE_SETTLE_WAIT_SEC="${G2RAY_HEADLESS_ROUTE_SETTLE_WAIT_SEC:-180}"
HEADLESS_ROUTE_SETTLE_INITIAL_DELAY_SEC="${G2RAY_HEADLESS_ROUTE_SETTLE_INITIAL_DELAY_SEC:-5}"
ROUTE_READY_STABLE_PROBES="${G2RAY_ROUTE_READY_STABLE_PROBES:-2}"
ROUTE_READY_STABLE_SLEEP_SEC="${G2RAY_ROUTE_READY_STABLE_SLEEP_SEC:-1}"
ROUTE_HEALTH_TTL_SEC="${G2RAY_ROUTE_HEALTH_TTL_SEC:-300}"
ROUTE_HEALTH_EXPORT_MAX_AGE_SEC="${G2RAY_ROUTE_HEALTH_EXPORT_MAX_AGE_SEC:-21600}"
ROUTE_REFRESH_BACKOFF_INITIAL_SEC="${G2RAY_ROUTE_REFRESH_BACKOFF_INITIAL_SEC:-60}"
ROUTE_REFRESH_BACKOFF_MAX_SEC="${G2RAY_ROUTE_REFRESH_BACKOFF_MAX_SEC:-600}"
DNS_CACHE_TTL_SEC="${G2RAY_DNS_CACHE_TTL_SEC:-300}"
ROUTE_FAILURE_COOLDOWN_SEC="${G2RAY_ROUTE_FAILURE_COOLDOWN_SEC:-180}"
ROUTE_REPAIR_COOLDOWN_SEC="${G2RAY_ROUTE_REPAIR_COOLDOWN_SEC:-180}"
ROUTE_STATS_MAX_AGE_SEC="${G2RAY_ROUTE_STATS_MAX_AGE_SEC:-604800}"
ROUTE_DEGRADED_MULTIPLIER="${G2RAY_ROUTE_DEGRADED_MULTIPLIER:-3}"
ROUTE_DEGRADED_MIN_DELTA_MS="${G2RAY_ROUTE_DEGRADED_MIN_DELTA_MS:-500}"
ROUTE_DEGRADED_MIN_SAMPLES="${G2RAY_ROUTE_DEGRADED_MIN_SAMPLES:-3}"
ROUTE_DEGRADED_TRIGGER_COUNT="${G2RAY_ROUTE_DEGRADED_TRIGGER_COUNT:-2}"
ROUTE_DEGRADED_REFRESH_COOLDOWN_SEC="${G2RAY_ROUTE_DEGRADED_REFRESH_COOLDOWN_SEC:-300}"
ROUTE_PROBE_CONCURRENCY="${G2RAY_ROUTE_PROBE_CONCURRENCY:-6}"
ROUTE_PROBE_JITTER_SEC="${G2RAY_ROUTE_PROBE_JITTER_SEC:-0}"
PORT_PUBLIC_TTL_SEC="${G2RAY_PORT_PUBLIC_TTL_SEC:-300}"
PORT_PUBLIC_STARTUP_ATTEMPTS="${G2RAY_PORT_PUBLIC_STARTUP_ATTEMPTS:-4}"
PORT_PUBLIC_STARTUP_RETRY_SEC="${G2RAY_PORT_PUBLIC_STARTUP_RETRY_SEC:-2}"
LOCAL_PROBE_TIMEOUT_SEC="${G2RAY_LOCAL_PROBE_TIMEOUT_SEC:-1}"
EXTERNAL_PROBE_TIMEOUT_SEC="${G2RAY_EXTERNAL_PROBE_TIMEOUT_SEC:-5}"
CONNECT_TIMEOUT_SEC="${G2RAY_CONNECT_TIMEOUT_SEC:-2}"
ACTIVE_TRAFFIC_WINDOW_SEC="${G2RAY_ACTIVE_TRAFFIC_WINDOW_SEC:-180}"
LAST_GOOD_ROUTE_MAX_AGE_SEC="${G2RAY_LAST_GOOD_ROUTE_MAX_AGE_SEC:-1800}"
WAKER_TEST_TIMEOUT_SEC="${G2RAY_WAKER_TEST_TIMEOUT_SEC:-180}"
RUNTIME_LOCK_WAIT_ATTEMPTS="${G2RAY_RUNTIME_LOCK_WAIT_ATTEMPTS:-900}"
G2RAY_SUPERVISOR_SELF_HEAL_TIMEOUT_SEC="${G2RAY_SUPERVISOR_SELF_HEAL_TIMEOUT_SEC:-45}"
G2RAY_SUPERVISOR_HEALTH_TIMEOUT_SEC="${G2RAY_SUPERVISOR_HEALTH_TIMEOUT_SEC:-20}"
G2RAY_SUPERVISOR_ROUTE_REFRESH_TIMEOUT_SEC="${G2RAY_SUPERVISOR_ROUTE_REFRESH_TIMEOUT_SEC:-45}"
G2RAY_SUPERVISOR_EXPORT_TIMEOUT_SEC="${G2RAY_SUPERVISOR_EXPORT_TIMEOUT_SEC:-45}"
G2RAY_SUPERVISOR_STATS_TIMEOUT_SEC="${G2RAY_SUPERVISOR_STATS_TIMEOUT_SEC:-10}"
G2RAY_SUPERVISOR_VERSION_CHECK_TICKS="${G2RAY_SUPERVISOR_VERSION_CHECK_TICKS:-15}"
G2RAY_LATENCY_FOCUS_ROUTE_REFRESH_TICKS="${G2RAY_LATENCY_FOCUS_ROUTE_REFRESH_TICKS:-15}"
G2RAY_LATENCY_FOCUS_EXPORT_REFRESH_TICKS="${G2RAY_LATENCY_FOCUS_EXPORT_REFRESH_TICKS:-15}"
G2RAY_ACTIVE_ROUTE_REFRESH_TICKS="${G2RAY_ACTIVE_ROUTE_REFRESH_TICKS:-30}"
G2RAY_ACTIVE_EXPORT_REFRESH_TICKS="${G2RAY_ACTIVE_EXPORT_REFRESH_TICKS:-30}"
G2RAY_SILENT_START_VERIFY_SEC="${G2RAY_SILENT_START_VERIFY_SEC:-4}"
G2RAY_INTERACTIVE_ROUTE_REFRESH_TIMEOUT_SEC="${G2RAY_INTERACTIVE_ROUTE_REFRESH_TIMEOUT_SEC:-45}"
G2RAY_INTERACTIVE_EXPORT_TIMEOUT_SEC="${G2RAY_INTERACTIVE_EXPORT_TIMEOUT_SEC:-45}"
# Profile resolution order: env var override, then the persisted preference
# file (set via `g2ray.sh profile <name>`), then the balanced default. The file
# makes the choice survive Codespace restarts and panels launched without the
# env var (postAttachCommand, the background supervisor, etc.).
PERFORMANCE_PROFILE="${G2RAY_PERFORMANCE_PROFILE:-}"
if [[ -z "$PERFORMANCE_PROFILE" && -f "$PERFORMANCE_PROFILE_FILE" ]]; then
    PERFORMANCE_PROFILE=$(awk 'NF{gsub(/[[:space:]]/,"");print;exit}' "$PERFORMANCE_PROFILE_FILE" 2>/dev/null || true)
fi
[[ -n "$PERFORMANCE_PROFILE" ]] || PERFORMANCE_PROFILE="balanced"
# TCP keepalive (seconds) applied to the inbound and direct-outbound sockets so
# idle connections are kept warm and dead peers are detected/reset promptly,
# instead of lingering as half-open sockets that a client reuses and stalls on.
# These run datacenter<->datacenter (edge<->Xray, Xray<->origin); they do NOT
# touch the mobile client's radio, so there is no battery cost.
TCP_KEEPALIVE_INTERVAL_SEC="${G2RAY_TCP_KEEPALIVE_INTERVAL:-15}"
TCP_KEEPALIVE_IDLE_SEC="${G2RAY_TCP_KEEPALIVE_IDLE:-30}"
[[ "$TCP_KEEPALIVE_INTERVAL_SEC" =~ ^[0-9]+$ ]] || TCP_KEEPALIVE_INTERVAL_SEC=15
[[ "$TCP_KEEPALIVE_IDLE_SEC" =~ ^[0-9]+$ ]] || TCP_KEEPALIVE_IDLE_SEC=30
LOG_MAX_BYTES="${G2RAY_LOG_MAX_BYTES:-1048576}"
LOG_ROTATE_KEEP="${G2RAY_LOG_ROTATE_KEEP:-3}"
XRAY_CONFIGTEST_LOG_MAX_AGE_MIN="${G2RAY_XRAY_CONFIGTEST_LOG_MAX_AGE_MIN:-1440}"
[[ "$WAKER_TEST_TIMEOUT_SEC" =~ ^[0-9]+$ && "$WAKER_TEST_TIMEOUT_SEC" -ge 30 ]] || WAKER_TEST_TIMEOUT_SEC=180
[[ "$RUNTIME_LOCK_WAIT_ATTEMPTS" =~ ^[0-9]+$ && "$RUNTIME_LOCK_WAIT_ATTEMPTS" -ge 1 ]] || RUNTIME_LOCK_WAIT_ATTEMPTS=900
[[ "$ROUTE_READY_STABLE_PROBES" =~ ^[0-9]+$ && "$ROUTE_READY_STABLE_PROBES" -ge 1 ]] || ROUTE_READY_STABLE_PROBES=2
[[ "$ROUTE_READY_STABLE_SLEEP_SEC" =~ ^[0-9]+$ ]] || ROUTE_READY_STABLE_SLEEP_SEC=1
[[ "$G2RAY_ACTIVE_ROUTE_REFRESH_TICKS" =~ ^[0-9]+$ && "$G2RAY_ACTIVE_ROUTE_REFRESH_TICKS" -ge 5 ]] || G2RAY_ACTIVE_ROUTE_REFRESH_TICKS=30
[[ "$G2RAY_ACTIVE_EXPORT_REFRESH_TICKS" =~ ^[0-9]+$ && "$G2RAY_ACTIVE_EXPORT_REFRESH_TICKS" -ge 5 ]] || G2RAY_ACTIVE_EXPORT_REFRESH_TICKS=30
[[ "$G2RAY_SILENT_START_VERIFY_SEC" =~ ^[0-9]+$ && "$G2RAY_SILENT_START_VERIFY_SEC" -ge 1 ]] || G2RAY_SILENT_START_VERIFY_SEC=4
(( G2RAY_SILENT_START_VERIFY_SEC > 10 )) && G2RAY_SILENT_START_VERIFY_SEC=10
[[ "$HEADLESS_ROUTE_SETTLE_WAIT_SEC" =~ ^[0-9]+$ && "$HEADLESS_ROUTE_SETTLE_WAIT_SEC" -ge 15 ]] || HEADLESS_ROUTE_SETTLE_WAIT_SEC=180
(( HEADLESS_ROUTE_SETTLE_WAIT_SEC > 300 )) && HEADLESS_ROUTE_SETTLE_WAIT_SEC=300
[[ "$HEADLESS_ROUTE_SETTLE_INITIAL_DELAY_SEC" =~ ^[0-9]+$ ]] || HEADLESS_ROUTE_SETTLE_INITIAL_DELAY_SEC=5
(( HEADLESS_ROUTE_SETTLE_INITIAL_DELAY_SEC > 30 )) && HEADLESS_ROUTE_SETTLE_INITIAL_DELAY_SEC=30
[[ "$ROUTE_HEALTH_TTL_SEC" =~ ^[0-9]+$ && "$ROUTE_HEALTH_TTL_SEC" -ge 30 ]] || ROUTE_HEALTH_TTL_SEC=300
[[ "$ROUTE_HEALTH_EXPORT_MAX_AGE_SEC" =~ ^[0-9]+$ && "$ROUTE_HEALTH_EXPORT_MAX_AGE_SEC" -ge "$ROUTE_HEALTH_TTL_SEC" ]] || ROUTE_HEALTH_EXPORT_MAX_AGE_SEC=21600
[[ "$ROUTE_REFRESH_BACKOFF_INITIAL_SEC" =~ ^[0-9]+$ && "$ROUTE_REFRESH_BACKOFF_INITIAL_SEC" -ge 15 ]] || ROUTE_REFRESH_BACKOFF_INITIAL_SEC=60
[[ "$ROUTE_REFRESH_BACKOFF_MAX_SEC" =~ ^[0-9]+$ && "$ROUTE_REFRESH_BACKOFF_MAX_SEC" -ge "$ROUTE_REFRESH_BACKOFF_INITIAL_SEC" ]] || ROUTE_REFRESH_BACKOFF_MAX_SEC=600
[[ "$DNS_CACHE_TTL_SEC" =~ ^[0-9]+$ ]] || DNS_CACHE_TTL_SEC=300
[[ "$ROUTE_PROBE_CONCURRENCY" =~ ^[0-9]+$ && "$ROUTE_PROBE_CONCURRENCY" -ge 1 ]] || ROUTE_PROBE_CONCURRENCY=6
(( ROUTE_PROBE_CONCURRENCY > 16 )) && ROUTE_PROBE_CONCURRENCY=16
[[ "$ROUTE_REPAIR_COOLDOWN_SEC" =~ ^[0-9]+$ ]] || ROUTE_REPAIR_COOLDOWN_SEC=180
[[ "$PORT_PUBLIC_STARTUP_ATTEMPTS" =~ ^[0-9]+$ && "$PORT_PUBLIC_STARTUP_ATTEMPTS" -ge 1 ]] || PORT_PUBLIC_STARTUP_ATTEMPTS=4
(( PORT_PUBLIC_STARTUP_ATTEMPTS > 8 )) && PORT_PUBLIC_STARTUP_ATTEMPTS=8
[[ "$PORT_PUBLIC_STARTUP_RETRY_SEC" =~ ^[0-9]+$ ]] || PORT_PUBLIC_STARTUP_RETRY_SEC=2
(( PORT_PUBLIC_STARTUP_RETRY_SEC > 10 )) && PORT_PUBLIC_STARTUP_RETRY_SEC=10
[[ "$LOCAL_PROBE_TIMEOUT_SEC" =~ ^[0-9]+$ && "$LOCAL_PROBE_TIMEOUT_SEC" -ge 1 ]] || LOCAL_PROBE_TIMEOUT_SEC=1
[[ "$EXTERNAL_PROBE_TIMEOUT_SEC" =~ ^[0-9]+$ && "$EXTERNAL_PROBE_TIMEOUT_SEC" -ge 1 ]] || EXTERNAL_PROBE_TIMEOUT_SEC=5
[[ "$CONNECT_TIMEOUT_SEC" =~ ^[0-9]+$ && "$CONNECT_TIMEOUT_SEC" -ge 1 ]] || CONNECT_TIMEOUT_SEC=2
[[ "$XRAY_CONFIGTEST_LOG_MAX_AGE_MIN" =~ ^[0-9]+$ && "$XRAY_CONFIGTEST_LOG_MAX_AGE_MIN" -ge 60 ]] || XRAY_CONFIGTEST_LOG_MAX_AGE_MIN=1440
[[ "$ROUTE_DEGRADED_MULTIPLIER" =~ ^[1-9][0-9]?$ && "$ROUTE_DEGRADED_MULTIPLIER" -ge 2 && "$ROUTE_DEGRADED_MULTIPLIER" -le 10 ]] || ROUTE_DEGRADED_MULTIPLIER=3
[[ "$ROUTE_DEGRADED_MIN_DELTA_MS" =~ ^[1-9][0-9]{0,4}$ ]] || ROUTE_DEGRADED_MIN_DELTA_MS=500
[[ "$ROUTE_DEGRADED_MIN_SAMPLES" =~ ^[1-9][0-9]?$ && "$ROUTE_DEGRADED_MIN_SAMPLES" -ge 3 ]] || ROUTE_DEGRADED_MIN_SAMPLES=3
[[ "$ROUTE_DEGRADED_TRIGGER_COUNT" =~ ^[1-9][0-9]?$ && "$ROUTE_DEGRADED_TRIGGER_COUNT" -ge 2 ]] || ROUTE_DEGRADED_TRIGGER_COUNT=2
[[ "$ROUTE_DEGRADED_REFRESH_COOLDOWN_SEC" =~ ^[1-9][0-9]{1,4}$ && "$ROUTE_DEGRADED_REFRESH_COOLDOWN_SEC" -ge 60 ]] || ROUTE_DEGRADED_REFRESH_COOLDOWN_SEC=300
[[ "$ACTIVE_TRAFFIC_WINDOW_SEC" =~ ^[0-9]+$ ]] || ACTIVE_TRAFFIC_WINDOW_SEC=180
[[ "$G2RAY_SUPERVISOR_VERSION_CHECK_TICKS" =~ ^[0-9]+$ && "$G2RAY_SUPERVISOR_VERSION_CHECK_TICKS" -ge 1 ]] || G2RAY_SUPERVISOR_VERSION_CHECK_TICKS=15
[[ "$G2RAY_LATENCY_FOCUS_ROUTE_REFRESH_TICKS" =~ ^[0-9]+$ && "$G2RAY_LATENCY_FOCUS_ROUTE_REFRESH_TICKS" -ge 1 ]] || G2RAY_LATENCY_FOCUS_ROUTE_REFRESH_TICKS=15
[[ "$G2RAY_LATENCY_FOCUS_EXPORT_REFRESH_TICKS" =~ ^[0-9]+$ && "$G2RAY_LATENCY_FOCUS_EXPORT_REFRESH_TICKS" -ge 1 ]] || G2RAY_LATENCY_FOCUS_EXPORT_REFRESH_TICKS=15
[[ "$G2RAY_INTERACTIVE_ROUTE_REFRESH_TIMEOUT_SEC" =~ ^[0-9]+$ && "$G2RAY_INTERACTIVE_ROUTE_REFRESH_TIMEOUT_SEC" -ge 1 ]] || G2RAY_INTERACTIVE_ROUTE_REFRESH_TIMEOUT_SEC=45
[[ "$G2RAY_INTERACTIVE_EXPORT_TIMEOUT_SEC" =~ ^[0-9]+$ && "$G2RAY_INTERACTIVE_EXPORT_TIMEOUT_SEC" -ge 1 ]] || G2RAY_INTERACTIVE_EXPORT_TIMEOUT_SEC=45

umask 077
mkdir -p "$DATA_DIR" "$LOG_DIR" "$QR_DIR"
chmod 700 "$DATA_DIR" "$LOG_DIR" "$QR_DIR" 2>/dev/null || true
touch "$LOG_FILE" 2>/dev/null || true
touch "$STRUCTURED_LOG_FILE" 2>/dev/null || true
touch "$DIAGNOSTIC_LOG_FILE" 2>/dev/null || true
[[ -f "$SAVED_BYTES_FILE"   ]] || printf '{"down":0,"up":0}\n' > "$SAVED_BYTES_FILE"
[[ -f "$SESSION_BYTES_FILE" ]] || printf '{"down":0,"up":0}\n' > "$SESSION_BYTES_FILE"
[[ -f "$TOTAL_UPTIME_FILE"  ]] || printf '0\n'                 > "$TOTAL_UPTIME_FILE"
[[ -f "$SESSION_START_FILE" ]] || date +%s                     > "$SESSION_START_FILE"
chmod 600 "$LOG_FILE" "$STRUCTURED_LOG_FILE" "$DIAGNOSTIC_LOG_FILE" "$SAVED_BYTES_FILE" "$SESSION_BYTES_FILE" "$TOTAL_UPTIME_FILE" "$SESSION_START_FILE" 2>/dev/null || true

json_escape() {
    local value="${1:-}"
    value=${value//\\/\\\\}
    value=${value//\"/\\\"}
    value=${value//$'\t'/\\t}
    value=${value//$'\r'/}
    value=${value//$'\n'/}
    printf '%s' "$value"
}

json_escape_into() {
    local -n output_ref="$1"
    local value="${2:-}"
    value=${value//\\/\\\\}
    value=${value//\"/\\\"}
    value=${value//$'\t'/\\t}
    value=${value//$'\r'/}
    value=${value//$'\n'/}
    output_ref="$value"
}

low_overhead_enabled() {
    [[ -s "$LOW_OVERHEAD_DISABLED_FILE" ]] && return 1
    [[ -s "$LOW_OVERHEAD_FILE" ]] && return 0
    [[ "${G2RAY_LOW_OVERHEAD:-0}" == "1" ]]
}

save_previous_performance_profile() {
    local file="$1" mode_profile="$2" current
    current="${PERFORMANCE_PROFILE:-balanced}"
    [[ -s "$file" ]] && return 0
    [[ "$current" == "$mode_profile" ]] && return 0
    _atomic_write "$file" "$current"
    chmod 600 "$file" 2>/dev/null || true
}

restore_previous_performance_profile() {
    local file="$1" previous
    previous=$(awk 'NF{gsub(/[[:space:]]/,"");print;exit}' "$file" 2>/dev/null || true)
    if [[ -n "$previous" ]] && valid_performance_profile "$previous"; then
        set_performance_profile "$previous" || true
    else
        set_performance_profile "balanced" || true
    fi
    rm -f "$file" 2>/dev/null || true
}

reapply_config_preserving_uuid_if_exists() {
    [[ -f "$CONFIG_FILE" ]] || return 0
    local _prev_preserve_set="${G2RAY_PRESERVE_UUID+x}" _prev_preserve="${G2RAY_PRESERVE_UUID:-}" _status
    G2RAY_PRESERVE_UUID=1
    if generate_config; then
        _status=0
    else
        _status=$?
    fi
    if [[ "$_prev_preserve_set" == "x" ]]; then
        G2RAY_PRESERVE_UUID="$_prev_preserve"
    else
        unset G2RAY_PRESERVE_UUID
    fi
    return "$_status"
}

enable_low_overhead_mode() {
    if latency_focus_enabled; then
        disable_latency_focus_mode --no-reapply
    fi
    rm -f "$LOW_OVERHEAD_DISABLED_FILE" 2>/dev/null || true
    printf 'enabled\n' > "$LOW_OVERHEAD_FILE"
    chmod 600 "$LOW_OVERHEAD_FILE" 2>/dev/null || true
    save_previous_performance_profile "$LOW_OVERHEAD_PREV_PROFILE_FILE" "low_overhead"
    set_performance_profile "low_overhead" || true
    reapply_config_preserving_uuid_if_exists
}

disable_low_overhead_mode() {
    rm -f "$LOW_OVERHEAD_FILE" 2>/dev/null || true
    printf 'disabled\n' > "$LOW_OVERHEAD_DISABLED_FILE"
    chmod 600 "$LOW_OVERHEAD_DISABLED_FILE" 2>/dev/null || true
    restore_previous_performance_profile "$LOW_OVERHEAD_PREV_PROFILE_FILE"
    [[ "${1:-}" == "--no-reapply" ]] || reapply_config_preserving_uuid_if_exists
}

toggle_low_overhead_mode() {
    if low_overhead_enabled; then
        disable_low_overhead_mode
        return 1
    fi
    enable_low_overhead_mode
    return 0
}

low_overhead_summary() {
    if low_overhead_enabled; then
        printf 'Enabled - applies low_overhead profile and reduces background route refresh / INFO log chatter\n'
    else
        printf 'Disabled - full monitoring and INFO logs are enabled\n'
    fi
}

latency_focus_enabled() {
    [[ -s "$LATENCY_FOCUS_DISABLED_FILE" ]] && return 1
    [[ -s "$LATENCY_FOCUS_FILE" ]] && return 0
    [[ "${G2RAY_LATENCY_FOCUS:-0}" == "1" ]]
}

enable_latency_focus_mode() {
    if low_overhead_enabled; then
        disable_low_overhead_mode --no-reapply
    fi
    rm -f "$LATENCY_FOCUS_DISABLED_FILE" 2>/dev/null || true
    printf 'enabled\n' > "$LATENCY_FOCUS_FILE"
    chmod 600 "$LATENCY_FOCUS_FILE" 2>/dev/null || true
    save_previous_performance_profile "$LATENCY_FOCUS_PREV_PROFILE_FILE" "low_latency"
    set_performance_profile "low_latency" || true
    reapply_config_preserving_uuid_if_exists
}

disable_latency_focus_mode() {
    rm -f "$LATENCY_FOCUS_FILE" 2>/dev/null || true
    printf 'disabled\n' > "$LATENCY_FOCUS_DISABLED_FILE"
    chmod 600 "$LATENCY_FOCUS_DISABLED_FILE" 2>/dev/null || true
    restore_previous_performance_profile "$LATENCY_FOCUS_PREV_PROFILE_FILE"
    [[ "${1:-}" == "--no-reapply" ]] || reapply_config_preserving_uuid_if_exists
}

toggle_latency_focus_mode() {
    if latency_focus_enabled; then
        disable_latency_focus_mode
        return 1
    fi
    enable_latency_focus_mode
    return 0
}

latency_focus_summary() {
    if latency_focus_enabled; then
        printf 'Enabled - applies low_latency profile, keeps heartbeat/self-heal, and minimizes noncritical background work\n'
    else
        printf 'Disabled - normal diagnostics, route refresh, and exports are enabled\n'
    fi
}

effective_performance_profile() {
    if latency_focus_enabled; then
        printf 'low_latency'
    elif low_overhead_enabled; then
        printf 'low_overhead'
    else
        printf '%s' "${PERFORMANCE_PROFILE:-balanced}"
    fi
}

domain_link_export_enabled() {
    local value
    value="${G2RAY_EXPORT_DOMAIN_LINK:-}"
    if [[ -z "$value" && -f "$DOMAIN_LINK_EXPORT_FILE" ]]; then
        value=$(awk 'NF {print; exit}' "$DOMAIN_LINK_EXPORT_FILE" 2>/dev/null || true)
    fi
    value=$(printf '%s' "${value:-1}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
    case "$value" in
        0|false|no|off|disabled) return 1 ;;
        *) return 0 ;;
    esac
}

valid_xhttp_mode() {
    case "${1:-}" in
        packet-up|stream-up|stream-one|auto) return 0 ;;
        *) return 1 ;;
    esac
}

xhttp_mode_value() {
    local value
    value="${G2RAY_XHTTP_MODE:-}"
    if [[ -z "$value" && -f "$XHTTP_MODE_FILE" ]]; then
        value=$(awk 'NF{gsub(/[[:space:]]/,"");print;exit}' "$XHTTP_MODE_FILE" 2>/dev/null || true)
    fi
    [[ -n "$value" ]] || value="packet-up"
    if valid_xhttp_mode "$value"; then
        printf '%s' "$value"
    else
        printf 'packet-up'
    fi
}

set_xhttp_mode() {
    local mode="${1:-}" active_mode=""
    valid_xhttp_mode "$mode" || return 1
    _atomic_write "$XHTTP_MODE_FILE" "$mode" || return 1
    log_event INFO "xhttp_mode preference set mode=${mode}"
    active_mode=$(xhttp_active_inbound_mode)
    if [[ -s "$CONFIG_FILE" && "$active_mode" != "auto" ]]; then
        # Migrate an older mode-restricted inbound once. Current configs accept
        # every supported client mode, so later link-mode changes need no
        # disruptive Xray restart.
        reapply_config_preserving_uuid_if_exists
    else
        if ! refresh_config_exports >/dev/null 2>&1; then
            log_event WARN "xhttp_mode export_refresh_failed mode=${mode} preference_saved=true"
            return 1
        fi
    fi
}

xhttp_mode_summary() {
    local mode
    mode=$(xhttp_mode_value)
    case "$mode" in
        packet-up) printf 'packet-up (recommended compatibility default)' ;;
        stream-up) printf 'stream-up (experimental streaming upload)' ;;
        stream-one) printf 'stream-one (experimental single-stream mode)' ;;
        auto) printf 'auto (client-selected mode)' ;;
        *) printf 'packet-up (recommended compatibility default)' ;;
    esac
}

# Packet-up writes one upload POST at a time, and GitHub's edge drains each POST
# body through a 64 KiB HTTP/2 stream window. With Xray's 1 MB default POSTs a
# single upload measured ~0.46 MB/s; 60 KB POSTs fit inside that window and
# measured 1.5-2.8 MB/s on the same path with unchanged latency and download
# (2026-10 A/B through a Codespace). The xmux block pins the pinned core's own
# defaults (3 connections; client defaults differ across Xray releases and 6
# connections measured ~450 ms slower to first byte) and adds an HTTP/2 PING
# health check so a silently dead edge connection is retired within ~35 s
# instead of stalling every new request routed onto it.
XHTTP_TUNED_EXTRA_JSON='{"scMaxEachPostBytes":60000,"scMinPostsIntervalMs":10,"xmux":{"maxConnections":3,"hMaxRequestTimes":"600-900","hMaxReusableSecs":"1800-3000","hKeepAlivePeriod":20}}'

xhttp_link_tuning_enabled() {
    local value="${G2RAY_XHTTP_LINK_TUNING:-}"
    if [[ -z "$value" && -f "$XHTTP_LINK_TUNING_FILE" ]]; then
        value=$(awk 'NF{gsub(/[[:space:]]/,"");print;exit}' "$XHTTP_LINK_TUNING_FILE" 2>/dev/null || true)
    fi
    value=$(printf '%s' "${value:-on}" | tr '[:upper:]' '[:lower:]')
    case "$value" in
        0|false|no|off|disabled) return 1 ;;
        *) return 0 ;;
    esac
}

set_xhttp_link_tuning() {
    local state="${1:-}"
    case "$state" in
        on|off) ;;
        *) return 1 ;;
    esac
    _atomic_write "$XHTTP_LINK_TUNING_FILE" "$state" || return 1
    log_event INFO "xhttp_link_tuning preference set state=${state}"
    [[ -s "$CONFIG_FILE" ]] || return 0
    if ! refresh_config_exports >/dev/null 2>&1; then
        log_event WARN "xhttp_link_tuning export_refresh_failed state=${state} preference_saved=true"
        return 1
    fi
}

xhttp_link_tuning_summary() {
    if [[ -n "${G2RAY_XHTTP_EXTRA_JSON:-}" ]] && xhttp_extra_json_valid "$G2RAY_XHTTP_EXTRA_JSON"; then
        printf 'custom (G2RAY_XHTTP_EXTRA_JSON overrides the built-in tuning)'
    elif xhttp_link_tuning_enabled; then
        printf 'on (60 KB upload POSTs + HTTP/2 health check)'
    else
        printf 'off (Xray client defaults; ~4x slower uploads through GitHub edge)'
    fi
}

# The XHTTP `extra` object exported in client links: a valid custom object wins,
# otherwise the measured upload tuning unless the user turned it off.
xhttp_link_extra_json() {
    if [[ -n "${G2RAY_XHTTP_EXTRA_JSON:-}" ]]; then
        if xhttp_extra_json_valid "$G2RAY_XHTTP_EXTRA_JSON"; then
            printf '%s' "$G2RAY_XHTTP_EXTRA_JSON"
            return 0
        fi
        log_event WARN "xhttp_extra_json invalid action=ignore_custom_extra"
    fi
    xhttp_link_tuning_enabled || return 0
    printf '%s' "$XHTTP_TUNED_EXTRA_JSON"
}

show_xhttp_mode_manager() {
    local choice mode
    while true; do
        refresh_screen
        echo -e "  ${GREEN}XHTTP Client Link Mode${NC}\n"
        echo -e "  Current       : ${WHITE}$(xhttp_mode_summary)${NC}"
        echo -e "  Upload tuning : ${WHITE}$(xhttp_link_tuning_summary)${NC}\n"
        echo -e "  ${DIM}The server accepts all supported modes; this controls newly exported links.${NC}\n"
        echo -e "  ${RED}1)${NC} packet-up  ${DIM}Recommended; the only mode measured working through GitHub's edge${NC}"
        echo -e "  ${RED}2)${NC} stream-up  ${YELLOW}Failed every request through the Codespaces edge in tests${NC}"
        echo -e "  ${RED}3)${NC} stream-one ${YELLOW}Failed every request through the Codespaces edge in tests${NC}"
        echo -e "  ${RED}4)${NC} auto       ${DIM}Xray resolves this to packet-up for these TLS links${NC}"
        echo -e "  ${RED}5)${NC} Toggle upload tuning ${DIM}(turn off only for a client that rejects the extra parameter)${NC}"
        echo -e "  ${RED}0)${NC} Return\n"
        echo -ne "  ${GREEN}Select:${NC} "
        read -r choice
        case "$choice" in
            1) mode="packet-up" ;;
            2) mode="stream-up" ;;
            3) mode="stream-one" ;;
            4) mode="auto" ;;
            5)
                local next_state=on
                xhttp_link_tuning_enabled && next_state=off
                if set_xhttp_link_tuning "$next_state"; then
                    echo -e "\n  ${GREEN}Upload tuning ${next_state}. Exported links were refreshed; re-import them in your client.${NC}"
                else
                    echo -e "\n  ${RED}Upload tuning was saved as ${next_state}, but exports could not be refreshed.${NC}"
                fi
                echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
                continue
                ;;
            0) return 0 ;;
            *) echo -e "  ${RED}Invalid option.${NC}"; sleep 1; continue ;;
        esac
        if [[ "$mode" == "stream-up" || "$mode" == "stream-one" ]]; then
            echo -e "\n  ${YELLOW}${mode} links failed every request through GitHub's Codespaces edge in measured tests.${NC}"
            echo -ne "  ${GREEN}Export ${mode} links anyway? (y/n):${NC} "
            local confirm_mode
            read -r confirm_mode
            [[ "$confirm_mode" =~ ^[Yy]$ ]] || continue
        fi
        if set_xhttp_mode "$mode"; then
            echo -e "\n  ${GREEN}XHTTP mode saved: ${mode}.${NC}"
            echo -e "  ${DIM}Existing UUID is preserved. A current auto-mode server stays connected; view configs to copy matching links.${NC}"
        else
            echo -e "\n  ${RED}Could not finish applying XHTTP mode.${NC}"
            echo -e "  ${DIM}The preference may be saved, but exports or the engine update failed. Check Diagnostics and refresh exports.${NC}"
        fi
        echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
    done
}

wide_dns_discovery_enabled() {
    local value
    value=$(printf '%s' "${G2RAY_WIDE_DNS_DISCOVERY:-${WIDE_DNS_DISCOVERY:-1}}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
    case "$value" in
        0|false|no|off|disabled) return 1 ;;
        *) return 0 ;;
    esac
}

valid_ipv4_cidr() {
    local subnet="${1:-}" ip prefix
    [[ "$subnet" == */* ]] || return 1
    ip="${subnet%/*}"
    prefix="${subnet##*/}"
    valid_ipv4 "$ip" || return 1
    [[ "$prefix" =~ ^[0-9]+$ ]] || return 1
    (( 10#$prefix >= 0 && 10#$prefix <= 32 ))
}

wide_dns_ecs_source_label() {
    local subnet="${1:-}" label
    label=$(printf '%s' "$subnet" | tr './-' '___' | tr -cd 'A-Za-z0-9_')
    printf 'dns_google_ecs_%s\n' "$label"
}

wide_dns_ecs_url_value() {
    local subnet="${1:-}"
    printf '%s\n' "${subnet//\//%2F}"
}

wide_dns_ecs_subnets() {
    local raw max count=0 subnet
    raw="${G2RAY_WIDE_DNS_ECS_SUBNETS:-${WIDE_DNS_ECS_SUBNETS:-}}"
    max="${G2RAY_WIDE_DNS_MAX_ECS_SUBNETS:-${WIDE_DNS_MAX_ECS_SUBNETS:-12}}"
    [[ "$max" =~ ^[0-9]+$ && "$max" -gt 0 ]] || max=12
    (( max > 24 )) && max=24
    for subnet in $raw; do
        subnet="${subnet//,/}"
        subnet="${subnet//;/}"
        valid_ipv4_cidr "$subnet" || continue
        printf '%s\n' "$subnet"
        count=$((count + 1))
        (( count >= max )) && break
    done
}

support_include_network_enabled() {
    local value
    value=$(printf '%s' "${G2RAY_SUPPORT_INCLUDE_NETWORK:-0}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
    case "$value" in
        1|true|yes|on|enabled) return 0 ;;
        *) return 1 ;;
    esac
}

tcp_fast_open_outbound_enabled() {
    # Kernel capability does not prove path compatibility: origin TFO caused
    # retained HTTPS stalls in live A/B checks. Keep it opt-in. Explicit auto
    # still checks the kernel's client-support bit; never apply it to inbounds.
    local override value
    override=$(printf '%s' "${G2RAY_TCP_FAST_OPEN:-0}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
    case "$override" in
        0|false|no|off|disabled) return 1 ;;
        1|true|yes|on|enabled) return 0 ;;
        auto) ;;
        *) return 1 ;;
    esac
    value=$(cat /proc/sys/net/ipv4/tcp_fastopen 2>/dev/null || printf '0')
    [[ "$value" =~ ^[0-9]+$ ]] || value=0
    (( (value & 1) == 1 ))
}

deadline_time_us() {
    local stamp="${EPOCHREALTIME:-}"
    if [[ "$stamp" =~ ^([0-9]+)\.([0-9]{6})$ ]]; then
        printf -v "$1" '%s%s' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
    else
        stamp=$(date +%s%6N 2>/dev/null) || return 1
        [[ "$stamp" =~ ^[0-9]{10,18}$ ]] || return 1
        printf -v "$1" '%s' "$stamp"
    fi
}

run_with_deadline() {
    local seconds="${1:-30}" pid waited_ticks=0 max_ticks status=0 status_file command_name monitor_was_enabled=false
    local start_us now_us completed_us budget_us
    shift || return 1
    [[ "$seconds" =~ ^[0-9]+$ && "$seconds" -gt 0 ]] || seconds=30
    max_ticks=$((seconds * 10))
    command_name="${1:-unknown}"
    status_file=$(mktemp "${TMPDIR:-/tmp}/g2ray-deadline.XXXXXX") || {
        log_event ERROR "deadline_init_failed command=${command_name}"
        return 125
    }
    if ! deadline_time_us start_us; then
        rm -f "$status_file" 2>/dev/null || true
        log_event ERROR "deadline_clock_init_failed command=${command_name}"
        return 125
    fi
    budget_us=$((seconds * 1000000))
    [[ "$-" == *m* ]] && monitor_was_enabled=true
    # Job control gives this wrapper a dedicated process group. Descendants
    # keep that identity after their parent exits during TERM handling.
    set -m
    (
        set +m
        set +e
        # Do not launch work before the parent verifies our isolated group.
        while [[ ! -s "$status_file" ]]; do sleep 0.01; done
        # Command-local EXIT traps must finish before recording completion.
        ( "$@" )
        status=$?
        deadline_time_us completed_us || completed_us=0
        printf '%s\n' "$status" "$completed_us" > "$status_file" 2>/dev/null || true
        exit "$status"
    ) &
    pid=$!
    [[ "$monitor_was_enabled" == true ]] || set +m
    # A finished child may already have no group; a live wrapper still needs
    # verified isolation even if its command has published a status.
    if ! kill -0 -- "-$pid" 2>/dev/null && kill -0 "$pid" 2>/dev/null; then
        kill -KILL "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        rm -f "$status_file" 2>/dev/null || true
        log_event ERROR "deadline_group_init_failed command=${command_name}"
        return 125
    fi
    if ! builtin printf 'ready\n' > "$status_file"; then
        kill -KILL "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        rm -f "$status_file" 2>/dev/null || true
        log_event ERROR "deadline_group_init_failed command=${command_name}"
        return 125
    fi
    # Reporting the command's status does not finish the wrapper: EXIT traps
    # and cleanup may still be running. Keep its entire lifetime bounded.
    while [[ ! -s "$status_file" ]] || kill -0 "$pid" 2>/dev/null; do
        if ! deadline_time_us now_us || (( now_us < start_us )); then
            status=125
        elif (( now_us - start_us >= budget_us || waited_ticks >= max_ticks )); then
            status=124
        else
            status=0
        fi
        if (( status != 0 )); then
            kill -TERM -- "-$pid" 2>/dev/null || true
            sleep 0.2
            kill -KILL -- "-$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
            rm -f "$status_file" 2>/dev/null || true
            if (( status == 124 )); then
                log_event WARN "deadline_exceeded command=${command_name} timeout_sec=${seconds}"
            else
                log_event ERROR "deadline_clock_failed command=${command_name}"
            fi
            return "$status"
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            wait "$pid" 2>/dev/null || status=$?
            rm -f "$status_file" 2>/dev/null || true
            return "$status"
        fi
        sleep 0.1
        waited_ticks=$((waited_ticks + 1))
    done
    { read -r status && read -r completed_us; } < "$status_file" || completed_us=0
    wait "$pid" 2>/dev/null || status=$?
    rm -f "$status_file" 2>/dev/null || true
    if [[ ! "$completed_us" =~ ^[0-9]+$ ]] || (( completed_us < start_us )); then
        log_event ERROR "deadline_clock_failed command=${command_name}"
        return 125
    fi
    if (( completed_us - start_us >= budget_us )); then
        log_event WARN "deadline_exceeded command=${command_name} timeout_sec=${seconds}"
        return 124
    fi
    [[ "$status" =~ ^[0-9]+$ ]] || status=1
    return "$status"
}

deadline_child_pids() {
    local parent="${1:-}"
    [[ "$parent" =~ ^[0-9]+$ ]] || return 0
    {
        command -v pgrep >/dev/null 2>&1 && pgrep -P "$parent" 2>/dev/null || true
        ps -eo pid=,ppid= 2>/dev/null | awk -v parent="$parent" '$2 == parent {print $1}' || true
        ps -ef 2>/dev/null | awk -v parent="$parent" '$3 == parent {print $2}' || true
    } | awk 'NF && /^[0-9]+$/ && !seen[$0]++ {print}'
}

# A deadline must stop the whole command tree, not only its shell wrapper. Route
# probes spawn helpers (curl, dig, awk) that otherwise survive a timeout and can
# continue writing state after the caller has moved on.
terminate_process_tree() {
    local pid="${1:-}" child
    [[ "$pid" =~ ^[0-9]+$ ]] || return 0
    while IFS= read -r child; do
        [[ "$child" =~ ^[0-9]+$ ]] || continue
        terminate_process_tree "$child"
    done < <(deadline_child_pids "$pid")
    kill -TERM "$pid" 2>/dev/null || true
}

kill_process_tree_hard() {
    local pid="${1:-}" child
    [[ "$pid" =~ ^[0-9]+$ ]] || return 0
    while IFS= read -r child; do
        [[ "$child" =~ ^[0-9]+$ ]] || continue
        kill_process_tree_hard "$child"
    done < <(deadline_child_pids "$pid")
    kill -KILL "$pid" 2>/dev/null || true
}

# flock is present in the Codespaces image. Minimal images use a bounded
# mkdir lock instead of running shared-state writers concurrently.
with_file_lock() {
    local lock_file="${1:-}" timeout_sec="${2:-5}" lock_dir owner recheck start now self_pid="${BASHPID:-$$}"
    shift 2 || return 1
    [[ -n "$lock_file" ]] || return 1
    [[ "$timeout_sec" =~ ^[0-9]+$ ]] || timeout_sec=5
    if command -v flock >/dev/null 2>&1; then
        (
            flock -w "$timeout_sec" 9 || return 1
            "$@"
        ) 9>"$lock_file"
    else
        lock_dir="${lock_file}.d"
        start=$(date +%s 2>/dev/null || printf '0')
        while ! _try_claim_lock_dir "$lock_dir"; do
            owner=$(cat "$lock_dir/pid" 2>/dev/null || true)
            if [[ -z "$owner" || ! "$owner" =~ ^[0-9]+$ ]] || ! kill -0 "$owner" 2>/dev/null; then
                recheck=$(cat "$lock_dir/pid" 2>/dev/null || true)
                if [[ "$recheck" == "$owner" ]]; then
                    rm -f "$lock_dir/pid" 2>/dev/null || true
                    rmdir "$lock_dir" 2>/dev/null || true
                fi
                continue
            fi
            now=$(date +%s 2>/dev/null || printf '0')
            (( now - start >= timeout_sec )) && return 1
            sleep 0.1
        done
        (
            trap 'rm -f "$lock_dir/pid" 2>/dev/null || true; rmdir "$lock_dir" 2>/dev/null || true' EXIT
            "$@"
        )
    fi
}

log_structured_event() {
    local ts="$1" level="$2" msg="$3" event escaped_ts escaped_level escaped_event escaped_msg
    event="${msg%%[[:space:]]*}"
    event="${event//[^A-Za-z0-9_.:-]/}"
    [[ -n "$event" ]] || event="event"
    rotate_log_file_throttled "$STRUCTURED_LOG_FILE" "$STRUCTURED_LOG_ROTATE_STAMP_FILE"
    json_escape_into escaped_ts "$ts"
    json_escape_into escaped_level "$level"
    json_escape_into escaped_event "$event"
    json_escape_into escaped_msg "$msg"
    printf '{"ts":"%s","level":"%s","event":"%s","message":"%s"}\n' \
        "$escaped_ts" "$escaped_level" "$escaped_event" "$escaped_msg" \
        >> "$STRUCTURED_LOG_FILE" 2>/dev/null || true
}

quiet_info_event_important() {
    case "${1:-}" in
        runtime_ready*|boot_status*|background\ supervisor_started*|self_heal\ restart_ok*|recover_now*|support_bundle*)
            return 0
            ;;
    esac
    return 1
}

log_event() {
    local level="$1"; shift || true
    local ts msg
    if latency_focus_enabled && [[ "$level" == "INFO" ]] && ! quiet_info_event_important "$*"; then
        return 0
    fi
    msg="$*"
    if low_overhead_enabled && [[ "$level" == "INFO" ]] && ! quiet_info_event_important "$msg"; then
        return 0
    fi
    ts=$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')
    rotate_log_file_throttled "$LOG_FILE" "$LOG_ROTATE_STAMP_FILE"
    printf '%s [%s] %s\n' "$ts" "$level" "$msg" >> "$LOG_FILE" 2>/dev/null || true
    log_structured_event "$ts" "$level" "$msg"
}

sweep_stale_temp_files() {
    [[ -d "$DATA_DIR" ]] || return 0
    find "$DATA_DIR" -maxdepth 1 \
        \( -name 'route_probe.??????' \
           -o -name 'route_health.??????' \
           -o -name 'route_cooldown.??????' \
           -o -name 'route_stats.??????' \
           -o -name 'dns-resolve.??????' \
           -o -name 'support-bundle.??????' \) \
        -mmin +10 -exec rm -rf -- {} + 2>/dev/null || true
}

write_boot_status() {
    local status="${1:-unknown}" reason="${2:-unknown}" message="${3:-}" code="${4:-0}" ms="${5:-0}" ts payload
    ts=$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')
    mkdir -p "$DATA_DIR" 2>/dev/null || true
    [[ "$code" =~ ^[0-9]+$ ]] || code=0
    [[ "$ms" =~ ^[0-9]+$ ]] || ms=0
    payload=$(cat <<JSON
{
  "ts": "$(json_escape "$ts")",
  "status": "$(json_escape "$status")",
  "reason": "$(json_escape "$reason")",
  "message": "$(json_escape "$message")",
  "route_http_status": ${code:-0},
  "route_latency_ms": ${ms:-0}
}
JSON
    ) || return 1
    if ! _atomic_write "$BOOT_STATUS_FILE" "$payload"; then
        log_event ERROR "boot_status write_failed status=${status} reason=${reason}"
        return 1
    fi
    log_event INFO "boot_status status=${status} reason=${reason} route_http=${code:-0} route_ms=${ms:-0}"
}

boot_status_summary() {
    if [[ ! -s "$BOOT_STATUS_FILE" ]]; then
        printf 'Boot status : none recorded\n'
        return 0
    fi
    if command -v jq >/dev/null 2>&1; then
        jq -r '"Boot status : \(.status) reason=\(.reason) route=HTTP \(.route_http_status) \(.route_latency_ms)ms at \(.ts)\nMessage     : \(.message)"' "$BOOT_STATUS_FILE" 2>/dev/null && return 0
    fi
    awk -F '"' '
        /"ts"/ {ts=$4}
        /"status"/ {status=$4}
        /"reason"/ {reason=$4}
        /"message"/ {message=$4}
        /"route_http_status"/ {gsub(/[^0-9]/, "", $0); code=$0}
        /"route_latency_ms"/ {gsub(/[^0-9]/, "", $0); ms=$0}
        END {
            printf "Boot status : %s reason=%s route=HTTP %s %sms at %s\n",
                (status ? status : "unknown"), (reason ? reason : "unknown"),
                (code ? code : "0"), (ms ? ms : "0"), (ts ? ts : "unknown")
            if (message) printf "Message     : %s\n", message
        }
    ' "$BOOT_STATUS_FILE" 2>/dev/null
}

rotate_log_file() {
    local file="$1" max="$LOG_MAX_BYTES" keep="$LOG_ROTATE_KEEP" size i prev next snapshot="" copytruncate=false
    [[ "$max" =~ ^[0-9]+$ && "$max" -gt 0 ]] || max=1048576
    [[ "$keep" =~ ^[0-9]+$ && "$keep" -gt 0 ]] || keep=3
    [[ -f "$file" ]] || return 0
    # Do not repeatedly copy an oversized daemon log that cannot be truncated.
    [[ -w "$file" ]] || return 1
    size=$(wc -c < "$file" 2>/dev/null || echo 0)
    [[ "$size" =~ ^[0-9]+$ ]] || size=0
    (( size < max )) && return 0
    case "$file" in
        "$LOG_DIR/xray.log"|"$LOG_DIR/xray-error.log")
            copytruncate=true
            snapshot=$(mktemp "${file}.rotate.XXXXXX") || return 1
            if ! cp -- "$file" "$snapshot" 2>/dev/null; then
                rm -f "$snapshot" 2>/dev/null || true
                return 1
            fi
            ;;
    esac
    rm -f "${file}.${keep}" 2>/dev/null || true
    for ((i=keep-1; i>=1; i--)); do
        prev="${file}.${i}"
        next="${file}.$((i + 1))"
        [[ -f "$prev" ]] && mv -f "$prev" "$next" 2>/dev/null || true
    done
    if [[ "$copytruncate" == true ]]; then
        mv -f "$snapshot" "${file}.1" 2>/dev/null || {
            rm -f "$snapshot" 2>/dev/null || true
            return 1
        }
        : > "$file" 2>/dev/null || return 1
    else
        mv -f "$file" "${file}.1" 2>/dev/null || true
        : > "$file" 2>/dev/null || true
    fi
    chmod 600 "$file" 2>/dev/null || true
}

rotate_log_file_throttled() {
    local file="$1" stamp="$2" interval="${LOG_ROTATE_CHECK_INTERVAL_SEC:-60}" now last=""
    [[ "$interval" =~ ^[0-9]+$ && "$interval" -gt 0 ]] || interval=60
    printf -v now '%(%s)T' -1 2>/dev/null || now=$(date +%s 2>/dev/null || printf '0')
    [[ "$now" =~ ^[0-9]+$ ]] || now=0
    case "$stamp" in
        "$LOG_ROTATE_STAMP_FILE") last="${_G2RAY_LOG_ROTATE_LAST:-}" ;;
        "$STRUCTURED_LOG_ROTATE_STAMP_FILE") last="${_G2RAY_STRUCTURED_LOG_ROTATE_LAST:-}" ;;
        "$DIAGNOSTIC_LOG_ROTATE_STAMP_FILE") last="${_G2RAY_DIAGNOSTIC_LOG_ROTATE_LAST:-}" ;;
    esac
    [[ -n "$last" ]] || last=$(cat "$stamp" 2>/dev/null || printf '0')
    [[ "$last" =~ ^[0-9]+$ ]] || last=0
    if (( now > 0 && last > 0 && now - last < interval )); then
        return 0
    fi
    printf '%s\n' "$now" > "$stamp" 2>/dev/null || true
    case "$stamp" in
        "$LOG_ROTATE_STAMP_FILE") _G2RAY_LOG_ROTATE_LAST="$now" ;;
        "$STRUCTURED_LOG_ROTATE_STAMP_FILE") _G2RAY_STRUCTURED_LOG_ROTATE_LAST="$now" ;;
        "$DIAGNOSTIC_LOG_ROTATE_STAMP_FILE") _G2RAY_DIAGNOSTIC_LOG_ROTATE_LAST="$now" ;;
    esac
    chmod 600 "$stamp" 2>/dev/null || true
    rotate_log_file "$file"
}

codespace_shared_github_token() {
    local n key="GITHUB_TOKEN"
    [[ -r "$CODESPACE_SHARED_ENV_FILE" ]] || return 1
    n=$(awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$CODESPACE_SHARED_ENV_FILE" 2>/dev/null)
    n="${n%\"}"; n="${n#\"}"
    n="${n%\'}"; n="${n#\'}"
    [[ -n "$n" ]] || return 1
    printf '%s' "$n"
}

run_gh() {
    command -v gh >/dev/null 2>&1 || return 127
    local token_env=() shared_token=""
    if [[ -z "${GH_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" ]]; then
        shared_token=$(codespace_shared_github_token 2>/dev/null || true)
        [[ -n "$shared_token" ]] && token_env=(GH_TOKEN="$shared_token")
    fi
    if command -v timeout >/dev/null 2>&1; then
        env "${token_env[@]}" GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 NO_COLOR=1 \
            timeout "$(gh_timeout_seconds)" gh "$@"
    else
        env "${token_env[@]}" GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 NO_COLOR=1 gh "$@"
    fi
}

gh_timeout_seconds() {
    local timeout_sec="${G2RAY_GH_TIMEOUT_SEC:-10}"
    [[ "$timeout_sec" =~ ^[0-9]+$ && "$timeout_sec" -gt 0 && "$timeout_sec" -le 120 ]] || timeout_sec=10
    printf '%s' "$timeout_sec"
}

curl_http_code() {
    local url="$1" timeout_sec="${2:-5}" code
    if code=$(curl -s -m "$timeout_sec" -o /dev/null -w "%{http_code}" "$url" 2>/dev/null); then
        if [[ "$code" =~ ^[0-9]{3}$ && "$code" != "000" ]]; then
            printf '%s' "$code"
        else
            printf '0'
        fi
        return 0
    fi
    printf '0'
}

valid_codespace_name() {
    local name="${1:-}"
    [[ -n "$name" ]] && [[ "$name" != "null" ]]
}

fingerprint_secret() {
    local value="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        printf '%s' "$value" | sha256sum | awk '{print substr($1,1,12)}'
    else
        printf '%s' "$value" | md5sum | awk '{print substr($1,1,12)}'
    fi
}

file_fingerprint() {
    local file="$1"
    [[ -f "$file" ]] || return 1
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" 2>/dev/null | awk '{print $1}'
    elif command -v md5sum >/dev/null 2>&1; then
        md5sum "$file" 2>/dev/null | awk '{print $1}'
    else
        cksum "$file" 2>/dev/null | awk '{print $1 ":" $2}'
    fi
}

script_code_version() {
    local git_head script_hash
    git_head=$(git -C "$BASE_DIR" rev-parse --verify HEAD 2>/dev/null || true)
    script_hash=$(file_fingerprint "$SCRIPT_PATH" 2>/dev/null || true)
    if [[ -n "$git_head" ]]; then
        printf '%s-%s\n' "$git_head" "${script_hash:0:12}"
        return 0
    fi
    printf '%s\n' "$script_hash"
}

reset_logs_on_code_change() {
    local enabled current previous keep i file
    enabled=$(printf '%s' "${G2RAY_RESET_LOGS_ON_CODE_CHANGE:-1}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
    case "$enabled" in
        0|false|no|off|disabled) return 0 ;;
    esac
    current=$(script_code_version)
    [[ -n "$current" ]] || return 0
    previous=$(cat "$LOG_CODE_VERSION_FILE" 2>/dev/null || true)
    [[ "$previous" == "$current" ]] && return 0

    keep="$LOG_ROTATE_KEEP"
    [[ "$keep" =~ ^[0-9]+$ && "$keep" -gt 0 ]] || keep=3
    for file in "$LOG_FILE" "$STRUCTURED_LOG_FILE" "$DIAGNOSTIC_LOG_FILE" "$LOG_DIR/xray.log" "$LOG_DIR/xray-error.log"; do
        : > "$file" 2>/dev/null || true
        chmod 600 "$file" 2>/dev/null || true
        for ((i=1; i<=keep; i++)); do
            rm -f "${file}.${i}" 2>/dev/null || true
        done
    done
    rm -f "$LOG_DIR"/xray-configtest.*.log 2>/dev/null || true
    rm -f "$LOG_ROTATE_STAMP_FILE" "$STRUCTURED_LOG_ROTATE_STAMP_FILE" "$DIAGNOSTIC_LOG_ROTATE_STAMP_FILE" 2>/dev/null || true
    printf '%s\n' "$current" > "$LOG_CODE_VERSION_FILE" 2>/dev/null || true
    chmod 600 "$LOG_CODE_VERSION_FILE" 2>/dev/null || true
    _G2RAY_LOG_ROTATE_LAST=""
    _G2RAY_STRUCTURED_LOG_ROTATE_LAST=""
    _G2RAY_DIAGNOSTIC_LOG_ROTATE_LAST=""
    log_event INFO "logs reset_for_code_change previous_hash=${previous:-none} current_hash=${current:0:12}"
}

detect_codespace_name_from_json_file() {
    local file="$1" n
    [[ -r "$file" ]] || return 1
    n=$(sed -nE 's/.*"CODESPACE_NAME"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' "$file" 2>/dev/null | head -n 1)
    [[ -n "$n" ]] || n=$(sed -nE 's/.*"codespace"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' "$file" 2>/dev/null | head -n 1)
    valid_codespace_name "$n" || return 1
    printf '%s' "$n"
}

detect_codespace_name_from_env_file() {
    local file="$1" n
    [[ -r "$file" ]] || return 1
    n=$(sed -nE 's/^CODESPACE_NAME=(.*)$/\1/p' "$file" 2>/dev/null | head -n 1)
    n="${n%\"}"; n="${n#\"}"
    n="${n%\'}"; n="${n#\'}"
    valid_codespace_name "$n" || return 1
    printf '%s' "$n"
}

detect_codespace_name_from_waker_metadata() {
    local n
    [[ -r "$WAKER_METADATA_FILE" ]] || return 1
    n=$(sed -nE 's/^codespace_name=(.+)$/\1/p' "$WAKER_METADATA_FILE" 2>/dev/null | head -n 1)
    valid_codespace_name "$n" || return 1
    printf '%s' "$n"
}

detect_codespace_name_from_port_stamp() {
    local stamp n
    for stamp in "$PORT_PUBLIC_STAMP_FILE".*."$XRAY_PORT"; do
        [[ -e "$stamp" ]] || continue
        n="${stamp#"$PORT_PUBLIC_STAMP_FILE".}"
        n="${n%."$XRAY_PORT"}"
        valid_codespace_name "$n" || continue
        printf '%s' "$n"
        return 0
    done
    return 1
}

_detect_codespace_name() {
    valid_codespace_name "${CODESPACE_NAME:-}" && { printf '%s' "$CODESPACE_NAME"; return; }
    local n
    n=$(detect_codespace_name_from_json_file "$CODESPACE_ENV_JSON_FILE" 2>/dev/null || true)
    valid_codespace_name "$n" && { printf '%s' "$n"; return; }
    n=$(detect_codespace_name_from_env_file "$CODESPACE_SHARED_ENV_FILE" 2>/dev/null || true)
    valid_codespace_name "$n" && { printf '%s' "$n"; return; }
    n=$(detect_codespace_name_from_waker_metadata 2>/dev/null || true)
    valid_codespace_name "$n" && { printf '%s' "$n"; return; }
    n=$(detect_codespace_name_from_json_file "$CONFIG_META_FILE" 2>/dev/null || true)
    valid_codespace_name "$n" && { printf '%s' "$n"; return; }
    n=$(detect_codespace_name_from_port_stamp 2>/dev/null || true)
    valid_codespace_name "$n" && { printf '%s' "$n"; return; }
    if command -v gh >/dev/null 2>&1; then
        n=$(run_gh codespace list --limit 1 --json name --jq '.[0].name // ""' 2>/dev/null || true)
        valid_codespace_name "$n" && { printf '%s' "$n"; return; }
        sleep 2
        n=$(run_gh codespace list --limit 1 --json name --jq '.[0].name // ""' 2>/dev/null || true)
        valid_codespace_name "$n" && { printf '%s' "$n"; return; }
    fi
    local h; h=$(hostname 2>/dev/null || true)
    printf '%s' "${h:-unknown-codespace}"
}

CODESPACE_NAME=$(_detect_codespace_name)
port_forwarding_domain_value() {
    local domain="${G2RAY_PORT_FORWARDING_DOMAIN:-${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-$PORT_FORWARDING_DOMAIN}}"
    domain="${domain#https://}"
    domain="${domain#http://}"
    domain="${domain%%/*}"
    domain="${domain#.}"
    [[ "$domain" =~ ^[A-Za-z0-9.-]+$ && "$domain" == *.* ]] || domain="app.github.dev"
    printf '%s' "$domain"
}

PORT_DOMAIN="${CODESPACE_NAME}-${XRAY_PORT}.$(port_forwarding_domain_value)"
WS_PORT_DOMAIN="${CODESPACE_NAME}-${WS_PORT}.$(port_forwarding_domain_value)"

refresh_port_domains() {
    local domain
    domain=$(port_forwarding_domain_value)
    PORT_DOMAIN="${CODESPACE_NAME}-${XRAY_PORT}.${domain}"
    WS_PORT_DOMAIN="${CODESPACE_NAME}-${WS_PORT}.${domain}"
}

ws_fallback_enabled() {
    local value
    [[ -s "$WS_FALLBACK_DISABLED_FILE" ]] && return 1
    [[ -s "$WS_FALLBACK_FILE" ]] && return 0
    value=$(printf '%s' "${G2RAY_ENABLE_WS_FALLBACK:-0}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
    [[ "$value" == "1" || "$value" == "true" || "$value" == "on" || "$value" == "yes" || "$value" == "enabled" ]]
}

enable_ws_fallback_mode() {
    rm -f "$WS_FALLBACK_DISABLED_FILE" 2>/dev/null || true
    printf 'enabled\n' > "$WS_FALLBACK_FILE"
    chmod 600 "$WS_FALLBACK_FILE" 2>/dev/null || true
}

disable_ws_fallback_mode() {
    rm -f "$WS_FALLBACK_FILE" 2>/dev/null || true
    printf 'disabled\n' > "$WS_FALLBACK_DISABLED_FILE"
    chmod 600 "$WS_FALLBACK_DISABLED_FILE" 2>/dev/null || true
}

toggle_ws_fallback_mode() {
    if ws_fallback_enabled; then
        disable_ws_fallback_mode
        return 1
    fi
    enable_ws_fallback_mode
    return 0
}

ws_fallback_summary() {
    if ws_fallback_enabled; then
        printf 'Enabled - adds optional WebSocket configs on Codespaces port %s\n' "$WS_PORT"
    else
        printf 'Disabled - XHTTP remains the only generated transport\n'
    fi
}

ws_fallback_in_config() {
    [[ -f "$CONFIG_FILE" ]] || return 1
    grep -Fq '"tag": "vless-ws"' "$CONFIG_FILE" 2>/dev/null
}

ws_fallback_configured() {
    ws_fallback_enabled && return 0
    ws_fallback_in_config
}

ws_path_value() {
    local path="${G2RAY_WS_PATH:-/ws}"
    [[ "$path" == /* ]] || path="/${path}"
    case "$path" in
        *[[:space:]\"\'\\]*|'') path="/ws" ;;
    esac
    printf '%s' "$path"
}

normalize_hostname_value() {
    local host="${1:-}"
    host=$(printf '%s' "$host" \
        | tr '[:upper:]' '[:lower:]' \
        | sed -E 's#^[a-z][a-z0-9+.-]*://##; s#/.*$##; s/:[0-9]+$//; s/[[:space:]]//g; s/\.$//')
    printf '%s' "$host"
}

valid_hostname_value() {
    local host label
    local -a host_labels
    host=$(normalize_hostname_value "${1:-}")
    [[ -n "$host" && ${#host} -le 253 ]] || return 1
    [[ "$host" == *.* ]] || return 1
    [[ "$host" =~ ^[a-z0-9.-]+$ ]] || return 1
    [[ "$host" != .* && "$host" != *. && "$host" != *..* ]] || return 1
    IFS='.' read -r -a host_labels <<< "$host"
    for label in "${host_labels[@]}"; do
        [[ -n "$label" && ${#label} -le 63 ]] || return 1
        [[ "$label" != -* && "$label" != *- ]] || return 1
    done
}

ws_front_domain_value() {
    local host="${G2RAY_WS_FRONT_DOMAIN:-}"
    if [[ -z "$host" && -f "$WS_FRONT_DOMAIN_FILE" ]]; then
        host=$(awk 'NF {print; exit}' "$WS_FRONT_DOMAIN_FILE" 2>/dev/null || true)
    fi
    host=$(normalize_hostname_value "$host")
    valid_hostname_value "$host" || return 1
    printf '%s' "$host"
}

set_ws_front_domain() {
    local host
    host=$(normalize_hostname_value "${1:-}")
    valid_hostname_value "$host" || return 1
    _atomic_write "$WS_FRONT_DOMAIN_FILE" "$host"
    chmod 600 "$WS_FRONT_DOMAIN_FILE" 2>/dev/null || true
}

clear_ws_front_domain() {
    rm -f "$WS_FRONT_DOMAIN_FILE" 2>/dev/null || true
}

xray_pid_matches() {
    local p="$1"
    [[ "$p" =~ ^[0-9]+$ ]] || return 1
    ps -p "$p" -o args= 2>/dev/null | grep -Fq "$XRAY_BIN run -c $CONFIG_FILE"
}

xray_running() {
    local p
    if [[ -f "$XRAY_PID_FILE" ]]; then
        p=$(cat "$XRAY_PID_FILE" 2>/dev/null || true)
        if xray_pid_matches "$p" && sudo kill -0 "$p" 2>/dev/null; then
            return 0
        fi
        rm -f "$XRAY_PID_FILE" 2>/dev/null || true
    fi
    command -v pgrep >/dev/null 2>&1 || return 1
    p=$(pgrep -f "$XRAY_BIN run -c $CONFIG_FILE" 2>/dev/null | head -1 || true)
    [[ -n "$p" ]]
}

owned_xray_pids() {
    local p
    p=$(cat "$XRAY_PID_FILE" 2>/dev/null || true)
    if xray_pid_matches "$p"; then
        printf '%s\n' "$p"
    fi
    if command -v pgrep >/dev/null 2>&1; then
        pgrep -f "$XRAY_BIN run -c $CONFIG_FILE" 2>/dev/null || true
    fi
}

json_dns_ips() {
    local url="$1" header="${2:-}" response ip
    if [[ -n "$header" ]]; then
        response=$(curl -sf -m 4 -H "$header" "$url" 2>/dev/null || true)
    else
        response=$(curl -sf -m 4 "$url" 2>/dev/null || true)
    fi
    [[ -n "$response" ]] || return 0
    if command -v jq >/dev/null 2>&1; then
        while IFS= read -r ip; do
            ip=${ip%$'\r'}
            valid_ipv4 "$ip" && printf '%s\n' "$ip"
        done < <(printf '%s' "$response" | jq -r '.. | objects | .data? | select(type == "string")' 2>/dev/null || true)
    else
        printf '%s' "$response" \
            | grep -oE '"data"[[:space:]]*:[[:space:]]*"[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*"' \
            | sed -E 's/^"data"[[:space:]]*:[[:space:]]*"//;s/"$//' \
            || true
    fi
    return 0
}

curl_remote_ip() {
    local domain="$1" ip
    ip=$(curl -sk -m 5 -o /dev/null -w '%{remote_ip}' "https://${domain}/" 2>/dev/null || true)
    [[ "$ip" =~ ^[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*$ ]] && printf '%s\n' "$ip"
}

dns_cache_ttl_sec() {
    [[ "${DNS_CACHE_TTL_SEC:-300}" =~ ^[0-9]+$ ]] && printf '%s' "$DNS_CACHE_TTL_SEC" || printf '300'
}

read_dns_candidate_cache() {
    local domain="${1:-}" ttl now
    [[ -n "$domain" && -s "$DNS_CANDIDATE_CACHE_FILE" ]] || return 1
    ttl=$(dns_cache_ttl_sec)
    (( ttl > 0 )) || return 1
    now=$(date +%s)
    awk -F '\t' -v domain="$domain" -v now="$now" -v ttl="$ttl" '
        $2 == domain && $1 ~ /^[0-9]+$/ && now >= $1 && now - $1 <= ttl && $3 != "" && $4 != "" {
            print $3 "\t" $4
            found = 1
        }
        END { exit(found ? 0 : 1) }
    ' "$DNS_CANDIDATE_CACHE_FILE" 2>/dev/null
}

write_dns_candidate_cache() {
    local domain="${1:-}" rows="${2:-}" ttl now tmp
    [[ -n "$domain" && -n "$rows" ]] || return 0
    ttl=$(dns_cache_ttl_sec)
    (( ttl > 0 )) || return 0
    mkdir -p "$(dirname "$DNS_CANDIDATE_CACHE_FILE")" 2>/dev/null || true
    now=$(date +%s)
    tmp=$(mktemp "${DNS_CANDIDATE_CACHE_FILE}.XXXXXX") || return 0
    if [[ -s "$DNS_CANDIDATE_CACHE_FILE" ]]; then
        awk -F '\t' -v domain="$domain" '$2 != domain {print}' "$DNS_CANDIDATE_CACHE_FILE" > "$tmp" 2>/dev/null || true
    fi
    printf '%s\n' "$rows" | while IFS=$'\t' read -r source ip; do
        valid_ipv4 "$ip" || continue
        printf '%s\t%s\t%s\t%s\n' "$now" "$domain" "${source:-unknown}" "$ip"
    done >> "$tmp"
    if [[ -s "$tmp" ]]; then
        mv -f "$tmp" "$DNS_CANDIDATE_CACHE_FILE" 2>/dev/null || rm -f "$tmp" 2>/dev/null || true
        chmod 600 "$DNS_CANDIDATE_CACHE_FILE" 2>/dev/null || true
    else
        rm -f "$tmp" 2>/dev/null || true
    fi
}

resolve_dns_provider_ips_with_sources() {
    local domain="${1:-}" tmpdir pids=()
    [[ -n "$domain" ]] || return 0
    tmpdir=$(mktemp -d "${DATA_DIR}/dns-resolve.XXXXXX" 2>/dev/null || mktemp -d)
    if command -v dig >/dev/null 2>&1; then
        (timeout 4 dig +time=2 +tries=1 +short "$domain" A 2>/dev/null | awk 'NF {print "dig\t" $0}' > "$tmpdir/dig") &
        pids+=("$!")
    fi
    (timeout 4 getent hosts "$domain" 2>/dev/null | awk '{print "getent\t" $1}' > "$tmpdir/getent") &
    pids+=("$!")
    ({ json_dns_ips "https://dns.google/resolve?name=${domain}&type=A" || true; } | awk 'NF {print "dns_google\t" $0}' > "$tmpdir/dns_google") &
    pids+=("$!")
    ({ json_dns_ips "https://cloudflare-dns.com/dns-query?name=${domain}&type=A" "accept: application/dns-json" || true; } | awk 'NF {print "dns_cloudflare\t" $0}' > "$tmpdir/dns_cloudflare") &
    pids+=("$!")
    # Quad9 retired its JSON API in May 2025. Its port-443 DoH service uses
    # DNS wire format, so changing this JSON request's port would still fail.
    if wide_dns_discovery_enabled; then
        local subnet source url_value safe_name
        while IFS= read -r subnet; do
            [[ -n "$subnet" ]] || continue
            source=$(wide_dns_ecs_source_label "$subnet")
            url_value=$(wide_dns_ecs_url_value "$subnet")
            safe_name=$(printf '%s\n' "$source" | tr -cd 'A-Za-z0-9_')
            ({ json_dns_ips "https://dns.google/resolve?name=${domain}&type=A&edns_client_subnet=${url_value}" || true; } \
                | awk -v source="$source" 'NF {print source "\t" $0}' > "$tmpdir/${safe_name}") &
            pids+=("$!")
        done < <(wide_dns_ecs_subnets)
    fi
    ({ curl_remote_ip "$domain" || true; } | awk 'NF {print "remote_http\t" $0}' > "$tmpdir/remote_http") &
    pids+=("$!")
    local pid
    for pid in "${pids[@]}"; do
        wait "$pid" 2>/dev/null || true
    done
    cat "$tmpdir"/* 2>/dev/null || true
    rm -rf "$tmpdir" 2>/dev/null || true
}

xhttp_config_path() {
    local cache_key cached_key cached_path path
    if [[ -f "$CONFIG_FILE" ]]; then
        cache_key=$(file_fingerprint "$CONFIG_FILE" 2>/dev/null || true)
        [[ -n "$cache_key" ]] || cache_key=$(stat -c '%Y:%s:%y' "$CONFIG_FILE" 2>/dev/null || printf '0')
        if [[ -s "$XHTTP_PATH_CACHE_FILE" ]]; then
            IFS=$'\t' read -r cached_key cached_path < "$XHTTP_PATH_CACHE_FILE" 2>/dev/null || true
            if [[ "$cached_key" == "$cache_key" && -n "${cached_path:-}" ]]; then
                printf '%s\n' "$cached_path"
                return 0
            fi
        fi
    fi
    if [[ -f "$CONFIG_FILE" ]] && command -v jq >/dev/null 2>&1; then
        path=$(jq -r '.inbounds[]? | select(.tag=="vless-in") | .streamSettings.xhttpSettings.path // "/"' "$CONFIG_FILE" 2>/dev/null \
            | awk 'NF {print; exit}')
        [[ -n "$path" ]] || path="/"
        if [[ -n "${cache_key:-}" ]]; then
            printf '%s\t%s\n' "$cache_key" "$path" > "$XHTTP_PATH_CACHE_FILE" 2>/dev/null || true
            chmod 600 "$XHTTP_PATH_CACHE_FILE" 2>/dev/null || true
        fi
        printf '%s\n' "$path"
        return 0
    fi
    printf '/'
}

xhttp_active_inbound_mode() {
    local mode="" cache_key cached_key cached_mode
    [[ -f "$CONFIG_FILE" ]] && command -v jq >/dev/null 2>&1 || return 0
    cache_key=$(file_fingerprint "$CONFIG_FILE" 2>/dev/null || true)
    if [[ -n "$cache_key" && -s "$XHTTP_MODE_CACHE_FILE" ]]; then
        IFS=$'\t' read -r cached_key cached_mode < "$XHTTP_MODE_CACHE_FILE" || true
        if [[ "$cached_key" == "$cache_key" ]] && valid_xhttp_mode "${cached_mode:-}"; then
            printf '%s' "$cached_mode"
            return 0
        fi
    fi
    mode=$(jq -r '.inbounds[]? | select(.tag=="vless-in") | .streamSettings.xhttpSettings.mode // empty' "$CONFIG_FILE" 2>/dev/null \
        | awk 'NF {print; exit}') || return 0
    if valid_xhttp_mode "$mode"; then
        # A concurrent config swap must not associate its mode with the old hash.
        if [[ -n "$cache_key" && "$(file_fingerprint "$CONFIG_FILE" 2>/dev/null || true)" == "$cache_key" ]]; then
            _atomic_write "$XHTTP_MODE_CACHE_FILE" "${cache_key}"$'\t'"${mode}" || true
        fi
        printf '%s' "$mode"
    fi
}

xhttp_config_mode() {
    local active_mode
    active_mode=$(xhttp_active_inbound_mode)
    # New server configs use auto so one healthy inbound can accept every
    # supported client mode. Keep old restricted configs link-consistent until
    # they are regenerated, then export the user's panel preference.
    if [[ -n "$active_mode" && "$active_mode" != "auto" ]]; then
        printf '%s' "$active_mode"
        return 0
    fi
    xhttp_mode_value
}

xhttp_probe_metrics() {
    local target="${1:-external}" address="${2:-}" path url raw code elapsed ms curl_rc=0 error_hint="" reason timeout_sec
    path=$(xhttp_config_path)
    [[ "$path" == /* ]] || path="/${path}"
    case "$target" in
        local)
            url="http://127.0.0.1:${XRAY_PORT}${path}"
            timeout_sec="$LOCAL_PROBE_TIMEOUT_SEC"
            ;;
        *)
            url="https://${PORT_DOMAIN}:${CODESPACES_EDGE_PORT}${path}"
            timeout_sec="$EXTERNAL_PROBE_TIMEOUT_SEC"
            ;;
    esac
    if [[ "$target" == "local" || -z "$address" ]]; then
        raw=$(curl -sS --connect-timeout "$CONNECT_TIMEOUT_SEC" -m "$timeout_sec" -X OPTIONS -o /dev/null -w "%{http_code} %{time_total}" "$url" 2>/dev/null) || {
            curl_rc=$?
            raw="0 0"
        }
    else
        raw=$(curl -sS --connect-timeout "$CONNECT_TIMEOUT_SEC" -m "$timeout_sec" --resolve "${PORT_DOMAIN}:${CODESPACES_EDGE_PORT}:${address}" \
            -X OPTIONS -o /dev/null -w "%{http_code} %{time_total}" "$url" 2>/dev/null) || {
            curl_rc=$?
            raw="0 0"
        }
    fi
    code=${raw%% *}
    elapsed=${raw#* }
    [[ "$code" =~ ^[0-9]{3}$ ]] || code=0
    [[ "$code" == "000" ]] && code=0
    [[ "$elapsed" =~ ^[0-9]+([.][0-9]+)?$ ]] || elapsed=0
    ms=$(awk -v s="${elapsed:-0}" 'BEGIN{printf "%d", (s * 1000) + 0.5}')
    if [[ "$code" == "0" ]]; then
        case "$curl_rc" in
            28) error_hint="timeout" ;;
            6)  error_hint="dns" ;;
            7|52|56) error_hint="network" ;;
            35|51|58|60) error_hint="tls" ;;
            *)  error_hint="curl_${curl_rc}" ;;
        esac
    fi
    reason=$(route_failure_reason_for_status "$code" "$error_hint")
    printf '%s %s %s\n' "${code:-0}" "${ms:-0}" "$reason"
}

xhttp_probe_status() {
    local target="${1:-external}" address="${2:-}" code
    code=$(xhttp_probe_metrics "$target" "$address" | awk '{print $1}')
    printf '%s' "${code:-0}"
}

xhttp_status_usable() {
    local code="${1:-0}"
    # The pinned Xray XHTTP handler answers OPTIONS with 200 before parsing a
    # tunnel request. A 400 therefore proves an error path, not readiness.
    [[ "$code" == "200" ]]
}

panel_next_action_code() {
    local engine="${1:-false}" listener="${2:-false}" edge_code="${3:-0}" config_present="${4:-true}"
    if [[ "$config_present" != "true" ]]; then
        printf 'generate_config'
    elif xhttp_status_usable "$edge_code"; then
        printf 'retry_vless_config'
    elif [[ "$engine" != "true" ]]; then
        printf 'start_engine'
    elif [[ "$listener" != "true" ]]; then
        printf 'restart_engine'
    elif [[ "$edge_code" == "404" ]]; then
        printf 'wait_route_or_recover'
    elif [[ "$edge_code" == "0" || "$edge_code" == "000" ]]; then
        printf 'check_dns_or_ports'
    else
        printf 'open_diagnostics'
    fi
}

panel_next_action_text() {
    case "${1:-open_diagnostics}" in
        generate_config) printf 'Generate a config and start the engine.' ;;
        start_engine) printf 'Start the Xray engine, then check the route again.' ;;
        restart_engine) printf 'Restart the engine so the listener opens on the configured port.' ;;
        retry_vless_config) printf 'Try the same VLESS config again.' ;;
        wait_route_or_recover) printf 'Wait for the Codespaces route to settle, or run Recover Now if it stays stuck.' ;;
        check_dns_or_ports) printf 'Check DNS, GitHub port visibility, and the Codespaces Ports tab.' ;;
        *) printf 'Open diagnostics and inspect the support bundle logs.' ;;
    esac
}

increment_route_bad_count() {
    local count
    count=$(cat "$ROUTE_BAD_COUNT_FILE" 2>/dev/null || echo 0)
    [[ "$count" =~ ^[0-9]+$ ]] || count=0
    count=$((count + 1))
    printf '%s\n' "$count" > "$ROUTE_BAD_COUNT_FILE"
    printf '%s' "$count"
}

reset_route_bad_count() {
    rm -f "$ROUTE_BAD_COUNT_FILE" 2>/dev/null || true
}

increment_edge_bad_count() {
    local count
    count=$(cat "$EDGE_BAD_COUNT_FILE" 2>/dev/null || echo 0)
    [[ "$count" =~ ^[0-9]+$ ]] || count=0
    count=$((count + 1))
    printf '%s\n' "$count" > "$EDGE_BAD_COUNT_FILE"
    printf '%s' "$count"
}

reset_edge_bad_count() {
    rm -f "$EDGE_BAD_COUNT_FILE" 2>/dev/null || true
}

edge_reconnect_cooldown_active() {
    local age cooldown="$SELF_HEAL_RECONNECT_COOLDOWN_SEC"
    [[ "$cooldown" =~ ^[0-9]+$ ]] || cooldown=300
    age=$(stamp_age_sec "$EDGE_RECONNECT_STAMP_FILE")
    [[ "$age" =~ ^[0-9]+$ ]] || age=999999999
    (( age < cooldown ))
}

mark_edge_reconnect_attempt() {
    date +%s > "$EDGE_RECONNECT_STAMP_FILE" 2>/dev/null || true
}

self_heal_state_summary() {
    local route_bad edge_bad now last cooldown remaining last_age threshold
    route_bad=$(cat "$ROUTE_BAD_COUNT_FILE" 2>/dev/null || echo 0)
    edge_bad=$(cat "$EDGE_BAD_COUNT_FILE" 2>/dev/null || echo 0)
    last=$(cat "$EDGE_RECONNECT_STAMP_FILE" 2>/dev/null || echo 0)
    [[ "$route_bad" =~ ^[0-9]+$ ]] || route_bad=0
    [[ "$edge_bad" =~ ^[0-9]+$ ]] || edge_bad=0
    [[ "$last" =~ ^[0-9]+$ ]] || last=0
    cooldown="$SELF_HEAL_RECONNECT_COOLDOWN_SEC"
    [[ "$cooldown" =~ ^[0-9]+$ ]] || cooldown=300
    threshold="$SELF_HEAL_EDGE_RECONNECT_THRESHOLD"
    [[ "$threshold" =~ ^[0-9]+$ ]] || threshold=3
    now=$(date +%s)
    if (( last > 0 && now >= last )); then
        last_age="$((now - last))s"
        remaining=$((cooldown - (now - last)))
        (( remaining < 0 )) && remaining=0
    else
        last_age="never"
        remaining=0
    fi
    printf 'route_bad=%s edge_bad=%s threshold=%s cooldown_remaining=%ss last_reconnect_age=%s\n' \
        "$route_bad" "$edge_bad" "$threshold" "$remaining" "$last_age"
}

last_log_event_matching() {
    local pattern="$1" line
    [[ -s "$LOG_FILE" ]] || { printf 'none recorded'; return 0; }
    line=$(grep -E "$pattern" "$LOG_FILE" 2>/dev/null | tail -n 1 || true)
    if [[ -z "$line" ]]; then
        printf 'none recorded'
        return 0
    fi
    printf '%s' "$line" | sed -E 's/ \[(INFO|WARN|ERROR)\] / /'
}

last_known_state_summary() {
    local failure repair last_export
    failure=$(last_log_event_matching 'route_unusable|edge_unreachable|engine_not_ready|started_route_unusable|started_route_still_unusable|port_public_failed|launch_failed|timeout|failed')
    repair=$(last_log_event_matching 'route_repair public_ok|route_repair public_failed|force_reconnect verify_external|force_reconnect start_engine ok|restart_ok|started_route_ready|route_repaired|runtime_ready .*action=skip_reconnect')
    last_export=$(last_log_event_matching 'config_exports refreshed|fallback_route_unusable|fallback_route_filter')
    printf 'Last failure : %s\n' "$failure"
    printf 'Last repair  : %s\n' "$repair"
    printf 'Last export  : %s\n' "$last_export"
}

valid_ipv4() {
    local ip="${1:-}" IFS=. part count=0
    [[ -n "$ip" && "$ip" != *[!0-9.]* && "$ip" == *.*.*.* && "$ip" != *.*.*.*.* ]] || return 1
    for part in $ip; do
        [[ "$part" =~ ^[0-9]+$ ]] || return 1
        (( 10#$part >= 0 && 10#$part <= 255 )) || return 1
        count=$((count + 1))
    done
    (( count == 4 ))
}

route_file_contains() {
    local file="$1" ip="$2"
    [[ -f "$file" ]] || return 1
    grep -Fxq "$ip" "$file" 2>/dev/null
}

write_unique_route_file() {
    local file="$1" tmp
    tmp=$(mktemp "${file}.XXXXXX") || return 1
    awk 'NF && !seen[$0]++ {print}' > "$tmp"
    if mv -f "$tmp" "$file"; then
        chmod 600 "$file" 2>/dev/null || true
        return 0
    fi
    rm -f "$tmp" 2>/dev/null || true
    return 1
}

append_unique_route() {
    local file="$1" ip="$2"
    valid_ipv4 "$ip" || return 1
    mkdir -p "$(dirname "$file")" 2>/dev/null || true
    route_file_contains "$file" "$ip" && return 1
    { [[ -f "$file" ]] && cat "$file"; printf '%s\n' "$ip"; } | write_unique_route_file "$file"
}

remove_route_from_file() {
    local file="$1" ip="$2" tmp
    route_file_contains "$file" "$ip" || return 1
    tmp=$(mktemp "${file}.XXXXXX") || return 1
    awk -v ip="$ip" 'NF && $0 != ip && !seen[$0]++ {print}' "$file" > "$tmp"
    if [[ -s "$tmp" ]]; then
        mv -f "$tmp" "$file" || { rm -f "$tmp" 2>/dev/null || true; return 1; }
        chmod 600 "$file" 2>/dev/null || true
    else
        rm -f "$tmp" "$file" 2>/dev/null || return 1
    fi
}

manual_route_candidates() {
    [[ -f "$MANUAL_ROUTE_CANDIDATES_FILE" ]] || return 0
    while IFS= read -r ip; do
        valid_ipv4 "$ip" && printf '%s\n' "$ip"
    done < "$MANUAL_ROUTE_CANDIDATES_FILE"
}

blacklisted_route_candidates() {
    [[ -f "$BLACKLISTED_ROUTE_CANDIDATES_FILE" ]] || return 0
    while IFS= read -r ip; do
        valid_ipv4 "$ip" && printf '%s\n' "$ip"
    done < "$BLACKLISTED_ROUTE_CANDIDATES_FILE"
}

cached_route_candidate_ips() {
    [[ -s "$ROUTE_HEALTH_FILE" ]] || return 0
    awk -F '\t' 'NF >= 5 && $3 == "200" && $5 == "true" {print $2}' "$ROUTE_HEALTH_FILE" 2>/dev/null \
        | while IFS= read -r ip; do
            valid_ipv4 "$ip" && printf '%s\n' "$ip"
        done
}

candidate_blacklisted() {
    local ip="${1:-}"
    valid_ipv4 "$ip" || return 1
    route_file_contains "$BLACKLISTED_ROUTE_CANDIDATES_FILE" "$ip"
}

add_manual_route_candidate() {
    local ip="${1:-}"
    valid_ipv4 "$ip" || return 1
    candidate_blacklisted "$ip" && return 1
    append_unique_route "$MANUAL_ROUTE_CANDIDATES_FILE" "$ip" || return 1
    clear_route_candidate_cooldown "$ip"
    log_event INFO "route_candidate manual_added ip=${ip}"
    return 0
}

extract_ipv4_candidates_from_text() {
    local text="${1:-}" ip
    printf '%s\n' "$text" \
        | grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}' 2>/dev/null \
        | while IFS= read -r ip; do
            valid_ipv4 "$ip" && printf '%s\n' "$ip"
        done \
        | awk '!seen[$0]++ {print}'
}

import_manual_route_candidates_from_text() {
    local text="${1:-}" ip added=0 skipped=0 total=0
    while IFS= read -r ip; do
        [[ -n "$ip" ]] || continue
        total=$((total + 1))
        if add_manual_route_candidate "$ip"; then
            added=$((added + 1))
        else
            skipped=$((skipped + 1))
        fi
    done < <(extract_ipv4_candidates_from_text "$text")
    log_event INFO "route_candidate manual_batch_import added=${added} skipped=${skipped} candidates=${total}"
    printf 'added=%s skipped=%s candidates=%s\n' "$added" "$skipped" "$total"
    (( added > 0 ))
}

remove_manual_route_candidate() {
    local ip="${1:-}"
    valid_ipv4 "$ip" || return 1
    remove_route_from_file "$MANUAL_ROUTE_CANDIDATES_FILE" "$ip" || return 1
    log_event INFO "route_candidate manual_removed ip=${ip}"
    return 0
}

pinned_route_value() {
    local ip
    ip=$(cat "$PINNED_ROUTE_FILE" 2>/dev/null | awk 'NF {print; exit}' || true)
    valid_ipv4 "$ip" || return 0
    candidate_blacklisted "$ip" && return 0
    printf '%s\n' "$ip"
}

pin_route_candidate() {
    local ip="${1:-}"
    valid_ipv4 "$ip" || return 1
    candidate_blacklisted "$ip" && return 1
    _atomic_write "$PINNED_ROUTE_FILE" "$ip" || return 1
    chmod 600 "$PINNED_ROUTE_FILE" 2>/dev/null || true
    clear_route_candidate_cooldown "$ip"
    log_event INFO "route_candidate pinned ip=${ip}"
    return 0
}

unpin_route_candidate() {
    rm -f "$PINNED_ROUTE_FILE" 2>/dev/null || return 1
    log_event INFO "route_candidate unpinned"
    return 0
}

blacklist_route_candidate() {
    local ip="${1:-}" pinned
    valid_ipv4 "$ip" || return 1
    pinned=$(cat "$PINNED_ROUTE_FILE" 2>/dev/null | awk 'NF {print; exit}' || true)
    append_unique_route "$BLACKLISTED_ROUTE_CANDIDATES_FILE" "$ip" || return 1
    if route_file_contains "$MANUAL_ROUTE_CANDIDATES_FILE" "$ip"; then
        remove_route_from_file "$MANUAL_ROUTE_CANDIDATES_FILE" "$ip" || return 1
    fi
    if [[ "$pinned" == "$ip" ]]; then
        rm -f "$PINNED_ROUTE_FILE" 2>/dev/null || return 1
    fi
    log_event WARN "route_candidate blacklisted ip=${ip}"
    return 0
}

unblacklist_route_candidate() {
    local ip="${1:-}"
    valid_ipv4 "$ip" || return 1
    remove_route_from_file "$BLACKLISTED_ROUTE_CANDIDATES_FILE" "$ip" || return 1
    clear_route_candidate_cooldown "$ip"
    log_event INFO "route_candidate unblacklisted ip=${ip}"
    return 0
}

reset_route_candidate_state() {
    reset_route_candidate_cache
    rm -f "$PINNED_ROUTE_FILE" "$MANUAL_ROUTE_CANDIDATES_FILE" "$BLACKLISTED_ROUTE_CANDIDATES_FILE" 2>/dev/null || true
    log_event WARN "route_candidate state_reset"
}

reset_route_candidate_cache() {
    rm -f "$ROUTE_HEALTH_FILE" "$ROUTE_STATS_FILE" "$LAST_GOOD_ROUTE_FILE" "$ROUTE_COOLDOWN_FILE" "$DNS_CANDIDATE_CACHE_FILE" 2>/dev/null || true
    rm -f "$ROUTE_LATENCY_BASELINE_FILE" "$ROUTE_DEGRADED_REQUEST_FILE" "$ROUTE_DEGRADED_REFRESH_STAMP_FILE" 2>/dev/null || true
    log_event INFO "route_candidate cache_reset"
}

route_candidate_state_summary() {
    local pinned
    pinned=$(pinned_route_value)
    printf 'Pinned route : %s\n' "${pinned:-none}"
    printf 'Manual routes:\n'
    if [[ -s "$MANUAL_ROUTE_CANDIDATES_FILE" ]]; then
        manual_route_candidates | sed 's/^/  /'
    else
        printf '  none\n'
    fi
    printf 'Blacklisted routes:\n'
    if [[ -s "$BLACKLISTED_ROUTE_CANDIDATES_FILE" ]]; then
        blacklisted_route_candidates | sed 's/^/  /'
    else
        printf '  none\n'
    fi
}

resolve_domain_ips_with_sources() {
    local domain="$1" candidates provider_rows
    if [[ -n "$domain" ]]; then
        provider_rows=$(read_dns_candidate_cache "$domain" 2>/dev/null || true)
        if [[ -z "$provider_rows" ]]; then
            provider_rows=$(resolve_dns_provider_ips_with_sources "$domain" || true)
            [[ -n "$provider_rows" ]] && write_dns_candidate_cache "$domain" "$provider_rows"
        fi
    fi
    candidates=$({
        pinned_route_value | awk 'NF {print "pinned\t" $0}'
        manual_route_candidates | awk 'NF {print "manual\t" $0}'
        cached_route_candidate_ips | awk 'NF {print "cache\t" $0}'
        if [[ -n "${G2RAY_EXTRA_FALLBACK_IPS:-}" ]]; then
            printf '%s\n' "$G2RAY_EXTRA_FALLBACK_IPS" | tr ',; ' '\n' | awk 'NF {print "extra\t" $0}'
        fi
        [[ -n "${provider_rows:-}" ]] && printf '%s\n' "$provider_rows"
        printf '%s\n' "$DEFAULT_FALLBACK_IPS" | tr ',; ' '\n' | awk 'NF {print "builtin\t" $0}'
    } | while IFS=$'\t' read -r source ip; do
        valid_ipv4 "$ip" || continue
        candidate_blacklisted "$ip" && continue
        printf '%s\t%s\n' "${source:-unknown}" "$ip"
    done | awk -F '\t' '!seen[$2]++ {print}')
    printf '%s\n' "$candidates"
}

resolve_domain_ips() {
    local domain="$1" candidates joined
    candidates=$(resolve_domain_ips_with_sources "$domain" | awk -F '\t' '{print $2}')
    if [[ -n "$candidates" ]]; then
        joined=$(printf '%s' "$candidates" | tr '\n' ',' | sed 's/,$//')
        log_event INFO "resolver domain=${domain} fallback_candidates=${joined}"
    else
        log_event WARN "resolver domain=${domain} no-ip-candidates"
    fi
    printf '%s\n' "$candidates"
}

resolve_domain_ip() {
    local domain="$1" ip=""
    ip=$(resolve_domain_ips "$domain" | head -1 || true)
    [[ -n "$ip" ]] && { printf '%s' "$ip"; return; }
    printf '%s' "$domain"
}

_atomic_write() {
    local file="$1" content="$2" tmp
    tmp=$(mktemp "${file}.XXXXXX") || return 1
    printf '%s\n' "$content" > "$tmp" || { rm -f "$tmp" 2>/dev/null || true; return 1; }
    chmod 600 "$tmp" 2>/dev/null || true
    mv -f "$tmp" "$file" || { rm -f "$tmp" 2>/dev/null || true; return 1; }
    chmod 600 "$file" 2>/dev/null || true
}

first_nonempty_line() {
    awk 'NF {print; exit}' <<< "${1:-}"
}

one_line() {
    printf '%s' "${1:-}" | tr -d '\r\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

config_vless_uuid() {
    local uuid=""
    [[ -s "$CONFIG_FILE" ]] || return 1
    if command -v jq >/dev/null 2>&1; then
        uuid=$(jq -r '
            [.inbounds[]?
             | select(.protocol == "vless")
             | .settings.clients[]?.id // empty]
            | map(select(type == "string" and length > 0))
            | first // ""
        ' "$CONFIG_FILE" 2>/dev/null | head -1 || true)
    fi
    uuid=$(one_line "$uuid")
    [[ -n "$uuid" ]] || return 1
    printf '%s\n' "$uuid"
}

# The validated Xray config is the runtime source of truth. uuid.txt is a
# persistence cache used while generating a replacement config; preferring it
# over config.json can export links for a UUID the live engine does not accept
# after an interrupted multi-file update.
active_vless_uuid() {
    local uuid=""
    if [[ -e "$CONFIG_FILE" ]]; then
        # Once a config exists, never pair it with a UUID from a different
        # transaction. A malformed/empty config must stop exports instead of
        # silently reviving a stale uuid.txt value.
        uuid=$(config_vless_uuid 2>/dev/null || true)
        [[ -n "$uuid" ]] || return 1
    else
        uuid=$(awk 'NF {print; exit}' "$UUID_FILE" 2>/dev/null || true)
        uuid=$(one_line "$uuid")
    fi
    [[ -n "$uuid" ]] || return 1
    printf '%s\n' "$uuid"
}

restore_uuid_cache_value() {
    local uuid="${1:-}" reason="${2:-unknown}"
    if [[ -z "$uuid" ]]; then
        rm -f "$UUID_FILE" 2>/dev/null || {
            log_event ERROR "uuid_cache clear_failed reason=${reason}"
            return 1
        }
        return 0
    fi
    if _atomic_write "$UUID_FILE" "$uuid"; then
        return 0
    fi
    # A missing cache is safer than a stale UUID because links can be rebuilt
    # from config.json, while a stale cache could point clients at the wrong ID.
    rm -f "$UUID_FILE" 2>/dev/null || true
    log_event ERROR "uuid_cache restore_failed reason=${reason} action=remove_stale_cache"
    return 1
}

generate_wake_secret() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 32 2>/dev/null && return 0
    fi
    if command -v uuidgen >/dev/null 2>&1; then
        printf '%s%s\n' "$(uuidgen | tr -d '-')" "$(uuidgen | tr -d '-')" \
            | tr '[:upper:]' '[:lower:]'
        return 0
    fi
    if [[ -r /dev/urandom ]] && command -v od >/dev/null 2>&1; then
        od -An -N32 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n'
        printf '\n'
        return 0
    fi
    echo "No cryptographically secure random source is available for WAKE_SECRET." >&2
    return 1
}

generate_uuid() {
    local hex
    if command -v uuidgen >/dev/null 2>&1; then
        uuidgen | tr '[:upper:]' '[:lower:]'
        return 0
    fi
    if command -v openssl >/dev/null 2>&1; then
        hex=$(openssl rand -hex 16 2>/dev/null || true)
    elif [[ -r /dev/urandom ]] && command -v od >/dev/null 2>&1; then
        hex=$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')
    else
        hex=""
    fi
    [[ "$hex" =~ ^[0-9a-fA-F]{32}$ ]] || return 1
    hex=$(printf '%s' "$hex" | tr '[:upper:]' '[:lower:]')
    printf '%s-%s-4%s-8%s-%s\n' "${hex:0:8}" "${hex:8:4}" "${hex:13:3}" "${hex:17:3}" "${hex:20:12}"
}

normalize_waker_url() {
    local url host port
    url=$(one_line "${1:-}")
    [[ -n "$url" ]] || return 1
    [[ "$url" != *[[:space:]]* ]] || return 1
    [[ "$url" == http://* ]] && return 1
    [[ "$url" == https://* ]] || url="https://${url}"
    if [[ "$url" =~ ^https://([A-Za-z0-9][A-Za-z0-9.-]*[.][A-Za-z0-9.-]+)(:[0-9]+)?(/wake)?/?$ ]]; then
        host="${BASH_REMATCH[1]:-}"
        port="${BASH_REMATCH[2]:-}"
        printf 'https://%s%s/wake' "$host" "$port"
        return 0
    fi
    return 1
}

waker_metadata_value() {
    local key="$1"
    [[ -f "$WAKER_METADATA_FILE" ]] || return 0
    awk -F= -v k="$key" '$1 == k { sub(/^[^=]*=/, ""); print; exit }' \
        "$WAKER_METADATA_FILE" 2>/dev/null || true
}

save_waker_metadata() {
    local worker_url="$1" wake_fingerprint="$2" codespace="${3:-$CODESPACE_NAME}" configured_at content
    local worker_codespace_id="${4:-$codespace}"
    worker_url=$(normalize_waker_url "$worker_url") || return 1
    wake_fingerprint=$(one_line "$wake_fingerprint")
    codespace=$(one_line "$codespace")
    worker_codespace_id=$(one_line "$worker_codespace_id")
    [[ "$worker_codespace_id" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$ ]] || worker_codespace_id="$codespace"
    configured_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')
    content=$(printf 'worker_url=%s\ncodespace_name=%s\nworker_codespace_id=%s\nwake_secret_fingerprint=%s\nconfigured_at=%s\n' \
        "$worker_url" "$codespace" "$worker_codespace_id" "$wake_fingerprint" "$configured_at")
    _atomic_write "$WAKER_METADATA_FILE" "$content"
    chmod 600 "$WAKER_METADATA_FILE" 2>/dev/null || true
    log_event INFO "waker_metadata saved worker_url_hash=$(fingerprint_secret "$worker_url") codespace=${codespace} worker_codespace_id=${worker_codespace_id}"
}

waker_metadata_summary() {
    local worker_url codespace worker_codespace_id wake_fingerprint configured_at
    worker_url=$(waker_metadata_value worker_url)
    codespace=$(waker_metadata_value codespace_name)
    worker_codespace_id=$(waker_metadata_value worker_codespace_id)
    wake_fingerprint=$(waker_metadata_value wake_secret_fingerprint)
    configured_at=$(waker_metadata_value configured_at)
    if [[ -n "$worker_url" ]]; then
        echo -e "Status      : configured"
        echo -e "Worker URL  : ${WHITE}${worker_url}${NC}"
        echo -e "Codespace   : ${WHITE}${codespace:-$CODESPACE_NAME}${NC}"
        if [[ -n "$worker_codespace_id" && "$worker_codespace_id" != "${codespace:-$CODESPACE_NAME}" ]]; then
            echo -e "Selection ID: ${WHITE}${worker_codespace_id}${NC}"
        fi
        echo -e "Secret      : fingerprint=${wake_fingerprint:-unknown} ${DIM}(raw secret is not stored)${NC}"
        echo -e "Last setup  : ${DIM}${configured_at:-unknown}${NC}"
    else
        echo -e "Status      : not configured"
        echo -e "Next step   : open option 15"
    fi
}

test_cloudflare_waker() {
    local worker_url="${1:-}" wake_secret="${2:-}" response status ok reason codespace route_ready route_status route_latency next_action worker_codespace_id
    worker_url=$(normalize_waker_url "${worker_url:-$(waker_metadata_value worker_url)}" 2>/dev/null || true)
    if [[ -z "$worker_url" ]]; then
        echo -ne "  ${GREEN}Worker wake URL:${NC} "
        read -r worker_url || return 1
        worker_url=$(normalize_waker_url "$worker_url" 2>/dev/null || true)
    fi
    if [[ -z "$worker_url" ]]; then
        echo -e "  ${RED}Missing Worker URL.${NC}"
        return 1
    fi
    if [[ -z "$wake_secret" ]]; then
        echo -ne "  ${GREEN}Wake secret (hidden):${NC} "
        read -r -s wake_secret || { echo ""; return 1; }
        echo ""
    fi

    worker_codespace_id=$(waker_metadata_value worker_codespace_id)
    [[ -n "$worker_codespace_id" ]] || worker_codespace_id="$(waker_metadata_value codespace_name)"
    [[ "$worker_codespace_id" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$ ]] || worker_codespace_id=""
    if [[ -z "$wake_secret" ]]; then
        echo -e "  ${RED}Missing wake secret.${NC}"
        return 1
    fi

    echo -e "  ${DIM}Calling Worker with Authorization: Bearer ...${NC}"
    response=$({
        printf 'request = "POST"\n'
        printf 'url = "%s"\n' "$worker_url"
        printf 'max-time = "%s"\n' "$WAKER_TEST_TIMEOUT_SEC"
        printf 'silent\nshow-error\n'
        printf 'header = "Authorization: Bearer %s"\n' "$wake_secret"
        [[ -n "$worker_codespace_id" ]] && printf 'header = "Content-Type: application/json"\ndata = "{\\"codespace_id\\":\\"%s\\"}"\n' "$worker_codespace_id"
    } | curl --config - 2>&1) || {
        echo -e "  ${RED}Worker call failed.${NC}"
        printf '%s\n' "$response" | sed 's/^/  /'
        return 1
    }

    if command -v jq >/dev/null 2>&1 && printf '%s' "$response" | jq . >/dev/null 2>&1; then
        ok=$(printf '%s' "$response" | jq -r '.ok // false' 2>/dev/null)
        status=$(printf '%s' "$response" | jq -r '.status // empty' 2>/dev/null)
        reason=$(printf '%s' "$response" | jq -r '.reason // empty' 2>/dev/null)
        codespace=$(printf '%s' "$response" | jq -r '.codespace // .body.name // empty' 2>/dev/null)
        route_ready=$(printf '%s' "$response" | jq -r '.route_ready // empty' 2>/dev/null)
        route_status=$(printf '%s' "$response" | jq -r '.route_probe.http_status // empty' 2>/dev/null)
        route_latency=$(printf '%s' "$response" | jq -r '.route_probe.latency_ms // empty' 2>/dev/null)
        next_action=$(printf '%s' "$response" | jq -r '.next_action // empty' 2>/dev/null)
        echo -e "  Result    : ${WHITE}ok=${ok} status=${status:-unknown}${NC}"
        [[ -n "$codespace" ]] && echo -e "  Codespace : ${WHITE}${codespace}${NC}"
        [[ -n "$route_ready" ]] && echo -e "  Route     : ${WHITE}ready=${route_ready} http=${route_status:-unknown} latency=${route_latency:-unknown}ms${NC}"
        [[ -n "$reason" ]] && echo -e "  Reason    : ${YELLOW}${reason}${NC}"
        [[ -n "$next_action" ]] && echo -e "  Next      : ${WHITE}${next_action}${NC}"
    else
        printf '%s\n' "$response" | sed 's/^/  /'
    fi
}

show_waker_recovery_guide() {
    refresh_screen
    echo -e "\n  ${RED}Recovery Guide${NC}\n"
    echo -e "  If configs suddenly stop working because GitHub stopped the Codespace:"
    echo -e "  1. Open your Worker wake URL in a browser."
    echo -e "  2. Paste your wake secret."
    echo -e "  3. Click Start Codespace."
    echo -e "  4. If the Worker reports ${WHITE}route_ready: true${NC}, use the same v2rayN config again."
    echo -e "  5. If it reports ${WHITE}route_ready: false${NC} or HTTP 404, wait 1-2 minutes and retry."
    echo -e "  6. If it stays stuck, open the panel and use option ${WHITE}6) Recover Now${NC}.\n"
    echo -e "  This works only if the Codespace still exists and GitHub quota/token are valid."
    echo -e "  It cannot bypass quota, billing, deletion, or account restrictions.\n"
    echo -e "  ${WHITE}${B}Quota survival${NC}"
    echo -e "  If GitHub returns ${WHITE}HTTP 402${NC}, the Codespace is quota/billing blocked."
    echo -e "  The same configs can work after the monthly reset only if this same Codespace survives."
    echo -e "  Before quota runs out, open GitHub Codespaces, use the ${WHITE}...${NC} menu, and choose ${WHITE}Keep codespace${NC}.\n"
    echo -e "  ${WHITE}${B}Saved Waker${NC}"
    waker_metadata_summary | sed 's/^/  /'
    echo ""; echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
}

setup_cloudflare_waker() {
    local wake_secret wake_fingerprint worker_url ready do_test
    CODESPACE_NAME=$(_detect_codespace_name 2>/dev/null || true)
    refresh_port_domains

    refresh_screen
    echo -e "\n  ${RED}Cloudflare Worker Waker Setup${NC}\n"
    echo -e "  ${WHITE}Detected Codespace:${NC} ${GREEN}${CODESPACE_NAME}${NC}"
    echo -e "  ${WHITE}Codespaces app domain:${NC} ${GREEN}${PORT_DOMAIN}${NC}\n"
    echo -e "  ${WHITE}${B}1. Create a GitHub token${NC}"
    echo -e "  ${WHITE}https://github.com/settings/tokens/new?scopes=codespace${NC}"
    echo -e "  Select the ${WHITE}codespace${NC} scope and copy the token.\n"
    echo -e "  ${WHITE}${B}2. Paste the Worker code${NC}"
    echo -e "  Cloudflare -> Workers & Pages -> your Worker -> Edit code."
    echo -e "  Replace all code with this file, then click Deploy:"
    echo -e "  ${WHITE}${BASE_DIR}/worker/codespace-waker/src/index.js${NC}\n"
    echo -e "  ${WHITE}${B}3. Choose one shared wake key${NC}"
    echo -e "  Use the same ${WHITE}WAKE_SECRET${NC} in Cloudflare and in the Android app."
    echo -e "  For an existing Worker, paste its current key below."
    echo -ne "  ${GREEN}Shared wake key (hidden; Enter generates a new first key):${NC} "
    read -r -s wake_secret || { echo ""; return 1; }
    echo ""
    if [[ -z "$wake_secret" ]]; then
        wake_secret=$(generate_wake_secret) || return 1
        echo -e "  Generated key: ${GREEN}${wake_secret}${NC}"
        echo -e "  ${YELLOW}Save it now. Use this exact same key everywhere.${NC}"
    else
        wake_secret=$(one_line "$wake_secret")
        echo -e "  ${GREEN}Using the existing shared wake key.${NC}"
    fi
    wake_fingerprint=$(fingerprint_secret "$wake_secret")
    echo -e "  ${DIM}Key fingerprint: ${wake_fingerprint}${NC}\n"
    echo -e "  ${WHITE}${B}4. Add exactly these Cloudflare variables${NC}"
    echo -e "  Worker -> Settings -> Variables and Secrets:"
    echo -e "  ${WHITE}CODESPACE_NAME${NC}  Plaintext  ${GREEN}${CODESPACE_NAME}${NC}"
    echo -e "  ${WHITE}GITHUB_TOKEN${NC}    Secret     ${DIM}<your GitHub token>${NC}"
    echo -e "  ${WHITE}WAKE_SECRET${NC}     Secret     ${DIM}<the shared key above>${NC}\n"
    echo -e "  For another Codespace in this same Worker, add only:"
    echo -e "  ${WHITE}CODESPACE_2_NAME${NC}  Plaintext  ${DIM}<second Codespace name>${NC}"
    echo -e "  ${WHITE}GITHUB_TOKEN_2${NC}    Secret     ${DIM}<its account token>${NC}"
    echo -e "  Keep the same ${WHITE}WAKE_SECRET${NC}. No KV or Wrangler is required.\n"
    echo -ne "  ${GREEN}Is the Worker deployed with those values? (y/n):${NC} "
    read -r ready || { touch "$WAKER_PROMPT_FILE" 2>/dev/null || true; return 0; }
    [[ "$ready" =~ ^[Yy]$ ]] || { echo -e "  ${DIM}Setup paused. Open option 15 when ready.${NC}"; sleep 2; return 0; }

    echo -ne "  ${GREEN}Worker wake URL (https optional, /wake optional):${NC} "
    read -r worker_url || { echo -e "  ${RED}Missing Worker URL.${NC}"; sleep 2; return 1; }
    worker_url=$(normalize_waker_url "$worker_url" 2>/dev/null || true)
    if [[ -z "$worker_url" ]]; then
        echo -e "  ${RED}Invalid Worker URL.${NC}"
        echo -e "  ${DIM}Example: https://your-worker.your-subdomain.workers.dev/wake${NC}"
        sleep 2
        return 1
    fi
    save_waker_metadata "$worker_url" "$wake_fingerprint" "$CODESPACE_NAME" "$CODESPACE_NAME"
    touch "$WAKER_PROMPT_FILE" 2>/dev/null || true
    echo -e "  ${GREEN}Saved non-sensitive waker metadata.${NC}"
    echo -e "  ${DIM}GitHub token and raw wake secret were not stored in G2ray.${NC}\n"
    echo -ne "  ${GREEN}Test Worker now? (y/n):${NC} "
    read -r do_test || do_test="n"
    if [[ "$do_test" =~ ^[Yy]$ ]]; then
        test_cloudflare_waker "$worker_url" "$wake_secret" || true
    fi
    echo ""; echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
}

reset_waker_metadata() {
    local confirm
    echo -e "\n  ${YELLOW}Remove saved Worker URL/fingerprint metadata?${NC}"
    echo -ne "  ${GREEN}Proceed (y/n):${NC} "
    read -r confirm || return 0
    [[ "$confirm" =~ ^[Yy]$ ]] || return 0
    rm -f "$WAKER_METADATA_FILE" 2>/dev/null || true
    touch "$WAKER_PROMPT_FILE" 2>/dev/null || true
    log_event INFO "waker_metadata reset"
    echo -e "  ${GREEN}Waker metadata reset.${NC}"
    sleep 1
}

show_recovery_waker() {
    local choice
    while true; do
        refresh_screen
        echo -e "\n  ${RED}Recovery / Waker Setup${NC}\n"
        echo -e "  ${WHITE}${B}External Waker${NC}"
        waker_metadata_summary | sed 's/^/  /'
        echo ""
        echo -e "  ${RED}1)${NC} Setup Cloudflare Waker"
        echo -e "  ${RED}2)${NC} Show Recovery Instructions"
        echo -e "  ${RED}3)${NC} Test Worker URL"
        echo -e "  ${RED}4)${NC} Reset Saved Waker Metadata"
        echo -e "  ${RED}0)${NC} Return"
        echo -ne "  ${RED}Select:${NC} "
        read -r choice || return 0
        case "$choice" in
            1) setup_cloudflare_waker ;;
            2) show_waker_recovery_guide ;;
            3)
                test_cloudflare_waker || true
                echo ""; echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
                ;;
            4) reset_waker_metadata ;;
            0) return 0 ;;
            *) echo -e "  ${RED}Invalid option.${NC}"; sleep 1 ;;
        esac
    done
}

maybe_prompt_waker_setup() {
    [[ -f "$WAKER_METADATA_FILE" || -f "$WAKER_PROMPT_FILE" ]] && return 0
    local answer
    refresh_screen
    echo -e "\n  ${RED}Optional Recovery Waker${NC}\n"
    echo -e "  GitHub can stop Codespaces when idle. This panel can guide you through"
    echo -e "  a Cloudflare Worker wake page so you can restart the Codespace from a browser."
    echo -e "  Do not paste the GitHub token into G2ray; the wizard sends it to Cloudflare only by your hand.\n"
    echo -ne "  ${GREEN}Set up the recovery waker now? (y/n):${NC} "
    read -r answer || { touch "$WAKER_PROMPT_FILE" 2>/dev/null || true; return 0; }
    touch "$WAKER_PROMPT_FILE" 2>/dev/null || true
    [[ "$answer" =~ ^[Yy]$ ]] && setup_cloudflare_waker
}

show_recovery_command_card() {
    local worker_url
    worker_url=$(waker_metadata_value worker_url)
    refresh_screen
    echo -e "\n  ${RED}First-run Recovery Card${NC}\n"
    echo -e "  ${WHITE}Keep these commands handy for the next time GitHub wakes slowly.${NC}"
    echo -e "  ${DIM}This card prints placeholders only; replace <WAKE_SECRET> with your saved secret.${NC}\n"
    echo -e "  ${WHITE}${B}copy these recovery commands${NC}"
    echo -e "  ${GREEN}bash ./g2ray.sh --doctor-json${NC}"
    echo -e "  ${GREEN}bash ./g2ray.sh --recover-now${NC}"
    echo -e "  ${GREEN}bash ./g2ray.sh --recover-now --json${NC}"
    echo -e "  ${GREEN}bash ./g2ray.sh --support-bundle${NC}"
    if [[ -n "$worker_url" ]]; then
        echo -e "  ${DIM}Linux/macOS:${NC}"
        echo -e "  ${GREEN}curl -X POST -H \"Authorization: Bearer <WAKE_SECRET>\" \"${worker_url}\"${NC}"
        echo -e "  ${DIM}Windows PowerShell:${NC}"
        echo -e "  ${GREEN}Invoke-RestMethod -Method Post -Headers @{Authorization=\"Bearer <WAKE_SECRET>\"} -Uri \"${worker_url}\"${NC}"
        echo -e "  ${DIM}Or use curl.exe instead of curl in PowerShell if you prefer curl syntax.${NC}"
    else
        echo -e "  ${DIM}Worker wake command appears here after option 15 is configured.${NC}"
    fi
    echo ""
    echo -e "  ${WHITE}${B}Log files${NC}"
    echo -e "  ${WHITE}${LOG_FILE}${NC}"
    echo -e "  ${WHITE}${STRUCTURED_LOG_FILE}${NC}"
    echo -e "  ${WHITE}${DIAGNOSTIC_LOG_FILE}${NC}\n"
    echo -e "  ${DIM}After this, diagnostics will open automatically so you can confirm route, supervisor, and waker state.${NC}"
    echo -ne "  ${DIM}Press Enter to open diagnostics...${NC}"
    read -r || true
}

write_config_qr_png() {
    local index="$1" link="$2"
    local png_file="$QR_DIR/config-${index}.png"
    command -v qrencode >/dev/null 2>&1 || return 1
    mkdir -p "$QR_DIR" 2>/dev/null || return 1
    qrencode -m 4 -s 8 -t PNG -o "$png_file" "$link" 2>/dev/null || return 1
    chmod 600 "$png_file" 2>/dev/null || true
    printf '%s' "$png_file"
}

render_config_qr() {
    local index="$1" link="$2"
    local png_file=""
    if command -v qrencode >/dev/null 2>&1; then
        if png_file=$(write_config_qr_png "$index" "$link"); then
            echo -e "  ${DIM}High-res QR PNG:${NC} ${WHITE}${png_file}${NC}"
            echo -e "  ${DIM}Open this PNG if your phone cannot scan the terminal preview.${NC}"
        fi
        echo -e "  ${DIM}Terminal QR preview:${NC}"
        qrencode -m 2 -t UTF8 "$link" | while IFS= read -r line; do
            printf '  %s\n' "$line"
        done
    else
        echo -e "  ${DIM}(qrencode not installed - QR unavailable)${NC}"
    fi
}

render_config_entry() {
    local index="$1" label="$2" link="$3"
    local show_qr="${4:-false}"
    echo -e "  ${RED}[${index}]${NC} ${WHITE}${B}${label}${NC}"
    if [[ "$show_qr" == true ]]; then
        echo -e "  ${DIM}QR:${NC}"
        render_config_qr "$index" "$link"
    else
        echo -e "  ${DIM}QR hidden in default view. Set G2RAY_QR_MODE=all to show every QR.${NC}"
    fi
    echo -e "  ${DIM}Copy-ready link:${NC}"
    printf '%s\n' "$link"
    echo ""
}

draw_logo() {
    echo -e "${RED}${B}"
    echo -e "    ██████╗ ██████╗ ██████╗  █████╗ ██╗   ██╗"
    echo -e "   ██╔════╝ ╚════██╗██╔══██╗██╔══██╗╚██╗ ██╔╝"
    echo -e "   ██║  ███╗█████╔╝██████╔╝███████║ ╚████╔╝ "
    echo -e "   ██║   ██║██╔═══╝ ██╔══██╗██╔══██║  ╚██╔╝  "
    echo -e "   ╚██████╔╝███████╗██║  ██║██║  ██║   ██║   "
    echo -e "    ╚═════╝ ╚══════╝╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝   ${NC}"
    echo -e "       ${WHITE}${B}v1.4.3${NC} ${DIM}•${NC} ${WHITE}Educational use only${NC} ${DIM}•${NC} ${WHITE}Customized${NC}\n"
}

refresh_screen() {
    stty sane 2>/dev/null || true
    clear
    draw_logo
}

verify_update_candidate() {
    local candidate="${1:-}"
    [[ -s "$candidate" ]] || return 1
    grep -Fq 'readonly G2RAY_ID="G2ray Panel' "$candidate" 2>/dev/null || return 1
    grep -Fq 'Educational use only' "$candidate" 2>/dev/null || return 1
    grep -Fq 'detect_project_repo_default()' "$candidate" 2>/dev/null || return 1
    grep -Fq 'ensure_runtime_ready()' "$candidate" 2>/dev/null || return 1
    grep -Fq 'print_doctor_json()' "$candidate" 2>/dev/null || return 1
    grep -Fq 'recover_now_json()' "$candidate" 2>/dev/null || return 1
    bash -n "$candidate" 2>/dev/null || return 1
    return 0
}

tracked_panel_has_local_changes() {
    command -v git >/dev/null 2>&1 || return 1
    git -C "$BASE_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1 || return 1
    git -C "$BASE_DIR" diff --quiet -- g2ray.sh 2>/dev/null || return 0
    git -C "$BASE_DIR" diff --cached --quiet -- g2ray.sh 2>/dev/null || return 0
    return 1
}

panel_version_of_file() {
    local candidate="${1:-}"
    [[ -s "$candidate" ]] || return 1
    sed -nE 's/^[[:space:]]*readonly G2RAY_ID="G2ray Panel v([0-9]+\.[0-9]+\.[0-9]+)"[[:space:]]*$/\1/p' "$candidate" | awk 'NR==1 {print}'
}

version_is_older() {
    local candidate="${1:-}" running="${2:-}" first
    [[ "$candidate" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$running" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
    [[ "$candidate" != "$running" ]] || return 1
    first=$(printf '%s\n%s\n' "$candidate" "$running" | sort -V | awk 'NR==1 {print}')
    [[ "$first" == "$candidate" ]]
}

update_candidate_version_ok() {
    local candidate_version running="${2:-${G2RAY_ID##* v}}"
    candidate_version=$(panel_version_of_file "${1:-}") || return 1
    [[ "$candidate_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$running" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
    [[ "${G2RAY_AUTO_UPDATE_FORCE:-0}" == 1 ]] && return 0
    # This project ships fixes without bumping the panel marker every commit.
    # Reject downgrades, not legitimate changed scripts with the same version.
    ! version_is_older "$candidate_version" "$running"
}

record_update_audit() {
    local decision="$1" from="$2" to="$3" sha="${4:-unknown}" source_id ts
    source_id=$(fingerprint_secret "$RAW_BASE_URL")
    ts=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    rotate_log_file "$UPDATE_AUDIT_FILE" || return 1
    printf '%s\t%s\tfrom=%s\tto=%s\tsha256=%s\tsource_id=%s\n' \
        "$ts" "$decision" "$from" "$to" "$sha" "$source_id" >> "$UPDATE_AUDIT_FILE" || return 1
    chmod 600 "$UPDATE_AUDIT_FILE" 2>/dev/null || true
}

check_for_updates() {
    [[ "${G2RAY_AUTO_UPDATE:-0}" == "1" ]] || return 0
    latency_focus_enabled && return 0
    if [[ "${G2RAY_AUTO_UPDATE_FORCE:-0}" != "1" ]] && tracked_panel_has_local_changes; then
        printf "  %b⚠%b %bAuto-update skipped because g2ray.sh has local changes. Set G2RAY_AUTO_UPDATE_FORCE=1 to override.%b\n" "$YELLOW" "$NC" "$DIM" "$NC"
        sleep 1
        return 0
    fi
    clear; draw_logo
    local tmp="" staged="" candidate_version running_version="${G2RAY_ID##* v}" candidate_sha before_sha download_status=0
    before_sha=$(sha256sum "$SCRIPT_PATH" 2>/dev/null | awk '{print $1}' || true)
    [[ "$before_sha" =~ ^[0-9a-f]{64}$ ]] || return 0
    tmp=$(mktemp "${TMPDIR:-/tmp}/g2ray_remote.XXXXXX") || {
        printf "\r  %b✖%b %bUpdate check failed (no temp file).    %b\n" "$RED" "$NC" "$DIM" "$NC"
        sleep 1
        return 0
    }
    curl -s -m 8 -L "${RAW_BASE_URL}/g2ray.sh" -o "$tmp" &
    local pid=$! frames=("⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏") i=0
    while kill -0 "$pid" 2>/dev/null; do
        printf "\r  %b%s%b %bChecking for latest updates...%b" "$GREEN" "${frames[i]}" "$NC" "$WHITE" "$NC"
        i=$(( (i+1) % 10 )); sleep 0.1
    done
    wait "$pid" || download_status=$?
    if (( download_status == 0 )) && verify_update_candidate "$tmp"; then
        candidate_version=$(panel_version_of_file "$tmp")
        candidate_sha=$(sha256sum "$tmp" 2>/dev/null | awk '{print $1}')
        if ! update_candidate_version_ok "$tmp" "$running_version"; then
            record_update_audit refused_version "$running_version" "${candidate_version:-unknown}" "$candidate_sha" || true
            printf '\r  Update refused: invalid version or downgrade.\n'
        elif ! cmp -s "$SCRIPT_PATH" "$tmp"; then
            printf "\r  %b✔%b %bUpdate found! Installing...              %b\n" "$GREEN" "$NC" "$WHITE" "$NC"
            # Stage beside the destination so rename is atomic across filesystems.
            staged=$(mktemp "$SCRIPT_PATH.update.XXXXXX") || {
                record_update_audit apply_failed "$running_version" "$candidate_version" "$candidate_sha" || true
                printf "  %b✖%b %bUpdate staging failed.%b\n" "$RED" "$NC" "$DIM" "$NC"
                rm -f "$tmp" 2>/dev/null || true
                sleep 1
                return 0
            }
            if ! cp "$tmp" "$staged" || ! chmod --reference="$SCRIPT_PATH" "$staged" || ! chmod u+x "$staged"; then
                record_update_audit apply_failed "$running_version" "$candidate_version" "$candidate_sha" || true
                rm -f "$tmp" "$staged" 2>/dev/null || true
                return 0
            fi
            if [[ "$(sha256sum "$SCRIPT_PATH" 2>/dev/null | awk '{print $1}')" != "$before_sha" ]]; then
                record_update_audit changed_during_check "$running_version" "$candidate_version" "$candidate_sha" || true
                printf '  Update skipped: the local panel changed during the download.\n'
                rm -f "$tmp" "$staged" 2>/dev/null || true
                return 0
            fi
            if ! mv -f "$staged" "$SCRIPT_PATH"; then
                record_update_audit apply_failed "$running_version" "$candidate_version" "$candidate_sha" || true
                printf '  Update could not be installed; the previous panel is unchanged.\n'
                rm -f "$tmp" "$staged" 2>/dev/null || true
                return 0
            fi
            record_update_audit applied "$running_version" "$candidate_version" "$candidate_sha" || true
            printf "  %b✔%b %bUpdate applied! Restarting...%b\n" "$GREEN" "$NC" "$WHITE" "$NC"
            rm -f "$tmp" 2>/dev/null || true
            sleep 1.5; exec bash "$SCRIPT_PATH" "$@"
        else
            printf "\r  %b✔%b %bSystem is fully up to date.               %b\n" "$GREEN" "$NC" "$DIM" "$NC"
        fi
    else
        record_update_audit rejected "$running_version" unknown "$(sha256sum "$tmp" 2>/dev/null | awk '{print $1}')" || true
        printf "\r  %b✖%b %bUpdate check failed (network or 404).     %b\n" "$RED" "$NC" "$DIM" "$NC"
    fi
    rm -f "$tmp" "$staged" 2>/dev/null || true
    sleep 1
}

anti_sleep_enabled() {
    [[ ! -f "$ANTI_SLEEP_DISABLED_FILE" ]]
}

anti_sleep_running() {
    command -v tmux >/dev/null 2>&1 || return 1
    tmux has-session -t g2ray_keepalive 2>/dev/null
}

enable_anti_sleep() {
    mkdir -p "$DATA_DIR" 2>/dev/null || true
    rm -f "$ANTI_SLEEP_DISABLED_FILE" 2>/dev/null || true
    anti_sleep_running && return 0
cat > "$DATA_DIR/keepalive.sh" << 'EOF'
#!/bin/bash
while true; do
    curl -s -m 4 https://github.com >/dev/null 2>&1 || true
    sleep 60
done
EOF
    chmod +x "$DATA_DIR/keepalive.sh"
    local keepalive_cmd; keepalive_cmd=$(printf 'bash %q' "$DATA_DIR/keepalive.sh")
    if ! command -v tmux >/dev/null 2>&1 || ! tmux new-session -d -s g2ray_keepalive "$keepalive_cmd" 2>/dev/null; then
        log_event WARN "anti_sleep runtime_start_failed tmux_unavailable_or_rejected"
        return 1
    fi
}

disable_anti_sleep() {
    mkdir -p "$DATA_DIR" 2>/dev/null || true
    _atomic_write "$ANTI_SLEEP_DISABLED_FILE" "1" 2>/dev/null || : > "$ANTI_SLEEP_DISABLED_FILE"
    if anti_sleep_running; then
        tmux kill-session -t g2ray_keepalive 2>/dev/null || true
    fi
}

sync_anti_sleep_runtime() {
    if anti_sleep_enabled; then
        enable_anti_sleep || true
    elif anti_sleep_running; then
        tmux kill-session -t g2ray_keepalive 2>/dev/null || true
    fi
}

is_port_open() {
    if command -v ss >/dev/null 2>&1; then
        ss -H -ltn 2>/dev/null | awk -v port="$XRAY_PORT" '
            { address=$4; if (address ~ (":" port "$") || address ~ (":" port "[[:space:]]*$")) found=1 }
            END { exit(found ? 0 : 1) }
        '
    else
        netstat -tnl 2>/dev/null | awk -v port="$XRAY_PORT" '
            { address=$4; if (address ~ (":" port "$") || address ~ (":" port "[[:space:]]*$")) found=1 }
            END { exit(found ? 0 : 1) }
        '
    fi
}

xray_listener_ready() {
    xray_running || return 1
    is_port_open || return 1
    local code
    code=$(xhttp_probe_status local)
    xhttp_status_usable "$code"
}

stamp_age_sec() {
    local file="$1" now stamp
    [[ -f "$file" ]] || { printf '999999999'; return 0; }
    now=$(date +%s 2>/dev/null || printf '0')
    stamp=$(cat "$file" 2>/dev/null || printf '0')
    [[ "$now" =~ ^[0-9]+$ && "$stamp" =~ ^[0-9]+$ && "$now" -ge "$stamp" ]] \
        || { printf '999999999'; return 0; }
    printf '%s' "$(( now - stamp ))"
}

file_age_sec() {
    local file="$1" now mtime
    [[ -f "$file" ]] || { printf '999999999'; return 0; }
    now=$(date +%s 2>/dev/null || printf '0')
    mtime=$(stat -c %Y "$file" 2>/dev/null || printf '0')
    [[ "$now" =~ ^[0-9]+$ && "$mtime" =~ ^[0-9]+$ && "$now" -ge "$mtime" ]] \
        || { printf '999999999'; return 0; }
    printf '%s' "$(( now - mtime ))"
}

write_epoch_stamp() {
    local file="$1" now
    now=$(date +%s 2>/dev/null || printf '0')
    _atomic_write "$file" "$now"
    chmod 600 "$file" 2>/dev/null || true
}

mark_active_traffic() {
    write_epoch_stamp "$ACTIVE_TRAFFIC_STAMP_FILE" >/dev/null 2>&1 || true
}

active_tunnel_recent() {
    local age window="$ACTIVE_TRAFFIC_WINDOW_SEC"
    [[ "$window" =~ ^[0-9]+$ ]] || window=180
    age=$(stamp_age_sec "$ACTIVE_TRAFFIC_STAMP_FILE")
    [[ "$age" =~ ^[0-9]+$ ]] || age=999999999
    (( age <= window ))
}

inbound_tunnel_connection_present() {
    command -v ss >/dev/null 2>&1 || return 1
    local port
    local -a ports=("$XRAY_PORT")
    if ws_fallback_configured && [[ "$WS_PORT" != "$XRAY_PORT" ]]; then
        ports+=("$WS_PORT")
    fi
    for port in "${ports[@]}"; do
        local connections
        connections=$(ss -Htn state established "( sport = :${port} )" 2>/dev/null || true)
        [[ -n "${connections//[[:space:]]/}" ]] && return 0
    done
    return 1
}

route_repair_cooldown_active() {
    local age cooldown="$ROUTE_REPAIR_COOLDOWN_SEC"
    [[ "$cooldown" =~ ^[0-9]+$ ]] || cooldown=180
    (( cooldown > 0 )) || return 1
    age=$(stamp_age_sec "$ROUTE_REPAIR_STAMP_FILE")
    [[ "$age" =~ ^[0-9]+$ ]] || age=999999999
    (( age < cooldown ))
}

mark_route_repair_attempt() {
    write_epoch_stamp "$ROUTE_REPAIR_STAMP_FILE" >/dev/null 2>&1 || true
}

mark_route_repair_attempt_if_allowed() {
    if route_repair_cooldown_active; then
        return 1
    fi
    mark_route_repair_attempt
    return 0
}

ensure_codespace_port_public_for_port() {
    local port="$1" force="${2:-}" age stamp_file
    [[ "$port" =~ ^[0-9]+$ && "$port" -gt 0 && "$port" -le 65535 ]] || return 1
    stamp_file="${PORT_PUBLIC_STAMP_FILE}.${CODESPACE_NAME}.${port}"
    stamp_file=$(printf '%s' "$stamp_file" | tr -c 'A-Za-z0-9._/-' '_')
    age=$(stamp_age_sec "$stamp_file")
    if [[ "$force" != "force" && "$PORT_PUBLIC_TTL_SEC" =~ ^[0-9]+$ && "$age" -lt "$PORT_PUBLIC_TTL_SEC" ]]; then
        log_event INFO "port_public cached_ok port=${port} age_sec=${age} ttl=${PORT_PUBLIC_TTL_SEC}"
        return 0
    fi
    command -v gh >/dev/null 2>&1 || {
        log_event WARN "port_public gh_missing port=${port}"
        return 1
    }
    local output
    if output=$(run_gh codespace ports visibility "${port}:public" -c "$CODESPACE_NAME" </dev/null 2>&1); then
        write_epoch_stamp "$stamp_file"
        log_event INFO "port_public ok port=${port} forced=${force:-false}"
        return 0
    fi
    output=$(printf '%s' "$output" | tr '\r\n' '  ' | cut -c1-180)
    log_event WARN "port_public failed port=${port} detail=${output:-unknown}"
    return 1
}

ensure_codespace_port_public() {
    ensure_codespace_port_public_for_port "$XRAY_PORT" "${1:-}"
}

ensure_optional_ws_port_public() {
    ws_fallback_configured || return 0
    ensure_codespace_port_public_for_port "$WS_PORT" "${1:-}"
}

force_public_runtime_ports() {
    local reason="${1:-lifecycle}" failed=0
    if ensure_codespace_port_public force >/dev/null 2>&1; then
        log_event INFO "port_public lifecycle_forced reason=${reason} port=${XRAY_PORT}"
    else
        failed=1
        log_event WARN "port_public lifecycle_force_failed reason=${reason} port=${XRAY_PORT}"
    fi
    if ! ensure_optional_ws_port_public force >/dev/null 2>&1; then
        failed=1
        log_event WARN "port_public lifecycle_force_failed reason=${reason} port=${WS_PORT}"
    fi
    return "$failed"
}

publish_runtime_ports_with_retry() {
    local reason="${1:-startup}" max_attempts="${2:-$PORT_PUBLIC_STARTUP_ATTEMPTS}" delay_sec="${3:-$PORT_PUBLIC_STARTUP_RETRY_SEC}" attempt=1
    [[ "$max_attempts" =~ ^[0-9]+$ && "$max_attempts" -ge 1 ]] || max_attempts=4
    (( max_attempts > 8 )) && max_attempts=8
    [[ "$delay_sec" =~ ^[0-9]+$ ]] || delay_sec=2
    (( delay_sec > 10 )) && delay_sec=10

    while (( attempt <= max_attempts )); do
        if force_public_runtime_ports "$reason"; then
            log_event INFO "port_public startup_ready reason=${reason} attempt=${attempt}"
            return 0
        fi
        log_event WARN "port_public startup_retry reason=${reason} attempt=${attempt} max=${max_attempts}"
        if (( attempt < max_attempts && delay_sec > 0 )); then
            sleep "$delay_sec"
        fi
        attempt=$(( attempt + 1 ))
    done

    log_event WARN "port_public startup_exhausted reason=${reason} attempts=${max_attempts} action=background_retry"
    return 1
}

repair_codespace_port_route() {
    command -v gh >/dev/null 2>&1 || return 1
    # Every caller, including panel attach and the settling monitor, must avoid
    # tearing down a live stream because a separate edge probe failed.
    if active_tunnel_recent || inbound_tunnel_connection_present; then
        log_event WARN "route_repair deferred_active_session port=${XRAY_PORT}"
        return 0
    fi
    log_event WARN "route_repair begin port=${XRAY_PORT} domain=${PORT_DOMAIN}"
    run_gh codespace ports visibility "${XRAY_PORT}:private" -c "$CODESPACE_NAME" \
        </dev/null >/dev/null 2>&1 || true
    sleep 2
    if ensure_codespace_port_public force; then
        log_event INFO "route_repair public_ok port=${XRAY_PORT}"
        ensure_optional_ws_port_public force >/dev/null 2>&1 \
            || log_event WARN "route_repair ws_port_public_failed port=${WS_PORT}"
        return 0
    fi
    log_event ERROR "route_repair public_failed port=${XRAY_PORT}"
    return 1
}

wait_for_xhttp_route_ready() {
    local reason="${1:-startup}" max_wait="${2:-$ROUTE_WAIT_SEC}" start now elapsed xcode=0 xms=0 attempt=0 stable=0
    [[ "$max_wait" =~ ^[0-9]+$ ]] || max_wait=120
    start=$(date +%s 2>/dev/null || printf '0')
    while true; do
        attempt=$(( attempt + 1 ))
        read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
        now=$(date +%s 2>/dev/null || printf '0')
        elapsed=$(( now - start ))
        if xhttp_status_usable "$xcode"; then
            stable=$(( stable + 1 ))
            if (( stable >= ROUTE_READY_STABLE_PROBES )); then
                log_event INFO "runtime_ready reason=${reason} route_wait_ready xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} wait_sec=${elapsed} stable_probes=${stable}"
                record_route_settling_metric "$reason" "ready" "${xcode:-0}" "${xms:-0}" "$elapsed" "$attempt"
                return 0
            fi
        else
            stable=0
        fi
        # If the first healthy probe arrives exactly at the deadline, allow the
        # bounded confirmation sequence to finish. Otherwise a recovering route
        # can be reported as timed out solely because the scheduler woke us late.
        # Any following failure resets stable to zero and exits immediately.
        (( elapsed >= max_wait && stable == 0 )) && break
        # A successful probe is being confirmed, not repaired. Flipping its
        # visibility now can interrupt clients and undo natural edge recovery.
        if (( stable > 0 )); then
            sleep "$ROUTE_READY_STABLE_SLEEP_SEC"
            continue
        fi
        if (( attempt == 1 )); then
            ensure_codespace_port_public >/dev/null 2>&1 || true
        elif (( attempt % 5 == 0 )) && mark_route_repair_attempt_if_allowed; then
            # A cached public-visibility result does not always repair a stale
            # Codespaces edge route. Reassert it once per repair cooldown.
            log_event WARN "runtime_ready reason=${reason} route_wait_repair attempt=${attempt} port=${XRAY_PORT}"
            repair_codespace_port_route >/dev/null 2>&1 || true
        fi
        sleep 3
    done
    log_event WARN "runtime_ready reason=${reason} route_wait_timeout xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} wait_sec=${max_wait} stable_probes=${stable}"
    record_route_settling_metric "$reason" "timeout" "${xcode:-0}" "${xms:-0}" "$max_wait" "$attempt"
    return 1
}

route_settle_monitor_running() {
    local p="${1:-}" expected
    [[ "$p" =~ ^[0-9]+$ ]] || return 1
    kill -0 "$p" 2>/dev/null || return 1
    expected=$(cat "$ROUTE_SETTLE_TOKEN_FILE" 2>/dev/null || true)
    [[ -n "$expected" && -r "/proc/$p/environ" ]] || return 1
    tr '\0' '\n' < "/proc/$p/environ" 2>/dev/null | grep -Fxq "G2RAY_ROUTE_SETTLE_TOKEN=$expected"
}

route_settle_token_current() {
    local expected
    expected=$(cat "$ROUTE_SETTLE_TOKEN_FILE" 2>/dev/null || true)
    [[ -n "${G2RAY_ROUTE_SETTLE_TOKEN:-}" && -n "$expected" && "$G2RAY_ROUTE_SETTLE_TOKEN" == "$expected" ]]
}

clear_route_settle_monitor_state() {
    local p expected
    p=$(cat "$ROUTE_SETTLE_PID_FILE" 2>/dev/null || true)
    expected=$(cat "$ROUTE_SETTLE_TOKEN_FILE" 2>/dev/null || true)
    if [[ "$p" == "$$" && -n "${G2RAY_ROUTE_SETTLE_TOKEN:-}" && "$G2RAY_ROUTE_SETTLE_TOKEN" == "$expected" ]]; then
        rm -f "$ROUTE_SETTLE_PID_FILE" "$ROUTE_SETTLE_TOKEN_FILE" 2>/dev/null || true
    fi
}

route_settle_monitor_status() {
    local p
    p=$(cat "$ROUTE_SETTLE_PID_FILE" 2>/dev/null || true)
    if route_settle_monitor_running "$p"; then
        printf 'pid=%s running=true wait_sec=%s log=%s\n' "$p" "$HEADLESS_ROUTE_SETTLE_WAIT_SEC" "$ROUTE_SETTLE_LOG_FILE"
    elif [[ -n "$p" ]]; then
        printf 'pid=%s running=false state=stale\n' "$p"
    else
        printf 'not running\n'
    fi
}

run_headless_route_settle() {
    local reason="${1:-silent_start}" xcode=0 xms=0 probe_reason=""
    [[ -f "$CONFIG_FILE" ]] || return 0
    if [[ -n "${G2RAY_ROUTE_SETTLE_TOKEN:-}" ]] && ! route_settle_token_current; then
        log_event WARN "route_settle superseded reason=${reason} pid=$$"
        return 0
    fi

    sleep "$HEADLESS_ROUTE_SETTLE_INITIAL_DELAY_SEC"
    if ! xray_listener_ready; then
        read -r xcode xms probe_reason < <(xhttp_probe_metrics external)
        write_boot_status "needs_attention" "$reason" "Xray listener was unavailable while background route settling started." "${xcode:-0}" "${xms:-0}" \
            || log_event WARN "boot_status nonfatal_write_failed reason=${reason}"
        log_event ERROR "route_settle reason=${reason} listener_unavailable xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0}"
        return 1
    fi

    publish_runtime_ports_with_retry "route_settle_start" >/dev/null 2>&1 || true
    if wait_for_xhttp_route_ready "$reason" "$HEADLESS_ROUTE_SETTLE_WAIT_SEC"; then
        read -r xcode xms probe_reason < <(xhttp_probe_metrics external)
        reset_route_bad_count
        reset_edge_bad_count
        write_boot_status "ready" "$reason" "Xray listener was ready and asynchronous Codespaces route settling succeeded." "${xcode:-0}" "${xms:-0}" \
            || log_event WARN "boot_status nonfatal_write_failed reason=${reason}"
        log_event INFO "route_settle reason=${reason} ready xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0}"
        return 0
    fi

    read -r xcode xms probe_reason < <(xhttp_probe_metrics external)
    write_boot_status "route_settling" "$reason" "Xray listener is ready, but GitHub's route is still settling after bounded background repair." "${xcode:-0}" "${xms:-0}" \
        || log_event WARN "boot_status nonfatal_write_failed reason=${reason}"
    log_event WARN "route_settle reason=${reason} timeout xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} route_reason=${probe_reason:-unknown}"
    return 1
}

start_headless_route_settling_monitor() {
    local reason="${1:-silent_start}" token p
    [[ -f "$CONFIG_FILE" ]] || return 0
    p=$(cat "$ROUTE_SETTLE_PID_FILE" 2>/dev/null || true)
    if route_settle_monitor_running "$p"; then
        log_event INFO "route_settle already_running reason=${reason} pid=${p}"
        return 0
    fi
    rm -f "$ROUTE_SETTLE_PID_FILE" "$ROUTE_SETTLE_TOKEN_FILE" 2>/dev/null || true
    token=$(uuidgen 2>/dev/null || printf '%s-%s-%s' "$$" "$RANDOM" "$(date +%s)")
    if ! _atomic_write "$ROUTE_SETTLE_TOKEN_FILE" "$token"; then
        log_event ERROR "route_settle token_write_failed reason=${reason}"
        return 1
    fi
    if ! mkdir -p "$LOG_DIR" 2>/dev/null; then
        rm -f "$ROUTE_SETTLE_TOKEN_FILE" 2>/dev/null || true
        log_event ERROR "route_settle log_dir_failed reason=${reason}"
        return 1
    fi
    G2RAY_ROUTE_SETTLE_TOKEN="$token" nohup bash "$SCRIPT_PATH" --headless-route-settle "$reason" </dev/null >> "$ROUTE_SETTLE_LOG_FILE" 2>&1 &
    p=$!
    if ! _atomic_write "$ROUTE_SETTLE_PID_FILE" "$p"; then
        kill "$p" 2>/dev/null || true
        rm -f "$ROUTE_SETTLE_TOKEN_FILE" 2>/dev/null || true
        log_event ERROR "route_settle pid_write_failed reason=${reason}"
        return 1
    fi
    log_event INFO "route_settle started reason=${reason} pid=${p} wait_sec=${HEADLESS_ROUTE_SETTLE_WAIT_SEC}"
}

record_route_settling_metric() {
    local reason="$1" result="$2" code="$3" ms="$4" wait_sec="$5" attempts="$6" checked
    checked=$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date)
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$checked" "$reason" "$result" "${code:-0}" "${ms:-0}" "${wait_sec:-0}" "${attempts:-0}" \
        >> "$ROUTE_SETTLING_HISTORY_FILE" 2>/dev/null || true
    chmod 600 "$ROUTE_SETTLING_HISTORY_FILE" 2>/dev/null || true
}

route_settling_history_summary() {
    if [[ ! -s "$ROUTE_SETTLING_HISTORY_FILE" ]]; then
        printf 'Last route wait : none recorded\n'
        printf 'History         : no route-settling waits have completed yet\n'
        return 0
    fi
    awk -F '\t' '
        NF >= 7 {
            n++;
            wait_sum += $6;
            last = $0;
            if ($3 == "ready") ready++;
            if ($3 == "timeout") timeout++;
        }
        END {
            if (n == 0) {
                print "Last route wait : none recorded";
                print "History         : no route-settling waits have completed yet";
                exit
            }
            split(last, f, "\t");
            printf "Last route wait : %s reason=%s result=%s http=%s wait=%ss attempts=%s\n", f[1], f[2], f[3], f[4], f[6], f[7];
            printf "Summary         : samples=%d ready=%d timeout=%d avg_wait=%ds\n", n, ready+0, timeout+0, wait_sum / n;
            print "Recent waits    :";
        }
    ' "$ROUTE_SETTLING_HISTORY_FILE" 2>/dev/null
    tail -n 6 "$ROUTE_SETTLING_HISTORY_FILE" 2>/dev/null | awk -F '\t' 'NF >= 7 {printf "  %s %-22s %-7s HTTP %-3s wait=%ss attempts=%s\n", $1, $2, $3, $4, $6, $7}'
}

xray_stats_inbound_totals() {
    awk '
        /name[": ]/ {
            name = $0
            next
        }
        /value[": ]/ {
            value = $0
            gsub(/[^0-9.eE+-]/, " ", value)
            count = split(value, parts, /[[:space:]]+/)
            n = 0
            for (i = 1; i <= count; i++) {
                if (parts[i] ~ /^[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$/) {
                    n = parts[i] + 0
                    break
                }
            }
            if (name ~ /inbound/ && name ~ /downlink/) down += n
            if (name ~ /inbound/ && name ~ /uplink/) up += n
            next
        }
        END { printf "%.0f %.0f\n", down + 0, up + 0 }
    '
}

_save_xray_stats_impl() {
    xray_running || return 0
    local stats sd su bd bu svd svu dd du
    stats=$(sudo timeout 3 "$XRAY_BIN" api statsquery -server=127.0.0.1:10085 2>/dev/null) || return 0
    [[ -z "$stats" ]] && return 0
    read -r sd su < <(printf '%s\n' "$stats" | xray_stats_inbound_totals)
    sd=${sd:-0}; su=${su:-0}
    bd=$(jq -r '.down//0' "$SESSION_BYTES_FILE" 2>/dev/null || echo 0)
    bu=$(jq -r '.up//0'   "$SESSION_BYTES_FILE" 2>/dev/null || echo 0)
    dd=$(awk -v s="$sd" -v b="$bd" 'BEGIN{d=s-b; printf "%.0f",(d<0?0:d)}')
    du=$(awk -v s="$su" -v b="$bu" 'BEGIN{d=s-b; printf "%.0f",(d<0?0:d)}')
    if [[ "$dd" =~ ^[0-9]+$ && "$du" =~ ^[0-9]+$ ]] && (( dd > 0 || du > 0 )); then
        mark_active_traffic
    fi
    svd=$(jq -r '.down//0' "$SAVED_BYTES_FILE" 2>/dev/null || echo 0)
    svu=$(jq -r '.up//0'   "$SAVED_BYTES_FILE" 2>/dev/null || echo 0)
    _atomic_write "$SAVED_BYTES_FILE" \
        "$(printf '{"down":%s,"up":%s}' \
            "$(awk -v a="$svd" -v b="$dd" 'BEGIN{printf "%.0f",a+b}')" \
            "$(awk -v a="$svu" -v b="$du" 'BEGIN{printf "%.0f",a+b}')")"
    _atomic_write "$SESSION_BYTES_FILE" \
        "$(printf '{"down":%s,"up":%s}' "$sd" "$su")"
}

save_xray_stats() {
    with_file_lock "$TRAFFIC_STATS_LOCK_FILE" 3 _save_xray_stats_impl "$@"
}

get_data_usage() {
    local svd svu sd=0 su=0 stats fd fu bd bu
    svd=$(jq -r '.down//0' "$SAVED_BYTES_FILE" 2>/dev/null || echo 0)
    svu=$(jq -r '.up//0'   "$SAVED_BYTES_FILE" 2>/dev/null || echo 0)
    if xray_running; then
        stats=$(sudo timeout 3 "$XRAY_BIN" api statsquery -server=127.0.0.1:10085 2>/dev/null) || true
        if [[ -n "$stats" ]]; then
            read -r fd fu < <(printf '%s\n' "$stats" | xray_stats_inbound_totals)
            bd=$(jq -r '.down//0' "$SESSION_BYTES_FILE" 2>/dev/null || echo 0)
            bu=$(jq -r '.up//0'   "$SESSION_BYTES_FILE" 2>/dev/null || echo 0)
            sd=$(awk -v s="${fd:-0}" -v b="$bd" 'BEGIN{d=s-b; printf "%.0f",(d<0?0:d)}')
            su=$(awk -v s="${fu:-0}" -v b="$bu" 'BEGIN{d=s-b; printf "%.0f",(d<0?0:d)}')
        fi
    fi
    printf '%s %s' \
        "$(awk -v a="$svd" -v b="$sd" 'BEGIN{printf "%.0f",a+b}')" \
        "$(awk -v a="$svu" -v b="$su" 'BEGIN{printf "%.0f",a+b}')"
}

reset_session_bytes_baseline() {
    _atomic_write "$SESSION_BYTES_FILE" '{"down":0,"up":0}'
}

current_quota_cycle() {
    date -u '+%Y-%m' 2>/dev/null || date '+%Y-%m'
}

reset_monthly_quota_if_needed() {
    local current stored now
    current=$(current_quota_cycle)
    stored=$(cat "$QUOTA_CYCLE_FILE" 2>/dev/null || true)
    if [[ -z "$stored" ]]; then
        _atomic_write "$QUOTA_CYCLE_FILE" "$current" || return 1
        return 0
    fi
    [[ "$stored" == "$current" ]] && return 0
    now=$(date +%s)
    [[ "$now" =~ ^[0-9]+$ ]] || return 1
    # Commit the cycle marker last so an interrupted reset is retried safely.
    _atomic_write "$TOTAL_UPTIME_FILE" "0" || return 1
    _atomic_write "$SESSION_START_FILE" "$now" || return 1
    _atomic_write "$QUOTA_CYCLE_FILE" "$current" || return 1
    log_event INFO "quota_cycle_reset old=${stored} new=${current}"
}

save_session_uptime() {
    local ss now elapsed prev total
    reset_monthly_quota_if_needed || return 1
    now=$(date +%s)
    [[ "$now" =~ ^[0-9]+$ ]] || return 1
    ss=$(cat "$SESSION_START_FILE" 2>/dev/null || true)
    if [[ ! "$ss" =~ ^[0-9]+$ ]]; then
        log_event WARN "uptime_state invalid_session_start action=reset"
        ss="$now"
    fi
    elapsed=$(( now - ss ))
    (( elapsed < 0    )) && elapsed=0
    (( elapsed > 3600 )) && elapsed=3600
    prev=$(cat "$TOTAL_UPTIME_FILE" 2>/dev/null || true)
    if [[ ! "$prev" =~ ^[0-9]+$ ]]; then
        log_event WARN "uptime_state invalid_total action=reset"
        prev=0
    fi
    total=$(( prev + elapsed ))
    _atomic_write "$TOTAL_UPTIME_FILE" "$total" || return 1
    _atomic_write "$SESSION_START_FILE" "$now" || return 1
}

# Publish a populated directory in one rename. Publishing mkdir before the PID
# lets another process mistake a live, not-yet-initialized claim for stale state.
_try_claim_lock_dir() {
    local dir="$1" op="${2:-}" staging self_pid="${BASHPID:-$$}"
    [[ ! -e "$dir" && ! -L "$dir" ]] || return 1
    staging=$(mktemp -d "${dir}.claim.XXXXXX") || return 1
    if printf '%s\n' "$self_pid" > "$staging/pid" &&
        { [[ -z "$op" ]] || printf '%s\n' "$op" > "$staging/op"; } &&
        mv -T -n -- "$staging" "$dir" 2>/dev/null && [[ ! -d "$staging" ]]; then
        return 0
    fi
    rm -f "$staging/pid" "$staging/op" 2>/dev/null || true
    rmdir "$staging" 2>/dev/null || true
    return 1
}

acquire_runtime_lock() {
    local op="${1:-runtime}" i lock_pid recheck attempts="$RUNTIME_LOCK_WAIT_ATTEMPTS" self_pid="${BASHPID:-$$}"
    [[ "$attempts" =~ ^[0-9]+$ && "$attempts" -ge 1 ]] || attempts=20
    for ((i=1; i<=attempts; i++)); do
        if _try_claim_lock_dir "$RUNTIME_LOCK_DIR" "$op"; then
            return 0
        fi
        lock_pid=$(cat "$RUNTIME_LOCK_DIR/pid" 2>/dev/null || true)
        if [[ -z "$lock_pid" || ! "$lock_pid" =~ ^[0-9]+$ ]]; then
            log_event WARN "runtime_lock_stale malformed pid=${lock_pid:-missing} op=${op}"
            rm -f "$RUNTIME_LOCK_DIR/pid" "$RUNTIME_LOCK_DIR/op" 2>/dev/null || true
            rmdir "$RUNTIME_LOCK_DIR" 2>/dev/null || true
            _try_claim_lock_dir "$RUNTIME_LOCK_DIR" "$op" && return 0
            continue
        fi
        if [[ "$lock_pid" == "$self_pid" ]]; then
            rm -f "$RUNTIME_LOCK_DIR/pid" "$RUNTIME_LOCK_DIR/op" 2>/dev/null || true
            rmdir "$RUNTIME_LOCK_DIR" 2>/dev/null || true
            _try_claim_lock_dir "$RUNTIME_LOCK_DIR" "$op" && return 0
            continue
        fi
        if ! kill -0 "$lock_pid" 2>/dev/null; then
            # Re-read the pid right before breaking, so we never delete a lock that
            # a third process legitimately acquired since our liveness check.
            recheck=$(cat "$RUNTIME_LOCK_DIR/pid" 2>/dev/null || true)
            if [[ "$recheck" == "$lock_pid" ]]; then
                rm -f "$RUNTIME_LOCK_DIR/pid" "$RUNTIME_LOCK_DIR/op" 2>/dev/null || true
                rmdir "$RUNTIME_LOCK_DIR" 2>/dev/null || true
                _try_claim_lock_dir "$RUNTIME_LOCK_DIR" "$op" && return 0
            fi
            continue
        fi
        sleep 0.2
    done
    return 1
}

release_runtime_lock() {
    local lock_pid self_pid="${BASHPID:-$$}"
    lock_pid=$(cat "$RUNTIME_LOCK_DIR/pid" 2>/dev/null || true)
    [[ "$lock_pid" == "$self_pid" ]] || return 0
    rm -f "$RUNTIME_LOCK_DIR/pid" "$RUNTIME_LOCK_DIR/op" 2>/dev/null || true
    rmdir "$RUNTIME_LOCK_DIR" 2>/dev/null || true
}

with_runtime_lock() {
    local op="${1:-runtime}" rc shell_opts old_held
    if [[ "${G2RAY_RUNTIME_LOCK_HELD:-}" == "1" ]]; then
        "$@"
        return $?
    fi
    if ! acquire_runtime_lock "$op"; then
        log_event WARN "runtime_lock busy op=${op}"
        return 1
    fi
    old_held="${G2RAY_RUNTIME_LOCK_HELD-__unset__}"
    shell_opts="$-"
    G2RAY_RUNTIME_LOCK_HELD=1
    set +e
    "$@"
    rc=$?
    case "$shell_opts" in
        *e*) set -e ;;
        *) set +e ;;
    esac
    if [[ "$old_held" == "__unset__" ]]; then
        unset G2RAY_RUNTIME_LOCK_HELD
    else
        G2RAY_RUNTIME_LOCK_HELD="$old_held"
    fi
    release_runtime_lock
    return "$rc"
}

_stop_xray_impl() {
    save_xray_stats 2>/dev/null || true
    local p _grace owned_pids=()
    mapfile -t owned_pids < <(owned_xray_pids | awk 'NF && !seen[$0]++ {print}')
    if ((${#owned_pids[@]})); then
        for p in "${owned_pids[@]}"; do
            if xray_pid_matches "$p" && sudo kill -0 "$p" 2>/dev/null; then
                log_event INFO "stop_xray pid=${p}"
                sudo kill "$p" >/dev/null 2>&1 || true
                for _grace in 1 2 3 4 5 6 7 8 9 10; do
                    sudo kill -0 "$p" 2>/dev/null || break
                    sleep 0.2
                done
                if sudo kill -0 "$p" 2>/dev/null; then
                    sudo kill -9 "$p" >/dev/null 2>&1 || true
                    log_event WARN "stop_xray forced_kill pid=${p}"
                fi
            fi
        done
    else
        log_event INFO "stop_xray no-owned-process"
    fi
    if ((${#owned_pids[@]})); then
        for p in "${owned_pids[@]}"; do
            if xray_pid_matches "$p" && sudo kill -0 "$p" 2>/dev/null; then
                log_event ERROR "stop_xray still_alive pid=${p}"
                return 1
            fi
        done
    fi
    rm -f "$XRAY_PID_FILE" 2>/dev/null || true
    ((${#owned_pids[@]})) && sleep 0.2
    return 0
}

stop_xray() {
    with_runtime_lock _stop_xray_impl "$@"
}

upgrade_config_dns() {
    [[ -f "$CONFIG_FILE" ]] || return 0
    if ! command -v jq >/dev/null 2>&1; then
        log_event WARN "config_dns jq_unavailable"
        return 0
    fi

    local tmp origin_tfo=false
    tmp=$(mktemp "${CONFIG_FILE}.dns.XXXXXX") || {
        log_event WARN "config_dns temp_create_failed"
        return 1
    }
    if tcp_fast_open_outbound_enabled; then origin_tfo=true; fi
    if jq --argjson ws_heartbeat "$WS_HEARTBEAT_PERIOD_SEC" --argjson max_buffered_posts "$XHTTP_SERVER_MAX_BUFFERED_POSTS" \
      --argjson origin_tfo "$origin_tfo" '
      .dns = {
        "servers": ["localhost", "168.63.129.16", "1.1.1.1", "1.0.0.1", "8.8.8.8"],
        "queryStrategy": "UseIPv4",
        "disableCache": false,
        "disableFallback": false,
        "disableFallbackIfMatch": false,
        "enableParallelQuery": true,
        "serveStale": true,
        "serveExpiredTTL": 600
      }
      | (.routing.domainStrategy) = "AsIs"
      | (.routing.rules) = (
          ((.routing.rules // [])
            | map(select(((.domain // []) | index("geosite:category-ads-all")) == null))
          ) as $rules
          | if any($rules[]?; .type == "field" and .network == "udp" and (.port|tostring) == "443" and .outboundTag == "block")
            then $rules
            else ([{ "type": "field", "network": "udp", "port": "443", "outboundTag": "block" }] + $rules)
            end
        )
      | (.inbounds[]? | select(.sniffing? and .sniffing.destOverride?) | .sniffing.destOverride) |= (map(select(. != "quic")))
      | (.inbounds[]? | select((.tag == "vless-in" or .tag == "vless-ws") and .sniffing?) | .sniffing.routeOnly) = true
      | (.inbounds[]? | select(.tag == "vless-in" and .streamSettings.xhttpSettings?) | .streamSettings.xhttpSettings.mode) = "auto"
      | (.inbounds[]? | select(.tag == "vless-in" and .streamSettings.xhttpSettings?) | .streamSettings.xhttpSettings.scMaxBufferedPosts) |=
          (if . == null or . < $max_buffered_posts then $max_buffered_posts else . end)
      | (.inbounds[]? | select(.tag == "vless-ws" and .streamSettings.wsSettings?) | .streamSettings.wsSettings.heartbeatPeriod) = $ws_heartbeat
      | (.outbounds[]? | select(.protocol == "blackhole") | .settings.response.type) = "none"
      | (.outbounds[]? | select(.tag == "direct" and .protocol == "freedom") | .streamSettings.sockopt.tcpFastOpen) = $origin_tfo
      | (.policy.levels["0"].handshake) |=
          (if . == null or . < 4 then 4 else . end)
    ' "$CONFIG_FILE" > "$tmp" 2>/dev/null; then
        if cmp -s "$CONFIG_FILE" "$tmp"; then
            rm -f "$tmp" 2>/dev/null || true
        else
            if ! mv -f "$tmp" "$CONFIG_FILE"; then
                rm -f "$tmp" 2>/dev/null || true
                log_event ERROR "config_dns commit_failed"
                return 1
            fi
            log_event INFO "config_dns refreshed"
        fi
    else
        rm -f "$tmp" 2>/dev/null || true
        log_event WARN "config_dns refresh_failed"
        return 1
    fi
}

prune_xray_configtest_logs() {
    local dir age="$XRAY_CONFIGTEST_LOG_MAX_AGE_MIN" tmp_dir="${TMPDIR:-/tmp}"
    [[ "$age" =~ ^[0-9]+$ && "$age" -ge 60 ]] || age=1440
    if [[ -d "$LOG_DIR" ]]; then
        find "$LOG_DIR" -maxdepth 1 -type f -name 'xray-configtest.*.log' \
            -mmin +"$age" -delete 2>/dev/null || true
    fi
    if [[ "$tmp_dir" != "$LOG_DIR" && -d "$tmp_dir" ]]; then
        find "$tmp_dir" -maxdepth 1 -type f -name 'g2ray-xray-configtest.*.log' \
            -mmin +"$age" -delete 2>/dev/null || true
    fi
}

xray_validate_config_file() {
    local config_file="${1:-}" tmp
    [[ -f "$config_file" ]] || return 1
    if command -v jq >/dev/null 2>&1 && ! jq . "$config_file" >/dev/null 2>&1; then
        log_event ERROR "xray_config_json_invalid file=$(basename "$config_file")"
        echo -e "  ${RED}ERROR: Xray config JSON is invalid.${NC}"
        return 1
    fi
    prune_xray_configtest_logs
    tmp=$(mktemp "${LOG_DIR}/xray-configtest.XXXXXX.log" 2>/dev/null || mktemp "${TMPDIR:-/tmp}/g2ray-xray-configtest.XXXXXX.log") || return 1
    if sudo timeout 10 "$XRAY_BIN" run -test -c "$config_file" >"$tmp" 2>&1; then
        rm -f "$tmp" 2>/dev/null || true
        return 0
    fi
    log_event ERROR "start_xray config_test_failed file=$(basename "$config_file") log=${tmp}"
    echo -e "  ${RED}ERROR: Xray config validation failed. See:${NC} ${WHITE}${tmp}${NC}"
    tail -n 8 "$tmp" 2>/dev/null | sed 's/^/  /' || true
    return 1
}

xray_validate_config() {
    xray_validate_config_file "$CONFIG_FILE"
}

restore_prestart_config_backup() {
    local backup="${1:-}" reason="${2:-unknown}"
    [[ -f "$backup" ]] || {
        log_event ERROR "start_xray config_restore_missing reason=${reason} backup=${backup:-missing}"
        return 1
    }
    if cmp -s "$backup" "$CONFIG_FILE"; then
        rm -f "$backup" 2>/dev/null || true
        return 0
    fi
    if mv -f "$backup" "$CONFIG_FILE"; then
        chmod 600 "$CONFIG_FILE" 2>/dev/null || true
        log_event WARN "start_xray config_restored reason=${reason}"
        return 0
    fi
    log_event ERROR "start_xray config_restore_failed reason=${reason} backup=${backup}"
    return 1
}

prepare_xray_logs() {
    local file owner
    owner="$(id -u):$(id -g)"
    for file in "$LOG_DIR/xray.log" "$LOG_DIR/xray-error.log"; do
        [[ ! -L "$file" ]] || return 1
        if [[ -e "$file" ]]; then
            [[ -f "$file" ]] || return 1
            if [[ ! -w "$file" ]]; then
                sudo -n chown "$owner" -- "$file" 2>/dev/null || return 1
            fi
        fi
        # Precreate as the panel user; root Xray retains that ownership.
        { : >> "$file"; } 2>/dev/null || return 1
        chmod 600 "$file" 2>/dev/null || return 1
    done
}

_start_xray_impl() {
    START_XRAY_LAST_PHASE="preflight"
    if [[ ! -f "$CONFIG_FILE" ]]; then
        log_event WARN "start_xray missing_config path=${CONFIG_FILE}"
        echo -e "  ${RED}✖ No config found. Generate one first (Option 2).${NC}"
        return 1
    fi
    local launch_cmd pid config_backup="" _grace
    log_event INFO "start_xray requested port=${XRAY_PORT} config=${CONFIG_FILE}"
    if ! prepare_xray_logs; then
        log_event ERROR "start_xray log_preflight_failed action=preserve_current_engine"
        echo -e "  ${RED}Could not prepare Xray logs. Current engine was not stopped.${NC}"
        return 1
    fi
    launch_cmd=$(printf 'ulimit -n "${G2RAY_XRAY_FD_LIMIT:-65536}" 2>/dev/null || true; nohup %q run -c %q </dev/null >>%q 2>&1 & printf "%%s\n" "$!"' \
        "$XRAY_BIN" "$CONFIG_FILE" "$LOG_DIR/xray.log")
    config_backup=$(mktemp "${CONFIG_FILE}.prestart.XXXXXX") || {
        log_event ERROR "start_xray config_backup_create_failed"
        return 1
    }
    if ! cp -- "$CONFIG_FILE" "$config_backup"; then
        rm -f "$config_backup" 2>/dev/null || true
        log_event ERROR "start_xray config_backup_failed"
        return 1
    fi
    if ! upgrade_config_dns >/dev/null 2>&1; then
        restore_prestart_config_backup "$config_backup" "config_migration_failed" || true
        log_event ERROR "start_xray config_migration_failed action=preserve_current_engine"
        echo -e "  ${RED}Could not migrate Xray config. Current engine was not stopped.${NC}"
        return 1
    fi
    if ! xray_validate_config; then
        restore_prestart_config_backup "$config_backup" "migration_validation_failed" || true
        return 1
    fi
    if ! stop_xray; then
        START_XRAY_LAST_PHASE="previous_stop_failed"
        restore_prestart_config_backup "$config_backup" "previous_engine_stop_failed" || true
        log_event ERROR "start_xray stop_previous_failed"
        echo -e "  ${RED}✖ Could not stop previous Xray process.${NC}"
        return 1
    fi
    START_XRAY_LAST_PHASE="previous_stopped"
    rm -f "$config_backup" 2>/dev/null || true
    reset_session_bytes_baseline || log_event WARN "start_xray session_baseline_write_failed"
    pid=$(sudo bash -c "$launch_cmd" 2>/dev/null || true)
    if [[ ! "$pid" =~ ^[0-9]+$ ]]; then
        START_XRAY_LAST_PHASE="launch_failed"
        log_event ERROR "start_xray launch_failed"
        echo -e "  ${RED}✖ Failed to start Xray.${NC}"
        return 1
    fi
    START_XRAY_LAST_PHASE="process_launched"
    if ! _atomic_write "$XRAY_PID_FILE" "$pid"; then
        log_event ERROR "start_xray pid_write_failed pid=${pid} action=terminate_launch"
        sudo kill "$pid" >/dev/null 2>&1 || true
        for _grace in 1 2 3 4 5 6 7 8 9 10; do
            sudo kill -0 "$pid" 2>/dev/null || break
            sleep 0.1
        done
        if sudo kill -0 "$pid" 2>/dev/null; then
            sudo kill -9 "$pid" >/dev/null 2>&1 || true
        fi
        rm -f "$XRAY_PID_FILE" 2>/dev/null || true
        START_XRAY_LAST_PHASE="launch_cleaned"
        echo -e "  ${RED}✖ Xray launched but its ownership state could not be saved; the launch was stopped safely.${NC}"
        return 1
    fi
    START_XRAY_LAST_PHASE="running"
    log_event INFO "xray launched pid=${pid} port=${XRAY_PORT}"
}

start_xray() {
    with_runtime_lock _start_xray_impl "$@"
}

wait_for_port() {
    local i=0 frame_index=0
    local frames=("-" "\\" "|" "/")
    while ! xray_listener_ready && (( i < 15 )); do
        printf "\r  ${GREEN}%s${NC} ${DIM}Initializing engine... (${i}s)${NC}" "${frames[$frame_index]}"
        frame_index=$(( (frame_index + 1) % ${#frames[@]} ))
        sleep 1
        i=$((i + 1))
    done
    printf "\r  ${GREEN}%s${NC} ${DIM}Initializing engine... (${i}s)${NC}        \n" "${frames[$frame_index]}"
    if xray_listener_ready; then
        log_event INFO "wait_for_port open port=${XRAY_PORT} seconds=${i}"
        return 0
    fi
    log_event ERROR "wait_for_port timeout port=${XRAY_PORT} seconds=${i}"
    return 1
}

health_probe() {
    local engine="stopped" listener="closed" code xcode xms xhttp_route_usable=false
    xray_running && engine="running"
    is_port_open && listener="open"
    code=$(curl_http_code "https://${PORT_DOMAIN}" 5)
    read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
    xhttp_status_usable "$xcode" && xhttp_route_usable=true
    log_event INFO "health engine=${engine} listener=${listener} external_code=${code:-0} xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} xhttp_route_usable=${xhttp_route_usable} domain=${PORT_DOMAIN}"
}

observe_route_latency() {
    local code="${1:-0}" ms="${2:-0}" key="${PORT_DOMAIN}" old_key="" stamp=0 baseline=0 samples=0 count=0 now degraded=false
    now=$(date +%s)
    if [[ -s "$ROUTE_LATENCY_BASELINE_FILE" ]]; then
        read -r old_key stamp baseline samples count < "$ROUTE_LATENCY_BASELINE_FILE" || true
    fi
    if [[ "$old_key" != "$key" || ! "$stamp" =~ ^(0|[1-9][0-9]{0,11})$ || ! "$baseline" =~ ^(0|[1-9][0-9]{0,5})$ \
        || ! "$samples" =~ ^(0|[1-9][0-9]{0,6})$ || ! "$count" =~ ^(0|[1-9][0-9]{0,2})$ ]] \
        || (( now < stamp || now - stamp > 1800 )); then
        baseline=0; samples=0; count=0
    fi
    if ! xhttp_status_usable "$code" || [[ ! "$ms" =~ ^(0|[1-9][0-9]{0,5})$ ]]; then
        # A failed probe breaks the consecutive chain, not the latency baseline.
        if (( samples > 0 )); then
            _atomic_write "$ROUTE_LATENCY_BASELINE_FILE" "$key $stamp $baseline $samples 0" || return 1
        fi
        return 0
    fi
    if (( samples >= ROUTE_DEGRADED_MIN_SAMPLES && baseline > 0 \
        && ms >= baseline * ROUTE_DEGRADED_MULTIPLIER && ms >= baseline + ROUTE_DEGRADED_MIN_DELTA_MS )); then
        degraded=true
        count=$((count + 1))
        (( count > ROUTE_DEGRADED_TRIGGER_COUNT )) && count="$ROUTE_DEGRADED_TRIGGER_COUNT"
        # Degraded samples must not raise the threshold for the next sample.
        # Adapt the baseline only after a healthy observation below.
    else
        count=0
        (( samples == 0 )) && baseline="$ms" || baseline=$(((baseline * 7 + ms * 3) / 10))
    fi
    samples=$((samples + 1))
    (( samples > 1000000 )) && samples=1000000
    _atomic_write "$ROUTE_LATENCY_BASELINE_FILE" "$key $now $baseline $samples $count" || return 1
    [[ "$degraded" == true ]] || return 0
    (( count >= ROUTE_DEGRADED_TRIGGER_COUNT )) || return 0
    (( $(stamp_age_sec "$ROUTE_DEGRADED_REFRESH_STAMP_FILE") >= ROUTE_DEGRADED_REFRESH_COOLDOWN_SEC )) || return 0
    route_refresh_retry_allowed || return 0
    _atomic_write "$ROUTE_DEGRADED_REQUEST_FILE" "$key $now" || return 1
    _atomic_write "$ROUTE_DEGRADED_REFRESH_STAMP_FILE" "$now" || return 1
    log_event WARN "self_heal route_degraded xhttp_probe_ms=${ms} action=queue_route_refresh"
}

refresh_degraded_routes_if_requested() {
    local key stamp now status=0
    [[ -s "$ROUTE_DEGRADED_REQUEST_FILE" ]] || return 0
    read -r key stamp < "$ROUTE_DEGRADED_REQUEST_FILE" || return 1
    now=$(date +%s)
    if [[ "$key" != "$PORT_DOMAIN" || ! "$stamp" =~ ^(0|[1-9][0-9]{0,11})$ ]] || (( now < stamp || now - stamp > 600 )); then
        rm -f "$ROUTE_DEGRADED_REQUEST_FILE" 2>/dev/null || true
        return 0
    fi
    route_refresh_retry_allowed || return 0
    rm -f "$ROUTE_DEGRADED_REQUEST_FILE" || return 1
    # Run outside the shorter self-heal deadline. --force bypasses a fresh
    # cache, but the existing route lock, retry backoff and deadlines remain.
    write_background_supervisor_heartbeat >/dev/null 2>&1 || true
    run_with_deadline "$G2RAY_SUPERVISOR_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health --force || status=$?
    write_background_supervisor_heartbeat >/dev/null 2>&1 || true
    if (( status != 0 )); then
        log_event WARN "route_degraded refresh_failed action=retain_exports"
        return 0
    fi
    if [[ "$(route_refresh_state_value consecutive_failures)" == 0 ]] && route_health_cache_fresh; then
        run_with_deadline "$G2RAY_SUPERVISOR_EXPORT_TIMEOUT_SEC" refresh_config_exports_if_changed || true
    fi
    write_background_supervisor_heartbeat >/dev/null 2>&1 || true
}

self_heal_once() {
    [[ -f "$CONFIG_FILE" ]] || return 0
    local reason="" code xcode xms bad_count threshold traffic_active=false traffic_signal=none

    if ! ensure_codespace_port_public >/dev/null 2>&1; then
        log_event WARN "self_heal port_public_failed port=${XRAY_PORT}"
    fi
    if ! ensure_optional_ws_port_public >/dev/null 2>&1; then
        log_event WARN "self_heal ws_port_public_failed port=${WS_PORT}"
    fi

    if ! xray_running; then
        reason=xray_stopped
    elif ! xray_listener_ready; then
        reason=listener_closed
    fi

    if [[ -n "$reason" ]]; then
        observe_route_latency 0 0 >/dev/null 2>&1 || true
        log_event WARN "self_heal restart reason=${reason}"
        if start_xray >/dev/null 2>&1 && wait_for_port >/dev/null 2>&1; then
            log_event INFO "self_heal restart_ok reason=${reason}"
            ensure_codespace_port_public >/dev/null 2>&1 || true
        else
            log_event ERROR "self_heal restart_failed reason=${reason}"
            return 1
        fi
    fi

    read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
    observe_route_latency "$xcode" "$xms" >/dev/null 2>&1 || true
    if xhttp_status_usable "$xcode"; then
        reset_route_bad_count
        reset_edge_bad_count
        return 0
    fi

    # A probe is only a control-plane signal. Do not tear down an active tunnel
    # because GitHub's edge briefly returns 404/timeout while real traffic is
    # still flowing. Dead core/listener recovery above remains immediate.
    if active_tunnel_recent; then
        traffic_active=true
        traffic_signal=recent_bytes
    elif inbound_tunnel_connection_present; then
        # Long-lived HTTP streams and WebSockets can be completely quiet while
        # still valid. Reasserting a Codespaces port destroys those sessions.
        traffic_active=true
        traffic_signal=established_connection
    fi
    if [[ "$traffic_active" == true ]]; then
        reset_route_bad_count
        reset_edge_bad_count
        log_event WARN "self_heal external_probe_failed xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} action=defer_active_traffic signal=${traffic_signal}"
        return 0
    fi

    code=$(curl_http_code "https://${PORT_DOMAIN}" 5)
    if [[ "$code" == "000" || "$code" == "0" ]]; then
        threshold="$SELF_HEAL_EDGE_RECONNECT_THRESHOLD"
        [[ "$threshold" =~ ^[0-9]+$ ]] || threshold=3
        bad_count=$(increment_edge_bad_count)
        if edge_reconnect_cooldown_active; then
            log_event WARN "self_heal edge_unreachable code=${code:-0} bad_count=${bad_count} xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} action=cooldown"
            return 0
        fi
        if (( bad_count < threshold )); then
            log_event WARN "self_heal edge_unreachable code=${code:-0} bad_count=${bad_count} action=observe xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0}"
            return 0
        fi
        log_event WARN "self_heal edge_unreachable code=${code:-0} bad_count=${bad_count} action=force_reconnect xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0}"
        mark_edge_reconnect_attempt
        if force_reconnect --no-prompt --defer-route-check >/dev/null 2>&1; then
            reset_edge_bad_count
        else
            log_event ERROR "self_heal force_reconnect_failed code=${code:-0}"
        fi
        return 0
    fi

    reset_edge_bad_count
    bad_count=$(increment_route_bad_count)
    log_event WARN "self_heal route_unusable xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} bad_count=${bad_count} action=observe"
    if (( bad_count >= 2 )); then
        if mark_route_repair_attempt_if_allowed; then
            log_event WARN "self_heal route_unusable action=repair port=${XRAY_PORT}"
            if repair_codespace_port_route >/dev/null 2>&1; then
                read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
                if xhttp_status_usable "$xcode"; then
                    reset_route_bad_count
                    log_event INFO "self_heal route_repair_ready xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0}"
                else
                    log_event WARN "self_heal route_repair_pending xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} bad_count=${bad_count}"
                fi
            else
                log_event ERROR "self_heal route_repair_failed port=${XRAY_PORT} bad_count=${bad_count}"
            fi
        else
            log_event WARN "self_heal route_unusable action=repair_cooldown port=${XRAY_PORT}"
        fi
    fi
}

_background_tasks() {
    set +e
    local tick=0 health_tick=0 export_tick=0 route_tick=0 version_tick=0
    write_background_supervisor_heartbeat \
        || log_event ERROR "background heartbeat_write_failed phase=startup"
    if [[ -f "$CONFIG_FILE" ]]; then
        force_public_runtime_ports "supervisor_start" >/dev/null 2>&1 || true
        run_with_deadline "$G2RAY_SUPERVISOR_STATS_TIMEOUT_SEC" save_xray_stats >/dev/null 2>&1 || true
        run_with_deadline "$G2RAY_SUPERVISOR_SELF_HEAL_TIMEOUT_SEC" self_heal_once >/dev/null 2>&1 || true
        refresh_degraded_routes_if_requested >/dev/null 2>&1 || true
        if ! latency_focus_enabled || ! route_health_cache_fresh; then
            run_with_deadline "$G2RAY_SUPERVISOR_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health >/dev/null 2>&1 || true
            if route_health_cache_exportable || ! config_export_artifacts_match_current_uuid; then
                run_with_deadline "$G2RAY_SUPERVISOR_EXPORT_TIMEOUT_SEC" refresh_config_exports_if_changed >/dev/null 2>&1 || true
            else
                log_event WARN "config_exports retained_previous reason=startup_route_cache_unavailable"
            fi
        fi
        if ! latency_focus_enabled; then
            run_with_deadline "$G2RAY_SUPERVISOR_HEALTH_TIMEOUT_SEC" health_probe >/dev/null 2>&1 || true
        fi
    fi
    while true; do
        sleep 60
        if ! background_supervisor_token_current; then
            log_event WARN "background supervisor_superseded pid=$$"
            exit 0
        fi
        (( ++version_tick >= G2RAY_SUPERVISOR_VERSION_CHECK_TICKS )) && { version_tick=0; supervisor_reexec_if_stale || true; }
        write_background_supervisor_heartbeat \
            || log_event ERROR "background heartbeat_write_failed phase=loop"
        rotate_log_file "$LOG_FILE"
        rotate_log_file "$LOG_DIR/xray.log"
        rotate_log_file "$LOG_DIR/xray-error.log"
        rotate_log_file "$ROUTE_SETTLE_LOG_FILE"
        if [[ "$PORT_DOMAIN" == unknown-codespace* ]]; then
            local n; n=$(_detect_codespace_name 2>/dev/null || true)
            if [[ -n "$n" && "$n" != "unknown-codespace" ]]; then
                CODESPACE_NAME="$n"
                refresh_port_domains
            fi
        fi
        [[ -f "$CONFIG_FILE" ]] || continue
        run_with_deadline "$G2RAY_SUPERVISOR_STATS_TIMEOUT_SEC" save_xray_stats >/dev/null 2>&1 || true
        run_with_deadline "$G2RAY_SUPERVISOR_SELF_HEAL_TIMEOUT_SEC" self_heal_once >/dev/null 2>&1 || true
        refresh_degraded_routes_if_requested >/dev/null 2>&1 || true
        save_session_uptime >/dev/null 2>&1 || true
        if active_tunnel_recent; then
            (( ++health_tick >= 15 )) && { run_with_deadline "$G2RAY_SUPERVISOR_HEALTH_TIMEOUT_SEC" health_probe >/dev/null 2>&1; health_tick=0; }
            (( ++route_tick >= G2RAY_ACTIVE_ROUTE_REFRESH_TICKS )) && { run_with_deadline "$G2RAY_SUPERVISOR_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health >/dev/null 2>&1 || true; route_tick=0; }
            (( ++export_tick >= G2RAY_ACTIVE_EXPORT_REFRESH_TICKS )) && { run_with_deadline "$G2RAY_SUPERVISOR_EXPORT_TIMEOUT_SEC" refresh_config_exports_if_changed >/dev/null 2>&1 || true; export_tick=0; }
            continue
        fi
        if latency_focus_enabled; then
            (( ++health_tick >= 15 )) && { run_with_deadline "$G2RAY_SUPERVISOR_HEALTH_TIMEOUT_SEC" health_probe >/dev/null 2>&1; health_tick=0; }
            (( ++route_tick >= G2RAY_LATENCY_FOCUS_ROUTE_REFRESH_TICKS )) && { run_with_deadline "$G2RAY_SUPERVISOR_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health >/dev/null 2>&1 || true; route_tick=0; }
            (( ++export_tick >= G2RAY_LATENCY_FOCUS_EXPORT_REFRESH_TICKS )) && { run_with_deadline "$G2RAY_SUPERVISOR_EXPORT_TIMEOUT_SEC" refresh_config_exports_if_changed >/dev/null 2>&1 || true; export_tick=0; }
            continue
        fi
        (( ++health_tick >= 5 )) && { run_with_deadline "$G2RAY_SUPERVISOR_HEALTH_TIMEOUT_SEC" health_probe >/dev/null 2>&1; health_tick=0; }
        if low_overhead_enabled; then
            (( ++route_tick >= 15 )) && { run_with_deadline "$G2RAY_SUPERVISOR_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health >/dev/null 2>&1 || true; route_tick=0; }
            (( ++export_tick >= 15 )) && { run_with_deadline "$G2RAY_SUPERVISOR_EXPORT_TIMEOUT_SEC" refresh_config_exports_if_changed >/dev/null 2>&1 || true; export_tick=0; }
        else
            (( ++route_tick >= 5 )) && { run_with_deadline "$G2RAY_SUPERVISOR_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health >/dev/null 2>&1 || true; route_tick=0; }
            (( ++export_tick >= 5 )) && { run_with_deadline "$G2RAY_SUPERVISOR_EXPORT_TIMEOUT_SEC" refresh_config_exports_if_changed >/dev/null 2>&1 || true; export_tick=0; }
        fi
    done
}

acquire_bg_tasks_lock() {
    local i lock_pid recheck
    for i in {1..20}; do
        if _try_claim_lock_dir "$BG_TASKS_LOCK_DIR"; then
            return 0
        fi
        lock_pid=$(cat "$BG_TASKS_LOCK_DIR/pid" 2>/dev/null || true)
        if [[ -z "$lock_pid" || ! "$lock_pid" =~ ^[0-9]+$ ]]; then
            log_event WARN "background_lock_stale malformed pid=${lock_pid:-missing}"
            rm -f "$BG_TASKS_LOCK_DIR/pid" 2>/dev/null || true
            rmdir "$BG_TASKS_LOCK_DIR" 2>/dev/null || true
            _try_claim_lock_dir "$BG_TASKS_LOCK_DIR" && return 0
            continue
        fi
        if ! kill -0 "$lock_pid" 2>/dev/null; then
            recheck=$(cat "$BG_TASKS_LOCK_DIR/pid" 2>/dev/null || true)
            if [[ "$recheck" == "$lock_pid" ]]; then
                rm -f "$BG_TASKS_LOCK_DIR/pid" 2>/dev/null || true
                rmdir "$BG_TASKS_LOCK_DIR" 2>/dev/null || true
            fi
            continue
        fi
        sleep 0.1
    done
    log_event WARN "background lock_busy"
    return 1
}

release_bg_tasks_lock() {
    rm -f "$BG_TASKS_LOCK_DIR/pid" 2>/dev/null || true
    rmdir "$BG_TASKS_LOCK_DIR" 2>/dev/null || true
}

start_background_tasks() {
    local expected_token="${1:-}" token bg_pid version watchdog_status=0
    if ! acquire_bg_tasks_lock; then
        bg_pid=$(cat "$BG_TASKS_PID" 2>/dev/null || true)
        if { bg_tasks_running "$bg_pid" || background_supervisor_heartbeat_running "$bg_pid"; } && background_watchdog_running; then
            return 0
        fi
        log_event WARN "background supervisor_start_failed reason=lock_busy no_live_supervisor"
        return 1
    fi
    # A queued watchdog restart must not undo an explicit stop or a newer start.
    if [[ -n "$expected_token" && "$(cat "$BG_TASKS_TOKEN_FILE" 2>/dev/null || true)" != "$expected_token" ]]; then
        release_bg_tasks_lock
        return 0
    fi
    if [[ -f "$BG_TASKS_PID" ]]; then
        local p; p=$(cat "$BG_TASKS_PID" 2>/dev/null || true)
        if bg_tasks_running "$p" || background_supervisor_heartbeat_running "$p"; then
            if background_supervisor_version_matches; then
                start_background_watchdog || watchdog_status=$?
                release_bg_tasks_lock
                return "$watchdog_status"
            fi
            log_event WARN "background supervisor_stale pid=${p}"
            if ! stop_background_tasks --lock-held; then
                release_bg_tasks_lock
                return 1
            fi
        elif legacy_bg_tasks_running "$p"; then
            log_event WARN "background legacy_supervisor_stale pid=${p}"
            if ! stop_background_tasks --lock-held; then
                release_bg_tasks_lock
                return 1
            fi
        fi
    fi
    token=$(uuidgen 2>/dev/null || printf '%s-%s-%s' "$$" "$RANDOM" "$(date +%s)")
    version=$(background_supervisor_version)
    if [[ -z "$token" ]] || ! _atomic_write "$BG_TASKS_TOKEN_FILE" "$token"; then
        log_event ERROR "background supervisor_start_failed reason=token_write"
        release_bg_tasks_lock
        return 1
    fi
    if [[ -z "$version" ]] || ! _atomic_write "$BG_TASKS_VERSION_FILE" "$version"; then
        rm -f "$BG_TASKS_TOKEN_FILE" "$BG_TASKS_VERSION_FILE" 2>/dev/null || true
        log_event ERROR "background supervisor_start_failed reason=version_write"
        release_bg_tasks_lock
        return 1
    fi
    G2RAY_SOURCE_ONLY=0 G2RAY_BG_TASK_TOKEN="$token" nohup bash "$SCRIPT_PATH" --background-supervisor </dev/null >/dev/null 2>&1 &
    bg_pid=$!
    if ! _atomic_write "$BG_TASKS_PID" "$bg_pid"; then
        kill "$bg_pid" >/dev/null 2>&1 || true
        wait "$bg_pid" 2>/dev/null || true
        rm -f "$BG_TASKS_PID" "$BG_TASKS_VERSION_FILE" "$BG_TASKS_TOKEN_FILE" "$BG_TASKS_HEARTBEAT_FILE" 2>/dev/null || true
        log_event ERROR "background supervisor_start_failed reason=pid_write"
        release_bg_tasks_lock
        return 1
    fi
    log_event INFO "background supervisor_started pid=${bg_pid}"
    start_background_watchdog || watchdog_status=$?
    release_bg_tasks_lock
    disown 2>/dev/null || true
    return "$watchdog_status"
}

background_watchdog_running() {
    local expected="${1:-}" p
    [[ -n "$expected" ]] || expected=$(cat "$BG_TASKS_TOKEN_FILE" 2>/dev/null || true)
    p=$(cat "$BG_WATCHDOG_PID_FILE" 2>/dev/null || true)
    [[ -n "$expected" && "$p" =~ ^[0-9]+$ && -r "/proc/$p/environ" ]] || return 1
    kill -0 "$p" 2>/dev/null || return 1
    tr '\0' '\n' < "/proc/$p/environ" 2>/dev/null | grep -Fx "G2RAY_BG_WATCHDOG_TOKEN=$expected" >/dev/null
}

start_background_watchdog() {
    local token p i
    token=$(cat "$BG_TASKS_TOKEN_FILE" 2>/dev/null || true)
    [[ -n "$token" ]] || return 1
    background_watchdog_running "$token" && return 0
    # Linux /proc provides ownership proof; never signal a PID based only on a file.
    if [[ ! -r "/proc/${BASHPID:-$$}/environ" ]]; then
        log_event WARN "background watchdog_unavailable reason=process_ownership_unavailable"
        return 1
    fi
    G2RAY_SOURCE_ONLY=0 G2RAY_BG_WATCHDOG_TOKEN="$token" nohup bash "$SCRIPT_PATH" --background-watchdog </dev/null >/dev/null 2>&1 &
    p=$!
    if ! _atomic_write "$BG_WATCHDOG_PID_FILE" "$p"; then
        kill "$p" >/dev/null 2>&1 || true
        wait "$p" 2>/dev/null || true
        log_event ERROR "background watchdog_start_failed reason=pid_write"
        return 1
    fi
    for ((i=0; i<40; i++)); do
        if background_watchdog_running "$token"; then
            log_event INFO "background watchdog_started pid=${p}"
            return 0
        fi
        kill -0 "$p" 2>/dev/null || break
        sleep 0.05
    done
    kill "$p" >/dev/null 2>&1 || true
    wait "$p" 2>/dev/null || true
    rm -f "$BG_WATCHDOG_PID_FILE" 2>/dev/null || true
    log_event ERROR "background watchdog_start_failed reason=ownership_not_confirmed"
    return 1
}

_background_watchdog() {
    set +e
    local token="${G2RAY_BG_WATCHDOG_TOKEN:-}" p
    [[ -n "$token" ]] || return 1
    while [[ "$(cat "$BG_TASKS_TOKEN_FILE" 2>/dev/null || true)" == "$token" ]]; do
        sleep 30
        [[ "$(cat "$BG_TASKS_TOKEN_FILE" 2>/dev/null || true)" == "$token" ]] || return 0
        p=$(cat "$BG_TASKS_PID" 2>/dev/null || true)
        # Slow bounded work can delay heartbeats. Only a dead/unowned process
        # justifies replacement here; this watcher never restarts Xray itself.
        if bg_tasks_running "$p" || background_supervisor_heartbeat_running "$p"; then
            continue
        fi
        log_event WARN "background supervisor_missing pid=${p:-none} action=restart"
        start_background_tasks "$token" \
            || log_event ERROR "background supervisor_restart_failed"
    done
}

background_supervisor_version() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$SCRIPT_PATH" 2>/dev/null | awk '{print $1}'
    elif command -v md5sum >/dev/null 2>&1; then
        md5sum "$SCRIPT_PATH" 2>/dev/null | awk '{print $1}'
    elif command -v git >/dev/null 2>&1; then
        git -C "$BASE_DIR" rev-parse HEAD 2>/dev/null || true
    fi
}

background_supervisor_version_matches() {
    local expected current
    expected=$(cat "$BG_TASKS_VERSION_FILE" 2>/dev/null || true)
    current=$(background_supervisor_version)
    [[ -n "$expected" && -n "$current" && "$expected" == "$current" ]]
}

supervisor_reexec_if_stale() {
    local version
    background_supervisor_version_matches && return 0
    if ! bash -n "$SCRIPT_PATH" >/dev/null 2>&1; then
        log_event ERROR "background supervisor_stale_code syntax_failed action=stay"
        return 1
    fi
    version=$(background_supervisor_version)
    if [[ -z "$version" ]] || ! _atomic_write "$BG_TASKS_VERSION_FILE" "$version"; then
        log_event ERROR "background supervisor_stale_code version_write_failed action=stay"
        return 1
    fi
    log_event WARN "background supervisor_stale_code action=reexec"
    exec bash "$SCRIPT_PATH" --background-supervisor
}

stop_background_tasks() {
    local p watchdog_pid="" lock_held=false status=0
    [[ "${1:-}" == "--lock-held" ]] && lock_held=true
    if [[ "$lock_held" != true ]] && ! acquire_bg_tasks_lock; then
        return 1
    fi
    if background_watchdog_running; then
        watchdog_pid=$(cat "$BG_WATCHDOG_PID_FILE" 2>/dev/null || true)
        kill "$watchdog_pid" >/dev/null 2>&1 || true
    fi
    p=$(cat "$BG_TASKS_PID" 2>/dev/null || true)
    if bg_tasks_running "$p" || background_supervisor_heartbeat_running "$p" || legacy_bg_tasks_running "$p"; then
        kill "$p" >/dev/null 2>&1 || true
        sleep 1
        (bg_tasks_running "$p" || background_supervisor_heartbeat_running "$p" || legacy_bg_tasks_running "$p") && kill -9 "$p" >/dev/null 2>&1 || true
    fi
    if ! rm -f "$BG_TASKS_PID" "$BG_TASKS_VERSION_FILE" "$BG_TASKS_TOKEN_FILE" "$BG_WATCHDOG_PID_FILE" 2>/dev/null; then
        log_event ERROR "background supervisor_stop_failed reason=state_cleanup"
        status=1
    fi
    [[ "$lock_held" == true ]] || release_bg_tasks_lock
    return "$status"
}

legacy_bg_tasks_running() {
    local p="${1:-}" args tty
    [[ -f "$BG_TASKS_TOKEN_FILE" ]] && return 1
    [[ "$p" =~ ^[0-9]+$ ]] || return 1
    kill -0 "$p" 2>/dev/null || return 1
    args=$(ps -p "$p" -o args= 2>/dev/null || true)
    tty=$(ps -p "$p" -o tty= 2>/dev/null | awk '{print $1}' || true)
    [[ "$tty" == "?" && "$args" == *"$SCRIPT_PATH"* ]]
}

bg_tasks_running() {
    local p="${1:-}" expected
    [[ "$p" =~ ^[0-9]+$ ]] || return 1
    kill -0 "$p" 2>/dev/null || return 1
    expected=$(cat "$BG_TASKS_TOKEN_FILE" 2>/dev/null || true)
    if [[ -n "$expected" && -r "/proc/$p/environ" ]]; then
        tr '\0' '\n' < "/proc/$p/environ" 2>/dev/null | grep -Fx "G2RAY_BG_TASK_TOKEN=$expected" >/dev/null
        return
    fi
    return 1
}

background_supervisor_token_current() {
    local expected
    expected=$(cat "$BG_TASKS_TOKEN_FILE" 2>/dev/null || true)
    [[ -n "${G2RAY_BG_TASK_TOKEN:-}" && -n "$expected" && "$G2RAY_BG_TASK_TOKEN" == "$expected" ]]
}

write_background_supervisor_heartbeat() {
    local now
    now=$(date +%s)
    [[ "$now" =~ ^[0-9]+$ ]] || return 1
    _atomic_write "$BG_TASKS_HEARTBEAT_FILE" "${BASHPID:-$$} ${G2RAY_BG_TASK_TOKEN:-} ${now}"
}

background_supervisor_heartbeat_timestamp() {
    local raw hb_pid hb_token hb_ts
    raw=$(cat "$BG_TASKS_HEARTBEAT_FILE" 2>/dev/null || true)
    read -r hb_pid hb_token hb_ts _ <<< "$raw"
    if [[ "$hb_ts" =~ ^[0-9]+$ ]]; then
        printf '%s' "$hb_ts"
    elif [[ "$hb_pid" =~ ^[0-9]+$ ]]; then
        printf '%s' "$hb_pid"
    else
        return 1
    fi
}

background_supervisor_recent_heartbeat() {
    local hb now max_age="${1:-180}"
    hb=$(background_supervisor_heartbeat_timestamp 2>/dev/null || true)
    [[ "$hb" =~ ^[0-9]+$ ]] || return 1
    now=$(date +%s)
    (( now >= hb && now - hb <= max_age ))
}

background_supervisor_heartbeat_matches() {
    local p="${1:-}" raw hb_pid hb_token hb_ts expected
    [[ "$p" =~ ^[0-9]+$ ]] || return 1
    kill -0 "$p" 2>/dev/null || return 1
    expected=$(cat "$BG_TASKS_TOKEN_FILE" 2>/dev/null || true)
    [[ -n "$expected" ]] || return 1
    # A fresh file cannot overrule visible evidence that this PID was reused.
    if [[ -r "/proc/$p/environ" ]] && ! bg_tasks_running "$p"; then
        return 1
    fi
    raw=$(cat "$BG_TASKS_HEARTBEAT_FILE" 2>/dev/null || true)
    read -r hb_pid hb_token hb_ts _ <<< "$raw"
    [[ "$hb_pid" == "$p" && "$hb_token" == "$expected" && "$hb_ts" =~ ^[0-9]+$ ]] || return 1
    background_supervisor_recent_heartbeat
}

background_supervisor_heartbeat_running() {
    local p="${1:-}"
    [[ "$p" =~ ^[0-9]+$ ]] || return 1
    background_supervisor_heartbeat_matches "$p" || return 1
}

background_supervisor_status() {
    local p running="false" token_state="missing" version_state="missing" heartbeat_age="unknown" watchdog="missing" hb now expected current
    p=$(cat "$BG_TASKS_PID" 2>/dev/null || true)
    if bg_tasks_running "$p"; then
        running="true"
    elif legacy_bg_tasks_running "$p"; then
        running="legacy"
    elif background_supervisor_heartbeat_running "$p"; then
        running="heartbeat"
    fi
    [[ -s "$BG_TASKS_TOKEN_FILE" ]] && token_state="present"
    expected=$(cat "$BG_TASKS_VERSION_FILE" 2>/dev/null || true)
    current=$(background_supervisor_version)
    if [[ -n "$expected" && -n "$current" ]]; then
        [[ "$expected" == "$current" ]] && version_state="ok" || version_state="stale"
    fi
    hb=$(background_supervisor_heartbeat_timestamp 2>/dev/null || true)
    if [[ "$hb" =~ ^[0-9]+$ ]]; then
        now=$(date +%s)
        heartbeat_age="$((now - hb))s"
    fi
    background_watchdog_running && watchdog="running"
    printf 'pid=%s running=%s version=%s token=%s heartbeat_age=%s watchdog=%s\n' "${p:-none}" "$running" "$version_state" "$token_state" "$heartbeat_age" "$watchdog"
}

format_duration_compact() {
    local seconds="${1:-0}" days hours minutes
    [[ "$seconds" =~ ^[0-9]+$ ]] || seconds=0
    days=$(( seconds / 86400 ))
    hours=$(( (seconds % 86400) / 3600 ))
    minutes=$(( (seconds % 3600) / 60 ))
    if (( days > 0 )); then
        printf '%dd %dh %dm' "$days" "$hours" "$minutes"
    elif (( hours > 0 )); then
        printf '%dh %dm' "$hours" "$minutes"
    else
        printf '%dm %ds' "$minutes" "$((seconds % 60))"
    fi
}

resume_gap_threshold_sec() {
    local threshold="${G2RAY_RESUME_GAP_WARN_SEC:-300}"
    [[ "$threshold" =~ ^[0-9]+$ && "$threshold" -gt 0 ]] || threshold=300
    printf '%s' "$threshold"
}

record_resume_gap() {
    local reason="${1:-startup}" hb now gap threshold detected
    hb=$(background_supervisor_heartbeat_timestamp 2>/dev/null || true)
    [[ "$hb" =~ ^[0-9]+$ ]] || return 0
    now=$(date +%s 2>/dev/null || echo 0)
    [[ "$now" =~ ^[0-9]+$ && "$now" -ge "$hb" ]] || return 0
    gap=$((now - hb))
    threshold=$(resume_gap_threshold_sec)
    (( gap < threshold )) && return 0
    detected=$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')
    printf 'reason=%s gap_sec=%s gap=%s previous_heartbeat=%s detected_at=%s\n' \
        "$reason" "$gap" "$(format_duration_compact "$gap")" "$hb" "$detected" > "$RESUME_GAP_FILE" 2>/dev/null || true
    chmod 600 "$RESUME_GAP_FILE" 2>/dev/null || true
    log_event WARN "resume_gap reason=${reason} gap_sec=${gap} previous_heartbeat=${hb} likely=stopped_or_suspended"
}

resume_gap_summary() {
    local line
    if [[ ! -s "$RESUME_GAP_FILE" ]]; then
        printf 'Last gap : none recorded\n'
        printf 'Meaning  : no long supervisor heartbeat gap has been observed yet\n'
        return 0
    fi
    line=$(tail -n 1 "$RESUME_GAP_FILE" 2>/dev/null || true)
    printf 'Last gap : %s\n' "${line:-none recorded}"
    printf 'Meaning  : a gap means the supervisor could not heartbeat, usually because the Codespace was stopped, suspended, rebuilding, or quota-blocked\n'
}

format_bytes() {
    awk -v b="${1:-0}" 'BEGIN{
        if      (b < 1048576)    printf "%.2f KB", b / 1024
        else if (b < 1073741824) printf "%.2f MB", b / 1048576
        else                     printf "%.2f GB", b / 1073741824
    }'
}

estimate_quota() {
    local prev ss now elapsed total rem h_used m_used h_left m_left dtime quota_seconds quota_hours
    reset_monthly_quota_if_needed \
        || log_event WARN "quota_state cycle_reset_failed action=continue_with_sanitized_state"
    quota_seconds="${G2RAY_QUOTA_SECONDS:-216000}"
    [[ "$quota_seconds" =~ ^[0-9]+$ && "$quota_seconds" -gt 0 ]] || quota_seconds=216000
    quota_hours=$(( quota_seconds / 3600 ))
    now=$(date +%s)
    [[ "$now" =~ ^[0-9]+$ ]] || now=0
    prev=$(cat "$TOTAL_UPTIME_FILE" 2>/dev/null || true)
    if [[ ! "$prev" =~ ^[0-9]+$ ]]; then
        log_event WARN "quota_state invalid_total action=repair"
        prev=0
        _atomic_write "$TOTAL_UPTIME_FILE" "$prev" >/dev/null 2>&1 || true
    fi
    ss=$(cat "$SESSION_START_FILE" 2>/dev/null || true)
    if [[ ! "$ss" =~ ^[0-9]+$ ]]; then
        log_event WARN "quota_state invalid_session_start action=repair"
        ss="$now"
        _atomic_write "$SESSION_START_FILE" "$ss" >/dev/null 2>&1 || true
    fi
    elapsed=$(( now - ss ))
    (( elapsed < 0    )) && elapsed=0
    (( elapsed > 3600 )) && elapsed=3600
    total=$(( prev + elapsed ))
    rem=$(( quota_seconds - total )); (( rem < 0 )) && rem=0
    h_used=$(( total / 3600 ));   m_used=$(( (total % 3600) / 60 ))
    h_left=$(( rem   / 3600 ));   m_left=$(( (rem   % 3600) / 60 ))
    dtime=$(date -d "+${rem} seconds" '+%Y-%m-%d %H:%M' 2>/dev/null || echo "N/A")
    echo -e "  ${GREEN}● Local 2-core quota estimate${NC}"
    echo -e "  Total Used : ${WHITE}${h_used}h ${m_used}m${NC}"
    echo -e "  Remaining  : ${GREEN}${h_left}h ${m_left}m${NC} ${DIM}(of local ${quota_hours}h estimate)${NC}"
    echo -e "  Depletion  : ${DIM}${dtime}${NC}"
    echo -e "  ${DIM}GitHub billing is authoritative; 15 GB-month is storage quota, not traffic quota.${NC}"
    echo -e "  ${DIM}If quota blocks starts, mark the Codespace as Keep codespace so the same configs can survive until reset.${NC}"
}

show_resource_stats() {
    refresh_screen
    echo -e "\n  ${GREEN}● Live Resource Stats${NC}"
    local xpid cpu mem_kb mem_mb
    xpid=""
    if [[ -f "$XRAY_PID_FILE" ]]; then
        xpid=$(cat "$XRAY_PID_FILE" 2>/dev/null || true)
        xray_pid_matches "$xpid" || xpid=""
    fi
    if [[ -z "$xpid" ]]; then
        xpid=$(owned_xray_pids | head -1 || true)
    fi
    if [[ -n "$xpid" ]]; then
        read -r cpu mem_kb <<< "$(ps -p "$xpid" -o %cpu,rss --no-headers 2>/dev/null || echo '0 0')"
        mem_mb=$(awk "BEGIN{printf \"%.1f\",${mem_kb:-0}/1024}")
        echo -e "  Engine : ${GREEN}Active${NC} (PID $xpid)"
        echo -e "  CPU    : ${WHITE}${cpu}%${NC}"
        echo -e "  Memory : ${WHITE}${mem_mb} MB${NC}"
    else
        echo -e "  Engine : ${RED}Offline${NC}"
    fi
    echo ""
    echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
}

check_port_visibility() {
    if ! xray_listener_ready; then
        log_event WARN "check_port_visibility engine_not_running port=${XRAY_PORT}"
        refresh_screen
        echo -e "  ${RED}✖ Engine is not ready!${NC}"
        echo -e "  ${DIM}Start or reconnect the engine first (Option 3 or 6).${NC}\n"
        echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
        return 1
    fi
    if ! ensure_codespace_port_public; then
        log_event WARN "check_port_visibility port_public_failed port=${XRAY_PORT}"
        refresh_screen
        echo -e "  ${YELLOW}⚠ Could not set Codespaces port ${XRAY_PORT} public.${NC}"
        echo -e "  ${DIM}Open the PORTS tab and set port ${XRAY_PORT} visibility to Public.${NC}\n"
        echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
        return 1
    fi
    ensure_optional_ws_port_public >/dev/null 2>&1 \
        || log_event WARN "check_port_visibility ws_port_public_failed port=${WS_PORT}"
}

performance_profile_settings() {
    local profile="${1:-$PERFORMANCE_PROFILE}"
    # Xray's default handshake allowance is four seconds. Going below it does
    # not accelerate successful requests and rejects lossy mobile handshakes.
    case "$profile" in
        low_latency|latency)
            printf 'name=low_latency\nhandshake=4\nconnIdle=900\nuplinkOnly=2\ndownlinkOnly=5\nbufferSize=512\nsniffQuic=false\nloglevel=warning\n'
            ;;
        streaming|video)
            printf 'name=streaming\nhandshake=4\nconnIdle=900\nuplinkOnly=2\ndownlinkOnly=8\nbufferSize=768\nsniffQuic=false\nloglevel=warning\n'
            ;;
        unstable_mobile|mobile)
            printf 'name=unstable_mobile\nhandshake=6\nconnIdle=900\nuplinkOnly=4\ndownlinkOnly=10\nbufferSize=512\nsniffQuic=false\nloglevel=warning\n'
            ;;
        low_overhead|minimal)
            printf 'name=low_overhead\nhandshake=4\nconnIdle=600\nuplinkOnly=2\ndownlinkOnly=5\nbufferSize=256\nsniffQuic=false\nloglevel=error\n'
            ;;
        max_throughput|throughput|max|high_throughput)
            printf 'name=max_throughput\nhandshake=4\nconnIdle=900\nuplinkOnly=2\ndownlinkOnly=8\nbufferSize=1024\nsniffQuic=false\nloglevel=warning\n'
            ;;
        *)
            printf 'name=balanced\nhandshake=4\nconnIdle=900\nuplinkOnly=2\ndownlinkOnly=5\nbufferSize=512\nsniffQuic=false\nloglevel=warning\n'
            ;;
    esac
}

valid_performance_profile() {
    case "${1:-}" in
        balanced|low_latency|latency|streaming|video|unstable_mobile|mobile|low_overhead|minimal|max_throughput|throughput|max|high_throughput)
            return 0 ;;
        *) return 1 ;;
    esac
}

set_performance_profile() {
    local profile="${1:-}"
    valid_performance_profile "$profile" || return 1
    _atomic_write "$PERFORMANCE_PROFILE_FILE" "$profile"
    chmod 600 "$PERFORMANCE_PROFILE_FILE" 2>/dev/null || true
    PERFORMANCE_PROFILE="$profile"
    log_event INFO "performance_profile preference set profile=${profile}"
}

_generate_config_impl() {
    # Keep the existing UUID when only re-applying a profile, so switching
    # profiles does not invalidate already-imported client links.
    local uuid old_uuid="" candidate_config="" previous_config="" had_previous_config=false had_running_engine=false
    if declare -F xray_running >/dev/null 2>&1 && xray_running; then
        had_running_engine=true
    fi
    old_uuid=$(active_vless_uuid 2>/dev/null || true)
    if [[ "${G2RAY_PRESERVE_UUID:-0}" == "1" && -n "$old_uuid" ]]; then
        uuid="$old_uuid"
    else
        uuid=$(generate_uuid) || {
            echo -e "  ${RED}Could not generate a UUID for the config.${NC}"
            return 1
        }
    fi
    [[ -n "$uuid" ]] || {
        echo -e "  ${RED}Could not generate a UUID for the config.${NC}"
        return 1
    }
    if [[ -s "$CONFIG_FILE" ]]; then
        previous_config=$(mktemp "${CONFIG_FILE}.previous.XXXXXX.json") || return 1
        cp "$CONFIG_FILE" "$previous_config" || {
            rm -f "$previous_config" 2>/dev/null || true
            return 1
        }
        had_previous_config=true
    fi
    local profile_name handshake conn_idle uplink_only downlink_only buffer_size sniff_quic loglevel sniff_dest key value xhttp_export_mode xhttp_accept_mode
    while IFS='=' read -r key value; do
        case "$key" in
            name) profile_name="$value" ;;
            handshake) handshake="$value" ;;
            connIdle) conn_idle="$value" ;;
            uplinkOnly) uplink_only="$value" ;;
            downlinkOnly) downlink_only="$value" ;;
            bufferSize) buffer_size="$value" ;;
            sniffQuic) sniff_quic="$value" ;;
            loglevel) loglevel="$value" ;;
        esac
    done < <(performance_profile_settings "$(effective_performance_profile)")
    profile_name="${profile_name:-balanced}"
    handshake="${handshake:-4}"
    conn_idle="${conn_idle:-600}"
    uplink_only="${uplink_only:-2}"
    downlink_only="${downlink_only:-5}"
    buffer_size="${buffer_size:-512}"
    sniff_quic="${sniff_quic:-false}"
    loglevel="${loglevel:-warning}"
    sniff_dest='["http", "tls"]'
    [[ "$sniff_quic" == "true" ]] && sniff_dest='["http", "tls", "quic"]'
    xhttp_export_mode=$(xhttp_mode_value)
    # The pinned Xray server accepts packet-up, stream-up, and stream-one when
    # its inbound mode is auto. Client links still use the explicit panel
    # preference, preserving a conservative packet-up default.
    xhttp_accept_mode="auto"
    # Keep origin connections warm, with Fast Open only when explicitly enabled.
    local direct_sockopt tfo_state="off" sockopt_inner
    sockopt_inner="\"tcpKeepAliveInterval\": ${TCP_KEEPALIVE_INTERVAL_SEC}, \"tcpKeepAliveIdle\": ${TCP_KEEPALIVE_IDLE_SEC}"
    if tcp_fast_open_outbound_enabled; then
        sockopt_inner="\"tcpFastOpen\": true, ${sockopt_inner}"
        tfo_state="on"
    else
        sockopt_inner="\"tcpFastOpen\": false, ${sockopt_inner}"
    fi
    direct_sockopt=", \"streamSettings\": { \"sockopt\": { ${sockopt_inner} } }"
    local ws_inbound_json="" ws_path
    if ws_fallback_enabled; then
        ws_path=$(ws_path_value)
        ws_inbound_json=$(cat << JSONEOF
,
    {
      "tag": "vless-ws", "port": ${WS_PORT}, "listen": "0.0.0.0", "protocol": "vless",
      "settings": {
        "clients": [ { "id": "${uuid}", "flow": "", "level": 0, "email": "user@G2rayXCodeLeafy-ws" } ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "ws", "security": "none",
        "wsSettings": { "path": "$(json_escape "$ws_path")", "heartbeatPeriod": ${WS_HEARTBEAT_PERIOD_SEC} },
        "sockopt": { "tcpKeepAliveInterval": ${TCP_KEEPALIVE_INTERVAL_SEC}, "tcpKeepAliveIdle": ${TCP_KEEPALIVE_IDLE_SEC} }
      },
      "sniffing": { "enabled": true, "destOverride": ${sniff_dest}, "routeOnly": true }
    }
JSONEOF
)
    fi
    local uuid_hash; uuid_hash=$(fingerprint_secret "$uuid")
    log_event INFO "generate_config uuid_hash=${uuid_hash} port=${XRAY_PORT} domain=${PORT_DOMAIN} profile=${profile_name} xhttp_accept_mode=${xhttp_accept_mode} xhttp_export_mode=${xhttp_export_mode} tcp_fast_open=${tfo_state}"
    candidate_config=$(mktemp "${CONFIG_FILE}.candidate.XXXXXX.json") || {
        rm -f "$previous_config" 2>/dev/null || true
        return 1
    }
    cat > "$candidate_config" << JSONEOF
{
  "log": { "loglevel": "${loglevel}", "access": "none", "error": "${LOG_DIR}/xray-error.log" },
  "stats": {},
  "api": { "tag": "api", "services": ["StatsService"] },
  "policy": {
    "system": { "statsInboundDownlink": true, "statsInboundUplink": true },
    "levels": { "0": { "statsUserUplink": false, "statsUserDownlink": false, "handshake": ${handshake}, "connIdle": ${conn_idle}, "uplinkOnly": ${uplink_only}, "downlinkOnly": ${downlink_only}, "bufferSize": ${buffer_size} } }
  },
  "dns": {
    "servers": ["localhost", "168.63.129.16", "1.1.1.1", "1.0.0.1", "8.8.8.8"],
    "queryStrategy": "UseIPv4",
    "disableCache": false,
    "disableFallback": false,
    "disableFallbackIfMatch": false,
    "enableParallelQuery": true,
    "serveStale": true,
    "serveExpiredTTL": 600
  },
  "inbounds": [
    {
      "tag": "vless-in", "port": ${XRAY_PORT}, "listen": "0.0.0.0", "protocol": "vless",
      "settings": {
        "clients": [ { "id": "${uuid}", "flow": "", "level": 0, "email": "user@G2rayXCodeLeafy" } ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "xhttp", "security": "none",
        "xhttpSettings": { "mode": "${xhttp_accept_mode}", "path": "/", "scMaxBufferedPosts": ${XHTTP_SERVER_MAX_BUFFERED_POSTS} },
        "sockopt": { "tcpKeepAliveInterval": ${TCP_KEEPALIVE_INTERVAL_SEC}, "tcpKeepAliveIdle": ${TCP_KEEPALIVE_IDLE_SEC} }
      },
      "sniffing": { "enabled": true, "destOverride": ${sniff_dest}, "routeOnly": true }
    }${ws_inbound_json},
    { "listen": "127.0.0.1", "port": 10085, "protocol": "dokodemo-door", "settings": { "address": "127.0.0.1" }, "tag": "api" }
  ],
  "outbounds": [
    { "tag": "direct", "protocol": "freedom",   "settings": { "domainStrategy": "UseIPv4" }${direct_sockopt} },
    { "tag": "block",  "protocol": "blackhole",  "settings": { "response": { "type": "none" } } }
  ],
  "routing": {
    "domainStrategy": "AsIs",
    "rules": [
      { "inboundTag": ["api"],                                   "outboundTag": "api",    "type": "field" },
      { "type": "field", "network": "udp", "port": "443",        "outboundTag": "block"  },
      { "type": "field", "ip":       ["geoip:private"],          "outboundTag": "block"  },
      { "type": "field", "protocol": ["bittorrent"],             "outboundTag": "block"  }
    ]
  }
}
JSONEOF
    chmod 600 "$candidate_config" 2>/dev/null || true
    if ! xray_validate_config_file "$candidate_config"; then
        log_event ERROR "generate_config candidate_invalid action=keep_existing"
        rm -f "$candidate_config" "$previous_config" 2>/dev/null || true
        return 1
    fi
    if ! _atomic_write "$UUID_FILE" "$uuid"; then
        rm -f "$candidate_config" "$previous_config" 2>/dev/null || true
        return 1
    fi
    if ! mv -f "$candidate_config" "$CONFIG_FILE"; then
        if [[ "$had_previous_config" == true ]]; then
            restore_uuid_cache_value "$old_uuid" "candidate_config_commit_failed" >/dev/null 2>&1 || true
        else
            restore_uuid_cache_value "" "candidate_config_commit_failed_no_previous_config" >/dev/null 2>&1 || true
        fi
        rm -f "$candidate_config" "$previous_config" 2>/dev/null || true
        return 1
    fi
    chmod 600 "$UUID_FILE" "$CONFIG_FILE" 2>/dev/null || true
    local engine_started=false start_phase="unknown"
    START_XRAY_LAST_PHASE="unknown"
    if start_xray && wait_for_port >/dev/null 2>&1; then
        engine_started=true
        log_event INFO "generate_config engine_started port=${XRAY_PORT}"
        echo -e "  ${GREEN}✔ Engine started on port ${XRAY_PORT}.${NC}"
    else
        log_event WARN "generate_config engine_maybe_not_bound port=${XRAY_PORT}"
        echo -e "  ${YELLOW}⚠ Engine may not have bound to port ${XRAY_PORT}.${NC}"
    fi
    start_phase="${START_XRAY_LAST_PHASE:-unknown}"
    if [[ "$engine_started" != true ]]; then
        local rollback_config="failed" rollback_identity_ok=true rollback_engine="not_available"
        local must_stop_new_engine=true old_engine_preserved=false rollback_uuid=""
        log_event ERROR "generate_config engine_start_failed port=${XRAY_PORT} action=rollback"

        case "$start_phase" in
            preflight|previous_stop_failed)
                # The replacement never launched and the previous process was
                # never stopped, so restoring its files must not kill it when
                # it was confirmed alive at transaction start.
                must_stop_new_engine=false
                [[ "$had_running_engine" == true ]] && old_engine_preserved=true
                ;;
            previous_stopped|launch_failed|launch_cleaned)
                # The previous process is gone and no replacement remains.
                must_stop_new_engine=false
                ;;
            running|process_launched|unknown|*)
                must_stop_new_engine=true
                ;;
        esac

        # A successful launch followed by a failed readiness probe can leave the
        # new UUID running in memory. Stop only when the start attempt reached a
        # launch phase; otherwise the old engine is the process still serving.
        if [[ "$must_stop_new_engine" == true ]] && ! stop_xray >/dev/null 2>&1; then
            log_event ERROR "generate_config rollback_stop_failed action=keep_new_files"
            rm -f "$previous_config" 2>/dev/null || true
            echo -e "  ${RED}Could not stop the failed new engine; its matching config was kept for safe recovery.${NC}"
            return 1
        fi

        if [[ "$had_previous_config" == true && -s "$previous_config" ]]; then
            if mv -f "$previous_config" "$CONFIG_FILE" 2>/dev/null; then
                chmod 600 "$CONFIG_FILE" 2>/dev/null || true
                rollback_config="restored"
            else
                log_event ERROR "generate_config rollback_config_restore_failed"
            fi
        else
            if rm -f "$CONFIG_FILE" 2>/dev/null; then
                rollback_config="removed"
            else
                log_event ERROR "generate_config rollback_config_remove_failed"
            fi
        fi
        if [[ "$rollback_config" != "failed" ]]; then
            [[ "$rollback_config" == "restored" ]] && rollback_uuid="$old_uuid"
            restore_uuid_cache_value "$rollback_uuid" "engine_start_rollback" >/dev/null 2>&1 \
                || rollback_identity_ok=false
        else
            # Config restoration failed, so keep the new UUID cache paired with
            # the still-committed new config and retain the backup for recovery.
            log_event ERROR "generate_config rollback_incomplete action=keep_new_identity backup=${previous_config:-missing}"
        fi

        if [[ "$rollback_config" == "restored" && "$old_engine_preserved" == true ]]; then
            rollback_engine="restored"
            log_event INFO "generate_config rollback_engine_preserved port=${XRAY_PORT} start_phase=${start_phase}"
        elif [[ "$rollback_config" == "restored" ]]; then
            if start_xray >/dev/null 2>&1 && wait_for_port >/dev/null 2>&1; then
                rollback_engine="restored"
                log_event INFO "generate_config rollback_engine_restored port=${XRAY_PORT}"
            else
                rollback_engine="restart_failed"
                log_event ERROR "generate_config rollback_engine_restart_failed port=${XRAY_PORT}"
            fi
        elif [[ "$rollback_config" == "failed" ]]; then
            rollback_engine="files_failed"
        fi
        [[ "$rollback_config" == "failed" ]] || rm -f "$previous_config" 2>/dev/null || true

        if [[ "$rollback_engine" == "restored" && "$rollback_identity_ok" == true ]]; then
            echo -e "  ${YELLOW}New config failed readiness; the previous config and engine were restored.${NC}"
        elif [[ "$rollback_engine" == "restored" ]]; then
            echo -e "  ${YELLOW}Previous engine was restored; its UUID cache will be rebuilt from config.json.${NC}"
        elif [[ "$rollback_engine" == "not_available" ]]; then
            echo -e "  ${YELLOW}New config failed readiness and was removed; no previous config existed.${NC}"
        else
            echo -e "  ${RED}New config failed and automatic rollback needs attention. Check engine logs.${NC}"
        fi
        return 1
    fi
    rm -f "$previous_config" 2>/dev/null || true
    if ensure_codespace_port_public; then
        log_event INFO "generate_config port_public port=${XRAY_PORT}"
    else
        log_event WARN "generate_config port_public_failed port=${XRAY_PORT}"
        echo -e "  ${YELLOW}⚠ Could not set Codespaces port ${XRAY_PORT} public. Check the PORTS tab.${NC}"
    fi
    if ws_fallback_enabled; then
        if ensure_optional_ws_port_public; then
            log_event INFO "generate_config ws_port_public port=${WS_PORT}"
        else
            log_event WARN "generate_config ws_port_public_failed port=${WS_PORT}"
            echo -e "  ${YELLOW}⚠ Could not set optional WebSocket port ${WS_PORT} public. Check the PORTS tab.${NC}"
        fi
    fi
    local _boot_code _boot_ms _boot_reason
    read -r _boot_code _boot_ms _boot_reason < <(xhttp_probe_metrics external)
    ensure_route_cache_for_export "${_boot_code:-0}" "generate_config" >/dev/null 2>&1 || true
    refresh_config_exports >/dev/null 2>&1 || true
    read -r _boot_code _boot_ms _boot_reason < <(xhttp_probe_metrics external)
    if xhttp_status_usable "${_boot_code:-0}"; then
        write_boot_status "ready" "generate_config" "Config generated and the Codespaces route is usable." "${_boot_code:-0}" "${_boot_ms:-0}" \
            || log_event WARN "boot_status nonfatal_write_failed reason=generate_config"
    elif [[ "${_boot_code:-0}" == "404" ]]; then
        write_boot_status "route_settling" "generate_config" "Config generated, but GitHub's app route is still settling. Wait, check health, or run Recover Now." "${_boot_code:-0}" "${_boot_ms:-0}" \
            || log_event WARN "boot_status nonfatal_write_failed reason=generate_config"
    else
        write_boot_status "needs_attention" "generate_config" "Config generated, but the external route is not usable yet." "${_boot_code:-0}" "${_boot_ms:-0}" \
            || log_event WARN "boot_status nonfatal_write_failed reason=generate_config"
    fi
}

generate_config() {
    with_runtime_lock _generate_config_impl "$@"
}

generate_config_with_feedback() {
    local rc
    if generate_config; then
        return 0
    else
        rc=$?
    fi
    log_event ERROR "generate_config interactive_failed rc=${rc}"
    echo -e "  ${RED}Config generation failed. Previous config was kept if one existed.${NC}"
    echo -e "  ${DIM}Use option 13) View Engine Logs or 14) Diagnostics for details.${NC}"
    return "$rc"
}

generate_config_preserving_uuid_with_feedback() {
    local prev_preserve_set="${G2RAY_PRESERVE_UUID+x}" prev_preserve="${G2RAY_PRESERVE_UUID:-}" status
    G2RAY_PRESERVE_UUID=1
    if generate_config_with_feedback; then
        status=0
    else
        status=$?
    fi
    if [[ "$prev_preserve_set" == "x" ]]; then
        G2RAY_PRESERVE_UUID="$prev_preserve"
    else
        unset G2RAY_PRESERVE_UUID
    fi
    return "$status"
}

url_encode_query_value() {
    local value="$1" encoded="" i char hex
    local LC_ALL=C
    for ((i = 0; i < ${#value}; i++)); do
        char="${value:i:1}"
        case "$char" in
            [a-zA-Z0-9.~_-]) encoded+="$char" ;;
            *) printf -v hex '%%%02X' "'$char"; encoded+="$hex" ;;
        esac
    done
    printf '%s' "$encoded"
}

xhttp_extra_json_valid() {
    local value="${1:-}" parser=""
    [[ -n "$value" ]] || return 1
    if command -v jq >/dev/null 2>&1; then
        printf '%s' "$value" | jq -se 'length == 1 and (.[0] | type == "object")
          and all(.. | numbers; (isnan or isinfinite) | not)' >/dev/null 2>&1
        return $?
    fi
    if command -v python3 >/dev/null 2>&1; then
        parser=python3
    elif command -v python >/dev/null 2>&1; then
        parser=python
    fi
    [[ -n "$parser" ]] || return 1
    printf '%s' "$value" | "$parser" -c 'import json,math,sys
def finite(value):
    if isinstance(value, float):
        return math.isfinite(value)
    if isinstance(value, dict):
        return all(finite(item) for item in value.values())
    if isinstance(value, list):
        return all(finite(item) for item in value)
    return True
data=json.load(sys.stdin)
sys.exit(0 if isinstance(data, dict) and finite(data) else 1)' >/dev/null 2>&1
}

generate_link_for_address() {
    local address="$1" label_suffix="${2:-}" uuid="${3:-}" path encoded_path xhttp_mode extra_param=""
    [[ -n "$uuid" ]] || uuid=$(active_vless_uuid 2>/dev/null) || { printf ''; return 1; }
    [[ -z "$uuid" ]] && { printf ''; return 1; }
    path=$(xhttp_config_path)
    [[ "$path" == /* ]] || path="/${path}"
    encoded_path=$(url_encode_query_value "$path")
    xhttp_mode=$(xhttp_config_mode)
    local extra_json=""
    extra_json=$(xhttp_link_extra_json)
    [[ -n "$extra_json" ]] && extra_param="&extra=$(url_encode_query_value "$extra_json")"
    printf 'vless://%s@%s:%s?encryption=none&security=tls&sni=%s&fp=chrome&alpn=h2&insecure=0&allowInsecure=0&type=xhttp&host=%s&path=%s&mode=%s%s#G2rayXCodeLeafy|%s' \
        "$uuid" "$address" "$CODESPACES_EDGE_PORT" "$PORT_DOMAIN" "$PORT_DOMAIN" "$encoded_path" "$(url_encode_query_value "$xhttp_mode")" "$extra_param" "${GITHUB_USER:-User}${label_suffix}"
}

ws_alpn_query_param() {
    local mode="${1:-h2}"
    case "$mode" in
        h2) printf '&alpn=h2' ;;
        h1|http1|http/1.1) printf '&alpn=http%%2F1.1' ;;
        blank|auto|none|'') printf '' ;;
        *) printf '&alpn=h2' ;;
    esac
}

ws_alpn_label_suffix() {
    local mode="${1:-h2}"
    case "$mode" in
        h2) printf 'h2' ;;
        h1|http1|http/1.1) printf 'h1' ;;
        blank|auto|none|'') printf 'auto' ;;
        *) printf 'h2' ;;
    esac
}

ws_link_alpn_label() {
    local link="$1"
    if [[ "$link" == *"alpn=http%2F1.1"* ]]; then
        printf 'h1.1'
    elif [[ "$link" == *"alpn=h2"* ]]; then
        printf 'h2'
    else
        printf 'blank ALPN'
    fi
}

generate_ws_link_for_address() {
    ws_fallback_enabled || return 1
    local address="$1" label_suffix="${2:-}" alpn_mode="${3:-h2}" uuid="${4:-}" path encoded_path alpn_param
    [[ -n "$uuid" ]] || uuid=$(active_vless_uuid 2>/dev/null) || { printf ''; return 1; }
    [[ -z "$uuid" ]] && { printf ''; return 1; }
    path=$(ws_path_value)
    encoded_path=$(url_encode_query_value "$path")
    alpn_param=$(ws_alpn_query_param "$alpn_mode")
    printf 'vless://%s@%s:%s?encryption=none&security=tls&sni=%s&fp=chrome%s&insecure=0&allowInsecure=0&type=ws&host=%s&path=%s#G2rayXCodeLeafy|%s' \
        "$uuid" "$address" "$CODESPACES_EDGE_PORT" "$WS_PORT_DOMAIN" "$alpn_param" "$WS_PORT_DOMAIN" "$encoded_path" "${GITHUB_USER:-User}${label_suffix}"
}

generate_ws_link_variants_for_address() {
    local address="$1" base_label_suffix="${2:-}" uuid="${3:-}" mode suffix printed=false
    for mode in h2 h1 blank; do
        suffix=$(ws_alpn_label_suffix "$mode")
        [[ "$printed" == true ]] && printf '\n'
        generate_ws_link_for_address "$address" "${base_label_suffix}-${suffix}" "$mode" "$uuid"
        printed=true
    done
}

generate_ws_front_link() {
    ws_fallback_enabled || return 1
    local alpn_mode="${1:-h2}" uuid="${2:-}" front_domain path encoded_path alpn_param
    front_domain=$(ws_front_domain_value) || return 1
    [[ -n "$uuid" ]] || uuid=$(active_vless_uuid 2>/dev/null) || { printf ''; return 1; }
    [[ -z "$uuid" ]] && { printf ''; return 1; }
    path=$(ws_path_value)
    encoded_path=$(url_encode_query_value "$path")
    alpn_param=$(ws_alpn_query_param "$alpn_mode")
    printf 'vless://%s@%s:%s?encryption=none&security=tls&sni=%s&fp=chrome%s&insecure=0&allowInsecure=0&type=ws&host=%s&path=%s#G2rayXCodeLeafy|%s' \
        "$uuid" "$front_domain" "$CODESPACES_EDGE_PORT" "$front_domain" "$alpn_param" "$front_domain" "$encoded_path" "${GITHUB_USER:-User}-ws-front-$(ws_alpn_label_suffix "$alpn_mode")"
}

generate_ws_front_link_variants() {
    local uuid="${1:-}" mode printed=false
    for mode in h2 h1 blank; do
        [[ "$printed" == true ]] && printf '\n'
        generate_ws_front_link "$mode" "$uuid"
        printed=true
    done
}

generate_domain_link() {
    generate_link_for_address "$PORT_DOMAIN" "" "${1:-}"
}

generate_ws_domain_link() {
    local mode="${1:-h2}" uuid="${2:-}"
    generate_ws_link_for_address "$WS_PORT_DOMAIN" "-ws-domain-$(ws_alpn_label_suffix "$mode")" "$mode" "$uuid"
}

generate_ws_domain_link_variants() {
    local uuid="${1:-}" mode printed=false
    for mode in h2 h1 blank; do
        [[ "$printed" == true ]] && printf '\n'
        generate_ws_domain_link "$mode" "$uuid"
        printed=true
    done
}

route_monitor_max_candidates() {
    local max="$ROUTE_MONITOR_MAX_CANDIDATES"
    [[ "$max" =~ ^[0-9]+$ && "$max" -gt 0 ]] || max=40
    (( max > 64 )) && max=64
    printf '%s' "$max"
}

route_failure_reason_for_status() {
    local code="${1:-0}" error="${2:-}" lowered
    [[ "$code" =~ ^[0-9]{3}$ ]] || code=0
    lowered=$(printf '%s' "$error" | tr '[:upper:]' '[:lower:]')
    if xhttp_status_usable "$code"; then
        printf 'ready'
    elif [[ "$code" == "404" ]]; then
        printf 'route_settling_404'
    elif [[ "$code" == "0" ]]; then
        if [[ "$lowered" == *"timed"* || "$lowered" == *"timeout"* || "$lowered" == *"network"* || "$lowered" == *"connect"* ]]; then
            printf 'timeout_or_unreachable'
        else
            printf 'dns_tls_or_network_unreachable'
        fi
    elif (( code >= 500 )); then
        printf 'edge_or_origin_error'
    elif (( code == 401 || code == 403 )); then
        printf 'auth_or_visibility_blocked'
    else
        printf 'unexpected_http_status'
    fi
}

route_candidate_cooldown_active() {
    local ip="$1" now until reason ttl remaining
    [[ -s "$ROUTE_COOLDOWN_FILE" ]] || return 1
    now=$(date +%s)
    ttl="$ROUTE_FAILURE_COOLDOWN_SEC"
    [[ "$ttl" =~ ^[0-9]+$ && "$ttl" -gt 0 ]] || ttl=180
    while IFS=$'\t' read -r until _ip reason; do
        [[ "$_ip" == "$ip" && "$until" =~ ^[0-9]+$ ]] || continue
        remaining=$((until - now))
        if (( remaining > 0 && remaining <= ttl )); then
            return 0
        fi
    done < "$ROUTE_COOLDOWN_FILE"
    return 1
}

route_candidate_cooldown_bypass() {
    local ip="$1" source="${2:-}"
    [[ "$source" == "pinned" || "$source" == "manual" ]] && return 0
    [[ "$(pinned_route_value)" == "$ip" ]] && return 0
    route_file_contains "$MANUAL_ROUTE_CANDIDATES_FILE" "$ip" && return 0
    return 1
}

record_route_candidate_cooldown() {
    local ip="$1" reason="${2:-unknown}" now until ttl tmp
    valid_ipv4 "$ip" || return 0
    ttl="$ROUTE_FAILURE_COOLDOWN_SEC"
    [[ "$ttl" =~ ^[0-9]+$ && "$ttl" -gt 0 ]] || ttl=180
    now=$(date +%s)
    until=$((now + ttl))
    tmp=$(mktemp "$DATA_DIR/route_cooldown.XXXXXX") || return 0
    awk -F '\t' -v OFS='\t' -v now="$now" -v target="$ip" '$1 ~ /^[0-9]+$/ && $1 > now && $2 != target {print}' "$ROUTE_COOLDOWN_FILE" 2>/dev/null > "$tmp" || true
    printf '%s\t%s\t%s\n' "$until" "$ip" "$reason" >> "$tmp"
    mv "$tmp" "$ROUTE_COOLDOWN_FILE" 2>/dev/null || rm -f "$tmp"
    chmod 600 "$ROUTE_COOLDOWN_FILE" 2>/dev/null || true
}

clear_route_candidate_cooldown() {
    local ip="$1" now tmp
    valid_ipv4 "$ip" || return 0
    [[ -s "$ROUTE_COOLDOWN_FILE" ]] || return 0
    now=$(date +%s)
    tmp=$(mktemp "$DATA_DIR/route_cooldown.XXXXXX") || return 0
    awk -F '\t' -v OFS='\t' -v now="$now" -v target="$ip" \
        '$1 ~ /^[0-9]+$/ && $1 > now && $2 != target {print}' "$ROUTE_COOLDOWN_FILE" 2>/dev/null > "$tmp" || true
    if [[ -s "$tmp" ]]; then
        mv "$tmp" "$ROUTE_COOLDOWN_FILE" 2>/dev/null || rm -f "$tmp"
        chmod 600 "$ROUTE_COOLDOWN_FILE" 2>/dev/null || true
    else
        rm -f "$tmp" "$ROUTE_COOLDOWN_FILE" 2>/dev/null || true
    fi
}

update_route_candidate_stats() {
    local ip="$1" code="${2:-0}" ms="${3:-0}" source="${4:-probe}" reason="${5:-}" usable=false checked tmp now_epoch stats_cutoff=""
    valid_ipv4 "$ip" || return 0
    [[ "$code" =~ ^[0-9]{3}$ ]] || code=0
    [[ "$ms" =~ ^[0-9]+$ ]] || ms=0
    [[ -n "$reason" ]] || reason=$(route_failure_reason_for_status "$code")
    xhttp_status_usable "$code" && usable=true
    checked=$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date)
    now_epoch=$(date -u +%s 2>/dev/null || printf '0')
    if [[ "$ROUTE_STATS_MAX_AGE_SEC" =~ ^[0-9]+$ && "$ROUTE_STATS_MAX_AGE_SEC" -gt 0 && "$now_epoch" =~ ^[0-9]+$ && "$now_epoch" -gt "$ROUTE_STATS_MAX_AGE_SEC" ]]; then
        stats_cutoff=$(date -u -d "@$((now_epoch - ROUTE_STATS_MAX_AGE_SEC))" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || true)
    fi
    mkdir -p "$DATA_DIR" 2>/dev/null || true
    [[ -f "$ROUTE_STATS_FILE" ]] || : > "$ROUTE_STATS_FILE"
    tmp=$(mktemp "$DATA_DIR/route_stats.XXXXXX") || return 1
    awk -F '\t' -v OFS='\t' \
        -v target="$ip" -v code="$code" -v ms="$ms" -v usable="$usable" -v checked="$checked" -v source="$source" -v reason="$reason" \
        -v cutoff="$stats_cutoff" '
        function expired(stamp) {
            # Bookworm mawk does not support interval expressions such as {4}.
            return cutoff != "" && stamp ~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T/ && stamp < cutoff
        }
        function emit(ip, samples, successes, failures, avg, min, max, last_ms, last_code, last_usable, last_checked, ewma, recent_failures, last_reason, variation) {
            if (samples < 1) samples = 1
            printf "%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%s\t%s\t%d\t%d\t%s\t%d\n",
                ip, samples, successes, failures, avg, min, max,
                last_ms, last_code, last_usable, last_checked,
                ewma, recent_failures, last_reason, variation
        }
        $1 == target {
            found = 1
            stale = (NF >= 11 && expired($11))
            samples = stale ? 1 : (($2 ~ /^[0-9]+$/) ? $2 + 1 : 1)
            successes = stale ? 0 : (($3 ~ /^[0-9]+$/) ? $3 + 0 : 0)
            failures = stale ? 0 : (($4 ~ /^[0-9]+$/) ? $4 + 0 : 0)
            avg = stale ? 0 : (($5 ~ /^[0-9]+$/) ? $5 + 0 : 0)
            min = stale ? 0 : (($6 ~ /^[0-9]+$/) ? $6 + 0 : 0)
            max = stale ? 0 : (($7 ~ /^[0-9]+$/) ? $7 + 0 : 0)
            ewma = stale ? 0 : (($12 ~ /^[0-9]+$/) ? $12 + 0 : avg)
            recent_failures = stale ? 0 : (($13 ~ /^[0-9]+$/) ? $13 + 0 : 0)
            variation = stale ? 0 : (($15 ~ /^[0-9]+$/) ? $15 + 0 : 0)
            if (usable == "true") {
                deviation = (successes == 0) ? 0 : ((ms >= avg) ? ms - avg : avg - ms)
                variation = int((variation * 7 + deviation * 3) / 10 + 0.5)
                successes += 1
                avg = (successes == 1) ? ms : int((((avg * (successes - 1)) + ms) / successes) + 0.5)
                min = (min == 0 || ms < min) ? ms : min
                max = (ms > max) ? ms : max
                ewma = (ewma == 0) ? ms : int(((ewma * 7 + ms * 3) / 10) + 0.5)
                recent_failures = int((recent_failures * 7) / 10)
            } else {
                failures += 1
                penalty_ms = (ms > 0) ? ms : 9999
                ewma = (ewma == 0) ? penalty_ms : int(((ewma * 7 + penalty_ms * 3) / 10) + 0.5)
                recent_failures += 1
            }
            emit(target, samples, successes, failures, avg, min, max, ms, code, usable, checked, ewma, recent_failures, reason, variation)
            next
        }
        NF >= 1 {
            if ($1 != target && NF >= 11 && expired($11)) next
            print
        }
        END {
            if (!found) {
                samples = 1
                successes = (usable == "true") ? 1 : 0
                failures = (usable == "true") ? 0 : 1
                avg = (usable == "true") ? ms : 0
                min = (usable == "true") ? ms : 0
                max = (usable == "true") ? ms : 0
                ewma = (usable == "true") ? ms : ((ms > 0) ? ms : 9999)
                recent_failures = (usable == "true") ? 0 : 1
                emit(target, samples, successes, failures, avg, min, max, ms, code, usable, checked, ewma, recent_failures, reason, 0)
            }
        }
    ' "$ROUTE_STATS_FILE" 2>/dev/null > "$tmp" || { rm -f "$tmp"; return 1; }
    mv "$tmp" "$ROUTE_STATS_FILE" || { rm -f "$tmp"; return 1; }
    chmod 600 "$ROUTE_STATS_FILE" 2>/dev/null || true
}

record_route_candidate_health() {
    local ip="$1" code="$2" ms="$3" source="${4:-probe}" reason="${5:-}" usable=false checked
    checked=$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date)
    [[ -n "$reason" ]] || reason=$(route_failure_reason_for_status "$code")
    xhttp_status_usable "$code" && usable=true
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$checked" "$ip" "${code:-0}" "${ms:-0}" "$usable" "$source" "$reason" >> "${route_health_tmp:-$ROUTE_HEALTH_FILE}"
    update_route_candidate_stats "$ip" "$code" "$ms" "$source" "$reason" || true
    if [[ "$reason" == "timeout_or_unreachable" || "$reason" == "dns_tls_or_network_unreachable" || "$reason" == "edge_or_origin_error" ]]; then
        record_route_candidate_cooldown "$ip" "$reason"
    fi
}

save_last_good_route() {
    local ip="$1" code="${2:-0}" ms="${3:-0}" source="${4:-probe}" checked content
    [[ -n "$ip" ]] || return 0
    checked=$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date)
    content=$(printf 'ip=%s\nhttp_status=%s\nlatency_ms=%s\nsource=%s\nchecked_at=%s\n' \
        "$ip" "$code" "$ms" "$source" "$checked")
    _atomic_write "$LAST_GOOD_ROUTE_FILE" "$content"
    chmod 600 "$LAST_GOOD_ROUTE_FILE" 2>/dev/null || true
    log_event INFO "last_good_route saved ip=${ip} http=${code} latency_ms=${ms} source=${source}"
}

last_good_route_value() {
    [[ -f "$LAST_GOOD_ROUTE_FILE" ]] || return 0
    awk -F= '$1 == "ip" {print $2; exit}' "$LAST_GOOD_ROUTE_FILE" 2>/dev/null || true
}

last_good_route_fresh_value() {
    local age checked checked_epoch max now ip
    [[ -f "$LAST_GOOD_ROUTE_FILE" ]] || return 0
    max="$LAST_GOOD_ROUTE_MAX_AGE_SEC"
    [[ "$max" =~ ^[0-9]+$ ]] || max=1800
    (( max > 0 )) || return 0
    checked=$(awk -F= '$1 == "checked_at" {print $2; exit}' "$LAST_GOOD_ROUTE_FILE" 2>/dev/null || true)
    if [[ -n "$checked" ]] && checked_epoch=$(date -u -d "$checked" +%s 2>/dev/null); then
        now=$(date -u +%s 2>/dev/null || printf '0')
        [[ "$now" =~ ^[0-9]+$ ]] || now=0
        age=$(( now - checked_epoch ))
        (( age >= 0 )) || return 0
    else
        age=$(file_age_sec "$LAST_GOOD_ROUTE_FILE")
    fi
    (( age <= max )) || return 0
    ip=$(last_good_route_value)
    valid_ipv4 "$ip" || return 0
    candidate_blacklisted "$ip" && return 0
    printf '%s\n' "$ip"
}

last_good_route_summary() {
    if [[ ! -s "$LAST_GOOD_ROUTE_FILE" ]]; then
        printf 'Last good route : none recorded\n'
        return 0
    fi
    awk -F= '
        $1 == "ip" {ip=$2}
        $1 == "http_status" {code=$2}
        $1 == "latency_ms" {ms=$2}
        $1 == "source" {source=$2}
        $1 == "checked_at" {checked=$2}
        END {
            printf "Last good route : %s HTTP %s %sms source=%s checked=%s\n",
                (ip ? ip : "unknown"), (code ? code : "unknown"),
                (ms ? ms : "0"), (source ? source : "unknown"),
                (checked ? checked : "unknown")
        }
    ' "$LAST_GOOD_ROUTE_FILE" 2>/dev/null
}

route_probe_jitter_sleep() {
    local max_delay="${ROUTE_PROBE_JITTER_SEC:-0}" delay
    [[ "$max_delay" == "0" || "$max_delay" == "0.0" ]] && return 0
    delay=$(awk -v max="$max_delay" -v seed="$RANDOM" '
        BEGIN {
            if (max <= 0) { print "0"; exit }
            srand(systime() + seed)
            printf "%.3f\n", rand() * max
        }
    ' 2>/dev/null || printf '0')
    [[ "$delay" == "0" || "$delay" == "0.000" ]] && return 0
    sleep "$delay" 2>/dev/null || true
}

probe_route_candidate_worker() {
    local ip="$1" source="$2" outfile="$3" code ms reason
    route_probe_jitter_sleep
    read -r code ms reason < <(xhttp_probe_metrics external "$ip")
    [[ -n "$reason" ]] || reason=$(route_failure_reason_for_status "$code")
    printf '%s\t%s\t%s\t%s\t%s\n' "$ip" "${source:-probe}" "${code:-0}" "${ms:-0}" "$reason" > "$outfile"
}

route_refresh_probe_candidates() {
    local candidates="$1" max="${2:-24}"
    [[ "$max" =~ ^[0-9]+$ && "$max" -gt 0 ]] || max=24
    printf '%s\n' "$candidates" | awk -F '\t' -v max="$max" '
        function store(bucket, line, ip) {
            if (bucket == 1) { b1[++n1] = line; i1[n1] = ip }
            else if (bucket == 2) { b2[++n2] = line; i2[n2] = ip }
            else if (bucket == 3) { b3[++n3] = line; i3[n3] = ip }
            else { b4[++n4] = line; i4[n4] = ip }
        }
        function emit(line, ip) {
            if (count >= max || ip == "" || seen[ip]++) return
            print line
            count++
        }
        NF >= 2 {
            if ($1 == "pinned" || $1 == "manual") store(1, $0, $2)
            else if ($1 == "cache") store(3, $0, $2)
            else if ($1 == "builtin") store(4, $0, $2)
            else store(2, $0, $2)
        }
        END {
            for (idx = 1; idx <= n1; idx++) emit(b1[idx], i1[idx])
            for (idx = 1; idx <= n2; idx++) emit(b2[idx], i2[idx])
            for (idx = 1; idx <= n3; idx++) emit(b3[idx], i3[idx])
            for (idx = 1; idx <= n4; idx++) emit(b4[idx], i4[idx])
        }
    '
}

route_refresh_state_value() {
    local key="${1:-}"
    [[ -n "$key" && -s "$ROUTE_REFRESH_STATE_FILE" ]] || return 0
    awk -F '\t' -v key="$key" '$1 == key {print $2; exit}' "$ROUTE_REFRESH_STATE_FILE" 2>/dev/null || true
}

route_refresh_retry_allowed() {
    local next now remaining max_delay
    next=$(route_refresh_state_value next_allowed_epoch)
    [[ "$next" =~ ^[0-9]+$ ]] || return 0
    now=$(date +%s)
    max_delay="$ROUTE_REFRESH_BACKOFF_MAX_SEC"
    [[ "$max_delay" =~ ^[0-9]+$ && "$max_delay" -gt 0 ]] || max_delay=600
    remaining=$((next - now))
    (( remaining <= 0 || remaining > max_delay ))
}

route_refresh_record_success() {
    local now
    now=$(date +%s)
    _atomic_write "$ROUTE_REFRESH_STATE_FILE" "$(printf 'last_attempt_epoch\t%s\nconsecutive_failures\t0\nnext_allowed_epoch\t%s\n' "$now" "$now")" || return 0
    chmod 600 "$ROUTE_REFRESH_STATE_FILE" 2>/dev/null || true
}

route_refresh_record_failure() {
    local failures remaining delay now
    failures=$(route_refresh_state_value consecutive_failures)
    [[ "$failures" =~ ^[0-9]+$ ]] || failures=0
    failures=$((failures + 1))
    remaining="$failures"
    delay="$ROUTE_REFRESH_BACKOFF_INITIAL_SEC"
    while (( remaining > 1 && delay < ROUTE_REFRESH_BACKOFF_MAX_SEC )); do
        delay=$((delay * 2))
        remaining=$((remaining - 1))
    done
    (( delay > ROUTE_REFRESH_BACKOFF_MAX_SEC )) && delay="$ROUTE_REFRESH_BACKOFF_MAX_SEC"
    now=$(date +%s)
    _atomic_write "$ROUTE_REFRESH_STATE_FILE" "$(printf 'last_attempt_epoch\t%s\nconsecutive_failures\t%s\nnext_allowed_epoch\t%s\n' "$now" "$failures" "$((now + delay))")" || return 0
    chmod 600 "$ROUTE_REFRESH_STATE_FILE" 2>/dev/null || true
    log_event WARN "route_candidate_monitor backoff_sec=${delay} failures=${failures}"
}

request_route_candidate_health_refresh() {
    [[ -f "$CONFIG_FILE" ]] || return 0
    route_health_cache_fresh && return 0
    route_refresh_retry_allowed || return 0
    (
        if command -v flock >/dev/null 2>&1; then
            flock -n 9 || exit 0
        fi
        log_event INFO "route_candidate_monitor refresh_requested reason=stale_export_cache"
        run_with_deadline "$G2RAY_SUPERVISOR_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health >/dev/null 2>&1 || true
        if route_health_cache_fresh; then
            run_with_deadline "$G2RAY_SUPERVISOR_EXPORT_TIMEOUT_SEC" refresh_config_exports_if_changed >/dev/null 2>&1 || true
        fi
    ) 9>"$ROUTE_REFRESH_REQUEST_LOCK_FILE" >/dev/null 2>&1 &
}

_refresh_route_candidate_health_impl() {
    local force="${1:-0}"
    [[ -f "$CONFIG_FILE" ]] || return 0
    if [[ "$force" != "1" ]]; then
        route_health_cache_fresh && return 0
        if ! route_refresh_retry_allowed; then
            log_event INFO "route_candidate_monitor deferred reason=backoff"
            return 0
        fi
    fi
    local candidates probe_candidates source ip ip_probe ip_ms reason count=0 max route_health_tmp best_ip="" best_code=0 best_ms=99999999
    local concurrency pids=() files=() active=0 file result probed_count=0
    max=$(route_monitor_max_candidates)
    concurrency="$ROUTE_PROBE_CONCURRENCY"
    [[ "$concurrency" =~ ^[0-9]+$ && "$concurrency" -gt 0 ]] || concurrency=6
    (( concurrency > 16 )) && concurrency=16
    candidates=$(resolve_domain_ips_with_sources "$PORT_DOMAIN" || true)
    if [[ -z "$candidates" ]]; then
        route_refresh_record_failure
        return 0
    fi
    route_health_tmp=$(mktemp "$DATA_DIR/route_health.XXXXXX") || return 1

    collect_route_probe_batch() {
        local idx f row _ip _source _code _ms _reason
        for idx in "${!pids[@]}"; do
            wait "${pids[$idx]}" 2>/dev/null || true
            f="${files[$idx]}"
            [[ -s "$f" ]] || { rm -f "$f"; continue; }
            IFS= read -r row < "$f" 2>/dev/null || row=""
            rm -f "$f" 2>/dev/null || true
            IFS=$'\t' read -r _ip _source _code _ms _reason <<< "$row"
            valid_ipv4 "$_ip" || continue
            probed_count=$((probed_count + 1))
            record_route_candidate_health "$_ip" "$_code" "$_ms" "$_source" "$_reason"
            if xhttp_status_usable "$_code" && [[ "$_ms" =~ ^[0-9]+$ ]] && (( _ms < best_ms )); then
                best_ip="$_ip"
                best_code="$_code"
                best_ms="$_ms"
            fi
        done
        pids=()
        files=()
        active=0
    }

    probe_candidates=$(route_refresh_probe_candidates "$candidates" "$max")
    while IFS=$'\t' read -r source ip; do
        [[ -n "$ip" ]] || continue
        candidate_blacklisted "$ip" && continue
        route_candidate_cooldown_active "$ip" && ! route_candidate_cooldown_bypass "$ip" "$source" && continue
        file=$(mktemp "$DATA_DIR/route_probe.XXXXXX") || continue
        probe_route_candidate_worker "$ip" "$source" "$file" &
        pids+=("$!")
        files+=("$file")
        active=$((active + 1))
        count=$((count + 1))
        if (( active >= concurrency )); then
            collect_route_probe_batch
        fi
        (( count >= max )) && break
    done <<< "$probe_candidates"
    (( active > 0 )) && collect_route_probe_batch
    if (( probed_count == 0 )); then
        rm -f "$route_health_tmp" 2>/dev/null || true
        log_event WARN "route_candidate_monitor skipped_all_candidates count=${count} max=${max}"
        route_refresh_record_failure
        return 0
    fi
    if [[ -z "$best_ip" && -s "$ROUTE_HEALTH_FILE" ]] \
        && awk -F '\t' '$3 == "200" && $5 == "true" { found=1 } END { exit found ? 0 : 1 }' "$ROUTE_HEALTH_FILE" 2>/dev/null; then
        rm -f "$route_health_tmp" 2>/dev/null || true
        log_event WARN "route_candidate_monitor all_unusable_preserved_cache count=${count} max=${max}"
        route_refresh_record_failure
        return 0
    fi
    mv "$route_health_tmp" "$ROUTE_HEALTH_FILE"
    chmod 600 "$ROUTE_HEALTH_FILE" 2>/dev/null || true
    if [[ -z "$best_ip" ]]; then
        log_event WARN "route_candidate_monitor all_unusable_cached count=${count} max=${max}"
        route_refresh_record_failure
        return 0
    fi
    [[ -n "$best_ip" ]] && save_last_good_route "$best_ip" "$best_code" "$best_ms" "route_candidate_monitor"
    route_refresh_record_success
    log_event INFO "route_candidate_monitor refreshed count=${count} max=${max}"
}

refresh_route_candidate_health() {
    local force=0
    if [[ "${1:-}" == "--force" ]]; then
        force=1
        shift
    fi
    (($# == 0)) || return 1
    with_file_lock "$ROUTE_STATE_LOCK_FILE" 45 _refresh_route_candidate_health_impl "$force"
}

route_health_cache_fresh() {
    local age
    [[ -s "$ROUTE_HEALTH_FILE" ]] || return 1
    awk -F '\t' '$3 == "200" && $5 == "true" { found=1; exit } END { exit found ? 0 : 1 }' "$ROUTE_HEALTH_FILE" 2>/dev/null || return 1
    age=$(file_age_sec "$ROUTE_HEALTH_FILE")
    [[ "$ROUTE_HEALTH_TTL_SEC" =~ ^[0-9]+$ ]] || ROUTE_HEALTH_TTL_SEC=300
    (( age <= ROUTE_HEALTH_TTL_SEC ))
}

route_health_cache_exportable() {
    local age
    [[ -s "$ROUTE_HEALTH_FILE" ]] || return 1
    awk -F '\t' '$3 == "200" && $5 == "true" { found=1; exit } END { exit found ? 0 : 1 }' "$ROUTE_HEALTH_FILE" 2>/dev/null || return 1
    age=$(file_age_sec "$ROUTE_HEALTH_FILE")
    [[ "$ROUTE_HEALTH_EXPORT_MAX_AGE_SEC" =~ ^[0-9]+$ ]] || ROUTE_HEALTH_EXPORT_MAX_AGE_SEC=21600
    (( age <= ROUTE_HEALTH_EXPORT_MAX_AGE_SEC ))
}

_refresh_route_candidate_health_for_export_impl() {
    route_health_cache_exportable && return 0
    _refresh_route_candidate_health_impl 1 || return 1
    route_health_cache_exportable
}

refresh_route_candidate_health_for_export() {
    with_file_lock "$ROUTE_STATE_LOCK_FILE" 45 _refresh_route_candidate_health_for_export_impl
}

ensure_route_cache_for_export() {
    local route_code="${1:-}" reason="${2:-export}" route_ms route_reason
    route_health_cache_exportable && return 0
    [[ -f "$CONFIG_FILE" ]] || return 1
    if [[ -z "$route_code" ]]; then
        read -r route_code route_ms route_reason < <(xhttp_probe_metrics external)
    fi
    if ! xhttp_status_usable "${route_code:-0}"; then
        log_event WARN "route_candidate_monitor synchronous_refresh_skipped reason=${reason} xhttp_probe=${route_code:-0} action=background_refresh"
        request_route_candidate_health_refresh
        return 1
    fi
    log_event INFO "route_candidate_monitor synchronous_refresh reason=${reason}"
    if run_with_deadline "$G2RAY_INTERACTIVE_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health_for_export >/dev/null 2>&1 \
        && route_health_cache_exportable; then
        log_event INFO "route_candidate_monitor synchronous_refresh_ready reason=${reason}"
        return 0
    fi
    log_event WARN "route_candidate_monitor synchronous_refresh_unavailable reason=${reason} action=background_refresh"
    request_route_candidate_health_refresh
    return 1
}

cached_usable_fallback_ips() {
    [[ -s "$ROUTE_HEALTH_FILE" ]] || return 1
    local cache_age last_good pinned stats_input
    cache_age=$(file_age_sec "$ROUTE_HEALTH_FILE")
    [[ "$ROUTE_HEALTH_EXPORT_MAX_AGE_SEC" =~ ^[0-9]+$ ]] || ROUTE_HEALTH_EXPORT_MAX_AGE_SEC=21600
    if (( cache_age > ROUTE_HEALTH_EXPORT_MAX_AGE_SEC )); then
        [[ "${1:-}" == --quiet ]] || log_event WARN "fallback_route_filter stale_cache age_sec=${cache_age} max_age_sec=${ROUTE_HEALTH_EXPORT_MAX_AGE_SEC} action=domain_only"
        return 1
    fi
    pinned=$(pinned_route_value)
    last_good=$(last_good_route_fresh_value)
    stats_input="$ROUTE_STATS_FILE"
    [[ -s "$stats_input" ]] || stats_input="/dev/null"
    awk -F '\t' -v pinned="$pinned" -v last_good="$last_good" -v stats_file="$stats_input" '
        FILENAME == stats_file {
            if (NF >= 11) {
                samples[$1] = ($2 ~ /^[0-9]+$/) ? $2 + 0 : 0
                successes[$1] = ($3 ~ /^[0-9]+$/) ? $3 + 0 : 0
                avg[$1] = ($5 ~ /^[0-9]+$/) ? $5 + 0 : 0
                latest[$1] = ($8 ~ /^[0-9]+$/) ? $8 + 0 : 0
                ewma[$1] = ($12 ~ /^[0-9]+$/) ? $12 + 0 : avg[$1]
                recent_failures[$1] = ($13 ~ /^[0-9]+$/) ? $13 + 0 : 0
                variation[$1] = ($15 ~ /^[0-9]+$/) ? $15 + 0 : 0
            }
            next
        }
        NF >= 5 && $3 == "200" && $5 == "true" {
            ip = $2
            latency = ($4 ~ /^[0-9]+$/) ? $4 + 0 : 99999999
            total = ((ip in samples) && samples[ip] > 0) ? samples[ip] : 1
            ok = (ip in successes) ? successes[ip] : 1
            recent = (ip in recent_failures) ? recent_failures[ip] : 0
            success_penalty = int(((total - ok) * 1000) / total) + (recent * 75)
            avg_ms = ((ip in avg) && avg[ip] > 0) ? avg[ip] : latency
            score_ms = ((ip in ewma) && ewma[ip] > 0) ? ewma[ip] : avg_ms
            last_ms = ((ip in latest) && latest[ip] > 0) ? latest[ip] : latency
            route_score = score_ms + success_penalty
            pinned_rank = (ip == pinned) ? 0 : 1
            last_good_rank = (ip == last_good) ? 0 : 1
            printf "%d\t%08d\t%08d\t%04d\t%08d\t%08d\t%d\t%s\t%s\t%s\n",
                pinned_rank, route_score, score_ms, success_penalty, variation[ip], last_ms, last_good_rank, ip, $3, $4
        }
    ' "$stats_input" "$ROUTE_HEALTH_FILE" 2>/dev/null | sort -k1,1n -k2,2n -k3,3n -k4,4n -k5,5n -k6,6n -k7,7n \
        | while IFS=$'\t' read -r _pinned _route_score _avg _success _variation _latest _last_good ip _code _ms; do
            valid_ipv4 "$ip" || continue
            candidate_blacklisted "$ip" && continue
            route_candidate_cooldown_active "$ip" && ! route_candidate_cooldown_bypass "$ip" && continue
            printf '%s\n' "$ip"
        done | awk '!seen[$0]++ {print}'
}

menu_best_route_summary() {
    local ip ewma pinned age suffix="" max_age="$ROUTE_HEALTH_EXPORT_MAX_AGE_SEC"
    [[ -s "$ROUTE_HEALTH_FILE" ]] || { printf 'no cached probes yet'; return 0; }
    age=$(file_age_sec "$ROUTE_HEALTH_FILE")
    if (( age > max_age )); then
        printf 'expired cache (option 17)'
        return 0
    fi
    (( age > ROUTE_HEALTH_TTL_SEC )) && suffix=' [stale cache]'
    ip=$(cached_usable_fallback_ips --quiet | awk 'NR==1 {print}' || true)
    [[ -n "$ip" ]] || { printf 'no usable cached routes'; return 0; }
    ewma=$(awk -F '\t' -v ip="$ip" '$1==ip {print ($12 ~ /^[0-9]+$/) ? $12 : $5; exit}' "$ROUTE_STATS_FILE" 2>/dev/null || true)
    [[ "$ewma" =~ ^[0-9]+$ ]] || ewma='?'
    pinned=$(pinned_route_value)
    [[ "$ip" == "$pinned" ]] && suffix="${suffix} [pinned]"
    printf '%s (server probe EWMA %sms)%s' "$ip" "$ewma" "$suffix"
}

route_candidate_health_summary() {
    local pinned stats_input
    pinned=$(pinned_route_value)
    printf 'Pinned route : %s\n' "${pinned:-none}"
    if [[ ! -s "$ROUTE_HEALTH_FILE" ]]; then
        printf 'Last refresh : none recorded\n'
        printf 'Candidates   : no cached candidate route probes yet\n'
        return 0
    fi

    local last cache_age cache_status sample_range
    last=$(awk -F '\t' 'END{print $1}' "$ROUTE_HEALTH_FILE" 2>/dev/null)
    cache_age=$(file_age_sec "$ROUTE_HEALTH_FILE")
    if (( cache_age <= ROUTE_HEALTH_TTL_SEC )); then
        cache_status="fresh"
    elif (( cache_age <= ROUTE_HEALTH_EXPORT_MAX_AGE_SEC )); then
        cache_status="stale"
    else
        cache_status="expired"
    fi
    sample_range=$(awk -F '\t' '
        $2 ~ /^[0-9]+$/ {
            value = $2 + 0
            if (!seen++) { min = value; max = value }
            if (value < min) min = value
            if (value > max) max = value
        }
        END {
            if (seen) printf "%d-%d", min, max
            else printf "none"
        }
    ' "$ROUTE_STATS_FILE" 2>/dev/null)
    printf 'Last refresh : %s\n' "${last:-unknown}"
    printf 'Cache status : %s age_sec=%s fresh_ttl=%ss export_max_age=%ss\n' \
        "$cache_status" "$cache_age" "$ROUTE_HEALTH_TTL_SEC" "$ROUTE_HEALTH_EXPORT_MAX_AGE_SEC"
    printf 'Candidates   : pinned first, then best reliable usable routes\n'
    printf 'Probe scope  : Codespace-side route checks; your ISP/client path can still block some IPs\n'
    printf 'Samples      : rolling per-route sample range=%s; low counts are provisional\n' "$sample_range"
    stats_input="$ROUTE_STATS_FILE"
    [[ -s "$stats_input" ]] || stats_input="/dev/null"
    awk -F '\t' -v pinned="$pinned" -v stats_file="$stats_input" '
        FILENAME == stats_file {
            if (NF >= 11) {
                samples[$1] = ($2 ~ /^[0-9]+$/) ? $2 + 0 : 0
                successes[$1] = ($3 ~ /^[0-9]+$/) ? $3 + 0 : 0
                avg[$1] = ($5 ~ /^[0-9]+$/) ? $5 + 0 : 0
                latest[$1] = ($8 ~ /^[0-9]+$/) ? $8 + 0 : 0
                ewma[$1] = ($12 ~ /^[0-9]+$/) ? $12 + 0 : avg[$1]
                recent_failures[$1] = ($13 ~ /^[0-9]+$/) ? $13 + 0 : 0
                variation[$1] = ($15 ~ /^[0-9]+$/) ? $15 + 0 : 0
                last_reason[$1] = (NF >= 14 && $14 != "") ? $14 : "unknown"
            }
            next
        }
        NF >= 5 {
            ip = $2
            record_usable = ($3 == "200" && $5 == "true")
            usable_rank = record_usable ? 0 : 1
            latency = ($4 ~ /^[0-9]+$/) ? $4 + 0 : 99999999
            total = ((ip in samples) && samples[ip] > 0) ? samples[ip] : 1
            ok = (ip in successes) ? successes[ip] : (record_usable ? 1 : 0)
            recent = (ip in recent_failures) ? recent_failures[ip] : 0
            success_penalty = int(((total - ok) * 1000) / total) + (recent * 75)
            confidence = (total >= 10 && ok >= total * 0.95) ? "high" : ((total >= 5 && ok >= total * 0.80) ? "medium" : "provisional")
            avg_ms = ((ip in avg) && avg[ip] > 0) ? avg[ip] : latency
            score_ms = ((ip in ewma) && ewma[ip] > 0) ? ewma[ip] : avg_ms
            last_ms = ((ip in latest) && latest[ip] > 0) ? latest[ip] : latency
            route_score = score_ms + success_penalty
            pinned_rank = (ip == pinned) ? 0 : 1
            source = (NF >= 6 && $6 != "") ? $6 : "unknown"
            reason = (NF >= 7 && $7 != "") ? $7 : ((ip in last_reason) ? last_reason[ip] : "unknown")
            line = sprintf("%-15s HTTP %-3s last=%sms avg=%sms success=%s/%s recent=%sms probe_variation=%sms recent_fail=%s confidence=%s usable=%s source=%s reason=%s",
                ip, $3, last_ms, avg_ms, ok, total, score_ms, variation[ip], recent, confidence, record_usable ? "true" : "false", source, reason)
            printf "%d\t%d\t%08d\t%08d\t%04d\t%08d\t%08d\t%s\t%s\n",
                pinned_rank, usable_rank, route_score, score_ms, success_penalty, variation[ip], last_ms, ip, line
        }
    ' "$stats_input" "$ROUTE_HEALTH_FILE" 2>/dev/null | sort -k1,1n -k2,2n -k3,3n -k4,4n -k5,5n -k6,6n -k7,7n \
        | while IFS=$'\t' read -r _pinned _usable _route_score _avg _success _variation _latest ip line; do
            candidate_blacklisted "$ip" && continue
            printf '%s\n' "$line"
        done
}

route_candidate_refresh_with_feedback() {
    echo -e "  ${DIM}Refreshing route probes (up to ${G2RAY_INTERACTIVE_ROUTE_REFRESH_TIMEOUT_SEC}s)...${NC}"
    if run_with_deadline "$G2RAY_INTERACTIVE_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health --force >/dev/null 2>&1; then
        return 0
    fi
    echo -e "  ${YELLOW}Route probe refresh timed out; keeping last-known route data.${NC}"
    return 1
}

route_candidate_export_with_feedback() {
    echo -e "  ${DIM}Refreshing local exports (up to ${G2RAY_INTERACTIVE_EXPORT_TIMEOUT_SEC}s)...${NC}"
    if run_with_deadline "$G2RAY_INTERACTIVE_EXPORT_TIMEOUT_SEC" refresh_config_exports >/dev/null 2>&1; then
        return 0
    fi
    echo -e "  ${YELLOW}Export refresh timed out or found no usable configs.${NC}"
    return 1
}

show_route_candidate_manager() {
    local choice ip line paste_text summary
    while true; do
        refresh_screen
        echo -e "\n  ${RED}Route Candidates${NC}\n"
        echo -e "  ${WHITE}${B}Manual and pinned routes${NC}"
        route_candidate_state_summary | sed 's/^/  /'
        echo ""
        echo -e "  ${WHITE}${B}Measured candidates${NC}"
        route_candidate_health_summary | sed 's/^/  /'
        echo ""
        echo -e "  ${RED}1)${NC} Refresh Route Probes"
        echo -e "  ${RED}2)${NC} Add Manual IPv4 Candidate"
        echo -e "  ${RED}22)${NC} Batch Import Manual IPv4s"
        echo -e "  ${RED}3)${NC} Pin Preferred Route"
        echo -e "  ${RED}4)${NC} Blacklist Bad Route"
        echo -e "  ${RED}5)${NC} Remove Manual Route"
        echo -e "  ${RED}6)${NC} Unblacklist Route"
        echo -e "  ${RED}7)${NC} Reset Route Health Cache"
        echo -e "  ${RED}8)${NC} Reset All Route Preferences"
        echo -e "  ${RED}9)${NC} Refresh Exports"
        echo -e "  ${RED}0)${NC} Return"
        echo -ne "  ${RED}Select:${NC} "
        read -r choice || return 0
        case "$choice" in
            1)
                route_candidate_refresh_with_feedback || true
                echo -e "  ${GREEN}Route probes refreshed.${NC}"; sleep 1
                ;;
            2)
                echo -ne "  ${GREEN}Manual IPv4:${NC} "
                read -r ip || continue
                if add_manual_route_candidate "$ip"; then
                    route_candidate_refresh_with_feedback || true
                    route_candidate_export_with_feedback || true
                    echo -e "  ${GREEN}Added.${NC}"
                else
                    echo -e "  ${RED}Invalid, duplicate, or blacklisted IPv4.${NC}"
                fi
                sleep 1
                ;;
            22)
                echo -e "  ${GREEN}Paste IPv4s or messy text. Submit a blank line to finish:${NC}"
                paste_text=""
                while IFS= read -r line; do
                    [[ -z "$line" ]] && break
                    paste_text+="${line}"$'\n'
                done
                if [[ -z "$paste_text" ]]; then
                    echo -e "  ${YELLOW}No text entered.${NC}"
                else
                    summary=$(import_manual_route_candidates_from_text "$paste_text" || true)
                    if [[ "$summary" == *"added=0"* ]]; then
                        echo -e "  ${YELLOW}No new valid IPv4 routes were added (${summary}).${NC}"
                    else
                        route_candidate_refresh_with_feedback || true
                        route_candidate_export_with_feedback || true
                        echo -e "  ${GREEN}Batch import complete (${summary}).${NC}"
                    fi
                fi
                sleep 1
                ;;
            3)
                echo -ne "  ${GREEN}IPv4 to pin:${NC} "
                read -r ip || continue
                if pin_route_candidate "$ip"; then
                    route_candidate_refresh_with_feedback || true
                    route_candidate_export_with_feedback || true
                    echo -e "  ${GREEN}Pinned preferred route.${NC}"
                else
                    echo -e "  ${RED}Invalid or blacklisted IPv4.${NC}"
                fi
                sleep 1
                ;;
            4)
                echo -ne "  ${YELLOW}IPv4 to blacklist:${NC} "
                read -r ip || continue
                if blacklist_route_candidate "$ip"; then
                    route_candidate_export_with_feedback || true
                    echo -e "  ${YELLOW}Blacklisted.${NC}"
                else
                    echo -e "  ${RED}Invalid IPv4.${NC}"
                fi
                sleep 1
                ;;
            5)
                echo -ne "  ${GREEN}Manual IPv4 to remove:${NC} "
                read -r ip || continue
                if remove_manual_route_candidate "$ip"; then
                    route_candidate_export_with_feedback || true
                    echo -e "  ${GREEN}Removed.${NC}"
                else
                    echo -e "  ${RED}Invalid IPv4.${NC}"
                fi
                sleep 1
                ;;
            6)
                echo -ne "  ${GREEN}IPv4 to unblacklist:${NC} "
                read -r ip || continue
                if unblacklist_route_candidate "$ip"; then
                    route_candidate_refresh_with_feedback || true
                    route_candidate_export_with_feedback || true
                    echo -e "  ${GREEN}Unblacklisted.${NC}"
                else
                    echo -e "  ${RED}Invalid IPv4 or route is not blacklisted.${NC}"
                fi
                sleep 1
                ;;
            7)
                local reset_probe reset_ms reset_reason
                read -r reset_probe reset_ms reset_reason < <(xhttp_probe_metrics external)
                if ! xhttp_status_usable "${reset_probe:-0}"; then
                    echo -e "  ${YELLOW}Edge route is currently HTTP ${reset_probe:-0}; resetting this cache cannot repair GitHub's route.${NC}"
                    echo -e "  ${DIM}It only discards learned route measurements. Wait for recovery or use Recover Now first.${NC}"
                fi
                echo -ne "  ${YELLOW}Reset measured route health cache only? (y/n):${NC} "
                read -r ip || continue
                if [[ "$ip" =~ ^[Yy]$ ]]; then
                    reset_route_candidate_cache
                    route_candidate_export_with_feedback || true
                    echo -e "  ${GREEN}Route health cache reset. Manual, pinned, and blacklisted routes were kept.${NC}"
                    sleep 1
                fi
                ;;
            8)
                echo -ne "  ${YELLOW}Reset manual routes, blacklist, pin, and route cache? (y/n):${NC} "
                read -r ip || continue
                if [[ "$ip" =~ ^[Yy]$ ]]; then
                    reset_route_candidate_state
                    route_candidate_export_with_feedback || true
                    echo -e "  ${GREEN}All route preferences and cached measurements reset.${NC}"
                    sleep 1
                fi
                ;;
            9)
                route_candidate_refresh_with_feedback || true
                route_candidate_export_with_feedback || true
                echo -e "  ${GREEN}Routes and exports refreshed.${NC}"; sleep 1
                ;;
            0) return 0 ;;
            *) echo -e "  ${RED}Invalid option.${NC}"; sleep 1 ;;
        esac
    done
}

show_live_monitor() {
    local choice tick=1 local_code local_ms edge_code edge_ms engine listener
    while true; do
        if [[ -f "$CONFIG_FILE" && $((tick % 6)) -eq 0 ]]; then
            run_with_deadline "$G2RAY_INTERACTIVE_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health >/dev/null 2>&1 || true
        fi
        read -r local_code local_ms _probe_reason < <(xhttp_probe_metrics local)
        read -r edge_code edge_ms _probe_reason < <(xhttp_probe_metrics external)
        xray_running && engine="running" || engine="stopped"
        is_port_open && listener="open" || listener="closed"
        refresh_screen
        echo -e "\n  ${RED}Live Monitor${NC}"
        echo -e "  ${DIM}Refreshes every 10 seconds. Press Enter or q to return.${NC}\n"
        echo -e "  Engine        : ${WHITE}${engine}${NC}"
        echo -e "  Listener      : ${WHITE}${listener}${NC}"
        echo -e "  Local XHTTP   : HTTP ${local_code:-0} ${local_ms:-0}ms usable=$(xhttp_status_usable "$local_code" && printf true || printf false)"
        echo -e "  Edge XHTTP    : HTTP ${edge_code:-0} ${edge_ms:-0}ms usable=$(xhttp_status_usable "$edge_code" && printf true || printf false)"
        echo -e "  Supervisor    : ${WHITE}$(background_supervisor_status)${NC}"
        echo -e "  Self-heal     : ${WHITE}$(self_heal_state_summary)${NC}\n"
        echo -e "  ${WHITE}${B}Best Route Candidates${NC}"
        route_candidate_health_summary | sed 's/^/  /'
        echo -e "\n  ${WHITE}${B}Route Settling${NC}"
        route_settling_history_summary | sed 's/^/  /'
        echo -e "\n  ${WHITE}${B}Recent Events${NC}"
        if [[ -s "$LOG_FILE" ]]; then
            tail -n 8 "$LOG_FILE" | sed 's/^/  /'
        else
            echo -e "  ${DIM}No events logged yet.${NC}"
        fi
        echo -ne "\n  ${DIM}Press Enter/q to return, or wait to refresh...${NC} "
        if IFS= read -r -t 10 choice; then
            [[ -z "$choice" || "$choice" =~ ^[Qq]$ ]] && return 0
        fi
        tick=$((tick + 1))
    done
}

show_ws_front_manager() {
    local choice front input normalized ws_state prev_preserve
    while true; do
        front=$(ws_front_domain_value 2>/dev/null || true)
        if ws_fallback_enabled; then
            ws_state="${GREEN}Enabled${NC}"
        else
            ws_state="${DIM}Disabled${NC}"
        fi
        refresh_screen
        echo -e "\n  ${RED}Cloudflare WebSocket Front${NC}\n"
        echo -e "  WS fallback      : ${ws_state}"
        echo -e "  Codespaces origin: ${WHITE}${WS_PORT_DOMAIN}${NC}"
        echo -e "  Origin port      : ${WHITE}${WS_PORT}${NC}"
        if [[ -n "$front" ]]; then
            echo -e "  Front domain     : ${GREEN}${front}${NC}"
        else
            echo -e "  Front domain     : ${DIM}not set${NC}"
        fi
        echo ""
        echo -e "  ${WHITE}${B}Cloudflare setup target${NC}"
        echo -e "  ${DIM}DNS: create a proxied CNAME such as ws.yourdomain.com -> ${WS_PORT_DOMAIN}${NC}"
        echo -e "  ${DIM}Origin Rule: for that hostname, override Host header to ${WS_PORT_DOMAIN}.${NC}"
        echo -e "  ${DIM}Cloudflare should also use ${WS_PORT_DOMAIN} as origin SNI; verify in Origin Rules/TLS settings.${NC}"
        echo -e "  ${YELLOW}Cloudflare Free note: a simple proxied CNAME is not expected to be reliable here.${NC}"
        echo -e "  ${YELLOW}This front mode needs Host/SNI override support; keep direct WS/XHTTP links otherwise.${NC}"
        echo ""
        echo -e "  ${RED}1)${NC} Set Cloudflare WS front domain"
        echo -e "  ${RED}2)${NC} Clear Cloudflare WS front domain"
        echo -e "  ${RED}3)${NC} Toggle WebSocket Fallback"
        echo -e "  ${RED}4)${NC} Regenerate Config With Same UUID"
        echo -e "  ${RED}0)${NC} Return"
        echo -ne "  ${RED}╰─❯${NC} "
        read -r choice
        case "$choice" in
            1)
                echo -ne "  Enter front domain (example: ws.example.com): "
                read -r input
                normalized=$(normalize_hostname_value "$input")
                if set_ws_front_domain "$normalized"; then
                    log_event INFO "ws_front_domain set domain=${normalized}"
                    echo -e "  ${GREEN}Saved front domain: ${normalized}${NC}"
                else
                    echo -e "  ${RED}Invalid hostname. Use a real subdomain like ws.example.com.${NC}"
                fi
                sleep 2
                ;;
            2)
                clear_ws_front_domain
                log_event INFO "ws_front_domain cleared"
                echo -e "  ${WHITE}Cloudflare WS front domain cleared.${NC}"
                sleep 2
                ;;
            3)
                if toggle_ws_fallback_mode; then
                    echo -e "  ${GREEN}WebSocket fallback enabled.${NC}"
                else
                    echo -e "  ${WHITE}WebSocket fallback disabled.${NC}"
                fi
                sleep 2
                ;;
            4)
                if [[ -f "$CONFIG_FILE" ]]; then
                    if generate_config_preserving_uuid_with_feedback; then
                        echo -e "  ${GREEN}Config regenerated with existing UUID.${NC}"
                    else
                        echo -e "  ${YELLOW}Config was not regenerated; previous config remains in place.${NC}"
                    fi
                else
                    echo -e "  ${YELLOW}No config exists yet. Use option 2 from the main panel first.${NC}"
                fi
                echo -ne "  ${DIM}Press Enter to continue...${NC}"; read -r
                ;;
            0) return 0 ;;
            *) echo -e "  ${RED}Invalid option.${NC}"; sleep 1 ;;
        esac
    done
}

safe_max_fallback_links() {
    local max="${1:-$MAX_FALLBACK_LINKS}"
    [[ "$max" =~ ^[0-9]+$ && "$max" -gt 0 ]] || max=20
    (( max > 64 )) && max=64
    printf '%s' "$max"
}

safe_ws_fallback_links() {
    local max="${1:-$WS_MAX_FALLBACK_LINKS}"
    [[ "$max" =~ ^[0-9]+$ && "$max" -gt 0 ]] || max=3
    (( max > 10 )) && max=10
    printf '%s' "$max"
}

effective_export_route_ips() {
    local max_links
    max_links=$(safe_max_fallback_links)
    cached_usable_fallback_ips 2>/dev/null | awk -v max="$max_links" 'NF {print; count++; if (count >= max) exit}'
}

usable_fallback_ips() {
    local ip count=0 max_links
    local cached_routes=()
    max_links=$(safe_max_fallback_links)
    mapfile -t cached_routes < <(effective_export_route_ips || true)
    if ! route_health_cache_fresh; then
        # Exports are a read path. They must never become a second serial route
        # scanner. Keep useful cached routes and ask the bounded monitor to refresh
        # them in the background instead.
        request_route_candidate_health_refresh
    fi
    for ip in "${cached_routes[@]}"; do
        [[ -n "$ip" ]] || continue
        printf '%s\n' "$ip"
        count=$((count + 1))
        (( count >= max_links )) && return 0
    done
    log_event INFO "fallback_route_filter no_cached_usable_routes action=domain_only_refresh_requested"
}

generate_ip_link() {
    local address; address=$(usable_fallback_ips | head -1 || true)
    [[ -n "$address" ]] || return 1
    generate_link_for_address "$address" "-ip1"
}

generate_ip_links() {
    generate_ip_links_from_list "$(usable_fallback_ips || true)"
}

generate_ip_links_from_list() {
    local fallback_ips="${1:-}"
    local uuid="${2:-}" address index=1 printed=false max_links
    [[ -n "$uuid" ]] || uuid=$(active_vless_uuid 2>/dev/null) || return 1
    max_links=$(safe_max_fallback_links)
    while IFS= read -r address; do
        [[ -n "$address" ]] || continue
        (( index > max_links )) && break
        [[ "$printed" == true ]] && printf '\n'
        generate_link_for_address "$address" "-ip${index}" "$uuid"
        printed=true
        index=$(( index + 1 ))
    done <<< "$fallback_ips"
}

generate_ws_links() {
    generate_ws_links_from_list "$(usable_fallback_ips || true)"
}

generate_ws_links_from_list() {
    ws_fallback_enabled || return 0
    local fallback_ips="${1:-}"
    local uuid="${2:-}" address index=1 printed=false max_links front_links
    [[ -n "$uuid" ]] || uuid=$(active_vless_uuid 2>/dev/null) || return 1
    max_links=$(safe_ws_fallback_links)
    front_links=$(generate_ws_front_link_variants "$uuid" 2>/dev/null || true)
    if [[ -n "$front_links" ]]; then
        printf '%s' "$front_links"
        printed=true
    fi
    while IFS= read -r address; do
        [[ -n "$address" ]] || continue
        (( index > max_links )) && break
        [[ "$printed" == true ]] && printf '\n'
        generate_ws_link_variants_for_address "$address" "-ws-ip${index}" "$uuid"
        printed=true
        index=$(( index + 1 ))
    done <<< "$fallback_ips"
    if domain_link_export_enabled; then
        [[ "$printed" == true ]] && printf '\n'
        generate_ws_domain_link_variants "$uuid" || true
    fi
}

generate_ordered_links() {
    local domain_link ip_links ws_links fallback_ips uuid
    uuid=$(active_vless_uuid 2>/dev/null) || return 1
    fallback_ips=$(usable_fallback_ips || true)
    ip_links=$(generate_ip_links_from_list "$fallback_ips" "$uuid" || true)
    printf '%s\n' "$ip_links" | awk 'NF'
    if domain_link_export_enabled; then
        domain_link=$(generate_domain_link "$uuid" || true)
        if [[ -n "$domain_link" ]] && ! printf '%s\n' "$ip_links" | grep -Fxq "$domain_link"; then
            printf '%s\n' "$domain_link"
        fi
    fi
    ws_links=$(generate_ws_links_from_list "$fallback_ips" "$uuid" || true)
    printf '%s\n' "$ws_links" | awk 'NF'
}

config_export_route_ips() {
    local link authority ip
    while IFS= read -r link; do
        [[ "$link" == vless://* ]] || continue
        authority="${link#vless://}"
        authority="${authority%%\?*}"
        authority="${authority##*@}"
        ip="${authority%%:*}"
        valid_ipv4 "$ip" && printf '%s\n' "$ip"
    done <<< "${1:-}" | awk '!seen[$0]++'
}

route_quality_json() {
    local ips="${1:-}" result
    [[ -n "$ips" && -s "$ROUTE_STATS_FILE" ]] || { printf '[]'; return 0; }
    # jq handles string escaping, including corrupt TSV timestamps. Capture
    # output first so a failing parser cannot append [] after partial JSON.
    if result=$(jq -Rn --arg ips "$ips" '
        def numeric: if test("^[0-9]{1,12}$") then tonumber else 0 end;
        def latency($fallback): if test("^[0-9]{1,6}$") and tonumber > 0 then tonumber else ($fallback | numeric) end;
        [inputs | split("\t") | select(length >= 11)] as $rows |
        ($ips | split("\n") | map(select(length > 0))) as $order |
        [range(0; $order | length) as $i | $order[$i] as $ip |
         first($rows[] | select(.[0] == $ip)) as $r |
         {ip: $ip, rank: ($i + 1), samples: ($r[1] | numeric), successes: ($r[2] | numeric),
          avg_latency_ms: ($r[4] | numeric), last_latency_ms: ($r[7] | numeric),
          ewma_latency_ms: (($r[11] // "") | latency($r[4])),
          probe_variation_ms: (($r[14] // "0") | numeric), last_checked: $r[10]}]
    ' "$ROUTE_STATS_FILE" 2>/dev/null); then
        printf '%s' "$result"
    else
        printf '[]'
    fi
}

write_config_metadata() {
    local count="$1" hash="$2" links="${3:-}" generated max_links payload xray_port ws_port route_quality
    route_quality=$(route_quality_json "$(config_export_route_ips "$links")")
    if ! jq -e 'type == "array" and all(.[]; type == "object")' >/dev/null 2>&1 <<< "$route_quality"; then
        route_quality='[]'
    fi
    max_links=$(safe_max_fallback_links)
    generated=$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')
    [[ "$count" =~ ^[0-9]+$ ]] || count=0
    xray_port="$XRAY_PORT"
    ws_port="$WS_PORT"
    [[ "$xray_port" =~ ^[0-9]+$ ]] || xray_port=443
    [[ "$ws_port" =~ ^[0-9]+$ ]] || ws_port=8443
    payload=$(cat <<JSON
{
  "generated_at": "$(json_escape "$generated")",
  "codespace": "$(json_escape "$CODESPACE_NAME")",
  "domain": "$(json_escape "$PORT_DOMAIN")",
  "port": ${xray_port},
  "config_count": ${count:-0},
  "max_fallback_links": ${max_links},
  "hash": "$(json_escape "$hash")",
  "mobile_config_file": "$(json_escape "$MOBILE_CONFIG_FILE")",
  "subscription_file": "$(json_escape "$SUBSCRIPTION_FILE")",
  "codespace_bundle_file": "$(json_escape "$CODESPACE_BUNDLE_FILE")",
  "subscription_scope": "local_codespace_only",
  "performance_profile": "$(json_escape "$(effective_performance_profile)")",
  "saved_performance_profile": "$(json_escape "$PERFORMANCE_PROFILE")",
  "ws_fallback": $(ws_fallback_enabled && printf true || printf false),
  "ws_port": ${ws_port},
  "ws_domain": "$(json_escape "$WS_PORT_DOMAIN")",
  "ws_front_domain": "$(json_escape "$(ws_front_domain_value 2>/dev/null || true)")",
  "low_overhead": $(low_overhead_enabled && printf true || printf false),
  "latency_focus": $(latency_focus_enabled && printf true || printf false),
  "domain_link_exported": $(domain_link_export_enabled && printf true || printf false),
  "xhttp_link_tuning": $(xhttp_link_tuning_enabled && printf true || printf false),
  "route_probe_scope": "codespace_side_options_not_client_ping",
  "routes": ${route_quality}
}
JSON
    ) || return 1
    _atomic_write "$CONFIG_META_FILE" "$payload"
}

quota_snapshot_json() {
    local now cycle stored prev ss elapsed budget
    now=$(date +%s) || return 1
    cycle=$(current_quota_cycle) || return 1
    stored=$(cat "$QUOTA_CYCLE_FILE" 2>/dev/null || true)
    [[ "$stored" == "$cycle" ]] || { printf 'null'; return 0; }
    prev=$(cat "$TOTAL_UPTIME_FILE" 2>/dev/null || true)
    ss=$(cat "$SESSION_START_FILE" 2>/dev/null || true)
    budget="${G2RAY_QUOTA_SECONDS:-216000}"
    [[ "$prev" =~ ^[0-9]{1,10}$ && "$ss" =~ ^[0-9]{1,10}$ && "$budget" =~ ^[0-9]{1,7}$ ]] \
        || { printf 'null'; return 0; }
    prev=$((10#$prev)); ss=$((10#$ss)); budget=$((10#$budget))
    (( budget > 0 && budget <= 2678400 )) || budget=216000
    elapsed=$(( now - ss )); (( elapsed < 0 )) && elapsed=0
    (( elapsed > 3600 )) && elapsed=3600
    prev=$(( prev + elapsed )); (( prev > 2678400 )) && prev=2678400
    # This snapshot runs on a live host; stopping Xray does not stop host billing.
    printf '{"used_seconds":%s,"budget_seconds":%s,"sampled_at_ms":%s,"cycle":"%s","assumed_running":true}' \
        "$prev" "$budget" "$((now * 1000))" "$cycle"
}

write_codespace_bundle_qr() {
    local bundle="$1" encoded
    command -v gzip >/dev/null 2>&1 && command -v base64 >/dev/null 2>&1 || return 1
    mkdir -p "$QR_DIR" || return 1
    encoded=$(printf '%s' "$bundle" | gzip -n -c | base64 | tr '+/' '-_' | tr -d '\r\n=') || return 1
    # Leave room for error correction. Oversized bundles retain individual QRs.
    if (( ${#encoded} > 2100 )); then
        rm -f "$QR_DIR/all-configs.txt" "$QR_DIR/all-configs.png"
        return 0
    fi
    _atomic_write "$QR_DIR/all-configs.txt" "g2ray://import/$encoded" || return 1
    chmod 600 "$QR_DIR/all-configs.txt" 2>/dev/null || true
    if command -v qrencode >/dev/null 2>&1; then
        qrencode -l M -m 4 -s 8 -t PNG -o "$QR_DIR/all-configs.png" "g2ray://import/$encoded" 2>/dev/null || return 1
        chmod 600 "$QR_DIR/all-configs.png" 2>/dev/null || true
    else
        rm -f "$QR_DIR/all-configs.png" || return 1
    fi
}

write_codespace_import_bundle() {
    local links="${1:-}" legacy="${2:-false}" generated worker_codespace_id first=true link bundle
    local bundle_file="$CODESPACE_BUNDLE_FILE" schema=2
    if [[ -z "${links//[[:space:]]/}" ]]; then
        log_event ERROR "codespace_bundle invalid_links" || true
        return 2
    fi
    if [[ "$legacy" == true ]]; then
        bundle_file="${CODESPACE_BUNDLE_FILE%.json}.legacy.json"
        schema=1
    fi
    generated=$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')
    worker_codespace_id="$(waker_metadata_value worker_codespace_id)"
    [[ -n "$worker_codespace_id" ]] || worker_codespace_id="${G2RAY_WORKER_CODESPACE_ID:-$CODESPACE_NAME}"
    bundle=$(
        printf '{\n'
        printf '  "schema_version": %s,\n' "$schema"
        [[ "$legacy" == true ]] && printf '  "compatibility": "legacy_without_xhttp_extra",\n'
        printf '  "generated_at": "%s",\n' "$(json_escape "$generated")"
        printf '  "codespace_name": "%s",\n' "$(json_escape "$CODESPACE_NAME")"
        printf '  "worker_codespace_id": "%s",\n' "$(json_escape "$worker_codespace_id")"
        if [[ "$legacy" != true ]]; then
            printf '  "quota_estimate": %s,\n' "$(quota_snapshot_json || printf 'null')"
        fi
        printf '  "configs": [\n'
        while IFS= read -r link; do
            [[ -n "$link" ]] || continue
            # Only the explicitly named legacy bundle removes advanced tuning.
            if [[ "$legacy" == true ]]; then
                link=$(printf '%s' "$link" | sed -E 's/&extra=[^&#]*//')
            fi
            if [[ "$first" != true ]]; then printf ',\n'; fi
            printf '    "%s"' "$(json_escape "$link")"
            first=false
        done <<< "$links"
        printf '\n  ]\n}\n'
    )
    _atomic_write "$bundle_file" "$bundle" || return 1
    chmod 600 "$bundle_file" 2>/dev/null || true
    if [[ "$legacy" != true ]] && ! write_codespace_bundle_qr "$bundle"; then
        rm -f "$QR_DIR/all-configs.txt" "$QR_DIR/all-configs.png" 2>/dev/null || true
        log_event WARN "codespace_bundle all_config_qr_unavailable action=use_individual_qrs" || true
    fi
    if [[ "$legacy" != true ]] && ! write_codespace_import_bundle "$links" true; then
        # The compatibility artifact must not roll back a valid current export
        # or leave an older UUID available for accidental import.
        rm -f "${CODESPACE_BUNDLE_FILE%.json}.legacy.json" 2>/dev/null || true
        log_event WARN "codespace_bundle legacy_bundle_write_failed action=use_current_bundle" || true
    fi
    return 0
}

write_config_exports_from_links() {
    local links encoded count hash
    links=$(printf '%s\n' "$@" | awk 'NF')
    [[ -n "$links" ]] || return 1
    _atomic_write "$MOBILE_CONFIG_FILE" "$links" || return 1
    if command -v base64 >/dev/null 2>&1; then
        encoded=$(printf '%s\n' "$links" | base64 | tr -d '\n')
        _atomic_write "$SUBSCRIPTION_FILE" "$encoded" || return 1
    fi
    write_codespace_import_bundle "$links" || return 1
    count=$(printf '%s\n' "$links" | awk 'NF {c++} END {print c+0}')
    hash=$(fingerprint_secret "$links")
    write_config_metadata "$count" "$hash" "$links" || return 1
    log_event INFO "config_exports refreshed count=${count} hash=${hash}"
}

clear_config_exports() {
    local reason="${1:-no_exportable_links}"
    rm -f "$MOBILE_CONFIG_FILE" "$SUBSCRIPTION_FILE" "$CONFIG_META_FILE" "$CODESPACE_BUNDLE_FILE" "${CODESPACE_BUNDLE_FILE%.json}.legacy.json" 2>/dev/null || true
    log_event WARN "config_exports cleared reason=${reason}"
}

config_link_artifacts_present() {
    [[ -s "$MOBILE_CONFIG_FILE" && -s "$CONFIG_META_FILE" ]] || return 1
    if command -v base64 >/dev/null 2>&1; then
        [[ -s "$SUBSCRIPTION_FILE" ]] || return 1
    fi
    return 0
}

config_export_artifacts_present() {
    config_link_artifacts_present || return 1
    [[ -s "$CODESPACE_BUNDLE_FILE" ]]
}

config_export_artifacts_match_current_uuid() {
    local current_uuid
    config_link_artifacts_present || return 1
    current_uuid=$(active_vless_uuid 2>/dev/null || true)
    [[ -n "$current_uuid" ]] || return 1
    awk -v prefix="vless://${current_uuid}@" '
        NF {
            found=1
            if (index($0, prefix) != 1) invalid=1
        }
        END { exit(found && !invalid ? 0 : 1) }
    ' "$MOBILE_CONFIG_FILE" 2>/dev/null
}

export_input_hash() {
    local input selected_routes uuid_fingerprint
    selected_routes=$(effective_export_route_ips | tr '\n' ',' 2>/dev/null || true)
    uuid_fingerprint=$(fingerprint_secret "$(active_vless_uuid 2>/dev/null || true)")
    input=$(
        printf 'bundle_schema=2\n'
        printf 'codespace=%s\n' "$CODESPACE_NAME"
        printf 'port_domain=%s\n' "$PORT_DOMAIN"
        printf 'ws_port_domain=%s\n' "$WS_PORT_DOMAIN"
        printf 'xray_port=%s\n' "$XRAY_PORT"
        printf 'edge_port=%s\n' "$CODESPACES_EDGE_PORT"
        printf 'ws_port=%s\n' "$WS_PORT"
        printf 'max_links=%s\n' "$(safe_max_fallback_links)"
        printf 'ws_max_links=%s\n' "$(safe_ws_fallback_links)"
        printf 'xhttp_mode=%s\n' "$(xhttp_config_mode)"
        printf 'xhttp_extra=%s\n' "$(fingerprint_secret "$(xhttp_link_extra_json 2>/dev/null || true)")"
        printf 'domain_export=%s\n' "$(domain_link_export_enabled && printf true || printf false)"
        printf 'ws_enabled=%s\n' "$(ws_fallback_enabled && printf true || printf false)"
        printf 'ws_front=%s\n' "$(ws_front_domain_value 2>/dev/null || true)"
        printf 'ws_path=%s\n' "$(ws_path_value)"
        printf 'uuid=%s\n' "$uuid_fingerprint"
        printf 'selected_routes=%s\n' "$selected_routes"
    )
    fingerprint_secret "$input"
}

_refresh_config_exports_impl() {
    active_vless_uuid >/dev/null 2>&1 || { clear_config_exports "missing_uuid"; return 1; }
    local link_array=() backup_dir="" artifact name
    mapfile -t link_array < <(generate_ordered_links | awk 'NF' || true)
    if ((${#link_array[@]} == 0)); then
        if config_export_artifacts_match_current_uuid; then
            log_event WARN "config_exports retained_previous reason=no_exportable_links"
        elif config_link_artifacts_present; then
            clear_config_exports "no_exportable_links_uuid_mismatch"
        else
            clear_config_exports "no_exportable_links"
        fi
        return 1
    fi
    if config_export_artifacts_match_current_uuid; then
        backup_dir=$(mktemp -d "$DATA_DIR/config-export-backup.XXXXXX" 2>/dev/null || true)
    fi
    if [[ -n "$backup_dir" ]]; then
        for artifact in "$MOBILE_CONFIG_FILE" "$SUBSCRIPTION_FILE" "$CONFIG_META_FILE" "$CODESPACE_BUNDLE_FILE" "${CODESPACE_BUNDLE_FILE%.json}.legacy.json"; do
            [[ -e "$artifact" ]] || continue
            name=$(basename "$artifact")
            cp -p "$artifact" "$backup_dir/$name" 2>/dev/null || true
        done
    fi
    if ! write_config_exports_from_links "${link_array[@]}"; then
        if [[ -n "$backup_dir" ]]; then
            rm -f "$MOBILE_CONFIG_FILE" "$SUBSCRIPTION_FILE" "$CONFIG_META_FILE" "$CODESPACE_BUNDLE_FILE" "${CODESPACE_BUNDLE_FILE%.json}.legacy.json" 2>/dev/null || true
            for artifact in "$MOBILE_CONFIG_FILE" "$SUBSCRIPTION_FILE" "$CONFIG_META_FILE" "$CODESPACE_BUNDLE_FILE" "${CODESPACE_BUNDLE_FILE%.json}.legacy.json"; do
                name=$(basename "$artifact")
                [[ -e "$backup_dir/$name" ]] && cp -p "$backup_dir/$name" "$artifact" 2>/dev/null || true
            done
            rm -f "$backup_dir"/* 2>/dev/null || true
            rmdir "$backup_dir" 2>/dev/null || true
            log_event WARN "config_exports restored_previous reason=write_failed"
        else
            clear_config_exports "write_failed_no_valid_backup"
        fi
        return 1
    fi
    if [[ -n "$backup_dir" ]]; then
        rm -f "$backup_dir"/* 2>/dev/null || true
        rmdir "$backup_dir" 2>/dev/null || true
    fi
    local new_hash
    new_hash=$(export_input_hash)
    _atomic_write "$EXPORT_INPUT_HASH_FILE" "$new_hash" >/dev/null 2>&1 || true
    chmod 600 "$EXPORT_INPUT_HASH_FILE" 2>/dev/null || true
}

refresh_config_exports() {
    with_file_lock "$EXPORT_LOCK_FILE" 45 _refresh_config_exports_impl "$@"
}

_refresh_config_exports_if_changed_impl() {
    active_vless_uuid >/dev/null 2>&1 || { clear_config_exports "missing_uuid"; return 1; }
    local new_hash old_hash
    new_hash=$(export_input_hash)
    old_hash=$(cat "$EXPORT_INPUT_HASH_FILE" 2>/dev/null || true)
    if [[ -n "$new_hash" && "$new_hash" == "$old_hash" ]] && config_export_artifacts_present; then
        log_event INFO "config_exports unchanged hash=${new_hash}"
        return 0
    fi
    _refresh_config_exports_impl || return 1
}

refresh_config_exports_if_changed() {
    with_file_lock "$EXPORT_LOCK_FILE" 45 _refresh_config_exports_if_changed_impl "$@"
}

kernel_tcp_value() {
    local key="${1:-}" value
    case "$key" in
        tcp_congestion_control|tcp_available_congestion_control|tcp_allowed_congestion_control|tcp_slow_start_after_idle) ;;
        *) printf 'unknown'; return 0 ;;
    esac
    value=$(cat "/proc/sys/net/ipv4/$key" 2>/dev/null | awk 'NF {$1=$1; print; exit}' || true)
    printf '%s' "${value:-unknown}"
}

runtime_tuning_diagnostics() {
    local installed expected state=unknown policy
    installed=$(timeout 3 "$XRAY_BIN" version 2>/dev/null | awk 'NR==1 && $1=="Xray" {print $2}' || true)
    expected=$(awk '$1=="ARG" && $2 ~ /^XRAY_VERSION=/ {sub(/^XRAY_VERSION=/,"",$2); print $2; exit}' \
        "$BASE_DIR/.devcontainer/Dockerfile" 2>/dev/null || true)
    installed=${installed#v}
    expected=${expected#v}
    expected=${expected%$'\r'}
    [[ "$installed" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || installed=unknown
    [[ "$expected" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || expected=unknown
    if [[ "$installed" != unknown && "$expected" != unknown ]]; then
        [[ "$installed" == "$expected" ]] && state=matches || state=different
    fi
    printf 'Xray binary: installed=%s image_pin=%s state=%s\n' "$installed" "$expected" "$state"
    if [[ "$state" == different ]]; then
        printf 'Image drift: git pull does not rebuild the container; a planned container rebuild applies its default pin.\n'
    fi
    # Read the config on disk, not the profile preference (which may be pending).
    policy=$(jq -r '(.policy.levels["0"] // {}) as $p |
        "level=0 connIdle=\($p.connIdle // "core-default") uplinkOnly=\($p.uplinkOnly // "core-default") downlinkOnly=\($p.downlinkOnly // "core-default") direct_congestion=\([.outbounds[]? | select(.tag=="direct") | .streamSettings.sockopt.tcpCongestion] | first // "kernel-default")"' \
        "$CONFIG_FILE" 2>/dev/null || true)
    printf 'Config policy: %s\n' "${policy:-unavailable}"
    printf 'TCP kernel: default=%s available=%s allowed=%s slow_start_after_idle=%s\n' \
        "$(kernel_tcp_value tcp_congestion_control)" "$(kernel_tcp_value tcp_available_congestion_control)" \
        "$(kernel_tcp_value tcp_allowed_congestion_control)" "$(kernel_tcp_value tcp_slow_start_after_idle)"
}

log_diagnostic_snapshot() {
    local reason="${1:-diagnostics}" local_probe local_ms edge_probe edge_ms engine listener supervisor ts
    local waker_url waker_codespace waker_fp waker_at tuning="${2:-}"
    [[ -n "$tuning" ]] || tuning=$(runtime_tuning_diagnostics)
    ts=$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')
    read -r local_probe local_ms _probe_reason < <(xhttp_probe_metrics local)
    read -r edge_probe edge_ms _probe_reason < <(xhttp_probe_metrics external)
    xray_running && engine=running || engine=stopped
    is_port_open && listener=open || listener=closed
    supervisor=$(background_supervisor_status | tr '\r\n' ' ' | cut -c1-180)
    log_event INFO "diagnostic_snapshot reason=${reason} engine=${engine} listener=${listener} local_probe=${local_probe:-0} local_ms=${local_ms:-0} edge_probe=${edge_probe:-0} edge_ms=${edge_ms:-0} supervisor=${supervisor:-unknown} last_good=$(last_good_route_value)"
    waker_url=$(waker_metadata_value worker_url)
    waker_codespace=$(waker_metadata_value codespace_name)
    waker_fp=$(waker_metadata_value wake_secret_fingerprint)
    waker_at=$(waker_metadata_value configured_at)
    rotate_log_file "$DIAGNOSTIC_LOG_FILE"
    {
        printf '===== Diagnostic Snapshot %s =====\n' "$ts"
        printf 'reason: %s\n' "$reason"
        printf 'identity: %s\n' "$CODESPACE_NAME"
        printf 'domain: %s\n' "$PORT_DOMAIN"
        printf 'port: %s\n' "$XRAY_PORT"
        printf 'engine: %s\n' "$engine"
        printf 'listener: %s\n' "$listener"
        printf 'local_xhttp_options: HTTP %s latency_ms=%s usable=%s\n' "${local_probe:-0}" "${local_ms:-0}" "$(xhttp_status_usable "$local_probe" && printf true || printf false)"
        printf 'edge_xhttp_options: HTTP %s latency_ms=%s usable=%s\n' "${edge_probe:-0}" "${edge_ms:-0}" "$(xhttp_status_usable "$edge_probe" && printf true || printf false)"
        printf 'supervisor: %s\n' "${supervisor:-unknown}"
        printf 'self_heal: %s\n' "$(self_heal_state_summary)"
        printf 'runtime_tuning:\n'
        printf '%s\n' "$tuning" | sed 's/^/  /'
        printf 'resume_gap:\n'
        resume_gap_summary | sed 's/^/  /'
        printf 'last_known_state:\n'
        last_known_state_summary | sed 's/^/  /'
        printf 'external_waker:\n'
        if [[ -n "$waker_url" ]]; then
            printf '  status: configured\n'
            printf '  worker_url: %s\n' "$waker_url"
            printf '  codespace: %s\n' "${waker_codespace:-$CODESPACE_NAME}"
            printf '  secret: fingerprint=%s raw_secret_stored=false\n' "${waker_fp:-unknown}"
            printf '  last_setup: %s\n' "${waker_at:-unknown}"
        else
            printf '  status: not configured\n'
        fi
        printf 'last_good_route:\n'
        last_good_route_summary | sed 's/^/  /'
        printf 'route_settling_history:\n'
        route_settling_history_summary | sed 's/^/  /'
        printf 'recent_events:\n'
        if [[ -s "$LOG_FILE" ]]; then
            tail -n 12 "$LOG_FILE" 2>/dev/null | sed 's/^/  /'
        else
            printf '  none\n'
        fi
        printf '\n'
    } | redact_sensitive_text >> "$DIAGNOSTIC_LOG_FILE" 2>/dev/null || true
    chmod 600 "$DIAGNOSTIC_LOG_FILE" 2>/dev/null || true
}

redact_sensitive_text() {
    sed -E \
        -e "s#vless://[^[:space:]\"'{},]+#<vless-redacted>#g" \
        -e "s#(\"?authorization\"?[[:space:]]*:[[:space:]]*\"?bearer[[:space:]]+)[^\"'[:space:],}]+#\\1<bearer-redacted>#Ig" \
        -e "s#(\"?GITHUB_TOKEN\"?[[:space:]]*[:=][[:space:]]*\"?)[^\"'[:space:],}]+#\\1<github-token-redacted>#Ig" \
        -e "s#(\"?token\"?[[:space:]]*:[[:space:]]*\"?)[^\"'[:space:],}]+#\\1<token-redacted>#Ig" \
        -e "s#(\"?WAKE_SECRET\"?[[:space:]]*[:=][[:space:]]*\"?)[^\"'[:space:],}]+#\\1<wake-secret-redacted>#Ig" \
        -e "s#(\"?wake_secret\"?[[:space:]]*[:=][[:space:]]*\"?)[^\"'[:space:],}]+#\\1<wake-secret-redacted>#Ig" \
        -e 's#github_pat_[A-Za-z0-9_]+#github_pat_<redacted>#g' \
        -e 's#gh[pousr]_[A-Za-z0-9_]+#gh_<redacted>#g' \
        -e 's#[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}#<uuid-redacted>#g' \
        -e 's#(^|[^0-9A-Fa-f])([0-9A-Fa-f]{48,})([^0-9A-Fa-f]|$)#\1<hex-secret-redacted>\3#g'
}

sed_literal_pattern() {
    printf '%s' "${1:-}" | sed -E 's#[][\\.^$*+?{}|()/#]#\\&#g'
}

redact_support_text() {
    local codespace_re domain_re sed_args=()
    if support_include_network_enabled; then
        redact_sensitive_text
        return 0
    fi
    codespace_re=$(sed_literal_pattern "$CODESPACE_NAME")
    domain_re=$(sed_literal_pattern "$PORT_DOMAIN")
    [[ -n "$codespace_re" ]] && sed_args+=(-e "s#${codespace_re}#<codespace-redacted>#g")
    [[ -n "$domain_re" ]] && sed_args+=(-e "s#${domain_re}#<codespaces-domain-redacted>#g")
    redact_sensitive_text | sed -E \
        "${sed_args[@]}" \
        -e 's#[A-Za-z0-9][A-Za-z0-9-]*-[0-9]+\.app\.github\.dev#<codespaces-domain-redacted>#g' \
        -e 's#(^|[^0-9])([0-9]{1,3}\.){3}[0-9]{1,3}([^0-9]|$)#\1<ip-redacted>\3#g'
}

copy_redacted_file() {
    local src="$1" dst="$2"
    mkdir -p "$(dirname "$dst")" 2>/dev/null || true
    if [[ -f "$src" ]]; then
        if [[ -r "$src" ]] && redact_support_text < "$src" > "$dst" 2>/dev/null; then
            :
        else
            printf 'unreadable: %s\n' "$src" > "$dst" 2>/dev/null || true
        fi
    else
        printf 'missing: %s\n' "$src" > "$dst"
    fi
    chmod 600 "$dst" 2>/dev/null || true
}

copy_redacted_log_family() {
    local src="$1" dst="$2" keep="$LOG_ROTATE_KEEP" i
    [[ "$keep" =~ ^[0-9]+$ && "$keep" -gt 0 ]] || keep=3
    copy_redacted_file "$src" "$dst"
    for ((i=1; i<=keep; i++)); do
        [[ -e "${src}.${i}" ]] || continue
        copy_redacted_file "${src}.${i}" "${dst}.${i}"
    done
}

create_support_bundle() {
    local ts out tmp git_rev xray_ver
    ts=$(date -u '+%Y%m%dT%H%M%SZ' 2>/dev/null || date '+%Y%m%dT%H%M%S')
    out="${1:-$LOG_DIR/g2ray-support-${ts}.tar.gz}"
    tmp=$(mktemp -d "$DATA_DIR/support-bundle.XXXXXX") || return 1
    mkdir -p "$tmp/logs" "$tmp/state" "$tmp/runtime" 2>/dev/null || { rm -rf "$tmp"; return 1; }

    git_rev=$(git -C "$BASE_DIR" log --oneline -1 2>/dev/null || printf 'unknown')
    xray_ver="unknown"
    if [[ -x "$XRAY_BIN" ]]; then
        xray_ver=$("$XRAY_BIN" version 2>/dev/null | sed -n '1p' || true)
        [[ -n "$xray_ver" ]] || xray_ver="unknown"
    fi
    {
        printf 'created_at=%s\n' "$ts"
        printf 'project=%s\n' "$PROJECT_REPO"
        printf 'git=%s\n' "$git_rev"
        printf 'codespace=%s\n' "$CODESPACE_NAME"
        printf 'domain=%s\n' "$PORT_DOMAIN"
        printf 'port=%s\n' "$XRAY_PORT"
        printf 'xray=%s\n' "$xray_ver"
        printf 'note=Sensitive VLESS links, UUIDs, bearer tokens, GitHub tokens, wake secrets, and network identifiers are redacted by default. Set G2RAY_SUPPORT_INCLUDE_NETWORK=1 only when you intentionally need full route details.\n'
    } | redact_support_text > "$tmp/metadata.txt"

    print_doctor_json | redact_support_text > "$tmp/doctor.json" 2>/dev/null || printf '{}\n' > "$tmp/doctor.json"
    copy_redacted_log_family "$LOG_FILE" "$tmp/logs/g2ray.log"
    copy_redacted_log_family "$STRUCTURED_LOG_FILE" "$tmp/logs/g2ray-events.jsonl"
    copy_redacted_log_family "$DIAGNOSTIC_LOG_FILE" "$tmp/logs/g2ray-diagnostics.log"
    copy_redacted_log_family "$ROUTE_SETTLE_LOG_FILE" "$tmp/logs/route-settle.log"
    copy_redacted_log_family "$LOG_DIR/xray.log" "$tmp/logs/xray.log"
    copy_redacted_log_family "$LOG_DIR/xray-error.log" "$tmp/logs/xray-error.log"
    copy_redacted_file "$ROUTE_HEALTH_FILE" "$tmp/state/route_candidate_health.tsv"
    copy_redacted_file "$ROUTE_STATS_FILE" "$tmp/state/route_candidate_stats.tsv"
    copy_redacted_file "$ROUTE_LATENCY_BASELINE_FILE" "$tmp/state/route_latency_baseline"
    copy_redacted_file "$ROUTE_DEGRADED_REQUEST_FILE" "$tmp/state/route_degraded_request"
    copy_redacted_file "$ROUTE_DEGRADED_REFRESH_STAMP_FILE" "$tmp/state/route_degraded_refresh_stamp"
    copy_redacted_log_family "$UPDATE_AUDIT_FILE" "$tmp/state/update_audit.log"
    copy_redacted_file "$LAST_GOOD_ROUTE_FILE" "$tmp/state/last_good_route.txt"
    copy_redacted_file "$DNS_CANDIDATE_CACHE_FILE" "$tmp/state/dns_candidate_cache.tsv"
    copy_redacted_file "$ROUTE_SETTLING_HISTORY_FILE" "$tmp/state/route_settling_history.tsv"
    copy_redacted_file "$PINNED_ROUTE_FILE" "$tmp/state/pinned_route.txt"
    copy_redacted_file "$MANUAL_ROUTE_CANDIDATES_FILE" "$tmp/state/manual_route_candidates.txt"
    copy_redacted_file "$BLACKLISTED_ROUTE_CANDIDATES_FILE" "$tmp/state/blacklisted_route_candidates.txt"
    copy_redacted_file "$DOMAIN_LINK_EXPORT_FILE" "$tmp/state/export_domain_link.txt"
    copy_redacted_file "$XHTTP_MODE_FILE" "$tmp/state/xhttp_mode.txt"
    copy_redacted_file "$WS_FALLBACK_FILE" "$tmp/state/ws_fallback_mode"
    copy_redacted_file "$WS_FALLBACK_DISABLED_FILE" "$tmp/state/ws_fallback_mode_disabled"
    copy_redacted_file "$WS_FRONT_DOMAIN_FILE" "$tmp/state/ws_front_domain.txt"
    copy_redacted_file "$WAKER_METADATA_FILE" "$tmp/state/waker_metadata.txt"
    route_candidate_health_summary | redact_support_text > "$tmp/runtime/route_candidates.txt" 2>/dev/null || true
    route_settling_history_summary | redact_support_text > "$tmp/runtime/route_settling_summary.txt" 2>/dev/null || true
    last_known_state_summary | redact_support_text > "$tmp/runtime/last_known_state.txt" 2>/dev/null || true

    mkdir -p "$(dirname "$out")" 2>/dev/null || { rm -rf "$tmp"; return 1; }
    if tar -C "$tmp" -czf "$out" .; then
        chmod 600 "$out" 2>/dev/null || true
        rm -rf "$tmp"
        log_event INFO "support_bundle created path=${out}"
        printf '%s\n' "$out"
        return 0
    fi
    rm -rf "$tmp"
    return 1
}

show_diagnostics() {
    refresh_screen
    log_event INFO "diagnostics opened"
    local runtime_tuning
    runtime_tuning=$(runtime_tuning_diagnostics)
    log_diagnostic_snapshot "interactive" "$runtime_tuning"
    echo -e "\n  ${RED}● Diagnostics${NC}\n"
    echo -e "  Identity : ${WHITE}${CODESPACE_NAME}${NC}"
    echo -e "  Domain   : ${WHITE}${PORT_DOMAIN}${NC}"
    echo -e "  Port     : ${WHITE}${XRAY_PORT}${NC}"
    if command -v git >/dev/null 2>&1; then
        echo -e "  Git      : ${DIM}$(git -C "$BASE_DIR" log --oneline -1 2>/dev/null || echo unknown)${NC}"
    fi
    if xray_running; then
        local xpid; xpid=$(cat "$XRAY_PID_FILE" 2>/dev/null || true)
        if [[ -z "$xpid" ]] && command -v pgrep >/dev/null 2>&1; then
            xpid=$(pgrep -f "$XRAY_BIN run -c $CONFIG_FILE" 2>/dev/null | head -1 || true)
        fi
        echo -e "  Engine   : ${GREEN}Running${NC} ${DIM}(PID ${xpid:-unknown})${NC}"
    else
        echo -e "  Engine   : ${RED}Stopped${NC}"
    fi
    is_port_open \
        && echo -e "  Listener : ${GREEN}Port ${XRAY_PORT} open${NC}" \
        || echo -e "  Listener : ${RED}Port ${XRAY_PORT} closed${NC}"
    if [[ -f "$CONFIG_FILE" ]] && command -v jq >/dev/null 2>&1; then
        local cfg_line cfg_network cfg_security cfg_path cfg_mode cfg_uuid
        cfg_line=$(jq -r '.inbounds[]? | select(.tag=="vless-in") |
            [.streamSettings.network // "-", .streamSettings.security // "-",
             .streamSettings.xhttpSettings.path // "-", .streamSettings.xhttpSettings.mode // "-",
             .settings.clients[0].id // ""] | @tsv' "$CONFIG_FILE" 2>/dev/null | head -1)
        if [[ -n "$cfg_line" ]]; then
            IFS=$'\t' read -r cfg_network cfg_security cfg_path cfg_mode cfg_uuid <<< "$cfg_line"
            echo -e "  Config    : ${WHITE}${cfg_network}/${cfg_security}${NC} ${DIM}path=${cfg_path} mode=${cfg_mode} uuid_hash=$(fingerprint_secret "$cfg_uuid")${NC}"
        fi
        if ws_fallback_in_config; then
            local ws_cfg_path="-"
            ws_cfg_path=$(jq -r '.inbounds[]? | select(.tag=="vless-ws") | .streamSettings.wsSettings.path // "-"' "$CONFIG_FILE" 2>/dev/null | head -1)
            if ws_fallback_enabled; then
                echo -e "  WS Fallback: ${WHITE}enabled/configured${NC} ${DIM}port=${WS_PORT} domain=${WS_PORT_DOMAIN} path=${ws_cfg_path:-/ws}${NC}"
            else
                echo -e "  WS Fallback: ${YELLOW}present but panel-disabled${NC} ${DIM}regenerate config to remove port=${WS_PORT}${NC}"
            fi
        elif ws_fallback_enabled; then
            echo -e "  WS Fallback: ${YELLOW}enabled, pending config regenerate${NC} ${DIM}port=${WS_PORT} domain=${WS_PORT_DOMAIN} path=$(ws_path_value)${NC}"
        fi
    fi
    if [[ -f "$CONFIG_FILE" ]]; then
        local tfo_cfg="off" tfo_kernel tfo_kernel_note="unknown"
        if command -v jq >/dev/null 2>&1; then
            [[ "$(jq -r '[.outbounds[]? | select(.tag=="direct") | .streamSettings.sockopt.tcpFastOpen] | first // false' "$CONFIG_FILE" 2>/dev/null)" == "true" ]] && tfo_cfg="on"
        elif grep -Fq '"tcpFastOpen": true' "$CONFIG_FILE" 2>/dev/null; then
            tfo_cfg="on"
        fi
        tfo_kernel=$(cat /proc/sys/net/ipv4/tcp_fastopen 2>/dev/null || printf '?')
        if [[ "$tfo_kernel" =~ ^[0-9]+$ ]]; then
            (( (tfo_kernel & 1) == 1 )) && tfo_kernel_note="client-supported" || tfo_kernel_note="not-supported"
        fi
        echo -e "  TCP FastOpen: ${WHITE}${tfo_cfg}${NC} ${DIM}(outbound dials; kernel tcp_fastopen=${tfo_kernel} ${tfo_kernel_note})${NC}"
    fi

    echo -e "\n  ${WHITE}${B}Background Supervisor${NC}"
    echo -e "  ${DIM}$(background_supervisor_status)${NC}"

    echo -e "\n  ${WHITE}${B}Codespaces Ports${NC}"
    if command -v gh >/dev/null 2>&1; then
        run_gh codespace ports -c "$CODESPACE_NAME" 2>/dev/null | sed 's/^/  /' \
            || echo -e "  ${YELLOW}Could not query gh codespace ports.${NC}"
    else
        echo -e "  ${DIM}gh CLI unavailable.${NC}"
    fi

    echo -e "\n  ${WHITE}${B}XHTTP Probes${NC}"
    local local_probe local_ms edge_probe edge_ms local_usable=false edge_usable=false
    read -r local_probe local_ms _probe_reason < <(xhttp_probe_metrics local)
    read -r edge_probe edge_ms _probe_reason < <(xhttp_probe_metrics external)
    xhttp_status_usable "$local_probe" && local_usable=true
    xhttp_status_usable "$edge_probe" && edge_usable=true
    echo -e "  Local OPTIONS : ${WHITE}HTTP ${local_probe}${NC} ${DIM}(${local_ms:-0}ms usable=${local_usable})${NC}"
    echo -e "  Edge OPTIONS  : ${WHITE}HTTP ${edge_probe}${NC} ${DIM}(${edge_ms:-0}ms usable=${edge_usable})${NC}"
    echo -e "  ${DIM}HTTP 404 here means the Codespaces edge has not routed this Host/path to Xray yet.${NC}"

    echo -e "\n  ${WHITE}${B}Self-Heal State${NC}"
    echo -e "  ${DIM}$(self_heal_state_summary)${NC}"

    echo -e "\n  ${WHITE}${B}Resume Gap${NC}"
    resume_gap_summary | sed 's/^/  /'

    echo -e "\n  ${WHITE}${B}Boot Status${NC}"
    boot_status_summary | sed 's/^/  /'

    echo -e "\n  ${WHITE}${B}Headless Route Settling${NC}"
    route_settle_monitor_status | sed 's/^/  /'

    echo -e "\n  ${WHITE}${B}Last Known State${NC}"
    last_known_state_summary | sed 's/^/  /'

    echo -e "\n  ${WHITE}${B}External Waker${NC}"
    waker_metadata_summary | sed 's/^/  /'

    echo -e "\n  ${WHITE}${B}Fallback IP Candidates${NC}"
    echo -e "  ${DIM}(includes resolved, manual, and built-in fallbacks)${NC}"
    local ips; ips=$(resolve_domain_ips "$PORT_DOMAIN" || true)
    if [[ -n "$ips" ]]; then
        printf '%s\n' "$ips" | sed 's/^/  /'
    else
        echo -e "  ${YELLOW}No IP candidates resolved from this environment.${NC}"
    fi

    echo -e "\n  ${WHITE}${B}Fallback Route Probes${NC}"
    if [[ -n "$ips" ]]; then
        local ip ip_probe ip_ms ip_usable probed=0 max_probe="$DIAGNOSTIC_MAX_FALLBACK_PROBES"
        [[ "$max_probe" =~ ^[0-9]+$ && "$max_probe" -gt 0 ]] || max_probe=12
        while IFS= read -r ip; do
            [[ -n "$ip" ]] || continue
            if (( probed >= max_probe )); then
                echo -e "  ${DIM}...additional candidates skipped; set G2RAY_DIAGNOSTIC_MAX_FALLBACK_PROBES to raise this cap.${NC}"
                break
            fi
            read -r ip_probe ip_ms _probe_reason < <(xhttp_probe_metrics external "$ip")
            ip_usable=false
            xhttp_status_usable "$ip_probe" && ip_usable=true
            printf '  %-15s HTTP %-3s %4sms usable=%s\n' "$ip" "${ip_probe:-0}" "${ip_ms:-0}" "$ip_usable"
            probed=$((probed + 1))
        done <<< "$ips"
    else
        echo -e "  ${DIM}No fallback IPs to probe.${NC}"
    fi

    echo -e "\n  ${WHITE}${B}Best Route Candidates${NC}"
    route_candidate_health_summary | sed 's/^/  /'

    echo -e "\n  ${WHITE}${B}Last Good Route${NC}"
    last_good_route_summary | sed 's/^/  /'

    echo -e "\n  ${WHITE}${B}Route Settling History${NC}"
    route_settling_history_summary | sed 's/^/  /'

    echo -e "\n  ${WHITE}${B}Persistent Logs${NC}"
    echo -e "  App events      : ${WHITE}${LOG_FILE}${NC}"
    echo -e "  Structured JSONL: ${WHITE}${STRUCTURED_LOG_FILE}${NC}"
    echo -e "  Diagnostics    : ${WHITE}${DIAGNOSTIC_LOG_FILE}${NC}"
    echo -e "  Xray errors     : ${WHITE}${LOG_DIR}/xray-error.log${NC}"
    echo -e "  ${DIM}These files persist across panel screens and are rotated by size.${NC}"

    echo -e "\n  ${WHITE}${B}Runtime Tuning${NC}"
    printf '%s\n' "$runtime_tuning" | sed 's/^/  /'
    echo -e "  Performance profile : ${WHITE}$(effective_performance_profile)${NC} ${DIM}(saved=${PERFORMANCE_PROFILE})${NC}"
    echo -e "  Low-overhead mode   : ${WHITE}$(low_overhead_summary)${NC}"
    echo -e "  Latency focus mode  : ${WHITE}$(latency_focus_summary)${NC}"
    echo -e "  Local subscription file : ${WHITE}${SUBSCRIPTION_FILE}${NC}"
    echo -e "  ${DIM}Generated exports stay local in this Codespace and are ignored by git.${NC}"

    echo -e "\n  ${WHITE}${B}Recent G2ray Events${NC}"
    if [[ -s "$LOG_FILE" ]]; then
        tail -n 18 "$LOG_FILE" | sed 's/^/  /'
    else
        echo -e "  ${DIM}No G2ray app events yet.${NC}"
    fi

    echo -e "\n  ${WHITE}${B}Recent Xray Errors${NC}"
    if [[ -s "$LOG_DIR/xray-error.log" ]]; then
        tail -n 10 "$LOG_DIR/xray-error.log" | sed 's/^/  /'
    else
        echo -e "  ${DIM}No Xray errors logged.${NC}"
    fi
    echo ""; echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
}

_recover_now_ensure_engine_impl() {
    RECOVER_ENGINE_RESULT="failed"
    if xray_listener_ready; then
        RECOVER_ENGINE_RESULT="running"
        return 0
    fi
    if start_xray >/dev/null 2>&1 && wait_for_port >/dev/null 2>&1 && xray_listener_ready; then
        RECOVER_ENGINE_RESULT="started"
        return 0
    fi
    return 1
}

_recover_now_impl() {
    local no_prompt="${1:-}" failed=0 expose_failed=false route_ready=false engine_started=false engine_ready=false xcode=0 xms=0 RECOVER_ENGINE_RESULT=""
    log_event INFO "recover_now begin no_prompt=${no_prompt:-false}"
    echo -e "\n  ${GREEN}*${NC} ${WHITE}Running Soft Recover Sequence...${NC}\n"

    echo -ne "  ${DIM}|-${NC} Detect Identity   : "
    CODESPACE_NAME=$(_detect_codespace_name 2>/dev/null || true)
    refresh_port_domains
    log_event INFO "recover_now identity codespace=${CODESPACE_NAME} domain=${PORT_DOMAIN}"
    [[ "$CODESPACE_NAME" == "unknown-codespace" ]] \
        && echo -e "${RED}Failed${NC}" \
        || echo -e "${GREEN}${CODESPACE_NAME}${NC}"

    echo -ne "  ${DIM}|-${NC} Verify Engine     : "
    RECOVER_ENGINE_RESULT="failed"
    if with_runtime_lock _recover_now_ensure_engine_impl; then
        engine_ready=true
        if [[ "$RECOVER_ENGINE_RESULT" == "started" ]]; then
            engine_ready=true
            engine_started=true
            log_event INFO "recover_now engine_started port=${XRAY_PORT}"
            echo -e "${GREEN}Started${NC}"
        else
            echo -e "${GREEN}Running${NC}"
        fi
    else
        failed=1
        log_event ERROR "recover_now engine_unavailable port=${XRAY_PORT}"
        echo -e "${RED}Failed${NC}"
    fi

    if [[ "$engine_ready" != true ]]; then
        log_diagnostic_snapshot "recover_now_engine_failed"
        log_event ERROR "recover_now aborted engine_unavailable route_work_skipped"
        echo -e "\n  ${RED}Engine is unavailable, so route recovery was skipped. Check engine logs, then start or restart the engine.${NC}\n"
        if [[ "$no_prompt" == "--no-prompt" ]]; then
            return 1
        fi
        echo -ne "  ${GREEN}Run hard restart now?${NC} (y/n): "
        local hard_after_engine_failure
        read -r hard_after_engine_failure
        if [[ "$hard_after_engine_failure" =~ ^[Yy]$ ]]; then
            force_reconnect
            return $?
        fi
        return 1
    fi

    echo -ne "  ${DIM}|-${NC} Expose Tunnel     : "
    if ensure_codespace_port_public force >/dev/null 2>&1; then
        ensure_optional_ws_port_public force >/dev/null 2>&1 \
            || log_event WARN "recover_now ws_port_public_failed port=${WS_PORT}"
        echo -e "${GREEN}Done${NC}"
    else
        expose_failed=true
        failed=1
        log_event WARN "recover_now expose_tunnel failed port=${XRAY_PORT}"
        echo -e "${YELLOW}Needs PORTS tab${NC}"
    fi

    echo -ne "  ${DIM}|-${NC} Wait Route        : "
    if wait_for_xhttp_route_ready "recover_now" "$ROUTE_WAIT_SEC" >/dev/null 2>&1; then
        read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
        route_ready=true
        echo -e "${GREEN}Ready (HTTP ${xcode})${NC}"
    else
        read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
        echo -e "${YELLOW}Settling (HTTP ${xcode:-0})${NC}"
    fi

    if [[ "$route_ready" != true ]]; then
        echo -ne "  ${DIM}|-${NC} Repair Route      : "
        repair_codespace_port_route >/dev/null 2>&1 || true
        if wait_for_xhttp_route_ready "recover_now_repair" "$FORCE_RECONNECT_ROUTE_WAIT_SEC" >/dev/null 2>&1; then
            read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
            route_ready=true
            echo -e "${GREEN}Ready (HTTP ${xcode})${NC}"
        else
            read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
            echo -e "${YELLOW}Still settling (HTTP ${xcode:-0})${NC}"
        fi
    fi

    echo -ne "  ${DIM}|-${NC} Refresh Routes    : "
    refresh_route_candidate_health >/dev/null 2>&1 || true
    echo -e "${GREEN}Done${NC}"

    echo -ne "  ${DIM}\\-${NC} Refresh Exports   : "
    if refresh_config_exports >/dev/null 2>&1; then
        echo -e "${GREEN}Done${NC}"
    else
        log_event WARN "recover_now export_refresh_failed"
        echo -e "${YELLOW}Skipped${NC}"
    fi

    log_diagnostic_snapshot "recover_now"
    log_event INFO "recover_now complete route_ready=${route_ready} xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} engine_started=${engine_started} expose_failed=${expose_failed}"

    if [[ "$route_ready" == true ]]; then
        failed=0
        reset_route_bad_count
        reset_edge_bad_count
        echo -e "\n  ${GREEN}Soft recover complete. Try the same config again.${NC}\n"
    else
        failed=1
        echo -e "\n  ${YELLOW}Route is still settling. Waiting usually fixes this; hard restart is available if it stays stuck.${NC}\n"
    fi

    if [[ "$no_prompt" == "--no-prompt" ]]; then
        return "$failed"
    fi

    if [[ "$route_ready" != true ]]; then
        echo -ne "  ${GREEN}Run hard restart now?${NC} (y/n): "
        local hard
        read -r hard
        if [[ "$hard" =~ ^[Yy]$ ]]; then
            force_reconnect
            return $?
        fi
    else
        echo -ne "  ${DIM}Press Enter to return...${NC}"
        read -r
    fi
    return "$failed"
}

recover_now() {
    _recover_now_impl "$@"
}

recover_now_json() {
    local recover_rc=0 rc=0 xcode=0 xms=0 route_ready=false engine=false listener=false status next_action next_action_code ok_bool=false
    if recover_now --no-prompt >/dev/null 2>&1; then
        recover_rc=0
    else
        recover_rc=$?
    fi
    read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
    if xhttp_status_usable "$xcode"; then
        route_ready=true
        status="ready"
        rc=0
    elif [[ "$xcode" == "404" ]]; then
        status="settling"
        rc="$recover_rc"
    else
        status="failed"
        rc="$recover_rc"
    fi
    [[ "$route_ready" != true && "$rc" -eq 0 ]] && rc=1
    xray_running && engine=true
    is_port_open && listener=true
    if [[ "$route_ready" == true ]]; then
        next_action_code="retry_vless_config"
    elif [[ "$xcode" == "404" ]]; then
        next_action_code="wait_route_or_recover"
    else
        next_action_code=$(panel_next_action_code "$engine" "$listener" "$xcode" true)
    fi
    next_action=$(panel_next_action_text "$next_action_code")
    [[ "$route_ready" == true && "$rc" -eq 0 ]] && ok_bool=true
    log_event INFO "recover_now_json requested ok=${ok_bool} status=${status} route_ready=${route_ready} xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} rc=${rc} recover_rc=${recover_rc}"
    cat <<JSON
{
  "ok": ${ok_bool},
  "status": "$(json_escape "$status")",
  "route_ready": ${route_ready},
  "engine_running": ${engine},
  "listener_open": ${listener},
  "edge_probe": {"http_status": ${xcode:-0}, "latency_ms": ${xms:-0}, "usable": $(xhttp_status_usable "$xcode" && printf true || printf false)},
  "exit_code": ${rc},
  "recover_exit_code": ${recover_rc},
  "next_action_code": "$(json_escape "$next_action_code")",
  "next_action": "$(json_escape "$next_action")",
  "log_file": "$(json_escape "$LOG_FILE")",
  "structured_log_file": "$(json_escape "$STRUCTURED_LOG_FILE")",
  "diagnostic_log_file": "$(json_escape "$DIAGNOSTIC_LOG_FILE")"
}
JSON
    return "$rc"
}

_force_reconnect_impl() {
    local no_prompt="${1:-}" failed=0 expose_failed=false hard_failed=false
    local verify_mode="${2:-}"
    log_event INFO "force_reconnect begin no_prompt=${no_prompt:-false}"
    echo -e "\n  ${GREEN}⠋${NC} ${WHITE}Running Clean Hard Restart & Reconnect Sequence...${NC}\n"

    echo -ne "  ${DIM}├─${NC} Detect Identity   : "
    CODESPACE_NAME=$(_detect_codespace_name 2>/dev/null || true)
    refresh_port_domains
    log_event INFO "force_reconnect identity codespace=${CODESPACE_NAME} domain=${PORT_DOMAIN}"
    [[ "$CODESPACE_NAME" == "unknown-codespace" ]] \
        && echo -e "${RED}Failed${NC}" \
        || echo -e "${GREEN}${CODESPACE_NAME}${NC}"
    if [[ "$CODESPACE_NAME" == "unknown-codespace" ]]; then
        log_event ERROR "force_reconnect identity_unknown action=abort"
        echo -e "\n  ${YELLOW}Codespaces identity is unavailable, so hard reconnect was skipped.${NC}\n"
        [[ "$no_prompt" == "--no-prompt" ]] && return 1
        echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
        return 1
    fi

    echo -ne "  ${DIM}├─${NC} Force Kill Engine : "
    if stop_xray >/dev/null 2>&1; then
        log_event INFO "force_reconnect stopped_previous_engine"
        echo -e "${GREEN}Done${NC}"
    else
        failed=1
        hard_failed=true
        log_event ERROR "force_reconnect stop_engine failed"
        echo -e "${RED}Failed${NC}"
    fi

    echo -ne "  ${DIM}├─${NC} Start Engine      : "
    if start_xray >/dev/null 2>&1 && wait_for_port >/dev/null 2>&1; then
        log_event INFO "force_reconnect start_engine ok port=${XRAY_PORT}"
        echo -e "${GREEN}OK${NC}"
    else
        failed=1
        hard_failed=true
        log_event ERROR "force_reconnect start_engine failed port=${XRAY_PORT}"
        echo -e "${RED}Failed${NC}"
    fi

    echo -ne "  ${DIM}├─${NC} Expose Tunnel     : "
    if ensure_codespace_port_public force >/dev/null 2>&1; then
        ensure_optional_ws_port_public force >/dev/null 2>&1 \
            || log_event WARN "force_reconnect ws_port_public_failed port=${WS_PORT}"
        log_event INFO "force_reconnect expose_tunnel ok port=${XRAY_PORT}"
        echo -e "${GREEN}Done${NC}"
    else
        expose_failed=true
        failed=1
        log_event WARN "force_reconnect expose_tunnel failed port=${XRAY_PORT}"
        echo -e "${YELLOW}Needs PORTS tab${NC}"
    fi

    # The supervisor has a short deadline. Let the existing bounded monitor
    # verify the edge after this operation releases its runtime lock.
    if [[ "$no_prompt" == "--no-prompt" && "$verify_mode" == "--defer-route-check" ]]; then
        [[ "$hard_failed" == false ]] || return 1
        if ! start_headless_route_settling_monitor "self_heal_reconnect"; then
            log_event ERROR "force_reconnect background_verify_failed"
            return 1
        fi
        log_event INFO "force_reconnect verify_external action=background_settle route_ready=unverified"
        return "$failed"
    fi

    echo -ne "  ${DIM}╰─${NC} Verify External   : "
    local edge_reachable=false xhttp_route_usable=false code xcode xms
    for _i in 1 2 3 4; do
        code=$(curl_http_code "https://${PORT_DOMAIN}" 5)
        [[ "$code" != "000" && "$code" != "0" ]] && edge_reachable=true
        read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
        if xhttp_status_usable "$xcode"; then
            xhttp_route_usable=true
            break
        fi
        sleep 2
    done
    if [[ "$edge_reachable" == true && "$xhttp_route_usable" != true ]]; then
        repair_codespace_port_route >/dev/null 2>&1 || true
        sleep 3
        if wait_for_xhttp_route_ready "force_reconnect" "$FORCE_RECONNECT_ROUTE_WAIT_SEC" >/dev/null 2>&1; then
            read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
            xhttp_route_usable=true
        else
            read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
            xhttp_status_usable "$xcode" && xhttp_route_usable=true
        fi
    fi
    log_event INFO "force_reconnect verify_external edge_reachable=${edge_reachable} code=${code:-none} xhttp_probe=${xcode:-none} xhttp_probe_ms=${xms:-0} xhttp_route_usable=${xhttp_route_usable} domain=${PORT_DOMAIN}"
    [[ "$edge_reachable" == true && "$xhttp_route_usable" == true ]] || failed=1
    if [[ "$xhttp_route_usable" == true ]]; then
        [[ "$expose_failed" == true && "$hard_failed" != true ]] && failed=0
        reset_route_bad_count
        echo -e "${GREEN}XHTTP route usable (HTTP ${xcode})${NC}\n"
    elif [[ "$edge_reachable" == true ]]; then
        echo -e "${YELLOW}Edge reachable but route settling (HTTP ${xcode:-0})${NC}\n"
    else
        echo -e "${YELLOW}Pending / delayed (HTTP ${code:-0})${NC}\n"
    fi

    [[ "$no_prompt" == "--no-prompt" ]] && { sleep 1; return "$failed"; }
    echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
    return "$failed"
}

force_reconnect() {
    with_runtime_lock _force_reconnect_impl "$@"
}

_ensure_runtime_ready_impl() {
    local reason="${1:-startup}" xcode xms
    record_resume_gap "$reason"
    [[ -f "$CONFIG_FILE" ]] || return 0

    CODESPACE_NAME=$(_detect_codespace_name 2>/dev/null || true)
    refresh_port_domains

    if xray_listener_ready; then
        read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
        if xhttp_status_usable "$xcode"; then
            ensure_codespace_port_public >/dev/null 2>&1 \
                || log_event WARN "runtime_ready reason=${reason} port_public_failed port=${XRAY_PORT}"
            ensure_optional_ws_port_public >/dev/null 2>&1 \
                || log_event WARN "runtime_ready reason=${reason} ws_port_public_failed port=${WS_PORT}"
            reset_route_bad_count
            reset_edge_bad_count
            log_event INFO "runtime_ready reason=${reason} engine=running xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} action=skip_reconnect"
            return 0
        fi

        if [[ "$reason" == "interactive_attach" ]]; then
            log_event WARN "runtime_ready reason=${reason} route_unusable xhttp_probe=${xcode:-0} action=background_settle"
            start_headless_route_settling_monitor "$reason" || true
            return 1
        fi
        log_event WARN "runtime_ready reason=${reason} route_unusable xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} action=repair"
        repair_codespace_port_route >/dev/null 2>&1 || true
        if wait_for_xhttp_route_ready "$reason"; then
            reset_route_bad_count
            log_event INFO "runtime_ready reason=${reason} route_repaired"
            return 0
        fi
        read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
        log_event WARN "runtime_ready reason=${reason} route_still_unusable xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} action=observe"
        return 1
    fi

    log_event WARN "runtime_ready reason=${reason} engine_not_ready action=start"
    if start_xray >/dev/null 2>&1 && wait_for_port >/dev/null 2>&1; then
        ensure_codespace_port_public >/dev/null 2>&1 \
            || log_event WARN "runtime_ready reason=${reason} port_public_failed port=${XRAY_PORT}"
        ensure_optional_ws_port_public >/dev/null 2>&1 \
            || log_event WARN "runtime_ready reason=${reason} ws_port_public_failed port=${WS_PORT}"
        read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
        if ! xhttp_status_usable "$xcode"; then
            if [[ "$reason" == "interactive_attach" ]]; then
                log_event WARN "runtime_ready reason=${reason} started_route_unusable xhttp_probe=${xcode:-0} action=background_settle"
                start_headless_route_settling_monitor "$reason" || true
                return 1
            fi
            log_event WARN "runtime_ready reason=${reason} started_route_unusable xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} action=repair"
            repair_codespace_port_route >/dev/null 2>&1 || true
            wait_for_xhttp_route_ready "$reason" >/dev/null 2>&1 || true
            read -r xcode xms _probe_reason < <(xhttp_probe_metrics external)
        fi
        if xhttp_status_usable "$xcode"; then
            reset_route_bad_count
            reset_edge_bad_count
            log_event INFO "runtime_ready reason=${reason} started_route_ready xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0}"
            log_event INFO "runtime_ready reason=${reason} engine=started xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0}"
            return 0
        fi
        log_event WARN "runtime_ready reason=${reason} started_route_still_unusable xhttp_probe=${xcode:-0} xhttp_probe_ms=${xms:-0} action=observe"
        return 1
    fi

    log_event ERROR "runtime_ready reason=${reason} start_failed port=${XRAY_PORT}"
    return 1
}

ensure_runtime_ready() {
    with_runtime_lock _ensure_runtime_ready_impl "$@"
}

start_runtime_headless_command() {
    local runtime_status=0 supervisor_status=0

    ensure_runtime_ready "headless_start" || runtime_status=$?
    start_background_tasks || supervisor_status=$?

    if (( runtime_status != 0 )); then
        log_event ERROR "headless_start command_failed stage=runtime_ready exit_code=${runtime_status}"
        return "$runtime_status"
    fi
    if (( supervisor_status != 0 )); then
        log_event ERROR "headless_start command_failed stage=background_supervisor exit_code=${supervisor_status}"
        return "$supervisor_status"
    fi
    return 0
}

_prepare_headless_runtime_impl() {
    local reason="${1:-silent_start}"
    record_resume_gap "$reason"
    [[ -f "$CONFIG_FILE" ]] || return 0

    CODESPACE_NAME=$(_detect_codespace_name 2>/dev/null || true)
    refresh_port_domains

    if xray_listener_ready; then
        force_public_runtime_ports "headless_start" >/dev/null 2>&1 \
            || log_event WARN "headless_start reason=${reason} port_public_failed port=${XRAY_PORT}"
        log_event INFO "headless_start reason=${reason} listener_ready action=continue_async"
        return 0
    fi

    log_event WARN "headless_start reason=${reason} listener_unavailable action=start"
    if start_xray >/dev/null 2>&1 && wait_for_port >/dev/null 2>&1 && xray_listener_ready; then
        force_public_runtime_ports "headless_start" >/dev/null 2>&1 \
            || log_event WARN "headless_start reason=${reason} port_public_failed port=${XRAY_PORT}"
        log_event INFO "headless_start reason=${reason} engine_started action=continue_async"
        return 0
    fi

    log_event ERROR "headless_start reason=${reason} start_failed port=${XRAY_PORT}"
    return 1
}

prepare_headless_runtime() {
    with_runtime_lock _prepare_headless_runtime_impl "$@"
}

print_doctor_json() {
    local engine=false listener=false local_probe=0 local_ms=0 edge_probe=0 edge_ms=0 supervisor last_good waker_url doctor_port config_present=false next_action_code next_action
    doctor_port="$XRAY_PORT"
    [[ "$doctor_port" =~ ^[0-9]+$ && "$doctor_port" -gt 0 && "$doctor_port" -le 65535 ]] || doctor_port=443
    xray_running && engine=true
    is_port_open && listener=true
    [[ -f "$CONFIG_FILE" ]] && config_present=true
    read -r local_probe local_ms _probe_reason < <(xhttp_probe_metrics local)
    read -r edge_probe edge_ms _probe_reason < <(xhttp_probe_metrics external)
    supervisor=$(background_supervisor_status | tr '\r\n' ' ' | cut -c1-180)
    last_good=$(last_good_route_value)
    waker_url=$(waker_metadata_value worker_url)
    next_action_code=$(panel_next_action_code "$engine" "$listener" "$edge_probe" "$config_present")
    next_action=$(panel_next_action_text "$next_action_code")
    log_event INFO "doctor_json requested engine=${engine} listener=${listener} edge_probe=${edge_probe:-0} edge_ms=${edge_ms:-0} next_action_code=${next_action_code}"
    cat <<JSON
{
  "ok": true,
  "codespace": "$(json_escape "$CODESPACE_NAME")",
  "domain": "$(json_escape "$PORT_DOMAIN")",
  "port": ${doctor_port},
  "engine_running": ${engine},
  "listener_open": ${listener},
  "config_present": ${config_present},
  "local_probe": {"http_status": ${local_probe:-0}, "latency_ms": ${local_ms:-0}, "usable": $(xhttp_status_usable "$local_probe" && printf true || printf false)},
  "edge_probe": {"http_status": ${edge_probe:-0}, "latency_ms": ${edge_ms:-0}, "usable": $(xhttp_status_usable "$edge_probe" && printf true || printf false)},
  "next_action_code": "$(json_escape "$next_action_code")",
  "next_action": "$(json_escape "$next_action")",
  "supervisor": "$(json_escape "$supervisor")",
  "last_good_route": "$(json_escape "$last_good")",
  "waker_configured": $([[ -n "$waker_url" ]] && printf true || printf false),
  "low_overhead": $(low_overhead_enabled && printf true || printf false),
  "latency_focus": $(latency_focus_enabled && printf true || printf false),
  "xhttp_mode": "$(json_escape "$(xhttp_mode_value)")",
  "xhttp_link_tuning": $(xhttp_link_tuning_enabled && printf true || printf false),
  "performance_profile": "$(json_escape "$(effective_performance_profile)")",
  "saved_performance_profile": "$(json_escape "$PERFORMANCE_PROFILE")",
  "log_file": "$(json_escape "$LOG_FILE")",
  "structured_log_file": "$(json_escape "$STRUCTURED_LOG_FILE")",
  "diagnostic_log_file": "$(json_escape "$DIAGNOSTIC_LOG_FILE")"
}
JSON
}

bench_now_ns() {
    date +%s%N 2>/dev/null || awk 'BEGIN{srand(); printf "%.0f\n", systime() * 1000000000}'
}

bench_elapsed_ms() {
    local start="$1" end="$2"
    [[ "$start" =~ ^[0-9]+$ && "$end" =~ ^[0-9]+$ && "$end" -ge "$start" ]] || { printf '0'; return 0; }
    printf '%s' "$(( (end - start) / 1000000 ))"
}

bench_budget_value() {
    local value="$1" fallback="$2"
    [[ "$value" =~ ^[0-9]+$ ]] && printf '%s' "$value" || printf '%s' "$fallback"
}

bench_platform_multiplier() {
    case "${OSTYPE:-}" in
        msys*|cygwin*|win32*)
            [[ "${G2RAY_BENCH_WINDOWS_MULTIPLIER:-4}" =~ ^[1-9][0-9]*$ ]] \
                && printf '%s' "${G2RAY_BENCH_WINDOWS_MULTIPLIER:-4}" \
                || printf '4'
            ;;
        *) printf '1' ;;
    esac
}

bench_budget_ms() {
    local name="$1" base multiplier
    case "$name" in
        config_path_cache) base=$(bench_budget_value "${G2RAY_BENCH_BUDGET_CONFIG_PATH_MS:-2500}" 2500) ;;
        route_ordering) base=$(bench_budget_value "${G2RAY_BENCH_BUDGET_ROUTE_ORDERING_MS:-1500}" 1500) ;;
        export_generation) base=$(bench_budget_value "${G2RAY_BENCH_BUDGET_EXPORT_MS:-10000}" 10000) ;;
        doctor_json) base=$(bench_budget_value "${G2RAY_BENCH_BUDGET_DOCTOR_MS:-6000}" 6000) ;;
        recover_json_contract) base=$(bench_budget_value "${G2RAY_BENCH_BUDGET_RECOVER_JSON_MS:-6000}" 6000) ;;
        log_event_cost) base=$(bench_budget_value "${G2RAY_BENCH_BUDGET_LOG_EVENT_MS:-8000}" 8000) ;;
        *) base=$(bench_budget_value "${G2RAY_BENCH_BUDGET_DEFAULT_MS:-1000}" 1000) ;;
    esac
    multiplier=$(bench_platform_multiplier)
    printf '%s' "$((base * multiplier))"
}

bench_case_json() {
    local name="$1" command="$2" start end elapsed budget ok rc=0
    budget=$(bench_budget_ms "$name")
    start=$(bench_now_ns)
    eval "$command" >/dev/null 2>&1 || rc=$?
    end=$(bench_now_ns)
    elapsed=$(bench_elapsed_ms "$start" "$end")
    ok=false
    [[ "$budget" =~ ^[0-9]+$ && "$elapsed" =~ ^[0-9]+$ && "$elapsed" -le "$budget" && "$rc" -eq 0 ]] && ok=true
    printf '{"name":"%s","elapsed_ms":%s,"budget_ms":%s,"ok":%s,"exit_code":%s}' \
        "$(json_escape "$name")" "$elapsed" "$budget" "$ok" "$rc"
}

bench_prepare_mock_state() {
    mkdir -p "$DATA_DIR" "$LOG_DIR" "$BASE_DIR/data/qr" 2>/dev/null || true
    [[ -s "$UUID_FILE" ]] || printf '00000000-0000-4000-8000-000000000001\n' > "$UUID_FILE"
    cat > "$CONFIG_FILE" <<'JSON'
{"inbounds":[{"tag":"vless-in","port":443,"protocol":"vless","settings":{"clients":[{"id":"00000000-0000-4000-8000-000000000001"}]},"streamSettings":{"network":"xhttp","security":"none","xhttpSettings":{"path":"/","mode":"packet-up"}}}]}
JSON
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-06-01T00:00:00Z	20.0.0.1	200	40	true	dns	ready
2026-06-01T00:00:00Z	20.0.0.2	200	70	true	dns	ready
2026-06-01T00:00:00Z	20.0.0.3	404	20	false	dns	route_settling_404
EOF
    cat > "$ROUTE_STATS_FILE" <<'EOF'
20.0.0.1	8	8	0	40	35	60	40	200	true	2026-06-01T00:00:00Z	40	0	ready
20.0.0.2	8	7	1	70	60	120	70	200	true	2026-06-01T00:00:00Z	75	0	ready
EOF
}

bench_rebind_runtime_paths() {
    BASE_DIR="$1"
    DATA_DIR="$BASE_DIR/data"
    CONFIG_FILE="$DATA_DIR/config.json"
    UUID_FILE="$DATA_DIR/uuid.txt"
    BG_TASKS_PID="$DATA_DIR/bg_tasks.pid"
    BG_TASKS_VERSION_FILE="$DATA_DIR/bg_tasks.version"
    BG_TASKS_LOCK_DIR="$DATA_DIR/bg_tasks.lock"
    RUNTIME_LOCK_DIR="$DATA_DIR/runtime.lock"
    BG_TASKS_TOKEN_FILE="$DATA_DIR/bg_tasks.token"
    BG_TASKS_HEARTBEAT_FILE="$DATA_DIR/bg_tasks.heartbeat"
    BG_WATCHDOG_PID_FILE="$DATA_DIR/bg_tasks.watchdog.pid"
    ROUTE_SETTLE_PID_FILE="$DATA_DIR/route_settle.pid"
    ROUTE_SETTLE_TOKEN_FILE="$DATA_DIR/route_settle.token"
    ROUTE_SETTLE_LOG_FILE="$LOG_DIR/route-settle.log"
    RESUME_GAP_FILE="$DATA_DIR/resume_gap.txt"
    WAKER_METADATA_FILE="$DATA_DIR/waker_metadata.txt"
    WAKER_PROMPT_FILE="$DATA_DIR/.waker_setup_prompted"
    ROUTE_BAD_COUNT_FILE="$DATA_DIR/xhttp_route_bad_count"
    ROUTE_LATENCY_BASELINE_FILE="$DATA_DIR/route_latency_baseline"
    ROUTE_DEGRADED_REQUEST_FILE="$DATA_DIR/route_degraded_request"
    ROUTE_DEGRADED_REFRESH_STAMP_FILE="$DATA_DIR/route_degraded_refresh_stamp"
    UPDATE_AUDIT_FILE="$DATA_DIR/update_audit.log"
    EDGE_BAD_COUNT_FILE="$DATA_DIR/edge_bad_count"
    EDGE_RECONNECT_STAMP_FILE="$DATA_DIR/edge_reconnect_last"
    ROUTE_HEALTH_FILE="$DATA_DIR/route_candidate_health.tsv"
    ROUTE_STATS_FILE="$DATA_DIR/route_candidate_stats.tsv"
    ROUTE_COOLDOWN_FILE="$DATA_DIR/route_candidate_cooldowns.tsv"
    DNS_CANDIDATE_CACHE_FILE="$DATA_DIR/dns_candidate_cache.tsv"
    BOOT_STATUS_FILE="$DATA_DIR/boot_status.json"
    XHTTP_PATH_CACHE_FILE="$DATA_DIR/xhttp_path_cache"
    XHTTP_MODE_CACHE_FILE="$DATA_DIR/xhttp_mode_cache"
    XHTTP_MODE_FILE="$DATA_DIR/xhttp_mode.txt"
    XHTTP_LINK_TUNING_FILE="$DATA_DIR/xhttp_link_tuning.txt"
    LOW_OVERHEAD_FILE="$DATA_DIR/low_overhead_mode"
    LOW_OVERHEAD_DISABLED_FILE="$DATA_DIR/low_overhead_mode_disabled"
    LOW_OVERHEAD_PREV_PROFILE_FILE="$DATA_DIR/low_overhead_previous_profile.txt"
    LATENCY_FOCUS_FILE="$DATA_DIR/latency_focus_mode"
    LATENCY_FOCUS_DISABLED_FILE="$DATA_DIR/latency_focus_mode_disabled"
    LATENCY_FOCUS_PREV_PROFILE_FILE="$DATA_DIR/latency_focus_previous_profile.txt"
    WS_FALLBACK_FILE="$DATA_DIR/ws_fallback_mode"
    WS_FALLBACK_DISABLED_FILE="$DATA_DIR/ws_fallback_mode_disabled"
    WS_FRONT_DOMAIN_FILE="$DATA_DIR/ws_front_domain.txt"
    LAST_GOOD_ROUTE_FILE="$DATA_DIR/last_good_route.txt"
    PINNED_ROUTE_FILE="$DATA_DIR/pinned_route.txt"
    MANUAL_ROUTE_CANDIDATES_FILE="$DATA_DIR/manual_route_candidates.txt"
    BLACKLISTED_ROUTE_CANDIDATES_FILE="$DATA_DIR/blacklisted_route_candidates.txt"
    ROUTE_SETTLING_HISTORY_FILE="$DATA_DIR/route_settling_history.tsv"
    PORT_PUBLIC_STAMP_FILE="$DATA_DIR/port_public_last"
    ROUTE_REPAIR_STAMP_FILE="$DATA_DIR/route_repair_last"
    QUOTA_CYCLE_FILE="$DATA_DIR/quota_cycle.txt"
    PERFORMANCE_PROFILE_FILE="$DATA_DIR/performance_profile.txt"
    XRAY_PID_FILE="$DATA_DIR/xray.pid"
    SAVED_BYTES_FILE="$DATA_DIR/saved_bytes.json"
    SESSION_BYTES_FILE="$DATA_DIR/session_bytes.json"
    ACTIVE_TRAFFIC_STAMP_FILE="$DATA_DIR/active_traffic_last"
    TOTAL_UPTIME_FILE="$DATA_DIR/total_uptime_sec.txt"
    SESSION_START_FILE="$DATA_DIR/session_start.txt"
    LOG_DIR="$BASE_DIR/logs"
    LOG_FILE="$LOG_DIR/g2ray.log"
    STRUCTURED_LOG_FILE="$LOG_DIR/g2ray-events.jsonl"
    DIAGNOSTIC_LOG_FILE="$LOG_DIR/g2ray-diagnostics.log"
    LOG_ROTATE_STAMP_FILE="$DATA_DIR/log_rotate_last"
    STRUCTURED_LOG_ROTATE_STAMP_FILE="$DATA_DIR/structured_log_rotate_last"
    DIAGNOSTIC_LOG_ROTATE_STAMP_FILE="$DATA_DIR/diagnostic_log_rotate_last"
    LOG_CODE_VERSION_FILE="$DATA_DIR/log_code_version"
    QR_DIR="$DATA_DIR/qr"
    MOBILE_CONFIG_FILE="$BASE_DIR/configs-to-copy-for-mobile.txt"
    SUBSCRIPTION_FILE="$BASE_DIR/configs-subscription-base64.txt"
    CONFIG_META_FILE="$BASE_DIR/configs-meta.json"
    CODESPACE_BUNDLE_FILE="$BASE_DIR/g2ray-codespace-bundle.json"
    EXPORT_INPUT_HASH_FILE="$DATA_DIR/export_input.hash"
}

bench_json_impl() {
    local mock="${1:-false}" first=true case_json cases="" overall=true
    if [[ "$mock" == "true" ]]; then
        bench_prepare_mock_state
        MAX_FALLBACK_LINKS=2
        ROUTE_MONITOR_MAX_CANDIDATES=3
        ROUTE_HEALTH_TTL_SEC=3600
        CODESPACE_NAME="bench-space"
        PORT_DOMAIN="bench-space-443.app.github.dev"
        WS_PORT_DOMAIN="bench-space-8443.app.github.dev"
        log_event() { return 0; }
        xhttp_probe_metrics() { printf '200 1 ready\n'; }
        ensure_codespace_port_public() { return 0; }
        xray_running() { return 0; }
        is_port_open() { return 0; }
        xray_listener_ready() { return 0; }
        wait_for_xhttp_route_ready() { printf '200 1\n'; return 0; }
        refresh_route_candidate_health() { return 0; }
        background_supervisor_status() { printf 'pid=1 running=true version=ok token=present heartbeat_age=1s\n'; }
        recover_now() { return 0; }
    fi
    for spec in \
        'config_path_cache|for _i in $(seq 1 5); do xhttp_config_path >/dev/null; done' \
        'route_ordering|cached_usable_fallback_ips >/dev/null || true' \
        'export_generation|refresh_config_exports' \
        'doctor_json|print_doctor_json' \
        'recover_json_contract|recover_now_json || true' \
        'log_event_cost|for _i in $(seq 1 50); do log_structured_event "2026-01-01T00:00:00Z" INFO "bench_event iteration=${_i}"; done'
    do
        local name="${spec%%|*}" cmd="${spec#*|}"
        case_json=$(bench_case_json "$name" "$cmd")
        [[ "$case_json" == *'"ok":false'* ]] && overall=false
        if [[ "$first" == true ]]; then
            cases="    ${case_json}"
            first=false
        else
            cases="${cases},\n    ${case_json}"
        fi
    done
    printf '{\n  "ok": %s,\n  "mocked": %s,\n  "cases": [\n%b\n  ],\n  "budgets_ok": %s\n}\n' \
        "$overall" "$mock" "$cases" "$overall"
    [[ "$overall" == true ]]
}

bench_json() {
    local mock=false tmp rc
    [[ "${1:-}" == "--mock" || "${G2RAY_BENCH_MOCK:-0}" == "1" ]] && mock=true
    if [[ "$mock" == "true" ]]; then
        tmp="${G2RAY_BENCH_PREINIT_TMP:-}"
        if [[ -z "$tmp" ]]; then
            tmp=$(mktemp -d "${TMPDIR:-/tmp}/g2ray-bench.XXXXXX") || return 1
        fi
        (
            export G2RAY_BENCH_ISOLATED=1
            bench_rebind_runtime_paths "$tmp"
            bench_json_impl true
        )
        rc=$?
        rm -rf -- "$tmp"
        return "$rc"
    fi
    bench_json_impl "$mock"
}

server_exit_observation() {
    local provider="$1" url response ip country
    case "$provider" in
        ipinfo) url='https://ipinfo.io/json' ;;
        country_is) url='https://api.country.is/' ;;
        *) return 1 ;;
    esac
    # Match the generated server freedom outbound's UseIPv4 policy. Ignore
    # shell proxies/curlrc so this measures this host, not an unrelated proxy.
    if response=$(curl -q --ipv4 --noproxy '*' --fail --silent --show-error \
        --proto '=https' --connect-timeout 3 --max-time 5 --max-filesize 16384 \
        --header 'Cache-Control: no-cache' "$url" 2>/dev/null) && \
        [[ ${#response} -le 16384 ]] && \
        jq -e 'type == "object" and (.ip | type == "string") and
            (.country | type == "string")' <<< "$response" >/dev/null 2>&1; then
        ip=$(jq -r '.ip' <<< "$response")
        country=$(jq -r '.country' <<< "$response")
        if [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] && valid_ipv4 "$ip" &&
            [[ "$country" =~ ^[A-Z]{2}$ && "$country" != XX && "$country" != ZZ ]]; then
            jq -c --arg provider "$provider" '
                def detail($key): if (.[$key] | type) == "string" then
                    .[$key][0:120] | gsub("[\u0000-\u001f\u007f]"; " ") else null end;
                {provider:$provider, available:true, ip:.ip, country:.country,
                 city:detail("city"), org:detail("org")}' <<< "$response"
            return
        fi
    fi
    jq -cn --arg provider "$provider" \
        '{provider:$provider, available:false, ip:null, country:null}'
}

server_exit_location_json() {
    command -v jq >/dev/null 2>&1 || { printf 'jq is required for exit diagnostics.\n' >&2; return 1; }
    local first second
    first=$(server_exit_observation ipinfo) || return 1
    second=$(server_exit_observation country_is) || return 1
    jq -cn --arg checked_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        --argjson first "$first" --argjson second "$second" '
        [$first,$second] as $observations |
        [$observations[] | select(.available)] as $valid |
        [$valid[].country] | unique as $countries |
        [$valid[].ip] | unique as $ips |
        {checked_at_utc:$checked_at, scope:"server_ipv4_egress", expected_country:"US",
         status:(if ($valid|length) == 0 then "unavailable"
             elif ($valid|length) < 2 then "partial"
             elif ($countries|length) > 1 then "country_disagreement"
             elif $countries == ["US"] then "us_observed"
             else "non_us_observed" end),
         multiple_exit_ips:(($ips|length) > 1), observations:$observations}'
}

show_server_exit_location() {
    local report
    report=$(server_exit_location_json) || return 1
    printf '  Server IPv4 exit (not the ingress/route IP)\n'
    jq -r '.observations[] | "  \(.provider): " +
        (if .available then "\(.ip) country=\(.country)" else "unavailable or invalid response" end)' <<< "$report"
    jq -r '.observations[] | select(.available and .provider == "ipinfo") |
        (if .city != null then "  IPinfo city estimate: " + .city else empty end),
        (if .org != null then "  ISP/host: " + .org else empty end)' <<< "$report"
    printf '  US country check: %s\n' "$(jq -r '.status' <<< "$report")"
    if [[ "$(jq -r '.multiple_exit_ips' <<< "$report")" == true ]]; then
        printf '  Different destinations observed different exit IPs; this is not by itself a phone-IP leak.\n'
    fi
    printf '  Country labels are database observations, not a guarantee for every website.\n'
    printf '  Compare these with a browser IP check through your active VPN.\n'
}

if [[ "${G2RAY_SOURCE_ONLY:-}" == "1" ]]; then
    return 0 2>/dev/null || exit 0
fi

if [[ "${1:-}" == "--background-watchdog" ]]; then
    _background_watchdog
    exit $?
fi

if [[ "${1:-}" == "--exit-location" ]]; then
    case "${2:-}" in
        --json) server_exit_location_json ;;
        '') show_server_exit_location ;;
        *) printf 'Usage: bash ./g2ray.sh --exit-location [--json]\n' >&2; exit 2 ;;
    esac
    exit $?
fi

reset_logs_on_code_change
sweep_stale_temp_files

if [[ "${1:-}" == "--doctor-json" || "${1:-}" == "--status-json" || ( "${1:-}" == "doctor" && "${2:-}" == "--json" ) ]]; then
    print_doctor_json
    exit 0
fi

if [[ "${1:-}" == "--status" || "${1:-}" == "status" ]]; then
    print_doctor_json
    exit 0
fi

if [[ "${1:-}" == "--bench" || "${1:-}" == "bench" ]]; then
    # Default to isolated mock unless --live was explicitly requested.
    if [[ "$G2RAY_BENCH_WANTS_LIVE" == "1" ]]; then
        bench_json
    else
        bench_json --mock
    fi
    exit $?
fi

if [[ "${1:-}" == "--profile" || "${1:-}" == "profile" ]]; then
    case "${2:-}" in
        ""|status|show|current)
            printf 'effective_profile=%s\n' "$(effective_performance_profile)"
            printf 'saved_profile=%s\n' "$PERFORMANCE_PROFILE"
            printf 'low_overhead=%s\n' "$(low_overhead_enabled && printf enabled || printf disabled)"
            printf 'latency_focus=%s\n' "$(latency_focus_enabled && printf enabled || printf disabled)"
            printf 'available=balanced,low_latency,streaming,unstable_mobile,low_overhead,max_throughput\n'
            exit 0
            ;;
    esac
    if ! valid_performance_profile "$2"; then
        printf 'Unknown profile: %s\n' "$2" >&2
        printf 'available=balanced,low_latency,streaming,unstable_mobile,low_overhead,max_throughput\n' >&2
        exit 1
    fi
    set_performance_profile "$2"
    if [[ -f "$CONFIG_FILE" ]]; then
        if G2RAY_PRESERVE_UUID=1 generate_config; then
            printf '\nProfile %s applied to the existing config (same UUID; no need to re-import links).\n' "$2"
            exit 0
        fi
        printf 'Profile %s saved, but applying to the running config failed; use panel option 2 to regenerate.\n' "$2" >&2
        exit 1
    fi
    printf 'Profile %s saved; it will be used when you generate a config.\n' "$2"
    exit 0
fi

if [[ "${1:-}" == "--generate" || "${1:-}" == "generate" ]]; then
    case "${2:-}" in
        --keep-uuid) G2RAY_PRESERVE_UUID=1 ;;
        --new-uuid) G2RAY_PRESERVE_UUID=0 ;;
        "")
            if [[ -s "$CONFIG_FILE" ]]; then
                printf 'A config already exists. Use "generate --keep-uuid" to re-apply settings without changing client links,\n' >&2
                printf 'or "generate --new-uuid" to replace the UUID (every client must re-import).\n' >&2
                exit 2
            fi
            ;;
        *) printf 'Usage: g2ray.sh generate [--keep-uuid|--new-uuid]\n' >&2; exit 2 ;;
    esac
    if generate_config; then
        start_background_tasks || log_event WARN "background supervisor_start_deferred reason=generate_command"
        printf 'Config generated. Exports: %s\n' "$MOBILE_CONFIG_FILE"
        exit 0
    fi
    printf 'Config generation failed; the previous config was kept if one existed.\n' >&2
    exit 1
fi

if [[ "${1:-}" == "--link-tuning" || "${1:-}" == "link-tuning" ]]; then
    case "${2:-status}" in
        on|enable|enabled) set_xhttp_link_tuning on || exit 1 ;;
        off|disable|disabled) set_xhttp_link_tuning off || exit 1 ;;
        status) ;;
        *) printf 'Usage: g2ray.sh link-tuning [on|off|status]\n' >&2; exit 2 ;;
    esac
    printf 'xhttp_link_tuning=%s\n' "$(xhttp_link_tuning_summary)"
    exit 0
fi

if [[ "${1:-}" == "--refresh-exports" || "${1:-}" == "--export" || "${1:-}" == "export" ]]; then
    if refresh_config_exports; then
        printf 'Exports refreshed: %s\n' "$SUBSCRIPTION_FILE"
        exit 0
    fi
    printf 'No exportable configs were generated; stale export files were cleared.\n' >&2
    exit 1
fi

if [[ "${1:-}" == "--support-bundle" || "${1:-}" == "support-bundle" ]]; then
    create_support_bundle
    exit $?
fi

if [[ ( "${1:-}" == "--recover-now" || "${1:-}" == "recover" ) && "${2:-}" == "--json" ]]; then
    recover_now_json
    exit $?
fi

if [[ "${1:-}" == "--recover-now" || "${1:-}" == "recover" ]]; then
    recover_now --no-prompt
    exit $?
fi

if [[ "${1:-}" == "--start" || "${1:-}" == "start" ]]; then
    [[ -f "$CONFIG_FILE" ]] || { echo "No config exists yet. Run the panel and generate one first." >&2; exit 1; }
    start_runtime_headless_command
    exit $?
fi

if [[ "${1:-}" == "--latency-focus" || "${1:-}" == "latency-focus" ]]; then
    case "${2:-toggle}" in
        on|enable|enabled) enable_latency_focus_mode; printf 'latency_focus=enabled\n' ;;
        off|disable|disabled) disable_latency_focus_mode; printf 'latency_focus=disabled\n' ;;
        status) latency_focus_enabled && printf 'latency_focus=enabled\n' || printf 'latency_focus=disabled\n' ;;
        *) toggle_latency_focus_mode && printf 'latency_focus=enabled\n' || printf 'latency_focus=disabled\n' ;;
    esac
    exit 0
fi

if [[ "${1:-}" == "--background-supervisor" ]]; then
    _background_tasks
    exit 0
fi

if [[ "${1:-}" == "--headless-route-settle" ]]; then
    trap clear_route_settle_monitor_state EXIT
    run_headless_route_settle "${2:-silent_start}"
    exit $?
fi

if [[ "${1:-}" == "--silent-start" ]]; then
    sync_anti_sleep_runtime
    if [[ ! -f "$CONFIG_FILE" ]]; then
        write_boot_status "no_config" "silent_start" "No config exists yet; open the panel and generate one." "0" "0" \
            || log_event WARN "boot_status nonfatal_write_failed reason=silent_start"
    elif prepare_headless_runtime "silent_start" >/dev/null 2>&1; then
        start_background_tasks || log_event WARN "headless_start reason=silent_start supervisor_start_deferred"
        if wait_for_xhttp_route_ready "silent_start_verify" "$G2RAY_SILENT_START_VERIFY_SEC" >/dev/null 2>&1; then
            read -r _boot_code _boot_ms _boot_reason < <(xhttp_probe_metrics external)
            write_boot_status "ready" "silent_start" "Xray listener and the Codespaces route are usable." "${_boot_code:-0}" "${_boot_ms:-0}" \
                || log_event WARN "boot_status nonfatal_write_failed reason=silent_start"
        else
            read -r _boot_code _boot_ms _boot_reason < <(xhttp_probe_metrics external)
            write_boot_status "route_settling" "silent_start" "Xray listener is ready; Codespaces route recovery now continues asynchronously in the background." "${_boot_code:-0}" "${_boot_ms:-0}" \
                || log_event WARN "boot_status nonfatal_write_failed reason=silent_start"
            start_headless_route_settling_monitor "silent_start" \
                || log_event WARN "headless_start reason=silent_start route_settle_start_deferred"
        fi
    else
        read -r _boot_code _boot_ms _boot_reason < <(xhttp_probe_metrics external)
        write_boot_status "needs_attention" "silent_start" "Xray listener could not start; background route settling was not started." "${_boot_code:-0}" "${_boot_ms:-0}" \
            || log_event WARN "boot_status nonfatal_write_failed reason=silent_start"
        start_background_tasks || log_event WARN "headless_start reason=silent_start supervisor_start_deferred"
        exit 1
    fi
    exit 0
fi

cleanup() {
    local code=$?
    save_xray_stats 2>/dev/null || true
    save_session_uptime 2>/dev/null || true
    echo -e "\n  ${DIM}Goodbye.${NC}"
    exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

check_for_updates "$@"
printf '  Checking local engine and public route...\n'
ensure_runtime_ready "interactive_attach" >/dev/null 2>&1 || true
start_background_tasks || log_event WARN "background supervisor_start_deferred reason=interactive_attach"
sync_anti_sleep_runtime

if [[ ! -f "$CONFIG_FILE" ]]; then
    refresh_screen
    echo -e "  ${GREEN}● Welcome to G2ray${NC}"
    echo -e "  ${WHITE}No configuration found. First run setup.${NC}\n"
    echo -e "  ${RED}1)${NC} Generate Config & Start"
    echo -e "  ${DIM}2)${NC} Exit\n"
    echo -ne "  ${RED}╰─❯${NC} "
    read -r setup
    if [[ "$setup" == "1" ]]; then
        if generate_config_with_feedback; then
            echo -e "\n  ${GREEN}✔ Setup complete!${NC}"
            sleep 1
            maybe_prompt_waker_setup
            show_recovery_command_card
            show_diagnostics
        else
            echo -e "\n  ${YELLOW}Setup did not complete. Check logs or diagnostics, then try option 2.${NC}"
            sleep 2
        fi
    else
        exit 0
    fi
else
    refresh_screen
    maybe_prompt_waker_setup
fi

while true; do
    refresh_screen

    if xray_running; then
        _STATUS="${GREEN}▶ RUNNING${NC}"
    else
        _STATUS="${RED}■ STOPPED${NC}"
    fi

    if anti_sleep_enabled && anti_sleep_running; then
        _KA="${GREEN}Enabled${NC}"
        _KA_LABEL="${GREEN}currently Enabled${NC}"
    elif anti_sleep_enabled; then
        _KA="${YELLOW}Enabled (runtime unavailable)${NC}"
        _KA_LABEL="${YELLOW}enabled, runtime unavailable${NC}"
    else
        _KA="${DIM}Disabled${NC}"
        _KA_LABEL="${DIM}currently Disabled${NC}"
    fi
    if low_overhead_enabled; then
        _LOW_LABEL="${GREEN}currently Enabled${NC}"
    else
        _LOW_LABEL="${DIM}currently Disabled${NC}"
    fi
    if latency_focus_enabled; then
        _LATENCY_LABEL="${GREEN}currently Enabled${NC}"
    else
        _LATENCY_LABEL="${DIM}currently Disabled${NC}"
    fi
    if ws_fallback_enabled; then
        _WS_LABEL="${GREEN}currently Enabled${NC}"
    else
        _WS_LABEL="${DIM}currently Disabled${NC}"
    fi
    _XHTTP_MODE_LABEL="${WHITE}$(xhttp_mode_value)${NC}"
    _WS_FRONT_VALUE=$(ws_front_domain_value 2>/dev/null || true)
    if [[ -n "$_WS_FRONT_VALUE" ]]; then
        _WS_FRONT_LABEL="${GREEN}${_WS_FRONT_VALUE}${NC}"
    else
        _WS_FRONT_LABEL="${DIM}not set${NC}"
    fi

    echo -e "  ${WHITE}${B}Engine Status  :${NC} $(echo -e "$_STATUS")"
    echo -e "  ${WHITE}${B}Server Route   :${NC} ${WHITE}$(menu_best_route_summary 2>/dev/null || true)${NC}"
    echo -e "  ${WHITE}${B}Anti-Sleep Mode:${NC} $(echo -e "$_KA")\n"
    echo -e "  ${DIM}Educational/research use only. Follow local laws and platform rules.${NC}\n"
    echo -e "  ${WHITE}${B}● CORE CONTROLS${NC}"
    echo -e "   ${RED}1)${NC} View Config & QR Code       ${RED}4)${NC} Stop Engine"
    echo -e "   ${RED}2)${NC} Generate New Config         ${RED}5)${NC} Restart Engine"
    echo -e "   ${RED}3)${NC} Start Engine                ${RED}6)${NC} Recover Now"
    echo ""
    echo -e "  ${WHITE}${B}● SYSTEM CONFIGURATION${NC}"
    echo -e "   ${RED}7)${NC} Toggle Anti-Sleep Mode ($(echo -e "$_KA_LABEL"))"
    echo -e "   ${DIM}Generated configs stay local; do not publish live links.${NC}"
    echo -e "  ${RED}18)${NC} Toggle Low-Overhead Mode ($(echo -e "$_LOW_LABEL"))"
    echo -e "  ${RED}19)${NC} Toggle WebSocket Fallback ($(echo -e "$_WS_LABEL"))"
    echo -e "  ${RED}20)${NC} Cloudflare WS Front ($(echo -e "$_WS_FRONT_LABEL"))"
    echo -e "  ${RED}21)${NC} XHTTP Link Mode ($(echo -e "$_XHTTP_MODE_LABEL"))"
    echo -e "  ${RED}49)${NC} Toggle Latency Focus Mode ($(echo -e "$_LATENCY_LABEL"))"
    echo ""
    echo -e "  ${WHITE}${B}● ANALYTICS & TOOLS${NC}"
    echo -e "   ${RED}9)${NC} Data Usage                 ${RED}12)${NC} Server Location"
    echo -e "  ${RED}10)${NC} Resource Stats             ${RED}13)${NC} View Engine Logs"
    echo -e "  ${RED}14)${NC} Diagnostics                ${RED}15)${NC} Recovery / Waker Setup"
    echo -e "  ${RED}11)${NC} Quota & Uptime             ${RED}16)${NC} Live Monitor"
    echo -e "                                      ${RED}17)${NC} Route Candidates"
    echo ""
    echo -e "   ${RED}0)${NC} Exit Panel"
    echo -e "  ${DIM}───────────────────────────────────────────────────────────${NC}"

    echo -ne "  ${RED}╰─❯${NC} "
    read -r _choice

    case $_choice in
        1)
            check_port_visibility || continue
            ensure_route_cache_for_export "" "view_configs" >/dev/null 2>&1 || true
            if domain_link_export_enabled; then
                _VLESS_DOMAIN=$(generate_domain_link) || _VLESS_DOMAIN=""
            else
                _VLESS_DOMAIN=""
            fi
            _VLESS_IPS=$(generate_ip_links) || _VLESS_IPS=""
            mapfile -t _VLESS_IP_ARRAY < <(printf '%s\n' "$_VLESS_IPS" | awk 'NF')
            _CONFIG_LINKS=()
            _CONFIG_LABELS=()
            _DOMAIN_ALREADY=false
            for _LINK in "${_VLESS_IP_ARRAY[@]}"; do
                [[ -n "$_LINK" ]] || continue
                if ((${#_CONFIG_LINKS[@]} == 0)); then
                    _CONFIG_LABELS+=("Recommended IP link (Codespace-side ranking; test on your network)")
                else
                    _CONFIG_LABELS+=("IP fallback link $((${#_CONFIG_LINKS[@]} + 1))")
                fi
                _CONFIG_LINKS+=("$_LINK")
                [[ "$_LINK" == "$_VLESS_DOMAIN" ]] && _DOMAIN_ALREADY=true
            done
            if [[ -n "$_VLESS_DOMAIN" && "$_DOMAIN_ALREADY" != true ]]; then
                if ((${#_CONFIG_LINKS[@]} == 0)); then
                    _CONFIG_LABELS+=("Recommended domain link")
                else
                    _CONFIG_LABELS+=("Domain link (try only if app.github.dev is allowed)")
                fi
                _CONFIG_LINKS+=("$_VLESS_DOMAIN")
            fi
            if ws_fallback_enabled; then
                _WS_LINKS=$(generate_ws_links) || _WS_LINKS=""
                _WS_FRONT_VALUE=$(ws_front_domain_value 2>/dev/null || true)
                while IFS= read -r _LINK; do
                    [[ -n "$_LINK" ]] || continue
                    _WS_ALPN_LABEL=$(ws_link_alpn_label "$_LINK")
                    if [[ -n "$_WS_FRONT_VALUE" && "$_LINK" == vless://*@"$_WS_FRONT_VALUE":* ]]; then
                        _CONFIG_LABELS+=("Cloudflare WebSocket front link (${_WS_ALPN_LABEL})")
                    else
                        _CONFIG_LABELS+=("Advanced WebSocket fallback link $((${#_CONFIG_LABELS[@]} + 1)) (${_WS_ALPN_LABEL})")
                    fi
                    _CONFIG_LINKS+=("$_LINK")
                done < <(printf '%s\n' "$_WS_LINKS" | awk 'NF')
            fi
            _VLESS_PRIMARY="${_CONFIG_LINKS[0]:-}"
            [[ -z "$_VLESS_PRIMARY" ]] && { echo -e "  ${RED}✖ Error generating link.${NC}"; sleep 2; continue; }
            with_file_lock "$EXPORT_LOCK_FILE" 45 write_config_exports_from_links "${_CONFIG_LINKS[@]}" >/dev/null 2>&1 \
                || log_event WARN "config_exports view_write_failed"
            refresh_screen
            echo -e "  ${RED}● Configs & QR Codes${NC}"
            echo -e "  ${DIM}Raw links are printed without color codes and saved locally to:${NC}"
            echo -e "  ${DIM}Local base64 subscription file:${NC} ${WHITE}${SUBSCRIPTION_FILE}${NC}"
            echo -e "  ${DIM}Codespace bundle for the Android app:${NC} ${WHITE}${CODESPACE_BUNDLE_FILE}${NC}"
            echo -e "  ${DIM}Generated exports are ignored by git and are not published through the repo.${NC}"
            echo -e "  ${WHITE}${MOBILE_CONFIG_FILE}${NC}\n"
            _INDEX=1
            _QR_MODE="${G2RAY_QR_MODE:-recommended}"
            if [[ "$_QR_MODE" != none && -s "$QR_DIR/all-configs.txt" ]]; then
                printf '  All configs + local usage estimate (scan with G2Ray Android):\n'
                [[ ! -s "$QR_DIR/all-configs.png" ]] || printf '  PNG: %s/all-configs.png\n' "$QR_DIR"
                if command -v qrencode >/dev/null 2>&1; then
                    qrencode -l M -m 2 -t UTF8 "$(cat "$QR_DIR/all-configs.txt")" || true
                fi
            fi
            for _LINK in "${_CONFIG_LINKS[@]}"; do
                _SHOW_QR=false
                [[ "$_QR_MODE" == "all" || ( "$_QR_MODE" != "none" && $_INDEX -eq 1 ) ]] && _SHOW_QR=true
                render_config_entry "$_INDEX" "${_CONFIG_LABELS[$((_INDEX - 1))]}" "$_LINK" "$_SHOW_QR"
                _INDEX=$((_INDEX + 1))
            done
            echo -e "  ${DIM}IP links keep ${PORT_DOMAIN} as SNI/Host for Codespaces routing.${NC}\n"
            echo -e "  ${DIM}QR PNG files are saved under ${QR_DIR}.${NC}"
            echo -e "  ${DIM}If phone QR scanning fails, open the PNG, import the copy-ready link, or${NC}"
            echo -e "  ${DIM}${MOBILE_CONFIG_FILE} instead. Terminal zoom/theme can make QR scanning unreliable.${NC}\n"
            echo -e "  ${DIM}For exit location details, use option 12) Server Location.${NC}"
            echo -e "  ${DIM}Not working? Use option 14) Diagnostics, 6) Recover Now, or${NC}"
            echo -e "  ${DIM}run: bash ./g2ray.sh --support-bundle${NC}\n"
            echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
            ;;
        2)
            echo -e "\n  ${YELLOW}⚠ Overwrite current config and restart engine?${NC}"
            echo -ne "  ${GREEN}╰─❯${NC} Proceed (y/n): "
            read -r _confirm
            if [[ "$_confirm" =~ ^[Yy]$ ]]; then
                if generate_config_with_feedback; then
                    sleep 1
                else
                    sleep 2
                fi
            fi
            ;;
        3)
            if xray_running; then
                echo -e "\n  ${WHITE}Engine is already running.${NC}"
            else
                if start_xray && wait_for_port; then
                    ensure_codespace_port_public \
                        || echo -e "  ${YELLOW}⚠ Could not set port public. Use the PORTS tab.${NC}"
                fi
            fi
            sleep 1
            ;;
        4)
            if stop_xray; then
                echo -e "\n  ${RED}■ Engine stopped.${NC}"
            else
                echo -e "\n  ${RED}✖ Could not fully stop the engine. Check diagnostics.${NC}"
            fi
            sleep 1
            ;;
        5)
            if start_xray && wait_for_port; then
                ensure_codespace_port_public \
                    || echo -e "  ${YELLOW}⚠ Could not set port public. Use the PORTS tab.${NC}"
            fi
            sleep 1
            ;;
        6) recover_now ;;
        7)
            if anti_sleep_enabled; then
                disable_anti_sleep
                echo -e "\n  ${RED}■ Anti-Sleep disabled.${NC}"
            else
                if enable_anti_sleep; then
                    echo -e "\n  ${GREEN}▶ Anti-Sleep enabled.${NC}"
                else
                    echo -e "\n  ${YELLOW}⚠ Anti-Sleep preference was enabled, but Tmux could not start.${NC}"
                fi
            fi
            sleep 2
            ;;
        8)
            echo -e "\n  ${DIM}This old public-sharing option has been removed. Keep generated configs for personal/test use only.${NC}"
            sleep 2
            ;;
        9)
            refresh_screen
            read -r _TD _TU <<< "$(get_data_usage)"
            _TD=${_TD:-0}; _TU=${_TU:-0}
            if (( _TD == 0 && _TU == 0 )); then
                echo -e "\n  ${DIM}No traffic data recorded yet. Connect and browse first.${NC}\n"
            else
                _TT=$(( _TD + _TU ))
                echo -e "\n  ${GREEN}● Data Usage (All Sessions)${NC}"
                echo -e "  Download (RX) : ${WHITE}$(format_bytes "$_TD")${NC}"
                echo -e "  Upload   (TX) : ${WHITE}$(format_bytes "$_TU")${NC}"
                echo -e "  Total Traffic : ${GREEN}$(format_bytes "$_TT")${NC}\n"
            fi
            echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
            ;;
        10) show_resource_stats ;;
        11)
            refresh_screen; echo ""
            estimate_quota
            echo ""; echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
            ;;
        12)
            refresh_screen
            echo -e "\n  ${DIM}Fetching server details...${NC}\n"
            show_server_exit_location || true
            echo ""; echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
            ;;
        13)
            refresh_screen
            echo -e "\n  ${GREEN}● Live Engine Logs${NC}"
            if [[ -s "$LOG_DIR/xray.log" ]]; then
                echo -e "  ${WHITE}${B}Runtime log${NC}"
                tail -n 15 "$LOG_DIR/xray.log" | sed 's/^/  /'
            else
                echo -e "  ${DIM}Log file empty or missing.${NC}"
            fi
            echo -e "\n  ${WHITE}${B}Error log${NC}"
            if [[ -s "$LOG_DIR/xray-error.log" ]]; then
                tail -n 15 "$LOG_DIR/xray-error.log" | sed 's/^/  /'
            else
                echo -e "  ${DIM}No Xray errors logged.${NC}"
            fi
            echo -e "\n  ${DIM}(Xray access log is disabled; diagnostics shows probe and supervisor state.)${NC}\n"
            echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
            ;;
        14) show_diagnostics ;;
        15) show_recovery_waker ;;
        16) show_live_monitor ;;
        17) show_route_candidate_manager ;;
        18)
            if toggle_low_overhead_mode; then
                echo -e "\n  ${GREEN}Low-overhead mode enabled.${NC}"
                echo -e "  ${DIM}Applied the low_overhead Xray profile, reduced nonessential background refreshes, and kept the same UUID.${NC}"
                echo -e "  ${DIM}This can improve stability on weak Codespace resources, but may reduce peak throughput.${NC}"
            else
                echo -e "\n  ${WHITE}Low-overhead mode disabled.${NC}"
                echo -e "  ${DIM}Restored the previous performance profile and normal background monitoring.${NC}"
            fi
            sleep 2
            ;;
        19)
            if toggle_ws_fallback_mode; then
                echo -e "\n  ${GREEN}WebSocket fallback enabled.${NC}"
                echo -e "  ${DIM}The panel will append advanced WS fallback links after the normal XHTTP links.${NC}"
                echo -e "  ${DIM}XHTTP remains the recommended default; use WS only for compatibility testing.${NC}"
            else
                echo -e "\n  ${WHITE}WebSocket fallback disabled.${NC}"
                echo -e "  ${DIM}Future generated configs will contain only the primary XHTTP inbound.${NC}"
            fi
            if [[ -f "$CONFIG_FILE" ]]; then
                echo -e "  ${DIM}Applying transport change with the same UUID...${NC}"
                generate_config_preserving_uuid_with_feedback || true
            else
                echo -e "  ${DIM}No config exists yet. Use option 2 to generate one with this setting.${NC}"
            fi
            echo -ne "  ${DIM}Press Enter to return...${NC}"; read -r
            ;;
        20) show_ws_front_manager ;;
        21) show_xhttp_mode_manager ;;
        49)
            if toggle_latency_focus_mode; then
                echo -e "\n  ${GREEN}Latency focus mode enabled.${NC}"
                echo -e "  ${DIM}Applied the low_latency Xray profile, kept the same UUID, and minimized noncritical background work.${NC}"
                echo -e "  ${DIM}This aims to reduce queueing/idle stalls; it cannot fix ISP packet loss by itself.${NC}"
            else
                echo -e "\n  ${WHITE}Latency focus mode disabled.${NC}"
                echo -e "  ${DIM}Restored the previous performance profile, normal route scans, exports, and logs.${NC}"
            fi
            sleep 3
            ;;
        0) echo -e "\n  ${GREEN}Exiting G2ray Panel...${NC}"; exit 0 ;;
        *) echo -e "  ${RED}✖ Invalid option.${NC}"; sleep 1 ;;
    esac
done

#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/g2ray.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT
mkdir -p "$TMP_ROOT/bin"
printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP_ROOT/bin/gh"
chmod +x "$TMP_ROOT/bin/gh"
export PATH="$TMP_ROOT/bin:$PATH"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

pass() {
    echo "PASS: $*"
}

reset_runtime_paths() {
    DATA_DIR="$TMP_ROOT/data"
    LOG_DIR="$TMP_ROOT/logs"
    QR_DIR="$DATA_DIR/qr"
    CONFIG_FILE="$DATA_DIR/config.json"
    UUID_FILE="$DATA_DIR/uuid.txt"
    ROUTE_HEALTH_FILE="$DATA_DIR/route_candidate_health.tsv"
    ROUTE_STATS_FILE="$DATA_DIR/route_candidate_stats.tsv"
    ROUTE_LATENCY_BASELINE_FILE="$DATA_DIR/route_latency_baseline"
    ROUTE_DEGRADED_REQUEST_FILE="$DATA_DIR/route_degraded_request"
    ROUTE_DEGRADED_REFRESH_STAMP_FILE="$DATA_DIR/route_degraded_refresh_stamp"
    UPDATE_AUDIT_FILE="$DATA_DIR/update_audit.log"
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
    LATENCY_FOCUS_FILE="$DATA_DIR/latency_focus_mode"
    LATENCY_FOCUS_DISABLED_FILE="$DATA_DIR/latency_focus_mode_disabled"
    WS_FALLBACK_FILE="$DATA_DIR/ws_fallback_mode"
    WS_FALLBACK_DISABLED_FILE="$DATA_DIR/ws_fallback_mode_disabled"
    WS_FRONT_DOMAIN_FILE="$DATA_DIR/ws_front_domain.txt"
    LOW_OVERHEAD_PREV_PROFILE_FILE="$DATA_DIR/low_overhead_previous_profile.txt"
    LATENCY_FOCUS_PREV_PROFILE_FILE="$DATA_DIR/latency_focus_previous_profile.txt"
    PERFORMANCE_PROFILE_FILE="$DATA_DIR/performance_profile.txt"
    DOMAIN_LINK_EXPORT_FILE="$DATA_DIR/export_domain_link.txt"
    MOBILE_CONFIG_FILE="$TMP_ROOT/configs-to-copy-for-mobile.txt"
    SUBSCRIPTION_FILE="$TMP_ROOT/configs-subscription-base64.txt"
    CONFIG_META_FILE="$TMP_ROOT/configs-meta.json"
    CODESPACE_BUNDLE_FILE="$TMP_ROOT/g2ray-codespace-bundle.json"
    LAST_GOOD_ROUTE_FILE="$DATA_DIR/last_good_route.txt"
    PINNED_ROUTE_FILE="$DATA_DIR/pinned_route.txt"
    MANUAL_ROUTE_CANDIDATES_FILE="$DATA_DIR/manual_route_candidates.txt"
    BLACKLISTED_ROUTE_CANDIDATES_FILE="$DATA_DIR/blacklisted_route_candidates.txt"
    ROUTE_SETTLING_HISTORY_FILE="$DATA_DIR/route_settling_history.tsv"
    PORT_PUBLIC_STAMP_FILE="$DATA_DIR/port_public_last"
    RUNTIME_LOCK_DIR="$DATA_DIR/runtime.lock"
    SAVED_BYTES_FILE="$DATA_DIR/saved_bytes.json"
    SESSION_BYTES_FILE="$DATA_DIR/session_bytes.json"
    TOTAL_UPTIME_FILE="$DATA_DIR/total_uptime_seconds"
    SESSION_START_FILE="$DATA_DIR/session_start_epoch"
    QUOTA_CYCLE_FILE="$DATA_DIR/quota_cycle.txt"
    LOG_FILE="$LOG_DIR/g2ray.log"
    STRUCTURED_LOG_FILE="$LOG_DIR/g2ray-events.jsonl"
    DIAGNOSTIC_LOG_FILE="$LOG_DIR/g2ray-diagnostics.log"
    LOG_CODE_VERSION_FILE="$DATA_DIR/log_code_version"
    EXPORT_INPUT_HASH_FILE="$DATA_DIR/export_input.hash"
    ACTIVE_TRAFFIC_STAMP_FILE="$DATA_DIR/active_traffic_last"
    ROUTE_REPAIR_STAMP_FILE="$DATA_DIR/route_repair_last"
    WAKER_METADATA_FILE="$DATA_DIR/waker_metadata.txt"
    XRAY_PID_FILE="$DATA_DIR/xray.pid"
    BG_TASKS_PID="$DATA_DIR/bg_tasks.pid"
    BG_TASKS_VERSION_FILE="$DATA_DIR/bg_tasks.version"
    BG_TASKS_LOCK_DIR="$DATA_DIR/bg_tasks.lock"
    BG_TASKS_TOKEN_FILE="$DATA_DIR/bg_tasks.token"
    BG_TASKS_HEARTBEAT_FILE="$DATA_DIR/bg_tasks.heartbeat"
    BG_WATCHDOG_PID_FILE="$DATA_DIR/bg_tasks.watchdog.pid"
    ROUTE_SETTLE_PID_FILE="$DATA_DIR/route_settle.pid"
    ROUTE_SETTLE_TOKEN_FILE="$DATA_DIR/route_settle.token"
    ROUTE_SETTLE_LOG_FILE="$LOG_DIR/route-settle.log"
    rm -rf "$DATA_DIR" "$LOG_DIR"
    mkdir -p "$DATA_DIR" "$LOG_DIR" "$QR_DIR"
    rm -f "$MOBILE_CONFIG_FILE" "$SUBSCRIPTION_FILE" "$CONFIG_META_FILE" "$CODESPACE_BUNDLE_FILE" "${CODESPACE_BUNDLE_FILE%.json}.legacy.json" 2>/dev/null || true
    : > "$LOG_FILE"
    : > "$STRUCTURED_LOG_FILE"
    : > "$DIAGNOSTIC_LOG_FILE"
    LOG_MAX_BYTES=1048576
    LOG_ROTATE_KEEP=3
    printf '{"down":0,"up":0}\n' > "$SAVED_BYTES_FILE"
    printf '{"down":0,"up":0}\n' > "$SESSION_BYTES_FILE"
    printf '0\n' > "$TOTAL_UPTIME_FILE"
    date +%s > "$SESSION_START_FILE"
    MAX_FALLBACK_LINKS=20
    ROUTE_MONITOR_MAX_CANDIDATES=24
    ROUTE_HEALTH_TTL_SEC=300
    ROUTE_REFRESH_BACKOFF_INITIAL_SEC=60
    ROUTE_REFRESH_BACKOFF_MAX_SEC=600
    DNS_CACHE_TTL_SEC=300
    ROUTE_FAILURE_COOLDOWN_SEC=180
    LAST_GOOD_ROUTE_MAX_AGE_SEC=1800
    PERFORMANCE_PROFILE=balanced
    ROUTE_DEGRADED_MULTIPLIER=3
    ROUTE_DEGRADED_MIN_DELTA_MS=500
    ROUTE_DEGRADED_MIN_SAMPLES=3
    ROUTE_DEGRADED_TRIGGER_COUNT=2
    ROUTE_DEGRADED_REFRESH_COOLDOWN_SEC=300
    unset G2RAY_XHTTP_LINK_TUNING
    unset G2RAY_TCP_FAST_OPEN
    unset G2RAY_LOW_OVERHEAD G2RAY_LATENCY_FOCUS G2RAY_PERFORMANCE_PROFILE G2RAY_EXPORT_DOMAIN_LINK G2RAY_XHTTP_EXTRA_JSON G2RAY_XHTTP_MODE G2RAY_ENABLE_WS_FALLBACK G2RAY_WS_PORT G2RAY_WS_MAX_FALLBACK_LINKS G2RAY_BENCH_MOCK G2RAY_BENCH_ISOLATED G2RAY_PORT_FORWARDING_DOMAIN GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN
}

write_test_vless_config() {
    local uuid="${1:-}"
    [[ -n "$uuid" ]] || uuid=$(awk 'NF {print; exit}' "$UUID_FILE" 2>/dev/null || true)
    [[ -n "$uuid" ]] || uuid="11111111-2222-4333-8444-555555555555"
    cat > "$CONFIG_FILE" <<JSON
{"inbounds":[{"tag":"vless-in","protocol":"vless","settings":{"clients":[{"id":"${uuid}"}]},"streamSettings":{"network":"xhttp","xhttpSettings":{"path":"/","mode":"packet-up"}}}]}
JSON
}

export CODESPACE_NAME="behavior-space"
export XRAY_PORT="443"
export G2RAY_SOURCE_ONLY=1
export G2RAY_DATA_DIR="$TMP_ROOT/bootstrap-data"
export G2RAY_LOG_DIR="$TMP_ROOT/bootstrap-logs"
source "$SCRIPT"
ORIGINAL_RUN_GH="$(declare -f run_gh)"
ORIGINAL_XRAY_RUNNING="$(declare -f xray_running)"
reset_runtime_paths

test_tcp_fast_open_requires_explicit_opt_in() {
    (
        reset_runtime_paths
        local mock_tfo_kernel=3
        cat() {
            if [[ "${1:-}" == /proc/sys/net/ipv4/tcp_fastopen ]]; then
                printf '%s\n' "$mock_tfo_kernel"
            else
                command cat "$@"
            fi
        }
        if tcp_fast_open_outbound_enabled; then
            fail 'kernel capability alone enabled origin TFO by default'
        fi
        G2RAY_TCP_FAST_OPEN=auto tcp_fast_open_outbound_enabled \
            || fail 'explicit auto did not honor supported client TFO'
        mock_tfo_kernel=2
        if G2RAY_TCP_FAST_OPEN=auto tcp_fast_open_outbound_enabled; then
            fail 'explicit auto enabled TFO on a server-only kernel'
        fi
        G2RAY_TCP_FAST_OPEN=1 tcp_fast_open_outbound_enabled \
            || fail 'explicit force-on stopped working'
        mock_tfo_kernel=3
        if G2RAY_TCP_FAST_OPEN=invalid tcp_fast_open_outbound_enabled; then
            fail 'invalid TFO setting fell back to unsafe enablement'
        fi
        pass 'origin TFO is off by default, with explicit kernel-gated auto and force-on'
    )
}

test_existing_config_upgrade_disables_unrequested_origin_tfo() {
    (
        reset_runtime_paths
        write_test_vless_config
        jq '.outbounds = [{"protocol":"freedom","tag":"direct","streamSettings":{"sockopt":{"tcpFastOpen":true,"tcpKeepAliveIdle":30,"tcpKeepAliveInterval":15}}},
                          {"protocol":"freedom","tag":"custom","streamSettings":{"sockopt":{"tcpFastOpen":true}}}]' \
            "$CONFIG_FILE" > "$TMP_ROOT/tfo-fixture.json"
        mv "$TMP_ROOT/tfo-fixture.json" "$CONFIG_FILE"
        upgrade_config_dns
        jq -e '.outbounds[0].streamSettings.sockopt.tcpFastOpen == false and
               .outbounds[0].streamSettings.sockopt.tcpKeepAliveIdle == 30 and
               .outbounds[0].streamSettings.sockopt.tcpKeepAliveInterval == 15 and
               .outbounds[1].streamSettings.sockopt.tcpFastOpen == true and
               .inbounds[0].settings.clients[0].id == "11111111-2222-4333-8444-555555555555"' \
            "$CONFIG_FILE" >/dev/null || fail 'TFO migration changed identity/socket settings or retained unsafe default'
        G2RAY_TCP_FAST_OPEN=1 upgrade_config_dns
        jq -e '.outbounds[0].streamSettings.sockopt.tcpFastOpen == true' "$CONFIG_FILE" >/dev/null \
            || fail 'migration ignored an explicit TFO override'
        pass 'TFO migration preserves UUID, keepalive, custom outbounds, and explicit overrides'
    )
}

test_probe_variation_tracks_successes_and_preserves_legacy_rows() {
    reset_runtime_paths
    local now legacy
    now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    legacy=$(printf '20.0.0.2\t5\t5\t0\t100\t100\t100\t100\t200\ttrue\t%s' "$now")
    printf '%s\n' "$legacy" > "$ROUTE_STATS_FILE"
    update_route_candidate_stats 20.0.0.1 200 100
    update_route_candidate_stats 20.0.0.1 200 100
    update_route_candidate_stats 20.0.0.1 200 300
    [[ "$(awk -F '\t' '$1=="20.0.0.1" {print NF ":" $15}' "$ROUTE_STATS_FILE")" == '15:60' ]] || fail 'probe variation EWMA is not 60'
    grep -Fxq "$legacy" "$ROUTE_STATS_FILE" || fail 'unrelated legacy row changed'
    update_route_candidate_stats 20.0.0.1 0 0
    [[ "$(awk -F '\t' '$1=="20.0.0.1" {print $15}' "$ROUTE_STATS_FILE")" == 60 ]] || fail 'failed probe changed variation'
    update_route_candidate_stats 20.0.0.2 200 110
    [[ "$(awk -F '\t' '$1=="20.0.0.2" {print NF ":" $15}' "$ROUTE_STATS_FILE")" == '15:3' ]] || fail 'legacy row did not upgrade safely'
    pass 'probe variation tracks successes, ignores failures, and migrates legacy rows'
}

test_probe_variation_only_breaks_route_score_ties() {
    reset_runtime_paths
    local now routes summary
    now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    printf '20.0.0.1\t10\t10\t0\t100\t100\t100\t100\t200\ttrue\t%s\t100\t0\tready\t10000\n' "$now" > "$ROUTE_STATS_FILE"
    printf '20.0.0.2\t10\t10\t0\t100\t100\t100\t100\t200\ttrue\t%s\t100\t0\tready\t0\n' "$now" >> "$ROUTE_STATS_FILE"
    printf '20.0.0.3\t10\t10\t0\t90\t90\t90\t90\t200\ttrue\t%s\t90\t0\tready\t20000\n' "$now" >> "$ROUTE_STATS_FILE"
    for ip in 20.0.0.1 20.0.0.2 20.0.0.3; do printf '%s\t%s\t200\t100\ttrue\tcache\tready\n' "$now" "$ip" >> "$ROUTE_HEALTH_FILE"; done
    routes=$(cached_usable_fallback_ips)
    [[ "$routes" == $'20.0.0.3\n20.0.0.2\n20.0.0.1' ]] || fail "variation displaced a faster route: $routes"
    summary=$(route_candidate_health_summary | awk '/^20\./ {print $1}')
    [[ "$summary" == "$routes" ]] || fail 'diagnostic order differs from export order'
    pass 'probe variation breaks ties without displacing a faster route'
}

test_menu_route_summary_is_quiet_and_cache_only() {
    (
        reset_runtime_paths
        [[ "$(menu_best_route_summary)" == 'no cached probes yet' ]] || fail 'empty menu state is misleading'
        local now before
        now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
        printf '%s\t20.0.0.1\t200\t100\ttrue\tcache\tready\n' "$now" > "$ROUTE_HEALTH_FILE"
        printf '20.0.0.1\t5\t5\t0\t100\t100\t100\t100\t200\ttrue\t%s\n' "$now" > "$ROUTE_STATS_FILE"
        printf '20.0.0.1\n' > "$PINNED_ROUTE_FILE"
        [[ "$(menu_best_route_summary)" == '20.0.0.1 (server probe EWMA 100ms) [pinned]' ]] || fail 'menu ignores pinned legacy route'
        before=$(wc -c < "$LOG_FILE")
        curl() { fail 'menu probed the network'; }
        file_age_sec() { printf '350'; }
        [[ "$(menu_best_route_summary)" == *'[stale cache]'* ]] || fail 'provisional old cache was not marked stale'
        file_age_sec() { printf '999999'; }
        [[ "$(menu_best_route_summary)" == 'expired cache (option 17)' ]] || fail 'expired route was shown as current'
        [[ "$(wc -c < "$LOG_FILE")" == "$before" ]] || fail 'rendering menu wrote stale-cache warnings'
    )
    pass 'server route menu is cache-only, quiet, and honest about stale data'
}

test_degraded_probe_queues_without_scanning_or_restarting() {
    (
        reset_runtime_paths
        refresh_route_candidate_health() { fail 'health observation scanned routes'; }
        start_xray() { fail 'latency caused an engine restart'; }
        repair_codespace_port_route() { fail 'latency changed port visibility'; }
        for i in 1 2 3; do observe_route_latency 200 100; done
        observe_route_latency 200 900
        [[ ! -e "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'one spike queued a refresh'
        observe_route_latency 200 900
        [[ -s "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'consecutive degradation did not queue a scan'
        rm -f "$ROUTE_DEGRADED_REQUEST_FILE"
        observe_route_latency 200 5000
        [[ ! -e "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'degradation ignored its cooldown'
    )
    pass 'degradation queues a cooldown-limited scan without touching the engine or ports'
}

test_degraded_probe_resets_chain_on_outage_or_healthy_sample() {
    reset_runtime_paths
    local old_stamp
    for i in 1 2 3; do observe_route_latency 200 100; done
    observe_route_latency 200 900
    observe_route_latency 0 0
    observe_route_latency 200 900
    [[ ! -e "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'outage did not break consecutive samples'
    observe_route_latency 200 100
    observe_route_latency 200 900
    [[ ! -e "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'healthy probe did not reset degradation'
    old_stamp=$(( $(date +%s) - 1900 ))
    printf '%s %s 100 10 1\n' "$PORT_DOMAIN" "$old_stamp" > "$ROUTE_LATENCY_BASELINE_FILE"
    observe_route_latency 200 900
    [[ ! -e "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'expired baseline triggered a refresh after suspension'
    printf 'other-space 1 100 10 1\n' > "$ROUTE_LATENCY_BASELINE_FILE"
    observe_route_latency 200 900
    [[ ! -e "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'another Codespace baseline was reused'
    printf '%s 008 008 008 008\n' "$PORT_DOMAIN" > "$ROUTE_LATENCY_BASELINE_FILE"
    observe_route_latency 200 900 || fail 'corrupt leading-zero state raised an arithmetic error'
    pass 'degradation resets across outages, healthy probes, suspension, identity changes, and corrupt state'
}

test_degraded_probe_keeps_healthy_baseline_at_threshold() {
    reset_runtime_paths
    for i in 1 2 3; do observe_route_latency 200 100; done
    observe_route_latency 200 600
    [[ "$(awk '{print $3 ":" $5}' "$ROUTE_LATENCY_BASELINE_FILE")" == '100:1' ]] \
        || fail 'a degraded sample contaminated the healthy baseline'
    [[ ! -e "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'one threshold sample queued a scan'
    observe_route_latency 200 600
    [[ -s "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'sustained threshold degradation was normalized away'
    observe_route_latency 200 110
    [[ "$(awk '{print $3 ":" $5}' "$ROUTE_LATENCY_BASELINE_FILE")" == '103:0' ]] \
        || fail 'healthy samples stopped updating the baseline or clearing the chain'
    pass 'threshold degradation cannot move its own baseline and healthy samples still adapt'
}

test_optional_legacy_bundle_failure_preserves_current_exports() {
    (
        reset_runtime_paths
        local legacy_file="${CODESPACE_BUNDLE_FILE%.json}.legacy.json"
        local link='vless://test@20.0.0.1:443?type=xhttp&extra=%7B%22xmux%22%3A%7B%7D%7D'
        printf 'stale legacy artifact\n' > "$legacy_file"
        _atomic_write() {
            [[ "$1" != "$legacy_file" ]] || return 1
            printf '%s\n' "$2" > "$1"
        }
        write_config_exports_from_links "$link" || fail 'optional legacy failure invalidated current exports'
        jq -e --arg link "$link" '.schema_version == 2 and .configs == [$link]' "$CODESPACE_BUNDLE_FILE" >/dev/null \
            || fail 'current bundle lost its exact link or schema'
        [[ "$(cat "$MOBILE_CONFIG_FILE")" == "$link" && -s "$CONFIG_META_FILE" && -s "$SUBSCRIPTION_FILE" ]] \
            || fail 'legacy failure left current exports incomplete'
        [[ ! -e "$legacy_file" ]] || fail 'failed legacy refresh kept a stale import artifact'
        grep -Fq 'legacy_bundle_write_failed' "$LOG_FILE" || fail 'legacy failure was not reported'
    )
    pass 'optional legacy failures do not invalidate current exports or leave a stale legacy artifact'
}

test_codespace_bundle_rejects_missing_or_empty_links() {
    (
        reset_runtime_paths
        local input rc
        printf 'preserve previous bundle\n' > "$CODESPACE_BUNDLE_FILE"
        rc=0
        (write_codespace_import_bundle) 2>/dev/null || rc=$?
        [[ "$rc" == 2 ]] || fail "missing bundle links returned $rc instead of a validation error"
        for input in '' $' \t\n'; do
            rc=0
            write_codespace_import_bundle "$input" || rc=$?
            [[ "$rc" == 2 ]] || fail "empty bundle links returned $rc instead of a validation error"
        done
        [[ "$(cat "$CODESPACE_BUNDLE_FILE")" == 'preserve previous bundle' ]] \
            || fail 'invalid input overwrote an existing bundle'
        [[ ! -e "${CODESPACE_BUNDLE_FILE%.json}.legacy.json" ]] || fail 'invalid input wrote a legacy bundle'
    )
    pass 'missing and whitespace-only bundle links fail without modifying existing artifacts'
}

test_codespace_bundle_primary_write_failure_still_fails() {
    (
        reset_runtime_paths
        printf 'previous primary\n' > "$CODESPACE_BUNDLE_FILE"
        _atomic_write() {
            [[ "$1" != "$CODESPACE_BUNDLE_FILE" ]] || return 1
            fail 'primary bundle failure continued writing artifacts'
        }
        if write_codespace_import_bundle 'vless://test@20.0.0.1:443?type=xhttp'; then
            fail 'primary bundle write failure was hidden'
        fi
        [[ "$(cat "$CODESPACE_BUNDLE_FILE")" == 'previous primary' ]] || fail 'primary failure damaged the previous bundle'
    )
    pass 'primary bundle write failures remain fatal and preserve the previous artifact'
}

test_degraded_refresh_forces_fresh_scan_outside_health_deadline() {
    (
        reset_runtime_paths
        local calls="$TMP_ROOT/degraded-calls"
        : > "$calls"
        printf '%s %s\n' "$PORT_DOMAIN" "$(date +%s)" > "$ROUTE_DEGRADED_REQUEST_FILE"
        write_background_supervisor_heartbeat() { printf 'heartbeat\n' >> "$calls"; }
        run_with_deadline() { printf 'deadline=%s function=%s\n' "$1" "$2" >> "$calls"; shift; "$@"; }
        refresh_route_candidate_health() { [[ "${1:-}" == --force ]] || fail 'degraded scan did not bypass fresh cache'; route_refresh_record_success; }
        refresh_config_exports_if_changed() { printf 'exports\n' >> "$calls"; }
        route_health_cache_fresh() { return 0; }
        refresh_degraded_routes_if_requested
        [[ ! -e "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'degraded request was not consumed'
        grep -Fq 'function=refresh_route_candidate_health' "$calls" || fail 'route deadline was not used'
        grep -Fxq exports "$calls" || fail 'successful rerank did not refresh exports'
        [[ "$(grep -c heartbeat "$calls")" == 3 ]] || fail 'long scan failed to maintain heartbeat'
        [[ "$(grep -c deadline= "$calls")" == 2 ]] || fail 'degraded refresh added a nested self-heal deadline'
    )
    pass 'degraded refresh forces a real scan outside the health deadline and maintains heartbeat'
}

test_degraded_refresh_respects_backoff_and_retains_failed_exports() {
    (
        reset_runtime_paths
        printf '%s %s\n' "$PORT_DOMAIN" "$(date +%s)" > "$ROUTE_DEGRADED_REQUEST_FILE"
        route_refresh_retry_allowed() { return 1; }
        refresh_route_candidate_health() { fail 'backoff was bypassed'; }
        refresh_degraded_routes_if_requested
        [[ -s "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'backoff lost pending request'
        route_refresh_retry_allowed() { return 0; }
        local heartbeats="$TMP_ROOT/degraded-failure-heartbeats"
        : > "$heartbeats"
        write_background_supervisor_heartbeat() { printf 'heartbeat\n' >> "$heartbeats"; }
        run_with_deadline() { return 124; }
        refresh_config_exports_if_changed() { fail 'timed-out scan overwrote exports'; }
        refresh_degraded_routes_if_requested
        [[ ! -e "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'timed-out request was not consumed'
        [[ "$(wc -l < "$heartbeats")" -eq 2 ]] || fail 'timed-out scan failed to renew the supervisor heartbeat'
    )
    pass 'degraded refresh respects backoff and keeps exports when a scan times out'
}

test_route_quality_uses_export_snapshot_and_escapes_timestamps() {
    reset_runtime_paths
    local links quality
    printf '20.0.0.1\t10\t10\t0\t100\t100\t100\t100\t200\ttrue\tbad"\\stamp\t100\t0\tready\t60\n' > "$ROUTE_STATS_FILE"
    printf '20.0.0.2\t5\t5\t0\t200\t200\t200\t200\t200\ttrue\tlegacy\n' >> "$ROUTE_STATS_FILE"
    links=$'vless://test@20.0.0.2:443?type=xhttp\nvless://test@20.0.0.1:443?type=xhttp\nvless://test@20.0.0.2:443?type=ws\nvless://test@domain.example:443?type=xhttp'
    write_config_exports_from_links "$links"
    jq -e '.routes | length == 2' "$CONFIG_META_FILE" >/dev/null || fail 'metadata contains duplicate or domain routes'
    jq -e '.routes[0].ip == "20.0.0.2" and .routes[0].rank == 1 and .routes[0].probe_variation_ms == 0 and .routes[0].ewma_latency_ms == 200 and .routes[1].ip == "20.0.0.1" and .routes[1].probe_variation_ms == 60' "$CONFIG_META_FILE" >/dev/null || fail 'route metadata lost export order or legacy stats'
    quality=$(route_quality_json '20.0.0.1')
    [[ "$(jq -r '.[0].last_checked' <<< "$quality")" == 'bad"\stamp' ]] || fail 'timestamp escaping corrupted metadata'
    write_config_exports_from_links 'vless://test@domain.example:443?type=xhttp'
    jq -e '.routes == []' "$CONFIG_META_FILE" >/dev/null || fail 'domain-only export advertised unrelated cached IPs'
    pass 'metadata describes actual exported routes and escapes corrupt legacy timestamps'
}

test_route_quality_rejects_partial_json_and_parser_failures() {
    (
        reset_runtime_paths
        route_quality_json() { printf '[broken]'; }
        write_config_exports_from_links 'vless://test@20.0.0.1:443?type=xhttp'
        jq -e '.routes == []' "$CONFIG_META_FILE" >/dev/null || fail 'bracket-wrapped malformed JSON survived validation'
    )
    (
        reset_runtime_paths
        printf 'row\n' > "$ROUTE_STATS_FILE"
        jq() { printf '[partial'; return 1; }
        [[ "$(route_quality_json '20.0.0.1')" == '[]' ]] || fail 'failed parser appended [] to truncated JSON'
    )
    pass 'metadata rejects malformed arrays and discards partial parser output'
}

test_route_quality_rejects_unbounded_numbers_and_invalid_ewma() {
    reset_runtime_paths
    local quality
    printf '20.0.0.1\t99999999999999999999999999999999999\t1\t0\t200\t200\t200\t200\t200\ttrue\tstamp\tbroken\t0\tready\t99999999999999999999999999999999999\n' > "$ROUTE_STATS_FILE"
    quality=$(route_quality_json '20.0.0.1')
    jq -e '.[0].samples == 0 and .[0].probe_variation_ms == 0 and .[0].ewma_latency_ms == 200' <<< "$quality" >/dev/null || fail 'corrupt stats became nonfinite metadata or a false zero-latency route'
    pass 'metadata bounds corrupt numbers and falls back from invalid EWMA to average latency'
}

test_updater_version_gate_preserves_same_version_fixes() {
    (
        reset_runtime_paths
        local panel="$TMP_ROOT/candidate-panel"
        printf '# G2ray Panel v9.9.9\nreadonly G2RAY_ID="G2ray Panel v1.4.3"\n' > "$panel"
        [[ "$(panel_version_of_file "$panel")" == 1.4.3 ]] || fail 'updater parsed a version from a comment'
        update_candidate_version_ok "$panel" 1.4.3 || fail 'same-version fixes were disabled'
        if update_candidate_version_ok "$panel" 1.4.4; then fail 'downgrade was allowed'; fi
        version_is_older 1.9.9 1.10.0 || fail 'numeric version ordering is broken'
        G2RAY_AUTO_UPDATE_FORCE=1 update_candidate_version_ok "$panel" 1.4.4 || fail 'explicit downgrade override failed'
        printf 'readonly G2RAY_ID="G2ray Panel vbogus"\n' > "$panel"
        if G2RAY_AUTO_UPDATE_FORCE=1 update_candidate_version_ok "$panel" 1.4.3; then fail 'force accepted an unparseable version'; fi
    )
    pass 'updater blocks downgrades without rejecting same-version bug fixes or parsing comments'
}

test_update_audit_is_bounded_and_hides_source_credentials() {
    (
        reset_runtime_paths
        RAW_BASE_URL='https://user:password@example.invalid/raw?token=secret'
        LOG_MAX_BYTES=1
        record_update_audit refused_version 1.4.3 1.4.2 abc
        record_update_audit applied 1.4.3 1.4.4 def
        [[ -s "$UPDATE_AUDIT_FILE.1" ]] || fail 'update audit did not rotate'
        grep -Fq 'applied' "$UPDATE_AUDIT_FILE" || fail 'update decision was lost'
        if grep -Eq 'password|token=|secret' "$UPDATE_AUDIT_FILE" "$UPDATE_AUDIT_FILE.1"; then fail 'audit exposed authenticated source URL'; fi
    )
    pass 'update audit is bounded and never records authenticated source URLs'
}

test_updater_failed_replace_never_reports_applied() {
    (
        reset_runtime_paths
        SCRIPT_PATH="$TMP_ROOT/update-panel.sh"
        cp "$SCRIPT" "$SCRIPT_PATH"
        G2RAY_AUTO_UPDATE=1
        clear() { :; }; draw_logo() { :; }; sleep() { :; }
        latency_focus_enabled() { return 1; }; tracked_panel_has_local_changes() { return 1; }
        curl() {
            local output=""
            while (($#)); do [[ "$1" == -o ]] && { output="$2"; break; }; shift; done
            printf 'readonly G2RAY_ID="G2ray Panel v1.4.4"\n' > "$output"
        }
        verify_update_candidate() { return 0; }
        mv() { return 1; }
        exec() { fail 'failed update restarted the panel'; }
        check_for_updates >/dev/null
        grep -Fq 'apply_failed' "$UPDATE_AUDIT_FILE" || fail 'failed replacement was not audited'
        if grep -Fq $'\tapplied\t' "$UPDATE_AUDIT_FILE"; then fail 'audit claimed an unapplied update'; fi
        cmp -s "$SCRIPT" "$SCRIPT_PATH" || fail 'failed replacement changed the previous panel'
    )
    pass 'failed update replacement keeps the panel and never audits success'
}

test_updater_preserves_edits_made_during_download() {
    (
        reset_runtime_paths
        SCRIPT_PATH="$TMP_ROOT/update-race-panel.sh"
        cp "$SCRIPT" "$SCRIPT_PATH"
        G2RAY_AUTO_UPDATE=1
        clear() { :; }; draw_logo() { :; }; sleep() { :; }
        latency_focus_enabled() { return 1; }; tracked_panel_has_local_changes() { return 1; }
        curl() {
            local output=""
            while (($#)); do [[ "$1" == -o ]] && { output="$2"; break; }; shift; done
            printf 'readonly G2RAY_ID="G2ray Panel v1.4.4"\n' > "$output"
            printf '\n# concurrent local edit\n' >> "$SCRIPT_PATH"
        }
        verify_update_candidate() { return 0; }
        exec() { fail 'updater overwrote a concurrent edit'; }
        check_for_updates >/dev/null
        grep -Fq changed_during_check "$UPDATE_AUDIT_FILE" || fail 'concurrent edit was not protected'
        grep -Fq '# concurrent local edit' "$SCRIPT_PATH" || fail 'concurrent edit was lost'
    )
    pass 'updater preserves local edits made while a download is in flight'
}

test_updater_success_stages_locally_and_preserves_permissions() {
    (
        reset_runtime_paths
        local before_mode
        SCRIPT_PATH="$TMP_ROOT/update-success-panel.sh"
        cp "$SCRIPT" "$SCRIPT_PATH"
        chmod 755 "$SCRIPT_PATH"
        before_mode=$(stat -c '%a' "$SCRIPT_PATH")
        G2RAY_AUTO_UPDATE=1
        clear() { :; }; draw_logo() { :; }; sleep() { :; }
        latency_focus_enabled() { return 1; }; tracked_panel_has_local_changes() { return 1; }
        curl() {
            local output=""
            while (($#)); do [[ "$1" == -o ]] && { output="$2"; break; }; shift; done
            printf '#!/usr/bin/env bash\nreadonly G2RAY_ID="G2ray Panel v1.4.3"\n' > "$output"
        }
        verify_update_candidate() { return 0; }
        mv() {
            [[ "$2" == "$SCRIPT_PATH.update."* ]] || fail 'update was not staged beside its destination'
            command mv "$@"
        }
        exec() {
            [[ "$1" == bash && "$2" == "$SCRIPT_PATH" ]] || fail 'update restarted a different panel'
            printf 'restarted\n' > "$TMP_ROOT/update-success-restarted"
        }
        check_for_updates >/dev/null
        [[ "$(panel_version_of_file "$SCRIPT_PATH")" == 1.4.3 ]] || fail 'same-version changed panel was not installed'
        [[ "$(stat -c '%a' "$SCRIPT_PATH")" == "$before_mode" ]] || fail 'replacement changed panel permissions'
        [[ -s "$TMP_ROOT/update-success-restarted" ]] || fail 'successful replacement did not restart the panel'
        grep -Fq $'\tapplied\t' "$UPDATE_AUDIT_FILE" || fail 'successful replacement was not audited'
    )
    pass 'successful same-version update uses an atomic local rename and preserves permissions'
}

test_updater_refuses_downgrade_end_to_end() {
    (
        reset_runtime_paths
        SCRIPT_PATH="$TMP_ROOT/update-downgrade-panel.sh"
        cp "$SCRIPT" "$SCRIPT_PATH"
        G2RAY_AUTO_UPDATE=1
        unset G2RAY_AUTO_UPDATE_FORCE
        clear() { :; }; draw_logo() { :; }; sleep() { :; }
        latency_focus_enabled() { return 1; }; tracked_panel_has_local_changes() { return 1; }
        curl() {
            local output=""
            while (($#)); do [[ "$1" == -o ]] && { output="$2"; break; }; shift; done
            sed 's/^readonly G2RAY_ID="G2ray Panel v1\.4\.3"$/readonly G2RAY_ID="G2ray Panel v1.4.2"/' "$SCRIPT" > "$output"
        }
        mv() { fail 'downgrade attempted to replace the panel'; }
        exec() { fail 'downgrade restarted the panel'; }
        check_for_updates >/dev/null
        cmp -s "$SCRIPT" "$SCRIPT_PATH" || fail 'downgrade changed the installed panel'
        grep -Fq $'\trefused_version\tfrom=1.4.3\tto=1.4.2\t' "$UPDATE_AUDIT_FILE" || fail 'downgrade was not rejected after real structural validation'
    )
    pass 'updater rejects a structurally valid downgrade without replacing or restarting the panel'
}

test_updater_rejects_failed_download_even_if_candidate_is_valid_bash() {
    (
        reset_runtime_paths
        SCRIPT_PATH="$TMP_ROOT/update-incomplete-panel.sh"
        cp "$SCRIPT" "$SCRIPT_PATH"
        G2RAY_AUTO_UPDATE=1
        clear() { :; }; draw_logo() { :; }; sleep() { :; }
        latency_focus_enabled() { return 1; }; tracked_panel_has_local_changes() { return 1; }
        curl() {
            local output=""
            while (($#)); do [[ "$1" == -o ]] && { output="$2"; break; }; shift; done
            sed 's/^readonly G2RAY_ID="G2ray Panel v1\.4\.3"$/readonly G2RAY_ID="G2ray Panel v1.4.4"/' "$SCRIPT" > "$output"
            return 28
        }
        verify_update_candidate() { fail 'failed download reached structural validation'; }
        mv() { fail 'failed download attempted to replace the panel'; }
        exec() { fail 'failed download restarted the panel'; }
        check_for_updates >/dev/null
        cmp -s "$SCRIPT" "$SCRIPT_PATH" || fail 'failed download changed the installed panel'
        grep -Fq $'\trejected\t' "$UPDATE_AUDIT_FILE" || fail 'failed download was not audited'
    )
    pass 'updater refuses failed downloads even when their partial contents look like valid Bash'
}

test_updater_audits_failed_staging() {
    (
        reset_runtime_paths
        SCRIPT_PATH="$TMP_ROOT/update-stage-failed-panel.sh"
        cp "$SCRIPT" "$SCRIPT_PATH"
        G2RAY_AUTO_UPDATE=1
        clear() { :; }; draw_logo() { :; }; sleep() { :; }
        latency_focus_enabled() { return 1; }; tracked_panel_has_local_changes() { return 1; }
        curl() {
            local output=""
            while (($#)); do [[ "$1" == -o ]] && { output="$2"; break; }; shift; done
            printf '#!/usr/bin/env bash\nreadonly G2RAY_ID="G2ray Panel v1.4.4"\n' > "$output"
        }
        verify_update_candidate() { return 0; }
        mktemp() {
            [[ "$1" == "$SCRIPT_PATH.update."* ]] && return 1
            command mktemp "$@"
        }
        exec() { fail 'failed staging restarted the panel'; }
        check_for_updates >/dev/null
        cmp -s "$SCRIPT" "$SCRIPT_PATH" || fail 'failed staging changed the installed panel'
        grep -Fq $'\tapply_failed\t' "$UPDATE_AUDIT_FILE" || fail 'failed staging was not audited'
    )
    pass 'updater audits failed staging without changing or restarting the panel'
}

test_self_heal_integrates_degradation_without_disruption() {
    (
        reset_runtime_paths
        write_test_vless_config
        ensure_codespace_port_public() { return 0; }
        ensure_optional_ws_port_public() { return 0; }
        xray_running() { return 0; }
        xray_listener_ready() { return 0; }
        xhttp_probe_metrics() { printf '200 900 ready\n'; }
        start_xray() { fail 'HTTP 200 latency spike restarted a working engine'; }
        repair_codespace_port_route() { fail 'HTTP 200 latency spike reset the public route'; }
        for i in 1 2 3; do observe_route_latency 200 100; done
        self_heal_once
        [[ ! -e "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'self-heal queued a scan after one spike'
        self_heal_once
        [[ -s "$ROUTE_DEGRADED_REQUEST_FILE" ]] || fail 'self-heal did not pass usable latencies to degradation tracking'
    )
    pass 'real self-heal queues consecutive degradation without restarting the engine or resetting ports'
}

test_xhttp_upload_tuning_is_reversible_and_bundle_compatible() {
    reset_runtime_paths
    local link
    printf '11111111-2222-4333-8444-555555555555\n' > "$UUID_FILE"
    write_test_vless_config
    jq -e '.scMaxEachPostBytes == 60000 and .scMinPostsIntervalMs == 10 and .xmux.maxConnections >= 1 and .xmux.maxConnections <= 6 and .xmux.hKeepAlivePeriod > 0' <<< "$XHTTP_TUNED_EXTRA_JSON" >/dev/null || fail 'measured tuning is invalid or unbounded'
    link=$(generate_link_for_address 20.0.0.1)
    [[ "$link" == *"extra=$(url_encode_query_value "$XHTTP_TUNED_EXTRA_JSON")"* ]] || fail 'tuned links lost their extra object'
    write_codespace_import_bundle "$link"
    jq -e --arg link "$link" '.schema_version == 2 and .configs[0] == $link' "$CODESPACE_BUNDLE_FILE" >/dev/null || fail 'current bundle changed the direct transport configuration'
    jq -e '.schema_version == 1 and .compatibility == "legacy_without_xhttp_extra" and (.configs[0] | contains("extra=") | not)' "${CODESPACE_BUNDLE_FILE%.json}.legacy.json" >/dev/null || fail 'legacy bundle lost its explicit compatibility contract'
    G2RAY_XHTTP_LINK_TUNING=off
    link=$(generate_link_for_address 20.0.0.1)
    [[ "$link" != *'&extra='* ]] || fail 'upload tuning off switch does not work'
    unset G2RAY_XHTTP_LINK_TUNING
    set_xhttp_link_tuning off
    xhttp_link_tuning_enabled && fail 'saved upload tuning off switch was ignored'
    pass 'upload tuning has a real off switch and preserves legacy Android bundle compatibility'
}

test_port_visibility_is_throttled() {
    reset_runtime_paths
    PORT_PUBLIC_TTL_SEC=300
    XRAY_PORT=443
    CODESPACE_NAME="behavior-space"
    local calls_file="$TMP_ROOT/gh-calls.txt"
    : > "$calls_file"
    run_gh() {
        printf 'call\n' >> "$calls_file"
        return 0
    }

    ensure_codespace_port_public >/dev/null || fail "first public-port call failed"
    ensure_codespace_port_public >/dev/null || fail "cached public-port call failed"
    local calls
    calls=$(wc -l < "$calls_file" | tr -d ' ')
    [[ "$calls" -eq 1 ]] || fail "expected cached port visibility to avoid second gh call, got $calls"

    ensure_codespace_port_public force >/dev/null || fail "forced public-port call failed"
    calls=$(wc -l < "$calls_file" | tr -d ' ')
    [[ "$calls" -eq 2 ]] || fail "expected forced port visibility to call gh again, got $calls"
    pass "port visibility calls are throttled and forceable"
}

test_codespace_detection_uses_shared_environment_in_headless_ssh() {
    (
        reset_runtime_paths
        CODESPACE_NAME=""
        CODESPACE_ENV_JSON_FILE="$TMP_ROOT/shared-environment-variables.json"
        CODESPACE_SHARED_ENV_FILE="$TMP_ROOT/missing-shared.env"
        CONFIG_META_FILE="$TMP_ROOT/missing-configs-meta.json"
        WAKER_METADATA_FILE="$DATA_DIR/missing-waker-metadata.txt"
        PORT_PUBLIC_STAMP_FILE="$DATA_DIR/port_public_last"
        printf '{ "CODESPACE_NAME": "real-codespace-name-123" }\n' > "$CODESPACE_ENV_JSON_FILE"
        run_gh() { return 1; }
        hostname() { printf 'codespaces-container-hostname\n'; }

        local detected
        detected="$(_detect_codespace_name)"
        [[ "$detected" == "real-codespace-name-123" ]] \
            || fail "headless detection used '$detected' instead of Codespaces shared environment"
    )
    pass "codespace detection uses shared environment in headless SSH sessions"
}

test_codespace_detection_uses_local_metadata_when_gh_is_unauthenticated() {
    (
        reset_runtime_paths
        CODESPACE_NAME=""
        CODESPACE_ENV_JSON_FILE="$TMP_ROOT/missing-shared-environment.json"
        CODESPACE_SHARED_ENV_FILE="$TMP_ROOT/missing-shared.env"
        CONFIG_META_FILE="$TMP_ROOT/missing-configs-meta.json"
        PORT_PUBLIC_STAMP_FILE="$DATA_DIR/port_public_last"
        printf 'worker_url=https://example.invalid/wake\ncodespace_name=metadata-codespace-name\n' > "$WAKER_METADATA_FILE"
        run_gh() { return 1; }
        hostname() { printf 'codespaces-container-hostname\n'; }

        local detected
        detected="$(_detect_codespace_name)"
        [[ "$detected" == "metadata-codespace-name" ]] \
            || fail "headless detection used '$detected' instead of local waker metadata"
    )
    pass "codespace detection uses local metadata when gh is unauthenticated"
}

test_port_domains_use_codespaces_forwarding_domain_env() {
    (
        reset_runtime_paths
        CODESPACE_NAME="custom-domain-space"
        GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN="ports.example.test"
        refresh_port_domains

        [[ "$PORT_DOMAIN" == "custom-domain-space-443.ports.example.test" ]] \
            || fail "PORT_DOMAIN ignored GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN: $PORT_DOMAIN"
        [[ "$WS_PORT_DOMAIN" == "custom-domain-space-8443.ports.example.test" ]] \
            || fail "WS_PORT_DOMAIN ignored GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN: $WS_PORT_DOMAIN"
    )
    pass "port domains use Codespaces forwarding-domain environment"
}

test_run_gh_uses_shared_codespaces_token_when_shell_is_unauthenticated() {
    (
        reset_runtime_paths
        eval "$ORIGINAL_RUN_GH"
        unset GH_TOKEN GITHUB_TOKEN
        CODESPACE_SHARED_ENV_FILE="$TMP_ROOT/shared.env"
        printf 'GITHUB_TOKEN=shared-token-for-test\n' > "$CODESPACE_SHARED_ENV_FILE"
        cat > "$TMP_ROOT/bin/gh" <<'SH'
#!/usr/bin/env bash
if [[ "${GH_TOKEN:-}" != "shared-token-for-test" ]]; then
    echo "missing shared GH_TOKEN" >&2
    exit 31
fi
printf 'ok\n'
SH
        chmod +x "$TMP_ROOT/bin/gh"
        local output
        output="$(run_gh codespace ports -c behavior-space)" \
            || fail "run_gh did not use the shared Codespaces token when shell auth was missing"
        [[ "$output" == "ok" ]] || fail "run_gh returned unexpected output: $output"
    )
    pass "run_gh uses shared Codespaces token when shell is unauthenticated"
}

test_runtime_lock_serializes_operations_and_allows_reentry() {
    reset_runtime_paths
    RUNTIME_LOCK_WAIT_ATTEMPTS=1
    local owner_pid
    sleep 10 &
    owner_pid=$!
    mkdir -p "$RUNTIME_LOCK_DIR"
    printf '%s\n' "$owner_pid" > "$RUNTIME_LOCK_DIR/pid"
    local ran_file="$TMP_ROOT/runtime-lock-ran.txt"
    runtime_lock_probe() { printf 'ran\n' > "$ran_file"; }

    if with_runtime_lock runtime_lock_probe; then
        kill "$owner_pid" 2>/dev/null || true
        fail "runtime lock allowed operation while a live owner held the lock"
    fi
    kill "$owner_pid" 2>/dev/null || true
    [[ ! -e "$ran_file" ]] || fail "runtime operation ran while lock was busy"

    rm -rf "$RUNTIME_LOCK_DIR"
    mkdir -p "$RUNTIME_LOCK_DIR"
    printf '%s\n' "$$" > "$RUNTIME_LOCK_DIR/pid"
    with_runtime_lock runtime_lock_probe || fail "runtime lock did not clean up stale same-process lock"
    grep -Fxq 'ran' "$ran_file" || fail "runtime operation did not run after stale same-process lock cleanup"
    [[ ! -d "$RUNTIME_LOCK_DIR" ]] || fail "runtime lock was not released after successful command"
    rm -f "$ran_file"

    rm -rf "$RUNTIME_LOCK_DIR"
    mkdir -p "$RUNTIME_LOCK_DIR"
    with_runtime_lock runtime_lock_probe || fail "runtime lock did not recover an empty lock directory"
    grep -Fxq 'ran' "$ran_file" || fail "runtime operation did not run after empty lock recovery"
    [[ ! -d "$RUNTIME_LOCK_DIR" ]] || fail "runtime lock was not released after empty lock recovery"
    rm -f "$ran_file"

    rm -rf "$RUNTIME_LOCK_DIR"
    mkdir -p "$RUNTIME_LOCK_DIR"
    printf 'not-a-pid\n' > "$RUNTIME_LOCK_DIR/pid"
    with_runtime_lock runtime_lock_probe || fail "runtime lock did not recover malformed pid lock"
    grep -Fxq 'ran' "$ran_file" || fail "runtime operation did not run after malformed lock recovery"
    [[ ! -d "$RUNTIME_LOCK_DIR" ]] || fail "runtime lock was not released after malformed lock recovery"
    rm -f "$ran_file"

    rm -rf "$RUNTIME_LOCK_DIR"
    runtime_lock_inner() { printf 'inner\n' > "$ran_file"; }
    runtime_lock_outer() { with_runtime_lock runtime_lock_inner; }
    with_runtime_lock runtime_lock_outer || fail "runtime lock was not reentrant for nested runtime operations"
    grep -Fxq 'inner' "$ran_file" || fail "nested runtime operation did not run"
    [[ ! -d "$RUNTIME_LOCK_DIR" ]] || fail "runtime lock was not released after nested successful command"

    rm -rf "$RUNTIME_LOCK_DIR"
    runtime_lock_assert_held_and_fail() {
        [[ -d "$RUNTIME_LOCK_DIR" ]] || fail "runtime lock was not held while command ran"
        [[ "$(cat "$RUNTIME_LOCK_DIR/pid" 2>/dev/null || true)" == "$$" ]] || fail "runtime lock owner was not current shell"
        return 7
    }
    local rc=0
    if with_runtime_lock runtime_lock_assert_held_and_fail; then
        fail "runtime lock wrapper swallowed command failure"
    else
        rc=$?
    fi
    [[ "$rc" -eq 7 ]] || fail "runtime lock wrapper did not preserve command failure status, got $rc"
    [[ ! -d "$RUNTIME_LOCK_DIR" ]] || fail "runtime lock was not released after a failing command"

    pass "runtime lock serializes operations and allows same-process reentry"
}

test_runtime_lock_distinguishes_background_subshells() {
    reset_runtime_paths
    local result_file="$TMP_ROOT/runtime-sibling-result.txt" child
    acquire_runtime_lock "parent" || fail "parent could not acquire runtime lock"
    (
        RUNTIME_LOCK_WAIT_ATTEMPTS=1
        unset G2RAY_RUNTIME_LOCK_HELD
        if acquire_runtime_lock "background-sibling"; then
            printf 'stolen\n' > "$result_file"
            release_runtime_lock
        else
            printf 'blocked\n' > "$result_file"
        fi
    ) &
    child=$!
    wait "$child"
    release_runtime_lock
    [[ "$(cat "$result_file" 2>/dev/null || true)" == "blocked" ]] \
        || fail "background subshell was mistaken for the parent runtime-lock owner"
    pass "runtime lock distinguishes sibling Bash processes"
}

test_stop_xray_succeeds_when_engine_is_already_stopped() {
    (
        reset_runtime_paths
        owned_xray_pids() { return 0; }
        save_xray_stats() { return 0; }

        stop_xray || fail "stop_xray returned failure when there was no owned Xray process"
    )
    pass "stop_xray succeeds when engine is already stopped"
}

test_port_visibility_cache_is_scoped_by_codespace_and_port() {
    reset_runtime_paths
    PORT_PUBLIC_TTL_SEC=300
    CODESPACE_NAME="behavior-space-a"
    XRAY_PORT=443
    local calls_file="$TMP_ROOT/gh-scoped-calls.txt"
    : > "$calls_file"
    run_gh() {
        printf '%s:%s\n' "$CODESPACE_NAME" "$XRAY_PORT" >> "$calls_file"
        return 0
    }

    ensure_codespace_port_public >/dev/null || fail "first scoped public-port call failed"
    CODESPACE_NAME="behavior-space-b"
    ensure_codespace_port_public >/dev/null || fail "second codespace public-port call failed"
    CODESPACE_NAME="behavior-space-a"
    XRAY_PORT=8443
    ensure_codespace_port_public >/dev/null || fail "second port public-port call failed"

    local calls
    calls=$(wc -l < "$calls_file" | tr -d ' ')
    [[ "$calls" -eq 3 ]] || fail "expected port-public cache to be scoped by codespace and port, got $calls calls"
    pass "port visibility cache is scoped by codespace and port"
}

test_run_gh_sanitizes_invalid_timeout_env() {
    (
        reset_runtime_paths
        eval "$ORIGINAL_RUN_GH"
        unset GH_TOKEN GITHUB_TOKEN
        G2RAY_GH_TIMEOUT_SEC="bad"
        local timeout_file="$TMP_ROOT/gh-timeout.txt"
        cat > "$TMP_ROOT/bin/gh" <<'SH'
#!/usr/bin/env bash
exit 0
SH
        chmod +x "$TMP_ROOT/bin/gh"
        cat > "$TMP_ROOT/bin/timeout" <<SH
#!/usr/bin/env bash
printf '%s\\n' "\${1:-}" > "$timeout_file"
shift
"\$@"
SH
        chmod +x "$TMP_ROOT/bin/timeout"

        run_gh codespace list >/dev/null || fail "run_gh failed with invalid timeout env"
        [[ "$(cat "$timeout_file" 2>/dev/null || true)" == "10" ]] \
            || fail "run_gh did not sanitize invalid timeout env"
    )
    pass "run_gh sanitizes invalid timeout environment values"
}

test_lifecycle_port_publish_forces_visibility_cache() {
    (
        reset_runtime_paths
        local calls_file="$TMP_ROOT/forced-port-publish.txt"
        : > "$calls_file"
        ensure_codespace_port_public() {
            printf 'main:%s\n' "${1:-}" >> "$calls_file"
            return 0
        }
        ensure_optional_ws_port_public() {
            printf 'ws:%s\n' "${1:-}" >> "$calls_file"
            return 0
        }

        force_public_runtime_ports "behavior_test" >/dev/null \
            || fail "lifecycle forced public-port publish returned failure"
        grep -Fxq 'main:force' "$calls_file" \
            || fail "main XHTTP port was not force-published across lifecycle boundary"
        grep -Fxq 'ws:force' "$calls_file" \
            || fail "optional WS port helper was not force-called across lifecycle boundary"
    )
    pass "lifecycle port publish bypasses the visibility cache"
}

test_startup_port_publish_retries_until_forwarding_is_registered() {
    (
        reset_runtime_paths
        PORT_PUBLIC_STARTUP_ATTEMPTS=4
        PORT_PUBLIC_STARTUP_RETRY_SEC=0
        local publish_attempts=0
        force_public_runtime_ports() {
            publish_attempts=$((publish_attempts + 1))
            (( publish_attempts >= 3 ))
        }

        publish_runtime_ports_with_retry "behavior_test" >/dev/null \
            || fail "startup port publish did not recover after a transient registration delay"
        [[ "$publish_attempts" -eq 3 ]] \
            || fail "startup port publish used $publish_attempts attempts instead of stopping after success"
        grep -Fq 'port_public startup_retry reason=behavior_test attempt=1 max=4' "$LOG_FILE" \
            || fail "startup port publish retry was not observable"
        grep -Fq 'port_public startup_ready reason=behavior_test attempt=3' "$LOG_FILE" \
            || fail "startup port publish success was not observable"
    )
    pass "startup port publish retries transient forwarding-registration delays"
}

test_background_start_reports_lock_failure_without_live_supervisor() {
    (
        reset_runtime_paths
        acquire_bg_tasks_lock() { return 1; }
        bg_tasks_running() { return 1; }
        background_supervisor_heartbeat_running() { return 1; }
        legacy_bg_tasks_running() { return 1; }

        if start_background_tasks; then
            fail "background startup reported success even though lock was busy and no supervisor was live"
        fi
    )
    pass "background startup reports lock-busy failure when no live supervisor is confirmed"
}

test_background_start_cleans_up_when_pid_state_cannot_be_committed() {
    (
        reset_runtime_paths
        local fixture="$TMP_ROOT/background-supervisor-fixture.sh" rc=0 bg_pid=""
        printf '#!/usr/bin/env bash\nexec sleep 30\n' > "$fixture"
        SCRIPT_PATH="$fixture"
        _atomic_write() {
            local file="$1" content="$2"
            [[ "$file" != "$BG_TASKS_PID" ]] || return 1
            printf '%s\n' "$content" > "$file"
        }

        set +e
        start_background_tasks >/dev/null 2>&1
        rc=$?
        set -e
        bg_pid=$(cat "$BG_TASKS_PID" 2>/dev/null || true)
        if [[ "$bg_pid" =~ ^[0-9]+$ ]] && kill -0 "$bg_pid" 2>/dev/null; then
            kill "$bg_pid" 2>/dev/null || true
            wait "$bg_pid" 2>/dev/null || true
        fi

        [[ "$rc" -ne 0 ]] || fail "background startup reported success after its PID state write failed"
        [[ ! -e "$BG_TASKS_PID" && ! -e "$BG_TASKS_TOKEN_FILE" && ! -e "$BG_TASKS_VERSION_FILE" ]] \
            || fail "background startup left partial ownership state after its PID write failed"
        [[ ! -d "$BG_TASKS_LOCK_DIR" ]] \
            || fail "background startup left its startup lock held after state commit failure"
    )
    pass "background startup cleans up after ownership state commit failure"
}

test_background_heartbeat_reports_atomic_write_failure() {
    (
        reset_runtime_paths
        _atomic_write() { return 1; }
        if write_background_supervisor_heartbeat; then
            fail "background heartbeat masked its state write failure"
        fi
    )
    pass "background heartbeat reports atomic state write failures"
}

test_background_restart_cannot_revive_a_stopped_generation() {
    (
        reset_runtime_paths
        start_background_tasks "obsolete-token" || fail "superseded restart did not exit cleanly"
        [[ ! -e "$BG_TASKS_PID" && ! -e "$BG_TASKS_TOKEN_FILE" && ! -e "$BG_WATCHDOG_PID_FILE" ]] \
            || fail "queued watchdog restart recreated explicitly stopped state"
        [[ ! -d "$BG_TASKS_LOCK_DIR" ]] || fail "superseded restart retained its lock"
    )
    pass "queued watchdog restart cannot revive an explicitly stopped generation"
}

test_background_watchdog_recovers_dead_owned_supervisors() {
    (
        reset_runtime_paths
        local fixture="$TMP_ROOT/watchdog-fixture.sh" first_bg first_watchdog token p foreign_pid="" i
        local -a starters=()
        export G2RAY_TEST_SUPERVISOR_SOURCE="$SCRIPT"
        export G2RAY_DATA_DIR="$DATA_DIR" G2RAY_LOG_DIR="$LOG_DIR"
        cat > "$fixture" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
export G2RAY_SOURCE_ONLY=1
source "$G2RAY_TEST_SUPERVISOR_SOURCE"
SCRIPT_PATH="${BASH_SOURCE[0]}"
sleep() {
    if [[ "${1:-}" == 30 ]]; then command sleep 0.1; else command sleep "$@"; fi
}
_background_tasks() {
    write_background_supervisor_heartbeat
    while background_supervisor_token_current; do command sleep 0.1; done
}
case "${1:-}" in
    --background-supervisor) _background_tasks ;;
    --background-watchdog) _background_watchdog ;;
    *) exit 2 ;;
esac
SH
        SCRIPT_PATH="$fixture"
        trap 'stop_background_tasks >/dev/null 2>&1 || true; if [[ -n "$foreign_pid" ]]; then kill "$foreign_pid" 2>/dev/null || true; fi' EXIT
        start_background_tasks || fail "fixture supervisor/watchdog failed to start"
        first_bg=$(cat "$BG_TASKS_PID")
        first_watchdog=$(cat "$BG_WATCHDOG_PID_FILE")
        token=$(cat "$BG_TASKS_TOKEN_FILE")
        for ((i=0; i<100; i++)); do
            bg_tasks_running "$first_bg" && background_watchdog_running && break
            sleep 0.05
        done
        bg_tasks_running "$first_bg" && background_watchdog_running || fail "fixture ownership was not verified"
        start_background_tasks || fail "idempotent supervisor startup failed"
        for ((i=0; i<4; i++)); do
            (start_background_tasks) &
            starters+=("$!")
        done
        for p in "${starters[@]}"; do
            wait "$p" || fail "concurrent supervisor startup failed"
        done
        [[ "$(cat "$BG_TASKS_PID")" == "$first_bg" && "$(cat "$BG_WATCHDOG_PID_FILE")" == "$first_watchdog" ]] \
            || fail "idempotent startup replaced a healthy process"
        _atomic_write "$BG_TASKS_HEARTBEAT_FILE" "$first_bg $token 1"
        sleep 0.4
        [[ "$(cat "$BG_TASKS_PID")" == "$first_bg" ]] \
            || fail "old heartbeat caused the watchdog to replace a live owned supervisor"
        kill -KILL "$first_watchdog"
        start_background_tasks || fail "healthy supervisor could not regain its missing watchdog"
        [[ "$(cat "$BG_TASKS_PID")" == "$first_bg" ]] \
            || fail "rearming the watchdog replaced a healthy supervisor"
        background_watchdog_running || fail "replacement watchdog ownership was not confirmed"

        kill -KILL "$first_bg"
        for ((i=0; i<200; i++)); do
            p=$(cat "$BG_TASKS_PID" 2>/dev/null || true)
            if [[ "$p" != "$first_bg" ]] && bg_tasks_running "$p" && background_watchdog_running; then break; fi
            sleep 0.05
        done
        [[ "$p" != "$first_bg" ]] && bg_tasks_running "$p" && background_watchdog_running \
            || fail "watchdog did not restore the supervisor after unexpected death"
        [[ "$(cat "$BG_TASKS_TOKEN_FILE")" != "$token" ]] || fail "replacement reused the dead generation token"
        grep -Fq 'action=restart' "$LOG_FILE" || fail "watchdog restart was not observable"

        command sleep 30 &
        foreign_pid=$!
        _atomic_write "$BG_TASKS_PID" "$foreign_pid"
        _atomic_write "$BG_TASKS_HEARTBEAT_FILE" "$foreign_pid $(cat "$BG_TASKS_TOKEN_FILE") $(date +%s)"
        for ((i=0; i<200; i++)); do
            p=$(cat "$BG_TASKS_PID" 2>/dev/null || true)
            if [[ "$p" != "$foreign_pid" ]] && bg_tasks_running "$p" && background_watchdog_running; then break; fi
            sleep 0.05
        done
        [[ "$p" != "$foreign_pid" ]] && bg_tasks_running "$p" && background_watchdog_running \
            || fail "watchdog did not replace an unowned stale PID record"
        kill -0 "$foreign_pid" || fail "watchdog signalled a foreign process"
        token=$(cat "$BG_TASKS_TOKEN_FILE")
        stop_background_tasks || fail "explicit supervisor shutdown failed"
        start_background_tasks "$token" || fail "obsolete queued restart failed to exit cleanly"
        sleep 0.4
        [[ ! -e "$BG_TASKS_PID" && ! -e "$BG_TASKS_TOKEN_FILE" && ! -e "$BG_WATCHDOG_PID_FILE" ]] \
            || fail "watchdog resurrected an explicitly stopped supervisor"
    )
    pass "watchdog deduplicates healthy starts, restores dead supervisors, and respects ownership and shutdown"
}

test_background_watchdog_reports_pid_commit_failure() {
    (
        reset_runtime_paths
        local original_atomic rc=0
        original_atomic=$(declare -f _atomic_write)
        printf 'watchdog-test-token\n' > "$BG_TASKS_TOKEN_FILE"
        SCRIPT_PATH="$TMP_ROOT/watchdog-failed-state-fixture.sh"
        printf '#!/usr/bin/env bash\nexec sleep 30\n' > "$SCRIPT_PATH"
        _atomic_write() { return 1; }
        start_background_watchdog >/dev/null 2>&1 || rc=$?
        eval "$original_atomic"
        [[ "$rc" -ne 0 && ! -e "$BG_WATCHDOG_PID_FILE" ]] \
            || fail "watchdog startup masked its PID commit failure"
    )
    pass "watchdog startup reports state-commit failure"
}

test_background_stop_reports_failed_state_cleanup() {
    (
        reset_runtime_paths
        printf 'stop-test-token\n' > "$BG_TASKS_TOKEN_FILE"
        rm() {
            [[ "$*" != *"$BG_TASKS_TOKEN_FILE"* ]] || return 1
            command rm "$@"
        }
        if stop_background_tasks; then
            fail "background shutdown reported success while its intent token could not be removed"
        fi
        [[ -f "$BG_TASKS_TOKEN_FILE" && ! -d "$BG_TASKS_LOCK_DIR" ]] \
            || fail "failed shutdown did not preserve state and release its startup lock"
    )
    pass "background shutdown reports failed intent-state cleanup without retaining its lock"
}

test_background_heartbeat_cannot_override_visible_process_ownership() {
    (
        reset_runtime_paths
        local p
        command sleep 30 &
        p=$!
        trap 'kill "$p" 2>/dev/null || true; wait "$p" 2>/dev/null || true' EXIT
        printf 'reused-pid-token\n' > "$BG_TASKS_TOKEN_FILE"
        printf '%s\n' "$p" > "$BG_TASKS_PID"
        printf '%s reused-pid-token %s\n' "$p" "$(date +%s)" > "$BG_TASKS_HEARTBEAT_FILE"
        [[ -r "/proc/$p/environ" ]] || fail "fixture process environment is not readable"
        if background_supervisor_heartbeat_running "$p"; then
            fail "a fresh persisted heartbeat overruled the foreign process's actual ownership"
        fi
        stop_background_tasks || fail "stale supervisor state could not be cleared"
        kill -0 "$p" || fail "background shutdown signalled a recycled foreign PID"
    )
    pass "fresh heartbeat cannot validate or signal a visibly foreign recycled PID"
}

test_stale_temp_sweep_removes_only_old_owned_artifacts() {
    reset_runtime_paths
    local old_probe="$DATA_DIR/route_probe.ABC123"
    local fresh_probe="$DATA_DIR/route_probe.DEF456"
    local old_dns="$DATA_DIR/dns-resolve.ABC123"
    local unrelated="$DATA_DIR/keep.txt"
    printf 'old\n' > "$old_probe"
    printf 'fresh\n' > "$fresh_probe"
    printf 'keep\n' > "$unrelated"
    mkdir -p "$old_dns"
    printf 'old dns\n' > "$old_dns/result"
    touch -d '20 minutes ago' "$old_probe" "$old_dns" 2>/dev/null \
        || fail "test environment cannot age temp artifacts"

    sweep_stale_temp_files
    [[ ! -e "$old_probe" ]] || fail "old route probe temp file was not swept"
    [[ ! -e "$old_dns" ]] || fail "old DNS temp directory was not swept"
    [[ -e "$fresh_probe" ]] || fail "fresh route probe temp file was swept"
    [[ -e "$unrelated" ]] || fail "unrelated file was swept"
    pass "stale temp sweep removes only old owned artifacts"
}

test_logs_reset_when_script_code_changes() {
    reset_runtime_paths
    printf 'old app log\n' > "$LOG_FILE"
    printf 'old app rotated\n' > "$LOG_FILE.1"
    printf '{"old":true}\n' > "$STRUCTURED_LOG_FILE"
    printf '{"old":true}\n' > "$STRUCTURED_LOG_FILE.1"
    printf 'old diagnostics\n' > "$DIAGNOSTIC_LOG_FILE"
    printf 'old diagnostics rotated\n' > "$DIAGNOSTIC_LOG_FILE.1"
    printf 'old xray runtime\n' > "$LOG_DIR/xray.log"
    printf 'old xray runtime rotated\n' > "$LOG_DIR/xray.log.1"
    printf 'old xray error\n' > "$LOG_DIR/xray-error.log"
    printf 'old config test\n' > "$LOG_DIR/xray-configtest.ABC123.log"
    printf 'old-code-version\n' > "$LOG_CODE_VERSION_FILE"

    reset_logs_on_code_change

    grep -Fq 'logs reset_for_code_change' "$LOG_FILE" \
        || fail "log reset did not leave a fresh code-change marker"
    if grep -Fq 'old app log' "$LOG_FILE" \
        || [[ -e "$LOG_FILE.1" ]] \
        || [[ -e "$STRUCTURED_LOG_FILE.1" ]] \
        || [[ -e "$DIAGNOSTIC_LOG_FILE.1" ]] \
        || [[ -e "$LOG_DIR/xray.log.1" ]] \
        || [[ -e "$LOG_DIR/xray-configtest.ABC123.log" ]]; then
        fail "old logs or rotated/config-test logs were not cleared after code change"
    fi
    [[ -s "$LOG_CODE_VERSION_FILE" ]] || fail "log code-version marker was not updated"
    pass "logs reset when script code changes"
}

test_cached_route_order_uses_reliability_then_average_latency() {
    reset_runtime_paths
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.1	200	10	true
2026-05-30T00:00:00Z	20.0.0.2	200	80	true
2026-05-30T00:00:00Z	20.0.0.3	404	10	false
EOF
    cat > "$ROUTE_STATS_FILE" <<'EOF'
20.0.0.1	5	1	4	10	10	10	10	200	true	2026-05-30T00:00:00Z
20.0.0.2	5	5	0	80	70	90	80	200	true	2026-05-30T00:00:00Z
EOF
    cat > "$LAST_GOOD_ROUTE_FILE" <<'EOF'
ip=20.0.0.1
http_status=200
latency_ms=10
source=test
checked_at=2026-05-30T00:00:00Z
EOF
    mapfile -t routes < <(cached_usable_fallback_ips)
    [[ "${routes[0]:-}" == "20.0.0.2" ]] || fail "reliable route was not preferred over a flaky fast last probe"
    [[ "${routes[1]:-}" == "20.0.0.1" ]] || fail "flaky route was not kept after reliable route"
    pass "cached route health orders exports by reliability and average latency"
}

test_cached_route_order_uses_recent_weighted_score() {
    reset_runtime_paths
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.1	200	120	true	dns	ready
2026-05-30T00:00:00Z	20.0.0.2	200	260	true	dns	ready
EOF
    cat > "$ROUTE_STATS_FILE" <<'EOF'
20.0.0.1	10	10	0	100	80	900	120	200	true	2026-05-30T00:00:00Z	900	0	ready
20.0.0.2	10	10	0	250	220	300	260	200	true	2026-05-30T00:00:00Z	250	0	ready
EOF
    mapfile -t routes < <(cached_usable_fallback_ips)
    [[ "${routes[0]:-}" == "20.0.0.2" ]] || fail "recent weighted route score did not outrank stale cumulative average"
    [[ "${routes[1]:-}" == "20.0.0.1" ]] || fail "route with stale cumulative average was not kept second"
    pass "cached route health orders exports by recent weighted score"
}

test_cached_route_order_does_not_overweight_tiny_reliability_delta() {
    reset_runtime_paths
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-06-17T03:19:17Z	20.207.70.99	200	2575	true	cache	ready
2026-06-17T03:19:17Z	20.85.77.48	200	248	true	cache	ready
EOF
    cat > "$ROUTE_STATS_FILE" <<'EOF'
20.207.70.99	302	299	3	2669	1000	3000	2575	200	true	2026-06-17T03:19:17Z	2289	0	ready
20.85.77.48	533	525	8	160	50	700	248	200	true	2026-06-17T03:19:17Z	257	0	ready
EOF
    mapfile -t routes < <(cached_usable_fallback_ips)
    [[ "${routes[0]:-}" == "20.85.77.48" ]] \
        || fail "tiny reliability delta let a multi-second route outrank a much faster route: ${routes[*]:-none}"
    [[ "${routes[1]:-}" == "20.207.70.99" ]] \
        || fail "slower but usable route was not kept after the faster route"
    pass "cached route health does not overweight tiny reliability deltas"
}

test_last_good_route_decays_before_breaking_ties() {
    reset_runtime_paths
    LAST_GOOD_ROUTE_MAX_AGE_SEC=60
    local now
    now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.1	200	50	true	dns	ready
2026-05-30T00:00:00Z	20.0.0.2	200	50	true	dns	ready
EOF
    cat > "$LAST_GOOD_ROUTE_FILE" <<EOF
ip=20.0.0.2
http_status=200
latency_ms=50
source=test
checked_at=$now
EOF
    local routes
    mapfile -t routes < <(cached_usable_fallback_ips)
    [[ "${routes[0]:-}" == "20.0.0.2" ]] || fail "fresh last-good route did not break an equal route tie"

    sed -i 's/^checked_at=.*/checked_at=2000-01-01T00:00:00Z/' "$LAST_GOOD_ROUTE_FILE"
    mapfile -t routes < <(cached_usable_fallback_ips)
    [[ "${routes[0]:-}" == "20.0.0.1" ]] || fail "stale last-good route still influenced equal route ordering"
    pass "last-good route preference decays before breaking route ties"
}

test_route_candidate_stats_track_average_and_success_rate() {
    reset_runtime_paths
    update_route_candidate_stats 20.0.0.1 200 100
    update_route_candidate_stats 20.0.0.1 404 900
    update_route_candidate_stats 20.0.0.1 200 300

    local row
    row=$(awk -F '\t' '$1 == "20.0.0.1" {print $0}' "$ROUTE_STATS_FILE")
    [[ "$row" == $'20.0.0.1\t3\t2\t1\t200\t100\t300\t300\t200\ttrue\t'* ]] \
        || fail "route stats did not track samples/success/failure/average/min/max/last: $row"
    printf '2026-05-30T00:00:00Z\t20.0.0.1\t200\t300\ttrue\n' > "$ROUTE_HEALTH_FILE"
    route_candidate_health_summary | grep -Fq 'avg=200ms success=2/3' \
        || fail "route candidate summary does not show average latency and success ratio"
    route_summary=$(route_candidate_health_summary)
    grep -Fq 'Cache status :' <<< "$route_summary" \
        || fail "route candidate summary does not show cache freshness"
    grep -Fq 'Samples      :' <<< "$route_summary" \
        || fail "route candidate summary does not show sample confidence"
    grep -Fq 'confidence=provisional' <<< "$route_summary" \
        || fail "low-sample route was not marked provisional"
    pass "route candidate stats track rolling average and reliability"
}

test_route_candidate_stats_reset_expired_target_history() {
    reset_runtime_paths
    ROUTE_STATS_MAX_AGE_SEC=60
    cat > "$ROUTE_STATS_FILE" <<'EOF'
20.0.0.1	500	490	10	2500	100	9000	8000	404	false	2000-01-01T00:00:00Z	7000	9	route_settling_404
EOF

    update_route_candidate_stats 20.0.0.1 200 120 cache ready

    local row
    row=$(awk -F '\t' '$1 == "20.0.0.1" {print $0}' "$ROUTE_STATS_FILE")
    [[ "$row" == $'20.0.0.1\t1\t1\t0\t120\t120\t120\t120\t200\ttrue\t'*$'\t120\t0\tready\t0' ]] \
        || fail "expired target history was not reset before recording a fresh route sample: $row"
    pass "route candidate stats reset expired target history"
}

test_route_candidate_stats_prune_expired_non_target_rows() {
    reset_runtime_paths
    ROUTE_STATS_MAX_AGE_SEC=60
    local fresh
    fresh=$(printf '20.0.0.3\t5\t5\t0\t100\t100\t100\t100\t200\ttrue\t%s' "$(date -u +%Y-%m-%dT%H:%M:%SZ)")
    printf '20.0.0.2\t5\t5\t0\t100\t100\t100\t100\t200\ttrue\t2000-01-01T00:00:00Z\n%s\n' "$fresh" > "$ROUTE_STATS_FILE"
    update_route_candidate_stats 20.0.0.1 200 120 cache ready
    if grep -Fq '20.0.0.2' "$ROUTE_STATS_FILE"; then fail 'expired unrelated stats were retained by the platform AWK'; fi
    grep -Fxq "$fresh" "$ROUTE_STATS_FILE" || fail 'fresh unrelated stats were pruned or rewritten'
    pass 'route history pruning works with the platform AWK and preserves fresh unrelated rows'
}

test_xray_stats_use_inbound_counters_only() {
    reset_runtime_paths
    local fake_xray="$TMP_ROOT/fake-xray-stats"
    cat > "$fake_xray" <<'SH'
#!/usr/bin/env bash
cat <<'EOF'
stat: <
  name: "inbound>>>vless-in>>>traffic>>>downlink"
  value: 1000
>
stat: <
  name: "inbound>>>vless-ws>>>traffic>>>downlink"
  value: 500
>
stat: <
  name: "user>>>user@G2rayXCodeLeafy>>>traffic>>>downlink"
  value: 1500
>
stat: <
  name: "inbound>>>vless-in>>>traffic>>>uplink"
  value: 200
>
stat: <
  name: "inbound>>>vless-ws>>>traffic>>>uplink"
  value: 100
>
stat: <
  name: "user>>>user@G2rayXCodeLeafy>>>traffic>>>uplink"
  value: 300
>
EOF
SH
    chmod +x "$fake_xray"
    XRAY_BIN="$fake_xray"
    xray_running() { return 0; }
    sudo() { "$@"; }

    save_xray_stats || fail "save_xray_stats failed with mocked stats"
    local saved_down saved_up usage
    saved_down=$(grep -oE '"down":[0-9]+' "$SAVED_BYTES_FILE" | grep -oE '[0-9]+' | head -1)
    saved_up=$(grep -oE '"up":[0-9]+' "$SAVED_BYTES_FILE" | grep -oE '[0-9]+' | head -1)
    [[ "$saved_down" == "1500" && "$saved_up" == "300" ]] \
        || fail "stats should count inbound counters once, got down=$saved_down up=$saved_up"

    usage=$(get_data_usage)
    [[ "$usage" == "1500 300" ]] || fail "data usage should count inbound counters once, got: $usage"
    pass "traffic accounting uses inbound counters without user double-counting"
}

test_save_xray_stats_marks_active_traffic_when_counters_increase() {
    reset_runtime_paths
    xray_running() { return 0; }
    sudo() {
        cat <<'JSON'
{
  "stat": [
    {
      "name": "inbound>>>vless-in>>>traffic>>>downlink",
      "value": 1200
    },
    {
      "name": "inbound>>>vless-in>>>traffic>>>uplink",
      "value": 300
    }
  ]
}
JSON
    }

    save_xray_stats >/dev/null || fail "save_xray_stats failed"
    [[ -s "$ACTIVE_TRAFFIC_STAMP_FILE" ]] || fail "active traffic stamp was not written"
    active_tunnel_recent || fail "active_tunnel_recent did not recognize fresh traffic"
    unset -f sudo xray_running
    eval "$ORIGINAL_XRAY_RUNNING"
    pass "active traffic is marked when Xray counters increase"
}

test_atomic_write_handles_mktemp_failure() {
    reset_runtime_paths
    local missing_parent="$DATA_DIR/missing-parent/value.txt"
    if _atomic_write "$missing_parent" "content" 2>/dev/null; then
        fail "_atomic_write reported success when mktemp could not create a temporary file"
    fi
    [[ ! -e "$missing_parent" ]] || fail "_atomic_write created target despite temporary-file failure"
    pass "atomic write handles temporary-file creation failure"
}

test_route_health_records_source_reason_and_recent_average() {
    reset_runtime_paths
    record_route_candidate_health 20.0.0.1 200 100 dns ready
    record_route_candidate_health 20.0.0.1 0 900 dns timeout
    record_route_candidate_health 20.0.0.1 200 300 dns ready

    local health_row stats_row
    health_row=$(tail -n 1 "$ROUTE_HEALTH_FILE")
    [[ "$health_row" == *$'\t20.0.0.1\t200\t300\ttrue\tdns\tready' ]] \
        || fail "route health did not record source and reason: $health_row"
    stats_row=$(awk -F '\t' '$1 == "20.0.0.1" {print $0}' "$ROUTE_STATS_FILE")
    awk -F '\t' '$1 == "20.0.0.1" && $12 ~ /^[0-9]+$/ && $13 ~ /^[0-9]+$/ && $14 == "ready" { found = 1 } END { exit !found }' "$ROUTE_STATS_FILE" \
        || fail "route stats did not persist ewma/recent failures/reason metadata: $stats_row"
    pass "route health records source, failure reason, and recent average metadata"
}

test_route_failure_reason_classifier() {
    reset_runtime_paths
    [[ "$(route_failure_reason_for_status 200 "")" == "ready" ]] || fail "HTTP 200 should classify ready"
    [[ "$(route_failure_reason_for_status 400 "")" == "unexpected_http_status" ]] || fail "HTTP 400 should classify as an XHTTP error"
    [[ "$(route_failure_reason_for_status 404 "")" == "route_settling_404" ]] || fail "HTTP 404 should classify route settling"
    [[ "$(route_failure_reason_for_status 0 "operation timed out")" == "timeout_or_unreachable" ]] || fail "HTTP 0 timeout should classify unreachable"
    [[ "$(route_failure_reason_for_status 503 "")" == "edge_or_origin_error" ]] || fail "HTTP 503 should classify edge/origin error"
    pass "route failure reason classifier is actionable"
}

test_edge_origin_errors_enter_candidate_cooldown() {
    reset_runtime_paths
    ROUTE_FAILURE_COOLDOWN_SEC=180

    record_route_candidate_health 20.0.0.9 503 77 dns edge_or_origin_error
    awk -F '\t' '$2 == "20.0.0.9" && $3 == "edge_or_origin_error" { found = 1 } END { exit !found }' "$ROUTE_COOLDOWN_FILE" \
        || fail "edge/origin errors were not temporarily cooled down"
    pass "edge/origin route errors enter candidate cooldown"
}

test_xhttp_external_probe_uses_strict_tls_by_default() {
    reset_runtime_paths
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT=443
    write_test_vless_config
    local args_file="$TMP_ROOT/curl-strict-tls-args.txt"
    curl() {
        printf '%s\n' "$*" > "$args_file"
        printf '200 0.010\n'
    }

    xhttp_probe_metrics external 20.0.0.9 >/dev/null
    unset -f curl
    ! grep -Eq '(^| )-k($| )|--insecure' "$args_file" \
        || fail "external XHTTP route probe disabled TLS verification even though exported configs are strict"
    grep -Fq -- '--resolve behavior-space-443.app.github.dev:443:20.0.0.9' "$args_file" \
        || fail "external XHTTP route probe did not preserve SNI/Host routing with --resolve"
    pass "external XHTTP route probes keep TLS verification enabled"
}

test_xhttp_probe_metrics_reports_curl_failure_reason() {
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT=443
    curl() { return 28; }

    local code ms reason
    read -r code ms reason < <(xhttp_probe_metrics external 20.0.0.1)
    unset -f curl
    [[ "$code" == "0" ]] || fail "curl timeout probe did not report HTTP 0: $code"
    [[ "$reason" == "timeout_or_unreachable" ]] || fail "curl timeout probe did not report actionable reason: $reason"
    pass "xhttp probe metrics reports curl failure reason"
}

test_xhttp_options_requires_handler_success() {
    xhttp_status_usable 200 || fail "HTTP 200 from the XHTTP OPTIONS handler was not accepted"
    if xhttp_status_usable 400; then
        fail "HTTP 400 was accepted even though the pinned Xray OPTIONS handler returns 200 on success"
    fi
    if xhttp_status_usable 404; then
        fail "HTTP 404 was accepted as a usable XHTTP route"
    fi
    pass "XHTTP readiness requires the pinned handler's HTTP 200 success response"
}

test_legacy_http_400_cache_rows_are_never_exported() {
    reset_runtime_paths
    printf '2026-08-28T00:00:00Z\t20.0.0.1\t400\t20\ttrue\tlegacy\tready\n' > "$ROUTE_HEALTH_FILE"

    if route_health_cache_fresh; then
        fail "legacy HTTP 400 cache row kept the route cache fresh"
    fi
    if route_health_cache_exportable; then
        fail "legacy HTTP 400 cache row remained exportable"
    fi
    [[ -z "$(cached_route_candidate_ips)" ]] \
        || fail "legacy HTTP 400 cache row remained a discovery candidate"
    [[ -z "$(cached_usable_fallback_ips)" ]] \
        || fail "legacy HTTP 400 cache row was exported as a fallback"

    printf '2026-08-28T00:00:01Z\t20.0.0.2\t200\t30\ttrue\tprobe\tready\n' >> "$ROUTE_HEALTH_FILE"
    [[ "$(cached_route_candidate_ips)" == "20.0.0.2" ]] \
        || fail "strict cache migration did not preserve the HTTP 200 route"
    [[ "$(cached_usable_fallback_ips)" == "20.0.0.2" ]] \
        || fail "strict cache migration did not export only the HTTP 200 route"
    pass "legacy HTTP 400 route-cache rows cannot survive strict readiness migration"
}

test_future_state_timestamps_are_treated_as_stale() {
    reset_runtime_paths
    local stamp="$DATA_DIR/future.stamp" file="$DATA_DIR/future.file" stamp_age file_age
    printf '200\n' > "$stamp"
    : > "$file"
    date() { printf '100\n'; }
    stat() { printf '200\n'; }

    stamp_age=$(stamp_age_sec "$stamp")
    file_age=$(file_age_sec "$file")
    unset -f date stat

    [[ "$stamp_age" == "999999999" ]] \
        || fail "future epoch stamp was treated as fresh: age=$stamp_age"
    [[ "$file_age" == "999999999" ]] \
        || fail "future file mtime was treated as fresh: age=$file_age"
    pass "future clock state cannot suppress visibility, cache, or recovery work"
}

test_future_deadlines_do_not_suppress_recovery() {
    reset_runtime_paths
    printf '200\n' > "$EDGE_RECONNECT_STAMP_FILE"
    printf '1000\t20.0.0.1\ttimeout_or_unreachable\n' > "$ROUTE_COOLDOWN_FILE"
    printf 'last_attempt_epoch\t200\nconsecutive_failures\t1\nnext_allowed_epoch\t1000\n' > "$ROUTE_REFRESH_STATE_FILE"
    ROUTE_FAILURE_COOLDOWN_SEC=180
    ROUTE_REFRESH_BACKOFF_MAX_SEC=600
    SELF_HEAL_RECONNECT_COOLDOWN_SEC=300
    date() { printf '100\n'; }

    if edge_reconnect_cooldown_active; then
        fail "future reconnect timestamp suppressed self-heal"
    fi
    if route_candidate_cooldown_active 20.0.0.1; then
        fail "far-future route cooldown suppressed a candidate after a clock rollback"
    fi
    route_refresh_retry_allowed \
        || fail "far-future route backoff suppressed scanner recovery after a clock rollback"
    unset -f date
    pass "future deadlines cannot suppress reconnect, candidates, or route refresh"
}

test_cached_route_order_prefers_pinned_route_then_latency_without_stats() {
    reset_runtime_paths
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.1	200	500	true
2026-05-30T00:00:00Z	20.0.0.2	200	40	true
2026-05-30T00:00:00Z	20.0.0.3	200	60	true
EOF
    cat > "$LAST_GOOD_ROUTE_FILE" <<'EOF'
ip=20.0.0.2
http_status=200
latency_ms=40
source=test
checked_at=2026-05-30T00:00:00Z
EOF
    pin_route_candidate 20.0.0.1
    mapfile -t routes < <(cached_usable_fallback_ips)
    [[ "${routes[0]:-}" == "20.0.0.1" ]] || fail "pinned route was not preferred first"
    [[ "${routes[1]:-}" == "20.0.0.2" ]] || fail "unpinned routes were not ordered by latency when stats were absent"
    pass "cached route health orders exports by pinned route, then latency when stats are absent"
}

test_route_monitor_default_and_hard_cap_are_wide_but_bounded() {
    reset_runtime_paths
    ROUTE_MONITOR_MAX_CANDIDATES=""
    [[ "$(route_monitor_max_candidates)" == "40" ]] || fail "empty route monitor max did not default to 40"

    ROUTE_MONITOR_MAX_CANDIDATES=0
    [[ "$(route_monitor_max_candidates)" == "40" ]] || fail "zero route monitor max did not default to 40"

    ROUTE_MONITOR_MAX_CANDIDATES=12
    [[ "$(route_monitor_max_candidates)" == "12" ]] || fail "valid route monitor max was not honored"

    ROUTE_MONITOR_MAX_CANDIDATES=99
    [[ "$(route_monitor_max_candidates)" == "64" ]] || fail "route monitor max was not hard-capped at 64"

    pass "route monitor scans enough candidates for 30 exports while staying bounded"
}

test_blacklisted_route_is_excluded_from_cached_exports() {
    reset_runtime_paths
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.1	200	40	true
2026-05-30T00:00:00Z	20.0.0.2	200	50	true
EOF
    blacklist_route_candidate 20.0.0.1
    mapfile -t routes < <(cached_usable_fallback_ips)
    [[ "${routes[*]}" == "20.0.0.2" ]] || fail "blacklisted route was not excluded from cached exports"
    pass "blacklisted cached routes are excluded from exports"
}

test_manual_route_candidates_are_validated_and_resettable() {
    reset_runtime_paths
    add_manual_route_candidate 20.0.0.9 || fail "valid manual route was rejected"
    if add_manual_route_candidate 20.0.0.9; then
        fail "duplicate manual route was accepted as a new candidate"
    fi
    if add_manual_route_candidate "20.0.0.999"; then
        fail "invalid manual route was accepted"
    fi
    grep -Fxq "20.0.0.9" "$MANUAL_ROUTE_CANDIDATES_FILE" || fail "manual route was not persisted"
    pin_route_candidate 20.0.0.9
    blacklist_route_candidate 20.0.0.2
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.9	200	40	true
EOF
    cat > "$ROUTE_STATS_FILE" <<'EOF'
20.0.0.9	1	1	0	40	40	40	40	200	true	2026-05-30T00:00:00Z
EOF
    cat > "$LAST_GOOD_ROUTE_FILE" <<'EOF'
ip=20.0.0.9
http_status=200
latency_ms=40
source=test
checked_at=2026-05-30T00:00:00Z
EOF
    printf '1780000000\tbehavior.example\tdns_google\t20.0.0.9\n' > "$DNS_CANDIDATE_CACHE_FILE"
    reset_route_candidate_cache
    grep -Fxq "20.0.0.9" "$MANUAL_ROUTE_CANDIDATES_FILE" || fail "cache reset removed manual route preferences"
    [[ "$(cat "$PINNED_ROUTE_FILE" 2>/dev/null)" == "20.0.0.9" ]] || fail "cache reset removed pinned route preference"
    grep -Fxq "20.0.0.2" "$BLACKLISTED_ROUTE_CANDIDATES_FILE" || fail "cache reset removed blacklist preferences"
    [[ ! -e "$ROUTE_HEALTH_FILE" ]] || fail "route health cache was not reset"
    [[ ! -e "$ROUTE_STATS_FILE" ]] || fail "route stats cache was not reset"
    [[ ! -e "$LAST_GOOD_ROUTE_FILE" ]] || fail "last-good route cache was not reset"
    [[ ! -e "$ROUTE_COOLDOWN_FILE" ]] || fail "route cooldown cache was not reset"
    [[ ! -e "$DNS_CANDIDATE_CACHE_FILE" ]] || fail "DNS candidate cache was not reset"
    reset_route_candidate_state
    [[ ! -e "$MANUAL_ROUTE_CANDIDATES_FILE" ]] || fail "manual route file was not reset"
    [[ ! -e "$PINNED_ROUTE_FILE" ]] || fail "pinned route file was not reset"
    [[ ! -e "$BLACKLISTED_ROUTE_CANDIDATES_FILE" ]] || fail "blacklist route file was not reset"
    pass "manual route candidates are validated and route manager cache/state resets are safe"
}

test_route_preferences_clear_matching_cooldowns() {
    reset_runtime_paths
    local future
    future=$(( $(date +%s) + ROUTE_FAILURE_COOLDOWN_SEC - 1 ))

    printf '%s\t20.0.0.9\ttimeout_or_unreachable\n' "$future" > "$ROUTE_COOLDOWN_FILE"
    add_manual_route_candidate 20.0.0.9 || fail "manual route add failed"
    [[ ! -e "$ROUTE_COOLDOWN_FILE" ]] || fail "manual route add did not clear matching cooldown"

    printf '%s\t20.0.0.10\ttimeout_or_unreachable\n' "$future" > "$ROUTE_COOLDOWN_FILE"
    pin_route_candidate 20.0.0.10 || fail "pin route failed"
    [[ ! -e "$ROUTE_COOLDOWN_FILE" ]] || fail "pinning a route did not clear matching cooldown"

    printf '%s\t20.0.0.11\ttimeout_or_unreachable\n' "$future" > "$ROUTE_COOLDOWN_FILE"
    printf '20.0.0.11\n' > "$BLACKLISTED_ROUTE_CANDIDATES_FILE"
    unblacklist_route_candidate 20.0.0.11 || fail "unblacklist route failed"
    [[ ! -e "$ROUTE_COOLDOWN_FILE" ]] || fail "unblacklisting a route did not clear matching cooldown"
    pass "manual, pinned, and unblacklisted routes clear matching cooldowns"
}

test_route_health_refresh_preserves_cache_when_all_candidates_are_cooled_down() {
    reset_runtime_paths
    touch "$CONFIG_FILE"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    ROUTE_PROBE_CONCURRENCY=2
    local future original_resolver original_probe
    future=$(( $(date +%s) + ROUTE_FAILURE_COOLDOWN_SEC - 1 ))
    original_resolver="$(declare -f resolve_domain_ips_with_sources)"
    original_probe="$(declare -f xhttp_probe_metrics)"
    printf '2026-05-30T00:00:00Z\t20.0.0.1\t200\t50\ttrue\tdns\tready\n' > "$ROUTE_HEALTH_FILE"
    printf '%s\t20.0.0.2\ttimeout_or_unreachable\n%s\t20.0.0.3\ttimeout_or_unreachable\n' "$future" "$future" > "$ROUTE_COOLDOWN_FILE"
    resolve_domain_ips_with_sources() {
        printf 'dns\t20.0.0.2\n'
        printf 'dns\t20.0.0.3\n'
    }
    xhttp_probe_metrics() { fail "cooled-down route was probed unexpectedly"; }

    refresh_route_candidate_health --force || fail "route health refresh failed when all candidates were cooled down"
    eval "$original_resolver"
    eval "$original_probe"
    grep -Fq $'20.0.0.1\t200\t50\ttrue' "$ROUTE_HEALTH_FILE" \
        || fail "route health refresh replaced the last known-good cache when every candidate was skipped"
    grep -Fq 'skipped_all_candidates' "$LOG_FILE" || fail "skipped-all-candidates event was not logged"
    pass "route health refresh preserves previous cache when all candidates are cooled down"
}

test_route_health_refresh_preserves_cache_when_all_probes_are_unusable() {
    reset_runtime_paths
    write_test_vless_config
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    ROUTE_MONITOR_MAX_CANDIDATES=2
    ROUTE_PROBE_CONCURRENCY=2
    ROUTE_FAILURE_COOLDOWN_SEC=0
    local original_resolver original_probe
    original_resolver="$(declare -f resolve_domain_ips_with_sources)"
    original_probe="$(declare -f xhttp_probe_metrics)"
    printf '2026-05-30T00:00:00Z\t20.0.0.1\t200\t50\ttrue\tdns\tready\n' > "$ROUTE_HEALTH_FILE"
    resolve_domain_ips_with_sources() {
        printf 'dns\t20.0.0.2\n'
        printf 'dns\t20.0.0.3\n'
    }
    xhttp_probe_metrics() { printf '404 25 route_settling_404\n'; }

    refresh_route_candidate_health --force || fail "route health refresh failed during all-404 settling"
    eval "$original_resolver"
    eval "$original_probe"
    grep -Fq $'20.0.0.1\t200\t50\ttrue' "$ROUTE_HEALTH_FILE" \
        || fail "route health refresh replaced the last known-good cache when every probe was unusable"
    ! grep -Fq $'20.0.0.2\t404' "$ROUTE_HEALTH_FILE" \
        || fail "all-unusable refresh should not replace health cache with temporary 404 probes"
    pass "route health refresh preserves previous cache when every probe is temporarily unusable"
}

test_route_health_refresh_mixes_provider_candidates_before_stale_cache_cap() {
    reset_runtime_paths
    write_test_vless_config
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    ROUTE_MONITOR_MAX_CANDIDATES=3
    ROUTE_PROBE_CONCURRENCY=1
    local original_provider original_probe probed_file
    original_provider="$(declare -f resolve_dns_provider_ips_with_sources)"
    original_probe="$(declare -f xhttp_probe_metrics)"
    probed_file="$TMP_ROOT/probed-provider-mix.txt"
    printf '2026-05-30T00:00:00Z\t20.0.0.1\t200\t500\ttrue\tcache\tready\n' > "$ROUTE_HEALTH_FILE"
    printf '2026-05-30T00:00:00Z\t20.0.0.2\t200\t501\ttrue\tcache\tready\n' >> "$ROUTE_HEALTH_FILE"
    printf '2026-05-30T00:00:00Z\t20.0.0.3\t200\t502\ttrue\tcache\tready\n' >> "$ROUTE_HEALTH_FILE"
    resolve_dns_provider_ips_with_sources() {
        printf 'dns\t20.0.0.9\n'
    }
    xhttp_probe_metrics() {
        printf '%s\n' "$2" >> "$probed_file"
        printf '200 10 ready\n'
    }

    refresh_route_candidate_health --force || fail "route health refresh failed with provider/cache mix"
    eval "$original_provider"
    eval "$original_probe"
    grep -Fxq '20.0.0.9' "$probed_file" \
        || fail "fresh provider candidate was starved by stale cached routes under the probe cap"
    pass "route health refresh mixes provider candidates before stale cache can fill the cap"
}

test_route_health_refresh_does_not_let_unusable_cache_starve_builtins() {
    reset_runtime_paths
    write_test_vless_config
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    DEFAULT_FALLBACK_IPS="20.0.0.9"
    G2RAY_EXTRA_FALLBACK_IPS=""
    ROUTE_MONITOR_MAX_CANDIDATES=2
    ROUTE_PROBE_CONCURRENCY=1
    local original_provider original_probe probed_file
    original_provider="$(declare -f resolve_dns_provider_ips_with_sources)"
    original_probe="$(declare -f xhttp_probe_metrics)"
    probed_file="$TMP_ROOT/probed-unusable-cache.txt"
    printf '2026-05-30T00:00:00Z\t20.0.0.1\t404\t20\tfalse\tcache\troute_settling_404\n' > "$ROUTE_HEALTH_FILE"
    printf '2026-05-30T00:00:00Z\t20.0.0.2\t0\t5000\tfalse\tcache\ttimeout_or_unreachable\n' >> "$ROUTE_HEALTH_FILE"
    resolve_dns_provider_ips_with_sources() { return 0; }
    xhttp_probe_metrics() {
        printf '%s\n' "$2" >> "$probed_file"
        printf '200 10 ready\n'
    }

    refresh_route_candidate_health --force || fail "route health refresh failed with unusable cached rows"
    eval "$original_provider"
    eval "$original_probe"
    grep -Fxq '20.0.0.9' "$probed_file" \
        || fail "unusable cached route rows starved built-in fallback probing under the cap"
    pass "route health refresh does not let unusable cache starve built-in fallbacks"
}

test_route_preference_write_failures_return_failure() {
    (
        reset_runtime_paths
        write_unique_route_file() { return 1; }
        if add_manual_route_candidate 20.0.0.9; then
            fail "manual route add reported success after write failure"
        fi
        if blacklist_route_candidate 20.0.0.9; then
            fail "blacklist route reported success after write failure"
        fi
    )
    (
        reset_runtime_paths
        _atomic_write() { return 1; }
        if pin_route_candidate 20.0.0.9; then
            fail "pin route reported success after write failure"
        fi
    )
    pass "route preference write failures do not report success"
}

test_pinned_route_is_a_durable_candidate_source() {
    reset_runtime_paths
    DEFAULT_FALLBACK_IPS=""
    G2RAY_EXTRA_FALLBACK_IPS=""
    json_dns_ips() { return 0; }
    curl_remote_ip() { return 0; }
    pin_route_candidate 20.0.0.7 || fail "pin route failed"
    mapfile -t routes < <(resolve_domain_ips "")
    [[ "${routes[0]:-}" == "20.0.0.7" ]] || fail "pinned route was not included in resolver candidates"
    pass "pinned route stays in resolver candidates after cache refresh"
}

test_cached_route_health_is_a_durable_candidate_source() {
    reset_runtime_paths
    DEFAULT_FALLBACK_IPS=""
    G2RAY_EXTRA_FALLBACK_IPS=""
    json_dns_ips() { return 0; }
    curl_remote_ip() { return 0; }
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.8	200	70	true
EOF
    mapfile -t routes < <(resolve_domain_ips "")
    [[ "${routes[0]:-}" == "20.0.0.8" ]] || fail "cached route health was not reused as a resolver candidate"
    pass "cached route health keeps discovered candidates durable"
}

test_dns_candidate_cache_reuses_fresh_provider_results() {
    (
        reset_runtime_paths
        DEFAULT_FALLBACK_IPS=""
        G2RAY_EXTRA_FALLBACK_IPS=""
        DNS_CACHE_TTL_SEC=300
        dig() { return 1; }
        getent() { return 1; }
        curl_remote_ip() { return 0; }
        local calls_file="$TMP_ROOT/dns-json-calls.txt"
        : > "$calls_file"
        json_dns_ips() {
            printf 'call\n' >> "$calls_file"
            printf '20.0.0.9\n'
        }

        mapfile -t rows < <(resolve_domain_ips_with_sources "behavior.example")
        printf '%s\n' "${rows[@]}" | awk -F '\t' '$2 == "20.0.0.9" {found = 1} END {exit !found}' \
            || fail "resolver did not use provider result: ${rows[*]:-none}"
        [[ -s "$DNS_CANDIDATE_CACHE_FILE" ]] || fail "resolver did not write DNS candidate cache"

        json_dns_ips() { fail "fresh DNS candidate cache did not suppress provider lookup"; }
        mapfile -t rows < <(resolve_domain_ips_with_sources "behavior.example")
        printf '%s\n' "${rows[@]}" | awk -F '\t' '$2 == "20.0.0.9" {found = 1} END {exit !found}' \
            || fail "resolver did not reuse fresh DNS candidate cache: ${rows[*]:-none}"
    )
    pass "DNS candidate cache reuses fresh provider results"
}

test_wide_dns_discovery_queries_multiple_ecs_subnets() {
    (
        reset_runtime_paths
        DEFAULT_FALLBACK_IPS=""
        G2RAY_EXTRA_FALLBACK_IPS=""
        DNS_CACHE_TTL_SEC=0
        G2RAY_WIDE_DNS_DISCOVERY=1
        G2RAY_WIDE_DNS_ECS_SUBNETS="1.1.1.0/24 8.8.8.0/24 invalid-subnet 999.1.1.0/24"
        dig() { return 1; }
        getent() { return 1; }
        curl_remote_ip() { return 0; }
        local calls_file="$TMP_ROOT/wide-dns-json-calls.txt"
        : > "$calls_file"
        json_dns_ips() {
            printf '%s\n' "$1" >> "$calls_file"
            case "$1" in
                *'edns_client_subnet=1.1.1.0%2F24'*) printf '20.0.0.11\n' ;;
                *'edns_client_subnet=8.8.8.0%2F24'*) printf '20.0.0.12\n' ;;
                *'edns_client_subnet='*) printf '20.0.0.99\n' ;;
                *'dns.google/resolve'*) printf '20.0.0.9\n' ;;
                *'cloudflare-dns.com'*) printf '20.0.0.10\n' ;;
            esac
        }

        mapfile -t rows < <(resolve_domain_ips_with_sources "behavior.example")
        printf '%s\n' "${rows[@]}" | grep -Fxq $'dns_google_ecs_1_1_1_0_24\t20.0.0.11' \
            || fail "wide DNS discovery did not include 1.1.1.0/24 ECS candidate: ${rows[*]:-none}"
        printf '%s\n' "${rows[@]}" | grep -Fxq $'dns_google_ecs_8_8_8_0_24\t20.0.0.12' \
            || fail "wide DNS discovery did not include 8.8.8.0/24 ECS candidate: ${rows[*]:-none}"
        ! grep -Fq 'invalid-subnet' "$calls_file" \
            || fail "wide DNS discovery queried invalid ECS subnet text"
        ! grep -Fq '999.1.1.0' "$calls_file" \
            || fail "wide DNS discovery queried invalid ECS subnet octets"
    )
    pass "wide DNS discovery queries multiple valid ECS subnets"
}

test_batch_manual_route_import_extracts_valid_unique_ipv4s() {
    (
        reset_runtime_paths
        blacklist_route_candidate 20.0.0.8 || fail "blacklist setup failed"
        local summary
        summary="$(import_manual_route_candidates_from_text $'messy text 20.0.0.7 duplicate 20.0.0.7 invalid 999.1.1.1 blocked 20.0.0.8 next 20.0.0.9')"
        grep -Fxq '20.0.0.7' "$MANUAL_ROUTE_CANDIDATES_FILE" \
            || fail "batch import missed first valid IPv4"
        grep -Fxq '20.0.0.9' "$MANUAL_ROUTE_CANDIDATES_FILE" \
            || fail "batch import missed second valid IPv4"
        ! grep -Fxq '20.0.0.8' "$MANUAL_ROUTE_CANDIDATES_FILE" \
            || fail "batch import added a blacklisted route"
        [[ "$summary" == *"added=2"* ]] || fail "batch import summary did not report two additions: $summary"
        [[ "$summary" == *"skipped=1"* ]] || fail "batch import summary did not report skipped blacklisted route: $summary"
    )
    pass "batch manual route import extracts valid unique IPv4 candidates"
}

test_last_known_state_scans_full_current_log() {
    reset_runtime_paths
    for _i in $(seq 1 700); do
        printf '2026-05-30T00:00:00Z [INFO] filler event\n' >> "$LOG_FILE"
    done
    printf '2026-05-30T00:01:00Z [WARN] port_public failed port=443 detail=test\n' >> "$LOG_FILE"
    for _i in $(seq 1 700); do
        printf '2026-05-30T00:02:00Z [INFO] health engine=running listener=open\n' >> "$LOG_FILE"
    done
    local summary
    summary="$(last_known_state_summary)"
    grep -Fq 'port_public failed port=443 detail=test' <<< "$summary" \
        || fail "last known state did not scan beyond the recent log tail"
    pass "last known state scans the full current log"
}

test_usable_fallback_ips_uses_fresh_cache() {
    reset_runtime_paths
    ROUTE_HEALTH_TTL_SEC=300
    MAX_FALLBACK_LINKS=2
    G2RAY_EXPORT_REVALIDATE_TOP_CACHED=0
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.5	200	70	true
2026-05-30T00:00:00Z	20.0.0.6	200	80	true
EOF
    xhttp_probe_metrics() { fail "usable_fallback_ips should not live-probe while route health cache is fresh"; }
    resolve_domain_ips() { fail "usable_fallback_ips should not resolve DNS while route health cache is fresh"; }
    mapfile -t routes < <(usable_fallback_ips)
    [[ "${routes[*]}" == "20.0.0.5 20.0.0.6" ]] || fail "usable_fallback_ips did not return cached usable routes"
    pass "usable fallback exports use fresh cached route health"
}

test_usable_fallback_ips_does_not_live_probe_cached_routes() {
    reset_runtime_paths
    ROUTE_HEALTH_TTL_SEC=300
    MAX_FALLBACK_LINKS=2
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.5	200	70	true
2026-05-30T00:00:00Z	20.0.0.6	200	80	true
EOF
    resolve_domain_ips() { fail "exports must not synchronously resolve candidate routes"; }
    xhttp_probe_metrics() { fail "exports must not synchronously probe cached routes"; }

    mapfile -t routes < <(usable_fallback_ips)
    [[ "${routes[*]}" == "20.0.0.5 20.0.0.6" ]] \
        || fail "usable_fallback_ips did not reuse cached routes: ${routes[*]:-none}"
    pass "usable fallback exports do not synchronously re-probe cached routes"
}

test_usable_fallback_ips_does_not_fill_partial_cache_synchronously() {
    reset_runtime_paths
    ROUTE_HEALTH_TTL_SEC=300
    MAX_FALLBACK_LINKS=3
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.5	200	70	true
2026-05-30T00:00:00Z	20.0.0.6	200	80	true
2026-05-30T00:00:00Z	20.0.0.7	200	90	true
EOF
    resolve_domain_ips() { fail "exports must not synchronously discover extra routes"; }
    xhttp_probe_metrics() { fail "exports must not synchronously probe extra routes"; }

    mapfile -t routes < <(usable_fallback_ips)
    [[ "${routes[*]}" == "20.0.0.5 20.0.0.6 20.0.0.7" ]] \
        || fail "usable_fallback_ips did not return the full cached selection: ${routes[*]:-none}"
    pass "usable fallback exports do not synchronously fill partial cache"
}

test_usable_fallback_ips_fills_partial_fresh_cache() {
    reset_runtime_paths
    ROUTE_HEALTH_TTL_SEC=300
    MAX_FALLBACK_LINKS=4
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.5	200	70	true
2026-05-30T00:00:00Z	20.0.0.6	200	80	true
EOF
    resolve_domain_ips() { fail "exports must not synchronously fill routes"; }
    xhttp_probe_metrics() { fail "exports must not synchronously probe routes"; }

    mapfile -t routes < <(usable_fallback_ips)
    [[ "${routes[*]}" == "20.0.0.5 20.0.0.6" ]] \
        || fail "usable_fallback_ips filled partial cache synchronously: ${routes[*]:-none}"
    pass "usable fallback exports keep partial cache responsive"
}

test_usable_fallback_ips_keeps_cached_routes_during_route_settling() {
    reset_runtime_paths
    ROUTE_HEALTH_TTL_SEC=300
    MAX_FALLBACK_LINKS=3
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.5	200	70	true
2026-05-30T00:00:00Z	20.0.0.6	200	80	true
EOF
    resolve_domain_ips() { fail "exports must not run a live settling probe"; }
    xhttp_probe_metrics() { fail "exports must not run a live settling probe"; }

    mapfile -t routes < <(usable_fallback_ips)
    [[ "${routes[*]}" == "20.0.0.5 20.0.0.6" ]] \
        || fail "usable_fallback_ips did not preserve last-known routes during settling: ${routes[*]:-none}"
    pass "usable fallback exports keep last-known routes during route settling"
}

test_usable_fallback_ips_keeps_cached_routes_without_opt_in() {
    reset_runtime_paths
    ROUTE_HEALTH_TTL_SEC=300
    MAX_FALLBACK_LINKS=3
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    cat > "$ROUTE_HEALTH_FILE" <<'EOF'
2026-05-30T00:00:00Z	20.0.0.5	200	70	true
2026-05-30T00:00:00Z	20.0.0.6	200	80	true
EOF
    resolve_domain_ips() { fail "exports must not synchronously probe stale cache"; }
    xhttp_probe_metrics() { fail "exports must not synchronously probe stale cache"; }

    mapfile -t routes < <(usable_fallback_ips)
    [[ "${routes[*]}" == "20.0.0.5 20.0.0.6" ]] \
        || fail "usable_fallback_ips did not preserve cached routes: ${routes[*]:-none}"
    pass "usable fallback exports preserve cached routes without an opt-in flag"
}

test_usable_fallback_ips_never_runs_live_probe_fallback() {
    reset_runtime_paths
    MAX_FALLBACK_LINKS=1
    ROUTE_MONITOR_MAX_CANDIDATES=3
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    resolve_domain_ips() { fail "exports must not resolve fallback candidates"; }
    local probes_file="$TMP_ROOT/live-fallback-probes.txt"
    : > "$probes_file"
    xhttp_probe_metrics() {
        printf 'probe\n' >> "$probes_file"
        printf '404 50\n'
    }

    usable_fallback_ips >/dev/null || true
    local probes
    probes=$(wc -l < "$probes_file" | tr -d ' ')
    [[ "$probes" -eq 0 ]] || fail "export path still ran a live fallback probe; got $probes"
    pass "usable fallback exports never run a live fallback probe"
}

test_xhttp_config_path_is_cached_by_config_content() {
    reset_runtime_paths
    cat > "$CONFIG_FILE" <<'JSON'
{"inbounds":[{"tag":"vless-in","streamSettings":{"xhttpSettings":{"path":"/cached"}}}]}
JSON
    local jq_calls="$TMP_ROOT/jq-calls.txt"
    : > "$jq_calls"
    jq() {
        printf 'call\n' >> "$jq_calls"
        sed -nE 's/.*"path":"([^"]+)".*/\1/p' "$CONFIG_FILE"
    }
    local first second third calls old_mtime
    first="$(xhttp_config_path)"
    second="$(xhttp_config_path)"
    old_mtime=$(stat -c %Y "$CONFIG_FILE" 2>/dev/null || printf '')
    cat > "$CONFIG_FILE" <<'JSON'
{"inbounds":[{"tag":"vless-in","streamSettings":{"xhttpSettings":{"path":"/fresh"}}}]}
JSON
    if [[ -n "$old_mtime" ]]; then
        touch -d "@$old_mtime" "$CONFIG_FILE" 2>/dev/null || true
    fi
    third="$(xhttp_config_path)"
    unset -f jq
    calls=$(wc -l < "$jq_calls" | tr -d ' ')
    [[ "$first" == "/cached" && "$second" == "/cached" && "$third" == "/fresh" ]] \
        || fail "xhttp_config_path did not cache by content safely: first=$first second=$second third=$third"
    [[ "$calls" -eq 2 ]] || fail "xhttp_config_path parsed config $calls times instead of once per content version"
    pass "xHTTP config path is cached by config file content"
}

test_boot_status_helpers_record_silent_start_result() {
    reset_runtime_paths
    write_boot_status "route_settling" "silent_start" "Route still settling" "404" "33"
    python -m json.tool "$BOOT_STATUS_FILE" >/dev/null || fail "boot status is not valid JSON"
    grep -Fq '"status": "route_settling"' "$BOOT_STATUS_FILE" || fail "boot status missing status"
    boot_status_summary | grep -Fq 'route_settling' || fail "boot status summary missing status"
    pass "boot status helpers persist readable startup state"
}

test_boot_status_reports_state_write_failure() {
    reset_runtime_paths
    BOOT_STATUS_FILE="$TMP_ROOT/missing-boot-parent/boot_status.json"
    if write_boot_status "ready" "test" "message" "200" "1" >/dev/null 2>&1; then
        fail "boot status helper masked its state write failure"
    fi
    pass "boot status helper reports state write failures"
}

test_generate_config_replaces_stale_no_config_boot_status() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        PERFORMANCE_PROFILE=balanced
        write_boot_status "no_config" "silent_start" "No config exists yet" "0" "0"
        uuidgen() { printf '11111111-2222-3333-4444-555555555555\n'; }
        start_xray() { return 0; }
        wait_for_port() { return 0; }
        ensure_codespace_port_public() { return 0; }
        refresh_config_exports() { return 0; }
        xray_validate_config_file() { return 0; }
        xhttp_probe_metrics() { printf '200 12 ready\n'; }
        generate_config >/dev/null
        python -m json.tool "$BOOT_STATUS_FILE" >/dev/null || fail "generated boot status is not valid JSON"
        grep -Fq '"status": "ready"' "$BOOT_STATUS_FILE" || fail "generate_config did not mark boot status ready"
        grep -Fq '"reason": "generate_config"' "$BOOT_STATUS_FILE" || fail "generate_config did not replace stale silent_start reason"
        ! boot_status_summary | grep -Fq 'no_config' || fail "diagnostics would still show stale no_config boot status"
    )
    pass "generate_config replaces stale no-config boot status"
}

test_generate_config_keeps_success_when_boot_status_write_fails() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        PERFORMANCE_PROFILE=balanced
        uuidgen() { printf '11111111-2222-3333-4444-555555555555\n'; }
        start_xray() { return 0; }
        wait_for_port() { return 0; }
        ensure_codespace_port_public() { return 0; }
        refresh_config_exports() { return 0; }
        xray_validate_config_file() { return 0; }
        xhttp_probe_metrics() { printf '200 12 ready\n'; }
        write_boot_status() { return 1; }

        generate_config >/dev/null 2>&1 \
            || fail "generate_config reported failure after its engine started because diagnostic boot state could not be written"
        [[ -s "$CONFIG_FILE" && -s "$UUID_FILE" ]] \
            || fail "generate_config lost committed runtime state after nonfatal boot-status failure"
    )
    pass "generate_config treats boot-status persistence as nonfatal after a successful start"
}

test_generated_and_upgraded_configs_do_not_block_page_assets_as_ads() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        PERFORMANCE_PROFILE=balanced
        uuidgen() { printf '11111111-2222-4333-8444-555555555555\n'; }
        start_xray() { return 0; }
        wait_for_port() { return 0; }
        ensure_codespace_port_public() { return 0; }
        refresh_config_exports() { return 0; }
        xray_validate_config_file() { return 0; }
        xhttp_probe_metrics() { printf '200 12 ready\n'; }

        generate_config >/dev/null || fail "generate_config failed"
        if jq -e '.routing.rules[]? | select((.domain // []) | index("geosite:category-ads-all"))' \
            "$CONFIG_FILE" >/dev/null; then
            fail "generated config can break page completeness by blocking the broad ad category"
        fi

        jq '.routing.rules += [{
            "type": "field",
            "domain": ["geosite:category-ads-all"],
            "outboundTag": "block"
        }]' "$CONFIG_FILE" > "$CONFIG_FILE.legacy"
        mv "$CONFIG_FILE.legacy" "$CONFIG_FILE"
        upgrade_config_dns
        if jq -e '.routing.rules[]? | select((.domain // []) | index("geosite:category-ads-all"))' \
            "$CONFIG_FILE" >/dev/null; then
            fail "existing config migration retained the broad ad-category block"
        fi
    )
    pass "generated and upgraded configs keep ordinary page assets reachable"
}

test_existing_config_upgrade_preserves_uuid_and_safe_handshake_floor() {
    reset_runtime_paths
    cat > "$CONFIG_FILE" <<'JSON'
{
  "dns": {"servers": ["localhost"]},
  "policy": {"levels": {"0": {"handshake": 3, "connIdle": 900}}},
  "inbounds": [{
    "tag": "vless-ws",
    "protocol": "vless",
    "settings": {"clients": [{"id": "11111111-2222-4333-8444-555555555555"}]},
    "streamSettings": {"network": "ws", "wsSettings": {"path": "/ws"}},
    "sniffing": {"enabled": true, "destOverride": ["http", "tls"]}
  }],
  "outbounds": [
    {"tag": "direct", "protocol": "freedom"},
    {"tag": "block", "protocol": "blackhole", "settings": {"response": {"type": "http"}}}
  ],
  "routing": {"domainStrategy": "AsIs", "rules": []}
}
JSON

    upgrade_config_dns

    [[ "$(jq -r '.policy.levels["0"].handshake' "$CONFIG_FILE")" == "4" ]] \
        || fail "existing config migration did not restore the safe handshake floor"
    [[ "$(jq -r '.inbounds[0].settings.clients[0].id' "$CONFIG_FILE")" == "11111111-2222-4333-8444-555555555555" ]] \
        || fail "existing config migration changed the active UUID"
    [[ "$(jq -r '.inbounds[0].streamSettings.wsSettings.heartbeatPeriod' "$CONFIG_FILE")" == "30" ]] \
        || fail "existing config migration did not add the WebSocket idle heartbeat"
    [[ "$(jq -r '.inbounds[0].sniffing.routeOnly' "$CONFIG_FILE")" == "true" ]] \
        || fail "existing WS migration still allows sniffing to rewrite explicit destinations"
    [[ "$(jq -r '.outbounds[] | select(.tag == "block") | .settings.response.type' "$CONFIG_FILE")" == "none" ]] \
        || fail "existing config migration retained an HTTP payload on the generic blackhole outbound"
    pass "existing config upgrade preserves UUID and restores safe handshake, blackhole, and WebSocket settings"
}

test_existing_xhttp_config_upgrade_accepts_all_client_modes() {
    reset_runtime_paths
    cat > "$CONFIG_FILE" <<'JSON'
{
  "dns": {"servers": ["localhost"]},
  "policy": {"levels": {"0": {"handshake": 4}}},
  "inbounds": [{
    "tag": "vless-in",
    "protocol": "vless",
    "settings": {"clients": [{"id": "11111111-2222-4333-8444-555555555555"}]},
    "streamSettings": {"network": "xhttp", "xhttpSettings": {"path": "/", "mode": "packet-up"}},
    "sniffing": {"enabled": true, "destOverride": ["http", "tls"]}
  }],
  "outbounds": [
    {"tag": "direct", "protocol": "freedom"},
    {"tag": "block", "protocol": "blackhole", "settings": {"response": {"type": "none"}}}
  ],
  "routing": {"domainStrategy": "AsIs", "rules": []}
}
JSON

    upgrade_config_dns

    [[ "$(jq -r '.inbounds[] | select(.tag == "vless-in") | .streamSettings.xhttpSettings.mode' "$CONFIG_FILE")" == "auto" ]] \
        || fail "existing XHTTP inbound remained restricted to one client mode"
    [[ "$(jq -r '.inbounds[] | select(.tag == "vless-in") | .settings.clients[0].id' "$CONFIG_FILE")" == "11111111-2222-4333-8444-555555555555" ]] \
        || fail "XHTTP compatibility migration changed the active UUID"
    [[ "$(jq -r '.inbounds[0].sniffing.routeOnly' "$CONFIG_FILE")" == "true" ]] \
        || fail "existing XHTTP migration still allows sniffing to rewrite explicit destinations"
    pass "existing XHTTP config accepts all supported client modes without changing identity"
}

test_start_xray_preserves_engine_when_config_migration_fails() {
    (
        reset_runtime_paths
        write_test_vless_config
        cp "$CONFIG_FILE" "$TMP_ROOT/before-migration.json"
        upgrade_config_dns() { return 1; }
        xray_validate_config() { fail 'failed migration must not be treated as successful preflight'; }
        stop_xray() { fail 'failed migration must not stop the existing engine'; }
        if start_xray >/dev/null 2>&1; then
            fail 'start_xray ignored a config migration failure'
        fi
        cmp -s "$CONFIG_FILE" "$TMP_ROOT/before-migration.json" \
            || fail 'failed migration did not preserve the previous config'
        grep -Fq 'config_migration_failed' "$LOG_FILE" \
            || fail 'migration failure was not logged separately'
    )
    pass 'startup preserves current engine and config when migration fails'
}

test_start_xray_restores_config_when_migrated_config_is_invalid() {
    reset_runtime_paths
    (
        cat > "$CONFIG_FILE" <<'JSON'
{
  "dns": {"servers": ["localhost"]},
  "policy": {"levels": {"0": {"handshake": 3}}},
  "inbounds": [{"sniffing": {"enabled": true, "destOverride": ["http", "tls", "quic"]}}],
  "routing": {"domainStrategy": "IPIfNonMatch", "rules": []}
}
JSON
        cp "$CONFIG_FILE" "$CONFIG_FILE.before"
        local stop_calls=0
        xray_validate_config() { return 1; }
        stop_xray() {
            stop_calls=$((stop_calls + 1))
            return 0
        }

        if start_xray >/dev/null 2>&1; then
            fail "start_xray accepted a migrated config that failed validation"
        fi
        cmp -s "$CONFIG_FILE.before" "$CONFIG_FILE" \
            || fail "start_xray left the invalid migrated config on disk"
        [[ "$stop_calls" -eq 0 ]] \
            || fail "start_xray stopped the existing engine before validating its migrated config"
    )
    pass "start_xray restores the previous config when migration validation fails"
}

test_start_xray_restores_config_when_previous_engine_cannot_stop() {
    reset_runtime_paths
    (
        cat > "$CONFIG_FILE" <<'JSON'
{
  "dns": {"servers": ["localhost"]},
  "policy": {"levels": {"0": {"handshake": 3}}},
  "inbounds": [{"sniffing": {"enabled": true, "destOverride": ["http", "tls", "quic"]}}],
  "routing": {"domainStrategy": "IPIfNonMatch", "rules": []}
}
JSON
        cp "$CONFIG_FILE" "$CONFIG_FILE.before"
        xray_validate_config() { return 0; }
        stop_xray() { return 1; }

        if start_xray >/dev/null 2>&1; then
            fail "start_xray reported success when the previous engine could not stop"
        fi
        cmp -s "$CONFIG_FILE.before" "$CONFIG_FILE" \
            || fail "start_xray left migrated disk state under the still-running previous engine"
    )
    pass "start_xray restores disk state when the previous engine cannot stop"
}

test_start_xray_cleans_up_when_pid_state_cannot_be_committed() {
    reset_runtime_paths
    (
        write_test_vless_config
        local sudo_calls="$TMP_ROOT/start-xray-sudo-calls.txt" rc=0
        : > "$sudo_calls"
        xray_validate_config() { return 0; }
        upgrade_config_dns() { return 0; }
        stop_xray() { return 0; }
        _atomic_write() {
            local file="$1" content="$2"
            [[ "$file" != "$XRAY_PID_FILE" ]] || return 1
            printf '%s\n' "$content" > "$file"
        }
        sudo() {
            printf '%s\n' "$*" >> "$sudo_calls"
            if [[ "${1:-}" == "bash" ]]; then
                printf '424242\n'
                return 0
            fi
            if [[ "${1:-}" == "kill" && "${2:-}" == "-0" ]]; then
                return 1
            fi
            return 0
        }

        set +e
        start_xray >/dev/null 2>&1
        rc=$?
        set -e

        [[ "$rc" -ne 0 ]] || fail "start_xray reported success after its PID state write failed"
        [[ ! -e "$XRAY_PID_FILE" ]] || fail "start_xray left a partial PID file after state commit failure"
        grep -Fq 'kill 424242' "$sudo_calls" \
            || fail "start_xray left its newly launched process running after PID state commit failure"
    )
    pass "start_xray cleans up a launch whose PID state cannot be committed"
}

test_session_uptime_recovers_from_corrupt_state() {
    reset_runtime_paths
    printf 'not-an-epoch\n' > "$SESSION_START_FILE"
    printf 'not-a-total\n' > "$TOTAL_UPTIME_FILE"
    local rc=0 total session
    set +e
    (save_session_uptime)
    rc=$?
    set -e
    [[ "$rc" -eq 0 ]] || fail "save_session_uptime crashed on corrupt persisted state"
    total=$(cat "$TOTAL_UPTIME_FILE" 2>/dev/null || true)
    session=$(cat "$SESSION_START_FILE" 2>/dev/null || true)
    [[ "$total" =~ ^[0-9]+$ && "$session" =~ ^[0-9]+$ ]] \
        || fail "save_session_uptime did not repair corrupt persisted state"
    pass "session uptime safely repairs corrupt persisted state"
}

test_quota_display_recovers_from_corrupt_state() {
    reset_runtime_paths
    printf 'not-an-epoch\n' > "$SESSION_START_FILE"
    printf 'not-a-total\n' > "$TOTAL_UPTIME_FILE"
    local output rc=0 total session
    set +e
    output=$(estimate_quota 2>&1)
    rc=$?
    set -e
    [[ "$rc" -eq 0 ]] || fail "estimate_quota crashed on corrupt persisted state"
    grep -Fq 'Total Used' <<< "$output" \
        || fail "estimate_quota did not render after repairing corrupt state"
    total=$(cat "$TOTAL_UPTIME_FILE" 2>/dev/null || true)
    session=$(cat "$SESSION_START_FILE" 2>/dev/null || true)
    [[ "$total" =~ ^[0-9]+$ && "$session" =~ ^[0-9]+$ ]] \
        || fail "estimate_quota did not repair corrupt persisted state"
    pass "quota display safely repairs corrupt persisted state"
}

test_generate_config_refreshes_expired_route_cache_before_exports() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        PERFORMANCE_PROFILE=balanced
        ROUTE_HEALTH_EXPORT_MAX_AGE_SEC=21600
        printf '2026-05-30T00:00:00Z\t20.0.0.1\t200\t50\ttrue\tcache\tready\n' > "$ROUTE_HEALTH_FILE"
        touch -d '7 hours ago' "$ROUTE_HEALTH_FILE"
        uuidgen() { printf 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\n'; }
        start_xray() { return 0; }
        wait_for_port() { return 0; }
        ensure_codespace_port_public() { return 0; }
        xray_validate_config_file() { return 0; }
        xhttp_probe_metrics() { printf '200 12 ready\n'; }
        _refresh_route_candidate_health_impl() {
            printf '2026-07-21T18:02:21Z\t20.0.0.2\t200\t40\ttrue\tdns\tready\n' > "$ROUTE_HEALTH_FILE"
            return 0
        }

        generate_config >/dev/null || fail "generate_config failed while refreshing an expired route cache"
        grep -Fq '@20.0.0.2:443?' "$MOBILE_CONFIG_FILE" \
            || fail "generate_config exported domain-only links before its expired route cache refresh completed"
    )
    pass "generate_config refreshes an expired route cache before exporting links"
}

test_generate_config_keeps_previous_config_when_candidate_validation_fails() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        PERFORMANCE_PROFILE=balanced
        printf 'old-uuid\n' > "$UUID_FILE"
        printf '{"old":true}\n' > "$CONFIG_FILE"
        uuidgen() { printf '22222222-3333-4444-8555-666666666666\n'; }
        xray_validate_config_file() { return 1; }
        start_xray() { fail "generate_config should not start Xray after invalid candidate"; }
        refresh_config_exports() { fail "generate_config should not refresh exports after invalid candidate"; }

        if generate_config >/dev/null 2>&1; then
            fail "generate_config succeeded despite invalid candidate config"
        fi
        grep -Fq '"old":true' "$CONFIG_FILE" || fail "invalid candidate replaced previous config"
        grep -Fxq 'old-uuid' "$UUID_FILE" || fail "invalid candidate replaced previous UUID"
    )
    pass "generate_config keeps previous config when candidate validation fails"
}

test_generate_config_candidate_file_keeps_json_suffix_for_xray_detection() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        PERFORMANCE_PROFILE=balanced
        uuidgen() { printf '44444444-5555-4666-8777-888888888888\n'; }
        local candidate_seen="$TMP_ROOT/candidate-path.txt"
        xray_validate_config_file() {
            printf '%s\n' "$1" > "$candidate_seen"
            return 1
        }
        start_xray() { fail "generate_config should not start after candidate validation failure"; }
        refresh_config_exports() { fail "generate_config should not export after candidate validation failure"; }

        if generate_config >/dev/null 2>&1; then
            fail "generate_config succeeded despite forced candidate validation failure"
        fi
        local candidate
        candidate=$(cat "$candidate_seen" 2>/dev/null || true)
        [[ "$candidate" == *.json ]] || fail "candidate config path does not keep .json suffix for Xray format detection: $candidate"
    )
    pass "generate_config candidate file keeps .json suffix for Xray format detection"
}

test_generate_config_rolls_back_when_valid_candidate_cannot_start() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        PERFORMANCE_PROFILE=balanced
        local old_uuid="11111111-2222-4333-8444-555555555555"
        printf '%s\n' "$old_uuid" > "$UUID_FILE"
        cat > "$CONFIG_FILE" <<JSON
{"old":true,"inbounds":[{"protocol":"vless","settings":{"clients":[{"id":"${old_uuid}"}]}}]}
JSON
        uuidgen() { printf '33333333-4444-4555-8666-777777777777\n'; }
        xray_validate_config_file() { return 0; }
        start_xray() { return 1; }
        wait_for_port() { return 1; }
        refresh_config_exports() { fail "generate_config should not refresh exports after failed start"; }

        if generate_config >/dev/null 2>&1; then
            fail "generate_config succeeded despite failed engine start"
        fi
        grep -Fq '"old":true' "$CONFIG_FILE" || fail "failed start did not restore previous config"
        grep -Fxq "$old_uuid" "$UUID_FILE" || fail "failed start did not restore previous UUID"
    )
    pass "generate_config rolls back when a valid candidate cannot start"
}

test_config_exports_write_local_only_metadata() {
    reset_runtime_paths
    BASE_DIR="$TMP_ROOT"
    MOBILE_CONFIG_FILE="$BASE_DIR/configs-to-copy-for-mobile.txt"
    SUBSCRIPTION_FILE="$BASE_DIR/configs-subscription-base64.txt"
    CONFIG_META_FILE="$BASE_DIR/configs-meta.json"
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT=443
    GITHUB_USER="tester"
    write_config_exports_from_links "vless://example-one" "vless://example-two" >/dev/null

    python -m json.tool "$CONFIG_META_FILE" >/dev/null || fail "config metadata is not valid JSON"
    python -m json.tool "$CODESPACE_BUNDLE_FILE" >/dev/null || fail "Codespace import bundle is not valid JSON"
    grep -Fq '"codespace_name": "behavior-space"' "$CODESPACE_BUNDLE_FILE" \
        || fail "Codespace import bundle is missing its Codespace name"
    grep -Fq '"configs": [' "$CODESPACE_BUNDLE_FILE" \
        || fail "Codespace import bundle is missing its config list"
    ! grep -Eqi 'wake[_-]?secret|github[_-]?token' "$CODESPACE_BUNDLE_FILE" \
        || fail "Codespace import bundle must not contain Worker credentials"
    grep -Fq '"config_count": 2' "$CONFIG_META_FILE" || fail "config metadata missing count"
    grep -Fq '"subscription_scope": "local_codespace_only"' "$CONFIG_META_FILE" \
        || fail "config metadata does not mark subscription as local-only"
    ! grep -Fq '"subscription_url"' "$CONFIG_META_FILE" \
        || fail "config metadata still exposes a subscription URL"
    pass "config exports write machine-readable local-only metadata"
}

test_config_exports_report_write_failure() {
    reset_runtime_paths
    MOBILE_CONFIG_FILE="$TMP_ROOT/missing-parent/configs-to-copy-for-mobile.txt"
    SUBSCRIPTION_FILE="$TMP_ROOT/configs-subscription-base64.txt"
    CONFIG_META_FILE="$TMP_ROOT/configs-meta.json"
    rm -f "$SUBSCRIPTION_FILE" "$CONFIG_META_FILE"

    if write_config_exports_from_links "vless://example-one" >/dev/null 2>&1; then
        fail "config export writer reported success when mobile export write failed"
    fi
    [[ ! -e "$SUBSCRIPTION_FILE" ]] \
        || fail "config export writer continued to write subscription after mobile export failure"
    pass "config exports report write failures instead of masking them"
}

test_config_exports_report_metadata_write_failure() {
    reset_runtime_paths
    CONFIG_META_FILE="$TMP_ROOT/missing-meta-parent/configs-meta.json"

    if write_config_exports_from_links "vless://example-one" >/dev/null 2>&1; then
        fail "config export writer masked metadata write failure"
    fi
    [[ ! -e "$CONFIG_META_FILE" ]] \
        || fail "config export writer unexpectedly created metadata in a missing parent"
    pass "config exports report metadata write failures"
}

test_refresh_config_exports_reports_write_failure() {
    reset_runtime_paths
    (
        printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
        MOBILE_CONFIG_FILE="$TMP_ROOT/missing-parent/configs-to-copy-for-mobile.txt"
        SUBSCRIPTION_FILE="$TMP_ROOT/configs-subscription-base64.txt"
        CONFIG_META_FILE="$TMP_ROOT/configs-meta.json"
        EXPORT_INPUT_HASH_FILE="$TMP_ROOT/export-input.hash"
        printf 'old-hash\n' > "$EXPORT_INPUT_HASH_FILE"
        generate_ordered_links() { printf 'vless://example-one\n'; }

        if refresh_config_exports >/dev/null 2>&1; then
            fail "refresh_config_exports reported success when export writes failed"
        fi
        [[ "$(cat "$EXPORT_INPUT_HASH_FILE" 2>/dev/null || true)" == "old-hash" ]] \
            || fail "refresh_config_exports updated input hash after failed export write"
        [[ ! -e "$CONFIG_META_FILE" ]] \
            || fail "refresh_config_exports wrote metadata after failed export write"
    )
    pass "refresh config exports reports write failures instead of masking them"
}

test_config_exports_are_stable_client_artifacts() {
    reset_runtime_paths
    BASE_DIR="$TMP_ROOT"
    MOBILE_CONFIG_FILE="$BASE_DIR/configs-to-copy-for-mobile.txt"
    SUBSCRIPTION_FILE="$BASE_DIR/configs-subscription-base64.txt"
    CONFIG_META_FILE="$BASE_DIR/configs-meta.json"
    local link1='vless://uuid@example.com:443?encryption=none#one'
    local link2='vless://uuid@20.0.0.1:443?encryption=none#two'

    write_config_exports_from_links "$link1" "$link2" >/dev/null
    mapfile -t mobile_lines < "$MOBILE_CONFIG_FILE"
    [[ "${mobile_lines[0]:-}" == "$link1" && "${mobile_lines[1]:-}" == "$link2" ]] \
        || fail "mobile export did not preserve generated link ordering"
    python - "$SUBSCRIPTION_FILE" "$link1" "$link2" <<'PY' || fail "subscription export does not decode to expected VLESS links"
import base64, pathlib, sys
decoded = base64.b64decode(pathlib.Path(sys.argv[1]).read_text()).decode().splitlines()
if decoded != [sys.argv[2], sys.argv[3]]:
    raise SystemExit(decoded)
PY
    pass "config exports produce stable mobile and base64 subscription artifacts"
}

test_domain_link_export_can_be_disabled_for_blocked_networks() {
    reset_runtime_paths
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    GITHUB_USER="tester"
    printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
    usable_fallback_ips() { printf '20.0.0.1\n'; }

    printf '0\n' > "$DOMAIN_LINK_EXPORT_FILE"
    local links
    links="$(generate_ordered_links)"

    [[ "$links" == *"@20.0.0.1:"* ]] || fail "domain-disabled exports lost IP fallback link"
    [[ "$links" != *"@behavior-space-443.app.github.dev:"* ]] \
        || fail "domain-disabled exports still included the blocked domain link"
    pass "domain link export can be disabled for blocked local networks"
}

test_disabled_domain_link_retains_last_exports_when_no_ip_is_available() {
    (
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT=443
    GITHUB_USER="tester"
    MAX_FALLBACK_LINKS=2
    printf '00000000-0000-4000-8000-000000000001\n' > "$UUID_FILE"
    write_test_vless_config
    printf 'vless://00000000-0000-4000-8000-000000000001@behavior-space-443.app.github.dev:443#stale\n' > "$MOBILE_CONFIG_FILE"
    printf 'dmxlc3M6Ly8wMDAwMDAwMC0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDFAZXhhbXBsZS5pbnZhbGlkCg==' > "$SUBSCRIPTION_FILE"
    printf '{}\n' > "$CONFIG_META_FILE"
    printf '0\n' > "$DOMAIN_LINK_EXPORT_FILE"
    usable_fallback_ips() { return 0; }

    if refresh_config_exports >/dev/null 2>&1; then
        fail "refresh_config_exports reported success when domain export was disabled and no IP fallback existed"
    fi
    [[ -s "$MOBILE_CONFIG_FILE" ]] || fail "last-known mobile configs were deleted after a transient no-link refresh"
    [[ -s "$SUBSCRIPTION_FILE" ]] || fail "last-known subscription was deleted after a transient no-link refresh"
    grep -Fq 'config_exports retained_previous reason=no_exportable_links' "$LOG_FILE" \
        || fail "retained export fallback was not logged"
    )
    pass "transient no-link refresh retains last-known local config artifacts"
}

test_no_link_refresh_clears_exports_from_a_previous_uuid() {
    (
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT=443
    GITHUB_USER="tester"
    printf '11111111-2222-4333-8444-555555555555\n' > "$UUID_FILE"
    write_test_vless_config
    printf 'vless://00000000-0000-4000-8000-000000000001@behavior-space-443.app.github.dev:443#old\n' > "$MOBILE_CONFIG_FILE"
    printf 'b2xkCg==' > "$SUBSCRIPTION_FILE"
    printf '{}\n' > "$CONFIG_META_FILE"
    printf '0\n' > "$DOMAIN_LINK_EXPORT_FILE"
    usable_fallback_ips() { return 0; }

    if refresh_config_exports >/dev/null 2>&1; then
        fail "refresh_config_exports reported success when no links were exportable"
    fi
    [[ ! -e "$MOBILE_CONFIG_FILE" ]] \
        || fail "configs from a previous UUID survived after credentials changed"
    [[ ! -e "$SUBSCRIPTION_FILE" ]] \
        || fail "subscription from a previous UUID survived after credentials changed"
    grep -Fq 'config_exports cleared reason=no_exportable_links_uuid_mismatch' "$LOG_FILE" \
        || fail "old-UUID export cleanup was not classified in the log"
    )
    pass "no-link refresh clears exports from a previous UUID"
}

test_failed_export_does_not_restore_exports_from_a_previous_uuid() {
    (
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT=443
    printf '11111111-2222-4333-8444-555555555555\n' > "$UUID_FILE"
    write_test_vless_config
    printf 'vless://00000000-0000-4000-8000-000000000001@behavior-space-443.app.github.dev:443#old\n' > "$MOBILE_CONFIG_FILE"
    printf 'b2xkCg==' > "$SUBSCRIPTION_FILE"
    printf '{}\n' > "$CONFIG_META_FILE"
    generate_ordered_links() {
        printf 'vless://11111111-2222-4333-8444-555555555555@behavior-space-443.app.github.dev:443#new\n'
    }
    write_config_exports_from_links() { return 1; }

    if refresh_config_exports >/dev/null 2>&1; then
        fail "refresh_config_exports reported success after a forced write failure"
    fi
    [[ ! -e "$MOBILE_CONFIG_FILE" ]] \
        || fail "failed new export restored unusable configs from a previous UUID"
    grep -Fq 'config_exports cleared reason=write_failed_no_valid_backup' "$LOG_FILE" \
        || fail "write failure without a current-UUID backup was not classified"
    )
    pass "failed export never restores configs from a previous UUID"
}

test_ordered_links_reuse_fallback_ips_for_xhttp_and_ws_exports() {
    (
        reset_runtime_paths
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        WS_PORT_DOMAIN="behavior-space-8443.app.github.dev"
        GITHUB_USER="tester"
        printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
        printf 'enabled\n' > "$WS_FALLBACK_FILE"
        local calls_file="$TMP_ROOT/usable-fallback-calls.txt" uuid_calls_file="$TMP_ROOT/active-uuid-calls.txt"
        : > "$calls_file"
        : > "$uuid_calls_file"
        usable_fallback_ips() {
            printf 'call\n' >> "$calls_file"
            printf '20.0.0.1\n20.0.0.2\n'
        }
        active_vless_uuid() {
            printf 'call\n' >> "$uuid_calls_file"
            printf '11111111-2222-3333-4444-555555555555\n'
        }

        local links calls uuid_calls
        links="$(generate_ordered_links)"
        calls=$(wc -l < "$calls_file" | tr -d ' ')
        uuid_calls=$(wc -l < "$uuid_calls_file" | tr -d ' ')
        [[ "$calls" -eq 1 ]] || fail "usable_fallback_ips was called $calls times for one ordered export"
        [[ "$uuid_calls" -eq 1 ]] || fail "active UUID was parsed $uuid_calls times for one ordered export"
        [[ "$links" == *"@20.0.0.1:443"* && "$links" == *"-ws-ip1"* ]] \
            || fail "ordered links did not include both XHTTP and WS fallback links from the shared list"
    )
    pass "ordered exports reuse one fallback route list for XHTTP and WS"
}

test_refresh_config_exports_if_changed_skips_unchanged_inputs() {
    reset_runtime_paths
    BASE_DIR="$TMP_ROOT"
    MOBILE_CONFIG_FILE="$BASE_DIR/configs-to-copy-for-mobile.txt"
    SUBSCRIPTION_FILE="$BASE_DIR/configs-subscription-base64.txt"
    CONFIG_META_FILE="$BASE_DIR/configs-meta.json"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    WS_PORT_DOMAIN="behavior-space-8443.app.github.dev"
    GITHUB_USER="tester"
    printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
    write_test_vless_config
    printf '2026-01-01T00:00:00Z\t20.0.0.1\t200\t10\ttrue\tcache\tready\n' > "$ROUTE_HEALTH_FILE"
    local calls_file="$TMP_ROOT/export-calls.txt"
    : > "$calls_file"
    generate_ordered_links() {
        printf 'call\n' >> "$calls_file"
        printf 'vless://example@20.0.0.1:443?encryption=none#one\n'
    }

    refresh_config_exports_if_changed >/dev/null || fail "first export refresh failed"
    refresh_config_exports_if_changed >/dev/null || fail "unchanged export refresh failed"
    local calls
    calls=$(wc -l < "$calls_file" | tr -d ' ')
    [[ "$calls" -eq 1 ]] || fail "unchanged export inputs regenerated links $calls times"
    grep -Fq 'config_exports unchanged' "$LOG_FILE" || fail "unchanged export refresh was not logged"
    pass "unchanged export inputs skip regeneration"
}

test_refresh_config_exports_if_changed_repairs_missing_codespace_bundle() {
    reset_runtime_paths
    BASE_DIR="$TMP_ROOT"
    MOBILE_CONFIG_FILE="$BASE_DIR/configs-to-copy-for-mobile.txt"
    SUBSCRIPTION_FILE="$BASE_DIR/configs-subscription-base64.txt"
    CONFIG_META_FILE="$BASE_DIR/configs-meta.json"
    CODESPACE_BUNDLE_FILE="$BASE_DIR/g2ray-codespace-bundle.json"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    WS_PORT_DOMAIN="behavior-space-8443.app.github.dev"
    CODESPACE_NAME="behavior-space"
    GITHUB_USER="tester"
    printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
    write_test_vless_config
    printf '2026-01-01T00:00:00Z\t20.0.0.1\t200\t10\ttrue\tcache\tready\n' > "$ROUTE_HEALTH_FILE"
    local calls_file="$TMP_ROOT/export-repair-calls.txt"
    : > "$calls_file"
    generate_ordered_links() {
        printf 'call\n' >> "$calls_file"
        printf 'vless://example@20.0.0.1:443?encryption=none#one\n'
    }

    refresh_config_exports_if_changed >/dev/null || fail "initial export refresh failed"
    rm -f "$CODESPACE_BUNDLE_FILE"
    refresh_config_exports_if_changed >/dev/null || fail "missing Codespace bundle was not repaired"

    local calls
    calls=$(wc -l < "$calls_file" | tr -d ' ')
    [[ "$calls" -eq 2 ]] || fail "missing Codespace bundle did not trigger one export rebuild"
    [[ -s "$CODESPACE_BUNDLE_FILE" ]] || fail "Codespace bundle remained missing after export repair"
    pass "unchanged export inputs repair a missing Codespace bundle"
}

test_refresh_config_exports_updates_input_hash_after_direct_refresh() {
    reset_runtime_paths
    BASE_DIR="$TMP_ROOT"
    MOBILE_CONFIG_FILE="$BASE_DIR/configs-to-copy-for-mobile.txt"
    SUBSCRIPTION_FILE="$BASE_DIR/configs-subscription-base64.txt"
    CONFIG_META_FILE="$BASE_DIR/configs-meta.json"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    GITHUB_USER="tester"
    printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
    write_test_vless_config
    printf 'old-hash\n' > "$EXPORT_INPUT_HASH_FILE"
    generate_ordered_links() {
        printf 'vless://example@20.0.0.1:443?encryption=none#one\n'
    }

    local expected actual
    expected=$(export_input_hash)
    refresh_config_exports >/dev/null || fail "direct export refresh failed"
    actual=$(cat "$EXPORT_INPUT_HASH_FILE" 2>/dev/null || true)
    [[ -n "$expected" && "$actual" == "$expected" ]] \
        || fail "direct export refresh did not update input hash"
    pass "direct export refresh updates the input hash"
}

test_force_reconnect_skips_restart_when_identity_unknown() {
    (
        reset_runtime_paths
        _detect_codespace_name() { printf 'unknown-codespace\n'; }
        refresh_port_domains() { PORT_DOMAIN="unknown-codespace-443.app.github.dev"; }
        stop_xray() { fail "force_reconnect should not stop Xray when identity is unknown"; }
        start_xray() { fail "force_reconnect should not start Xray when identity is unknown"; }
        ensure_codespace_port_public() { fail "force_reconnect should not expose ports when identity is unknown"; }

        local rc=0
        if _force_reconnect_impl --no-prompt >/dev/null; then
            fail "force_reconnect succeeded with unknown Codespaces identity"
        else
            rc=$?
        fi
        [[ "$rc" -eq 1 ]] || fail "force_reconnect returned unexpected status $rc for unknown identity"
        grep -Fq 'force_reconnect identity_unknown action=abort' "$LOG_FILE" \
            || fail "force_reconnect did not log identity_unknown abort"
    )
    pass "force reconnect skips destructive restart when Codespaces identity is unknown"
}

test_generated_links_follow_configured_xhttp_path() {
    reset_runtime_paths
    BASE_DIR="$TMP_ROOT"
    MOBILE_CONFIG_FILE="$BASE_DIR/configs-to-copy-for-mobile.txt"
    SUBSCRIPTION_FILE="$BASE_DIR/configs-subscription-base64.txt"
    CONFIG_META_FILE="$BASE_DIR/configs-meta.json"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    GITHUB_USER="tester"
    printf '11111111-2222-4333-8444-555555555555\n' > "$UUID_FILE"
    cat > "$CONFIG_FILE" <<'JSON'
{
  "inbounds": [
    {
      "tag": "vless-in",
      "protocol": "vless",
      "settings": {"clients": [{"id": "11111111-2222-4333-8444-555555555555"}]},
      "streamSettings": {
        "network": "xhttp",
        "xhttpSettings": {
          "path": "/custom"
        }
      }
    }
  ]
}
JSON

    local link
    link="$(generate_link_for_address "20.0.0.1" "-ip1")"
    [[ "$link" == *"path=%2Fcustom"* ]] \
        || fail "generated VLESS link did not use configured XHTTP path: $link"
    write_config_exports_from_links "$link" >/dev/null
    grep -Fq 'path=%2Fcustom' "$MOBILE_CONFIG_FILE" \
        || fail "mobile export did not preserve configured XHTTP path"
    pass "generated links follow configured XHTTP path"
}

test_xhttp_mode_is_persistent_and_link_consistent() {
    reset_runtime_paths
    BASE_DIR="$TMP_ROOT"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    GITHUB_USER="tester"
    XRAY_PORT=443
    CODESPACES_EDGE_PORT=443
    printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
    start_xray() { return 0; }
    wait_for_port() { return 0; }
    ensure_codespace_port_public() { return 0; }
    refresh_config_exports() { return 0; }
    xray_validate_config_file() { return 0; }
    xhttp_probe_metrics() { printf '200 1 ready\n'; }

    set_xhttp_mode "stream-up" >/dev/null || fail "set_xhttp_mode did not accept stream-up"
    [[ "$(xhttp_mode_value)" == "stream-up" ]] || fail "xhttp mode was not persisted"
    generate_config >/dev/null || fail "generate_config failed with stream-up mode"
    grep -Fq '"mode": "auto"' "$CONFIG_FILE" || fail "server config did not accept all supported XHTTP client modes"
    grep -Fq '"168.63.129.16"' "$CONFIG_FILE" || fail "server config does not include Azure internal DNS resolver"
    grep -Fq '"network": "udp", "port": "443"' "$CONFIG_FILE" || fail "server config does not block outbound UDP/443"
    if grep -Fq '"quic"' "$CONFIG_FILE"; then
        fail "server config still enables QUIC sniffing by default"
    fi
    local link
    link="$(generate_link_for_address "20.0.0.1" "-ip1")"
    [[ "$link" == *"mode=stream-up"* ]] || fail "generated link did not match persistent XHTTP mode: $link"

    if set_xhttp_mode "invalid-mode" >/dev/null 2>&1; then
        fail "set_xhttp_mode accepted an invalid mode"
    fi
    pass "XHTTP mode persists in links while the server accepts all supported client modes"
}

test_xhttp_mode_change_does_not_restart_auto_inbound() {
    (
        reset_runtime_paths
        printf '11111111-2222-4333-8444-555555555555\n' > "$UUID_FILE"
        cat > "$CONFIG_FILE" <<'JSON'
{"inbounds":[{"tag":"vless-in","protocol":"vless","settings":{"clients":[{"id":"11111111-2222-4333-8444-555555555555"}]},"streamSettings":{"network":"xhttp","xhttpSettings":{"path":"/","mode":"auto"}}}]}
JSON
        local refreshed=0
        refresh_config_exports() { refreshed=1; return 0; }
        reapply_config_preserving_uuid_if_exists() { fail "auto XHTTP inbound was restarted for a link-only mode change"; }

        set_xhttp_mode "stream-up" >/dev/null || fail "mode change failed with auto inbound"
        [[ "$refreshed" == "1" ]] || fail "mode change did not refresh exported links"
        [[ "$(xhttp_config_mode)" == "stream-up" ]] || fail "auto inbound did not export the selected client mode"
    )
    pass "XHTTP link mode changes do not restart an auto-mode server inbound"
}

test_generated_links_use_active_config_mode_over_env_override() {
    reset_runtime_paths
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    GITHUB_USER="tester"
    CODESPACES_EDGE_PORT=443
    printf '11111111-2222-4333-8444-555555555555\n' > "$UUID_FILE"
    cat > "$CONFIG_FILE" <<'JSON'
{"inbounds":[{"tag":"vless-in","protocol":"vless","settings":{"clients":[{"id":"11111111-2222-4333-8444-555555555555"}]},"streamSettings":{"network":"xhttp","xhttpSettings":{"path":"/","mode":"stream-one"}}}]}
JSON
    printf 'packet-up\n' > "$XHTTP_MODE_FILE"
    G2RAY_XHTTP_MODE="stream-up"
    local link
    link="$(generate_link_for_address "20.0.0.1" "-ip1")"
    unset G2RAY_XHTTP_MODE
    [[ "$link" == *"mode=stream-one"* ]] \
        || fail "generated link followed preference/env mode instead of active config: $link"
    pass "generated links use the active XHTTP config mode"
}

test_xhttp_mode_cache_avoids_reparsing_and_tracks_config_changes() {
    (
        reset_runtime_paths
        local calls="$TMP_ROOT/mode-jq-count" i
        : > "$calls"
        write_test_vless_config
        jq() { printf 'call\n' >> "$calls"; command jq "$@"; }
        for ((i=0; i<20; i++)); do
            [[ "$(xhttp_active_inbound_mode)" == packet-up ]] || fail "cached mode changed"
        done
        [[ "$(wc -l < "$calls")" -eq 1 ]] || fail "mode cache reparsed unchanged config"
        command jq '.inbounds[0].streamSettings.xhttpSettings.mode = "stream-one"' "$CONFIG_FILE" > "${CONFIG_FILE}.new"
        mv "${CONFIG_FILE}.new" "$CONFIG_FILE"
        [[ "$(xhttp_active_inbound_mode)" == stream-one ]] || fail "mode cache survived a config replacement"
        [[ "$(wc -l < "$calls")" -eq 2 ]] || fail "replacement config was not parsed exactly once"
        printf 'invalid json\n' > "$CONFIG_FILE"
        [[ -z "$(xhttp_active_inbound_mode)" ]] || fail "corrupt config reused the old mode"
        rm "$CONFIG_FILE"
        [[ -z "$(xhttp_active_inbound_mode)" ]] || fail "missing config reused the old mode"
    )
    pass "XHTTP mode cache parses unchanged config once and invalidates on replacement, corruption, and deletion"
}

test_xhttp_mode_cache_preserves_preferences_and_write_failure_fallback() {
    (
        reset_runtime_paths
        write_test_vless_config
        command jq '.inbounds[0].streamSettings.xhttpSettings.mode = "auto"' "$CONFIG_FILE" > "${CONFIG_FILE}.new"
        mv "${CONFIG_FILE}.new" "$CONFIG_FILE"
        G2RAY_XHTTP_MODE=packet-up
        [[ "$(xhttp_config_mode)" == packet-up ]] || fail "auto inbound ignored the link preference"
        G2RAY_XHTTP_MODE=stream-up
        [[ "$(xhttp_config_mode)" == stream-up ]] || fail "mode cache froze the link preference"
        printf '%s\tinvalid-mode\n' "$(file_fingerprint "$CONFIG_FILE")" > "$XHTTP_MODE_CACHE_FILE"
        [[ "$(xhttp_active_inbound_mode)" == auto ]] || fail "invalid cached mode was trusted"
        rm "$XHTTP_MODE_CACHE_FILE"
        _atomic_write() { return 1; }
        [[ "$(xhttp_active_inbound_mode)" == auto ]] || fail "cache write failure blocked a valid mode lookup"
    )
    pass "XHTTP mode cache preserves auto-mode preferences and falls back after invalid cache or failed writes"
}

test_xhttp_mode_cache_does_not_commit_a_concurrent_config_swap() {
    (
        reset_runtime_paths
        write_test_vless_config
        jq() {
            command jq "$@"
            command jq '.inbounds[0].streamSettings.xhttpSettings.mode = "stream-up"' "$CONFIG_FILE" > "${CONFIG_FILE}.new"
            mv "${CONFIG_FILE}.new" "$CONFIG_FILE"
        }
        [[ "$(xhttp_active_inbound_mode)" == packet-up ]] || fail "original parsed mode was not returned"
        [[ ! -e "$XHTTP_MODE_CACHE_FILE" ]] || fail "concurrent replacement was cached under the old fingerprint"
        unset -f jq
        [[ "$(xhttp_active_inbound_mode)" == stream-up ]] || fail "concurrent replacement mode was lost"
    )
    pass "XHTTP mode cache cannot associate a concurrent replacement with the old config hash"
}

test_xhttp_mode_reports_export_failure() {
    (
        reset_runtime_paths
        xhttp_active_inbound_mode() { printf 'auto'; }
        refresh_config_exports() { return 1; }
        if set_xhttp_mode "stream-up" >/dev/null 2>&1; then
            fail "mode manager reported success after exported links failed to refresh"
        fi
        [[ "$(xhttp_mode_value)" == "stream-up" ]] || fail "saved mode was lost after export failure"
    )
    pass "XHTTP mode reports failed exports while preserving the saved preference"
}

test_xhttp_extra_requires_one_complete_object() {
    (
        reset_runtime_paths
        local value link
        local -a invalid=('{}{}' '{} {}' 'null {}' '[] {}' '{} trailing' '[]' 'null' ''
                        '{"xmux":{"maxConnections":NaN}}' '{"values":[Infinity,-Infinity]}' '{"value":1e400}')
        local -a valid=('{}' ' {"xmux":{"maxConnections":3},"note":"brace } and encoded % text"} '
                      '{"values":[null,true,1,0.25,1e3],"note":"NaN Infinity"}')
        for value in "${invalid[@]}"; do
            if xhttp_extra_json_valid "$value"; then
                fail 'custom XHTTP extra accepted incomplete, non-object, or multiple JSON documents'
            fi
        done
        for value in "${valid[@]}"; do
            xhttp_extra_json_valid "$value" || fail 'valid single-object XHTTP extra was rejected'
        done
        (
            command() {
                if [[ "${1:-}" == -v && "${2:-}" == jq ]]; then return 1; fi
                builtin command "$@"
            }
            for value in "${invalid[@]}"; do
                if xhttp_extra_json_valid "$value"; then fail 'Python fallback accepted invalid XHTTP JSON'; fi
            done
            for value in "${valid[@]}"; do
                xhttp_extra_json_valid "$value" || fail 'Python fallback rejected valid XHTTP JSON'
            done
        )
        write_test_vless_config
        G2RAY_XHTTP_EXTRA_JSON='{}{}'
        G2RAY_XHTTP_LINK_TUNING=off
        link=$(generate_link_for_address 20.0.0.1)
        [[ "$link" != *'&extra='* ]] || fail 'invalid multi-document extra escaped into an untuned link'
        G2RAY_XHTTP_LINK_TUNING=on
        link=$(generate_link_for_address 20.0.0.1)
        [[ "$link" == *"extra=$(url_encode_query_value "$XHTTP_TUNED_EXTRA_JSON")"* ]] \
            || fail 'invalid custom extra did not preserve the valid built-in tuning fallback'
        pass 'custom XHTTP extra requires one complete object and preserves valid tuning fallbacks'
    )
}

test_custom_xhttp_extra_json_is_validated() {
    reset_runtime_paths
    G2RAY_XHTTP_LINK_TUNING=off
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    GITHUB_USER="tester"
    printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
    write_test_vless_config

    local link
    link="$(generate_link_for_address "20.0.0.1" "-ip1")"
    [[ "$link" != *"&extra="* ]] \
        || fail "default generated link unexpectedly contains an XHTTP extra parameter: $link"

    G2RAY_XHTTP_EXTRA_JSON='{"xmux":{"hKeepAlivePeriod":15,"hMaxRequestTimes":600}}'
    link="$(generate_link_for_address "20.0.0.1" "-ip1")"
    [[ "$link" == *"hMaxRequestTimes"* || "$link" == *"hMaxRequestTimes%22%3A600"* ]] \
        || fail "valid custom XHTTP extra JSON was not included in generated link: $link"

    G2RAY_XHTTP_EXTRA_JSON='not-json'
    link="$(generate_link_for_address "20.0.0.1" "-ip1")"
    [[ "$link" != *"extra=not-json"* ]] \
        || fail "invalid custom XHTTP extra JSON was exported verbatim"
    [[ "$link" != *"&extra="* ]] \
        || fail "invalid custom XHTTP extra JSON unexpectedly added a default extra parameter: $link"
    pass "custom XHTTP extra JSON is opt-in and validated"
}

test_websocket_fallback_is_advanced_opt_in() {
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    WS_PORT_DOMAIN="behavior-space-8443.app.github.dev"
    GITHUB_USER="tester"
    printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
    write_test_vless_config

    if ws_fallback_enabled; then
        fail "WebSocket fallback is enabled by default"
    fi
    if generate_ws_link_for_address "20.0.0.1" "-ws1" >/dev/null 2>&1; then
        fail "WebSocket fallback link was generated while fallback was disabled"
    fi

    G2RAY_ENABLE_WS_FALLBACK=1
    local link
    link="$(generate_ws_link_for_address "20.0.0.1" "-ws1")"
    [[ "$link" == *"@20.0.0.1:443"* ]] || fail "WS IP fallback does not use public edge port 443: $link"
    [[ "$link" == *"type=ws"* ]] || fail "WS fallback link does not use WebSocket transport: $link"
    [[ "$link" == *"host=behavior-space-8443.app.github.dev"* ]] || fail "WS fallback link does not use WS port domain as Host: $link"
    [[ "$link" == *"sni=behavior-space-8443.app.github.dev"* ]] || fail "WS fallback link does not use WS port domain as SNI: $link"
    [[ "$link" == *"path=%2Fws"* ]] || fail "WS fallback link does not use /ws path: $link"
    [[ "$link" == *"insecure=0&allowInsecure=0"* ]] || fail "WS fallback link is not strict TLS by default: $link"
    pass "WebSocket fallback links are advanced opt-in"
}

test_websocket_fallback_exports_separate_alpn_variants() {
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    WS_PORT_DOMAIN="behavior-space-8443.app.github.dev"
    GITHUB_USER="tester"
    G2RAY_EXPORT_DOMAIN_LINK=0
    WS_MAX_FALLBACK_LINKS=1
    printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
    write_test_vless_config
    local original_usable_fallback_ips
    original_usable_fallback_ips="$(declare -f usable_fallback_ips)"
    usable_fallback_ips() { printf '20.0.0.5\n'; }

    enable_ws_fallback_mode

    local h2 h1 blank links count
    h2="$(generate_ws_link_for_address "20.0.0.5" "-ws-ip1-h2" "h2")"
    h1="$(generate_ws_link_for_address "20.0.0.5" "-ws-ip1-h1" "h1")"
    blank="$(generate_ws_link_for_address "20.0.0.5" "-ws-ip1-auto" "blank")"

    [[ "$h2" == *"alpn=h2"* ]] || fail "WS h2 variant does not force h2 ALPN: $h2"
    [[ "$h2" != *"http%2F1.1"* && "$h2" != *"h2,http"* ]] || fail "WS h2 variant still contains mixed ALPN: $h2"
    [[ "$h1" == *"alpn=http%2F1.1"* ]] || fail "WS h1 variant does not force http/1.1 ALPN: $h1"
    [[ "$h1" != *"alpn=h2"* && "$h1" != *"h2,http"* ]] || fail "WS h1 variant still contains h2 or mixed ALPN: $h1"
    [[ "$blank" != *"alpn="* ]] || fail "WS blank variant should omit ALPN entirely: $blank"

    links="$(generate_ws_links)"
    count=$(printf '%s\n' "$links" | awk 'NF {c++} END {print c+0}')
    [[ "$count" == "3" ]] || fail "one WS route should export h2, h1, and blank ALPN variants, got $count: $links"
    grep -Fq 'z3roozsh' <<< "$links" && fail "test leaked a real username into WS fixture links"
    grep -Fq 'G2rayXCodeLeafy|tester-ws-ip1-h2' <<< "$links" || fail "WS h2 route variant label missing"
    grep -Fq 'G2rayXCodeLeafy|tester-ws-ip1-h1' <<< "$links" || fail "WS h1 route variant label missing"
    grep -Fq 'G2rayXCodeLeafy|tester-ws-ip1-auto' <<< "$links" || fail "WS blank route variant label missing"
    if grep -Fq 'alpn=h2,http' <<< "$links"; then
        fail "WS exports still include the old combined ALPN value"
    fi
    eval "$original_usable_fallback_ips"
    pass "WebSocket fallback exports separate h2, h1, and blank ALPN variants"
}

test_websocket_fallback_persists_from_panel_state() {
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    WS_PORT_DOMAIN="behavior-space-8443.app.github.dev"
    GITHUB_USER="tester"
    printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
    write_test_vless_config

    enable_ws_fallback_mode
    unset G2RAY_ENABLE_WS_FALLBACK
    ws_fallback_enabled || fail "saved panel WS preference did not enable fallback"

    local link
    link="$(generate_ws_link_for_address "20.0.0.1" "-ws1")"
    [[ "$link" == *"type=ws"* ]] || fail "saved panel WS preference did not allow WS link generation: $link"

    disable_ws_fallback_mode
    if ws_fallback_enabled; then
        fail "disabled panel WS preference did not disable fallback"
    fi

    G2RAY_ENABLE_WS_FALLBACK=1
    if ws_fallback_enabled; then
        fail "explicit disabled panel WS preference should override environment default"
    fi
    pass "WebSocket fallback persists from panel state"
}

test_websocket_front_domain_generates_cloudflare_link() {
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    WS_PORT_DOMAIN="behavior-space-8443.app.github.dev"
    GITHUB_USER="tester"
    printf '11111111-2222-3333-4444-555555555555\n' > "$UUID_FILE"
    write_test_vless_config
    local original_usable_fallback_ips
    original_usable_fallback_ips="$(declare -f usable_fallback_ips)"
    usable_fallback_ips() { printf '20.0.0.5\n'; }

    enable_ws_fallback_mode
    set_ws_front_domain "https://WS.Example.COM:443/path" \
        || fail "valid front domain with scheme/port/path was not normalized and saved"
    [[ "$(ws_front_domain_value)" == "ws.example.com" ]] \
        || fail "front domain was not normalized to hostname-only value"

    local front_link first_link
    front_link="$(generate_ws_front_link "h1")"
    [[ "$front_link" == *"@ws.example.com:443"* ]] || fail "front link does not use front domain as address: $front_link"
    [[ "$front_link" == *"sni=ws.example.com"* ]] || fail "front link does not use front domain as SNI: $front_link"
    [[ "$front_link" == *"host=ws.example.com"* ]] || fail "front link does not use front domain as Host: $front_link"
    [[ "$front_link" == *"alpn=http%2F1.1"* ]] || fail "front h1 link does not force http/1.1 ALPN: $front_link"
    [[ "$front_link" == *"type=ws"* ]] || fail "front link is not a WebSocket link: $front_link"

    first_link="$(generate_ws_links | awk 'NF && !seen {print; seen=1} END {exit 0}')"
    [[ "$first_link" == "$(generate_ws_front_link "h2")" ]] || fail "Cloudflare front h2 link is not first WS fallback link"
    [[ "$(generate_ws_links | awk 'NF {c++} END {print c+0}')" == "9" ]] \
        || fail "front + one IP + domain should each export 3 WS ALPN variants"

    if set_ws_front_domain "bad host name"; then
        fail "invalid front domain with spaces was accepted"
    fi
    eval "$original_usable_fallback_ips"
    pass "WebSocket front domain generates a Cloudflare-ready link"
}

test_websocket_fallback_adds_config_and_public_port() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        XRAY_PORT=443
        WS_PORT=8443
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        WS_PORT_DOMAIN="behavior-space-8443.app.github.dev"
        PERFORMANCE_PROFILE=balanced
        G2RAY_ENABLE_WS_FALLBACK=1
        uuidgen() { printf '11111111-2222-3333-4444-555555555555\n'; }
        start_xray() { return 0; }
        wait_for_port() { return 0; }
        refresh_config_exports() { return 0; }
        xray_validate_config_file() { return 0; }
        xhttp_probe_metrics() { printf '200 12 ready\n'; }
        local ports_file="$TMP_ROOT/ws-public-ports.txt"
        : > "$ports_file"
        run_gh() {
            printf '%s\n' "$*" >> "$ports_file"
            return 0
        }
        generate_config >/dev/null
        python -m json.tool "$CONFIG_FILE" >/dev/null || fail "generated WS fallback config is not valid JSON"
        grep -Fq '"tag": "vless-ws"' "$CONFIG_FILE" || fail "WS fallback did not add vless-ws inbound"
        grep -Fq '"network": "ws"' "$CONFIG_FILE" || fail "WS fallback inbound is not WebSocket"
        grep -Fq '"path": "/ws"' "$CONFIG_FILE" || fail "WS fallback inbound does not use /ws path"
        [[ "$(jq -r '.inbounds[] | select(.tag == "vless-ws") | .streamSettings.wsSettings.heartbeatPeriod' "$CONFIG_FILE")" == "30" ]] \
            || fail "WS fallback inbound does not keep idle WebSocket sessions alive"
        grep -Fq '443:public' "$ports_file" || fail "primary XHTTP port was not made public"
        grep -Fq '8443:public' "$ports_file" || fail "WS fallback port was not made public"
    )
    pass "WebSocket fallback config and port exposure are opt-in"
}

test_config_metadata_sanitizes_invalid_max_fallback_links() {
    reset_runtime_paths
    BASE_DIR="$TMP_ROOT"
    CONFIG_META_FILE="$BASE_DIR/configs-meta.json"
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT=443
    MAX_FALLBACK_LINKS="not-a-number"
    GITHUB_USER="tester"

    write_config_metadata 1 "abc123"
    python -m json.tool "$CONFIG_META_FILE" >/dev/null || fail "metadata JSON broke when max fallback links was invalid"
    grep -Fq '"max_fallback_links": 20' "$CONFIG_META_FILE" \
        || fail "invalid max fallback link setting was not sanitized to default 20"
    pass "config metadata sanitizes invalid max fallback link settings"
}

test_bench_json_reports_deterministic_budgets() {
    reset_runtime_paths
    local output bench_file bench_root command_output platform_multiplier
    local contract_config_ms=6000 contract_route_ms=5000 contract_export_ms=30000
    local contract_doctor_ms=12000 contract_recover_ms=12000 contract_log_ms=16000
    platform_multiplier=$(bench_platform_multiplier)
    [[ "$(bench_budget_ms config_path_cache)" == "$((2500 * platform_multiplier))" ]] \
        || fail "default config_path_cache benchmark budget changed"
    [[ "$(bench_budget_ms route_ordering)" == "$((1500 * platform_multiplier))" ]] \
        || fail "default route_ordering benchmark budget changed"
    [[ "$(bench_budget_ms export_generation)" == "$((10000 * platform_multiplier))" ]] \
        || fail "default export_generation benchmark budget changed"
    [[ "$(bench_budget_ms doctor_json)" == "$((6000 * platform_multiplier))" ]] \
        || fail "default doctor_json benchmark budget changed"
    [[ "$(bench_budget_ms recover_json_contract)" == "$((6000 * platform_multiplier))" ]] \
        || fail "default recover_json_contract benchmark budget changed"
    [[ "$(bench_budget_ms log_event_cost)" == "$((8000 * platform_multiplier))" ]] \
        || fail "default log_event_cost benchmark budget changed"
    [[ "$(G2RAY_BENCH_BUDGET_CONFIG_PATH_MS=not-a-number bench_budget_ms config_path_cache)" == "$((2500 * platform_multiplier))" ]] \
        || fail "invalid benchmark budget did not fall back to the default"
    [[ "$(G2RAY_BENCH_WINDOWS_MULTIPLIER=invalid bench_platform_multiplier)" == "$platform_multiplier" ]] \
        || fail "invalid Windows benchmark multiplier did not fall back safely"
    if ! output="$(
        G2RAY_BENCH_MOCK=1 \
        G2RAY_BENCH_BUDGET_CONFIG_PATH_MS="$contract_config_ms" \
        G2RAY_BENCH_BUDGET_ROUTE_ORDERING_MS="$contract_route_ms" \
        G2RAY_BENCH_BUDGET_EXPORT_MS="$contract_export_ms" \
        G2RAY_BENCH_BUDGET_DOCTOR_MS="$contract_doctor_ms" \
        G2RAY_BENCH_BUDGET_RECOVER_JSON_MS="$contract_recover_ms" \
        G2RAY_BENCH_BUDGET_LOG_EVENT_MS="$contract_log_ms" \
        bench_json --mock
    )"; then
        printf '%s\n' "$output"
        fail "bench_json returned nonzero under mock budgets"
    fi
    bench_file="$TMP_ROOT/bench.json"
    printf '%s\n' "$output" > "$bench_file"
    python - "$bench_file" <<'PY' || fail "bench_json did not return valid budget JSON"
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
assert data["ok"] is True
assert data["mocked"] is True
assert data["budgets_ok"] is True
names = {case["name"] for case in data["cases"]}
required = {"config_path_cache", "route_ordering", "export_generation", "doctor_json", "recover_json_contract", "log_event_cost"}
missing = required - names
if missing:
    raise AssertionError(f"missing benchmark cases: {sorted(missing)}")
for case in data["cases"]:
    assert isinstance(case["elapsed_ms"], int)
    assert case["elapsed_ms"] <= case["budget_ms"], case
PY
    bench_root="$TMP_ROOT/bench-command-root"
    command_output="$TMP_ROOT/bench-command.json"
    mkdir -p "$bench_root"
    cp "$SCRIPT" "$bench_root/g2ray.sh"
    if ! (
        cd "$bench_root" && \
        env -u G2RAY_SOURCE_ONLY \
            G2RAY_BENCH_BUDGET_CONFIG_PATH_MS="$contract_config_ms" \
            G2RAY_BENCH_BUDGET_ROUTE_ORDERING_MS="$contract_route_ms" \
            G2RAY_BENCH_BUDGET_EXPORT_MS="$contract_export_ms" \
            G2RAY_BENCH_BUDGET_DOCTOR_MS="$contract_doctor_ms" \
            G2RAY_BENCH_BUDGET_RECOVER_JSON_MS="$contract_recover_ms" \
            G2RAY_BENCH_BUDGET_LOG_EVENT_MS="$contract_log_ms" \
            bash ./g2ray.sh bench --json --mock > "$command_output"
    ); then
        cat "$command_output" 2>/dev/null || true
        fail "bench --json --mock command returned nonzero under mock budgets"
    fi
    [[ ! -e "$bench_root/data" && ! -e "$bench_root/logs" ]] \
        || fail "bench --json --mock created runtime dirs in the command working tree"
    python - "$command_output" <<'PY' || fail "bench --json --mock command did not return valid passing JSON"
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
assert data["ok"] is True
assert data["budgets_ok"] is True
PY
    pass "bench --json reports deterministic performance budgets"
}

test_low_overhead_mode_suppresses_info_logs() {
    reset_runtime_paths
    enable_low_overhead_mode
    log_event INFO "low_overhead_info_should_skip"
    log_event WARN "low_overhead_warn_should_stay"
    ! grep -Fq "low_overhead_info_should_skip" "$LOG_FILE" || fail "low overhead mode did not suppress INFO logs"
    grep -Fq "low_overhead_warn_should_stay" "$LOG_FILE" || fail "low overhead mode suppressed WARN logs"
    disable_low_overhead_mode
    pass "low-overhead mode suppresses nonessential INFO logs only"
}

test_low_overhead_env_can_be_overridden_by_toggle() {
    reset_runtime_paths
    G2RAY_LOW_OVERHEAD=1
    low_overhead_enabled || fail "low overhead env flag did not enable mode"
    disable_low_overhead_mode
    if low_overhead_enabled; then
        fail "low overhead disable toggle did not override the env flag"
    fi
    enable_low_overhead_mode
    low_overhead_enabled || fail "low overhead enable toggle did not re-enable mode"
    unset G2RAY_LOW_OVERHEAD
    disable_low_overhead_mode
    pass "low-overhead mode can be explicitly toggled even when env default is enabled"
}

test_anti_sleep_preference_persists_across_headless_reconciliation() {
    (
        reset_runtime_paths
        local tmux_state="$TMP_ROOT/mock-tmux-state" new_session_calls="$TMP_ROOT/mock-tmux-starts"
        : > "$new_session_calls"
        tmux() {
            case "${1:-}" in
                has-session) [[ -f "$tmux_state" ]] ;;
                new-session)
                    printf 'start\n' >> "$new_session_calls"
                    : > "$tmux_state"
                    ;;
                kill-session) rm -f "$tmux_state" ;;
                *) return 1 ;;
            esac
        }

        anti_sleep_enabled || fail "anti-sleep should default to enabled"
        sync_anti_sleep_runtime
        [[ -f "$tmux_state" ]] || fail "headless reconciliation did not start enabled anti-sleep"

        disable_anti_sleep
        anti_sleep_enabled && fail "anti-sleep disable preference was not persisted"
        [[ ! -f "$tmux_state" ]] || fail "disabling anti-sleep left its runtime alive"

        sync_anti_sleep_runtime
        [[ ! -f "$tmux_state" ]] || fail "headless reconciliation ignored the disabled preference"
        [[ "$(wc -l < "$new_session_calls")" -eq 1 ]] \
            || fail "disabled anti-sleep was restarted during reconciliation"

        enable_anti_sleep
        anti_sleep_enabled || fail "anti-sleep enable preference was not persisted"
        [[ -f "$tmux_state" ]] || fail "enabling anti-sleep did not start its runtime"
    )
    pass "anti-sleep preference survives headless and interactive runtime reconciliation"
}

test_low_overhead_keeps_important_state_logs() {
    reset_runtime_paths
    enable_low_overhead_mode
    log_event INFO "health chatty_probe_should_skip"
    log_event INFO "runtime_ready reason=test engine=started xhttp_probe=200"
    ! grep -Fq "chatty_probe_should_skip" "$LOG_FILE" || fail "low overhead did not suppress chatty health INFO"
    grep -Fq "runtime_ready reason=test" "$LOG_FILE" || fail "low overhead suppressed important runtime_ready INFO"
    disable_low_overhead_mode
    pass "low-overhead mode preserves important state-transition INFO logs"
}

test_latency_focus_mode_suppresses_noncritical_info_but_preserves_warnings() {
    reset_runtime_paths
    enable_latency_focus_mode
    log_event INFO "latency_focus_info_should_skip"
    log_event WARN "latency_focus_warn_should_stay"
    log_event ERROR "latency_focus_error_should_stay"
    ! grep -Fq "latency_focus_info_should_skip" "$LOG_FILE" || fail "latency focus mode did not suppress INFO logs"
    grep -Fq "latency_focus_warn_should_stay" "$LOG_FILE" || fail "latency focus mode suppressed WARN logs"
    grep -Fq "latency_focus_error_should_stay" "$LOG_FILE" || fail "latency focus mode suppressed ERROR logs"
    disable_latency_focus_mode
    pass "latency-focus mode suppresses noncritical INFO while preserving warnings and errors"
}

test_latency_focus_env_can_be_overridden_by_toggle() {
    reset_runtime_paths
    G2RAY_LATENCY_FOCUS=1
    latency_focus_enabled || fail "latency focus env flag did not enable mode"
    disable_latency_focus_mode
    if latency_focus_enabled; then
        fail "latency focus disable toggle did not override the env flag"
    fi
    enable_latency_focus_mode
    latency_focus_enabled || fail "latency focus enable toggle did not re-enable mode"
    unset G2RAY_LATENCY_FOCUS
    disable_latency_focus_mode
    pass "latency-focus mode can be explicitly toggled even when env default is enabled"
}

test_panel_modes_apply_real_performance_profiles() {
    reset_runtime_paths
    local calls_file="$TMP_ROOT/profile-reapply-calls.txt"
    : > "$calls_file"
    write_test_vless_config
    generate_config() {
        printf 'profile=%s effective=%s preserve=%s\n' \
            "$PERFORMANCE_PROFILE" "$(effective_performance_profile)" "${G2RAY_PRESERVE_UUID:-0}" >> "$calls_file"
    }

    set_performance_profile max_throughput
    toggle_low_overhead_mode >/dev/null
    low_overhead_enabled || fail "low-overhead toggle did not enable mode"
    [[ "$PERFORMANCE_PROFILE" == "low_overhead" ]] || fail "low-overhead mode did not select the low_overhead profile"
    grep -Fq 'effective=low_overhead preserve=1' "$calls_file" \
        || fail "low-overhead toggle did not reapply the config with preserved UUID"

    : > "$calls_file"
    toggle_latency_focus_mode >/dev/null
    latency_focus_enabled || fail "latency-focus toggle did not enable mode"
    if low_overhead_enabled; then
        fail "latency-focus mode should disable the conflicting low-overhead mode"
    fi
    [[ "$PERFORMANCE_PROFILE" == "low_latency" ]] || fail "latency-focus mode did not select the low_latency profile"
    grep -Fq 'effective=low_latency preserve=1' "$calls_file" \
        || fail "latency-focus toggle did not reapply the config with preserved UUID"

    : > "$calls_file"
    toggle_latency_focus_mode >/dev/null || true
    latency_focus_enabled && fail "latency-focus toggle did not disable mode"
    [[ "$PERFORMANCE_PROFILE" == "max_throughput" ]] || fail "latency-focus disable did not restore previous profile"
    grep -Fq 'effective=max_throughput preserve=1' "$calls_file" \
        || fail "latency-focus disable did not reapply the restored profile with preserved UUID"

    pass "panel modes apply real performance profiles and preserve UUID"
}

test_switching_performance_modes_reapplies_once() {
    reset_runtime_paths
    local calls_file="$TMP_ROOT/profile-single-reapply-calls.txt"
    : > "$calls_file"
    write_test_vless_config
    generate_config() {
        printf 'profile=%s effective=%s preserve=%s\n' \
            "$PERFORMANCE_PROFILE" "$(effective_performance_profile)" "${G2RAY_PRESERVE_UUID:-0}" >> "$calls_file"
    }

    set_performance_profile max_throughput
    toggle_low_overhead_mode >/dev/null
    [[ "$(wc -l < "$calls_file")" -eq 1 ]] \
        || fail "enabling low-overhead mode reapplied config more than once"

    : > "$calls_file"
    toggle_latency_focus_mode >/dev/null
    [[ "$(wc -l < "$calls_file")" -eq 1 ]] \
        || fail "switching from low-overhead to latency focus reapplied config more than once"
    pass "switching mutually exclusive performance modes reapplies once"
}

test_xray_configtest_logs_prune_old_files() {
    reset_runtime_paths
    XRAY_CONFIGTEST_LOG_MAX_AGE_MIN=60
    local old_file="$LOG_DIR/xray-configtest.old.log" fresh_file="$LOG_DIR/xray-configtest.fresh.log"
    printf 'old\n' > "$old_file"
    printf 'fresh\n' > "$fresh_file"
    touch -d '2 hours ago' "$old_file"
    prune_xray_configtest_logs
    [[ ! -e "$old_file" ]] || fail "stale Xray validation log was not pruned"
    [[ -e "$fresh_file" ]] || fail "fresh Xray validation log was pruned"
    pass "stale Xray validation logs are pruned without deleting fresh diagnostics"
}

test_performance_profile_settings_are_available() {
    reset_runtime_paths
    local balanced low_latency low_overhead streaming max_throughput
    balanced="$(performance_profile_settings balanced)"
    low_latency="$(performance_profile_settings low_latency)"
    low_overhead="$(performance_profile_settings low_overhead)"
    streaming="$(performance_profile_settings streaming)"
    max_throughput="$(performance_profile_settings max_throughput)"
    ! grep -Fq 'maxConcurrentUploads=' <<< "$balanced" || fail "balanced profile still claims obsolete XHTTP concurrency tuning"
    ! grep -Fq 'maxUploadSize=' <<< "$balanced" || fail "balanced profile still claims obsolete XHTTP upload tuning"
    grep -Fq 'sniffQuic=false' <<< "$balanced" || fail "balanced profile should disable QUIC sniffing"
    grep -Fq 'handshake=4' <<< "$balanced" || fail "balanced profile should retain Xray's safe handshake allowance"
    grep -Fq 'connIdle=900' <<< "$low_latency" || fail "low_latency profile should retain app sessions across long foreground/background switches"
    grep -Fq 'handshake=4' <<< "$low_latency" || fail "low_latency profile should not reject lossy mobile handshakes early"
    grep -Fq 'sniffQuic=false' <<< "$low_latency" || fail "low_latency profile should disable QUIC sniffing"
    grep -Fq 'sniffQuic=false' <<< "$streaming" || fail "streaming profile should disable QUIC sniffing"
    grep -Fq 'sniffQuic=false' <<< "$low_overhead" || fail "low_overhead profile should disable QUIC sniffing"
    local mobile; mobile="$(performance_profile_settings unstable_mobile)"
    grep -Fq 'handshake=6' <<< "$mobile" || fail "unstable_mobile profile should tolerate delayed mobile handshakes"
    grep -Fq 'handshake=4' <<< "$low_overhead" || fail "low_overhead profile should not trade reliability for a shorter handshake window"
    grep -Fq 'connIdle=900' <<< "$mobile" || fail "unstable_mobile profile should retain app sessions across long foreground/background switches"
    grep -Fq 'connIdle=900' <<< "$balanced" || fail "balanced profile should retain app sessions across long foreground/background switches"
    grep -Fq 'name=max_throughput' <<< "$max_throughput" || fail "max_throughput profile is not selectable"
    ! grep -Fq 'maxConcurrentUploads=' <<< "$max_throughput" || fail "max_throughput profile still claims obsolete XHTTP concurrency tuning"
    grep -Fq 'bufferSize=1024' <<< "$max_throughput" || fail "max_throughput profile does not use the bounded throughput buffer"
    grep -Fq 'sniffQuic=false' <<< "$max_throughput" || fail "max_throughput profile should disable QUIC sniffing"
    pass "performance profile settings are explicit and inspectable"
}

test_route_settling_history_records_summary() {
    reset_runtime_paths
    record_route_settling_metric "recover_now" "timeout" "404" "25" "60" "20"
    record_route_settling_metric "recover_now_repair" "ready" "200" "30" "9" "4"
    summary="$(route_settling_history_summary)"
    grep -Fq "samples=2" <<< "$summary" || fail "route settling summary missing sample count"
    grep -Fq "ready=1" <<< "$summary" || fail "route settling summary missing ready count"
    grep -Fq "timeout=1" <<< "$summary" || fail "route settling summary missing timeout count"
    pass "route settling history records timing and outcomes"
}

test_doctor_json_reports_probe_state() {
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT=443
    touch "$CONFIG_FILE"
    xray_running() { return 0; }
    is_port_open() { return 0; }
    xhttp_probe_metrics() {
        if [[ "${1:-}" == "local" ]]; then
            printf '200 1 ready\n'
        else
            printf '404 31 route_settling_404\n'
        fi
    }
    background_supervisor_status() { printf 'pid=1 running=heartbeat version=ok token=present heartbeat_age=1s\n'; }
    output="$(print_doctor_json)"
    python -m json.tool <<< "$output" >/dev/null || fail "doctor json became invalid when probe reason is present"
    grep -Fq '"codespace": "behavior-space"' <<< "$output" || fail "doctor json missing codespace"
    grep -Fq '"edge_probe": {"http_status": 404' <<< "$output" || fail "doctor json missing edge probe"
    grep -Fq '"next_action_code": "wait_route_or_recover"' <<< "$output" || fail "doctor json missing actionable next_action_code"
    grep -Fq '"structured_log_file":' <<< "$output" || fail "doctor json missing structured log path"
    grep -Fq '"diagnostic_log_file":' <<< "$output" || fail "doctor json missing diagnostic log path"
    grep -Fq '"latency_focus": false' <<< "$output" || fail "doctor json missing latency focus state"
    pass "doctor json reports machine-readable route state"
}

test_doctor_json_sanitizes_invalid_port() {
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT="443abc"
    xray_running() { return 1; }
    is_port_open() { return 1; }
    xhttp_probe_metrics() { printf '0 0\n'; }
    background_supervisor_status() { printf 'pid=none running=false version=missing token=missing heartbeat_age=unknown\n'; }
    output="$(print_doctor_json)"
    python -m json.tool <<< "$output" >/dev/null || fail "doctor json is invalid with nonnumeric XRAY_PORT"
    grep -Fq '"port": 443' <<< "$output" || fail "doctor json did not sanitize invalid XRAY_PORT to 443"
    pass "doctor json remains valid with invalid port input"
}

test_route_wait_requires_stable_usable_probes() {
    reset_runtime_paths
    ROUTE_READY_STABLE_SLEEP_SEC=0
    local probes=0
    xhttp_probe_metrics() {
        probes=$((probes + 1))
        printf '200 7\n'
    }
    wait_for_xhttp_route_ready "behavior_stable" 5 >/dev/null || fail "route wait did not accept stable usable probes"
    awk -F '\t' '$2 == "behavior_stable" && $3 == "ready" && $4 == "200" && $5 == "7" && $7 == "2" { found = 1 } END { exit !found }' "$ROUTE_SETTLING_HISTORY_FILE" \
        || fail "route wait did not require and record two stable usable probes"
    pass "route wait requires stable usable probes"
}

test_route_wait_rejects_transient_single_success() {
    (
        reset_runtime_paths
        local probes_file="$TMP_ROOT/route-wait-flap-count.txt"
        local clock_file="$TMP_ROOT/route-wait-flap-clock.txt"
        printf '0\n' > "$probes_file"
        printf '0\n' > "$clock_file"
        xhttp_probe_metrics() {
            local probes
            probes=$(cat "$probes_file")
            probes=$((probes + 1))
            printf '%s\n' "$probes" > "$probes_file"
            case "$probes" in
                1) printf '200 11\n' ;;
                2) printf '404 12\n' ;;
                3) printf '200 13\n' ;;
                *) printf '200 14\n' ;;
            esac
        }
        date() {
            if [[ "${1:-}" == "+%s" ]]; then
                local calls
                calls=$(cat "$clock_file")
                calls=$((calls + 1))
                printf '%s\n' "$calls" > "$clock_file"
                if (( calls >= 4 )); then
                    printf '10\n'
                else
                    printf '0\n'
                fi
            else
                command date "$@"
            fi
        }
        sleep() { :; }
        ensure_codespace_port_public() { return 0; }
        wait_for_xhttp_route_ready "behavior_flap" 10 >/dev/null \
            || fail "route wait did not confirm a recovery that began at the timeout boundary"
        awk -F '\t' '$2 == "behavior_flap" && $3 == "ready" && $4 == "200" && $7 == "4" { found = 1 } END { exit !found }' "$ROUTE_SETTLING_HISTORY_FILE" \
            || fail "route wait accepted a transient single 200 instead of waiting for stability"
    )
    pass "route wait rejects transient single success"
}

test_route_wait_reasserts_port_once_after_persistent_404() {
    (
        reset_runtime_paths
        ROUTE_READY_STABLE_SLEEP_SEC=0
        local probes_file="$TMP_ROOT/route-wait-reassert-count.txt" repairs=0
        printf '0\n' > "$probes_file"
        xhttp_probe_metrics() {
            local probes
            probes=$(cat "$probes_file")
            probes=$((probes + 1))
            printf '%s\n' "$probes" > "$probes_file"
            if (( probes <= 5 )); then
                printf '404 10 route_settling_404\n'
            else
                printf '200 11 ready\n'
            fi
        }
        sleep() { :; }
        ensure_codespace_port_public() { return 0; }
        repair_codespace_port_route() {
            repairs=$((repairs + 1))
            return 0
        }
        mark_route_repair_attempt_if_allowed() { return 0; }

        wait_for_xhttp_route_ready "behavior_reassert" 10 >/dev/null \
            || fail "route wait did not recover after a bounded port reassert"
        [[ "$repairs" -eq 1 ]] \
            || fail "route wait should make one cooldown-gated repair, got $repairs"
        grep -Fq 'runtime_ready reason=behavior_reassert route_wait_repair attempt=5' "$LOG_FILE" \
            || fail "route wait did not log the bounded repair"
    )
    pass "route wait reasserts port once after persistent 404"
}

test_route_wait_does_not_repair_a_recovering_route() {
    (
        reset_runtime_paths
        ROUTE_READY_STABLE_SLEEP_SEC=0
        local probes_file="$TMP_ROOT/route-wait-recovered-count.txt" repairs=0
        printf '0\n' > "$probes_file"
        xhttp_probe_metrics() {
            local probes
            probes=$(cat "$probes_file")
            probes=$((probes + 1))
            printf '%s\n' "$probes" > "$probes_file"
            if (( probes < 5 )); then
                printf '404 10 route_settling_404\n'
            else
                printf '200 11 ready\n'
            fi
        }
        sleep() { :; }
        ensure_codespace_port_public() { return 0; }
        mark_route_repair_attempt_if_allowed() { return 0; }
        repair_codespace_port_route() { repairs=$((repairs + 1)); }
        wait_for_xhttp_route_ready "behavior_recovered" 60 >/dev/null \
            || fail "route wait rejected a naturally recovered route"
        [[ "$repairs" -eq 0 ]] || fail "route wait toggled a healthy route private/public"
    )
    pass "route wait confirms natural recovery without toggling a healthy port"
}

test_recover_now_success_clears_nonfatal_port_public_failure() {
    (
        reset_runtime_paths
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        xray_listener_ready() { return 0; }
        ensure_codespace_port_public() { return 1; }
        wait_for_xhttp_route_ready() { return 0; }
        xhttp_probe_metrics() { printf '200 5\n'; }
        refresh_route_candidate_health() { return 0; }
        refresh_config_exports() { return 0; }
        log_diagnostic_snapshot() { return 0; }
        reset_route_bad_count() { return 0; }
        reset_edge_bad_count() { return 0; }
        output="$(recover_now --no-prompt 2>&1)"
        rc=$?
        [[ "$rc" -eq 0 ]] || fail "recover_now returned $rc despite route-ready recovery; output: $output"
        grep -Fq "Soft recover complete" <<< "$output" || fail "recover_now success output missing"
    )
    pass "recover now returns success when route recovers despite nonfatal port-public failure"
}

test_route_repair_attempts_are_cooldown_limited() {
    reset_runtime_paths
    ROUTE_REPAIR_COOLDOWN_SEC=300
    local calls_file="$TMP_ROOT/route-repair-calls.txt"
    : > "$calls_file"
    route_repair_cooldown_active && fail "route repair cooldown should be inactive before first attempt"
    mark_route_repair_attempt
    route_repair_cooldown_active || fail "route repair cooldown should be active immediately after marking"
    if mark_route_repair_attempt_if_allowed; then
        fail "mark_route_repair_attempt_if_allowed allowed a second repair during cooldown"
    fi
    printf 'blocked\n' >> "$calls_file"
    # Simulate a stale mark and verify another attempt can be recorded.
    printf '%s\n' "$(( $(date +%s) - 600 ))" > "$ROUTE_REPAIR_STAMP_FILE"
    mark_route_repair_attempt_if_allowed || fail "route repair mark failed after cooldown expired"
    pass "route repair attempts are cooldown limited"
}

test_recover_now_stops_after_engine_start_failure() {
    (
        reset_runtime_paths
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        xray_listener_ready() { return 1; }
        start_xray() { return 1; }
        wait_for_port() { return 1; }
        ensure_codespace_port_public() { fail "recover_now exposed tunnel after engine startup failure"; }
        wait_for_xhttp_route_ready() { fail "recover_now waited for route after engine startup failure"; }
        repair_codespace_port_route() { fail "recover_now repaired route after engine startup failure"; }
        refresh_route_candidate_health() { return 0; }
        refresh_config_exports() { return 0; }
        log_diagnostic_snapshot() { return 0; }
        set +e
        output="$(recover_now --no-prompt 2>&1)"
        rc=$?
        set -e
        [[ "$rc" -ne 0 ]] || fail "recover_now returned success after engine startup failure"
        grep -Fq "Engine is unavailable" <<< "$output" \
            || fail "recover_now did not explain that engine failure blocks route recovery"
    )
    pass "recover now stops after engine startup failure"
}

test_recover_now_json_reports_ready_contract() {
    (
        reset_runtime_paths
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        xray_listener_ready() { return 0; }
        ensure_codespace_port_public() { return 0; }
        wait_for_xhttp_route_ready() { return 0; }
        xhttp_probe_metrics() { printf '200 7 ready\n'; }
        refresh_route_candidate_health() { return 0; }
        refresh_config_exports() { return 0; }
        log_diagnostic_snapshot() { return 0; }
        output="$(recover_now_json)"
        rc=$?
        [[ "$rc" -eq 0 ]] || fail "ready recover_now_json returned $rc"
        python -m json.tool <<< "$output" >/dev/null || fail "recover_now_json returned invalid JSON"
        grep -Fq '"ok": true' <<< "$output" || fail "recover_now_json did not report ok=true"
        grep -Fq '"status": "ready"' <<< "$output" || fail "recover_now_json did not report ready status"
        grep -Fq '"route_ready": true' <<< "$output" || fail "recover_now_json did not report route_ready=true"
        grep -Fq '"edge_probe": {"http_status": 200' <<< "$output" || fail "recover_now_json missing edge probe status"
        grep -Fq '"next_action_code": "retry_vless_config"' <<< "$output" || fail "recover_now_json missing ready next_action_code"
    )
    pass "recover now json reports ready contract"
}

test_recover_now_json_reports_settling_contract() {
    (
        reset_runtime_paths
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        xray_listener_ready() { return 0; }
        ensure_codespace_port_public() { return 0; }
        wait_for_xhttp_route_ready() { return 1; }
        repair_codespace_port_route() { return 0; }
        xhttp_probe_metrics() { printf '404 33 route_settling_404\n'; }
        refresh_route_candidate_health() { return 0; }
        refresh_config_exports() { return 0; }
        log_diagnostic_snapshot() { return 0; }
        if output="$(recover_now_json)"; then
            fail "settling recover_now_json returned success"
        else
            rc=$?
        fi
        [[ "$rc" -ne 0 ]] || fail "settling recover_now_json returned zero"
        python -m json.tool <<< "$output" >/dev/null || fail "settling recover_now_json returned invalid JSON"
        grep -Fq '"ok": false' <<< "$output" || fail "recover_now_json did not report ok=false while settling"
        grep -Fq '"status": "settling"' <<< "$output" || fail "recover_now_json did not report settling status"
        grep -Fq '"route_ready": false' <<< "$output" || fail "recover_now_json did not report route_ready=false"
        grep -Fq '"next_action_code": "wait_route_or_recover"' <<< "$output" || fail "recover_now_json missing settling next_action_code"
        grep -Fq '"next_action": "Wait for the Codespaces route to settle, or run Recover Now if it stays stuck."' <<< "$output" \
            || fail "recover_now_json missing settling next action"
    )
    pass "recover now json reports settling contract"
}

test_recover_now_json_treats_followup_ready_probe_as_success() {
    (
        reset_runtime_paths
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        recover_now() { return 1; }
        xhttp_probe_metrics() { printf '200 9 ready\n'; }
        xray_running() { return 0; }
        is_port_open() { return 0; }
        output="$(recover_now_json)"
        rc=$?
        [[ "$rc" -eq 0 ]] || fail "ready follow-up probe should make recover_now_json exit zero, got $rc"
        python -m json.tool <<< "$output" >/dev/null || fail "follow-up ready recover_now_json returned invalid JSON"
        grep -Fq '"ok": true' <<< "$output" || fail "follow-up ready recover_now_json did not report ok=true"
        grep -Fq '"status": "ready"' <<< "$output" || fail "follow-up ready recover_now_json did not report ready"
        grep -Fq '"exit_code": 0' <<< "$output" || fail "follow-up ready recover_now_json did not expose final exit_code=0"
        grep -Fq '"recover_exit_code": 1' <<< "$output" || fail "follow-up ready recover_now_json did not preserve recover_exit_code"
        grep -Fq '"next_action_code": "retry_vless_config"' <<< "$output" || fail "follow-up ready recover_now_json missing next_action_code"
    )
    pass "recover now json treats follow-up ready probe as success"
}

test_headless_start_prepares_listener_without_waiting_for_edge() {
    (
        reset_runtime_paths
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        printf '{}' > "$CONFIG_FILE"
        local publish_calls=0
        record_resume_gap() { return 0; }
        _detect_codespace_name() { printf '%s\n' "behavior-space"; }
        refresh_port_domains() { PORT_DOMAIN="behavior-space-443.app.github.dev"; }
        xray_listener_ready() { return 0; }
        force_public_runtime_ports() {
            publish_calls=$((publish_calls + 1))
            return 0
        }
        wait_for_xhttp_route_ready() { fail "headless listener preparation must not wait synchronously for the edge"; }

        prepare_headless_runtime "silent_start" >/dev/null \
            || fail "headless startup did not accept a locally ready listener"
        [[ "$publish_calls" -eq 1 ]] \
            || fail "headless startup did not publish runtime ports exactly once"
        grep -Fq 'headless_start reason=silent_start listener_ready action=continue_async' "$LOG_FILE" \
            || fail "headless startup did not record asynchronous route settling"
    )
    pass "headless startup prepares listener without blocking on the edge"
}

test_generate_config_feedback_preserves_failure_status() {
    (
        reset_runtime_paths
        generate_config() { return 7; }
        local rc=0
        generate_config_with_feedback >/dev/null 2>&1 || rc=$?
        [[ "$rc" -eq 7 ]] || fail "config generation failure was reported as success"
        generate_config() { return 0; }
        generate_config_with_feedback >/dev/null 2>&1 || fail "successful config generation was rejected"
    )
    pass "interactive config generation preserves its real exit status"
}

test_headless_start_command_preserves_failures() {
    (
        reset_runtime_paths
        local calls_file="$TMP_ROOT/headless-start-command-calls.txt" status
        : > "$calls_file"
        ensure_runtime_ready() {
            printf 'runtime\n' >> "$calls_file"
            return 7
        }
        start_background_tasks() {
            printf 'supervisor\n' >> "$calls_file"
            return 0
        }
        log_event() { return 0; }

        if start_runtime_headless_command; then
            status=0
        else
            status=$?
        fi
        [[ "$status" -eq 7 ]] \
            || fail "headless start masked runtime failure with status $status"
        [[ "$(tr '\n' ' ' < "$calls_file")" == "runtime supervisor " ]] \
            || fail "headless start did not leave the supervisor available to repair a runtime failure"

        ensure_runtime_ready() { return 0; }
        start_background_tasks() { return 9; }
        if start_runtime_headless_command; then
            status=0
        else
            status=$?
        fi
        [[ "$status" -eq 9 ]] \
            || fail "headless start masked supervisor failure with status $status"
    )
    pass "headless start command preserves runtime and supervisor failures"
}

test_headless_route_settle_records_background_ready_state() {
    (
        reset_runtime_paths
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        printf '{}' > "$CONFIG_FILE"
        xray_listener_ready() { return 0; }
        force_public_runtime_ports() { return 0; }
        wait_for_xhttp_route_ready() { return 0; }
        xhttp_probe_metrics() { printf '200 9 ready\n'; }
        reset_route_bad_count() { return 0; }
        reset_edge_bad_count() { return 0; }

        run_headless_route_settle "silent_start" >/dev/null \
            || fail "background route settle did not report a ready route"
        grep -Fq '"status": "ready"' "$BOOT_STATUS_FILE" \
            || fail "background route settle did not persist ready boot status"
        grep -Fq 'route_settle reason=silent_start ready' "$LOG_FILE" \
            || fail "background route settle did not log its final ready state"
    )
    pass "headless route settle records background ready state"
}

test_diagnostic_snapshot_writes_readable_history() {
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT=443
    xray_running() { return 0; }
    is_port_open() { return 0; }
    xhttp_probe_metrics() {
        if [[ "${1:-}" == "local" ]]; then
            printf '200 1\n'
        else
            printf '404 31\n'
        fi
    }
    background_supervisor_status() { printf 'pid=1 running=heartbeat version=ok token=present heartbeat_age=1s\n'; }
    log_diagnostic_snapshot "behavior_test"
    grep -Fq 'Diagnostic Snapshot' "$DIAGNOSTIC_LOG_FILE" || fail "diagnostic log missing snapshot header"
    grep -Fq 'reason: behavior_test' "$DIAGNOSTIC_LOG_FILE" || fail "diagnostic log missing snapshot reason"
    grep -Fq 'edge_xhttp_options: HTTP 404' "$DIAGNOSTIC_LOG_FILE" || fail "diagnostic log missing edge probe"
    grep -Fq 'recent_events:' "$DIAGNOSTIC_LOG_FILE" || fail "diagnostic log missing recent events section"
    pass "diagnostic snapshots persist readable history"
}

test_diagnostic_log_rotation_keeps_readable_history() {
    reset_runtime_paths
    LOG_MAX_BYTES=64
    LOG_ROTATE_KEEP=2
    printf 'old diagnostic line that is intentionally longer than the tiny rotation threshold\n' > "$DIAGNOSTIC_LOG_FILE"
    rotate_log_file "$DIAGNOSTIC_LOG_FILE"
    printf 'new diagnostic line\n' > "$DIAGNOSTIC_LOG_FILE"
    [[ -s "$DIAGNOSTIC_LOG_FILE.1" ]] || fail "diagnostic rotation did not keep rotated history"
    grep -Fq 'old diagnostic line' "$DIAGNOSTIC_LOG_FILE.1" \
        || fail "diagnostic rotation did not preserve readable prior content"
    pass "diagnostic log rotation keeps readable bounded history"
}

test_structured_log_jsonl_is_parseable_with_special_chars() {
    reset_runtime_paths
    log_event INFO $'route_unusable detail="bad route" path=C:\\tmp\\x\nnext-line'
    log_event WARN "fallback_route_unusable ip=20.0.0.1 xhttp_probe=404"
    python - "$STRUCTURED_LOG_FILE" <<'PY' || fail "structured event log contains invalid JSONL"
import json
import sys
path = sys.argv[1]
with open(path, encoding="utf-8") as fh:
    rows = [json.loads(line) for line in fh if line.strip()]
assert len(rows) == 2
for row in rows:
    assert {"ts", "level", "event", "message"} <= set(row)
    assert "\n" not in row["message"]
assert rows[0]["event"] == "route_unusable"
assert rows[1]["event"] == "fallback_route_unusable"
PY
    pass "structured log JSONL stays parseable with special characters"
}

test_support_bundle_redacts_sensitive_material() {
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT=443
    local uuid="11111111-2222-3333-4444-555555555555"
    local bearer="TEST_BEARER_SECRET_REDACT_ME"
    local github_token="TEST_GITHUB_TOKEN_REDACT_ME"
    local structured_token="TEST_STRUCTURED_TOKEN_REDACT_ME"
    local metadata_token="TEST_METADATA_GITHUB_TOKEN_REDACT_ME"
    local vless="vless://${uuid}@behavior-space-443.app.github.dev:443?encryption=none#label"
    printf '%s\nAuthorization: Bearer %s\nauthorization: Bearer %s\nGITHUB_TOKEN=%s\n"WAKE_SECRET":"%s"\n' \
        "$vless" "$bearer" "$bearer" "$github_token" "$bearer" > "$LOG_FILE"
    printf '{"ts":"2026-05-30T00:00:00Z","level":"INFO","event":"test","message":"%s","authorization":"Bearer %s","WAKE_SECRET":"%s","token":"%s"}\n' \
        "$vless" "$bearer" "$bearer" "$structured_token" > "$STRUCTURED_LOG_FILE"
    printf 'wake_secret=%s\n%s\n"authorization":"Bearer %s"\n' "$bearer" "$uuid" "$bearer" > "$DIAGNOSTIC_LOG_FILE"
    printf 'worker_url=https://worker.example/wake\nwake_secret=%s\nGITHUB_TOKEN=%s\n' "$bearer" "$metadata_token" > "$WAKER_METADATA_FILE"
    printf '2026-05-30T00:00:00Z\t20.0.0.1\t200\t10\ttrue\n' > "$ROUTE_HEALTH_FILE"
    printf '20.0.0.1\t1\t1\t0\t10\t10\t10\t10\t200\ttrue\t2026-05-30T00:00:00Z\t10\t0\tready\n' > "$ROUTE_STATS_FILE"
    printf 'ip=20.0.0.1\nchecked_at=2026-05-30T00:00:00Z\n' > "$LAST_GOOD_ROUTE_FILE"
    printf '%s 1 100 5 1\n' "$PORT_DOMAIN" > "$ROUTE_LATENCY_BASELINE_FILE"
    printf '%s 1\n' "$PORT_DOMAIN" > "$ROUTE_DEGRADED_REQUEST_FILE"
    printf '1\n' > "$ROUTE_DEGRADED_REFRESH_STAMP_FILE"
    printf 'refused_version\tfrom=1.4.3\tto=1.4.2\tsha256=abc\n' > "$UPDATE_AUDIT_FILE"
    printf 'older update decision\n' > "$UPDATE_AUDIT_FILE.1"
    XRAY_BIN="$TMP_ROOT/missing-xray"
    xray_running() { return 0; }
    is_port_open() { return 0; }
    xhttp_probe_metrics() { printf '200 1\n'; }
    background_supervisor_status() { printf 'pid=1 running=heartbeat version=ok token=present heartbeat_age=1s\n'; }

    local bundle extract
    bundle="$(create_support_bundle)" || fail "support bundle creation failed"
    [[ -s "$bundle" ]] || fail "support bundle archive is missing"
    extract="$TMP_ROOT/support-extract"
    mkdir -p "$extract"
    tar -xzf "$bundle" -C "$extract"
    [[ -s "$extract/doctor.json" ]] || fail "support bundle missing doctor JSON"
    [[ -s "$extract/logs/g2ray.log" ]] || fail "support bundle missing redacted app log"
    for file in route_latency_baseline route_degraded_request route_degraded_refresh_stamp update_audit.log update_audit.log.1; do
        [[ -s "$extract/state/$file" ]] || fail "support bundle missing route/update evidence: $file"
    done
    if grep -R -Fq "$uuid" "$extract" || grep -R -Fq "$bearer" "$extract" || grep -R -Fq "$github_token" "$extract" || grep -R -Fq "$structured_token" "$extract" || grep -R -Fq "$metadata_token" "$extract" || grep -R -Fq "$vless" "$extract"; then
        fail "support bundle leaked sensitive material"
    fi
    if grep -R -Fq 'behavior-space' "$extract" || grep -R -Fq 'behavior-space-443.app.github.dev' "$extract" || grep -R -Fq '20.0.0.1' "$extract"; then
        fail "support bundle leaked network identifiers"
    fi
    grep -R -Fq '<vless-redacted>' "$extract" || fail "support bundle did not mark redacted VLESS links"
    grep -R -Fq '<codespace-redacted>' "$extract" || fail "support bundle did not mark redacted Codespace identity"
    grep -R -Fq '<ip-redacted>' "$extract" || fail "support bundle did not mark redacted route IPs"
    python - "$extract/logs/g2ray-events.jsonl" <<'PY' || fail "support bundle redaction corrupted structured JSONL"
import json
import sys
with open(sys.argv[1], encoding="utf-8") as fh:
    rows = [json.loads(line) for line in fh if line.strip()]
assert rows
PY
    grep -Fq 'xray=unknown' "$extract/metadata.txt" || fail "support bundle did not survive missing Xray binary"
    pass "support bundle redacts sensitive material"
}

test_support_bundle_can_include_network_metadata_when_opted_in() {
    reset_runtime_paths
    CODESPACE_NAME="behavior-space"
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    XRAY_PORT=443
    G2RAY_SUPPORT_INCLUDE_NETWORK=1
    printf 'route ok ip=20.0.0.1 domain=behavior-space-443.app.github.dev\n' > "$LOG_FILE"
    printf '2026-05-30T00:00:00Z\t20.0.0.1\t200\t10\ttrue\n' > "$ROUTE_HEALTH_FILE"
    XRAY_BIN="$TMP_ROOT/missing-xray"
    xray_running() { return 0; }
    is_port_open() { return 0; }
    xhttp_probe_metrics() { printf '200 1\n'; }
    background_supervisor_status() { printf 'pid=1 running=heartbeat version=ok token=present heartbeat_age=1s\n'; }

    local bundle extract
    bundle="$(create_support_bundle)" || fail "support bundle creation failed with network opt-in"
    extract="$TMP_ROOT/support-network-extract"
    mkdir -p "$extract"
    tar -xzf "$bundle" -C "$extract"
    grep -R -Fq 'behavior-space-443.app.github.dev' "$extract" \
        || fail "network opt-in did not preserve Codespaces domain"
    grep -R -Fq '20.0.0.1' "$extract" \
        || fail "network opt-in did not preserve route IP"
    pass "support bundle can include network metadata when explicitly opted in"
}

test_support_bundle_handles_relative_log_dir() {
    reset_runtime_paths
    local cwd="$TMP_ROOT/relative-support-cwd"
    mkdir -p "$cwd"
    (
        cd "$cwd"
        LOG_DIR="relative-logs"
        LOG_FILE="$LOG_DIR/g2ray.log"
        STRUCTURED_LOG_FILE="$LOG_DIR/g2ray-events.jsonl"
        DIAGNOSTIC_LOG_FILE="$LOG_DIR/g2ray-diagnostics.log"
        mkdir -p "$LOG_DIR"
        printf 'relative app log\n' > "$LOG_FILE"
        printf '{"ts":"2026-05-30T00:00:00Z","level":"INFO","event":"test","message":"relative"}\n' > "$STRUCTURED_LOG_FILE"
        printf 'relative diagnostic\n' > "$DIAGNOSTIC_LOG_FILE"
        XRAY_BIN="$TMP_ROOT/missing-xray"
        xray_running() { return 0; }
        is_port_open() { return 0; }
        xhttp_probe_metrics() { printf '200 1\n'; }
        background_supervisor_status() { printf 'pid=1 running=heartbeat version=ok token=present heartbeat_age=1s\n'; }

        local bundle
        bundle="$(create_support_bundle)" || fail "support bundle failed with relative LOG_DIR"
        [[ -s "$bundle" ]] || fail "relative support bundle archive is missing"
        tar -tzf "$bundle" > "$TMP_ROOT/relative-support-list.txt"
        grep -Fxq './logs/g2ray.log' "$TMP_ROOT/relative-support-list.txt" || grep -Fxq 'logs/g2ray.log' "$TMP_ROOT/relative-support-list.txt" \
            || fail "relative support bundle is missing app log"
    )
    pass "support bundle handles relative log dir"
}

test_support_bundle_includes_rotated_logs() {
    reset_runtime_paths
    printf 'live app log\n' > "$LOG_FILE"
    printf 'rotated app log\n' > "$LOG_FILE.1"
    printf '{"ts":"2026-05-30T00:00:00Z","level":"INFO","event":"live","message":"live"}\n' > "$STRUCTURED_LOG_FILE"
    printf '{"ts":"2026-05-29T00:00:00Z","level":"WARN","event":"old","message":"rotated"}\n' > "$STRUCTURED_LOG_FILE.1"
    printf 'live diagnostic\n' > "$DIAGNOSTIC_LOG_FILE"
    printf 'rotated diagnostic\n' > "$DIAGNOSTIC_LOG_FILE.1"
    printf 'xray live\n' > "$LOG_DIR/xray.log"
    printf 'xray rotated\n' > "$LOG_DIR/xray.log.1"
    printf 'xray error live\n' > "$LOG_DIR/xray-error.log"
    printf 'xray error rotated\n' > "$LOG_DIR/xray-error.log.1"
    XRAY_BIN="$TMP_ROOT/missing-xray"
    xray_running() { return 0; }
    is_port_open() { return 0; }
    xhttp_probe_metrics() { printf '200 1\n'; }
    background_supervisor_status() { printf 'pid=1 running=heartbeat version=ok token=present heartbeat_age=1s\n'; }

    local bundle extract
    bundle="$(create_support_bundle)" || fail "support bundle creation failed with rotated logs"
    extract="$TMP_ROOT/support-rotated-extract"
    mkdir -p "$extract"
    tar -xzf "$bundle" -C "$extract"
    [[ -s "$extract/logs/g2ray.log.1" ]] || fail "support bundle missing rotated app log"
    [[ -s "$extract/logs/g2ray-events.jsonl.1" ]] || fail "support bundle missing rotated event log"
    [[ -s "$extract/logs/g2ray-diagnostics.log.1" ]] || fail "support bundle missing rotated diagnostics log"
    [[ -s "$extract/logs/xray.log.1" ]] || fail "support bundle missing rotated Xray log"
    [[ -s "$extract/logs/xray-error.log.1" ]] || fail "support bundle missing rotated Xray error log"
    pass "support bundle includes rotated logs"
}

test_support_bundle_marks_unreadable_optional_logs() {
    reset_runtime_paths
    local was_unreadable=0
    printf 'live app log\n' > "$LOG_FILE"
    printf '{"ts":"2026-05-30T00:00:00Z","level":"INFO","event":"live","message":"live"}\n' > "$STRUCTURED_LOG_FILE"
    printf 'live diagnostic\n' > "$DIAGNOSTIC_LOG_FILE"
    printf 'root-owned style xray log\n' > "$LOG_DIR/xray.log"
    chmod 000 "$LOG_DIR/xray.log" 2>/dev/null || true
    [[ ! -r "$LOG_DIR/xray.log" ]] && was_unreadable=1
    XRAY_BIN="$TMP_ROOT/missing-xray"
    xray_running() { return 0; }
    is_port_open() { return 0; }
    xhttp_probe_metrics() { printf '200 1\n'; }
    background_supervisor_status() { printf 'pid=1 running=heartbeat version=ok token=present heartbeat_age=1s\n'; }

    local bundle extract
    bundle="$(create_support_bundle)" || {
        chmod 600 "$LOG_DIR/xray.log" 2>/dev/null || true
        fail "support bundle failed with unreadable optional log"
    }
    chmod 600 "$LOG_DIR/xray.log" 2>/dev/null || true
    extract="$TMP_ROOT/support-unreadable-extract"
    mkdir -p "$extract"
    tar -xzf "$bundle" -C "$extract"
    [[ -s "$extract/logs/xray.log" ]] || fail "support bundle missing marker for unreadable optional log"
    if [[ "$was_unreadable" == "1" ]]; then
        grep -Fq 'unreadable:' "$extract/logs/xray.log" || fail "support bundle did not mark unreadable optional log"
    else
        grep -Fq 'root-owned style xray log' "$extract/logs/xray.log" || fail "support bundle did not copy readable xray log"
    fi
    pass "support bundle marks unreadable optional logs"
}

test_route_refresh_backoff_preserves_cache_without_probe_storms() {
    reset_runtime_paths
    write_test_vless_config
    PORT_DOMAIN="behavior-space-443.app.github.dev"
    ROUTE_MONITOR_MAX_CANDIDATES=1
    ROUTE_PROBE_CONCURRENCY=1
    ROUTE_FAILURE_COOLDOWN_SEC=0
    printf '2026-05-30T00:00:00Z\t20.0.0.1\t200\t50\ttrue\tdns\tready\n' > "$ROUTE_HEALTH_FILE"
    local original_resolver original_probe
    original_resolver="$(declare -f resolve_domain_ips_with_sources)"
    original_probe="$(declare -f xhttp_probe_metrics)"
    resolve_domain_ips_with_sources() { printf 'dns\t20.0.0.2\n'; }
    xhttp_probe_metrics() { printf '404 25 route_settling_404\n'; }

    refresh_route_candidate_health --force || fail "forced failed route refresh did not complete"
    grep -Fq $'20.0.0.1\t200\t50\ttrue' "$ROUTE_HEALTH_FILE" \
        || fail "failed refresh did not preserve the last known usable cache"
    grep -Fq $'consecutive_failures\t1' "$ROUTE_REFRESH_STATE_FILE" \
        || fail "failed refresh did not record backoff state"

    touch -d '1 hour ago' "$ROUTE_HEALTH_FILE"
    xhttp_probe_metrics() { fail "backoff allowed an immediate duplicate route scan"; }
    refresh_route_candidate_health || fail "backoff-skipped refresh returned failure"
    eval "$original_resolver"
    eval "$original_probe"
    grep -Fq 'route_candidate_monitor deferred reason=backoff' "$LOG_FILE" \
        || fail "route refresh backoff was not logged"
    pass "route refresh backoff preserves cache and prevents probe storms"
}

test_stale_route_cache_is_not_exported_after_hard_age() {
    reset_runtime_paths
    write_test_vless_config
    printf '2026-05-30T00:00:00Z\t20.0.0.1\t200\t50\ttrue\tdns\tready\n' > "$ROUTE_HEALTH_FILE"
    touch -d '7 hours ago' "$ROUTE_HEALTH_FILE"
    ROUTE_HEALTH_EXPORT_MAX_AGE_SEC=21600

    if cached_usable_fallback_ips >/dev/null 2>&1; then
        fail "stale route cache was exported after the hard maximum age"
    fi
    grep -Fq 'stale_cache' "$LOG_FILE" || fail "stale route cache rejection was not logged"
    pass "stale route cache is excluded from exports after the hard maximum age"
}

test_self_heal_defers_external_repairs_while_traffic_is_active() {
    (
        reset_runtime_paths
        write_test_vless_config
        date +%s > "$ACTIVE_TRAFFIC_STAMP_FILE"
        ensure_codespace_port_public() { return 0; }
        xray_running() { return 0; }
        xray_listener_ready() { return 0; }
        xhttp_probe_metrics() { printf '404 25 route_settling_404\n'; }
        curl_http_code() { printf '404\n'; }
        repair_codespace_port_route() { fail "self-heal repaired the route despite recent active traffic"; }
        force_reconnect() { fail "self-heal reconnected despite recent active traffic"; }
        reset_route_bad_count() { return 0; }
        reset_edge_bad_count() { return 0; }

        self_heal_once || fail "self-heal failed while deferring an external probe"
        grep -Fq 'action=defer_active_traffic' "$LOG_FILE" \
            || fail "self-heal did not log active-traffic deferral"
    )
    pass "self-heal defers disruptive external repair while traffic is active"
}

test_self_heal_preserves_idle_established_tunnels() {
    (
        reset_runtime_paths
        write_test_vless_config
        ensure_codespace_port_public() { return 0; }
        xray_running() { return 0; }
        xray_listener_ready() { return 0; }
        xhttp_probe_metrics() { printf '404 25 route_settling_404\n'; }
        inbound_tunnel_connection_present() { return 0; }
        curl_http_code() { fail "self-heal continued probing after finding an established tunnel"; }
        repair_codespace_port_route() { fail "self-heal destroyed an idle established tunnel"; }
        force_reconnect() { fail "self-heal reconnected despite an established tunnel"; }
        reset_route_bad_count() { return 0; }
        reset_edge_bad_count() { return 0; }

        self_heal_once || fail "self-heal failed while preserving an idle established tunnel"
        grep -Fq 'signal=established_connection' "$LOG_FILE" \
            || fail "self-heal did not classify the established-tunnel deferral"
    )
    pass "self-heal preserves quiet but established tunnel sessions"
}

test_self_heal_preserves_idle_established_ws_tunnels() {
    (
        reset_runtime_paths
        write_test_vless_config
        XRAY_PORT=443
        WS_PORT=8443
        ws_fallback_configured() { return 0; }
        ensure_codespace_port_public() { return 0; }
        ensure_optional_ws_port_public() { return 0; }
        xray_running() { return 0; }
        xray_listener_ready() { return 0; }
        xhttp_probe_metrics() { printf '404 25 route_settling_404\n'; }
        ss() {
            case "$*" in
                *":8443"*) printf 'ESTAB 0 0 127.0.0.1:8443 127.0.0.1:49152\n' ;;
            esac
        }
        curl_http_code() { fail "self-heal continued probing after finding an established WS tunnel"; }
        repair_codespace_port_route() { fail "self-heal destroyed an idle established WS tunnel"; }
        force_reconnect() { fail "self-heal reconnected despite an established WS tunnel"; }
        reset_route_bad_count() { return 0; }
        reset_edge_bad_count() { return 0; }

        self_heal_once || fail "self-heal failed while preserving an idle established WS tunnel"
        grep -Fq 'signal=established_connection' "$LOG_FILE" \
            || fail "self-heal did not classify the established WS tunnel deferral"
    )
    pass "self-heal preserves quiet but established WebSocket sessions"
}

test_self_heal_reasserts_optional_ws_visibility() {
    (
        reset_runtime_paths
        write_test_vless_config
        ws_fallback_configured() { return 0; }
        ensure_codespace_port_public() { printf 'primary\n' >> "$TMP_ROOT/ports"; return 0; }
        ensure_optional_ws_port_public() { printf 'ws\n' >> "$TMP_ROOT/ports"; return 0; }
        xray_running() { return 0; }
        xray_listener_ready() { return 0; }
        xhttp_probe_metrics() { printf '204 25 ready\n'; }

        self_heal_once || fail "self-heal failed while refreshing optional WS visibility"
        grep -Fxq 'primary' "$TMP_ROOT/ports" || fail "primary port visibility was not checked"
        grep -Fxq 'ws' "$TMP_ROOT/ports" || fail "optional WS port visibility was not checked"
    )
    pass "self-heal reasserts optional WebSocket port visibility"
}

test_self_heal_keeps_failure_count_when_route_repair_fails() {
    (
        reset_runtime_paths
        write_test_vless_config
        ensure_codespace_port_public() { return 0; }
        xray_running() { return 0; }
        xray_listener_ready() { return 0; }
        xhttp_probe_metrics() { printf '404 25 route_settling_404\n'; }
        curl_http_code() { printf '404\n'; }
        increment_route_bad_count() { printf '2\n'; }
        mark_route_repair_attempt_if_allowed() { return 0; }
        repair_codespace_port_route() { return 1; }
        active_tunnel_recent() { return 1; }
        inbound_tunnel_connection_present() { return 1; }
        reset_route_bad_count() { printf 'reset\n' >> "$TMP_ROOT/unexpected-route-reset"; }
        reset_edge_bad_count() { return 0; }

        self_heal_once || fail "self-heal returned failure after a bounded route repair failure"
        [[ ! -e "$TMP_ROOT/unexpected-route-reset" ]] \
            || fail "self-heal erased route failure history after repair failed"
        grep -Fq 'route_repair_failed' "$LOG_FILE" \
            || fail "failed route repair was not classified in the log"
    )
    pass "self-heal preserves route failure history until repair is verified"
}

test_port_listener_detection_does_not_require_sudo() {
    (
        XRAY_PORT=443
        ss() { printf 'LISTEN 0 4096 0.0.0.0:443 0.0.0.0:*\n'; }
        is_port_open || fail "listener detection missed a bound IPv4 port"
        XRAY_PORT=8443
        if is_port_open; then
            fail "listener detection matched the wrong port"
        fi
    )
    pass "listener detection works without sudo and matches the exact port"
}

test_deadline_stops_term_resistant_writers() {
    local output="$TMP_ROOT/deadline-resistant.log" before after rc=0
    run_with_deadline 1 bash -c '
        bash -c '\''trap "" TERM; while :; do printf x >> "$1"; sleep 0.05; done'\'' _ "$1" &
        wait
    ' _ "$output" || rc=$?
    [[ "$rc" == 124 ]] || fail "resistant writer did not time out: $rc"
    before=$(wc -c < "$output")
    sleep 0.3
    after=$(wc -c < "$output")
    [[ "$before" == "$after" ]] || fail 'reparented deadline writer survived group KILL'
    pass 'deadline stops TERM-resistant reparented writers'
}

test_deadline_fails_closed_without_temp_storage() {
    (
        mktemp() { return 1; }
        local rc=0
        run_with_deadline 1 touch "$TMP_ROOT/deadline-must-not-run" || rc=$?
        [[ "$rc" == 125 && ! -e "$TMP_ROOT/deadline-must-not-run" ]] || fail 'deadline ran without its status storage'
    )
    pass 'deadline initialization failure never runs an unbounded task'
}

test_deadline_includes_exit_cleanup() {
    (
        reset_runtime_paths
        deadline_exit_cleanup() {
            trap 'sleep 3' EXIT
            return 0
        }
        local rc=0
        run_with_deadline 1 deadline_exit_cleanup || rc=$?
        [[ "$rc" == 124 ]] || fail "deadline reported $rc while exit cleanup exceeded its budget"
        grep -Fq 'deadline_exceeded' "$LOG_FILE" || fail 'exit cleanup timeout was not reported'
        run_with_deadline 1 true || fail 'a completed deadline task lost its success status'
        rc=0
        run_with_deadline 1 bash -c 'exit 37' || rc=$?
        [[ "$rc" == 37 ]] || fail "a completed deadline task lost its failure status: $rc"
        deadline_failed_exit_cleanup() {
            trap 'exit 39' EXIT
            return 0
        }
        rc=0
        run_with_deadline 1 deadline_failed_exit_cleanup || rc=$?
        [[ "$rc" == 39 ]] || fail "failed exit cleanup was masked by command status: $rc"
    )
    pass 'deadline bounds exit cleanup and preserves completed task statuses'
}

test_deadline_preserves_status_without_a_status_write() {
    (
        reset_runtime_paths
        printf() {
            [[ "${1:-}" != '%s\n' ]] || return 1
            builtin printf "$@"
        }
        local rc=0 attempt
        for attempt in {1..25}; do
            rc=0
            run_with_deadline 1 bash -c 'exit 37' || rc=$?
            [[ "$rc" == 37 ]] || fail "a failed status write hid the command failure: $rc"
            run_with_deadline 1 true || fail 'a failed status write invented a command failure'
        done
    )
    pass 'deadline preserves command exit status when its status write fails'
}

test_deadline_rejects_unverified_process_group() {
    (
        reset_runtime_paths
        kill() {
            if [[ "${1:-}" == -0 && "${2:-}" == -- && "${3:-}" == -[0-9]* ]]; then
                return 1
            fi
            builtin kill "$@"
        }
        deadline_test_work() { while :; do :; done; }
        local rc=0 started="$SECONDS"
        run_with_deadline 10 deadline_test_work || rc=$?
        [[ "$rc" == 125 ]] || fail "unverified deadline group returned $rc instead of initialization failure"
        (( SECONDS - started < 5 )) || fail 'unverified process group waited for the command deadline'
        grep -Fq 'deadline_group_init_failed' "$LOG_FILE" || fail 'group initialization failure was not reported'
    )
    pass 'deadline rejects an unverified process group instead of reaching an unbounded wait'
}

test_deadline_terminates_descendant_processes() {
    reset_runtime_paths
    local child_file child rc
    child_file="$TMP_ROOT/deadline-child.pid"
    set +e
    run_with_deadline 1 bash -c 'sleep 30 & child=$!; printf "%s\n" "$child" > "$1"; wait "$child"' _ "$child_file"
    rc=$?
    set -e
    [[ "$rc" -eq 124 ]] || fail "deadline wrapper returned $rc instead of timeout status 124"
    child=$(cat "$child_file" 2>/dev/null || true)
    [[ "$child" =~ ^[0-9]+$ ]] || fail "deadline child PID was not recorded"
    sleep 0.2
    if kill -0 "$child" 2>/dev/null; then
        [[ "$(ps -o stat= -p "$child" 2>/dev/null)" == Z* ]] || fail "deadline left a descendant process running"
    fi
    pass "deadline wrapper terminates descendant processes"
}

test_post_start_propagates_silent_start_failure() {
    local fixture="$TMP_ROOT/post-start-fixture" rc
    mkdir -p "$fixture/scripts" "$fixture/logs" "$fixture/data"
    cp "$ROOT_DIR/scripts/post-start.sh" "$fixture/scripts/post-start.sh"
    printf '#!/usr/bin/env bash\nexit 37\n' > "$fixture/g2ray.sh"

    set +e
    G2RAY_LOG_DIR="$fixture/logs" bash "$fixture/scripts/post-start.sh"
    rc=$?
    set -e

    [[ "$rc" -eq 37 ]] || fail "post-start returned $rc instead of silent-start status 37"
    grep -Fq 'post_start failed rc=37' "$fixture/logs/g2ray.log" \
        || fail "post-start did not log the real silent-start failure status"
    pass "post-start propagates silent-start failures"
}

test_generate_config_respects_runtime_lock_for_entire_transaction() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        PERFORMANCE_PROFILE=balanced
        RUNTIME_LOCK_WAIT_ATTEMPTS=1
        local owner_pid rc=0
        sleep 10 &
        owner_pid=$!
        mkdir -p "$RUNTIME_LOCK_DIR"
        printf '%s\n' "$owner_pid" > "$RUNTIME_LOCK_DIR/pid"
        uuidgen() { printf '77777777-8888-4999-8aaa-bbbbbbbbbbbb\n'; }
        xray_validate_config_file() { return 0; }
        start_xray() { return 0; }
        wait_for_port() { return 0; }
        ensure_codespace_port_public() { return 0; }
        ensure_optional_ws_port_public() { return 0; }
        ensure_route_cache_for_export() { return 0; }
        refresh_config_exports() { return 0; }
        xhttp_probe_metrics() { printf '200 12 ready\n'; }

        set +e
        generate_config >/dev/null 2>&1
        rc=$?
        set -e
        kill "$owner_pid" 2>/dev/null || true
        wait "$owner_pid" 2>/dev/null || true

        [[ "$rc" -ne 0 ]] || fail "generate_config ignored a live runtime transaction lock"
        [[ ! -e "$CONFIG_FILE" && ! -e "$UUID_FILE" ]] \
            || fail "generate_config changed config state while another runtime transaction held the lock"
    )
    pass "generate_config holds the runtime lock for its entire transaction"
}

test_generate_config_failed_start_restores_previous_running_engine() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        PERFORMANCE_PROFILE=balanced
        local old_uuid="11111111-2222-4333-8444-555555555555"
        printf '%s\n' "$old_uuid" > "$UUID_FILE"
        cat > "$CONFIG_FILE" <<JSON
{"old":true,"inbounds":[{"protocol":"vless","settings":{"clients":[{"id":"${old_uuid}"}]}}]}
JSON
        local engine_state="old" start_calls=0 stop_calls=0 wait_calls=0 rc=0
        uuidgen() { printf '99999999-aaaa-4bbb-8ccc-dddddddddddd\n'; }
        xray_validate_config_file() { return 0; }
        start_xray() {
            start_calls=$((start_calls + 1))
            if (( start_calls == 1 )); then
                engine_state="new"
            elif grep -Fq '"old":true' "$CONFIG_FILE" && grep -Fxq "$old_uuid" "$UUID_FILE"; then
                engine_state="old"
            else
                engine_state="wrong-config"
            fi
            return 0
        }
        wait_for_port() {
            wait_calls=$((wait_calls + 1))
            [[ "$engine_state" == "old" ]]
        }
        stop_xray() {
            stop_calls=$((stop_calls + 1))
            engine_state="stopped"
            return 0
        }
        refresh_config_exports() { fail "failed config generation must not refresh exports"; }

        set +e
        generate_config >/dev/null 2>&1
        rc=$?
        set -e

        [[ "$rc" -ne 0 ]] || fail "generate_config reported success after the new engine failed readiness"
        grep -Fq '"old":true' "$CONFIG_FILE" || fail "failed start did not restore the previous config"
        grep -Fxq "$old_uuid" "$UUID_FILE" || fail "failed start did not restore the previous UUID"
        [[ "$stop_calls" -ge 1 ]] || fail "failed new engine was left running during rollback"
        [[ "$start_calls" -eq 2 && "$wait_calls" -eq 2 && "$engine_state" == "old" ]] \
            || fail "previous engine was not restarted after rollback (starts=$start_calls waits=$wait_calls state=$engine_state)"
    )
    pass "failed config start restores the previous running engine"
}

test_generate_config_preserves_old_engine_when_replacement_never_launches() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        PERFORMANCE_PROFILE=balanced
        local old_uuid="11111111-2222-4333-8444-555555555555"
        printf '%s\n' "$old_uuid" > "$UUID_FILE"
        cat > "$CONFIG_FILE" <<JSON
{"old":true,"inbounds":[{"protocol":"vless","settings":{"clients":[{"id":"${old_uuid}"}]}}]}
JSON
        local start_calls=0 stop_calls=0 rc=0
        uuidgen() { printf '99999999-aaaa-4bbb-8ccc-dddddddddddd\n'; }
        xray_validate_config_file() { return 0; }
        xray_running() { return 0; }
        start_xray() {
            start_calls=$((start_calls + 1))
            START_XRAY_LAST_PHASE="previous_stop_failed"
            return 1
        }
        wait_for_port() { fail "wait_for_port ran after start_xray failed before launch"; }
        stop_xray() {
            stop_calls=$((stop_calls + 1))
            return 1
        }

        set +e
        generate_config >/dev/null 2>&1
        rc=$?
        set -e

        [[ "$rc" -ne 0 ]] || fail "failed replacement config unexpectedly reported success"
        grep -Fq '"old":true' "$CONFIG_FILE" \
            || fail "pre-launch failure did not restore the old engine's config"
        grep -Fxq "$old_uuid" "$UUID_FILE" \
            || fail "pre-launch failure did not restore the old engine's UUID"
        [[ "$start_calls" -eq 1 ]] \
            || fail "rollback tried to launch another engine even though the old engine was preserved"
        [[ "$stop_calls" -eq 0 ]] \
            || fail "rollback tried to stop the preserved old engine after the replacement never launched"
    )
    pass "pre-launch config failure preserves the still-running previous engine"
}

test_generate_config_rollback_keeps_runtime_available_when_uuid_cache_restore_fails() {
    reset_runtime_paths
    (
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        XRAY_PORT=443
        PERFORMANCE_PROFILE=balanced
        local old_uuid="11111111-2222-4333-8444-555555555555"
        cat > "$CONFIG_FILE" <<JSON
{
  "inbounds": [{
    "tag": "vless-in",
    "protocol": "vless",
    "settings": {"clients": [{"id": "${old_uuid}"}]},
    "streamSettings": {"network": "xhttp", "xhttpSettings": {"path": "/", "mode": "packet-up"}}
  }]
}
JSON
        printf '%s\n' "$old_uuid" > "$UUID_FILE"
        local uuid_writes=0 start_calls=0 wait_calls=0 rc=0
        uuidgen() { printf '99999999-aaaa-4bbb-8ccc-dddddddddddd\n'; }
        xray_validate_config_file() { return 0; }
        _atomic_write() {
            local file="$1" content="$2" tmp
            if [[ "$file" == "$UUID_FILE" ]]; then
                uuid_writes=$((uuid_writes + 1))
                (( uuid_writes == 1 )) || return 1
            fi
            tmp="${file}.test-write"
            printf '%s\n' "$content" > "$tmp" && mv -f "$tmp" "$file"
        }
        start_xray() {
            start_calls=$((start_calls + 1))
            return 0
        }
        wait_for_port() {
            wait_calls=$((wait_calls + 1))
            (( wait_calls >= 2 ))
        }
        stop_xray() { return 0; }

        set +e
        generate_config >/dev/null 2>&1
        rc=$?
        set -e

        [[ "$rc" -ne 0 ]] || fail "failed replacement config unexpectedly reported success"
        [[ "$(config_vless_uuid)" == "$old_uuid" ]] \
            || fail "rollback did not restore the previous config identity"
        [[ ! -e "$UUID_FILE" ]] \
            || fail "rollback kept a stale UUID cache after restoring that cache failed"
        [[ "$(active_vless_uuid)" == "$old_uuid" ]] \
            || fail "active UUID did not fall back to the restored config"
        [[ "$start_calls" -eq 2 && "$wait_calls" -eq 2 ]] \
            || fail "UUID cache failure prevented the previous engine from restarting"
    )
    pass "config rollback preserves runtime availability when UUID cache repair fails"
}

test_links_and_export_hash_use_active_config_uuid() {
    reset_runtime_paths
    (
        local active_uuid="11111111-2222-4333-8444-555555555555"
        local stale_uuid="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        CODESPACE_NAME="behavior-space"
        PORT_DOMAIN="behavior-space-443.app.github.dev"
        CODESPACES_EDGE_PORT=443
        printf '%s\n' "$stale_uuid" > "$UUID_FILE"
        cat > "$CONFIG_FILE" <<JSON
{
  "inbounds": [{
    "tag": "vless-in",
    "protocol": "vless",
    "settings": {"clients": [{"id": "${active_uuid}"}]},
    "streamSettings": {"network": "xhttp", "xhttpSettings": {"path": "/", "mode": "packet-up"}}
  }]
}
JSON
        effective_export_route_ips() { printf '20.0.0.1\n'; }
        local link before after
        link=$(generate_link_for_address "20.0.0.1")
        [[ "$link" == "vless://${active_uuid}@"* ]] \
            || fail "generated link used stale uuid.txt instead of the active Xray config UUID"
        before=$(export_input_hash)
        jq --arg uuid "22222222-3333-4444-8555-666666666666" \
            '(.inbounds[0].settings.clients[0].id) = $uuid' "$CONFIG_FILE" > "$CONFIG_FILE.next"
        mv -f "$CONFIG_FILE.next" "$CONFIG_FILE"
        after=$(export_input_hash)
        [[ "$before" != "$after" ]] \
            || fail "export cache hash ignored a UUID change in the active Xray config"
    )
    pass "links and export cache identity follow the active Xray config"
}

test_active_uuid_rejects_invalid_existing_config() {
    reset_runtime_paths
    (
        local stale_uuid="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        printf '%s\n' "$stale_uuid" > "$UUID_FILE"
        : > "$CONFIG_FILE"
        if active_vless_uuid >/dev/null 2>&1; then
            fail "active UUID fell back to stale uuid.txt while config.json was invalid"
        fi
        if generate_link_for_address "20.0.0.1" >/dev/null 2>&1; then
            fail "link generation used stale uuid.txt while config.json was invalid"
        fi
    )
    pass "invalid active config cannot export a stale UUID"
}

test_export_hash_tracks_active_config_xhttp_mode() {
    reset_runtime_paths
    (
        printf '11111111-2222-4333-8444-555555555555\n' > "$UUID_FILE"
        cat > "$CONFIG_FILE" <<'JSON'
{
  "inbounds": [{
    "tag": "vless-in",
    "protocol": "vless",
    "settings": {"clients": [{"id": "11111111-2222-4333-8444-555555555555"}]},
    "streamSettings": {"xhttpSettings": {"mode": "packet-up", "path": "/"}}
  }]
}
JSON
        G2RAY_XHTTP_MODE="packet-up"
        effective_export_route_ips() { printf '20.0.0.1\n'; }
        local before after
        before=$(export_input_hash)
        jq '(.inbounds[0].streamSettings.xhttpSettings.mode) = "stream-up"' "$CONFIG_FILE" > "$CONFIG_FILE.next"
        mv "$CONFIG_FILE.next" "$CONFIG_FILE"
        after=$(export_input_hash)
        [[ "$before" != "$after" ]] \
            || fail "export hash ignored the XHTTP mode loaded by the active config"
    )
    pass "export hash tracks the active config XHTTP mode"
}

test_json_dns_ips_parses_formatted_provider_json() {
    (
        curl() {
            printf '%s\n' '{ "Status": 0, "Answer": [ { "data": "1.2.3.4" }, {"data":"5.6.7.8"}, { "data": "999.1.1.1" } ] }'
        }
        local output
        output=$(json_dns_ips "https://dns.example.test/resolve")
        grep -Fxq '1.2.3.4' <<< "$output" \
            || fail "formatted DoH JSON did not yield its first IPv4 candidate"
        grep -Fxq '5.6.7.8' <<< "$output" \
            || fail "compact DoH JSON did not yield its IPv4 candidate"
        if grep -Fxq '999.1.1.1' <<< "$output"; then
            fail "DoH JSON parser accepted an invalid IPv4 candidate"
        fi
    )
    pass "DoH candidate discovery parses structured JSON regardless of whitespace"
}

test_active_xray_log_rotation_preserves_open_descriptor() {
    (
        local file="$LOG_DIR/xray.log"
        LOG_MAX_BYTES=1
        LOG_ROTATE_KEEP=2
        printf 'before\n' > "$file"
        exec 8>>"$file"
        rotate_log_file "$file" || fail "copy-truncate rotation returned failure"
        printf 'after\n' >&8
        exec 8>&-
        grep -Fxq 'before' "${file}.1" \
            || fail "rotated Xray snapshot lost the pre-rotation content"
        grep -Fxq 'after' "$file" \
            || fail "open Xray log descriptor kept writing to the rotated inode"
        if grep -Fxq 'after' "${file}.1"; then
            fail "copy-truncate rotation did not preserve the active Xray log path"
        fi
    )
    pass "active Xray log rotation preserves daemon file descriptors"
}

test_lock_claim_publishes_owner_atomically() {
    (
        reset_runtime_paths
        local exposed="$TMP_ROOT/exposed-empty-lock"
        rm -f "$exposed"
        printf() {
            if [[ -d "$RUNTIME_LOCK_DIR" && ! -s "$RUNTIME_LOCK_DIR/pid" ]]; then
                builtin printf 'exposed\n' > "$exposed"
            fi
            builtin printf "$@"
        }
        _try_claim_lock_dir "$RUNTIME_LOCK_DIR" test || fail "lock claim failed"
        [[ ! -f "$exposed" ]] || fail "lock directory was visible before its PID was initialized"
        [[ "$(cat "$RUNTIME_LOCK_DIR/pid")" == "$BASHPID" ]] || fail "lock lost its owner"
        release_runtime_lock
    )
    pass "lock claim publishes initialized ownership atomically"
}

test_xray_launch_uses_append_and_checks_log_preflight() {
    (
        reset_runtime_paths
        write_test_vless_config
        local launch_file="$TMP_ROOT/xray-launch-command" stopped="$TMP_ROOT/xray-stopped"
        upgrade_config_dns() { return 0; }
        xray_validate_config() { return 0; }
        stop_xray() { touch "$stopped"; }
        reset_session_bytes_baseline() { return 0; }
        sudo() { builtin printf '%s\n' "${3:-}" > "$launch_file"; builtin printf '123456\n'; }
        _start_xray_impl >/dev/null || fail "mock Xray launch failed"
        grep -Fq '</dev/null >>' "$launch_file" || fail "Xray launch does not use append-mode logging"
        [[ -w "$LOG_DIR/xray.log" && -w "$LOG_DIR/xray-error.log" ]] || fail "daemon logs were not precreated for the panel user"
        rm -f "$stopped"
        prepare_xray_logs() { return 1; }
        if _start_xray_impl >/dev/null; then fail "Xray start ignored log preflight failure"; fi
        [[ ! -f "$stopped" ]] || fail "log preflight failure stopped the old engine"
    )
    pass "Xray launches with append logging and checks logs before stopping the current engine"
}

test_dns_discovery_skips_retired_json_service() {
    (
        reset_runtime_paths
        local calls="$TMP_ROOT/json-dns-calls"
        : > "$calls"
        json_dns_ips() { builtin printf '%s\n' "$1" >> "$calls"; builtin printf '20.0.0.1\n'; }
        wide_dns_discovery_enabled() { return 1; }
        curl_remote_ip() { return 0; }
        timeout() { return 0; }
        resolve_dns_provider_ips_with_sources behavior.example >/dev/null
        ! grep -Fq 'dns.quad9.net' "$calls" || fail "discovery still sends JSON to retired Quad9 API"
        grep -Fq 'dns.google/resolve' "$calls" || fail "Google candidate discovery was lost"
        grep -Fq 'cloudflare-dns.com/dns-query' "$calls" || fail "Cloudflare candidate discovery was lost"
    )
    pass "DNS discovery skips retired JSON service and retains working providers"
}

test_route_repair_preserves_active_sessions_for_all_callers() {
    (
        reset_runtime_paths
        local calls="$TMP_ROOT/repair-visibility-calls"
        : > "$calls"
        run_gh() { builtin printf 'toggle\n' >> "$calls"; }
        ensure_codespace_port_public() { return 0; }
        ensure_optional_ws_port_public() { return 0; }
        sleep() { :; }
        active_tunnel_recent() { return 0; }
        inbound_tunnel_connection_present() { return 1; }
        repair_codespace_port_route || fail "active traffic deferral failed"
        [[ ! -s "$calls" ]] || fail "route repair toggled visibility during active traffic"
        active_tunnel_recent() { return 1; }
        inbound_tunnel_connection_present() { return 0; }
        repair_codespace_port_route || fail "established session deferral failed"
        [[ ! -s "$calls" ]] || fail "route repair toggled visibility during a quiet established session"
        inbound_tunnel_connection_present() { return 1; }
        repair_codespace_port_route || fail "idle broken route repair failed"
        [[ -s "$calls" ]] || fail "route repair was disabled even without active sessions"
    )
    pass "route repair preserves active sessions regardless of its caller"
}

test_interactive_attach_defers_slow_route_settling() {
    (
        reset_runtime_paths
        write_test_vless_config
        local deferred="$TMP_ROOT/attach-deferred"
        _detect_codespace_name() { builtin printf 'behavior-space'; }
        record_resume_gap() { :; }
        xray_listener_ready() { return 0; }
        xhttp_probe_metrics() { builtin printf '404 1 route_settling_404\n'; }
        repair_codespace_port_route() { fail "interactive attach ran disruptive repair synchronously"; }
        wait_for_xhttp_route_ready() { fail "interactive attach blocked on route settling"; }
        start_headless_route_settling_monitor() { builtin printf '%s\n' "$1" > "$deferred"; }
        if ensure_runtime_ready interactive_attach; then fail "attach falsely reported an unusable route ready"; fi
        grep -Fxq 'interactive_attach' "$deferred" || fail "attach did not schedule bounded background recovery"
        [[ ! -d "$RUNTIME_LOCK_DIR" ]] || fail "attach kept the runtime lock during background recovery"
    )
    pass "interactive attach returns the panel while route settling continues in the background"
}

test_supervisor_reconnect_defers_long_route_verification() {
    (
        reset_runtime_paths
        write_test_vless_config
        local args_file="$TMP_ROOT/supervisor-reconnect-args"
        ensure_codespace_port_public() { return 0; }
        ensure_optional_ws_port_public() { return 0; }
        xray_running() { return 0; }
        xray_listener_ready() { return 0; }
        active_tunnel_recent() { return 1; }
        inbound_tunnel_connection_present() { return 1; }
        xhttp_probe_metrics() { builtin printf '0 1 transport_error\n'; }
        curl_http_code() { builtin printf '000\n'; }
        increment_edge_bad_count() { builtin printf '3\n'; }
        edge_reconnect_cooldown_active() { return 1; }
        mark_edge_reconnect_attempt() { :; }
        force_reconnect() {
            builtin printf '%s\n' "$*" > "$args_file"
        }
        self_heal_once || fail "supervisor deferred reconnect failed"
        grep -Fxq -- '--no-prompt --defer-route-check' "$args_file" \
            || fail "supervisor entered long foreground route verification under its short deadline"
    )
    (
        reset_runtime_paths
        write_test_vless_config
        local deferred="$TMP_ROOT/reconnect-deferred"
        _detect_codespace_name() { builtin printf 'behavior-space'; }
        stop_xray() { return 0; }
        start_xray() { return 0; }
        wait_for_port() { return 0; }
        ensure_codespace_port_public() { return 0; }
        ensure_optional_ws_port_public() { return 0; }
        curl_http_code() { fail "deferred reconnect ran foreground edge verification"; }
        xhttp_probe_metrics() { fail "deferred reconnect ran foreground XHTTP verification"; }
        start_headless_route_settling_monitor() { builtin printf '%s\n' "$1" > "$deferred"; }
        force_reconnect --no-prompt --defer-route-check >/dev/null || fail "deferred reconnect failed"
        grep -Fxq 'self_heal_reconnect' "$deferred" || fail "reconnect did not start background verification"
        [[ ! -d "$RUNTIME_LOCK_DIR" ]] || fail "reconnect held its lock while the route settled"
        rm -f "$deferred"
        start_xray() { return 1; }
        if force_reconnect --no-prompt --defer-route-check >/dev/null; then fail "deferred reconnect hid an engine start failure"; fi
        [[ ! -e "$deferred" ]] || fail "reconnect queued verification for an engine that failed to start"
    )
    pass "supervisor reconnect releases its lock and verifies routes outside the short supervisor deadline"
}

test_runtime_diagnostics_report_deployed_versions_and_policy() {
    (
        reset_runtime_paths
        local BASE_DIR="$TMP_ROOT/runtime-diagnostics" output
        mkdir -p "$BASE_DIR/.devcontainer"
        printf 'FROM debian:bookworm-slim\nARG XRAY_VERSION=v26.7.28\n' > "$BASE_DIR/.devcontainer/Dockerfile"
        timeout() { printf 'Xray 26.5.9 (Xray, Penetrates Everything.)\n'; }
        printf '{"policy":{"levels":{"0":{"connIdle":900,"uplinkOnly":2,"downlinkOnly":5}}},"outbounds":[{"tag":"direct","streamSettings":{"sockopt":{"tcpCongestion":"cubic"}}}]}' > "$CONFIG_FILE"
        kernel_tcp_value() { printf 'test-%s' "$1"; }
        output=$(runtime_tuning_diagnostics)
        [[ "$output" == *'installed=26.5.9 image_pin=26.7.28 state=different'* ]] || fail "diagnostics hid the deployed core mismatch"
        [[ "$output" == *'connIdle=900 uplinkOnly=2 downlinkOnly=5'* ]] || fail "diagnostics did not read policy from config"
        [[ "$output" == *'direct_congestion=cubic'* ]] || fail "diagnostics did not expose the configured socket override"
        [[ "$output" == *'default=test-tcp_congestion_control'* && "$output" == *'available=test-tcp_available_congestion_control'* ]] || fail "diagnostics did not report observed kernel settings"
        timeout() { printf 'Xray 26.7.28 (Xray, Penetrates Everything.)\n'; }
        [[ "$(runtime_tuning_diagnostics)" == *'state=matches'* ]] || fail "matching core version was flagged different"
        timeout() { return 1; }
        [[ "$(runtime_tuning_diagnostics)" == *'installed=unknown'* ]] || fail "missing core output was treated as a known version"
        printf 'not-json' > "$CONFIG_FILE"
        [[ "$(runtime_tuning_diagnostics)" == *'Config policy: unavailable'* ]] || fail "invalid config silently reported default policy"
        printf '{}' > "$CONFIG_FILE"
        output=$(runtime_tuning_diagnostics)
        [[ "$output" == *'uplinkOnly=core-default'* && "$output" == *'direct_congestion=kernel-default'* ]] || fail "omitted policy or sockopt was misreported"
    )
    pass "runtime diagnostics distinguish installed core, image pin, configured policy, and kernel state"
}

test_xhttp_extra_requires_one_complete_object
test_deadline_includes_exit_cleanup
test_deadline_preserves_status_without_a_status_write
test_degraded_probe_keeps_healthy_baseline_at_threshold
test_optional_legacy_bundle_failure_preserves_current_exports
test_codespace_bundle_rejects_missing_or_empty_links
test_codespace_bundle_primary_write_failure_still_fails
test_runtime_diagnostics_report_deployed_versions_and_policy
test_supervisor_reconnect_defers_long_route_verification
test_route_repair_preserves_active_sessions_for_all_callers
test_interactive_attach_defers_slow_route_settling
test_lock_claim_publishes_owner_atomically
test_xray_launch_uses_append_and_checks_log_preflight
test_dns_discovery_skips_retired_json_service
test_port_visibility_is_throttled
test_codespace_detection_uses_shared_environment_in_headless_ssh
test_codespace_detection_uses_local_metadata_when_gh_is_unauthenticated
test_port_domains_use_codespaces_forwarding_domain_env
test_run_gh_uses_shared_codespaces_token_when_shell_is_unauthenticated
test_runtime_lock_serializes_operations_and_allows_reentry
test_runtime_lock_distinguishes_background_subshells
test_stop_xray_succeeds_when_engine_is_already_stopped
test_port_visibility_cache_is_scoped_by_codespace_and_port
test_lifecycle_port_publish_forces_visibility_cache
test_startup_port_publish_retries_until_forwarding_is_registered
test_run_gh_sanitizes_invalid_timeout_env
test_background_start_reports_lock_failure_without_live_supervisor
test_background_start_cleans_up_when_pid_state_cannot_be_committed
test_background_heartbeat_reports_atomic_write_failure
test_background_restart_cannot_revive_a_stopped_generation
test_background_watchdog_recovers_dead_owned_supervisors
test_background_watchdog_reports_pid_commit_failure
test_background_stop_reports_failed_state_cleanup
test_background_heartbeat_cannot_override_visible_process_ownership
test_stale_temp_sweep_removes_only_old_owned_artifacts
test_logs_reset_when_script_code_changes
test_cached_route_order_uses_reliability_then_average_latency
test_cached_route_order_uses_recent_weighted_score
test_cached_route_order_does_not_overweight_tiny_reliability_delta
test_last_good_route_decays_before_breaking_ties
test_route_candidate_stats_track_average_and_success_rate
test_route_candidate_stats_reset_expired_target_history
test_route_candidate_stats_prune_expired_non_target_rows
test_xray_stats_use_inbound_counters_only
test_save_xray_stats_marks_active_traffic_when_counters_increase
test_atomic_write_handles_mktemp_failure
test_route_health_records_source_reason_and_recent_average
test_route_failure_reason_classifier
test_edge_origin_errors_enter_candidate_cooldown
test_xhttp_external_probe_uses_strict_tls_by_default
test_xhttp_probe_metrics_reports_curl_failure_reason
test_xhttp_options_requires_handler_success
test_legacy_http_400_cache_rows_are_never_exported
test_future_state_timestamps_are_treated_as_stale
test_future_deadlines_do_not_suppress_recovery
test_cached_route_order_prefers_pinned_route_then_latency_without_stats
test_route_monitor_default_and_hard_cap_are_wide_but_bounded
test_blacklisted_route_is_excluded_from_cached_exports
test_manual_route_candidates_are_validated_and_resettable
test_route_preferences_clear_matching_cooldowns
test_route_health_refresh_preserves_cache_when_all_candidates_are_cooled_down
test_route_health_refresh_preserves_cache_when_all_probes_are_unusable
test_route_health_refresh_mixes_provider_candidates_before_stale_cache_cap
test_route_health_refresh_does_not_let_unusable_cache_starve_builtins
test_route_refresh_backoff_preserves_cache_without_probe_storms
test_stale_route_cache_is_not_exported_after_hard_age
test_self_heal_defers_external_repairs_while_traffic_is_active
test_self_heal_preserves_idle_established_tunnels
test_self_heal_preserves_idle_established_ws_tunnels
test_self_heal_reasserts_optional_ws_visibility
test_self_heal_keeps_failure_count_when_route_repair_fails
test_port_listener_detection_does_not_require_sudo
test_deadline_terminates_descendant_processes
test_deadline_stops_term_resistant_writers
test_deadline_fails_closed_without_temp_storage
test_deadline_rejects_unverified_process_group
test_post_start_propagates_silent_start_failure
test_tcp_fast_open_requires_explicit_opt_in
test_existing_config_upgrade_disables_unrequested_origin_tfo
test_generate_config_respects_runtime_lock_for_entire_transaction
test_generate_config_failed_start_restores_previous_running_engine
test_generate_config_preserves_old_engine_when_replacement_never_launches
test_generate_config_rollback_keeps_runtime_available_when_uuid_cache_restore_fails
test_links_and_export_hash_use_active_config_uuid
test_active_uuid_rejects_invalid_existing_config
test_export_hash_tracks_active_config_xhttp_mode
test_route_preference_write_failures_return_failure
test_json_dns_ips_parses_formatted_provider_json
test_pinned_route_is_a_durable_candidate_source
test_cached_route_health_is_a_durable_candidate_source
test_dns_candidate_cache_reuses_fresh_provider_results
test_wide_dns_discovery_queries_multiple_ecs_subnets
test_batch_manual_route_import_extracts_valid_unique_ipv4s
test_last_known_state_scans_full_current_log
test_usable_fallback_ips_uses_fresh_cache
test_usable_fallback_ips_does_not_live_probe_cached_routes
test_usable_fallback_ips_does_not_fill_partial_cache_synchronously
test_usable_fallback_ips_fills_partial_fresh_cache
test_usable_fallback_ips_keeps_cached_routes_during_route_settling
test_usable_fallback_ips_keeps_cached_routes_without_opt_in
test_usable_fallback_ips_never_runs_live_probe_fallback
test_xhttp_config_path_is_cached_by_config_content
test_boot_status_helpers_record_silent_start_result
test_boot_status_reports_state_write_failure
test_generate_config_replaces_stale_no_config_boot_status
test_generate_config_keeps_success_when_boot_status_write_fails
test_generated_and_upgraded_configs_do_not_block_page_assets_as_ads
test_existing_config_upgrade_preserves_uuid_and_safe_handshake_floor
test_existing_xhttp_config_upgrade_accepts_all_client_modes
test_start_xray_restores_config_when_migrated_config_is_invalid
test_start_xray_preserves_engine_when_config_migration_fails
test_start_xray_restores_config_when_previous_engine_cannot_stop
test_start_xray_cleans_up_when_pid_state_cannot_be_committed
test_session_uptime_recovers_from_corrupt_state
test_quota_display_recovers_from_corrupt_state
test_generate_config_refreshes_expired_route_cache_before_exports
test_generate_config_keeps_previous_config_when_candidate_validation_fails
test_generate_config_candidate_file_keeps_json_suffix_for_xray_detection
test_generate_config_rolls_back_when_valid_candidate_cannot_start
test_config_exports_write_local_only_metadata
test_config_exports_report_write_failure
test_config_exports_report_metadata_write_failure
test_refresh_config_exports_reports_write_failure
test_config_exports_are_stable_client_artifacts
test_domain_link_export_can_be_disabled_for_blocked_networks
test_disabled_domain_link_retains_last_exports_when_no_ip_is_available
test_no_link_refresh_clears_exports_from_a_previous_uuid
test_failed_export_does_not_restore_exports_from_a_previous_uuid
test_ordered_links_reuse_fallback_ips_for_xhttp_and_ws_exports
test_refresh_config_exports_if_changed_skips_unchanged_inputs
test_refresh_config_exports_if_changed_repairs_missing_codespace_bundle
test_refresh_config_exports_updates_input_hash_after_direct_refresh
test_force_reconnect_skips_restart_when_identity_unknown
test_generated_links_follow_configured_xhttp_path
test_xhttp_mode_is_persistent_and_link_consistent
test_xhttp_mode_change_does_not_restart_auto_inbound
test_xhttp_mode_reports_export_failure
test_generated_links_use_active_config_mode_over_env_override
test_xhttp_mode_cache_avoids_reparsing_and_tracks_config_changes
test_xhttp_mode_cache_preserves_preferences_and_write_failure_fallback
test_xhttp_mode_cache_does_not_commit_a_concurrent_config_swap
test_custom_xhttp_extra_json_is_validated
test_websocket_fallback_is_advanced_opt_in
test_websocket_fallback_exports_separate_alpn_variants
test_websocket_fallback_persists_from_panel_state
test_websocket_front_domain_generates_cloudflare_link
test_websocket_fallback_adds_config_and_public_port
test_config_metadata_sanitizes_invalid_max_fallback_links
test_bench_json_reports_deterministic_budgets
test_low_overhead_mode_suppresses_info_logs
test_low_overhead_env_can_be_overridden_by_toggle
test_anti_sleep_preference_persists_across_headless_reconciliation
test_low_overhead_keeps_important_state_logs
test_latency_focus_mode_suppresses_noncritical_info_but_preserves_warnings
test_latency_focus_env_can_be_overridden_by_toggle
test_panel_modes_apply_real_performance_profiles
test_switching_performance_modes_reapplies_once
test_performance_profile_settings_are_available
test_xray_configtest_logs_prune_old_files
test_active_xray_log_rotation_preserves_open_descriptor
test_route_settling_history_records_summary
test_doctor_json_reports_probe_state
test_doctor_json_sanitizes_invalid_port
test_route_wait_requires_stable_usable_probes
test_route_wait_rejects_transient_single_success
test_route_wait_reasserts_port_once_after_persistent_404
test_route_wait_does_not_repair_a_recovering_route
test_recover_now_success_clears_nonfatal_port_public_failure
test_route_repair_attempts_are_cooldown_limited
test_recover_now_stops_after_engine_start_failure
test_recover_now_json_reports_ready_contract
test_recover_now_json_reports_settling_contract
test_recover_now_json_treats_followup_ready_probe_as_success
test_headless_start_prepares_listener_without_waiting_for_edge
test_headless_start_command_preserves_failures
test_generate_config_feedback_preserves_failure_status
test_headless_route_settle_records_background_ready_state
test_diagnostic_snapshot_writes_readable_history
test_diagnostic_log_rotation_keeps_readable_history
test_structured_log_jsonl_is_parseable_with_special_chars
test_support_bundle_redacts_sensitive_material
test_support_bundle_can_include_network_metadata_when_opted_in
test_support_bundle_handles_relative_log_dir
test_support_bundle_includes_rotated_logs
test_support_bundle_marks_unreadable_optional_logs
test_probe_variation_tracks_successes_and_preserves_legacy_rows
test_probe_variation_only_breaks_route_score_ties
test_menu_route_summary_is_quiet_and_cache_only
test_degraded_probe_queues_without_scanning_or_restarting
test_degraded_probe_resets_chain_on_outage_or_healthy_sample
test_degraded_refresh_forces_fresh_scan_outside_health_deadline
test_degraded_refresh_respects_backoff_and_retains_failed_exports
test_self_heal_integrates_degradation_without_disruption
test_route_quality_uses_export_snapshot_and_escapes_timestamps
test_route_quality_rejects_partial_json_and_parser_failures
test_route_quality_rejects_unbounded_numbers_and_invalid_ewma
test_updater_version_gate_preserves_same_version_fixes
test_update_audit_is_bounded_and_hides_source_credentials
test_updater_failed_replace_never_reports_applied
test_updater_preserves_edits_made_during_download
test_updater_success_stages_locally_and_preserves_permissions
test_updater_refuses_downgrade_end_to_end
test_updater_rejects_failed_download_even_if_candidate_is_valid_bash
test_updater_audits_failed_staging
test_xhttp_upload_tuning_is_reversible_and_bundle_compatible

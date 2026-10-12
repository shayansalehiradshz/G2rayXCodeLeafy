#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/g2ray.sh"
GITIGNORE="$ROOT_DIR/.gitignore"
GITATTRIBUTES="$ROOT_DIR/.gitattributes"
README="$ROOT_DIR/README.md"
DOCKERFILE="$ROOT_DIR/.devcontainer/Dockerfile"
CI_WORKFLOW="$ROOT_DIR/.github/workflows/static-tests.yml"
BEHAVIOR_TESTS="$ROOT_DIR/tests/g2ray_behavior_tests.sh"
REOPEN_SCRIPT="$ROOT_DIR/scripts/reopen-codespace.ps1"
POST_START_SCRIPT="$ROOT_DIR/scripts/post-start.sh"
WORKER_DIR="$ROOT_DIR/worker/codespace-waker"
WORKER_SCRIPT="$WORKER_DIR/src/index.js"
WORKER_README="$WORKER_DIR/README.md"
WORKER_WRANGLER_EXAMPLE="$WORKER_DIR/wrangler.toml.example"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

pass() {
    printf 'PASS: %s\n' "$1"
}

grep_fixed() {
    local pattern="$1" file="$2"
    grep -Fq -- "$pattern" "$file"
}

grep_regex() {
    local pattern="$1" file="$2"
    grep -Eq -- "$pattern" "$file"
}

test_wait_for_port_increment_is_set_e_safe() {
    if grep_fixed '(( i++ ))' "$SCRIPT"; then
        fail 'wait_for_port still uses post-increment, which exits under set -e when i is 0'
    fi
    grep_regex 'i=\$\(\(i \+ 1\)\)|\(\( \+\+i \)\)' "$SCRIPT" \
        || fail 'wait_for_port does not contain a set -e safe increment'
    pass 'wait_for_port increment is set -e safe'
}

test_process_management_uses_pid_file() {
    grep_fixed 'XRAY_PID_FILE=' "$SCRIPT" \
        || fail 'script does not define an Xray PID file'
    grep_fixed 'xray_pid_matches()' "$SCRIPT" \
        || fail 'script does not validate that a PID belongs to this Xray config'
    grep_fixed 'xray_pid_matches "$p"' "$SCRIPT" \
        || fail 'stop/status paths do not validate PID ownership before trusting the PID file'
    grep_fixed 'owned_xray_pids()' "$SCRIPT" \
        || fail 'script does not enumerate all owned Xray processes'
    grep_fixed 'xpid=$(owned_xray_pids | head -1 || true)' "$SCRIPT" \
        || fail 'resource stats do not prefer project-owned Xray PIDs'
    grep_fixed 'mapfile -t owned_pids' "$SCRIPT" \
        || fail 'stop_xray does not collect every owned Xray PID before stopping'
    grep_fixed 'for p in "${owned_pids[@]}"' "$SCRIPT" \
        || fail 'stop_xray does not stop every owned Xray PID'
    grep_fixed 'if ! stop_xray; then' "$SCRIPT" \
        || fail 'start_xray does not abort when the old owned listener cannot be stopped'
    grep_fixed '_atomic_write "$XRAY_PID_FILE" "$pid"' "$SCRIPT" \
        || fail 'start_xray does not atomically store the launched Xray PID'
    if grep_fixed 'pkill -x "xray"' "$SCRIPT" || grep_fixed 'pkill -9 -x "xray"' "$SCRIPT"; then
        fail 'script still kills every process named xray'
    fi
    if grep_fixed 'pgrep -x "xray"' "$SCRIPT"; then
        fail 'script still trusts a broad xray process lookup'
    fi
    pass 'process management is scoped through a PID file'
}

test_background_tasks_uses_owned_pid_file() {
    grep_fixed 'bg_tasks_running()' "$SCRIPT" \
        || fail 'script does not validate that the background PID belongs to a live g2ray process'
    grep_fixed 'bg_tasks_running "$p"' "$SCRIPT" \
        || fail 'start_background_tasks does not validate background PID ownership'
    grep_fixed 'BG_TASKS_LOCK_DIR=' "$SCRIPT" \
        || fail 'background supervisor startup is not protected by an atomic lock'
    grep_fixed 'BG_TASKS_TOKEN_FILE=' "$SCRIPT" \
        || fail 'background supervisor does not persist an ownership token'
    grep_fixed 'G2RAY_BG_TASK_TOKEN=' "$SCRIPT" \
        || fail 'background supervisor process is not tagged with an ownership token'
    grep_fixed 'SCRIPT_PATH=' "$SCRIPT" \
        || fail 'script does not track its actual running path'
    grep_fixed 'G2RAY_BG_TASK_TOKEN="$token" nohup bash "$SCRIPT_PATH" --background-supervisor' "$SCRIPT" \
        || fail 'background supervisor is not launched from the actual running script path'
    grep_fixed 'exec bash "$SCRIPT_PATH" --background-supervisor' "$SCRIPT" \
        || fail 'stale supervisor reexec does not use the actual running script path'
    grep_fixed 'if [[ "${1:-}" == "--background-supervisor" ]]; then' "$SCRIPT" \
        || fail 'script has no dedicated background supervisor entrypoint'
    if grep_fixed '_background_tasks </dev/null >/dev/null 2>&1 &' "$SCRIPT"; then
        fail 'background supervisor still runs as an in-process child of postStart/postAttach'
    fi
    grep_fixed '/proc/$p/environ' "$SCRIPT" \
        || fail 'background supervisor ownership is not verified against the live process environment'
    grep_fixed 'background_supervisor_recent_heartbeat()' "$SCRIPT" \
        || fail 'background supervisor diagnostics cannot recognize a fresh heartbeat'
    grep_fixed 'running="heartbeat"' "$SCRIPT" \
        || fail 'background supervisor diagnostics cannot distinguish a live heartbeat from a dead supervisor'
    if grep_fixed 'grep -Fq "g2ray.sh"' "$SCRIPT"; then
        fail 'background supervisor ownership still matches any process whose args contain g2ray.sh'
    fi
    pass 'background supervisor validates PID ownership'
}

test_control_plane_optimizations_are_present() {
    grep_fixed 'PORT_FORWARDING_DOMAIN=' "$SCRIPT" \
        || fail 'script does not honor the Codespaces port-forwarding domain suffix'
    grep_fixed 'GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN' "$SCRIPT" \
        || fail 'script does not read GitHub Codespaces forwarding-domain environment'
    grep_fixed 'LOCAL_PROBE_TIMEOUT_SEC=' "$SCRIPT" \
        || fail 'local XHTTP probe timeout is not separately configurable'
    grep_fixed 'EXTERNAL_PROBE_TIMEOUT_SEC=' "$SCRIPT" \
        || fail 'external XHTTP probe timeout is not separately configurable'
    grep_fixed 'refresh_config_exports_if_changed()' "$SCRIPT" \
        || fail 'background exports are not cache-gated by unchanged inputs'
    grep_fixed 'run_with_deadline "$G2RAY_SUPERVISOR_EXPORT_TIMEOUT_SEC" refresh_config_exports_if_changed' "$SCRIPT" \
        || fail 'background supervisor still regenerates exports without an unchanged-input guard'
    grep_fixed 'active_tunnel_recent()' "$SCRIPT" \
        || fail 'script has no active-traffic detector for suppressing heavy background work'
    grep_fixed 'if active_tunnel_recent; then' "$SCRIPT" \
        || fail 'background supervisor does not suppress heavy work during active traffic'
    grep_fixed 'ROUTE_REPAIR_COOLDOWN_SEC=' "$SCRIPT" \
        || fail 'route repair attempts do not have a cooldown'
    grep_fixed 'mark_route_repair_attempt_if_allowed' "$SCRIPT" \
        || fail 'route repair path does not mark/skip attempts through a cooldown helper'
    pass 'control-plane optimizations are present'
}

test_background_tasks_require_config() {
    grep_fixed '[[ -f "$CONFIG_FILE" ]] || continue' "$SCRIPT" \
        || fail 'background loop can still start Xray before config exists'
    if grep_fixed 'start_xray; wait_for_port' "$SCRIPT"; then
        fail 'menu paths still run start_xray without handling a failed start'
    fi
    grep_fixed 'start_xray >/dev/null 2>&1 && wait_for_port >/dev/null 2>&1' "$SCRIPT" \
        || fail 'force reconnect does not handle start_xray failure explicitly'
    pass 'background tasks skip Xray start until config exists'
}

test_recovery_hot_paths_are_bounded_and_validated() {
    grep_fixed 'run_with_deadline()' "$SCRIPT" \
        || fail 'script does not provide a reusable per-task deadline wrapper'
    grep_fixed 'run_with_deadline "$G2RAY_SUPERVISOR_SELF_HEAL_TIMEOUT_SEC" self_heal_once' "$SCRIPT" \
        || fail 'background self-heal is not deadline bounded'
    grep_fixed 'run_with_deadline "$G2RAY_SUPERVISOR_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health' "$SCRIPT" \
        || fail 'background route refresh is not deadline bounded'
    grep_fixed 'xray_validate_config()' "$SCRIPT" \
        || fail 'start_xray has no config-validation helper'
    grep_fixed 'run -test -c' "$SCRIPT" \
        || fail 'Xray config is not preflight-tested before launch'
    grep_fixed 'ulimit -n "${G2RAY_XRAY_FD_LIMIT:-65536}"' "$SCRIPT" \
        || fail 'Xray launch does not raise the file-descriptor ceiling'
    grep_fixed 'for _grace in 1 2 3 4 5 6 7 8 9 10' "$SCRIPT" \
        || fail 'stop_xray does not poll for graceful exit before SIGKILL'
    grep_fixed 'timeout 4 dig +time=2 +tries=1' "$SCRIPT" \
        || fail 'dig-based DNS discovery has no explicit timeout'
    grep_fixed 'timeout 4 getent hosts' "$SCRIPT" \
        || fail 'getent DNS discovery has no explicit timeout'
    grep_fixed 'ROUTE_STATS_MAX_AGE_SEC=' "$SCRIPT" \
        || fail 'route stats do not have a retention setting'
    grep_fixed 'log_event_cost|' "$SCRIPT" \
        || fail 'benchmark suite does not cover logging overhead'
    if grep_fixed 'Keepalive tick:' "$SCRIPT"; then
        fail 'anti-sleep keepalive still wakes every second for a display-only tick'
    fi
    pass 'recovery hot paths are bounded and startup is preflight-validated'
}

test_latency_focus_keeps_slow_route_export_refresh() {
    grep_fixed 'latency_focus_enabled' "$SCRIPT" \
        || fail 'latency focus helper is missing'
    awk '
        /if latency_focus_enabled; then/ { in_branch=1; saw_route=0; saw_export=0; next }
        in_branch && /run_with_deadline "\$G2RAY_SUPERVISOR_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health/ { saw_route=1 }
        in_branch && /run_with_deadline "\$G2RAY_SUPERVISOR_EXPORT_TIMEOUT_SEC" refresh_config_exports/ { saw_export=1 }
        in_branch && /continue/ {
            if (saw_route && saw_export) found=1
            in_branch=0
        }
        END { exit found ? 0 : 1 }
    ' "$SCRIPT" || fail 'latency focus mode still skips route health/export refresh forever'
    pass 'latency focus keeps a slow route/export refresh cadence'
}

test_interactive_route_refreshes_are_bounded() {
    grep_fixed 'G2RAY_INTERACTIVE_ROUTE_REFRESH_TIMEOUT_SEC=' "$SCRIPT" \
        || fail 'interactive route refresh timeout is not configurable'
    grep_fixed 'route_candidate_refresh_with_feedback()' "$SCRIPT" \
        || fail 'route candidate manager has no bounded foreground refresh helper'
    grep_fixed 'run_with_deadline "$G2RAY_INTERACTIVE_ROUTE_REFRESH_TIMEOUT_SEC" refresh_route_candidate_health' "$SCRIPT" \
        || fail 'foreground route refresh is not deadline bounded'
    if awk '
        /show_route_candidate_manager\(\)/ { in_fn=1; next }
        in_fn && /^}/ { in_fn=0 }
        in_fn && /^[[:space:]]*refresh_route_candidate_health[[:space:]]*>\/dev\/null/ { bad=1 }
        END { exit bad ? 0 : 1 }
    ' "$SCRIPT"; then
        fail 'route candidate manager still calls blocking route refresh directly'
    fi
    if awk '
        /show_live_monitor\(\)/ { in_fn=1; next }
        in_fn && /^}/ { in_fn=0 }
        in_fn && /^[[:space:]]*refresh_route_candidate_health[[:space:]]*>\/dev\/null/ { bad=1 }
        END { exit bad ? 0 : 1 }
    ' "$SCRIPT"; then
        fail 'live monitor still calls blocking route refresh directly'
    fi
    pass 'interactive route refreshes are bounded and user-visible'
}

test_supervisor_handles_resume_and_stale_code() {
    grep_fixed 'force_public_runtime_ports()' "$SCRIPT" \
        || fail 'supervisor has no lifecycle public-port reassertion helper'
    grep_fixed 'force_public_runtime_ports "supervisor_start"' "$SCRIPT" \
        || fail 'background supervisor does not force public ports on startup/resume'
    grep_fixed 'supervisor_reexec_if_stale()' "$SCRIPT" \
        || fail 'background supervisor cannot re-exec when the script changes on disk'
    grep_fixed 'bash -n "$SCRIPT_PATH"' "$SCRIPT" \
        || fail 'supervisor stale-code re-exec does not syntax-check the on-disk script first'
    grep_fixed 'supervisor_reexec_if_stale' "$SCRIPT" \
        || fail 'background loop does not periodically check for stale code'
    pass 'supervisor reasserts ports on resume and can pick up script updates'
}

test_stale_temp_sweep_is_present() {
    grep_fixed 'sweep_stale_temp_files()' "$SCRIPT" \
        || fail 'script has no stale temp-file sweeper'
    grep_fixed 'route_probe.??????' "$SCRIPT" \
        || fail 'stale sweeper does not cover route probe temp files'
    grep_fixed 'dns-resolve.??????' "$SCRIPT" \
        || fail 'stale sweeper does not cover DNS temp directories'
    grep_fixed 'sweep_stale_temp_files' "$SCRIPT" \
        || fail 'stale temp-file sweeper is never invoked'
    pass 'stale temp artifacts are swept on startup'
}

test_port_visibility_failures_are_handled() {
    if grep -Eq '^[[:space:]]*ensure_codespace_port_public[[:space:]]*>/dev/null 2>&1[[:space:]]*$' "$SCRIPT"; then
        fail 'bare ensure_codespace_port_public call can exit under set -e without a user-facing message'
    fi
    grep_fixed 'Could not set Codespaces port' "$SCRIPT" \
        || fail 'port-public failure does not produce an actionable user-facing warning'
    pass 'port visibility failures are handled explicitly'
}

test_self_update_is_opt_in() {
    grep_fixed 'G2RAY_AUTO_UPDATE' "$SCRIPT" \
        || fail 'self-update is not controlled by an opt-in environment variable'
    grep_fixed 'verify_update_candidate()' "$SCRIPT" \
        || fail 'self-update does not use a centralized candidate verifier'
    grep_fixed 'verify_update_candidate "$tmp"' "$SCRIPT" \
        || fail 'self-update does not verify the downloaded script before replacing itself'
    grep_fixed 'bash -n "$candidate"' "$SCRIPT" \
        || fail 'self-update verifier does not syntax-check the downloaded script before replacing itself'
    grep_fixed 'grep -Fq '\''detect_project_repo_default()'\''' "$SCRIPT" \
        || fail 'self-update verifier does not check for expected source markers'
    grep_fixed 'tracked_panel_has_local_changes()' "$SCRIPT" \
        || fail 'self-update does not define a tracked-script dirty-worktree guard'
    grep_fixed 'G2RAY_AUTO_UPDATE_FORCE' "$SCRIPT" \
        || fail 'self-update dirty-worktree guard cannot be explicitly overridden'
    if grep_fixed 'cat "$tmp" > "$0"' "$SCRIPT"; then
        fail 'self-update still writes directly over the running script'
    fi
    pass 'self-update is opt-in'
}

test_exit_trap_preserves_failures() {
    grep_fixed 'cleanup()' "$SCRIPT" \
        || fail 'script does not define an explicit cleanup trap'
    grep_fixed 'local code=$?' "$SCRIPT" \
        || fail 'cleanup trap does not preserve the triggering exit status'
    if grep_fixed "exit 0' EXIT INT TERM" "$SCRIPT"; then
        fail 'global trap still masks failures as successful exits'
    fi
    pass 'exit trap preserves failure status'
}

test_generated_files_are_ignored() {
    [[ -f "$GITIGNORE" ]] || fail '.gitignore is missing'
    for pattern in '/data/' '/logs/' '/configs.txt' '/configs-to-copy-for-mobile.txt' '/configs-subscription-base64.txt' '/configs-meta.json'; do
        grep_fixed "$pattern" "$GITIGNORE" || fail ".gitignore missing $pattern"
    done
    for pattern in '/configs-to-copy-for-mobile.txt.*' '/configs-subscription-base64.txt.*' '/configs-meta.json.*'; do
        grep_fixed "$pattern" "$GITIGNORE" || fail ".gitignore missing atomic temp pattern $pattern"
    done
    grep_fixed '/g2ray-support-*.tar.gz' "$GITIGNORE" \
        || fail '.gitignore missing support bundle archive pattern'
    pass 'generated runtime files are ignored'
}

test_support_bundle_has_safe_entrypoint() {
    grep_fixed 'create_support_bundle()' "$SCRIPT" \
        || fail 'script does not provide a support bundle creator'
    grep_fixed 'redact_sensitive_text()' "$SCRIPT" \
        || fail 'support bundle creator has no redaction helper'
    grep_fixed '--support-bundle' "$SCRIPT" \
        || fail 'script does not expose support bundle creation as a headless command'
    grep_fixed 'Sensitive VLESS links, UUIDs, bearer tokens, GitHub tokens, wake secrets, and network identifiers are redacted by default.' "$SCRIPT" \
        || fail 'support bundle metadata does not explain redaction'
    pass 'support bundle has safe headless entrypoint'
}

test_shell_files_are_lf_normalized() {
    local file
    [[ -f "$GITATTRIBUTES" ]] || fail '.gitattributes is missing'
    grep_fixed '*.sh text eol=lf' "$GITATTRIBUTES" \
        || fail '.gitattributes does not force shell scripts to LF'
    grep_fixed '*.ps1 text eol=lf' "$GITATTRIBUTES" \
        || fail '.gitattributes does not force PowerShell helper scripts to LF'
    grep_fixed '*.mjs text eol=lf' "$GITATTRIBUTES" \
        || fail '.gitattributes does not force Node ESM test scripts to LF'
    grep_fixed 'tests/*.sh text eol=lf' "$GITATTRIBUTES" \
        || fail '.gitattributes does not force test shell scripts to LF'
    while IFS= read -r file; do
        [[ -f "$ROOT_DIR/$file" ]] || continue
        if LC_ALL=C grep -q $'\r' "$ROOT_DIR/$file"; then
            fail "tracked script/helper file contains CRLF bytes: $file"
        fi
    done < <(git -C "$ROOT_DIR" ls-files '*.sh' '*.ps1' '*.mjs' 2>/dev/null)
    pass 'shell files are LF-normalized for Linux Bash'
}

test_panel_script_is_executable() {
    git -C "$ROOT_DIR" ls-files --stage -- g2ray.sh | grep -q '^100755 ' \
        || fail 'g2ray.sh is not tracked as executable, so ./g2ray.sh fails after git pull in Linux Codespaces'
    pass 'panel script is tracked executable'
}

test_xray_version_can_be_pinned() {
    grep_fixed 'ARG XRAY_VERSION=' "$DOCKERFILE" \
        || fail 'Dockerfile does not expose XRAY_VERSION for reproducible builds'
    if grep_fixed 'releases/latest/download' "$DOCKERFILE"; then
        fail 'Dockerfile still downloads Xray from latest'
    fi
    grep_fixed 'Xray-linux-64.zip.dgst' "$DOCKERFILE" \
        || fail 'Dockerfile does not download Xray digest metadata'
    grep_fixed 'sha256sum -c -' "$DOCKERFILE" \
        || fail 'Dockerfile does not verify Xray zip checksum before installing'
    pass 'Dockerfile supports pinned Xray version'
}

test_generated_config_uses_resilient_dns_fallback() {
    grep_fixed '"enableParallelQuery": true' "$SCRIPT" \
        || fail 'generated Xray DNS config does not race equivalent fallback resolvers'
    grep_fixed '"serveStale": true' "$SCRIPT" \
        || fail 'generated Xray DNS config does not serve cached answers during short resolver outages'
    grep_fixed '"serveExpiredTTL": 600' "$SCRIPT" \
        || fail 'generated Xray DNS config does not bound stale-answer lifetime'
    grep_fixed '"disableFallback": false' "$SCRIPT" \
        || fail 'generated Xray DNS config does not explicitly keep fallback enabled'
    grep_fixed '"disableFallbackIfMatch": false' "$SCRIPT" \
        || fail 'generated Xray DNS config could stop fallback after a matched resolver times out'
    grep_fixed '"servers": ["localhost", "168.63.129.16", "1.1.1.1", "1.0.0.1", "8.8.8.8"]' "$SCRIPT" \
        || fail 'generated Xray DNS config does not include Azure local DNS plus public fallbacks'
    grep_fixed '"queryStrategy": "UseIPv4"' "$SCRIPT" \
        || fail 'generated Xray DNS config does not force IPv4 to avoid broken-AAAA latency'
    if grep_fixed 'https+local://' "$SCRIPT"; then
        fail 'generated Xray DNS config still pays a DoH TLS round-trip per resolver instead of fast UDP/local lookups'
    fi
    grep_fixed '"domainStrategy": "AsIs"' "$SCRIPT" \
        || fail 'routing still forces a per-connection DNS lookup; use AsIs to cut connection latency'
    grep_fixed '"network": "udp", "port": "443"' "$SCRIPT" \
        || fail 'generated Xray config does not block outbound UDP/443 to avoid QUIC-over-TCP stalls'
    grep_fixed '"protocol": "blackhole",  "settings": { "response": { "type": "none" } }' "$SCRIPT" \
        || fail 'generic blackhole outbound still injects an HTTP payload into non-HTTP blocked traffic'
    if grep_fixed 'sniffQuic=true' "$SCRIPT"; then
        fail 'performance profiles still enable QUIC sniffing by default'
    fi
    grep_fixed 'upgrade_config_dns()' "$SCRIPT" \
        || fail 'script does not provide an in-place DNS migration for existing configs'
    grep_fixed '.dns = {' "$SCRIPT" \
        || fail 'existing config DNS migration does not replace the dns object'
    grep_fixed 'upgrade_config_dns >/dev/null 2>&1 || true' "$SCRIPT" \
        || fail 'start path does not apply DNS migration before launching Xray'
    grep_fixed 'config_dns refreshed' "$SCRIPT" \
        || fail 'DNS migration does not log when it refreshes an existing config'
    if grep_fixed '"domains": ["geosite:geolocation-!cn"]' "$SCRIPT"; then
        fail 'generated Xray DNS config still pins most lookups to a single domain-matched resolver before fallback'
    fi
    pass 'generated Xray config uses low-latency resilient DNS and AsIs routing'
}

test_daemon_logs_use_copytruncate_rotation() {
    grep_fixed '"$LOG_DIR/xray.log"|"$LOG_DIR/xray-error.log"' "$SCRIPT" \
        || fail 'active Xray daemon logs are not identified for copy-truncate rotation'
    grep_fixed 'rotate_log_file "$LOG_DIR/xray.log"' "$SCRIPT" \
        || fail 'background supervisor does not rotate the Xray runtime log'
    pass 'active Xray daemon logs use bounded copy-truncate rotation'
}

test_tcp_fast_open_is_gated_and_outbound_only() {
    grep_fixed 'tcp_fast_open_outbound_enabled()' "$SCRIPT" \
        || fail 'script does not provide a kernel-gated TCP Fast Open toggle'
    grep_fixed 'G2RAY_TCP_FAST_OPEN' "$SCRIPT" \
        || fail 'TCP Fast Open is not configurable via G2RAY_TCP_FAST_OPEN'
    grep_fixed '/proc/sys/net/ipv4/tcp_fastopen' "$SCRIPT" \
        || fail 'TCP Fast Open auto mode does not check kernel client support before enabling'
    grep_fixed 'tcpFastOpen\": true' "$SCRIPT" \
        || fail 'generated config does not apply tcpFastOpen via outbound sockopt'
    grep_fixed '"settings": { "domainStrategy": "UseIPv4" }${direct_sockopt}' "$SCRIPT" \
        || fail 'tcpFastOpen sockopt is not attached to the freedom (direct) outbound'
    grep_fixed 'G2RAY_TCP_FAST_OPEN' "$README" \
        || fail 'README does not document the TCP Fast Open toggle'
    grep_fixed 'TCP FastOpen:' "$SCRIPT" \
        || fail 'diagnostics do not surface TCP Fast Open status'
    pass 'TCP Fast Open is kernel-gated and applied only to the direct outbound'
}

test_connection_keepalive_and_halfclose_are_tuned() {
    grep_fixed 'TCP_KEEPALIVE_INTERVAL_SEC=' "$SCRIPT" \
        || fail 'TCP keepalive interval is not configurable'
    grep_fixed 'G2RAY_TCP_KEEPALIVE_INTERVAL' "$SCRIPT" \
        || fail 'TCP keepalive cannot be overridden via env'
    grep_fixed 'tcpKeepAliveInterval' "$SCRIPT" \
        || fail 'sockets do not enable TCP keepalive to clear stale/half-open connections'
    grep_fixed 'tcpKeepAliveIdle' "$SCRIPT" \
        || fail 'sockets do not set a TCP keepalive idle threshold'
    # The inbound stream must carry a keepalive sockopt block (edge<->Xray).
    grep_fixed '"sockopt": { "tcpKeepAliveInterval"' "$SCRIPT" \
        || fail 'inbound stream settings do not set keepalive sockopt'
    if grep_fixed 'uplinkOnly=1\ndownlinkOnly=2' "$SCRIPT"; then
        fail 'profiles still use the over-aggressive 1/2 half-close timeouts that prematurely drop idle connections'
    fi
    grep_fixed 'G2RAY_XHTTP_EXTRA_JSON' "$SCRIPT" \
        || fail 'exported links cannot carry an explicitly requested XHTTP extra object'
    grep_fixed 'G2RAY_XHTTP_LINK_TUNING' "$SCRIPT" \
        || fail 'measured XHTTP link tuning has no compatibility escape hatch'
    grep_fixed 'xhttp_link_tuning_enabled || return 0' "$SCRIPT" \
        || fail 'XHTTP link tuning cannot be disabled without changing the server'
    pass 'TCP keepalive, half-close timeouts, and reversible XHTTP link tuning are present'
}

test_xhttp_links_carry_measured_upload_tuning() {
    grep_fixed 'XHTTP_TUNED_EXTRA_JSON=' "$SCRIPT" \
        || fail 'built-in XHTTP tuning object is missing'
    grep_fixed '"scMaxEachPostBytes":60000,"scMinPostsIntervalMs":10' "$SCRIPT" \
        || fail 'XHTTP upload POST sizing changed without updating the measured contract'
    grep_fixed '"maxConnections":3' "$SCRIPT" \
        || fail 'XHTTP tuning no longer bounds connection concurrency'
    grep_fixed 'extra_json=$(xhttp_link_extra_json)' "$SCRIPT" \
        || fail 'XHTTP link rendering bypasses the reversible tuning preference'
    grep_fixed 'extra_param="&extra=$(url_encode_query_value "$extra_json")"' "$SCRIPT" \
        || fail 'XHTTP link extra is not URL-encoded'
    pass 'XHTTP links preserve the bounded upload tuning and encode its JSON'
}

test_performance_profile_is_persistent_and_bench_is_isolated() {
    grep_fixed 'PERFORMANCE_PROFILE_FILE=' "$SCRIPT" \
        || fail 'performance profile preference is not persisted to a file'
    grep_fixed 'set_performance_profile()' "$SCRIPT" \
        || fail 'script cannot persist a chosen performance profile'
    grep_fixed 'valid_performance_profile()' "$SCRIPT" \
        || fail 'script does not validate performance profile names'
    grep_fixed 'G2RAY_PRESERVE_UUID' "$SCRIPT" \
        || fail 'profile apply path does not preserve the existing UUID'
    grep_fixed '== "--profile" || "${1:-}" == "profile"' "$SCRIPT" \
        || fail 'script does not expose a profile subcommand'
    grep_fixed 'G2RAY_BENCH_WANTS_LIVE' "$SCRIPT" \
        || fail 'bench no longer gates live side effects behind an explicit flag'
    grep_fixed '== "--live"' "$SCRIPT" \
        || fail 'bench does not require --live before touching real data/logs'
    pass 'performance profile persists and bench is isolated unless --live is given'
}

test_generated_links_include_domain_and_ip_variants() {
    grep_fixed 'resolve_domain_ip()' "$SCRIPT" \
        || fail 'script does not provide a resolver for the Codespaces app domain'
    grep_fixed 'resolve_domain_ips()' "$SCRIPT" \
        || fail 'script does not provide a multi-IP resolver for the Codespaces app domain'
    grep_fixed 'G2RAY_EXTRA_FALLBACK_IPS' "$SCRIPT" \
        || fail 'script does not allow manually supplied fallback IPs'
    grep_fixed 'DEFAULT_FALLBACK_IPS=' "$SCRIPT" \
        || fail 'script does not provide built-in fallback IPs for DNS-blocked networks'
    for ip in 20.69.79.91 20.85.77.48 20.120.56.11 20.125.70.28 20.90.66.7 20.103.221.187 20.207.70.99; do
        grep_fixed "$ip" "$SCRIPT" \
            || fail "script does not include observed Codespaces fallback IP $ip"
    done
    grep_fixed 'dns.google/resolve' "$SCRIPT" \
        || fail 'script does not query Google DNS-over-HTTPS for fallback IPs'
    grep_fixed 'cloudflare-dns.com/dns-query' "$SCRIPT" \
        || fail 'script does not query Cloudflare DNS-over-HTTPS for fallback IPs'
    grep_fixed 'seen[$0]++' "$SCRIPT" \
        || fail 'script does not deduplicate fallback IP candidates'
    if grep_fixed '(\.[0-9]+){3}' "$SCRIPT"; then
        fail 'script still uses interval IPv4 regexes that can drop fallback IPs on non-GNU tools'
    fi
    grep_fixed '^[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*$' "$SCRIPT" \
        || fail 'script does not use a portable IPv4 filter for fallback IP candidates'
    grep_fixed 'generate_domain_link()' "$SCRIPT" \
        || fail 'script does not generate a domain-address link'
    grep_fixed 'generate_ip_link()' "$SCRIPT" \
        || fail 'script does not generate a resolved-IP fallback link'
    grep_fixed 'generate_ip_links()' "$SCRIPT" \
        || fail 'script does not generate multiple resolved-IP fallback links'
    grep_fixed '"$uuid" "$address" "$CODESPACES_EDGE_PORT" "$PORT_DOMAIN" "$PORT_DOMAIN"' "$SCRIPT" \
        || fail 'IP fallback link must preserve PORT_DOMAIN as SNI and host'
    grep_fixed 'generate_link_for_address "$PORT_DOMAIN"' "$SCRIPT" \
        || fail 'domain link must use PORT_DOMAIN as address, SNI, and host'
    grep_fixed '_VLESS_IPS=$(generate_ip_links)' "$SCRIPT" \
        || fail 'display/copy paths do not use multi-IP fallback generation'
    grep_fixed 'render_config_entry()' "$SCRIPT" \
        || fail 'display does not render each config as a separate copy-safe entry'
    grep_fixed 'render_config_qr()' "$SCRIPT" \
        || fail 'display does not render a QR code for each config entry'
    grep_fixed 'G2RAY_QR_MODE' "$SCRIPT" \
        || fail 'QR display mode is not configurable'
    grep_fixed 'local show_qr="${4:-false}"' "$SCRIPT" \
        || fail 'config entry renderer cannot hide non-primary QR codes'
    grep_fixed '[[ "$show_qr" == true ]]' "$SCRIPT" \
        || fail 'config entry renderer does not conditionally show QR codes'
    grep_fixed '_QR_MODE="${G2RAY_QR_MODE:-recommended}"' "$SCRIPT" \
        || fail 'config display does not default to recommended-only QR mode'
    grep_fixed '[[ "$_QR_MODE" == "all" || ( "$_QR_MODE" != "none" && $_INDEX -eq 1 ) ]]' "$SCRIPT" \
        || fail 'config display does not limit default QR rendering to the first recommended config'
    grep_fixed 'QR_DIR="$DATA_DIR/qr"' "$SCRIPT" \
        || fail 'script does not define a private QR export directory'
    grep_fixed 'write_config_qr_png()' "$SCRIPT" \
        || fail 'script does not export high-resolution QR PNG files'
    grep_fixed 'qrencode -m 4 -s 8 -t PNG -o "$png_file" "$link"' "$SCRIPT" \
        || fail 'QR PNG export does not use a scan-friendly quiet zone and scale'
    grep_fixed 'qrencode -m 2 -t UTF8 "$link"' "$SCRIPT" \
        || fail 'terminal QR renderer does not use a less cramped quiet-zone margin'
    grep_fixed 'High-res QR PNG' "$SCRIPT" \
        || fail 'config view does not show where the scan-friendly QR PNG was saved'
    grep_fixed 'QR PNG files are saved under' "$SCRIPT" \
        || fail 'config view does not summarize the QR PNG export location'
    grep_fixed 'Terminal zoom/theme can make QR scanning unreliable' "$SCRIPT" \
        || fail 'config view does not explain the copy-link fallback when QR scanning fails'
    grep_fixed 'printf '\''%s\n'\'' "$link"' "$SCRIPT" \
        || fail 'config links are not printed as raw copy-ready lines'
    grep_fixed 'write_config_exports_from_links()' "$SCRIPT" \
        || fail 'script does not provide an export writer for an existing displayed link list'
    grep_fixed 'write_config_exports_from_links "${_CONFIG_LINKS[@]}"' "$SCRIPT" \
        || fail 'display path does not export the exact links it shows'
    if grep_fixed 'printf '\''%s\n'\'' "${_CONFIG_LINKS[@]}" > "$MOBILE_CONFIG_FILE"' "$SCRIPT"; then
        fail 'display path still writes mobile config directly before recomputing exports'
    fi
    grep_fixed 'Recommended IP link' "$SCRIPT" \
        || fail 'display does not label the primary IP config clearly'
    grep_fixed 'Domain link' "$SCRIPT" \
        || fail 'display does not label the domain config clearly'
    if grep_fixed 'qrencode -m 2 -t ANSIUTF8 "$_VLESS_PRIMARY"' "$SCRIPT"; then
        fail 'display still renders only one large QR code'
    fi
    if grep_fixed 'sed "s/^/  ${WHITE}/;s/$/${NC}/"' "$SCRIPT"; then
        fail 'display still pipes colored links through sed, which corrupts copied configs'
    fi
    if grep_fixed '"$_VLESS_IP"' "$SCRIPT"; then
        fail 'display/copy paths still reference the old singular fallback variable'
    fi
    if grep_fixed 'address=$(resolve_domain_ip "$PORT_DOMAIN")' "$SCRIPT"; then
        fail 'IP fallback still falls back to the domain when no IP resolves'
    fi
    pass 'generated links include domain and multiple IP variants'
}

test_websocket_fallback_is_advanced_opt_in() {
    grep_fixed 'G2RAY_ENABLE_WS_FALLBACK' "$SCRIPT" \
        || fail 'WebSocket fallback is not guarded by an explicit advanced opt-in'
    grep_fixed 'WS_FALLBACK_FILE=' "$SCRIPT" \
        || fail 'WebSocket fallback preference is not persisted in panel state'
    grep_fixed 'WS_FALLBACK_DISABLED_FILE=' "$SCRIPT" \
        || fail 'WebSocket fallback disabled preference is not persisted'
    grep_fixed 'WS_FRONT_DOMAIN_FILE=' "$SCRIPT" \
        || fail 'Cloudflare WebSocket front domain is not persisted'
    grep_fixed 'enable_ws_fallback_mode()' "$SCRIPT" \
        || fail 'script cannot enable WebSocket fallback from the panel'
    grep_fixed 'disable_ws_fallback_mode()' "$SCRIPT" \
        || fail 'script cannot disable WebSocket fallback from the panel'
    grep_fixed 'toggle_ws_fallback_mode()' "$SCRIPT" \
        || fail 'script does not expose a reusable WebSocket fallback toggle'
    grep_fixed 'ws_fallback_in_config()' "$SCRIPT" \
        || fail 'script does not distinguish saved WS preference from generated config state'
    grep_fixed 'WS_PORT="${G2RAY_WS_PORT:-8443}"' "$SCRIPT" \
        || fail 'WebSocket fallback does not have a stable default port'
    grep_fixed 'WS_HEARTBEAT_PERIOD_SEC=30' "$SCRIPT" \
        || fail 'WebSocket fallback does not define a bounded idle heartbeat'
    grep_fixed '.streamSettings.wsSettings.heartbeatPeriod) = $ws_heartbeat' "$SCRIPT" \
        || fail 'existing WebSocket configs do not receive the idle heartbeat during safe migration'
    grep_fixed 'ws_fallback_enabled()' "$SCRIPT" \
        || fail 'script does not centralize WebSocket fallback enablement'
    grep_fixed '"network": "ws"' "$SCRIPT" \
        || fail 'generated config cannot add a WebSocket inbound'
    grep_fixed '"wsSettings": { "path": "$(json_escape "$ws_path")", "heartbeatPeriod": ${WS_HEARTBEAT_PERIOD_SEC} }' "$SCRIPT" \
        || fail 'WebSocket fallback path and idle heartbeat are not explicit in generated config'
    grep_fixed 'generate_ws_link_for_address()' "$SCRIPT" \
        || fail 'script cannot generate WebSocket fallback links'
    grep_fixed 'generate_ws_front_link()' "$SCRIPT" \
        || fail 'script cannot generate Cloudflare WebSocket front links'
    grep_fixed 'generate_ws_link_variants_for_address()' "$SCRIPT" \
        || fail 'script cannot generate h2/h1/blank WebSocket ALPN variants'
    grep_fixed 'ws_alpn_query_param()' "$SCRIPT" \
        || fail 'script does not centralize WebSocket ALPN query generation'
    if grep_fixed 'alpn=h2,http/1.1' "$SCRIPT"; then
        fail 'WebSocket links still export the old mixed ALPN value instead of separate variants'
    fi
    grep_fixed 'type=ws' "$SCRIPT" \
        || fail 'WebSocket fallback links do not use type=ws'
    grep_fixed 'ensure_codespace_port_public_for_port "$WS_PORT"' "$SCRIPT" \
        || fail 'WebSocket fallback port is not explicitly exposed when enabled'
    grep_fixed '19)${NC} Toggle WebSocket Fallback' "$SCRIPT" \
        || fail 'main menu does not expose WebSocket fallback as a panel toggle'
    grep_fixed '20)${NC} Cloudflare WS Front' "$SCRIPT" \
        || fail 'main menu does not expose Cloudflare WS front management'
    grep_fixed 'show_ws_front_manager()' "$SCRIPT" \
        || fail 'script does not provide a Cloudflare WS front manager'
    grep_fixed 'Cloudflare Free note: a simple proxied CNAME is not expected to be reliable here.' "$SCRIPT" \
        || fail 'panel does not warn Cloudflare Free users that simple proxied CNAME fronting is unreliable'
    grep_fixed 'This front mode needs Host/SNI override support; keep direct WS/XHTTP links otherwise.' "$SCRIPT" \
        || fail 'panel does not state that Cloudflare front mode needs Host/SNI override support'
    grep_fixed 'enabled, pending config regenerate' "$SCRIPT" \
        || fail 'diagnostics do not explain when saved WS preference has not been applied to config'
    grep_fixed '19) Toggle WebSocket Fallback' "$README" \
        || fail 'README does not document the WebSocket fallback panel toggle'
    grep_fixed '20) Cloudflare WS Front' "$README" \
        || fail 'README does not document the Cloudflare WS front panel manager'
    grep_fixed 'If your Cloudflare plan cannot set Host/SNI override rules' "$README" \
        || fail 'README does not warn that Cloudflare Free/simple CNAME fronting is unreliable'
    grep_fixed 'survives future panel sessions until you toggle it again' "$README" \
        || fail 'README does not document persistent WebSocket fallback state'
    grep_fixed 'XHTTP remains the recommended default' "$README" \
        || fail 'README does not keep XHTTP as the recommended default'
    pass 'WebSocket fallback is advanced opt-in'
}

test_terminal_branding_is_customized_red() {
    grep_fixed 'echo -e "${RED}${B}"' "$SCRIPT" \
        || fail 'logo banner is not rendered in red'
    grep_fixed 'Educational use only' "$SCRIPT" \
        || fail 'logo banner does not show educational-use notice'
    grep_fixed 'Customized' "$SCRIPT" \
        || fail 'logo banner does not show customized branding'
    pass 'terminal branding is red and educational-use labeled'
}

test_runtime_diagnostics_logging() {
    grep_fixed 'LOG_FILE=' "$SCRIPT" \
        || fail 'script does not define an application log file'
    grep_fixed 'log_event()' "$SCRIPT" \
        || fail 'script does not define structured runtime logging'
    grep_fixed 'fingerprint_secret()' "$SCRIPT" \
        || fail 'script does not define a helper for secret-safe fingerprints'
    grep_fixed 'fingerprint_secret "$uuid"' "$SCRIPT" \
        || fail 'config generation does not log a secret-safe UUID fingerprint'
    grep_fixed 'log_event INFO "xray launched' "$SCRIPT" \
        || fail 'start_xray does not log successful launches'
    grep_fixed 'log_event INFO "force_reconnect begin' "$SCRIPT" \
        || fail 'force reconnect does not log start of reconnect flow'
    grep_fixed '[[ "$code" != "000" && "$code" != "0" ]]' "$SCRIPT" \
        || fail 'external reconnect verification does not distinguish reachable HTTP edge responses from connection failure'
    grep_fixed 'verify_external edge_reachable=' "$SCRIPT" \
        || fail 'external reconnect verification does not log whether the Codespaces edge is reachable'
    grep_fixed 'local no_prompt="${1:-}" failed=0' "$SCRIPT" \
        || fail 'force reconnect does not track failure state'
    grep_fixed 'if stop_xray >/dev/null 2>&1; then' "$SCRIPT" \
        || fail 'force reconnect does not handle stop_xray failure explicitly'
    grep_fixed 'return "$failed"' "$SCRIPT" \
        || fail 'force reconnect does not return failure to the watchdog'
    if grep_fixed 'verify_external reachable=' "$SCRIPT"; then
        fail 'external reconnect verification still uses ambiguous reachable logging'
    fi
    grep_fixed 'fallback_candidates=' "$SCRIPT" \
        || fail 'resolver does not log mixed fallback IP candidates clearly'
    grep_fixed 'Fallback IP Candidates' "$SCRIPT" \
        || fail 'diagnostics still labels mixed fallback candidates as purely resolved IPs'
    grep_fixed 'includes resolved, manual, and built-in fallbacks' "$SCRIPT" \
        || fail 'diagnostics does not disclose that fallback IPs may include built-in candidates'
    grep_fixed 'curl_remote_ip()' "$SCRIPT" \
        || fail 'script does not use curl remote_ip as a resolver fallback'
    grep_fixed 'curl_remote_ip "$domain"' "$SCRIPT" \
        || fail 'multi-IP resolver does not include curl remote_ip fallback'
    grep_fixed 'curl_remote_ip "$domain" || true' "$SCRIPT" \
        || fail 'curl remote_ip fallback can abort resolver when no remote IP is found'
    grep_fixed 'health_probe()' "$SCRIPT" \
        || fail 'script does not define a periodic health probe'
    grep_fixed 'log_event INFO "health engine=' "$SCRIPT" \
        || fail 'health probe does not log engine/listener/public endpoint state'
    grep_fixed 'health_probe >/dev/null 2>&1' "$SCRIPT" \
        || fail 'background supervisor does not run periodic health probes'
    grep_fixed 'refresh_config_exports()' "$SCRIPT" \
        || fail 'script does not define a config export refresher'
    grep_fixed 'SUBSCRIPTION_FILE=' "$SCRIPT" \
        || fail 'script does not define a base64 subscription export file'
    grep_fixed 'generate_ordered_links()' "$SCRIPT" \
        || fail 'script does not generate a stable ordered failover config list'
    grep_fixed 'base64 | tr -d' "$SCRIPT" \
        || fail 'script does not generate a single-line base64 subscription export'
    grep_fixed 'refresh_config_exports >/dev/null 2>&1' "$SCRIPT" \
        || fail 'background supervisor does not refresh exported fallback configs'
    grep_fixed 'self_heal_once()' "$SCRIPT" \
        || fail 'script does not define a self-healing watchdog pass'
    grep_fixed 'self_heal_once >/dev/null 2>&1' "$SCRIPT" \
        || fail 'background supervisor does not run the self-healing watchdog'
    grep_fixed 'reason=listener_closed' "$SCRIPT" \
        || fail 'watchdog does not restart when the listener is closed'
    grep_fixed 'reason=xray_stopped' "$SCRIPT" \
        || fail 'watchdog does not restart when Xray is stopped'
    grep_fixed 'edge_unreachable' "$SCRIPT" \
        || fail 'watchdog does not detect an unreachable Codespaces edge'
    grep_fixed 'force_reconnect --no-prompt' "$SCRIPT" \
        || fail 'watchdog does not force reconnect when the Codespaces edge is unreachable'
    grep_fixed 'show_diagnostics()' "$SCRIPT" \
        || fail 'script does not provide a diagnostics view'
    grep_fixed '14)${NC} Diagnostics' "$SCRIPT" \
        || fail 'menu does not expose the diagnostics view'
    grep_fixed '14) show_diagnostics' "$SCRIPT" \
        || fail 'case statement does not route to diagnostics view'
    pass 'runtime diagnostics logging is present'
}

test_runtime_control_paths_are_hardened() {
    grep_fixed 'XRAY_PORT="${XRAY_PORT:-443}"' "$SCRIPT" \
        || fail 'XRAY_PORT documentation says it is configurable, but script still hard-codes 443'
    grep_fixed 'valid_codespace_name()' "$SCRIPT" \
        || fail 'codespace detection does not validate candidate names'
    grep_fixed '[[ "$name" != "null" ]]' "$SCRIPT" \
        || fail 'codespace detection can accept literal null as a real name'
    grep_fixed '/workspaces/.codespaces/shared/environment-variables.json' "$SCRIPT" \
        || fail 'headless Codespace detection does not read the shared Codespaces environment file'
    grep_fixed 'detect_codespace_name_from_waker_metadata' "$SCRIPT" \
        || fail 'headless Codespace detection does not fall back to saved local metadata'
    grep_fixed 'codespace_shared_github_token' "$SCRIPT" \
        || fail 'headless gh calls do not load the shared Codespaces token'
    grep_fixed 'env "${token_env[@]}" GH_PROMPT_DISABLED=1' "$SCRIPT" \
        || fail 'run_gh does not pass the shared Codespaces token into gh safely'
    if grep_fixed 'GH_FORCE_TTY=0' "$SCRIPT"; then
        fail 'run_gh sets GH_FORCE_TTY=0, but any GH_FORCE_TTY value forces terminal output'
    fi
    grep_fixed 'xray_listener_ready()' "$SCRIPT" \
        || fail 'engine readiness does not verify the Xray/XHTTP listener, only an open port'
    grep_fixed 'while ! xray_listener_ready && (( i < 15 )); do' "$SCRIPT" \
        || fail 'wait_for_port still succeeds on any listener bound to the port'
    grep_fixed 'prepare_headless_runtime "silent_start"' "$SCRIPT" \
        || fail '--silent-start does not prepare the local engine before asynchronous route settling'
    grep_fixed 'wait_for_xhttp_route_ready "silent_start_verify" "$G2RAY_SILENT_START_VERIFY_SEC"' "$SCRIPT" \
        || fail '--silent-start declares ready without stable route verification'
    grep_fixed 'start_headless_route_settling_monitor "silent_start"' "$SCRIPT" \
        || fail '--silent-start does not launch bounded asynchronous route settling'
    grep_fixed 'if [[ "${1:-}" == "--headless-route-settle" ]]; then' "$SCRIPT" \
        || fail 'script has no dedicated background route-settling entrypoint'
    grep_fixed 'G2RAY_ROUTE_SETTLE_TOKEN="$token" nohup bash "$SCRIPT_PATH" --headless-route-settle' "$SCRIPT" \
        || fail 'route-settling monitor is not detached from the Codespaces lifecycle command'
    grep_fixed 'write_boot_status "route_settling" "silent_start"' "$SCRIPT" \
        || fail '--silent-start does not persist route-settling boot status'
    grep_fixed 'if stop_xray; then' "$SCRIPT" \
        || fail 'interactive Stop Engine path does not handle stop_xray failure'
    pass 'runtime control paths are hardened'
}

test_startup_does_not_reconnect_healthy_runtime() {
    grep_fixed 'ensure_runtime_ready()' "$SCRIPT" \
        || fail 'script does not provide a health-gated startup path'
    grep_fixed 'runtime_ready reason=${reason} engine=running' "$SCRIPT" \
        || fail 'startup readiness path does not log when it reuses a healthy engine'
    grep_fixed 'action=skip_reconnect' "$SCRIPT" \
        || fail 'startup readiness path does not explicitly skip reconnects when healthy'
    if awk '
        /if \[\[ "\$\{1:-\}" == "--silent-start" \]\]; then/ { in_block=1 }
        in_block && /^[[:space:]]*fi[[:space:]]*$/ { exit }
        in_block && /stop_xray/ { bad=1; exit }
        END { exit bad ? 0 : 1 }
    ' "$SCRIPT"; then
        fail '--silent-start still stops Xray unconditionally'
    fi
    grep_fixed 'ensure_runtime_ready "interactive_attach" >/dev/null 2>&1 || true' "$SCRIPT" \
        || fail 'interactive attach does not use health-gated startup'
    if grep_fixed 'force_reconnect --no-prompt || true' "$SCRIPT"; then
        fail 'interactive attach still force-reconnects existing configs'
    fi
    grep_fixed 'wait_for_xhttp_route_ready()' "$SCRIPT" \
        || fail 'startup path does not wait for app.github.dev route readiness after resume'
    grep_fixed 'G2RAY_ROUTE_WAIT_SEC' "$SCRIPT" \
        || fail 'startup route wait is not configurable'
    grep_fixed 'runtime_ready reason=${reason} route_wait_timeout' "$SCRIPT" \
        || fail 'startup route wait timeout is not logged for troubleshooting'
    grep_fixed 'route_wait_repair attempt=${attempt}' "$SCRIPT" \
        || fail 'startup route wait does not perform a cooldown-gated port-route repair'
    pass 'startup paths do not reconnect a healthy runtime'
}

test_interactive_attach_readies_runtime_before_background_watchdog() {
    awk '
        /check_for_updates "\$@"/ { in_main=1; next }
        in_main && /while true; do/ { exit }
        in_main && /ensure_runtime_ready "interactive_attach"/ && !ready_line { ready_line=NR }
        in_main && /start_background_tasks/ && !bg_line { bg_line=NR }
        END { exit !(ready_line && bg_line && ready_line < bg_line) }
    ' "$SCRIPT" || fail 'interactive attach can start the background self-heal watchdog before runtime readiness, causing competing Xray starts'
    grep_fixed 'start_background_tasks || log_event WARN "background supervisor_start_deferred reason=interactive_attach"' "$SCRIPT" \
        || fail 'interactive attach can exit the panel when background supervisor startup is transiently deferred'
    pass 'interactive attach readies runtime before background watchdog'
}

test_self_heal_uses_reconnect_backoff() {
    grep_fixed 'EDGE_BAD_COUNT_FILE=' "$SCRIPT" \
        || fail 'self-heal does not persist edge failure streaks'
    grep_fixed 'EDGE_RECONNECT_STAMP_FILE=' "$SCRIPT" \
        || fail 'self-heal does not persist reconnect cooldown timestamps'
    grep_fixed 'SELF_HEAL_RECONNECT_COOLDOWN_SEC=' "$SCRIPT" \
        || fail 'self-heal reconnect cooldown is not configurable'
    grep_fixed 'edge_unreachable code=${code:-0} bad_count=${bad_count} action=observe' "$SCRIPT" \
        || fail 'self-heal does not observe transient edge failures before reconnecting'
    grep_fixed 'edge_unreachable code=${code:-0} bad_count=${bad_count} action=force_reconnect' "$SCRIPT" \
        || fail 'self-heal does not log thresholded force reconnects'
    if grep_fixed 'edge_unreachable code=${code:-0} action=force_reconnect' "$SCRIPT"; then
        fail 'self-heal still force-reconnects on a single edge failure'
    fi
    pass 'self-heal reconnects are thresholded and cooled down'
}

test_probe_and_gh_commands_are_bounded() {
    grep_fixed 'curl_http_code()' "$SCRIPT" \
        || fail 'script has no helper to normalize curl transport failures'
    grep_fixed 'printf '\''0'\''' "$SCRIPT" \
        || fail 'curl_http_code does not normalize failed transports to a single 0'
    if grep_fixed 'w "%{http_code}" "https://${PORT_DOMAIN}" 2>/dev/null || echo "0"' "$SCRIPT"; then
        fail 'raw curl http_code probes can still emit 0000 on transport failure'
    fi
    grep_fixed 'run_gh()' "$SCRIPT" \
        || fail 'script has no bounded gh helper'
    grep_fixed 'gh_timeout_seconds()' "$SCRIPT" \
        || fail 'gh helper has no timeout sanitizer'
    grep_fixed 'timeout "$(gh_timeout_seconds)" gh' "$SCRIPT" \
        || fail 'gh helper does not bound GitHub CLI calls with sanitized timeout'
    grep_fixed 'run_gh codespace list --limit 1 --json name --jq' "$SCRIPT" \
        || fail 'codespace name detection still bypasses the bounded gh helper'
    grep_fixed 'ensure_codespace_port_public_for_port()' "$SCRIPT" \
        || fail 'port visibility does not use a reusable bounded helper'
    grep_fixed 'run_gh codespace ports visibility "${port}:public" -c "$CODESPACE_NAME"' "$SCRIPT" \
        || fail 'port visibility still bypasses the bounded gh helper'
    grep_fixed 'ensure_codespace_port_public_for_port "$XRAY_PORT"' "$SCRIPT" \
        || fail 'primary XHTTP port visibility wrapper is missing'
    grep_fixed 'publish_runtime_ports_with_retry()' "$SCRIPT" \
        || fail 'startup port publication has no bounded retry helper'
    grep_fixed 'publish_runtime_ports_with_retry "route_settle_start"' "$SCRIPT" \
        || fail 'headless route settling does not retry port publication after registration delays'
    grep_fixed 'run_gh codespace ports -c "$CODESPACE_NAME"' "$SCRIPT" \
        || fail 'diagnostics port query still bypasses the bounded gh helper'
    pass 'curl probes and gh calls are bounded'
}

test_diagnostics_show_latency_and_supervisor_state() {
    grep_fixed 'xhttp_probe_metrics()' "$SCRIPT" \
        || fail 'diagnostics cannot measure XHTTP probe latency'
    if grep -Eq 'read -r [^[:space:]]+ [^[:space:]]+ < <\(xhttp_probe_metrics' "$SCRIPT"; then
        fail 'xhttp_probe_metrics callers must consume or discard the route reason field to keep JSON latency numeric'
    fi
    grep_fixed 'CODESPACES_EDGE_PORT="${G2RAY_CODESPACES_EDGE_PORT:-443}"' "$SCRIPT" \
        || fail 'script does not define the external Codespaces HTTPS edge port separately from the internal Xray port'
    grep_fixed 'url="https://${PORT_DOMAIN}:${CODESPACES_EDGE_PORT}${path}"' "$SCRIPT" \
        || fail 'external XHTTP probes do not include the Codespaces edge port in the URL, so fallback IP probes can test the wrong route'
    grep_fixed '--resolve "${PORT_DOMAIN}:${CODESPACES_EDGE_PORT}:${address}"' "$SCRIPT" \
        || fail 'fallback XHTTP probes do not bind --resolve to the same edge port used by the URL'
    grep_fixed 'xhttp_probe_ms=' "$SCRIPT" \
        || fail 'health/reconnect logs do not include XHTTP probe latency'
    grep_fixed 'BG_TASKS_HEARTBEAT_FILE=' "$SCRIPT" \
        || fail 'background supervisor heartbeat file is missing'
    grep_fixed 'background_supervisor_status()' "$SCRIPT" \
        || fail 'diagnostics cannot summarize background supervisor state'
    grep_fixed 'Background Supervisor' "$SCRIPT" \
        || fail 'diagnostics do not show background supervisor state'
    grep_fixed 'Fallback Route Probes' "$SCRIPT" \
        || fail 'diagnostics do not probe fallback route latency'
    grep_fixed 'ms' "$SCRIPT" \
        || fail 'diagnostics do not display latency in milliseconds'
    pass 'diagnostics show probe latency and supervisor state'
}

test_diagnostics_show_self_heal_state() {
    grep_fixed 'self_heal_state_summary()' "$SCRIPT" \
        || fail 'diagnostics cannot summarize self-heal counters'
    grep_fixed 'Self-Heal State' "$SCRIPT" \
        || fail 'diagnostics do not show self-heal state'
    grep_fixed 'route_bad=' "$SCRIPT" \
        || fail 'self-heal summary omits route failure streak'
    grep_fixed 'edge_bad=' "$SCRIPT" \
        || fail 'self-heal summary omits edge failure streak'
    grep_fixed 'cooldown_remaining=' "$SCRIPT" \
        || fail 'self-heal summary omits reconnect cooldown'
    pass 'diagnostics show self-heal state'
}

test_diagnostics_show_last_known_state() {
    grep_fixed 'last_known_state_summary()' "$SCRIPT" \
        || fail 'diagnostics cannot summarize the last known failure/repair/export state'
    grep_fixed 'Last Known State' "$SCRIPT" \
        || fail 'diagnostics do not show the last known state section'
    grep_fixed 'Last failure :' "$SCRIPT" \
        || fail 'last known state omits the last failure line'
    grep_fixed 'Last repair  :' "$SCRIPT" \
        || fail 'last known state omits the last repair line'
    grep_fixed 'Last export  :' "$SCRIPT" \
        || fail 'last known state omits the last export line'
    grep_fixed 'route_unusable|edge_unreachable|engine_not_ready|started_route_unusable|started_route_still_unusable|port_public_failed|launch_failed|timeout|failed' "$SCRIPT" \
        || fail 'last known failure summary does not include known failure markers'
    pass 'diagnostics show last known state'
}

test_diagnostics_show_resume_gap_state() {
    grep_fixed 'RESUME_GAP_FILE=' "$SCRIPT" \
        || fail 'script does not persist detected Codespaces resume gaps'
    grep_fixed 'record_resume_gap()' "$SCRIPT" \
        || fail 'script cannot record a supervisor heartbeat gap on startup'
    grep_fixed 'resume_gap_summary()' "$SCRIPT" \
        || fail 'diagnostics cannot summarize the last resume gap'
    grep_fixed 'Resume Gap' "$SCRIPT" \
        || fail 'diagnostics do not show resume gap state'
    grep_fixed 'resume_gap reason=${reason}' "$SCRIPT" \
        || fail 'resume gap detection does not log the startup reason'
    grep_fixed 'record_resume_gap "$reason"' "$SCRIPT" \
        || fail 'runtime readiness does not record resume gaps before healing'
    grep_fixed 'ensure_runtime_ready "interactive_attach" >/dev/null 2>&1 || true' "$SCRIPT" \
        || fail 'interactive attach does not enter the resume-gap-aware runtime readiness path'
    pass 'diagnostics show resume gap state'
}

test_local_reopen_helper_is_documented() {
    [[ -f "$REOPEN_SCRIPT" ]] || fail 'local Codespace reopen helper is missing'
    grep_fixed '--method POST "/user/codespaces/$Name/start"' "$REOPEN_SCRIPT" \
        || fail 'reopen helper does not use the official Codespaces start API'
    grep_fixed 'HTTP 402' "$REOPEN_SCRIPT" \
        || fail 'reopen helper does not explain quota/payment blocked starts'
    grep_fixed 'gh codespace code -c $Name' "$REOPEN_SCRIPT" \
        || fail 'reopen helper does not open the restarted Codespace in VS Code'
    grep_fixed 'scripts/reopen-codespace.ps1' "$README" \
        || fail 'README does not document the local reopen helper'
    grep_fixed 'Default idle timeout' "$README" \
        || fail 'README does not document setting the Codespaces idle timeout'
    grep_fixed '240 minutes' "$README" \
        || fail 'README does not recommend the maximum supported idle timeout'
    pass 'local reopen helper is documented'
}

test_cloudflare_worker_waker_is_safe_to_publish() {
    [[ -f "$WORKER_SCRIPT" ]] || fail 'Cloudflare Worker waker source is missing'
    [[ -f "$WORKER_README" ]] || fail 'Cloudflare Worker waker README is missing'
    [[ -f "$WORKER_WRANGLER_EXAMPLE" ]] || fail 'Cloudflare Worker wrangler example is missing'
    grep_fixed 'worker/codespace-waker/.dev.vars*' "$GITIGNORE" \
        || fail 'Worker local dev secrets are not ignored'
    grep_fixed 'worker/codespace-waker/.env' "$GITIGNORE" \
        || fail 'Worker local .env secrets are not ignored'
    grep_fixed 'worker/codespace-waker/.env.*' "$GITIGNORE" \
        || fail 'Worker local environment-specific .env secrets are not ignored'
    grep_fixed 'worker/codespace-waker/wrangler.toml' "$GITIGNORE" \
        || fail 'Worker local wrangler config is not ignored'
    grep_fixed 'worker/codespace-waker/node_modules/' "$GITIGNORE" \
        || fail 'Worker local node_modules are not ignored'
    grep_fixed 'worker/codespace-waker/.wrangler/' "$GITIGNORE" \
        || fail 'Worker local Wrangler cache is not ignored'
    [[ -f "$WORKER_DIR/package-lock.json" ]] \
        || fail 'Worker npm lockfile is missing, so Wrangler installs can drift'
    node -e '
      const value = require(process.argv[1]).devDependencies?.wrangler || "";
      if (!/^\d+\.\d+\.\d+$/.test(value)) process.exit(1);
    ' "$WORKER_DIR/package.json" \
        || fail 'Worker package.json does not pin Wrangler to an exact version'
    grep_fixed 'env[entry.tokenBinding]' "$WORKER_SCRIPT" \
        || fail 'Worker does not resolve the selected Codespace GitHub token from Cloudflare secret env'
    grep_fixed 'env.WAKE_SECRET' "$WORKER_SCRIPT" \
        || fail 'Worker does not require a separate wake secret'
    grep_fixed 'authorization: `Bearer ${token}`' "$WORKER_SCRIPT" \
        || fail 'Worker does not call GitHub with a bearer token'
    grep_fixed '/user/codespaces/${encodeURIComponent(name)}/start' "$WORKER_SCRIPT" \
        || fail 'Worker does not call the Codespaces start endpoint'
    grep_fixed 'reason: "quota_or_billing_blocked"' "$WORKER_SCRIPT" \
        || fail 'Worker does not classify HTTP 402 quota/billing failures'
    grep_fixed 'githubErrorDetail(body)' "$WORKER_SCRIPT" \
        || fail 'Worker does not redact GitHub error response details'
    grep_fixed 'idle_timeout_minutes:' "$WORKER_SCRIPT" \
        || fail 'Worker success response does not retain useful redacted status fields'
    grep_fixed 'probeCodespaceRoutes(name, routePorts, env)' "$WORKER_SCRIPT" \
        || fail 'Worker does not wait for the selected Codespace XHTTP route after start'
    grep_fixed 'probeCodespaceRoutes(codespaceName, context.routePorts, env)' "$WORKER_SCRIPT" \
        || fail 'Worker health check does not use stable selected-Codespace route readiness'
    grep_fixed 'route_ready: routeChecked' "$WORKER_SCRIPT" \
        || fail 'Worker route_ready is not derived from stable route probe state'
    grep_fixed ': status.ok && checkRoute' "$WORKER_SCRIPT" \
        || fail 'Worker does not distinguish an unavailable Codespace from an explicitly skipped route check'
    grep_fixed 'url.searchParams.get("route") !== "false"' "$WORKER_SCRIPT" \
        || fail 'Worker health API does not support a cheap route-skip status check'
    grep_fixed 'method: "OPTIONS"' "$WORKER_SCRIPT" \
        || fail 'Worker route readiness probe does not use the XHTTP OPTIONS probe'
    grep_fixed 'route_ready:' "$WORKER_SCRIPT" \
        || fail 'Worker response does not report route readiness'
    grep_fixed 'ROUTE_WAIT_MS' "$WORKER_SCRIPT" \
        || fail 'Worker route readiness wait is not bounded'
    grep_fixed 'wake_secret' "$WORKER_SCRIPT" \
        || fail 'Worker browser form cannot submit the wake secret'
    grep_fixed 'wrangler secret put GITHUB_TOKEN' "$WORKER_README" \
        || fail 'Worker README does not instruct storing GitHub token as a secret'
    grep_fixed 'wrangler secret put WAKE_SECRET' "$WORKER_README" \
        || fail 'Worker README does not instruct storing wake secret as a secret'
    grep_fixed 'Cloudflare Worker Waker' "$README" \
        || fail 'root README does not mention the Cloudflare Worker waker'
    local gh_prefix='ghp''_'
    local pat_prefix='github''_pat_'
    for file in "$WORKER_SCRIPT" "$WORKER_README" "$WORKER_WRANGLER_EXAMPLE"; do
        if grep_regex "${gh_prefix}[A-Za-z0-9_]{20,}|${pat_prefix}[A-Za-z0-9_]{20,}" "$file" 2>/dev/null; then
            fail 'Worker files appear to contain a GitHub token'
        fi
    done
    pass 'Cloudflare Worker waker is safe to publish'
}

test_worker_dashboard_and_history_features() {
    grep_fixed 'renderDashboard()' "$WORKER_SCRIPT" \
        || fail 'Worker does not render a mobile dashboard page'
    grep_fixed 'Start Codespace' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not expose a clear start button'
    grep_fixed 'Check Health' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not expose a health check action'
    grep_fixed '/api/wake' "$WORKER_SCRIPT" \
        || fail 'Worker does not expose a dashboard wake API'
    grep_fixed '/api/health' "$WORKER_SCRIPT" \
        || fail 'Worker does not expose a dashboard health API'
    grep_fixed '/api/history' "$WORKER_SCRIPT" \
        || fail 'Worker does not expose a dashboard history API'
    grep_fixed 'getCodespaceStatus(codespaceName, context.token, env)' "$WORKER_SCRIPT" \
        || fail 'Worker health API does not query the selected GitHub Codespace state'
    grep_fixed 'recordHistory(env, {' "$WORKER_SCRIPT" \
        || fail 'Worker does not persist wake/health events when KV is configured'
    grep_fixed 'env.WAKER_KV' "$WORKER_SCRIPT" \
        || fail 'Worker does not support optional KV-backed history'
    grep_fixed 'HISTORY_LIMIT' "$WORKER_SCRIPT" \
        || fail 'Worker history is not bounded'
    grep_fixed 'sendNotifications(env, event)' "$WORKER_SCRIPT" \
        || fail 'Worker does not offer optional notification hooks'
    grep_fixed 'DISCORD_WEBHOOK_URL' "$WORKER_SCRIPT" \
        || fail 'Worker does not support Discord webhook notifications'
    grep_fixed 'TELEGRAM_BOT_TOKEN' "$WORKER_SCRIPT" \
        || fail 'Worker does not support Telegram bot notifications'
    grep_fixed 'TELEGRAM_CHAT_ID' "$WORKER_SCRIPT" \
        || fail 'Worker does not support Telegram chat routing'
    grep_fixed 'GitHub token rejected or expired' "$WORKER_SCRIPT" \
        || fail 'Worker UI/API does not warn clearly about rejected or expired GitHub tokens'
    grep_fixed 'reason: "codespace_start_accepted"' "$WORKER_SCRIPT" \
        || fail 'Worker cold wake does not return explicit accepted progress'
    grep_fixed 'github_wait_attempts: 0' "$WORKER_SCRIPT" \
        || fail 'Worker cold wake still holds the mobile request open for GitHub state polling'
    grep_fixed 'isRouteStatusUsable(res.status)' "$WORKER_SCRIPT" \
        || fail 'Worker route readiness does not share the panel status classifier'
    grep_fixed 'return status === 200;' "$WORKER_SCRIPT" \
        || fail 'Worker route readiness does not require the pinned XHTTP OPTIONS handler success response'
    grep_fixed 'next_action' "$WORKER_SCRIPT" \
        || fail 'Worker API does not return a concrete next action when route is not ready'
    grep_fixed 'next_action_code' "$WORKER_SCRIPT" \
        || fail 'Worker API does not return a stable next-action code'
    grep_fixed 'id="actionCode"' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not show the stable next-action code'
    grep_fixed 'id="lastChecked"' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not show dashboard freshness'
    grep_fixed 'copyStatus' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not provide a copy-status action'
    grep_fixed 'setTimeout(checkHealth' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not auto-refresh while the route is settling'
    grep_fixed 'fetchWithTimeout(' "$WORKER_SCRIPT" \
        || fail 'Worker GitHub/route fetches are not individually time-bounded'
    grep_fixed 'github_start_request_unreachable' "$WORKER_SCRIPT" \
        || fail 'Worker start timeout/network errors are not returned as structured JSON'
    grep_fixed 'github_status_request_unreachable' "$WORKER_SCRIPT" \
        || fail 'Worker status timeout/network errors are not returned as structured JSON'
    grep_fixed 'routeSettlingFailureText(data, route, routeReady)' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard can still report no failure while the route is settling'
    grep_fixed 'data.ok && data.start_accepted && data.route_ready !== true' "$WORKER_SCRIPT" \
        || fail 'Worker HTTP status does not return accepted/in-progress after GitHub accepts a cold start'
    grep_fixed 'data.route_probe?.error === "route_stability_not_confirmed"' "$WORKER_SCRIPT" \
        || fail 'Worker HTTP status does not treat unconfirmed route stability as retryable'
    grep_fixed 'data && (data.ok || data.start_accepted) && data.route_ready !== true' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not keep polling after an accepted start is still settling'
    grep_fixed 'Route history summary' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not summarize route health history'
    grep_fixed 'latencyTrend' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not render a latency trend'
    grep_fixed 'renderHistorySummary' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not compute history summary metrics'
    grep_fixed 'History request failed:' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard hides unauthorized or failed history requests'
    grep_fixed 'Quota Survival' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not expose quota survival status'
    grep_fixed 'quotaBlocked' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not show quota-block status'
    grep_fixed 'securityHeaders(' "$WORKER_SCRIPT" \
        || fail 'Worker responses do not share hardened security headers'
    grep_fixed 'ctx.waitUntil' "$WORKER_SCRIPT" \
        || fail 'Worker does not defer notification side effects with waitUntil'
    grep_fixed 'queueHistorySideEffects(env, event, data, ctx)' "$WORKER_SCRIPT" \
        || fail 'Worker does not defer KV history/quota side effects with waitUntil'
    grep_fixed 'enrichEventWithHistoryContext(env, eventFromResult("health", codespaceName, data))' "$WORKER_SCRIPT" \
        || fail 'Worker health events are not enriched from KV history before notifications'
    grep_fixed 'route_ready_transition' "$WORKER_SCRIPT" \
        || fail 'Worker does not mark route-ready transitions after stuck routes'
    grep_fixed 'shouldStoreHistoryEvent(env, event, existing)' "$WORKER_SCRIPT" \
        || fail 'Worker does not dedupe or sample noisy health history'
    grep_fixed 'HEALTH_HISTORY_SAMPLE_MS' "$WORKER_SCRIPT" \
        || fail 'Worker health history sampling is not configurable'
    grep_fixed 'WAKER_KV' "$WORKER_README" \
        || fail 'Worker README does not document optional KV history'
    grep_fixed 'route-ready transition alert' "$WORKER_README" \
        || fail 'Worker README does not document health-driven route-ready transition alerts'
    grep_fixed 'Identical health polls are sampled' "$WORKER_README" \
        || fail 'Worker README does not document health-history sampling'
    grep_fixed 'DISCORD_WEBHOOK_URL' "$WORKER_README" \
        || fail 'Worker README does not document optional Discord alerts'
    grep_fixed 'TELEGRAM_BOT_TOKEN' "$WORKER_README" \
        || fail 'Worker README does not document optional Telegram alerts'
    grep_fixed 'Health dashboard' "$README" \
        || fail 'root README does not mention the private Worker health dashboard'
    pass 'Worker dashboard, history, and alert features are documented'
}

test_quota_survival_layer_is_present() {
    grep_fixed 'quota_blocked:' "$WORKER_SCRIPT" \
        || fail 'Worker responses do not include quota_blocked'
    grep_fixed 'quota_reset_estimate_utc' "$WORKER_SCRIPT" \
        || fail 'Worker responses do not include a monthly reset estimate'
    grep_fixed 'retention_expires_at' "$WORKER_SCRIPT" \
        || fail 'Worker does not expose Codespaces retention expiration'
    grep_fixed 'retention_risk' "$WORKER_SCRIPT" \
        || fail 'Worker does not classify retention risk'
    grep_fixed 'survival_next_action' "$WORKER_SCRIPT" \
        || fail 'Worker does not provide quota-survival next actions'
    grep_fixed 'QUOTA_INCIDENT_KEY_PREFIX' "$WORKER_SCRIPT" \
        || fail 'Worker does not persist quota incident history'
    grep_fixed 'QUOTA_SURVIVAL_CRON_ENABLED' "$WORKER_SCRIPT" \
        || fail 'Worker scheduled quota checks are not explicitly opt-in'
    grep_fixed 'quotaSurvivalCronEnabled(env)' "$WORKER_SCRIPT" \
        || fail 'Worker scheduled handler is not gated behind opt-in config'
    grep_fixed 'Keep codespace' "$SCRIPT" \
        || fail 'panel does not guide users to Keep codespace before quota exhaustion'
    grep_fixed 'same configs can survive until reset' "$SCRIPT" \
        || fail 'quota panel does not explain same-Codespace survival'
    grep_fixed 'Keep codespace' "$README" \
        || fail 'README does not tell users to preserve the Codespace with Keep codespace'
    grep_fixed 'same VLESS configs survive into the next monthly reset only if the same Codespace name/domain survives' "$README" \
        || fail 'README does not explain same-domain survival through quota reset'
    grep_fixed 'QUOTA_SURVIVAL_CRON_ENABLED' "$WORKER_README" \
        || fail 'Worker README does not document optional quota-survival Cron'
    pass 'quota survival layer is present and documented'
}

test_worker_wake_edge_cases_are_hardened() {
    grep_fixed 'githubFetchWithRetry(' "$WORKER_SCRIPT" \
        || fail 'Worker GitHub API calls do not use bounded retry wrapper'
    grep_fixed 'shouldRetryGithubResponse(response)' "$WORKER_SCRIPT" \
        || fail 'Worker GitHub retry wrapper does not classify retryable HTTP failures'
    grep_fixed 'route_check_requested: false' "$WORKER_SCRIPT" \
        || fail 'Worker cold wake falsely claims that route readiness was checked'
    grep_fixed 'function isRouteSettlingStatus(routeProbe)' "$WORKER_SCRIPT" \
        || fail 'Worker has no narrow route-settling classifier for 202 responses'
    grep_fixed 'id="stop"' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard has no stop-polling control'
    grep_fixed 'function stopPolling()' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard cannot stop background health polling'
    grep_fixed 'document.getElementById("stop").addEventListener("click", stopPolling)' "$WORKER_SCRIPT" \
        || fail 'Worker stop-polling button is not wired to stopPolling'
    grep_fixed 'function scheduleHealthPoll()' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not centralize health polling through a managed timer'
    grep_fixed 'pollTimer = setTimeout(checkHealth, Math.max(1, pollAfterSeconds) * 1000)' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard polling is not tracked through pollTimer'
    grep_fixed 'pollAfterSeconds = Number.isFinite' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not honor poll_after_seconds/retry_after_seconds'
    grep_fixed 'clearTimeout(pollTimer)' "$WORKER_SCRIPT" \
        || fail 'Worker dashboard does not clear pending poll timers'
    grep_fixed 'historyKey(codespace)' "$WORKER_SCRIPT" \
        || fail 'Worker KV history is not namespaced by Codespace'
    grep_fixed 'readHistory(env, context.codespaceName)' "$WORKER_SCRIPT" \
        || fail 'Worker history API does not read the current Codespace namespace'
    grep_fixed '`401`: Wrong wake secret, or GitHub rejected the stored token' "$WORKER_README" \
        || fail 'Worker README does not distinguish wake-secret 401 from GitHub-token 401'
    pass 'Worker wake edge cases are hardened'
}

test_worker_rate_limits_and_classifies_github_errors() {
    grep_fixed 'rateLimitFailedAuth(request, env)' "$WORKER_SCRIPT" \
        || fail 'Worker does not rate-limit failed wake-secret attempts'
    grep_fixed 'githubFailureForResponse(res, body, codespaceName)' "$WORKER_SCRIPT" \
        || fail 'Worker does not centralize GitHub error classification'
    grep_fixed 'github_rate_limited' "$WORKER_SCRIPT" \
        || fail 'Worker does not distinguish GitHub rate limit failures'
    grep_fixed 'res.status === 429' "$WORKER_SCRIPT" \
        || fail 'Worker does not classify direct GitHub HTTP 429 failures'
    grep_fixed 'github_secondary_rate_limited' "$WORKER_SCRIPT" \
        || fail 'Worker does not distinguish secondary rate limits'
    grep_fixed 'status: 429' "$WORKER_SCRIPT" \
        || fail 'Worker does not return 429 for rate-limit conditions'
    grep_fixed 'data.next_action = data.next_action || nextActionForWake(data, data.route_probe || {})' "$WORKER_SCRIPT" \
        || fail 'Worker wake failures do not include next_action guidance'
    grep_fixed 'FAILED_AUTH_KEY_PREFIX' "$WORKER_SCRIPT" \
        || fail 'Worker failed-auth rate limit is not persisted in KV'
    pass 'Worker rate-limits bad secrets and classifies GitHub failures'
}

test_route_candidate_monitor_is_bounded() {
    grep_fixed 'ROUTE_HEALTH_FILE=' "$SCRIPT" \
        || fail 'route candidate monitor has no bounded health cache file'
    grep_fixed 'ROUTE_STATS_FILE=' "$SCRIPT" \
        || fail 'route candidate monitor has no rolling stats file'
    grep_fixed 'ROUTE_MONITOR_MAX_CANDIDATES=' "$SCRIPT" \
        || fail 'route candidate monitor does not cap candidate probes'
    grep_fixed 'update_route_candidate_stats()' "$SCRIPT" \
        || fail 'route candidate monitor cannot maintain rolling averages'
    grep_fixed 'refresh_route_candidate_health()' "$SCRIPT" \
        || fail 'route candidate monitor cannot refresh candidate health'
    grep_fixed 'route_candidate_health_summary()' "$SCRIPT" \
        || fail 'diagnostics cannot summarize route candidate health'
    grep_fixed 'cached_route_candidate_ips()' "$SCRIPT" \
        || fail 'previously measured route candidates are not reused across resolver refreshes'
    grep_fixed 'record_route_candidate_health "$_ip" "$_code" "$_ms" "$_source" "$_reason"' "$SCRIPT" \
        || fail 'route candidate monitor does not persist per-candidate probe results'
    grep_fixed 'update_route_candidate_stats "$ip" "$code" "$ms" "$source" "$reason"' "$SCRIPT" \
        || fail 'route candidate probes do not update rolling route stats with metadata'
    grep_fixed 'route_failure_reason_for_status()' "$SCRIPT" \
        || fail 'route candidate probes do not classify route failure reasons'
    grep_fixed 'ROUTE_PROBE_CONCURRENCY=' "$SCRIPT" \
        || fail 'route candidate probes are not bounded-concurrent'
    grep_fixed 'success=' "$SCRIPT" \
        || fail 'route candidate diagnostics do not show route reliability'
    grep_fixed 'avg=' "$SCRIPT" \
        || fail 'route candidate diagnostics do not show average latency'
    grep_fixed 'Cache status :' "$SCRIPT" \
        || fail 'route candidate diagnostics do not show cache freshness'
    grep_fixed 'confidence = (' "$SCRIPT" \
        || fail 'route candidate diagnostics do not classify low-sample confidence'
    grep_fixed 'Probe scope  : Codespace-side route checks; your ISP/client path can still block some IPs' "$SCRIPT" \
        || fail 'route candidate diagnostics do not warn that Codespace-side route probes can differ from the client path'
    grep_fixed 'refresh_route_candidate_health >/dev/null 2>&1 || true' "$SCRIPT" \
        || fail 'background supervisor does not refresh route candidate health'
    awk '
        /_background_tasks\(\)/ { in_fn=1; next }
        in_fn && /refresh_route_candidate_health >\/dev\/null 2>&1 \|\| true/ { saw_startup_route=1 }
        in_fn && /refresh_config_exports >\/dev\/null 2>&1 \|\| true/ && !saw_startup_route { exit 1 }
        in_fn && /\(\( \+\+route_tick >= 5 \)\)/ { saw_loop_route=1 }
        in_fn && /\(\( \+\+export_tick >= 5 \)\)/ && !saw_loop_route { exit 1 }
        in_fn && /^}/ { exit 0 }
    ' "$SCRIPT" || fail 'background supervisor can refresh exports before route probes, causing duplicate scans'
    grep_fixed 'route_candidate_health_summary | sed' "$SCRIPT" \
        || fail 'diagnostics do not display route candidate health'
    if grep_regex 'for[[:space:]]+.*in[[:space:]]+.*(/8|/16|/24|seq[[:space:]]+1[[:space:]]+254)' "$SCRIPT"; then
        fail 'route candidate monitor appears to scan broad address ranges'
    fi
    pass 'route candidate monitor is bounded and diagnostics-visible'
}

test_route_candidate_manager_and_live_monitor_are_present() {
    grep_fixed 'MANUAL_ROUTE_CANDIDATES_FILE=' "$SCRIPT" \
        || fail 'manual route candidate file is not defined'
    grep_fixed 'BLACKLISTED_ROUTE_CANDIDATES_FILE=' "$SCRIPT" \
        || fail 'blacklisted route candidate file is not defined'
    grep_fixed 'PINNED_ROUTE_FILE=' "$SCRIPT" \
        || fail 'pinned route file is not defined'
    grep_fixed 'valid_ipv4()' "$SCRIPT" \
        || fail 'route manager has no IPv4 validator'
    grep_fixed 'add_manual_route_candidate()' "$SCRIPT" \
        || fail 'route manager cannot add manual IPv4 candidates'
    grep_fixed 'blacklist_route_candidate()' "$SCRIPT" \
        || fail 'route manager cannot blacklist bad routes'
    grep_fixed 'pin_route_candidate()' "$SCRIPT" \
        || fail 'route manager cannot pin a preferred route'
    grep_fixed 'reset_route_candidate_cache()' "$SCRIPT" \
        || fail 'route manager cannot reset measured route health without wiping preferences'
    grep_fixed 'reset_route_candidate_state()' "$SCRIPT" \
        || fail 'route manager cannot reset all route preferences'
    grep_fixed 'Reset Route Health Cache' "$SCRIPT" \
        || fail 'route manager UI does not distinguish cache reset from preference reset'
    grep_fixed 'Reset All Route Preferences' "$SCRIPT" \
        || fail 'route manager UI does not expose an explicit full preference reset'
    grep_fixed 'show_route_candidate_manager()' "$SCRIPT" \
        || fail 'route candidate manager screen is missing'
    grep_fixed 'show_live_monitor()' "$SCRIPT" \
        || fail 'live monitor screen is missing'
    grep_fixed '16)${NC} Live Monitor' "$SCRIPT" \
        || fail 'main menu does not expose live monitor'
    grep_fixed '17)${NC} Route Candidates' "$SCRIPT" \
        || fail 'main menu does not expose route candidates manager'
    grep_fixed '16) show_live_monitor' "$SCRIPT" \
        || fail 'case statement does not route to live monitor'
    grep_fixed '17) show_route_candidate_manager' "$SCRIPT" \
        || fail 'case statement does not route to route candidate manager'
    grep_fixed 'pinned_route_value' "$SCRIPT" \
        || fail 'cached route ordering does not consider pinned routes'
    grep_fixed 'while IFS= read -r ip; do' "$SCRIPT" \
        || fail 'resolver does not include persisted pinned/manual route candidates'
    grep_fixed 'cached_route_candidate_ips' "$SCRIPT" \
        || fail 'resolver does not include cached measured route candidates'
    grep_fixed 'append_unique_route "$MANUAL_ROUTE_CANDIDATES_FILE" "$ip" || return 1' "$SCRIPT" \
        || fail 'manual route additions do not report persistence failures'
    grep_fixed '_atomic_write "$PINNED_ROUTE_FILE" "$ip" || return 1' "$SCRIPT" \
        || fail 'pinned routes do not report persistence failures'
    awk '
        /if pin_route_candidate "\$ip"; then/ {in_pin=1; next}
        in_pin && /else/ {in_pin=0}
        in_pin && /route_candidate_refresh_with_feedback \|\| true/ {saw_refresh=1}
        in_pin && /route_candidate_export_with_feedback \|\| true/ && saw_refresh {saw_export_after_refresh=1}
        END {exit !(saw_refresh && saw_export_after_refresh)}
    ' "$SCRIPT" || fail 'pinning a preferred route does not refresh route health before exports'
    grep_fixed 'append_unique_route "$BLACKLISTED_ROUTE_CANDIDATES_FILE" "$ip" || return 1' "$SCRIPT" \
        || fail 'blacklisted route additions do not report persistence failures'
    grep_fixed 'remove_route_from_file "$BLACKLISTED_ROUTE_CANDIDATES_FILE" "$ip" || return 1' "$SCRIPT" \
        || fail 'unblacklist does not report no-op or persistence failures'
    grep_fixed 'candidate_blacklisted "$ip"' "$SCRIPT" \
        || fail 'route exports do not filter blacklisted routes'
    grep_fixed 'Route Candidates' "$README" \
        || fail 'README does not document route candidate manager'
    pass 'route candidate manager and live monitor are present'
}

test_first_run_recovery_card_is_present() {
    grep_fixed 'show_recovery_command_card()' "$SCRIPT" \
        || fail 'first-run recovery command card helper is missing'
    grep_fixed 'bash ./g2ray.sh --doctor-json' "$SCRIPT" \
        || fail 'recovery command card does not show doctor JSON command'
    grep_fixed 'bash ./g2ray.sh --recover-now' "$SCRIPT" \
        || fail 'recovery command card does not show recover command'
    grep_fixed 'bash ./g2ray.sh --recover-now --json' "$SCRIPT" \
        || fail 'recovery command card does not show machine-readable recover command'
    grep_fixed 'replace <WAKE_SECRET>' "$SCRIPT" \
        || fail 'recovery command card does not avoid printing the raw wake secret'
    grep_fixed 'copy these recovery commands' "$README" \
        || fail 'README does not mention the recovery command card'
    pass 'first-run recovery card is present'
}

test_soft_recovery_and_route_memory_are_present() {
    grep_fixed 'recover_now()' "$SCRIPT" \
        || fail 'panel has no idempotent soft recovery path'
    grep_fixed 'recover_now --no-prompt' "$SCRIPT" \
        || fail 'headless recover command is not wired'
    grep_fixed 'recover_now_json()' "$SCRIPT" \
        || fail 'machine-readable recover JSON renderer is missing'
    grep_fixed 'recover_exit_code' "$SCRIPT" \
        || fail 'machine-readable recover JSON does not preserve the internal recovery exit code'
    grep_fixed 'recover_now --no-prompt >/dev/null 2>&1' "$SCRIPT" \
        || fail 'machine-readable recover command does not suppress human terminal output'
    grep_fixed '"subscription_scope": "local_codespace_only"' "$SCRIPT" \
        || fail 'config metadata does not mark exports as local-only'
    if grep_fixed 'publish_subscription_export' "$SCRIPT" || grep_fixed 'G2RAY_PUBLISH_PUBLIC_SUBSCRIPTION' "$SCRIPT"; then
        fail 'panel still contains public subscription publishing code'
    fi
    grep_fixed 'tar -C "$tmp" -czf "$out" .' "$SCRIPT" \
        || fail 'support bundle archive creation is not safe for relative output paths'
    grep_fixed '--doctor-json' "$SCRIPT" \
        || fail 'panel has no headless doctor JSON command'
    grep_fixed '"${1:-}" == "--status" || "${1:-}" == "status"' "$SCRIPT" \
        || fail 'panel has no short headless status command'
    grep_fixed '"${1:-}" == "--start" || "${1:-}" == "start"' "$SCRIPT" \
        || fail 'panel has no short headless start command'
    grep_fixed '"${1:-}" == "--refresh-exports" || "${1:-}" == "--export" || "${1:-}" == "export"' "$SCRIPT" \
        || fail 'panel has no headless export refresh command'
    grep_fixed 'print_doctor_json()' "$SCRIPT" \
        || fail 'doctor JSON renderer is missing'
    grep_fixed 'doctor_port=443' "$SCRIPT" \
        || fail 'doctor JSON does not sanitize invalid port values'
    grep_fixed 'ensure_codespace_port_public force' "$SCRIPT" \
        || fail 'hard repair paths cannot bypass port-public throttling'
    grep_fixed 'PORT_PUBLIC_STAMP_FILE=' "$SCRIPT" \
        || fail 'port visibility calls are not timestamp-throttled'
    grep_fixed 'PORT_PUBLIC_TTL_SEC="${G2RAY_PORT_PUBLIC_TTL_SEC:-300}"' "$SCRIPT" \
        || fail 'port visibility default TTL is too chatty for steady-state health checks'
    grep_fixed 'PORT_PUBLIC_STAMP_FILE}.${CODESPACE_NAME}.${port}' "$SCRIPT" \
        || fail 'port visibility cache is not scoped by codespace and port'
    grep_fixed 'LAST_GOOD_ROUTE_FILE=' "$SCRIPT" \
        || fail 'last-good route is not persisted'
    grep_fixed 'save_last_good_route()' "$SCRIPT" \
        || fail 'last-good route save helper is missing'
    grep_fixed 'cached_usable_fallback_ips()' "$SCRIPT" \
        || fail 'exports cannot use cached route health'
    grep_fixed 'DNS_CANDIDATE_CACHE_FILE=' "$SCRIPT" \
        || fail 'DNS candidate cache file is not defined'
    grep_fixed 'read_dns_candidate_cache "$domain"' "$SCRIPT" \
        || fail 'resolver does not reuse fresh cached DNS candidates'
    grep_fixed 'resolve_dns_provider_ips_with_sources "$domain"' "$SCRIPT" \
        || fail 'resolver does not centralize DNS provider discovery'
    grep_fixed 'pids+=("$!")' "$SCRIPT" \
        || fail 'DNS provider discovery is not run with bounded parallel workers'
    grep_fixed 'write_dns_candidate_cache "$domain" "$provider_rows"' "$SCRIPT" \
        || fail 'resolver does not persist fresh DNS provider candidates'
    grep_fixed '"next_action_code": "$(json_escape "$next_action_code")"' "$SCRIPT" \
        || fail 'panel JSON outputs do not expose stable next_action_code values'
    grep_fixed 'ROUTE_STATS_FILE' "$SCRIPT" \
        || fail 'exports cannot use rolling route stats'
    grep_fixed 'route_health_cache_fresh()' "$SCRIPT" \
        || fail 'route health cache has no freshness guard'
    grep_fixed 'Route Settling History' "$SCRIPT" \
        || fail 'diagnostics do not expose route-settling history'
    grep_fixed 'record_route_settling_metric "$reason" "ready"' "$SCRIPT" \
        || fail 'route wait does not record ready timing'
    grep_fixed 'attempt=$(( attempt + 1 ))' "$SCRIPT" \
        || fail 'route wait does not count the first probe attempt'
    grep_fixed 'record_route_settling_metric "$reason" "timeout"' "$SCRIPT" \
        || fail 'route wait does not record timeout timing'
    grep_fixed 'STRUCTURED_LOG_FILE=' "$SCRIPT" \
        || fail 'structured persistent log file is not defined'
    grep_fixed 'DIAGNOSTIC_LOG_FILE=' "$SCRIPT" \
        || fail 'readable persistent diagnostic log file is not defined'
    grep_fixed '>> "$DIAGNOSTIC_LOG_FILE"' "$SCRIPT" \
        || fail 'diagnostic snapshots are not written to the diagnostic log'
    grep_fixed 'log_diagnostic_snapshot "interactive"' "$SCRIPT" \
        || fail 'diagnostics do not write a persistent snapshot'
    grep_fixed 'grep -E "$pattern" "$LOG_FILE"' "$SCRIPT" \
        || fail 'last known diagnostics summary only scans a recent log tail'
    grep_fixed '6) recover_now' "$SCRIPT" \
        || fail 'menu option 6 does not use soft recovery first'
    pass 'soft recovery, route memory, headless status, and persistent logs are present'
}

test_route_export_and_reconnect_edges_are_hardened() {
    grep_fixed 'effective_export_route_ips()' "$SCRIPT" \
        || fail 'fallback exports do not read the effective cached route selection'
    grep_fixed 'ensure_route_cache_for_export()' "$SCRIPT" \
        || fail 'config export has no synchronous stale-cache refresh helper'
    grep_fixed 'config_export_artifacts_match_current_uuid()' "$SCRIPT" \
        || fail 'config export cannot reject artifacts from a previous UUID'
    grep_fixed 'request_route_candidate_health_refresh' "$SCRIPT" \
        || fail 'fallback exports do not request a bounded background route refresh'
    grep_fixed 'fallback_route_filter no_cached_usable_routes action=domain_only_refresh_requested' "$SCRIPT" \
        || fail 'fallback exports do not explain the domain-only case when no route cache exists'
    awk '
        /_generate_config_impl\(\)/ { in_fn=1 }
        in_fn && index($0, "refresh_config_exports >/dev/null 2>&1 || true") { saw=1 }
        in_fn && /^generate_link_for_address\(\)/ { exit }
        END { exit saw ? 0 : 1 }
    ' "$SCRIPT" \
        || fail 'new config generation does not refresh exported config files'
    awk '
        /_generate_config_impl\(\)/ { in_fn=1; next }
        in_fn && /ensure_route_cache_for_export .*"generate_config"/ { saw_cache=1; next }
        in_fn && /refresh_config_exports >\/dev\/null 2>&1 \|\| true/ {
            if (saw_cache) ordered=1
            exit
        }
        END { exit ordered ? 0 : 1 }
    ' "$SCRIPT" \
        || fail 'new config generation can export before refreshing an expired route cache'
    grep_fixed 'if ! latency_focus_enabled || ! route_health_cache_fresh; then' "$SCRIPT" \
        || fail 'latency focus can still skip the first route refresh when cache is stale'
    grep_fixed 'no_exportable_links_uuid_mismatch' "$SCRIPT" \
        || fail 'empty exports do not clear artifacts from a previous UUID'
    grep_fixed 'write_failed_no_valid_backup' "$SCRIPT" \
        || fail 'failed exports can restore artifacts from a previous UUID'
    grep_fixed 'with_file_lock "$EXPORT_LOCK_FILE" 45 write_config_exports_from_links "${_CONFIG_LINKS[@]}"' "$SCRIPT" \
        || fail 'view-configs can race the supervisor while writing client export artifacts'
    grep_fixed 'FORCE_RECONNECT_ROUTE_WAIT_SEC' "$SCRIPT" \
        || fail 'force reconnect has no dedicated route-settling wait budget'
    grep_fixed 'XRAY_PORT=443' "$SCRIPT" \
        || fail 'invalid XRAY_PORT input is not sanitized to 443'
    grep_fixed 'wait_for_xhttp_route_ready "force_reconnect" "$FORCE_RECONNECT_ROUTE_WAIT_SEC"' "$SCRIPT" \
        || fail 'force reconnect does not reuse the route-settling wait loop'
    grep_fixed 'local no_prompt="${1:-}" failed=0 expose_failed=false hard_failed=false' "$SCRIPT" \
        || fail 'force reconnect does not track expose-tunnel failure separately'
    grep_fixed '[[ "$expose_failed" == true && "$hard_failed" != true ]] && failed=0' "$SCRIPT" \
        || fail 'force reconnect does not treat a usable route as success after a flaky port visibility command'
    grep_fixed 'DIAGNOSTIC_MAX_FALLBACK_PROBES' "$SCRIPT" \
        || fail 'diagnostics fallback probes are not capped'
    grep_fixed 'if (( probed >= max_probe )); then' "$SCRIPT" \
        || fail 'diagnostics fallback probes do not enforce the configured cap'
    grep_fixed 'additional candidates skipped' "$SCRIPT" \
        || fail 'diagnostics does not disclose when fallback probes are capped'
    pass 'route export and reconnect edges are hardened'
}

test_interactive_config_generation_failures_are_guarded() {
    grep_fixed 'generate_config_with_feedback()' "$SCRIPT" \
        || fail 'panel has no guarded interactive config-generation helper'
    grep_fixed 'generate_config_preserving_uuid_with_feedback()' "$SCRIPT" \
        || fail 'panel has no guarded same-UUID config regeneration helper'
    grep_fixed 'candidate_config=$(mktemp "${CONFIG_FILE}.candidate.XXXXXX.json")' "$SCRIPT" \
        || fail 'candidate config temp file does not keep a .json suffix for Xray format detection'
    awk '
        /Overwrite current config and restart engine/ { in_option=1; next }
        in_option && /^[[:space:]]*;;/ { exit }
        in_option && /generate_config_with_feedback/ { found=1 }
        END { exit found ? 0 : 1 }
    ' "$SCRIPT" \
        || fail 'main menu option 2 can still exit the panel when config generation fails'
    if awk '
        /Overwrite current config and restart engine/ { in_option=1; next }
        in_option && /^[[:space:]]*;;/ { exit }
        in_option && /generate_config; sleep/ { bad=1 }
        END { exit bad ? 0 : 1 }
    ' "$SCRIPT"; then
        fail 'main menu option 2 still calls generate_config directly under set -e'
    fi
    pass 'interactive config-generation failures are guarded'
}

test_panel_guides_cloudflare_waker_setup() {
    grep_fixed 'WAKER_METADATA_FILE=' "$SCRIPT" \
        || fail 'panel does not persist non-sensitive waker metadata'
    grep_fixed 'generate_wake_secret()' "$SCRIPT" \
        || fail 'panel cannot generate a one-time wake secret'
    grep_fixed 'save_waker_metadata()' "$SCRIPT" \
        || fail 'panel cannot save the configured Worker URL metadata'
    grep_fixed 'waker_metadata_summary()' "$SCRIPT" \
        || fail 'diagnostics cannot summarize configured waker metadata'
    grep_fixed 'setup_cloudflare_waker()' "$SCRIPT" \
        || fail 'panel does not provide a guided Cloudflare Worker setup flow'
    grep_fixed 'test_cloudflare_waker()' "$SCRIPT" \
        || fail 'panel cannot test a configured Worker URL on demand'
    grep_fixed 'reset_waker_metadata()' "$SCRIPT" \
        || fail 'panel cannot clear saved waker metadata'
    grep_fixed 'show_recovery_waker()' "$SCRIPT" \
        || fail 'panel does not expose a recovery/waker menu screen'
    grep_fixed '15)${NC} Recovery / Waker Setup' "$SCRIPT" \
        || fail 'main menu does not expose recovery/waker setup'
    grep_fixed '15) show_recovery_waker' "$SCRIPT" \
        || fail 'case statement does not route to the recovery/waker setup screen'
    grep_fixed '1) setup_cloudflare_waker' "$SCRIPT" \
        || fail 'recovery submenu does not route to setup'
    grep_fixed '2) show_waker_recovery_guide' "$SCRIPT" \
        || fail 'recovery submenu does not route to instructions'
    grep_fixed 'test_cloudflare_waker || true' "$SCRIPT" \
        || fail 'recovery submenu does not route to Worker testing'
    grep_fixed '4) reset_waker_metadata' "$SCRIPT" \
        || fail 'recovery submenu does not route to metadata reset'
    grep_fixed 'Do not paste the GitHub token into G2ray' "$SCRIPT" \
        || fail 'wizard does not warn users not to store GitHub tokens in the panel'
    grep_fixed 'https://github.com/settings/tokens/new?scopes=codespace' "$SCRIPT" \
        || fail 'wizard does not give a direct GitHub token creation path'
    grep_fixed 'CODESPACE_NAME${NC}  Plaintext' "$SCRIPT" \
        || fail 'wizard does not clarify the CODESPACE_NAME Cloudflare binding type'
    grep_fixed 'GITHUB_TOKEN${NC}    Secret' "$SCRIPT" \
        || fail 'wizard does not clarify GitHub token and wake secret binding types'
    grep_fixed 'WAKE_SECRET${NC}     Secret' "$SCRIPT" \
        || fail 'wizard does not clarify WAKE_SECRET as a Cloudflare secret'
    grep_fixed 'Worker wake URL (https optional, /wake optional)' "$SCRIPT" \
        || fail 'wizard does not clarify acceptable Worker URL formats'
    grep_fixed 'No KV or Wrangler is required' "$SCRIPT" \
        || fail 'wizard does not clarify that the dashboard setup needs no KV or Wrangler'
    grep_fixed 'route_ready: false' "$SCRIPT" \
        || fail 'recovery guide does not explain route settling after wake'
    grep_fixed 'Use this exact same key everywhere' "$SCRIPT" \
        || fail 'wizard does not tell users to keep one shared wake key'
    grep_fixed '^https://([A-Za-z0-9][A-Za-z0-9.-]*[.][A-Za-z0-9.-]+)' "$SCRIPT" \
        || fail 'waker URL normalization does not reject obvious non-URL secret input'
    grep_fixed 'printf '\''https://%s%s/wake'\''' "$SCRIPT" \
        || fail 'waker URL normalization does not canonicalize trailing /wake slashes'
    grep_fixed 'read -r answer || { touch "$WAKER_PROMPT_FILE"' "$SCRIPT" \
        || fail 'one-time waker prompt is not EOF-safe under set -e'
    grep_fixed 'rm -f "$WAKER_METADATA_FILE"' "$SCRIPT" \
        || fail 'reset path does not clear saved waker metadata'
    grep_fixed 'touch "$WAKER_PROMPT_FILE"' "$SCRIPT" \
        || fail 'reset/prompt paths do not preserve the one-time prompt marker'
    grep_fixed 'Authorization: Bearer' "$SCRIPT" \
        || fail 'wizard/test flow does not use the safer bearer secret form'
    grep_fixed 'WAKER_TEST_TIMEOUT_SEC' "$SCRIPT" \
        || fail 'panel Worker test timeout is not configurable for long wake waits'
    grep_fixed 'printf '\''max-time = "%s"\n'\'' "$WAKER_TEST_TIMEOUT_SEC"' "$SCRIPT" \
        || fail 'panel Worker test still uses a fixed short curl timeout'
    grep_fixed 'curl --config -' "$SCRIPT" \
        || fail 'panel Worker test does not feed the secret-bearing curl config through stdin'
    if grep_fixed 'waker-curl' "$SCRIPT"; then
        fail 'panel Worker test still writes the wake secret to a temporary curl config file'
    fi
    grep_fixed '.route_ready // empty' "$SCRIPT" \
        || fail 'panel Worker test does not show route_ready from the Worker response'
    grep_fixed '.route_probe.http_status // empty' "$SCRIPT" \
        || fail 'panel Worker test does not show route probe HTTP status'
    grep_fixed '.next_action // empty' "$SCRIPT" \
        || fail 'panel Worker test does not show Worker next_action guidance'
    grep_fixed 'fingerprint_secret "$wake_secret"' "$SCRIPT" \
        || fail 'wizard does not store only a fingerprint of the wake secret'
    if grep_fixed 'GITHUB_TOKEN=' "$SCRIPT"; then
        fail 'panel appears to store a GitHub token assignment'
    fi
    pass 'panel guides Cloudflare waker setup without storing tokens'
}

test_diagnostics_show_external_waker_state() {
    awk '
        /show_diagnostics\(\)/ { in_fn=1 }
        in_fn && /External Waker/ { saw_title=1 }
        in_fn && /waker_metadata_summary \| sed/ { saw_summary=1 }
        in_fn && /^}/ { exit }
        END { exit (saw_title && saw_summary) ? 0 : 1 }
    ' "$SCRIPT" \
        || fail 'diagnostics do not render the waker metadata summary'
    grep_fixed 'Status      : configured' "$SCRIPT" \
        || fail 'waker summary cannot report configured status'
    grep_fixed 'Status      : not configured' "$SCRIPT" \
        || fail 'waker summary cannot report missing setup'
    grep_fixed 'Next step   : open option 15' "$SCRIPT" \
        || fail 'waker summary does not point users to the recovery setup option'
    grep_fixed 'Worker URL  :' "$SCRIPT" \
        || fail 'waker summary omits the Worker URL'
    grep_fixed 'Secret      : fingerprint=' "$SCRIPT" \
        || fail 'waker summary omits the wake secret fingerprint'
    pass 'diagnostics show external waker state'
}

test_docs_cover_panel_waker_setup() {
    grep_fixed 'Option 15' "$README" \
        || fail 'README does not document the panel recovery setup option'
    grep_fixed 'choose `1) Generate Config & Start`' "$README" \
        || fail 'README fresh-start flow does not tell users which panel option to choose'
    grep_fixed 'Do not paste the GitHub token into G2ray' "$README" \
        || fail 'README does not warn against storing GitHub tokens in the panel'
    grep_fixed 'paste the existing key instead of generating another one' "$README" \
        || fail 'README does not explain shared wake-key reuse'
    grep_fixed 'Recovery / Waker Setup' "$README" \
        || fail 'README does not name the recovery setup screen'
    grep_fixed 'Default idle timeout** to **240 minutes' "$README" \
        || fail 'README does not retain the 240 minute idle-timeout recommendation'
    grep_fixed 'Recovery / Waker Setup' "$WORKER_README" \
        || fail 'Worker README does not mention the panel setup flow'
    grep_fixed 'GET /wake` page is public' "$README" \
        || fail 'README does not clarify that the Worker page is public but actions are protected'
    grep_fixed 'GET /wake` page is public' "$WORKER_README" \
        || fail 'Worker README does not clarify that the page is public but actions are protected'
    grep_fixed 'Return to panel option `15`' "$README" \
        || fail 'README does not tell users to return to option 15 after Worker deployment'
    grep_fixed 'Return to **Option 15: Recovery / Waker Setup**' "$WORKER_README" \
        || fail 'Worker README does not tell users to return to option 15 after deploy'
    grep_fixed 'bash ./g2ray.sh --silent-start' "$README" \
        || fail 'README does not document the post-pull runtime refresh command'
    grep_fixed '`--recover-now` is non-interactive and soft-only' "$README" \
        || fail 'README does not clarify headless recover limitations'
    grep_fixed 'This project intentionally does not publish a raw GitHub subscription URL' "$README" \
        || fail 'README does not explain local-only subscription exports'
    grep_fixed 'Local subscription file' "$SCRIPT" \
        || fail 'diagnostics does not show the local subscription file'
    if grep_fixed 'publish-subscription' "$README" || grep_fixed 'publish-subscription' "$SCRIPT"; then
        fail 'public subscription publishing command is still documented or wired'
    fi
    if grep_fixed 'Raw subscription URL' "$SCRIPT"; then
        fail 'panel still prints a raw subscription URL'
    fi
    grep_fixed 'Option `49) Toggle Latency Focus Mode`' "$README" \
        || fail 'README does not document latency focus mode'
    grep_fixed 'G2RAY_LATENCY_FOCUS=1' "$README" \
        || fail 'README does not document the latency focus environment flag'
    grep_fixed 'applies the `low_latency` profile' "$README" \
        || fail 'README does not state that latency focus applies the real low_latency profile'
    grep_fixed 'applies the `low_overhead` profile' "$README" \
        || fail 'README does not state that low-overhead applies the real low_overhead profile'
    grep_fixed 'wake attempts' "$WORKER_README" \
        || fail 'Worker README overstates or omits alert trigger scope'
    grep_fixed 'next_action_code' "$WORKER_README" \
        || fail 'Worker README does not document stable next-action codes'
    grep_fixed 'history_deferred: true' "$WORKER_README" \
        || fail 'Worker README does not document deferred KV history writes'
    grep_fixed 'history and quota-incident work were queued through Cloudflare' "$WORKER_README" \
        || fail 'Worker README does not document deferred quota-incident work'
    grep_fixed '`quota_incident_recorded: false` while deferred' "$WORKER_README" \
        || fail 'Worker README claims deferred quota-incident writes completed'
    grep_fixed 'Do not paste the GitHub token into G2ray' "$WORKER_README" \
        || fail 'Worker README does not mirror the token handling warning'
    grep_fixed 'read -rsp "Wake secret: " WAKE_SECRET' "$README" \
        || fail 'README still encourages typing the wake secret directly into curl commands'
    grep_fixed 'read -rsp "Wake secret: " WAKE_SECRET' "$WORKER_README" \
        || fail 'Worker README still encourages typing the wake secret directly into curl commands'
    grep_fixed 'keeps the wake secret out of shell history' "$README" \
        || fail 'README does not explain safer wake secret CLI handling'
    grep_fixed 'https://github.com/settings/tokens/new?scopes=codespace' "$README" \
        || fail 'README does not give a direct GitHub token creation path'
    grep_fixed 'CODESPACE_NAME`: **Plaintext** variable' "$README" \
        || fail 'README does not clarify CODESPACE_NAME as a plaintext Cloudflare variable'
    grep_fixed 'GITHUB_TOKEN`: **Secret** variable' "$README" \
        || fail 'README does not clarify GITHUB_TOKEN as a Cloudflare secret'
    grep_fixed 'CODESPACE_PORT`: **Plaintext** variable only if you changed `XRAY_PORT`' "$README" \
        || fail 'README does not document the optional CODESPACE_PORT Worker binding'
    grep_fixed 'route_ready: true' "$README" \
        || fail 'README does not explain successful Worker route readiness'
    grep_fixed 'route_ready: false' "$README" \
        || fail 'README does not explain Worker route settling failures'
    grep_fixed 'with or without `https://`, and with or without `/wake`' "$README" \
        || fail 'README does not clarify accepted Worker URL formats'
    grep_fixed '| Plaintext | `CODESPACE_NAME`' "$WORKER_README" \
        || fail 'Worker README does not clarify CODESPACE_NAME dashboard binding type'
    grep_fixed 'CODESPACE_PORT` as a **Plaintext** variable only if you changed' "$WORKER_README" \
        || fail 'Worker README does not document the optional CODESPACE_PORT binding'
    grep_fixed '| Secret | `GITHUB_TOKEN`' "$WORKER_README" \
        || fail 'Worker README does not clarify GITHUB_TOKEN as a secret'
    grep_fixed '| Secret | `WAKE_SECRET`' "$WORKER_README" \
        || fail 'Worker README does not clarify WAKE_SECRET as a secret'
    grep_fixed 'route_ready: false` with HTTP `404`' "$WORKER_README" \
        || fail 'Worker README does not explain the route-settling response'
    grep_fixed 'notification_status: "deferred"' "$WORKER_README" \
        || fail 'Worker README does not explain deferred notification status'
    grep_fixed 'VS Code Desktop' "$README" \
        || fail 'README does not mention VS Code Desktop fallback for slow browser Codespaces'
    pass 'docs cover panel waker setup'
}

test_background_supervisor_ownership_is_strict() {
    grep_fixed 'background_supervisor_token_current()' "$SCRIPT" \
        || fail 'background supervisor loop does not verify its token remains current'
    grep_fixed 'write_background_supervisor_heartbeat()' "$SCRIPT" \
        || fail 'background supervisor heartbeat does not carry ownership data'
    grep_fixed 'background_supervisor_heartbeat_matches()' "$SCRIPT" \
        || fail 'fresh heartbeat cannot prove supervisor ownership when proc env is unavailable'
    grep_fixed '_atomic_write "$BG_TASKS_HEARTBEAT_FILE" "${BASHPID:-$$} ${G2RAY_BG_TASK_TOKEN:-} ${now}"' "$SCRIPT" \
        || fail 'heartbeat does not atomically persist pid token and timestamp together'
    awk '
        /background_supervisor_status\(\)/ { in_fn=1 }
        in_fn && /background_supervisor_heartbeat_timestamp/ { saw_timestamp=1 }
        in_fn && /^}/ { exit }
        END { exit saw_timestamp ? 0 : 1 }
    ' "$SCRIPT" \
        || fail 'background supervisor status still cannot read structured heartbeat timestamps'
    grep_fixed 'supervisor_superseded' "$SCRIPT" \
        || fail 'superseded background supervisors do not self-exit'
    if grep_fixed 'legacy_bg_tasks_running "$p" || background_supervisor_heartbeat_running "$p"' "$SCRIPT"; then
        fail 'supervisor lifecycle still trusts legacy/heartbeat ownership for reuse or kill decisions'
    fi
    pass 'background supervisor ownership is strict'
}

test_exports_filter_unusable_fallback_routes() {
    grep_fixed 'usable_fallback_ips()' "$SCRIPT" \
        || fail 'fallback exports cannot filter unusable route IPs'
    grep_fixed 'cached_usable_fallback_ips' "$SCRIPT" \
        || fail 'fallback exports do not consume measured usable route health'
    grep_fixed 'generate_ip_links_from_list "$(usable_fallback_ips || true)"' "$SCRIPT" \
        || fail 'generate_ip_links does not use the filtered fallback route list'
    grep_fixed 'ip_links=$(generate_ip_links_from_list "$fallback_ips" "$uuid" || true)' "$SCRIPT" \
        || fail 'ordered exports do not reuse a shared filtered fallback route list and active UUID'
    grep_fixed 'address=$(usable_fallback_ips | head -1 || true)' "$SCRIPT" \
        || fail 'recommended IP link does not prefer a usable fallback route'
    if awk '
        /usable_fallback_ips\(\)/ { in_fn=1; next }
        in_fn && /^}/ { exit }
        in_fn && /xhttp_probe_metrics|resolve_domain_ips/ { bad=1 }
        END { exit bad ? 0 : 1 }
    ' "$SCRIPT"; then
        fail 'fallback exports still synchronously probe or resolve routes'
    fi
    pass 'fallback exports use cached measured routes without serial live probes'
}

test_runtime_ready_rejects_started_but_unusable_route() {
    awk '
        /_ensure_runtime_ready_impl\(\)/ { in_fn=1 }
        in_fn && /started_route_unusable/ { saw_started_unusable=1 }
        saw_started_unusable && /started_route_ready/ { saw_ready=1 }
        saw_started_unusable && /started_route_still_unusable/ { saw_still_bad=1 }
        saw_started_unusable && /return 1/ { saw_failure=1 }
        in_fn && /^}/ { exit }
        END { exit (saw_started_unusable && saw_ready && saw_still_bad && saw_failure) ? 0 : 1 }
    ' "$SCRIPT" \
        || fail 'ensure_runtime_ready can still return success after a newly-started engine has an unusable XHTTP route'
    grep_fixed 'ensure_runtime_ready() {' "$SCRIPT" \
        || fail 'ensure_runtime_ready wrapper is missing'
    grep_fixed 'with_runtime_lock _ensure_runtime_ready_impl "$@"' "$SCRIPT" \
        || fail 'ensure_runtime_ready is not serialized by the runtime lock'
    pass 'runtime readiness rejects newly-started unusable XHTTP routes'
}

test_runtime_files_are_private_and_tempfiles_are_unique() {
    grep_fixed 'umask 077' "$SCRIPT" \
        || fail 'runtime files are not created with a private umask'
    grep_fixed 'DATA_DIR="${G2RAY_DATA_DIR:-$BASE_DIR/data}"' "$SCRIPT" \
        || fail 'data dir cannot be redirected for hermetic behavior tests'
    grep_fixed 'LOG_DIR="${G2RAY_LOG_DIR:-$BASE_DIR/logs}"' "$SCRIPT" \
        || fail 'log dir cannot be redirected for hermetic behavior tests'
    grep_fixed 'chmod 600 "$UUID_FILE" "$CONFIG_FILE"' "$SCRIPT" \
        || fail 'generated UUID/config files are not explicitly chmod 600'
    if grep_fixed 'local tmp="/tmp/g2ray_remote.sh"' "$SCRIPT"; then
        fail 'self-update still uses a predictable /tmp path'
    fi
    if grep_fixed '> /tmp/gas_resp.txt' "$SCRIPT"; then
        fail 'removed public-sharing response still uses a predictable /tmp path'
    fi
    grep_fixed 'mktemp "${TMPDIR:-/tmp}/g2ray_remote.XXXXXX"' "$SCRIPT" \
        || fail 'self-update does not stage downloads through mktemp'
    pass 'runtime files are private and tempfiles are unique'
}

test_logs_are_bounded_and_quota_is_cycle_aware() {
    grep_fixed 'rotate_log_file()' "$SCRIPT" \
        || fail 'script has no log rotation helper'
    grep_fixed 'G2RAY_LOG_MAX_BYTES' "$SCRIPT" \
        || fail 'app log cap is not configurable'
    grep_fixed 'rotate_log_file "$LOG_FILE"' "$SCRIPT" \
        || fail 'g2ray app log is not capped'
    grep_fixed 'rotate_log_file "$LOG_DIR/xray-error.log"' "$SCRIPT" \
        || fail 'xray error log is not capped'
    grep_fixed 'QUOTA_CYCLE_FILE=' "$SCRIPT" \
        || fail 'quota estimate has no persisted billing cycle marker'
    grep_fixed 'reset_monthly_quota_if_needed()' "$SCRIPT" \
        || fail 'quota estimate is not monthly-cycle aware'
    grep_fixed 'G2RAY_QUOTA_SECONDS' "$SCRIPT" \
        || fail 'quota seconds are not configurable'
    grep_fixed 'Local 2-core quota estimate' "$SCRIPT" \
        || fail 'quota panel does not label itself as a local estimate'
    grep_fixed 'storage quota, not traffic quota' "$README" \
        || fail 'README does not distinguish GitHub storage quota from VPN traffic'
    pass 'logs are bounded and quota estimate is cycle aware'
}

test_fallback_link_count_is_capped() {
    grep_fixed 'MAX_FALLBACK_LINKS=' "$SCRIPT" \
        || fail 'fallback link cap is not configurable'
    grep_fixed 'MAX_FALLBACK_LINKS="${G2RAY_MAX_FALLBACK_LINKS:-30}"' "$SCRIPT" \
        || fail 'fallback link default is not 30 exported IP routes'
    grep_fixed 'ROUTE_MONITOR_MAX_CANDIDATES="${G2RAY_ROUTE_MONITOR_MAX_CANDIDATES:-40}"' "$SCRIPT" \
        || fail 'route monitor default does not scan enough candidates for 30 exports'
    grep_fixed '(( max > 64 )) && max=64' "$SCRIPT" \
        || fail 'route monitor hard cap is not bounded at 64 candidates'
    grep_fixed '(( index > max_links )) && break' "$SCRIPT" \
        || fail 'fallback link generation does not cap weak extra routes'
    grep_fixed 'G2RAY_MAX_FALLBACK_LINKS' "$README" \
        || fail 'README does not document the fallback link cap'
    grep_fixed 'G2RAY_DNS_CACHE_TTL_SEC' "$README" \
        || fail 'README does not document the DNS candidate cache TTL'
    grep_fixed 'Default: `30`' "$README" \
        || fail 'README does not document 30 exported fallback links by default'
    grep_fixed 'up to 30 usable IP fallback configs plus the domain config' "$README" \
        || fail 'README prose does not match the 30 fallback-link default'
    if grep_fixed 'up to 20 usable IP fallback configs' "$README"; then
        fail 'README still contains stale 20 fallback-link wording'
    fi
    grep_fixed 'Default: `40`, hard-capped at `64`' "$README" \
        || fail 'README does not document the widened bounded route scanner default'
    grep_fixed 'WIDE_DNS_DISCOVERY="${G2RAY_WIDE_DNS_DISCOVERY:-1}"' "$SCRIPT" \
        || fail 'wide DNS discovery is not enabled by default'
    grep_fixed 'G2RAY_WIDE_DNS_ECS_SUBNETS' "$SCRIPT" \
        || fail 'wide DNS discovery has no configurable ECS subnet list'
    grep_fixed 'import_manual_route_candidates_from_text()' "$SCRIPT" \
        || fail 'route manager has no batch manual route import helper'
    grep_fixed 'Batch Import Manual IPv4s' "$SCRIPT" \
        || fail 'route manager does not expose batch manual route import in the panel'
    grep_fixed 'G2RAY_WIDE_DNS_DISCOVERY' "$README" \
        || fail 'README does not document wide DNS discovery'
    pass 'fallback link count is capped and documented'
}

test_ci_runs_static_regressions() {
    [[ -f "$CI_WORKFLOW" ]] || fail 'static test GitHub Actions workflow is missing'
    grep_fixed 'bash -n ./g2ray.sh' "$CI_WORKFLOW" \
        || fail 'CI workflow does not syntax-check g2ray.sh'
    grep_fixed 'shellcheck -S error' "$CI_WORKFLOW" \
        || fail 'CI workflow does not run ShellCheck for critical shell issues'
    grep_fixed 'node-version: 22' "$CI_WORKFLOW" \
        || fail 'CI does not pin Node 22 for current Wrangler'
    grep_fixed 'npm ci' "$CI_WORKFLOW" \
        || fail 'CI does not install pinned Worker dependencies'
    grep_fixed 'npm run check' "$CI_WORKFLOW" \
        || fail 'CI workflow does not syntax-check the Worker script'
    grep_fixed 'npm test' "$CI_WORKFLOW" \
        || fail 'CI workflow does not run Worker behavior tests through package scripts'
    grep_fixed 'npm run dry-run' "$CI_WORKFLOW" \
        || fail 'CI workflow does not dry-run bundle the Worker with pinned Wrangler'
    grep_fixed 'node scripts/dry-run.mjs' "$WORKER_DIR/package.json" \
        || fail 'Worker dry-run package script is not self-contained'
    grep_fixed 'bash ./tests/g2ray_static_tests.sh' "$CI_WORKFLOW" \
        || fail 'CI workflow does not run the static regression suite'
    grep_fixed 'bash ./tests/g2ray_behavior_tests.sh' "$CI_WORKFLOW" \
        || fail 'CI workflow does not run the behavior regression suite'
    grep_fixed 'working-directory: worker/codespace-waker' "$CI_WORKFLOW" \
        || fail 'CI workflow does not run Worker package scripts from the Worker directory'
    grep_fixed 'LC_ALL: C.UTF-8' "$CI_WORKFLOW" \
        || fail 'CI workflow does not pin a UTF-8 locale for README/static text checks'
    grep_fixed 'permissions:' "$CI_WORKFLOW" \
        || fail 'CI workflow does not set explicit permissions'
    grep_fixed 'contents: read' "$CI_WORKFLOW" \
        || fail 'CI workflow does not use least-privilege read-only contents permission'
    if grep_regex '^[[:space:]]+[A-Za-z_-]+:[[:space:]]+write[[:space:]]*$' "$CI_WORKFLOW"; then
        fail 'CI workflow grants a write permission scope'
    fi
    grep_fixed 'concurrency:' "$CI_WORKFLOW" \
        || fail 'CI workflow does not cancel obsolete duplicate runs'
    grep_fixed 'cancel-in-progress: true' "$CI_WORKFLOW" \
        || fail 'CI workflow concurrency does not cancel in-progress duplicates'
    grep_fixed 'fast-tests:' "$CI_WORKFLOW" \
        || fail 'CI workflow does not split fast tests into a dedicated job'
    grep_fixed 'extended-tests:' "$CI_WORKFLOW" \
        || fail 'CI workflow does not split slower extended checks into a dedicated job'
    grep_fixed 'timeout-minutes: 20' "$CI_WORKFLOW" \
        || fail 'CI fast job has no bounded timeout'
    grep_fixed 'timeout-minutes: 30' "$CI_WORKFLOW" \
        || fail 'CI extended job has no bounded timeout'
    grep_fixed 'needs: fast-tests' "$CI_WORKFLOW" \
        || fail 'CI extended job does not wait for fast tests'
    grep_fixed 'paths-ignore:' "$CI_WORKFLOW" \
        || fail 'CI workflow does not use path filters for asset-only churn'
    [[ "$(grep -Fc '      - "assets/**"' "$CI_WORKFLOW")" -eq 2 ]] \
        || fail 'CI workflow path filters should ignore only assets on push and pull_request'
    grep_fixed 'bash ./g2ray.sh bench --json --mock' "$CI_WORKFLOW" \
        || fail 'CI workflow does not run deterministic benchmark budgets'
    pass 'CI runs split, bounded shell/Worker regressions and benchmark budgets'
}

test_headless_benchmark_path_is_documented_and_guarded() {
    grep_fixed 'XHTTP_PATH_CACHE_FILE=' "$SCRIPT" \
        || fail 'script does not define the cached XHTTP config path file'
    grep_fixed 'file_fingerprint "$CONFIG_FILE"' "$SCRIPT" \
        || fail 'XHTTP config path cache is not keyed by config content'
    grep_fixed 'bench_json()' "$SCRIPT" \
        || fail 'script does not expose headless benchmark JSON'
    grep_fixed 'bench_budget_value()' "$SCRIPT" \
        || fail 'benchmark budgets do not sanitize environment overrides before JSON output'
    grep_fixed 'bench_platform_multiplier()' "$SCRIPT" \
        || fail 'benchmark budgets do not account for Windows Git Bash process overhead'
    grep_fixed 'bench_rebind_runtime_paths "$tmp"' "$SCRIPT" \
        || fail 'mock benchmarks do not isolate runtime state in a temporary root'
    grep_fixed 'G2RAY_BENCH_PREINIT_TMP=' "$SCRIPT" \
        || fail 'mock benchmark dispatch does not redirect runtime paths before top-level initialization'
    grep_fixed 'G2RAY_DATA_DIR="$G2RAY_BENCH_PREINIT_TMP/data"' "$SCRIPT" \
        || fail 'mock benchmark dispatch can still touch the real data directory before isolation'
    grep_fixed 'G2RAY_LOG_DIR="$G2RAY_BENCH_PREINIT_TMP/logs"' "$SCRIPT" \
        || fail 'mock benchmark dispatch can still touch the real log directory before isolation'
    grep_fixed 'config_path_cache)' "$SCRIPT" \
        || fail 'benchmark budget set does not cover config path caching'
    grep_fixed 'recover_json_contract)' "$SCRIPT" \
        || fail 'benchmark budget set does not cover recover --json contract speed'
    grep_fixed 'LAST_GOOD_ROUTE_MAX_AGE_SEC="${G2RAY_LAST_GOOD_ROUTE_MAX_AGE_SEC:-1800}"' "$SCRIPT" \
        || fail 'script does not bound stale last-good route preference'
    grep_fixed 'last_good_route_fresh_value()' "$SCRIPT" \
        || fail 'script does not decay stale last-good route preference before export ordering'
    grep_fixed 'checked_epoch=$(date -u -d "$checked" +%s' "$SCRIPT" \
        || fail 'last-good route freshness does not use saved checked_at timestamps'
    grep -Eq 'unset .*G2RAY_BENCH_MOCK.*G2RAY_BENCH_ISOLATED|unset .*G2RAY_BENCH_ISOLATED.*G2RAY_BENCH_MOCK' "$BEHAVIOR_TESTS" \
        || fail 'behavior test harness can leak benchmark isolation state between tests'
    grep_fixed 'G2RAY_LAST_GOOD_ROUTE_MAX_AGE_SEC' "$README" \
        || fail 'README does not document the last-good route decay knob'
    grep_fixed 'bash ./g2ray.sh bench --json --mock' "$README" \
        || fail 'README does not document the benchmark budget command'
    grep_fixed 'isolated mocked performance budget checks' "$README" \
        || fail 'README does not explain that mock benchmarks avoid live state and network probes'
    pass 'headless benchmark budgets are exposed, isolated, and documented'
}

test_xhttp_route_settling_is_observable() {
    grep_fixed 'BG_TASKS_VERSION_FILE=' "$SCRIPT" \
        || fail 'background supervisor does not record a script version marker'
    grep_fixed 'background_supervisor_version()' "$SCRIPT" \
        || fail 'script cannot fingerprint the active background supervisor version'
    grep_fixed 'stop_background_tasks()' "$SCRIPT" \
        || fail 'script cannot stop stale background supervisors after updates'
    grep_fixed 'background_supervisor_version_matches' "$SCRIPT" \
        || fail 'start_background_tasks reuses stale supervisors after script updates'
    grep_fixed 'xhttp_probe_status()' "$SCRIPT" \
        || fail 'script does not provide a dedicated XHTTP edge probe'
    grep_fixed 'xhttp_status_usable()' "$SCRIPT" \
        || fail 'script does not classify XHTTP probe status codes'
    grep_fixed '[[ "$code" == "200" ]]' "$SCRIPT" \
        || fail 'XHTTP readiness does not require the pinned OPTIONS handler success response'
    grep_fixed 'xhttp_route_usable=' "$SCRIPT" \
        || fail 'health/reconnect logs do not expose XHTTP route usability'
    grep_fixed 'repair_codespace_port_route()' "$SCRIPT" \
        || fail 'script has no repair path for stale Codespaces port routing'
    grep_fixed 'route_unusable' "$SCRIPT" \
        || fail 'watchdog does not distinguish unusable XHTTP routing from a dead edge'
    grep_fixed 'XHTTP Probes' "$SCRIPT" \
        || fail 'diagnostics do not show local/external XHTTP probe results'
    grep_fixed 'Config    :' "$SCRIPT" \
        || fail 'diagnostics do not show XHTTP config path/mode/network summary'
    pass 'XHTTP route settling is observable and stale supervisors are replaced'
}

test_docs_and_public_configs_are_consistent() {
    if grep_fixed '1-to-17' "$README" || grep_fixed '1 to 17' "$README"; then
        fail 'README menu count is stale'
    fi
    grep_fixed 'numbered menu actions grouped by core controls' "$README" \
        || fail 'README does not describe the current grouped menu'
    if grep_fixed 'did now get shown' "$README"; then
        fail 'README still contains the "did now" typo'
    fi
    grep_fixed 'G2RAY_QR_MODE' "$README" \
        || fail 'README does not document G2RAY_QR_MODE'
    grep_fixed 'high-resolution QR PNG files under `data/qr/`' "$README" \
        || fail 'README does not document the scan-friendly QR PNG export'
    grep_fixed 'G2RAY_EXTRA_FALLBACK_IPS' "$README" \
        || fail 'README does not document G2RAY_EXTRA_FALLBACK_IPS'
    grep_fixed 'G2RAY_ROUTE_WAIT_SEC' "$README" \
        || fail 'README does not document startup route wait'
    grep_fixed 'configs-to-copy-for-mobile.txt' "$README" \
        || fail 'README does not document the mobile copy-link fallback for QR scan failures'
    grep_fixed 'Choose Your Codespace Region Before Creating It' "$README" \
        || fail 'README does not tell users to choose a region before creating a Codespace'
    grep_fixed 'You cannot move an existing Codespace to another region' "$README" \
        || fail 'README does not explain that region changes require a new Codespace'
    grep_fixed 'gh codespace create -R OWNER/REPO -l WestEurope --idle-timeout 240m' "$README" \
        || fail 'README does not include a CLI example for region selection'
    grep_fixed 'Linux recovery after Worker wake' "$README" \
        || fail 'README does not include Linux recovery troubleshooting after Worker wake'
    grep_fixed 'gh codespace ports visibility 443:public -c "$CS"' "$README" \
        || fail 'README Linux recovery flow does not include public port repair'
    grep_fixed 'Startup automation and port forwarding' "$README" \
        || fail 'README does not explain automatic startup port forwarding and its rebuild requirement'
    grep_fixed 'curl -sS -o /dev/null -w "route=%{http_code} time=%{time_total}s\n" -X OPTIONS "$APP"' "$README" \
        || fail 'README Linux recovery flow does not include an external route probe'
    grep_fixed '12) Server Location' "$README" \
        || fail 'README does not tell users to verify the observed exit location'
    [[ ! -e "$ROOT_DIR/configs.txt" ]] \
        || fail 'public configs.txt should not exist in the repo'
    grep_fixed '/configs.txt' "$GITIGNORE" \
        || fail '.gitignore does not block regenerated public configs.txt'
    grep_fixed 'Personal test and educational use only' "$README" \
        || fail 'README does not state personal/test-only use'
    grep_fixed 'Private Local Exports' "$README" \
        || fail 'README does not describe private local exports'
    grep_fixed 'This project intentionally does not publish a raw GitHub subscription URL' "$README" \
        || fail 'README does not explain that subscriptions are local-only'
    grep_fixed 'Local base64 subscription file' "$SCRIPT" \
        || fail 'config screen does not label the local subscription file'
    grep_fixed '"subscription_scope": "local_codespace_only"' "$SCRIPT" \
        || fail 'config metadata is not marked local-only'
    for removed in \
        'Community Donated Configs' \
        'Community Config Network' \
        'Donate Config' \
        'send_to_vless_forwarder' \
        'publish-subscription' \
        'raw.githubusercontent.com/shayanay80atomic/G2rayXCodeLeafy/main/configs.txt' \
        'shayanay80atomic/G2rayXCodeLeafy' \
        'shaunme32/G2rayXCodeLeafy' \
        'https://code-leafy.github.io/NetLeafy' \
        'https://t.me/CodeLeafy'
    do
        if grep_fixed "$removed" "$README" || grep_fixed "$removed" "$SCRIPT"; then
            fail "public config sharing reference remains: $removed"
        fi
    done
    grep_fixed 'insecure=0&allowInsecure=0' "$README" \
        || fail 'README does not document secure-by-default TLS verification in exported links'
    grep_fixed 'insecure=0&allowInsecure=0' "$SCRIPT" \
        || fail 'generated links are not secure-by-default for TLS verification'
    grep_fixed 'allowInsecure=1` can be tried manually as a compatibility workaround' "$README" \
        || fail 'README does not disclose the optional TLS verification compatibility tradeoff'
    pass 'docs and local-only config exports are consistent'
}

test_devcontainer_tooling_is_not_duplicated() {
    grep_fixed 'dnsutils' "$ROOT_DIR/.devcontainer/Dockerfile" \
        || fail 'Dockerfile does not install dnsutils for dig-based DNS resolution'
    grep_fixed 'python-is-python3' "$ROOT_DIR/.devcontainer/Dockerfile" \
        || fail 'Dockerfile does not provide python for the in-Codespace behavior test suite'
    grep_fixed 'node_22.x' "$ROOT_DIR/.devcontainer/Dockerfile" \
        || fail 'Dockerfile does not configure the Node 22 repository for Worker checks inside Codespace'
    grep_fixed 'apt-get update && apt-get install -y --no-install-recommends nodejs' "$ROOT_DIR/.devcontainer/Dockerfile" \
        || fail 'Dockerfile does not install NodeSource nodejs, which includes npm'
    grep_fixed 'shellcheck' "$ROOT_DIR/.devcontainer/Dockerfile" \
        || fail 'Dockerfile does not install shellcheck for local Bash linting'
    if grep_fixed 'vnstat' "$ROOT_DIR/.devcontainer/Dockerfile"; then
        fail 'Dockerfile still installs unused vnstat'
    fi
    if grep_fixed 'cli.github.com/packages' "$ROOT_DIR/.devcontainer/Dockerfile"; then
        fail 'Dockerfile still installs gh manually despite the devcontainer feature'
    fi
    grep_fixed 'ghcr.io/devcontainers/features/github-cli:1' "$ROOT_DIR/.devcontainer/devcontainer.json" \
        || fail 'devcontainer no longer installs gh through the github-cli feature'
    grep_fixed 'ghcr.io/devcontainers/features/sshd:1' "$ROOT_DIR/.devcontainer/devcontainer.json" \
        || fail 'devcontainer no longer enables sshd for gh codespace ssh remote access'
    if grep_fixed 'openssh-server' "$ROOT_DIR/.devcontainer/Dockerfile"; then
        fail 'Dockerfile manually installs openssh-server instead of using the devcontainer sshd feature'
    fi
    grep_fixed 'gh codespace ssh' "$README" \
        || fail 'README does not document remote Codespace SSH access'
    grep_fixed 'socks5://127.0.0.1:10808' "$README" \
        || fail 'README does not document the working proxy scheme for gh codespace ssh/rebuild'
    if grep_fixed '/workspaces/G2rayXCodeLeafy' "$README"; then
        fail 'README hard-codes the workspace folder and breaks renamed forks'
    fi
    if grep_fixed 'socks5h://127.0.0.1:10808' "$README"; then
        fail 'README still documents socks5h for gh codespace ssh/rebuild, which breaks Codespaces tunnel RPC on this setup'
    fi
    grep_fixed 'node_22.x' "$ROOT_DIR/.devcontainer/Dockerfile" \
        || fail 'devcontainer does not install Node 22 for current Wrangler'
    grep_fixed '.devcontainer/Dockerfile text eol=lf' "$ROOT_DIR/.gitattributes" \
        || fail 'Dockerfile line endings are not pinned to LF'
    if grep_fixed 'assets/message.txt' "$ROOT_DIR/.gitattributes"; then
        fail 'removed message.txt asset is still pinned in .gitattributes'
    fi
    pass 'devcontainer tooling and LF policy are clean'
}

test_devcontainer_post_start_wrapper_is_present() {
    [[ -f "$POST_START_SCRIPT" ]] || fail 'devcontainer postStart wrapper script is missing'
    grep_fixed 'bash ./scripts/post-start.sh' "$ROOT_DIR/.devcontainer/devcontainer.json" \
        || fail 'devcontainer does not run the post-start wrapper'
    grep_fixed '--silent-start' "$POST_START_SCRIPT" \
        || fail 'post-start wrapper does not call the silent-start recovery path'
    grep_fixed 'post-start.log' "$POST_START_SCRIPT" \
        || fail 'post-start wrapper does not write a persistent post-start log'
    pass 'devcontainer post-start wrapper is present and wired'
}

test_menu_loop_and_link_output_are_tidy() {
    if grep_fixed '( fetch_remote_message >/dev/null 2>&1 & )' "$SCRIPT"; then
        fail 'menu loop still starts a redundant remote-message fetch every render'
    fi
    if grep_fixed 'fetch_remote_message' "$SCRIPT" || grep_fixed 'REMOTE_MESSAGE_FILE' "$SCRIPT"; then
        fail 'removed remote-message fetch path still exists'
    fi
    if awk '
        /generate_ip_links\(\)/ { in_fn=1 }
        in_fn && /generate_ordered_links\(\)/ { exit }
        in_fn && /generate_link_for_address "\$address" "-ip\$\{index\}"/ { saw_link=1; next }
        in_fn && saw_link && /printf '\''\\n'\''/ { bad=1; exit }
        in_fn && saw_link && /printed=true/ { saw_link=0 }
        END { exit bad ? 0 : 1 }
    ' "$SCRIPT"; then
        fail 'generate_ip_links still appends an unconditional blank line after each link'
    fi
    grep_fixed 'frames=("-" "\\" "|" "/")' "$SCRIPT" \
        || fail 'wait_for_port does not provide animated progress frames'
    grep_fixed 'Initializing engine... (${i}s)' "$SCRIPT" \
        || fail 'wait_for_port does not show elapsed initialization time'
    grep_fixed 'Toggle Anti-Sleep Mode ($(echo -e "$_KA_LABEL"))' "$SCRIPT" \
        || fail 'anti-sleep toggle menu item does not show the current state inline'
    grep_fixed 'Toggle Latency Focus Mode ($(echo -e "$_LATENCY_LABEL"))' "$SCRIPT" \
        || fail 'latency focus toggle menu item does not show the current state inline'
    grep_fixed 'latency_focus_enabled()' "$SCRIPT" \
        || fail 'latency focus mode helper is missing'
    if awk '
        /case \$_choice in/ { in_menu=1; next }
        in_menu && /^[[:space:]]*1\)/ { in_config=1; next }
        in_config && /^[[:space:]]*2\)/ { exit }
        in_config && /ipinfo\.io/ { found=1; exit }
        END { exit found ? 0 : 1 }
    ' "$SCRIPT"; then
        fail 'config/QR screen still performs a slow external ipinfo lookup'
    fi
    pass 'menu loop and link output are tidy'
}

test_publish_helpers_default_to_public_visibility() {
    grep_fixed "Visibility - type 'private' or 'public' [public]" "$ROOT_DIR/publish-to-github.ps1" \
        || fail 'Windows publish helper does not default visibility to public'
    grep_fixed '.Trim().ToLowerInvariant()' "$ROOT_DIR/publish-to-github.ps1" \
        || fail 'Windows publish helper does not normalize visibility input'
    grep_fixed 'Invalid visibility' "$ROOT_DIR/publish-to-github.ps1" \
        || fail 'Windows publish helper does not reject invalid visibility input'
    grep_fixed 'if ($LASTEXITCODE -ne 0) { Fail "Could not commit local changes." }' "$ROOT_DIR/publish-to-github.ps1" \
        || fail 'Windows publish helper does not fail when git commit fails'
    grep_fixed "Visibility - type 'private' or 'public' [public]:" "$ROOT_DIR/publish-to-github.sh" \
        || fail 'Bash publish helper does not default visibility to public'
    grep_fixed 'normalize_visibility()' "$ROOT_DIR/publish-to-github.sh" \
        || fail 'Bash publish helper does not validate visibility input'
    grep_fixed 'Invalid visibility' "$ROOT_DIR/publish-to-github.sh" \
        || fail 'Bash publish helper does not reject invalid visibility input'
    grep_fixed 'Ensure-GitIdentity' "$ROOT_DIR/publish-to-github.ps1" \
        || fail 'Windows publish helper does not self-configure missing local git identity'
    grep_fixed '$user@users.noreply.github.com' "$ROOT_DIR/publish-to-github.ps1" \
        || fail 'Windows publish helper does not use a GitHub noreply email fallback'
    grep_fixed 'ensure_git_identity()' "$ROOT_DIR/publish-to-github.sh" \
        || fail 'Bash publish helper does not self-configure missing local git identity'
    grep_fixed 'git config user.email "${user}@users.noreply.github.com"' "$ROOT_DIR/publish-to-github.sh" \
        || fail 'Bash publish helper does not use a GitHub noreply email fallback'
    pass 'publish helpers default to public visibility'
}

test_wait_for_port_increment_is_set_e_safe
test_process_management_uses_pid_file
test_background_tasks_uses_owned_pid_file
test_control_plane_optimizations_are_present
test_background_tasks_require_config
test_recovery_hot_paths_are_bounded_and_validated
test_latency_focus_keeps_slow_route_export_refresh
test_interactive_route_refreshes_are_bounded
test_supervisor_handles_resume_and_stale_code
test_stale_temp_sweep_is_present
test_port_visibility_failures_are_handled
test_self_update_is_opt_in
test_exit_trap_preserves_failures
test_generated_files_are_ignored
test_support_bundle_has_safe_entrypoint
test_shell_files_are_lf_normalized
test_panel_script_is_executable
test_xray_version_can_be_pinned
test_generated_config_uses_resilient_dns_fallback
test_daemon_logs_use_copytruncate_rotation
test_tcp_fast_open_is_gated_and_outbound_only
test_connection_keepalive_and_halfclose_are_tuned
test_xhttp_links_carry_measured_upload_tuning
test_performance_profile_is_persistent_and_bench_is_isolated
test_generated_links_include_domain_and_ip_variants
test_websocket_fallback_is_advanced_opt_in
test_terminal_branding_is_customized_red
test_runtime_diagnostics_logging
test_xhttp_route_settling_is_observable
test_runtime_control_paths_are_hardened
test_startup_does_not_reconnect_healthy_runtime
test_interactive_attach_readies_runtime_before_background_watchdog
test_self_heal_uses_reconnect_backoff
test_probe_and_gh_commands_are_bounded
test_diagnostics_show_latency_and_supervisor_state
test_diagnostics_show_self_heal_state
test_diagnostics_show_last_known_state
test_diagnostics_show_resume_gap_state
test_local_reopen_helper_is_documented
test_cloudflare_worker_waker_is_safe_to_publish
test_worker_dashboard_and_history_features
test_quota_survival_layer_is_present
test_worker_wake_edge_cases_are_hardened
test_worker_rate_limits_and_classifies_github_errors
test_route_candidate_monitor_is_bounded
test_route_candidate_manager_and_live_monitor_are_present
test_first_run_recovery_card_is_present
test_soft_recovery_and_route_memory_are_present
test_route_export_and_reconnect_edges_are_hardened
test_interactive_config_generation_failures_are_guarded
test_panel_guides_cloudflare_waker_setup
test_diagnostics_show_external_waker_state
test_docs_cover_panel_waker_setup
test_background_supervisor_ownership_is_strict
test_exports_filter_unusable_fallback_routes
test_runtime_ready_rejects_started_but_unusable_route
test_runtime_files_are_private_and_tempfiles_are_unique
test_logs_are_bounded_and_quota_is_cycle_aware
test_fallback_link_count_is_capped
test_ci_runs_static_regressions
test_headless_benchmark_path_is_documented_and_guarded
test_docs_and_public_configs_are_consistent
test_devcontainer_tooling_is_not_duplicated
test_devcontainer_post_start_wrapper_is_present
test_menu_loop_and_link_output_are_tidy
test_publish_helpers_default_to_public_visibility

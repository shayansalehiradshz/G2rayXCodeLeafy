#!/usr/bin/env bash
set -euo pipefail

# Real configuration rendering in a private fixture. No live engine, ports,
# exports, GitHub requests or keepalive sessions are changed by this test.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="${1:-/usr/local/bin/xray}"
[[ -x "$CORE" ]] || { printf 'Xray is not executable: %s\n' "$CORE" >&2; exit 1; }
TMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/g2ray-option-matrix.XXXXXX")
trap 'rm -rf -- "$TMP_ROOT"' EXIT
export G2RAY_SOURCE_ONLY=1 G2RAY_DATA_DIR="$TMP_ROOT/data" G2RAY_LOG_DIR="$TMP_ROOT/logs"
export G2RAY_TCP_FAST_OPEN=0 G2RAY_LATENCY_FOCUS=0 G2RAY_LOW_OVERHEAD=0
unset G2RAY_XHTTP_MODE G2RAY_XHTTP_LINK_TUNING G2RAY_XHTTP_EXTRA_JSON G2RAY_WS_FRONT_DOMAIN
# shellcheck source=../g2ray.sh
source "$ROOT_DIR/g2ray.sh"
XRAY_BIN="$CORE"
CODESPACE_NAME=validation-space
PORT_DOMAIN=validation-space-443.app.github.dev
WS_PORT_DOMAIN=validation-space-8443.app.github.dev
GITHUB_USER=fixture
mkdir -p "$DATA_DIR" "$LOG_DIR"

sudo() { "$@"; }
xray_running() { return 1; }
start_xray() { return 0; }
wait_for_port() { return 0; }
ensure_codespace_port_public() { return 0; }
ensure_optional_ws_port_public() { return 0; }
ensure_route_cache_for_export() { return 0; }
refresh_config_exports() { return 0; }
xhttp_probe_metrics() { printf '200 1 ready\n'; }

configs=0 links=0
"$XRAY_BIN" version | sed -n '1p'
for profile in balanced low_latency streaming unstable_mobile low_overhead max_throughput; do
    for override in normal low_overhead low_latency; do
        # Environment overrides exercise every saved profile without invoking
        # runtime work. Actual toggle transitions are checked separately below.
        G2RAY_LOW_OVERHEAD=0 G2RAY_LATENCY_FOCUS=0
        case "$override" in
            low_overhead) G2RAY_LOW_OVERHEAD=1 ;;
            low_latency) G2RAY_LATENCY_FOCUS=1 ;;
        esac
        set_performance_profile "$profile"
        expected="$profile"
        [[ "$override" == normal ]] || expected="$override"
        for ws in enabled disabled; do
            if [[ "$ws" == enabled ]]; then enable_ws_fallback_mode; else disable_ws_fallback_mode; fi
            generate_config > "$TMP_ROOT/generate.log" 2>&1 || { cat "$TMP_ROOT/generate.log"; exit 1; }
            policy=$(performance_profile_settings "$expected" | jq -Rn '[inputs | split("=") | {key: .[0], value: .[1]}] | from_entries')
            jq -e --argjson p "$policy" --arg ws "$ws" '
              (.policy.levels["0"] | {handshake, connIdle, uplinkOnly, downlinkOnly, bufferSize}) ==
              ($p | {handshake: (.handshake|tonumber), connIdle: (.connIdle|tonumber),
                uplinkOnly: (.uplinkOnly|tonumber), downlinkOnly: (.downlinkOnly|tonumber), bufferSize: (.bufferSize|tonumber)})
              and .log.loglevel == $p.loglevel
              and (any(.inbounds[]; .tag == "vless-ws") == ($ws == "enabled"))
              and ([.inbounds[] | select(.protocol == "vless") | .sniffing.routeOnly] | all(. == true))
              and (any(.inbounds[]; .tag == "vless-in" and .streamSettings.xhttpSettings.mode == "auto"))
              and (any(.outbounds[]; .tag == "direct" and .streamSettings.sockopt.tcpFastOpen == false))
            ' "$CONFIG_FILE" >/dev/null
            uuid=$(active_vless_uuid)
            config_hash=$(sha256sum "$CONFIG_FILE")
            for mode in packet-up stream-up stream-one auto; do
                set_xhttp_mode "$mode"
                for tuning in on off; do
                    set_xhttp_link_tuning "$tuning"
                    link=$(generate_domain_link)
                    [[ "$link" == "vless://$uuid@$PORT_DOMAIN:"* && "$link" == *"&mode=$mode"* ]]
                    [[ "$link" == *"&sni=$PORT_DOMAIN&"* && "$link" == *"&host=$PORT_DOMAIN&"* ]]
                    if [[ "$tuning" == on ]]; then [[ "$link" == *'&extra=%7B'* ]]; else [[ "$link" != *'&extra='* ]]; fi
                    links=$((links + 1))
                done
            done
            # Link-only settings must never restart/rewrite the accepting server.
            [[ "$(sha256sum "$CONFIG_FILE")" == "$config_hash" ]]
            if [[ "$ws" == enabled ]]; then
                set_ws_front_domain 'https://front.fixture.example/'
                for alpn in h2 h1 blank; do
                    link=$(generate_ws_front_link "$alpn")
                    [[ "$link" == "vless://$uuid@front.fixture.example:"* ]]
                    [[ "$link" == *'&sni=front.fixture.example&'* && "$link" == *'&host=front.fixture.example&'* ]]
                    [[ "$link" == *'&path=%2Fws#'* ]]
                    case "$alpn" in
                        h2) [[ "$link" == *'&alpn=h2&'* ]] ;;
                        h1) [[ "$link" == *'&alpn=http%2F1.1&'* ]] ;;
                        blank) [[ "$link" != *'&alpn='* ]] ;;
                    esac
                    links=$((links + 1))
                done
                clear_ws_front_domain
                ! generate_ws_front_link >/dev/null
            else
                ! generate_ws_domain_link >/dev/null
            fi
            configs=$((configs + 1))
            printf 'PASS: saved=%s override=%s WS=%s\n' "$profile" "$override" "$ws"
        done
    done
done

# Persisted mode transitions must be mutually exclusive and keep credentials.
G2RAY_LOW_OVERHEAD=0 G2RAY_LATENCY_FOCUS=0
set_performance_profile streaming
uuid=$(active_vless_uuid)
enable_low_overhead_mode > "$TMP_ROOT/toggle.log" 2>&1
low_overhead_enabled && ! latency_focus_enabled
[[ "$(effective_performance_profile)" == low_overhead && "$(active_vless_uuid)" == "$uuid" ]]
enable_latency_focus_mode >> "$TMP_ROOT/toggle.log" 2>&1
latency_focus_enabled && ! low_overhead_enabled
[[ "$(effective_performance_profile)" == low_latency && "$(active_vless_uuid)" == "$uuid" ]]
disable_latency_focus_mode >> "$TMP_ROOT/toggle.log" 2>&1
! latency_focus_enabled && ! low_overhead_enabled
[[ "$(effective_performance_profile)" == streaming && "$(active_vless_uuid)" == "$uuid" ]]
enable_latency_focus_mode >> "$TMP_ROOT/toggle.log" 2>&1
enable_low_overhead_mode >> "$TMP_ROOT/toggle.log" 2>&1
low_overhead_enabled && ! latency_focus_enabled
disable_low_overhead_mode >> "$TMP_ROOT/toggle.log" 2>&1
[[ "$(effective_performance_profile)" == streaming && "$(active_vless_uuid)" == "$uuid" ]]
printf 'PASS: real Xray accepted %s server combinations; %s export combinations; both mode transition orders preserved UUID/profile.\n' "$configs" "$links"

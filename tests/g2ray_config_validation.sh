#!/usr/bin/env bash
set -euo pipefail

# Optional Linux integration check. Validates configs without binding listeners
# or modifying the real runtime. Supply a separately verified Xray binary.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="${1:-/usr/local/bin/xray}"
FIXTURE_DIR=""
if [[ $# -gt 1 ]]; then
    [[ $# == 3 && "$2" == --fixture-dir && -d "$3" ]] \
        || { echo 'Usage: g2ray_config_validation.sh [xray] [--fixture-dir existing-private-directory]' >&2; exit 2; }
    FIXTURE_DIR=$(cd "$3" && pwd)
fi
[[ -x "$CORE" ]] || { echo "Xray binary is not executable: $CORE" >&2; exit 1; }
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/g2ray-config-validation.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT
export G2RAY_SOURCE_ONLY=1 G2RAY_DATA_DIR="$TMP_ROOT/data" G2RAY_LOG_DIR="$TMP_ROOT/logs"
export G2RAY_TCP_FAST_OPEN=0
export G2RAY_LATENCY_FOCUS=0 G2RAY_LOW_OVERHEAD=0
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

assert_profile_policy() {
    local expected
    expected=$(performance_profile_settings "$1" | jq -Rn '[inputs | split("=") | {key: .[0], value: .[1]}] | from_entries')
    jq -e --argjson expected "$expected" --arg ws "$2" '
      (.policy.levels["0"] | {handshake, connIdle, uplinkOnly, downlinkOnly, bufferSize}) ==
      ($expected | {handshake: (.handshake|tonumber), connIdle: (.connIdle|tonumber),
                    uplinkOnly: (.uplinkOnly|tonumber), downlinkOnly: (.downlinkOnly|tonumber),
                    bufferSize: (.bufferSize|tonumber)})
      and .log.loglevel == $expected.loglevel
      and (any(.inbounds[]?; .tag == "vless-ws" and .protocol == "vless" and .streamSettings.network == "ws") == ($ws == "enabled"))
    ' "$CONFIG_FILE" >/dev/null || { echo "Generated policy does not match requested profile: $1" >&2; return 1; }
}

"$XRAY_BIN" version | sed -n '1p'
for profile in balanced low_latency streaming unstable_mobile low_overhead max_throughput; do
    for ws in enabled disabled; do
        set_performance_profile "$profile"
        if [[ "$ws" == enabled ]]; then enable_ws_fallback_mode; else disable_ws_fallback_mode; fi
        generate_config > "$TMP_ROOT/generate.log" 2>&1 || { cat "$TMP_ROOT/generate.log"; exit 1; }
        assert_profile_policy "$profile" "$ws"
        jq -e '[.inbounds[] | select(.protocol == "vless") | .sniffing.routeOnly] | all(. == true)' "$CONFIG_FILE" >/dev/null
        jq -e '.outbounds[] | select(.tag == "direct") | .streamSettings.sockopt.tcpFastOpen == false' "$CONFIG_FILE" >/dev/null
        if [[ -n "$FIXTURE_DIR" ]]; then
            [[ ! -e "$FIXTURE_DIR/$profile-$ws.json" ]] \
                || { echo 'Refusing to overwrite an existing fixture' >&2; exit 1; }
            cp -- "$CONFIG_FILE" "$FIXTURE_DIR/$profile-$ws.json"
            chmod 600 "$FIXTURE_DIR/$profile-$ws.json"
            if [[ "$ws" == enabled ]]; then
                [[ ! -e "$FIXTURE_DIR/$profile.bundle.json" ]] \
                    || { echo 'Refusing to overwrite an existing fixture bundle' >&2; exit 1; }
                xhttp_link=$(generate_domain_link)
                ws_link=$(generate_ws_domain_link h1)
                [[ -n "$xhttp_link" && -n "$ws_link" ]] || { echo 'Fixture exports are missing' >&2; exit 1; }
                jq -n --arg xhttp "$xhttp_link" --arg ws "$ws_link" \
                    '{schema_version: 2, configs: [$xhttp, $ws]}' > "$FIXTURE_DIR/$profile.bundle.json"
                chmod 600 "$FIXTURE_DIR/$profile.bundle.json"
            fi
        fi
        # Exercise the same migration used for existing user configs.
        jq '(.inbounds[] | select(.protocol == "vless") | .sniffing.routeOnly) = false
            | (.outbounds[] | select(.tag == "direct") | .streamSettings.sockopt.tcpFastOpen) = true' "$CONFIG_FILE" > "$TMP_ROOT/old.json"
        mv "$TMP_ROOT/old.json" "$CONFIG_FILE"
        upgrade_config_dns
        xray_validate_config_file "$CONFIG_FILE"
        assert_profile_policy "$profile" "$ws"
        jq -e '[.inbounds[] | select(.protocol == "vless") | .sniffing.routeOnly] | all(. == true)' "$CONFIG_FILE" >/dev/null
        jq -e '.outbounds[] | select(.tag == "direct") | .streamSettings.sockopt.tcpFastOpen == false' "$CONFIG_FILE" >/dev/null
        printf 'PASS: real Xray accepts generated and migrated %s / WS %s\n' "$profile" "$ws"
    done
done

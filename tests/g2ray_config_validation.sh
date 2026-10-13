#!/usr/bin/env bash
set -euo pipefail

# Optional Linux integration check. Validates configs without binding listeners
# or modifying the real runtime. Supply a separately verified Xray binary.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="${1:-/usr/local/bin/xray}"
[[ -x "$CORE" ]] || { echo "Xray binary is not executable: $CORE" >&2; exit 1; }
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/g2ray-config-validation.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT
export G2RAY_SOURCE_ONLY=1 G2RAY_DATA_DIR="$TMP_ROOT/data" G2RAY_LOG_DIR="$TMP_ROOT/logs"
# shellcheck source=../g2ray.sh
source "$ROOT_DIR/g2ray.sh"
XRAY_BIN="$CORE"
CODESPACE_NAME=validation-space
PORT_DOMAIN=validation-space-443.app.github.dev
WS_PORT_DOMAIN=validation-space-8443.app.github.dev
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

"$XRAY_BIN" version | sed -n '1p'
for profile in balanced low_latency streaming low_overhead max_throughput; do
    for ws in enabled disabled; do
        printf '%s\n' "$profile" > "$PERFORMANCE_PROFILE_FILE"
        rm -f "$WS_FALLBACK_FILE" "$WS_FALLBACK_DISABLED_FILE"
        if [[ "$ws" == enabled ]]; then touch "$WS_FALLBACK_FILE"; else touch "$WS_FALLBACK_DISABLED_FILE"; fi
        generate_config > "$TMP_ROOT/generate.log" 2>&1 || { cat "$TMP_ROOT/generate.log"; exit 1; }
        jq -e '[.inbounds[] | select(.protocol == "vless") | .sniffing.routeOnly] | all(. == true)' "$CONFIG_FILE" >/dev/null
        # Exercise the same migration used for existing user configs.
        jq '(.inbounds[] | select(.protocol == "vless") | .sniffing.routeOnly) = false' "$CONFIG_FILE" > "$TMP_ROOT/old.json"
        mv "$TMP_ROOT/old.json" "$CONFIG_FILE"
        upgrade_config_dns
        xray_validate_config_file "$CONFIG_FILE"
        jq -e '[.inbounds[] | select(.protocol == "vless") | .sniffing.routeOnly] | all(. == true)' "$CONFIG_FILE" >/dev/null
        printf 'PASS: real Xray accepts generated and migrated %s / WS %s\n' "$profile" "$ws"
    done
done

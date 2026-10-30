#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
output="${1:?Pass a new output directory}"
pin=1b1647cd9ddf0abc34b981d4611fc03bcac1ca74
patch="$root/patches/xray-websocket-reader-26.10.21.patch"
handshake_patch="$root/patches/xray-xhttp-handshake-26.10.21.patch"
[[ "$(go env GOVERSION)" == go1.27.3 ]] || { printf 'Go 1.27.3 is required.\n' >&2; exit 1; }
[[ ! -e "$output/xray" && ! -e "$output/xray-build.properties" ]] || {
    printf 'Refusing to overwrite an existing candidate.\n' >&2; exit 1;
}
workspace="$(mktemp -d)"
trap 'rm -rf -- "$workspace"' EXIT
git -C "$workspace" init --quiet
git -C "$workspace" fetch --quiet --depth=1 https://github.com/XTLS/Xray-core.git "$pin"
git -C "$workspace" -c core.autocrlf=false checkout --quiet --detach FETCH_HEAD
[[ "$(git -C "$workspace" rev-parse HEAD)" == "$pin" ]]
git -C "$workspace" apply --check "$patch"
git -C "$workspace" apply "$patch"
git -C "$workspace" apply --check "$handshake_patch"
git -C "$workspace" apply "$handshake_patch"
cp "$root/fixtures/g2ray_ws_server_reader_test.go" \
    "$workspace/transport/internet/websocket/g2ray_ws_server_reader_test.go"
cp "$root/fixtures/g2ray_xhttp_handshake_test.go" \
    "$workspace/transport/internet/splithttp/g2ray_xhttp_handshake_test.go"
mkdir -p "$output"
output="$(cd "$output" && pwd)"
(
    cd "$workspace"
    go test -race ./transport/internet/websocket -run '^TestG2RayWS(ReadPreservesDataWithEOF|RealFramePreservesDataWithEOF)$' -count=3 -timeout=60s
    go test -race ./transport/internet/splithttp -run '^TestG2RayXHTTP' -count=3 -timeout=60s
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -buildvcs=false \
        -ldflags '-s -w -buildid= -X github.com/xtls/xray-core/core.build=1b1647c-g2ray-lifecycle' \
        -o "$output/xray" ./main
)
{
    printf 'xray.version=26.10.21\nxray.ref=%s\nxray.go=go1.27.3\n' "$pin"
    printf 'xray.websocket.patch.sha256=%s\n' "$(sha256sum "$patch" | cut -d' ' -f1)"
    printf 'xray.xhttp.handshake.patch.sha256=%s\n' "$(sha256sum "$handshake_patch" | cut -d' ' -f1)"
    printf 'xray.sha256=%s\n' "$(sha256sum "$output/xray" | cut -d' ' -f1)"
} > "$output/xray-build.properties"

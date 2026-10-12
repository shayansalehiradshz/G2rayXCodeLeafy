#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/g2ray-hev-payload.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT
SOURCE="$TMP_ROOT/source"
git init -q "$SOURCE"
git -C "$SOURCE" remote add origin https://github.com/heiher/hev-socks5-tunnel.git
git -C "$SOURCE" fetch -q --depth 1 origin 9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a
git -C "$SOURCE" checkout -q --detach FETCH_HEAD
git -C "$SOURCE" submodule update --init --recursive --depth 1
git -C "$SOURCE" apply --recount --ignore-whitespace "$SCRIPT_DIR/patches/hev-payload-stats.patch"
# Android embeds static dependencies in one library. Separate Linux DSOs let
# lwIP's weak malloc/free symbols interpose the task system's slice allocator.
make -C "$SOURCE" -j2 static CFLAGS=-fPIC > "$TMP_ROOT/build.log" 2>&1 || {
    tail -n 50 "$TMP_ROOT/build.log"
    exit 1
}
gcc -shared -o "$TMP_ROOT/hev-fixture.so" -Wl,--whole-archive \
    "$SOURCE/bin/libhev-socks5-tunnel.a" \
    -Wl,--no-whole-archive -Wl,--start-group \
    "$SOURCE/third-part/hev-task-system/bin/libhev-task-system.a" \
    "$SOURCE/third-part/yaml/bin/libyaml.a" \
    "$SOURCE/third-part/lwip/bin/liblwip.a" \
    -Wl,--end-group -pthread
python3 "$SCRIPT_DIR/test-hev-payload.py" "$TMP_ROOT/hev-fixture.so"

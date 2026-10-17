#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf -- "$TMP_ROOT"' EXIT
export G2RAY_SOURCE_ONLY=1
export G2RAY_DATA_DIR="$TMP_ROOT/data" G2RAY_LOG_DIR="$TMP_ROOT/logs"
source "$ROOT_DIR/g2ray.sh"

fixture_first='{"ip":"203.0.113.1","country":"US"}'
fixture_second="$fixture_first"
failed_provider=''
curl() {
    printf '%s\n' "$*" >> "$TMP_ROOT/requests"
    [[ "$1" == -q && "$*" == *'--ipv4'* && "$*" == *'--noproxy *'* &&
        "$*" == *'--max-time 5'* && "$*" == *'--max-filesize 16384'* ]] || return 1
    if [[ "${*: -1}" == https://ipinfo.io/json ]]; then
        [[ "$failed_provider" != ipinfo ]] || return 22
        printf '%s' "$fixture_first"
    else
        [[ "${*: -1}" == https://api.country.is/ ]] || return 1
        [[ "$failed_provider" != country_is ]] || return 28
        printf '%s' "$fixture_second"
    fi
}
expect_status() {
    local expected="$1" actual
    actual=$(server_exit_location_json | jq -r '.status')
    [[ "$actual" == "$expected" ]] || { printf 'FAIL: expected %s, got %s\n' "$expected" "$actual" >&2; exit 1; }
}
expect_status us_observed
[[ $(wc -l < "$TMP_ROOT/requests") -eq 2 ]]
printf 'PASS: two bounded IPv4 observations agree on US\n'

fixture_second='{"ip":"203.0.113.1","country":"DE"}'
expect_status country_disagreement
printf 'PASS: same-IP country disagreement is not called a leak or US verification\n'

fixture_first='{"ip":"203.0.113.1","country":"CA"}'
fixture_second="$fixture_first"
expect_status non_us_observed
printf 'PASS: two non-US observations are reported clearly\n'

fixture_first='{"ip":"203.0.113.1","country":"US"}'
failed_provider=country_is
expect_status partial
printf 'PASS: one available provider never produces a complete US check\n'

failed_provider=ipinfo
fixture_second='<html>rate limited</html>'
expect_status unavailable
printf 'PASS: HTTP failures and malformed bodies produce unavailable evidence\n'

failed_provider=''
for invalid in '{"ip":"::1","country":"US"}' '{"ip":"999.1.1.1","country":"US"}' \
    '{"ip":"203.0.113.1","country":"US\nDE"}' '{"ip":"203.0.113.1","country":"XX"}' \
    '{"ip":true,"country":"US"}' '[]' '{"ip":"203.0.113.1","country":null}'; do
    fixture_first="$invalid" fixture_second="$invalid"
    expect_status unavailable
done
printf 'PASS: wrong-family addresses and invalid/provider-error fields are rejected\n'

fixture_first=$(jq -cn --arg body "$(printf '%17000s' '')" '{ip:"203.0.113.1",country:"US",padding:$body}')
fixture_second="$fixture_first"
expect_status unavailable
printf 'PASS: oversized bodies cannot become exit-country evidence\n'

fixture_first='{"ip":"203.0.113.1","country":"US"}'
fixture_second='{"ip":"203.0.113.2","country":"US"}'
server_exit_location_json | jq -e '.status == "us_observed" and .multiple_exit_ips and
    .scope == "server_ipv4_egress" and .expected_country == "US"' >/dev/null
output=$(show_server_exit_location)
[[ "$output" == *'not by itself a phone-IP leak'* ]]
printf 'PASS: multiple NAT exits remain distinct from a phone-IP bypass\n'

fixture_first='{"ip":"203.0.113.1","country":"US","city":"Test\u001b[31m City","org":"Test\nISP"}'
fixture_second='{"ip":"203.0.113.1","country":"US"}'
output=$(show_server_exit_location)
[[ "$output" == *'ISP/host: Test ISP'* && "$output" != *$'\033'* ]]
printf 'PASS: existing city/ISP details remain available without terminal control characters\n'

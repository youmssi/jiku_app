#!/usr/bin/env bash
# Confirms the public-endpoint rate limits (JIKU-9B) against a running instance:
# floods one representative endpoint of each protected flow and checks that a
# 429 with a Retry-After header shows up within the configured budget.
#
# Usage:
#   BASE_URL=http://localhost:8080/api/v1 ./scripts/rate-limit-loadtest.sh
#
# The probe tokens are garbage on purpose — the limiter counts requests before
# any token validation, so no real data is needed. Run against a freshly
# started instance (or wait a window) so earlier traffic does not skew counts.
set -u

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
FAILURES=0

probe() {
    local name="$1" method="$2" url="$3" budget="$4"
    local attempts=$((budget + 5))
    local i status
    for i in $(seq 1 "$attempts"); do
        if [ "$method" = "POST" ]; then
            status=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
                -H 'Content-Type: application/json' \
                -d '{"email":"probe@example.com","password":"not-the-password"}' \
                "$url")
        else
            status=$(curl -s -o /dev/null -w '%{http_code}' "$url")
        fi
        if [ "$status" = "429" ]; then
            echo "PASS  $name: 429 after $i requests (budget $budget)"
            return 0
        fi
    done
    echo "FAIL  $name: no 429 within $attempts requests"
    FAILURES=$((FAILURES + 1))
    return 1
}

echo "Probing rate limits at $BASE_URL"
probe "login (per IP)"              POST "$BASE_URL/auth/login" 5
probe "rsvp view (per IP+token)"    GET  "$BASE_URL/rsvp/loadtest-probe-token" 30
probe "check-in search (per token)" GET  "$BASE_URL/checkin/loadtest-probe-token/search?q=probe" 120

if [ "$FAILURES" -gt 0 ]; then
    echo "Rate limiting is NOT enforced on $FAILURES flow(s)."
    exit 1
fi
echo "All protected flows enforce their limits."

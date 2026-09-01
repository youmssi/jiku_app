#!/usr/bin/env bash
# Runs the end-to-end suite against a real stack (JIKU-71).
#
# Brings up the infrastructure and builds both services; Playwright then starts
# the backend and frontend processes itself (see e2e/playwright.config.ts) and
# waits for them to answer.
#
# The frontend lives in the sibling `web` repository. Point E2E_WEB_DIR at your
# checkout if it is not next to this one.
#
#   ./scripts/run-e2e.sh                 # everything
#   ./scripts/run-e2e.sh --grep @smoke   # the fast subset
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB_DIR="${E2E_WEB_DIR:-$(cd "$APP_DIR/.." && pwd)/web}"
API_URL="${E2E_API_URL:-http://localhost:8080/api/v1}"
BASE_URL="${E2E_BASE_URL:-http://localhost:3000}"

if [ ! -d "$WEB_DIR" ]; then
    echo "Frontend not found at $WEB_DIR. Clone the web repository next to this one, or set E2E_WEB_DIR." >&2
    exit 1
fi

echo "==> Starting PostgreSQL and Mailpit"
docker compose -f "$APP_DIR/docker-compose.yml" up -d

echo "==> Waiting for PostgreSQL"
for _ in $(seq 1 30); do
    if docker compose -f "$APP_DIR/docker-compose.yml" exec -T postgres pg_isready -U "${DATABASE_USERNAME:-jiku}" -d "${DATABASE_NAME:-jiku}" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done

echo "==> Building the backend"
(cd "$APP_DIR" && ./gradlew bootJar -q)

# A frontend already serving at BASE_URL is holding its own build output, and on
# Windows rebuilding into it fails outright on the file lock. Playwright reuses a
# running server outside CI anyway, so skip the rebuild and say so rather than
# failing on a machine where the dev loop is already up.
if [ -z "${CI:-}" ] && curl -sf -o /dev/null --max-time 3 "$BASE_URL" 2>/dev/null; then
    echo "==> Frontend already serving at $BASE_URL — reusing it, skipping the build"
    echo "    (stop it first if you want the suite to run against fresh frontend code)"
else
    echo "==> Building the frontend"
    # NEXT_PUBLIC_* values are inlined at build time, so the API URL has to be set
    # here rather than when the server starts.
    (
        cd "$WEB_DIR"
        pnpm install --frozen-lockfile
        NEXT_PUBLIC_API_URL="$API_URL" \
        API_BASE_URL="$API_URL" \
        NEXT_PUBLIC_SITE_URL="$BASE_URL" \
            pnpm build
    )
fi

echo "==> Running the end-to-end suite"
cd "$APP_DIR/e2e"
# ci honours the committed lockfile exactly, so a run here installs what CI does.
npm ci --no-fund --no-audit
npx playwright install chromium --with-deps
exec npx playwright test "$@"

import { existsSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';

import { defineConfig, devices } from '@playwright/test';

/**
 * Drives the real stack: this backend, the frontend from the sibling `web`
 * repository, PostgreSQL and Mailpit. Nothing is stubbed — the point of this
 * suite is to catch the wiring bugs that two independently green unit suites
 * cannot (JIKU-71).
 *
 * `scripts/run-e2e.sh` brings up the infrastructure and builds both services;
 * Playwright then starts the two application processes below and waits for them.
 */

const APP_DIR = resolve(__dirname, '..');
const WEB_DIR = process.env.E2E_WEB_DIR ?? resolve(APP_DIR, '..', 'web');

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:3000';
const API_URL = process.env.E2E_API_URL ?? 'http://localhost:8080/api/v1';

/** The bootJar output, resolved rather than hardcoded so a version bump does not break the suite. */
function backendJar(): string {
    const libs = join(APP_DIR, 'build', 'libs');
    const jar = existsSync(libs)
        ? readdirSync(libs).find((f) => f.endsWith('.jar') && !f.endsWith('-plain.jar'))
        : undefined;
    if (!jar) {
        throw new Error(`No bootJar found in ${libs}. Run ./gradlew bootJar first (scripts/run-e2e.sh does this).`);
    }
    return join(libs, jar);
}

export default defineConfig({
    testDir: './tests',
    // A journey spans several pages and at least one mail round trip, so the
    // default 30s is too tight. Journeys measure 8-27s against a cold stack; the
    // headroom is for a loaded CI runner, not for a known slow path.
    timeout: 120_000,
    expect: { timeout: 15_000 },
    // These tests share one database and one Mailpit inbox, so they are ordered
    // and serial by design. Parallelism here would trade real coverage for noise.
    fullyParallel: false,
    workers: 1,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list'], ['html', { open: 'never' }]],
    use: {
        baseURL: BASE_URL,
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
        video: 'retain-on-failure',
    },
    projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
    webServer: [
        {
            command: `java -jar "${backendJar()}"`,
            url: `${API_URL}/health`,
            // Without this the jar runs from e2e/, where the optional .env import
            // finds nothing and the datasource is left unconfigured.
            cwd: APP_DIR,
            reuseExistingServer: !process.env.CI,
            timeout: 180_000,
            stdout: 'pipe',
            stderr: 'pipe',
            env: {
                // Set explicitly rather than inherited from a developer's .env:
                // application.yaml gives DATABASE_URL no default, and CI has no
                // .env at all. These match docker-compose.yml's defaults.
                DATABASE_URL: process.env.DATABASE_URL ?? 'jdbc:postgresql://localhost:5433/jiku',
                DATABASE_USERNAME: process.env.DATABASE_USERNAME ?? 'jiku',
                DATABASE_PASSWORD: process.env.DATABASE_PASSWORD ?? 'jiku',
                // Invitations must land in Mailpit, where the tests read them, rather
                // than in the log-only transport a fresh clone defaults to.
                MAIL_TRANSPORT: 'smtp',
                MAIL_HOST: process.env.E2E_MAIL_HOST ?? 'localhost',
                MAIL_PORT: process.env.E2E_MAIL_SMTP_PORT ?? '1025',
                SERVER_BASE_URL: BASE_URL,
                CORS_ALLOWED_ORIGINS: BASE_URL,
                // Journeys legitimately hit public endpoints far faster than a human,
                // and the limiter has its own dedicated tests.
                RATE_LIMIT_ENABLED: 'false',
            },
        },
        {
            command: 'pnpm start',
            cwd: WEB_DIR,
            url: BASE_URL,
            reuseExistingServer: !process.env.CI,
            timeout: 120_000,
            stdout: 'pipe',
            stderr: 'pipe',
        },
    ],
});

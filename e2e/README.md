# End-to-end suite (JIKU-71)

Drives the real stack — this backend, the frontend from the sibling `web`
repository, PostgreSQL and Mailpit. Nothing is stubbed.

The suite exists because two independently green unit suites cannot catch a
wiring bug between them, which is exactly how the product came to have a
verification email nobody had ever clicked in a test.

## Running it

Everything, from a cold machine:

```bash
./scripts/run-e2e.sh
```

Just the fast subset:

```bash
./scripts/run-e2e.sh --grep @smoke
```

Prerequisites: Docker, a JDK, Node and pnpm, and a checkout of the `web`
repository next to this one (or `E2E_WEB_DIR` pointing at it).

With the stack already running — the usual inner loop — Playwright reuses it, so
you can iterate without rebuilding:

```bash
cd e2e && npx playwright test tests/checkin.spec.ts
```

## What it covers

| Spec | Journey |
|---|---|
| `organizer-onboarding.spec.ts` | Register, verify the address from the real email, create an organization |
| `invitation-and-rsvp.spec.ts` | Import guests from CSV, send invitations, guest confirms from the emailed link and gets a ticket with a QR |
| `checkin.spec.ts` | Validator admits a guest, and a second attempt reports who admitted them and when |

`@smoke` marks the load-bearing case in each file; the tag is what CI runs on
every pull request.

## How it is written

- **Arrange through the API, drive the flow under test through the UI.** Clicking
  through event creation before every guest journey would be slow, brittle, and
  would report setup failures as failures of the thing being tested.
- **Assert on rendered content, not on URLs.** A URL change proves routing, not
  that the product did anything.
- **Email is real.** Verification and invitation links are read out of Mailpit and
  followed, so a broken template or a wrong `SERVER_BASE_URL` fails here rather
  than in a customer's inbox.
- **Serial, single worker.** The specs share one database and one inbox. Running
  them in parallel would trade real coverage for noise.
- **Fresh identities per run.** Registration is unique-by-email, so every run
  generates its own addresses instead of reusing fixture accounts that would
  collide on the second run.

## CI wiring — not applied yet

The job below is written and verified locally but **is not in
`.github/workflows/ci.yml`**: pushing a workflow change needs a token with the
`workflow` scope, which the account that produced this branch did not have. Paste
it under `jobs:` in that file to close the gate. Until then the suite is a local
tool, and nothing stops a pull request that breaks a journey.

```yaml
  e2e:
    name: End-to-end journeys
    runs-on: ubuntu-latest
    # The unit and architecture gates are cheap and catch most breakage; running
    # the full stack only after they pass keeps a broken build from spending ten
    # minutes booting two services to fail anyway.
    needs: build

    steps:
      - name: Checkout backend
        uses: actions/checkout@v4

      # The frontend is a separate, public repository, so no credentials are
      # needed here. Pinned to develop: the suite verifies the integration this
      # change would actually be merged into.
      - name: Checkout frontend
        uses: actions/checkout@v4
        with:
          repository: youmssi/jiku_web
          ref: develop
          path: web-checkout

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "25"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Setup pnpm
        uses: pnpm/action-setup@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: 22

      - name: Make scripts executable
        run: chmod +x ./gradlew ./scripts/run-e2e.sh

      # Only the @smoke subset runs per pull request: it covers the load-bearing
      # case of every journey while keeping the gate to a few minutes.
      - name: Run the smoke journeys against a real stack
        env:
          E2E_WEB_DIR: ${{ github.workspace }}/web-checkout
        run: ./scripts/run-e2e.sh --grep @smoke

      - name: Upload the report when a journey fails
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: e2e-report
          path: |
            e2e/playwright-report
            e2e/test-results
          retention-days: 7
```

## Known limitations

- **The QR scan path is not exercised.** The console's primary action needs a
  camera, which a headless browser has not got. The tests use the name-search
  fallback, which is the same check-in path with a different way of resolving the
  guest — and the path staff actually use when a screen will not scan.
- **Offline check-in is not covered.** It needs a controllable network and
  IndexedDB state across reloads; worth adding, but it is its own piece of work.
- **The suite does not run on frontend pull requests.** It lives here because
  this repository is private and `web` is public: a workflow here can check out
  `web` with no credentials, while the reverse would mean putting a token with
  access to this private repository into a public one. Wiring the frontend
  repository to trigger it needs that decision to be made first.

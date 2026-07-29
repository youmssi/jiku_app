# Deploying to a permanent host

Render's free tier sleeps the API after inactivity, which breaks WhatsApp/email
webhook delivery and produces a slow first request for every visitor after a gap.
This runbook moves the backend to an always-on Hetzner box while the frontend
stays on Vercel and the database stays on Neon.

Everything the code needs is already in this repository (`Dockerfile`,
`docker-compose.prod.yml`, `Caddyfile`, `/api/v1/health`). The steps below are the
manual, one-time provisioning work — there is no further code change required to
follow this runbook.

## 1. Provision the server

1. Create a Hetzner Cloud server (CX22 or larger; Ubuntu 24.04 LTS image is
   sufficient — the app runs entirely in Docker).
2. Note its public IPv4 address.
3. SSH in and install Docker Engine + the Compose plugin:
   ```
   curl -fsSL https://get.docker.com | sh
   ```
4. Open the firewall for HTTP/HTTPS only — the app container never needs to be
   reachable directly, Caddy is the only public entry point:
   ```
   ufw allow 22/tcp
   ufw allow 80/tcp
   ufw allow 443/tcp
   ufw enable
   ```

## 2. Point DNS

At your DNS provider, create an `A` record:

```
api.jiku.mrvin100.de.  A  <server public IPv4>
```

Wait for it to resolve (`dig +short api.jiku.mrvin100.de`) before continuing —
Caddy's automatic TLS issuance fails if the domain does not yet resolve to this
server.

## 3. Deploy the stack

1. Clone the repository onto the server (or copy just the `app/` directory —
   nothing else is needed to run the backend).
2. Copy `.env.example` to `.env` and fill in the **REQUIRED** section with real
   production values:
   - `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` — the Neon
     connection string (`...neon.tech:5432/jiku?sslmode=require`), not the local
     Docker default.
   - `SERVER_BASE_URL` — the deployed frontend origin (the Vercel URL / custom
     domain), used to build guest and validator links.
   - `CORS_ALLOWED_ORIGINS` — the same frontend origin, so the browser is allowed
     to call the API.
   - `AUTH_JWT_SECRET`, `PROVIDER_CREDENTIALS_ENCRYPTION_KEY`,
     `PAYMENT_WEBHOOK_SECRET`, `NOTIFICATION_WEBHOOK_SECRET` — strong random
     values, not the local development defaults.
   - `API_DOMAIN=api.jiku.mrvin100.de` and `CADDY_EMAIL=<an address you monitor>`
     (Let's Encrypt sends expiry notices there; it never causes a renewal
     failure on its own, but ignoring it can hide one).
   - Provider credentials as they're issued (`RESEND_API_KEY`,
     `WHATSAPP_META_ACCESS_TOKEN`, etc.) — the app runs correctly with these
     blank, just with the corresponding channel logging instead of delivering.
3. Bring the stack up:
   ```
   docker compose -f docker-compose.prod.yml up -d --build
   ```
4. Watch the logs until Caddy reports it obtained a certificate and the app
   reports it started:
   ```
   docker compose -f docker-compose.prod.yml logs -f
   ```

## 4. Smoke test

```
curl https://api.jiku.mrvin100.de/api/v1/health
```

Expect `{"status":"UP","version":"..."}` over a valid HTTPS connection (no `-k`
needed — that's the signal Caddy's certificate issuance succeeded). If this
fails, check `docker compose -f docker-compose.prod.yml logs caddy` for ACME
errors (usually DNS not yet resolving, or port 80 not reachable from the
internet) and `docker compose -f docker-compose.prod.yml logs app` for startup
errors (usually a missing/incorrect `DATABASE_URL`).

Also confirm a real endpoint works end-to-end, e.g. register a throwaway
organizer account through the deployed frontend once step 5 is done.

## 5. Point the frontend at the new backend

On Vercel, set `NEXT_PUBLIC_API_URL=https://api.jiku.mrvin100.de/api/v1` (and
`API_BASE_URL` to the same value for server-side calls) and redeploy. Confirm
`CORS_ALLOWED_ORIGINS` on the backend still matches the frontend's actual origin
after this change.

## 6. Wire uptime monitoring

Create an UptimeRobot HTTP(S) monitor:
- URL: `https://api.jiku.mrvin100.de/api/v1/health`
- Expected: HTTP 200, keyword `"status":"UP"`
- Interval: 5 minutes is enough for a single-instance MVP deployment

This is the endpoint the monitor should watch, not `/actuator/health` — the
`/api/v1/health` endpoint needs no authentication and reports nothing about
internal component state, which is exactly the surface an external monitor
should see.

## Updating a deployed instance

```
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

Flyway migrations run automatically on app startup, same as locally — no
separate migration step.

## Backups

See `docs/backup.md`. Neon (the database host) is the primary backup mechanism;
nothing in this section is required before going live.

## Email deliverability (SPF/DKIM/DMARC)

Both Resend and Brevo need the sending domain's ownership proven via DNS before
they will deliver mail from it — skipping this gets messages spam-foldered or
outright rejected regardless of `MAIL_TRANSPORT`.

1. **Resend**: Dashboard -> Domains -> add the domain used in `MAIL_FROM`.
   Resend shows the exact DNS records to add (typically an SPF `TXT` record, a
   DKIM `CNAME`/`TXT` pair, and a `MX` record if using Resend's inbound). Add
   them at your DNS provider and wait for Resend to mark the domain verified —
   sending fails with a rejected-domain error until it does.
2. **Brevo**: Dashboard -> Senders, Domains & Dedicated IPs -> Domains -> add
   the same domain. Brevo issues its own SPF/DKIM `TXT` records (different
   values from Resend's — both providers' records coexist on the same domain,
   since SPF supports multiple `include:` mechanisms in one record and DKIM
   selectors are provider-specific).
3. **DMARC** (recommended, not required by either provider): add one `TXT`
   record at `_dmarc.<domain>` such as
   `v=DMARC1; p=quarantine; rua=mailto:<an address you monitor>` — this is
   independent of which provider sent the mail and only needs setting once.
4. Verify with each provider's own domain-status check in their dashboard, then
   send one real test invitation end-to-end before relying on either transport.

`MAIL_TRANSPORT=routing` (JIKU-62) needs both Resend's and Brevo's records in
place — Resend handles the first 100/day, Brevo the rest up to the caps in
`EMAIL_RESEND_DAILY_CAP`/`EMAIL_BREVO_DAILY_CAP`/`EMAIL_GLOBAL_DAILY_CAP`.

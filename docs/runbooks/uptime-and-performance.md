# Runbook — Uptime & Performance Monitoring

Covers JIKU-30: how the Jikū backend is monitored, and what to do when an alert
fires. Keep this current as endpoints or thresholds change.

## What is monitored

### Health probe (external uptime monitoring)

- **Public status surface:** `GET /api/v1/health` — returns `{"status":"UP"}` and
  nothing else. This is the endpoint the external uptime monitor and the platform's
  health-check path use (JIKU-97), kept deliberately apart from `/actuator/health`.
- **Endpoint:** `GET /actuator/health` (public — returns only `{"status":"UP|DOWN"}`,
  never internal component details).
- **What it checks:** application liveness and readiness. Readiness includes
  **database connectivity**, so the probe flips to `DOWN` (HTTP 503) when
  PostgreSQL is unreachable, not only when the process is dead.
- **Liveness vs. readiness:** `GET /actuator/health/liveness` (process is up) and
  `GET /actuator/health/readiness` (up **and** dependencies reachable) are
  available separately for orchestrators.

### Performance metrics (dashboard)

- **Endpoints:** `GET /actuator/prometheus` (scrape format) and
  `GET /actuator/metrics` — both **require authentication**, so operational data
  is not exposed publicly. Access them through an authenticated session or an
  internal-network port-forward.
- **Key metric:** `http_server_requests_seconds` (Micrometer `http.server.requests`),
  tagged by `uri`, `method`, `status`, and `outcome`. Latency histograms are
  enabled, so p95/p99 and error rate are queryable per route.

The two most failure-sensitive paths, by templated `uri` tag:

| Path | `uri` tag |
|---|---|
| Check-in validation (QR scan) | `/api/v1/checkin/{token}/scan` |
| Check-in validation (manual) | `/api/v1/checkin/{token}/manual` |
| RSVP confirmation | `/api/v1/rsvp/{token}/confirm` |

Example Prometheus queries:

```promql
# p95 latency of check-in scans over 5m
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{uri="/api/v1/checkin/{token}/scan"}[5m])) by (le))

# error rate (5xx) of RSVP confirmations over 5m
sum(rate(http_server_requests_seconds_count{uri="/api/v1/rsvp/{token}/confirm", status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count{uri="/api/v1/rsvp/{token}/confirm"}[5m]))
```

## Uptime monitor configuration (operator setup)

Provision a lightweight uptime-monitoring SaaS (e.g. UptimeRobot, Better Uptime,
or the hosting platform's built-in monitor — a free/low tier is sufficient; do not
build a custom pipeline):

1. **Monitor:** HTTP(s) check against `https://<backend-host>/api/v1/health`
   (the public status surface — returns `{"status":"UP"}`; any other response or a
   timeout counts as down).
2. **Interval:** 60 seconds.
3. **Alert threshold:** notify after **2 consecutive failed checks** (avoids a
   single transient blip paging the team) — a failed check is any non-200 status
   or a timeout.
4. **Channel:** email and/or Slack to the on-call address.
5. **Validation:** confirm the alert path works by temporarily stopping the staging
   backend and verifying an alert is delivered (JIKU-30 story DoD).

## When an uptime alert fires

1. **Confirm scope.** Open `GET /actuator/health` yourself. `DOWN` (503) → the app
   considers itself unhealthy; unreachable/timeout → the process or platform is
   down.
2. **Check readiness detail** (authenticated): `GET /actuator/health/readiness` with
   component details shows whether it is the **database** (`db` component `DOWN`)
   or the app itself.
3. **Check the logs first.** Logs are structured and carry a `requestId` and
   `tenantId` per request (JIKU-29). Filter the deployment's logs for `ERROR`
   around the alert time; a database outage shows connection-pool/`db` health
   errors. Unhandled application errors are captured with their request context.
4. **Database down:** verify the managed PostgreSQL instance status and
   connectivity; the app recovers automatically once the database is reachable
   (readiness returns to `UP`).
5. **App down / crash-looping:** check the platform's deploy/restart logs and roll
   back the last deploy if it correlates with the outage.
6. **Escalate** to the Tech Lead if not resolved within the agreed window, and
   record the incident (start, cause, resolution) for the retro.

## Who to notify

- **First responder:** on-call developer (rotation).
- **Escalation:** Tech Lead.
- **Customer-facing status:** the commercial collaborator communicates to affected
  organizers if an outage exceeds the agreed threshold.

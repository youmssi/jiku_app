# ADR-79: Shared rate-limit counters in PostgreSQL, selected by configuration

**Status:** Accepted
**Story:** JIKU-79

## Context

Rate limiting protects every endpoint reachable without an organizer login —
login, registration, RSVP, check-in, webhooks, booking. The counters were held in
a `ConcurrentHashMap` inside `FixedWindowRateLimiter`, which is correct for one
API instance and wrong the moment there are two: each instance keeps its own map,
so N instances grant N times every configured budget. A login policy of five
attempts per minute silently becomes ten across two replicas.

This is boundary **B10** in the capability dossier, recorded there as *"must be
fixed before the second replica, not after"*. It is also a stated prerequisite for
the queue-management initiative (`docs/jiku-queue-management-evaluation.md` §5, T1)
and for any uptime commitment, since a single instance cannot be restarted without
downtime.

Three options were considered.

1. **Sticky sessions / consistent hashing at the proxy** — route a client to the
   same instance so the local map stays authoritative. Rejected: it makes the
   limit correct only while the routing is stable, breaks on any rebalance or
   instance loss, and neither Render nor Vercel guarantees it for an API.
2. **Redis** — the purpose-built answer: atomic `INCR` with `EXPIRE`, sub-millisecond,
   designed for exactly this access pattern.
3. **PostgreSQL** — a shared table written through a conditional upsert, reusing
   the atomic-counter pattern already proven in production here for the email
   daily quota (`V31__email_quota_counter.sql`).

Redis is the better tool in the abstract, and the honest objection to PostgreSQL
has to be stated plainly: **a rate limiter backed by the resource it protects can
amplify a flood rather than absorb it.** Under attack, every request that the
limiter would reject still costs a database round trip.

That objection is real but does not bind at this scale. The counter write is a
single indexed statement on a primary key; PostgreSQL serves those at a rate
orders of magnitude above any traffic this platform will see before the maturity
gates in the GTM plan are met. Every endpoint behind these policies already needs
the same database to do its work, so the database is on the critical path
regardless. Against that, Redis would add a second piece of stateful
infrastructure to provision, secure, monitor, pay for and recover — on a team
whose own risk register records bus factor 1 (R1) and no rehearsed restore (B7).
Adding an operational surface is not free, and right now it is the more expensive
half of the trade.

## Decision

Introduce a `RateLimiter` port with two implementations, selected by
`api.rate-limit.store` — the same shape the codebase already uses for
`ErrorTracker`.

- **`InMemoryRateLimiter`** (`store=memory`, the default) — the existing
  per-instance map, renamed to say what it is. No database round trip; correct for
  local development, the test suite and the current single-instance deployment.
- **`DatabaseRateLimiter`** (`store=database`) — counters in `rate_limit_counter`,
  shared by every instance.

The shared counter is written by one statement that rolls the window over,
increments, and enforces the ceiling together:

```sql
INSERT INTO rate_limit_counter (bucket_key, window_start, expires_at, request_count)
VALUES (:key, :now, :expiresAt, 1)
ON CONFLICT (bucket_key) DO UPDATE SET
  request_count = CASE WHEN rate_limit_counter.expires_at <= :now THEN 1
                       ELSE rate_limit_counter.request_count + 1 END,
  ...
WHERE rate_limit_counter.expires_at <= :now
   OR rate_limit_counter.request_count < :maxRequests
```

Rows affected is the verdict: 1 allowed, 0 refused. Two instances can therefore
never both be allowed past a budget, and the allowed path costs exactly one round
trip. Only the refused path pays a second read, to report `Retry-After` from the
window actually in force.

The table is **not** tenant-scoped, deliberately: every rate-limited policy guards
an endpoint reachable without an organizer login, so no tenant context exists when
the counter is touched. `bucket_key` already carries the policy name, the client
IP and, for link flows, the token segment.

`RateLimitCounterSweepJob` deletes elapsed windows every ten minutes, registered
only when the shared store is active. Every instance runs it; deleting an
already-deleted row is harmless, so no leader election is needed.

**The shared limiter fails open.** If the store cannot be reached the request is
allowed and a warning is logged, rather than every public endpoint answering 429
because a counter is unavailable — that would convert a counter outage into a full
outage. The exposure is bounded, because every endpoint behind these policies
needs the same database moments later anyway.

## Consequences

- **Positive:** B10 is closable with no new infrastructure, no new cost and no new
  operational surface. Correctness across instances is proven by
  `DatabaseRateLimiterTest`, whose load-bearing case is that *separate limiter
  instances share one budget* — the exact property the map failed to provide.
  Swapping in Redis later is a new class behind the existing port plus one config
  value; no call site changes.
- **Negative / risks:** one database round trip per rate-limited request when the
  shared store is active, and a flood is not absorbed before reaching the
  database. Fail-open means a store outage temporarily removes brute-force
  protection on `/auth/login`. Both are accepted deliberately and are the reasons
  the default stays `memory` while the deployment is single-instance.
- **Operational requirement:** `RATE_LIMIT_STORE=database` **must** be set before a
  second instance is started. Nothing in the system detects a second instance, so
  this is a deployment-checklist item, not a safety property — it is recorded in
  `.env.example` and `docs/deploy.md`.
- **Revisit if:** sustained traffic makes the per-request round trip measurable in
  the latency histograms, an attack demonstrates flood amplification, or a second
  component (queue dispatch, session state) independently justifies Redis — at
  which point moving both together is the cheaper migration.

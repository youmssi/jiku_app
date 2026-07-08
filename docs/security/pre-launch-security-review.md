# Jikū — Pre-Launch Security Review (JIKU-31)

**Status:** For Tech Lead sign-off before the V1 `develop` → `main` merge
**Scope:** Full MVP surface across all modules (tenant, event, invitation, ticketing,
checkin, notification, billing) and the Next.js frontend.
**Date of review:** V1 pre-launch pass.

This report re-verifies the security guarantees the product depends on, records a
dependency scan, and lists findings with their disposition. Items marked
**Confirmed** were checked against the implementation and, where noted, a test.

---

## 1. Tenant isolation (re-verified across modules)

**Mechanism.** Every tenant-scoped entity extends `BaseTenantEntity`, which carries
`tenant_id` and is stamped/filtered automatically by Hibernate's `@TenantId`
discriminator multi-tenancy, driven by a request-scoped `TenantContext` via
`TenantIdentifierResolver`. When no tenant is bound the resolver returns an
`__unresolved__` sentinel that matches no real tenant, so an unscoped read returns
nothing rather than leaking; writes are additionally blocked by the `@PrePersist`
guard in `BaseTenantEntity`. No query relies on a hand-written `WHERE tenant_id = ?`
as its only safeguard.

**Re-verification (beyond the original JIKU-6 unit test):**
- `CrossModuleTenantIsolationTest` — end-to-end across event + invitation + ticketing
  + checkin: tenant B cannot read tenant A's event, guests, or dashboard, sees an
  empty event list, and the validator-link roster returns only tenant A's data.
- `BillingHistoryTest` — a second tenant sees an empty payment history and gets 404
  on another tenant's receipt.
- `GuestErasureTest` / `GuestRetentionTest` — anonymization operates under the bound
  tenant only.
- Original `TenantIsolationTest` — persistence-layer guarantee intact.

**Deliberate cross-tenant reads (reviewed, safe):**
- `email_feedback` (sender reputation, JIKU-28B) is intentionally global; it carries
  an attributed tenant and is read platform-wide for reputation only — no
  tenant-owned personal data is exposed cross-tenant.
- The retention job's `findEventsPastRetention` uses native SQL that bypasses the
  entity filter **by design** (a platform maintenance sweep); it returns only event
  id + owning tenant, and the job **binds each event's own tenant** before touching
  any of its data. The payment callback binds the tenant carried in the
  signature-verified reference before its transaction opens. All three were reviewed
  and confine cross-tenant scope to non-personal identifiers.

**Verdict: Confirmed.**

---

## 2. Authentication & token handling

- Stateless JWT (HMAC-SHA, jjwt). Access + refresh tokens; refresh is type-checked so
  an access token cannot be replayed as a refresh token.
- The signing secret is configuration (`JWT_SECRET`); it ships **only** a development
  default that must be overridden outside local — see Findings F-3.
- Tokens carry the subject, tenant and role; the `JwtAuthenticationFilter` populates
  both the Spring Security context (for `@PreAuthorize`) and `TenantContext`, and
  clears both in a `finally` so nothing leaks across pooled threads.
- The frontend (BFF) stores tokens in **httpOnly cookies**, not `localStorage`, and
  forwards them server-side; no token is exposed to client JavaScript.
- Guest/validator links are separately-signed JWTs (invitation / validator token
  services), not login sessions; a tampered token fails signature verification and is
  rejected as 404 ("invalid or expired link").

**Verdict: Confirmed** (with F-3 on secret management).

---

## 3. Rate limiting on public endpoints (JIKU-9B)

A shared, configuration-driven `RateLimitFilter` runs before the security chain and
enforces per-client budgets on every unauthenticated public endpoint:

| Flow | Endpoint(s) | Key |
|---|---|---|
| Login | `/auth/login` | per IP |
| Registration / refresh | `/auth/register`, `/auth/refresh` | per IP |
| Guest RSVP + ticket view | `/rsvp/**` | per IP + invitation token |
| Validator check-in / search / sync | `/checkin/**` | per IP + validator token |
| Email feedback webhook | `/notifications/email-feedback` | per IP |

Exceeding a budget returns `429` with `Retry-After`. Keys combine IP and the link
token where present, so a shared venue NAT is not lumped together and token rotation
does not grant fresh budget. A load-test script confirms enforcement.

**Note (F-4):** counters are in-memory and per-instance by design at MVP scale (single
API replica). Running more than one replica requires a shared store (e.g. Redis).

**Verdict: Confirmed** (single-instance scope noted).

---

## 4. Ticket forgery resistance (re-verify JIKU-20)

- `TicketCodeGenerator` produces 120 bits of `SecureRandom` entropy, URL-safe base64,
  non-sequential — not guessable or enumerable.
- Check-in resolves a ticket strictly by its stored code; an unknown/forged code
  returns `NOT_FOUND`, never a false success. The QR encodes only the code, verified
  against the database.
- A cancelled event's tickets return the explicit `EVENT_CANCELLED` outcome
  (JIKU-14B), and a declined guest's ticket returns `CANCELLED` — no path yields a
  false "checked in".
- Check-in is an atomic conditional update; concurrent scans of the same ticket
  resolve to a single `CHECKED_IN` with the rest reported `ALREADY_CHECKED_IN`
  (who/when), preventing double-entry.

**Verdict: Confirmed.**

---

## 5. OWASP Top 10 pass (baseline)

- **A01 Broken Access Control** — organizer endpoints require `@PreAuthorize('ROLE_ORGANIZER_ADMIN')`
  plus tenant filtering; public endpoints are explicitly allow-listed and
  token-authenticated; the payment webhook is signature-verified. Tenant isolation
  re-verified (Section 1). *OK.*
- **A02 Cryptographic Failures** — HMAC-signed JWTs; ticket/webhook use `SecureRandom`
  / HMAC-SHA256 with constant-time comparison; secrets via env (F-3). *OK.*
- **A03 Injection** — all persistence via Spring Data JPA / parameterized queries; the
  single native query (retention) uses a bound `:cutoff` parameter, no string
  concatenation of input. CSV import is parsed, not evaluated. *OK.*
- **A04 Insecure Design** — payment tiers unlock only on server-verified callbacks,
  never client-side; paywall enforced server-side; erasure irreversible + audited. *OK.*
- **A05 Security Misconfiguration** — single `application.yaml`, all env-overridable;
  CSRF disabled (correct for a stateless bearer-token API with no cookie-based auth on
  the backend); Actuator health public (status only), metrics/prometheus authenticated;
  frontend sends `X-Content-Type-Options`, `X-Frame-Options: DENY`,
  `Referrer-Policy`. *OK.*
- **A06 Vulnerable Components** — see Section 6.
- **A07 Auth Failures** — login rate-limited; generic "invalid email or password";
  bcrypt password hashing. *OK.*
- **A08 Integrity Failures** — payment callbacks HMAC-verified before any unlock. *OK.*
- **A09 Logging & Monitoring** — structured JSON logging with request/tenant
  correlation ids (JIKU-29); unhandled errors captured with context; no secrets,
  tokens, passwords or full personal data written to logs; health/metrics for uptime
  (JIKU-30). *OK.*
- **A10 SSRF** — no server-side fetch of user-supplied URLs. *N/A.*

---

## 6. Dependency vulnerability scan

**Frontend (`pnpm audit --prod`):** 1 **moderate**, 0 high/critical.
- `postcss < 8.5.10` (GHSA-qx2v-qp2m-jg93), transitive via `next`. Build-time CSS
  stringify XSS; not reachable at runtime in our usage (we do not stringify
  untrusted CSS). **Accepted**, tracked to clear when Next bumps its `postcss`
  (F-5). No high/critical findings requiring action.

**Backend:** dependencies are pinned to current stable, mutually-compatible versions
(Spring Boot 4.1, Kotlin 2.3.21, jjwt 0.13.x, springdoc 3.0.x). No automated CVE
scan is wired into CI yet. **Recommendation (F-6):** add the OWASP
dependency-check Gradle plugin (or `gradle`'s built-in advisory tooling / Dependabot)
to CI so high/critical CVEs fail the build, matching the frontend's `pnpm audit`.

---

## 7. Findings & disposition

| ID | Severity | Finding | Disposition |
|---|---|---|---|
| F-1 | — | Tenant isolation holds across all modules | Confirmed, tested |
| F-2 | — | Ticket codes non-guessable; forged codes rejected | Confirmed, tested |
| F-3 | High (ops) | Dev-default `JWT_SECRET` and payment/webhook secrets must be overridden outside local | **Blocking for prod deploy:** set strong `JWT_SECRET`, `NOTIFICATION_WEBHOOK_SECRET`, `BILLING_PAYMENT_WEBHOOK_SECRET` in the deploy environment. Enforced by convention + this checklist. |
| F-4 | Low | Rate-limit counters are per-instance | Accepted at single-replica MVP scale; move to a shared store before horizontal scaling |
| F-5 | Moderate | `postcss` transitive advisory (build-time) | Accepted; clear on next `next` bump |
| F-6 | Low | No backend CVE scan in CI | Recommended: add OWASP dependency-check to CI |

No high/critical **code** vulnerabilities were found. The one operational blocker
(F-3) is a deployment-time secret-configuration step, not a code defect.

---

## 8. Sign-off

- [ ] Reviewed and explicitly signed off by the **Tech Lead** before the V1
      `develop` → `main` merge.

Pre-merge deployment checklist (from F-3): strong `JWT_SECRET`,
`NOTIFICATION_WEBHOOK_SECRET`, `BILLING_PAYMENT_WEBHOOK_SECRET`, and real provider
credentials are set in the production environment; Actuator metrics/prometheus are
not publicly exposed.

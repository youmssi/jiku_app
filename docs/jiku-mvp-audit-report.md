# Jikū — Full MVP Audit Report

**Date:** 2026-07-09
**Scope:** `app/` (backend), `web/` (frontend), `docs/` (backlog & framing) — audited against `jiku-mvp-backlog.md` and `jiku-project-framing.md`
**Companion documents:** `web/docs/frontend-architecture-review.md` (earlier frontend-only review; its P0–P2 findings are folded into this report's remediation plan)

---

## 1. Executive summary

The MVP is **substantially built and architecturally healthy**. The backend implements every code-bearing story from EPIC-01 through EPIC-11 with the module discipline the backlog demands; the frontend implements every organizer/guest/validator flow with the agreed 4-layer module architecture and a genuinely strong offline check-in engine. What separates the current state from a launchable V1 falls into five buckets:

1. **Provider adapters are stubs by design.** Email, WhatsApp, Mobile Money, and error tracking all sit behind clean ports with logging/sandbox stand-ins. The interactive decisions blocking them are now resolved (see §2) — wiring the real adapters is the main remaining backend work.
2. **The landing page undermines the product.** It contains fabricated statistics, invented testimonials, false compliance claims (SOC 2, GDPR, uptime SLA), and advertises a **monthly USD subscription** that contradicts the validated pay-per-event XOF/Mobile Money model the backend actually implements. It is also English-only for a Francophone target market. This is the single most urgent fix — it is both a legal/trust exposure and exactly the "looks AI-generated" problem this audit was asked to eliminate.
3. **The organizer settings surface is missing.** The backend exposes white-label branding (JIKU-11), but no settings UI exists — and the newly validated requirement (organizations self-configure their own email and WhatsApp provider credentials) needs that page plus a per-tenant provider-credentials capability on the backend.
4. **A handful of frontend pattern violations** (one module breaks the flat-module convention, one duplicated view, one route outside its layout group) plus the still-unapplied P0/P1 items from the earlier frontend architecture review.
5. **SEO is baseline-only.** Metadata/robots/sitemap exist, but there is no OG image, no structured data, a one-entry sitemap, no French content, and no premium visual identity on the landing page. Google Search Console verification + API support was **added during this audit** (see §10).

Non-code stories remain open as expected: backup/restore verification (JIKU-9C), CD pipelines (JIKU-7, deliberately skipped for now), the pre-launch security review (JIKU-31), and the UAT epic (JIKU-39/40/41).

---

## 2. Decisions validated during this audit

These were confirmed interactively on 2026-07-09 and unblock the corresponding backlog stories:

| # | Decision | Choice | Unblocks |
|---|---|---|---|
| 1 | Landing/guest language strategy | **FR + EN i18n** (locale routing, French default for the target market, hreflang) | Landing rewrite, SEO |
| 2 | Landing content integrity & pricing | **Full honest rewrite** — remove all fabricated stats/testimonials/compliance badges; pricing displayed as pay-per-event in FCFA with the free 100-guest tier | Landing rewrite |
| 3 | Email provider (JIKU-16 interactive step) | **Resend**, integrated with the Tûm transport pattern: SMTP transport for local dev (Mailpit), Resend HTTPS API transport in production, switched by env | JIKU-16, JIKU-28B webhooks |
| 4 | WhatsApp path (JIKU-17 interactive step) | **Meta WhatsApp Cloud API first**, designed behind a pluggable provider abstraction so post-MVP providers (Brevo, Twilio, 360dialog…) can be added and **the organization owner chooses which provider to use** | JIKU-17 |
| 5 | Tenant self-service provider settings (new requirement) | Org owner can configure their own WhatsApp API credentials and email provider settings in an org-settings UI; platform credentials serve as fallback | New stories (§9) |

Still open (unchanged): error-tracking provider (JIKU-29 — Sentry is the standing recommendation) and Mobile Money provider/aggregator (JIKU-33).

---

## 3. Architecture overview

### 3.1 Backend (`app/`) — Spring Modulith, Kotlin, Spring Boot 4.1

Eight auto-detected modules under `com.jiku`, each following the same internal shape:

```
com.jiku.<module>
├── <Module>ModuleApi.kt        # the ONLY surface other modules may touch
├── <api types>.kt              # e.g. EventInfo, TenantInfo — never JPA entities
└── internal/                   # controllers, services, entities, repositories
```

- **Modules:** `tenant`, `event`, `invitation`, `ticketing`, `checkin`, `notification`, `billing`, `shared`.
- **Boundary enforcement:** `ModularityTests` runs `ApplicationModules.verify()`; cross-module communication uses `*ModuleApi` interfaces or application events (`GuestInvitedEvent`, `EventCancelledEvent`, `InvitationDeliveryResult`, `EventCancellationNotice` in `shared`).
- **Multi-tenancy:** `BaseTenantEntity` (`shared`) contributes `tenant_id` via Hibernate `@TenantId` with two independent guards (NOT NULL column + `@PrePersist` check against `TenantContext`). `TenantIdentifierResolver` wires the session-level filter. Verified by `TenantIsolationTest` and `CrossModuleTenantIsolationTest`.
- **Cross-cutting (`shared`):** JWT auth (`JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`), configurable rate limiting (`RateLimitFilter` + per-policy config in `application.yaml`), observability (`RequestCorrelationFilter`, `GlobalExceptionHandler`, `ErrorTracker` port), central API prefix (`WebConfig` + `ApiProperties`, `/api/v1` lives in config only), OpenAPI (`OpenApiConfig`), async config, demo seeding (`DemoSeeding` + per-module `*DemoSeedContributor`).
- **Ports awaiting real adapters:** `EmailSender` (→ `LoggingEmailSender`), `WhatsAppSender` (→ `LoggingWhatsAppSender`), `PaymentProvider` (→ `SandboxPaymentProvider`, which already exercises real HMAC-SHA256 webhook verification), `ErrorTracker`.
- **Configuration:** single `application.yaml`, every environment-dependent value from `${ENV_VAR:default}`, documented in `app/.env.example` (which already follows the Tûm naming convention: `DATABASE_*`, `MAIL_*`, `NOTIFICATION_*`, …).
- **Persistence:** 16 Flyway migrations (V1–V16), `ddl-auto: validate`, Testcontainers-backed integration tests.
- **Tests:** 36 test classes mapping directly to acceptance criteria — including the concurrency tests the backlog explicitly demands (`CheckInConcurrencyTest`, `EventCapacityConcurrencyTest`), erasure/retention, paywall, reputation-alert, and offline-sync tests.

**Assessment (Senior Architect / Senior Backend):** this codebase does what the backlog's Section 1 rules ask, without exception found during this audit. Module boundaries, tenant isolation, config discipline, versioned API prefix, timezone rules, and rate limiting are all implemented as designed. No backend refactor is recommended. The remaining work is additive (adapters, tenant provider settings), not corrective.

### 3.2 Frontend (`web/`) — Next.js 16 App Router, TypeScript strict, Tailwind 4

Route groups by role, modules by business domain, strict one-way layers:

```
app/                       ROUTING   (organizer)/(guest)/(validator) + landing, SEO files
components/modules/<d>/    domain modules, each flat:
  schema.ts                CONTRACT  Zod schemas + backend DTO mirrors
  <d>.service.ts           SERVICE   Server Actions; only layer reading HTTP statuses
  use<D>.ts                CACHE     polling / IndexedDB hooks (where needed)
  <feature>.tsx            COMPONENT UI + validation
  index.ts                 BARREL    the only cross-module import surface
components/ui/             shadcn primitives · components/shared/ cross-cutting chrome
lib/                       api, api-server, auth, constants, datetime, error-tracking,
                           google-search-console (new), support, utils
proxy.ts                   cookie gate on /dashboard and /events
```

- **Auth:** correct BFF — JWTs only in httpOnly cookies, `serverFetch` adds the Bearer header server-side; the browser never sees a token.
- **Rendering:** per the framing document — organizer = RSC + client polling; guest = dynamic RSC + Server Actions; validator = client-dominant (camera, IndexedDB, sync queue). No SSG/ISR.
- **PWA:** `manifest.ts`, `public/sw.js`, `/offline` page, install icons.
- **Offline check-in engine** (`checkin` module): IndexedDB roster per validator link, queued offline check-ins, background sync, first-timestamp-wins reconciliation — the strongest code in the frontend.

**Assessment (Senior Frontend):** the layer model is real, not aspirational — with the specific violations listed in §5. The earlier `frontend-architecture-review.md` P0 items (hand-mirrored DTOs, no unified `ActionResult`, `as T` casts) remain the highest-leverage consolidation work and are re-affirmed here.

---

## 4. Feature status — full matrix against the backlog

Legend: ✅ implemented & tested · 🟡 partial (noted) · ⛔ not implemented · ➖ non-code story / ops task

| Story | What it is | Status | Notes |
|---|---|---|---|
| JIKU-1 | Backend skeleton, Modulith | ✅ | `@Modulithic`, verify() test |
| JIKU-2 | Frontend skeleton, route groups | ✅ | |
| JIKU-3 | Backend CI | 🟡 | Workflows exist in `.github/`; branch protection deliberately skipped for now |
| JIKU-4 | Frontend CI | 🟡 | Same |
| JIKU-5 | PostgreSQL + profiles + Flyway | ✅ | Docker local; Neon deferred by directive |
| JIKU-6 | Hibernate tenant isolation | ✅ | `@TenantId` + double guard + leak tests |
| JIKU-7 | CD pipelines | ➖ | Deliberately skipped (per standing directive) |
| JIKU-8 | Auth foundation (JWT, refresh, roles) | ✅ | `AuthFlowTest` covers register→login→refresh |
| JIKU-9 | PWA shell | ✅ | Manifest, SW, offline page, icons |
| JIKU-9B | Public-endpoint rate limiting | ✅ | Shared filter + per-policy config; login migrated onto it |
| JIKU-9C | Backup + verified restore | ⛔ | Ops story; do before launch (depends on hosting) |
| JIKU-9D | Seed data | ✅ | `DemoDataSeeder` + per-module contributors + test |
| JIKU-10 | Tenant domain + registration | ✅ | Atomic tenant+admin creation |
| JIKU-11 | White-label branding | 🟡 | Backend complete (`BrandingController`); **no organizer settings UI** |
| JIKU-12 | Register/login UI | ✅ | RHF+Zod, httpOnly cookies |
| JIKU-13 | Event domain + settings | ✅ | Status machine, `EventSettings`, capacity |
| JIKU-14 | Event wizard UI | ✅ | Draft save/resume, progressive settings |
| JIKU-14B | Cancellation cascade | ✅ | Atomic cascade + notifications + check-in contract; tested |
| JIKU-15 | Guest model + CSV import | ✅ | Per-row results, duplicates, caps; verify MX/deliverability warning depth when Resend lands |
| JIKU-16 | Email invitation sending | 🟡 | Full queue/retry/status pipeline done; **provider adapter = logging stub → wire Resend (§9)** |
| JIKU-17 | WhatsApp sending | 🟡 | Same pipeline; **adapter stub → wire Meta Cloud API (§9)**; template approval is an external prerequisite |
| JIKU-18 | Guest list UI | ✅ | Import, filters, bulk send, statuses |
| JIKU-19 | RSVP flow + capacity | ✅ | Atomic conditional update; concurrency test |
| JIKU-20 | Ticket + QR | ✅ | Non-guessable code, atomic with confirmation |
| JIKU-21 | Guest ticket page | ✅ | Client-rendered QR, offline-tolerant |
| JIKU-22 | Check-in endpoint | ✅ | Atomic `UPDATE…WHERE`, "already checked in by X at Y" |
| JIKU-23 | Validator links | ✅ | Labeled, revocable, scoped |
| JIKU-24 | Validator scan UI | ✅ | Lazy QR scanner, search fallback |
| JIKU-25 | Offline check-in | ✅ | IndexedDB roster, queue, conflict resolution; `OfflineSyncTest` |
| JIKU-26 | Organizer dashboard | ✅ | Polling, per-validator breakdown |
| JIKU-27 | CSV export | ✅ | Streaming authed download route |
| JIKU-27B | Support contact | ✅ | Env-driven email/WhatsApp `SupportButton` |
| JIKU-28 | Notification orchestration | ✅ | Event-driven listeners, central templates, audit log |
| JIKU-28B | Sender reputation | 🟡 | Webhook endpoint, rolling metrics, alerts done; payload mapping to **Resend's actual webhook format** pending adapter work |
| JIKU-29 | Logging + error tracking | 🟡 | Structured JSON + correlation IDs done; `ErrorTracker`/`lib/error-tracking` are console stubs — provider decision (Sentry?) still open |
| JIKU-30 | Uptime/perf monitoring | 🟡 | Health probes + latency histograms exposed; external monitor + runbook = ops task |
| JIKU-31 | Pre-launch security review | ➖ | Scheduled last; `CrossModuleTenantIsolationTest` already covers part |
| JIKU-32 | Usage metering | ✅ | `UsageRecord`, configurable free tier, dashboard visibility |
| JIKU-33 | Mobile Money | 🟡 | Full flow against `SandboxPaymentProvider` (real HMAC verification); real provider = open decision |
| JIKU-34 | Paywall enforcement | ✅ | Server-side `UsageAllowanceGate`; whole-batch block; tested |
| JIKU-35 | Billing history + receipts | ✅ | History view + receipt download route |
| JIKU-36 | Right to erasure | ✅ | Anonymization preserving aggregates; audit log; tested |
| JIKU-37 | Retention policy | ✅ | Scheduled job reusing JIKU-36 logic; dashboard notice |
| JIKU-38 | Privacy notice | ✅ | `(guest)/privacy` page, linked from guest pages |
| JIKU-39–41 | UAT plan / sessions / remediation | ➖ | Not started (process stories) |

**Bottom line:** of the 41 stories, everything that can be finished purely in code is done except the provider adapters, the org-settings UI (JIKU-11's front half), and the error-tracking integration. The remainder is ops/process (9C, 30-external, 31, 39–41, 7).

---

## 5. Rule & pattern violations found (and how to fix them properly)

### 5.1 Frontend violations

**V1 — `components/modules/organizer/` breaks the flat-module convention.** *(severity: medium, effort: small)*
It nests `components/` and `services/` subfolders, has **no `index.ts` barrel**, and `app/(organizer)/events/[id]/billing/page.tsx` deep-imports `.../organizer/components/billing-view` — all three contrary to `AGENTS.md` ("FLAT folder… other layers import a module only through its `index.ts` barrel").
**Fix:** fold it into a flat `components/modules/billing/` module: `schema.ts` (absorb `billing-types.ts`), `billing.service.ts` (absorb `services/billing.ts`), `billing-view.tsx`, `index.ts`. Update the route import to the barrel. There is no other content in `organizer/`, so the folder disappears.

**V2 — `guest/billing-view.tsx` is a byte-for-byte duplicate of `organizer/components/billing-view.tsx`.** *(severity: high — DRY hard rule, effort: trivial)*
Two identical 100+ line files; whichever one a future fix misses becomes a latent bug. It also makes the `guest` module import `organizer` internals (cross-module deep import).
**Fix:** delete `guest/billing-view.tsx` as part of the V1 consolidation; the single `billing` module owns the view. Nothing in the guest role uses billing.

**V3 — Billing route sits outside the authenticated layout group.** *(severity: medium, effort: trivial)*
`app/(organizer)/events/[id]/billing/page.tsx` lives outside `(app)/`, so it loses the sidebar/app chrome every other organizer page gets (`(organizer)/(app)/layout.tsx`), producing an inconsistent UX jump.
**Fix:** move to `app/(organizer)/(app)/events/[id]/billing/page.tsx`. `proxy.ts`'s `/events/:path*` matcher already covers it.

**V4 — Hardcoded `Intl.DateTimeFormat("en", …)` in billing view.** *(severity: low)*
Duplicates the date-formatting concern `lib/datetime.ts` owns, and will fight the FR+EN i18n decision.
**Fix:** route through `lib/datetime` and make locale follow the active i18n locale once next-intl lands.

**V5 — Unapplied P0/P1 items from `web/docs/frontend-architecture-review.md`.** *(severity: medium, effort: medium)*
Still true in the current tree: backend DTOs hand-mirrored inline in views instead of `schema.ts`/generated types (`lib/api-types.ts` exists but adoption is incomplete), no unified `ActionResult`/`handle()` (each service hand-rolls result shapes; `lib/action-result.ts` exists — finish wiring all services onto it), channel constants defined in multiple places, `authedGet` duplication. That review's fix plan remains valid; treat it as the checklist for a consolidation story rather than re-deriving it here.

### 5.2 Backend violations

None found against the agreed rules. Spot-checks of module APIs, tenant guards, config externalization, rate-limit centralization, and the notification event-driven boundary (JIKU-28's "only `notification` touches provider SDKs") all conform. Two watch-items, not violations:
- `SecurityConfig`/controllers were not exhaustively line-audited for every endpoint's authorization rule — JIKU-31 (security review) should do that systematically before launch.
- JIKU-15's "deliverability check beyond syntax" (MX lookup or provider validation) should be re-verified once Resend is wired; if absent, Resend's validation + the JIKU-28B hard-bounce flagging loop is an acceptable MVP substitute — document the choice in the PR.

### 5.3 Documentation drift

- `jiku-project-framing.md` §9 says the no-AI-trace rule is "enforced by a CI blocklist check", while the backlog §1.4 explicitly says that approach was dropped. Align the framing doc with the backlog (the backlog is the source of truth).
- The framing document has two sections numbered "11".
- `web/docs/frontend-architecture-review.md` predates `lib/action-result.ts`/`lib/api-types.ts`; mark applied items when the consolidation story closes them.

---

## 6. Senior-role analyses

### 6.1 Senior Architect
The modulith + BFF split is working exactly as intended: the versioned contract is the only coupling between repos, boundaries are machine-enforced on both sides (Modulith verify / barrel convention), and every "later" decision (email, WhatsApp, payment, error tracking) was correctly hidden behind a port so the stubs never leaked upward. The single structural addition this audit recommends is a **provider-strategy layer for tenant-selectable notification providers** (§9) — designed as configuration-driven adapter resolution inside the `notification` module, it requires no new module and no boundary change. Resist any temptation to split `notification` per provider; adapters are internal classes selected at send time.

### 6.2 Senior Backend
Test discipline is the standout: acceptance-criteria-shaped tests (concurrency, isolation, rollback) rather than coverage filler. Priorities now: (1) Resend adapter + real webhook payload mapping for JIKU-28B; (2) Meta WhatsApp adapter with E.164 validation already in place; (3) tenant provider-credentials entity with encryption at rest (§9.3); (4) keep `SandboxPaymentProvider` until the Mobile Money decision, it is doing its job. When adding tenant BYO credentials, ensure the credentials table extends `BaseTenantEntity` so isolation is automatic, and that secrets are excluded from all read DTOs and logs.

### 6.3 Senior Frontend
Fix the five violations in §5.1 (a day of work, most of it mechanical), then execute the existing review's P0 consolidation. After that the codebase is consistent enough that the new work (org settings page, i18n, landing rewrite) lands on a clean base. For i18n, `next-intl` with `[locale]` segment routing fits the App Router structure already in place; guest-facing pages should default to the event's tenant locale eventually, but MVP scope is: landing + auth + guest pages localized FR/EN, organizer app can follow.

### 6.4 Senior UI/UX
The product surfaces (wizard, guest list, validator console, dashboard) follow good patterns — progressive disclosure in the wizard, one-tap-away search fallback in the validator console, glanceable check-in states. Gaps: (1) **no organizer settings area at all** — branding, and soon provider credentials, have nowhere to live; add a `/settings` section with tabs (Organization · Branding · Messaging providers · Billing); (2) the billing page losing the sidebar (V3) reads as a broken page; (3) the landing page leans on abstract SVG animation everywhere — premium positioning needs at least one real product visual (dashboard screenshot or styled mockup) and one human/venue photograph (see §8).

### 6.5 Senior BA
The implemented feature set matches the validated scope with no scope creep — deferred items (transfer, seating, multi-validator accounts) stayed out. Two business-critical misalignments: the landing pricing model contradiction (§8 — fix before anyone sees the site) and the missing JIKU-9C backup verification (a data-loss incident with zero restore evidence is an existential risk for a trust-based product; schedule it as ops work now that hosting is near). The UAT epic (39–41) is the actual gate to launch; the codebase is close enough that drafting the UAT script (a document, no dependencies) should start immediately.

---

## 7. SEO audit (Senior SEO Specialist)

### In place (good)
`metadataBase` + title template + description, robots meta with googleBot directives, `robots.ts`, `sitemap.ts`, PWA manifest, semantic landing structure (`h1` → `h2` per section), `next/font` (no CLS from fonts), icons. **Added during this audit:** Google Search Console verification meta + API support (§10).

### Gaps and fixes, in priority order

1. **No Open Graph / Twitter image.** `summary_large_image` is declared with no image — shares render blank. **Fix:** add `app/opengraph-image.tsx` (dynamic OG via `next/og`) or a designed static `opengraph-image.png` (1200×630); Next auto-wires both OG and Twitter tags.
2. **No structured data.** **Fix:** JSON-LD on the landing page — `Organization`, `SoftwareApplication` (with `offers` reflecting the real FCFA pay-per-event pricing, only once it's truthful), and `FAQPage` if a FAQ section is added in the rewrite. Render via a `<script type="application/ld+json">` in a Server Component.
3. **Sitemap has one URL.** **Fix:** add `/privacy` and, post-i18n, every locale variant with `alternates.languages`. Keep tokenized guest/validator URLs out (correct today).
4. **`robots.ts` disallow list misses authenticated areas.** `/dashboard`, `/events`, `/billing` are crawlable in principle (they redirect, but disallowing is cleaner and avoids soft-404 noise). **Fix:** add them to `disallow`. Reconsider disallowing `/register` — it's a legitimate conversion landing target; keep `/login` disallowed.
5. **No hreflang / locale signals.** Comes with the FR+EN i18n decision: `[locale]` routes, `alternates: { languages: { fr, en } }` in metadata, `lang` attribute per locale, French keywords (e.g. « billetterie événementielle », « invitation mariage WhatsApp », « check-in QR code », « gestion des invités ») which currently have zero presence.
6. **Landing is 100% Client Components.** Content is still server-rendered into HTML (so it *is* indexable), but the JS payload and the opacity-0-until-visible animation pattern hurt LCP and risk content being visually hidden at first paint. **Fix during the rewrite:** make copy-bearing sections Server Components, keep animation as CSS (`@media (prefers-reduced-motion)`-aware) or a single small client wrapper; target Lighthouse ≥ 90 mobile.
7. **Canonicals:** add `alternates.canonical` per page once locales exist.
8. **Content depth:** the page has no long-form copy for any query. The rewrite (§8) should add a FAQ and use-case copy (weddings, conferences, galas — the market's actual search intents) — this is the real ranking lever, not tags.

---

## 8. Landing page content & humanization plan (Senior Product Marketer / Product Designer)

### 8.1 Remove — factually false or AI-tell content (decision: full honest rewrite)

| Current | Problem | Replace with |
|---|---|---|
| Stats row: "50K+ invitations · 1.2K+ events · 200K+ checked in · 99.9% offline reliability" (`hero-section.tsx`) | Fabricated; legally risky; classic AI filler | Product truths: "Invitations by email & WhatsApp · Tickets QR infalsifiables · Check-in même sans réseau · Vos couleurs, votre logo" |
| Three named testimonials with invented people/companies (`testimonials-section.tsx`) | Fabricated endorsements | Use-case section (mariage / conférence / gala) written as scenarios, or a pilot-program invitation ("Rejoignez les premiers organisateurs") — swap in real quotes after UAT (JIKU-40 produces them) |
| TrustBar: "SOC 2 compliant · GDPR compliant · 99.9% uptime SLA" | False compliance claims | Honest trust signals: "Vos données restent les vôtres — suppression sur demande" (JIKU-36 is real), "Paiement Mobile Money", "Fonctionne hors-ligne le jour J" |
| "Join thousands of organizers" (`cta-section.tsx`) | False | "Créez votre premier événement gratuitement — jusqu'à 100 invités" |
| Pricing: $0/$29/$99 **per month**, "Start trial", "SLA guarantee" (`pricing-section.tsx`) | Contradicts validated pay-per-event XOF model and the implemented backend (`billing.free-tier-guests=100`, XOF, tier catalog endpoint exists) | Pay-per-event cards in FCFA: Gratuit (≤100 invités) / paliers par événement mirroring the backend tier catalog (`BillingTiersController`) — ideally rendered from it so pricing can never drift again |

### 8.2 Humanization principles for the rewrite

- **French first, and *market* French** — speak to a wedding planner in Abidjan or a conference organizer in Dakar, not to a generic SaaS buyer. Name the real alternative ("WhatsApp + Excel + tickets papier") because that is the validated competitor.
- Kill the AI-tells: "game-changer", "transformed how we…", "flawless experiences", symmetrical three-adjective lists, exclamation-free hype. One concrete sentence beats three abstract ones: *« Le réseau tombe pendant l'entrée ? Vos contrôleurs continuent de scanner. Tout se synchronise au retour du réseau. »*
- Every claim must be demonstrable in the product today. If UAT hasn't happened, the site says "early access", not "trusted by thousands".
- **Premium imagery:** replace at least the hero's abstract SVG with (a) a real dashboard/validator-console screenshot in a device frame, and (b) one licensed, non-stock-looking photograph of an African event context (gala/wedding/conference — e.g. from an African-focused collection; avoid generic Western stock). Ship as optimized `next/image` with proper `alt` in both locales. The animated SVG feature cards can stay as accents — they're distinctive — but they cannot be the only visuals.
- Keep the section rhythm (hero → features → how-it-works → use cases → pricing → CTA → footer); it's sound. Add a short FAQ (SEO + objection handling: "Mes invités doivent-ils installer une app ? Non.").

---

## 9. New capability design: production email + tenant self-service providers

### 9.1 Production email — Resend via the Tûm transport pattern (validated)

Mirror `AFG/tum`'s proven structure inside `com.jiku.notification.internal`:

```
EmailSender (existing port — unchanged, callers unaffected)
├── SmtpEmailSender      @ConditionalOnProperty jiku.mail.transport=smtp (matchIfMissing=true)
│                        JavaMailSender → Mailpit locally (MAIL_HOST/MAIL_PORT already in .env.example)
├── ResendEmailSender    @ConditionalOnProperty jiku.mail.transport=resend
│                        RestClient → https://api.resend.com/emails, Bearer RESEND_API_KEY,
│                        5s connect / 15s read timeouts, fail-fast if key missing,
│                        throws EmailDeliveryException so the existing retry pipeline engages
└── LoggingEmailSender   retire once the above land (or keep behind transport=log for tests)
```

- Env: `MAIL_TRANSPORT` (smtp|resend), `RESEND_API_KEY`, existing `MAIL_FROM`; add Mailpit to `docker-compose.yml` for local dev.
- JIKU-28B: map Resend's webhook events (`email.bounced`, `email.complained`, `email.delivered`) onto the existing `EmailFeedbackController` contract; verify Resend's webhook signature (svix headers) with `NOTIFICATION_WEBHOOK_SECRET`.
- Templates stay where they are (`resources/email-templates/`) — same "templates folder" approach as Tûm.

### 9.2 WhatsApp — Meta Cloud API behind a pluggable provider strategy (validated)

Same shape: `WhatsAppSender` port stays; add `MetaCloudWhatsAppSender` (Graph API `POST /{phone-number-id}/messages`, template messages, Bearer access token). Design the adapter selection **per tenant** from day one (§9.3), because that is the validated post-MVP path (Brevo, Twilio, 360dialog as future `WhatsAppSender` implementations the org owner can choose between). External prerequisite to start now: Meta Business verification + template approval.

### 9.3 Tenant self-service provider settings (new requirement, validated)

**Backend (`tenant` or `notification` module — recommend `notification`, since credentials exist to serve sending):**
- New entity `TenantProviderSettings extends BaseTenantEntity`: `channel` (EMAIL|WHATSAPP), `provider` (e.g. RESEND, SMTP, META_CLOUD; enum, extensible), `credentials` (JSON, **encrypted at rest** with AES-GCM under a new `PROVIDER_CREDENTIALS_ENCRYPTION_KEY` env var), `active` flag, `verifiedAt`.
- Resolution rule at send time: tenant-active provider → else platform default from env. One resolver class per channel inside `notification.internal`; no public API change.
- Endpoints (organizer-authenticated): GET current settings (credentials **masked**, e.g. last 4 chars), PUT per channel, POST `/test` that sends a test email / WhatsApp message to the organizer so setup is verifiable in one click — this is what makes it "easy to set up via web".
- Never return or log raw credentials; masked DTOs only.

**Frontend:** new flat module `components/modules/settings/` + routes `app/(organizer)/(app)/settings/{organization|branding|messaging}` — Branding tab finally exposes JIKU-11 (name, logo URL, primary color with live preview); Messaging tab: provider picker per channel (Email: "Jikū par défaut / Resend (ma clé) / SMTP", WhatsApp: "Meta Cloud API (mes identifiants)"), guided fields with help text per provider (for Meta: Phone Number ID, WABA ID, access token — with a short "where to find this" note), Save + "Send test message" button.

**Suggested story breakdown (continuing the backlog numbering):**

| ID | Story | Depends on |
|---|---|---|
| JIKU-42 | Resend + SMTP email adapters (Tûm pattern) + Resend webhook mapping for 28B | decision §2.3 |
| JIKU-43 | Meta Cloud API WhatsApp adapter | decision §2.4 |
| JIKU-44 | Tenant provider settings — backend (entity, encryption, resolver, masked API, test-send) | 42, 43 |
| JIKU-45 | Organizer settings UI (Organization · Branding · Messaging) | 44 |
| JIKU-46 | Landing rewrite FR/EN + next-intl + honest pricing from tier catalog | decisions §2.1–2.2 |
| JIKU-47 | SEO completion (OG image, JSON-LD, sitemap/robots, hreflang, FAQ) | 46 |
| JIKU-48 | Frontend consolidation (apply frontend-architecture-review P0/P1 + §5.1 V1–V4) | none |

---

## 10. Changes already applied during this audit

**P0 items completed on 2026-07-09 (after the decisions in §2):**

1. **JIKU-42 — production email (Tûm pattern).** `SmtpEmailSender` (Mailpit/SMTP) and `ResendEmailSender` (HTTPS API, timeouts, fail-fast on missing key) added behind the existing `EmailSender` port, selected by `MAIL_TRANSPORT` (`log` default · `smtp` · `resend`). Resend webhook endpoint `/notifications/email-feedback/resend` with Svix signature verification (`SvixSignatureVerifier`) and `ResendFeedbackMapper` normalizing `email.bounced`/`email.complained` into the JIKU-28B reputation pipeline. `spring-boot-starter-mail` added; security matcher and rate-limit policy widened to `/notifications/email-feedback/**`; all variables documented in `app/.env.example`. Tests: `SvixSignatureVerifierTest`, `ResendFeedbackMapperTest`, `ResendEmailSenderTest` — green, along with the existing notification/invitation integration suites and `ModularityTests`.
2. **JIKU-46 — landing rewrite, FR default + EN.** All fabricated content removed (stats, testimonials, SOC 2/GDPR/SLA badges, "thousands of organizers", fake newsletter form). New content contract `components/modules/landing/content.ts` holds every word in both locales; French serves at `/`, English at `/en`, with hreflang alternates, per-locale metadata, and `<html lang="fr">`. Pricing now mirrors the backend catalog (Gratuit ≤100 · Standard 5 000 FCFA ≤2 000 · Premium 20 000 FCFA ≤10 000, per event, shared feature list). New FAQ section (+ `FAQPage`/`Organization`/`SoftwareApplication` JSON-LD), testimonials replaced by an honest use-cases section with a truthful trust bar. All copy-bearing sections are now Server Components (`/` and `/en` prerender statically); sitemap gained `/en` + `/privacy` with locale alternates; robots now disallows `/dashboard`, `/events` and allows `/register`.
3. **Billing module consolidation (§5.1 V1–V4).** Flat `components/modules/billing/` module (schema/service/view/barrel) replaces the non-conforming `organizer/` folder; the duplicate `guest/billing-view.tsx` is deleted; the billing route moved into `(organizer)/(app)/` so it gets the app chrome; date formatting moved to a new `lib/datetime.formatLocalDateTime`. `next build` and `tsc` green.

Still open from the P0 list: a real product screenshot/photograph for the hero (needs an asset decision — the abstract SVG visuals remain until then).

Google Search Console support in `web/` (as requested):

1. **`web/.env.example`** — new documented variables: `NEXT_PUBLIC_GOOGLE_SITE_VERIFICATION` (ownership meta tag), `GOOGLE_SEARCH_CONSOLE_CLIENT_EMAIL`, `GOOGLE_SEARCH_CONSOLE_PRIVATE_KEY`, `GOOGLE_SEARCH_CONSOLE_SITE_URL` (service-account API access; server-only).
2. **`web/app/layout.tsx`** — `metadata.verification.google` now renders the `google-site-verification` meta tag from the env var on every page.
3. **`web/lib/google-search-console.ts`** — new zero-dependency, server-only Search Console API client: service-account JWT → access token (RS256 via `node:crypto`), `submitSitemap()` (call from a deploy hook or admin route after releases) and `inspectUrl()` (index status / canonical / last crawl for SEO health checks), plus `searchConsoleConfigured()` guard. Type-checked clean (`tsc --noEmit` passes).

Setup steps when the domain is live: verify the property in Search Console with the meta tag → create a GCP service account with the Search Console API enabled → add its email as a full user of the property → set the three `GOOGLE_SEARCH_CONSOLE_*` vars → submit the sitemap.

---

## 11. Prioritized remediation plan

**P0 — before anything else (trust, legality, revenue truthfulness)**
1. JIKU-46: landing rewrite — remove all fabricated claims, FR/EN, pay-per-event FCFA pricing. *(§8)*
2. JIKU-42: Resend email adapter + webhook mapping — the product cannot invite anyone for real without it. *(§9.1)*
3. §5.1 V2/V3 quick fixes (delete duplicate billing view; move billing route into `(app)`) — trivial, do alongside.

**P1 — completes the MVP's commercial loop**
4. JIKU-43: Meta WhatsApp adapter (start Meta verification/template approval immediately — external lead time).
5. JIKU-44 + JIKU-45: tenant provider settings backend + organizer settings UI (also closes JIKU-11's missing front half).
6. JIKU-47: SEO completion (OG image, JSON-LD, sitemap/robots, hreflang).
7. Error-tracking decision (JIKU-29) — recommend Sentry; both `ErrorTracker` (backend) and `lib/error-tracking.ts` (frontend) are ready ports.

**P2 — hardening & consistency**
8. JIKU-48: frontend consolidation (existing review P0/P1 + §5.1 V1/V4).
9. JIKU-9C backup/restore verification + JIKU-30 external monitor + runbooks (ops, once hosting is provisioned).
10. Mobile Money provider decision → replace `SandboxPaymentProvider` (JIKU-33).

**Launch gate (unchanged from backlog):** JIKU-39 UAT script (can start now) → JIKU-40 UAT sessions → JIKU-41 remediation → JIKU-31 security review → V1 merge.

---

*This report supersedes nothing; `docs/jiku-mvp-backlog.md` remains the single source of truth for scope. New stories proposed here (JIKU-42…48) should be appended to the backlog with full acceptance criteria before implementation, following the story template in its Section 4.*

<div align="center">

<img src="docs/assets/jiku-logo.svg" alt="Jikū" width="96" height="96">

# Jikū API

**Invitations, tickets, appointments and queues for organizations in francophone Africa.**

The backend of Jikū: a multi-tenant REST API in Kotlin, Spring Boot and Spring Modulith.

[Web app](https://github.com/youmssi/jiku_web) ·
[Architecture decisions](docs/adr) ·
[Deployment](docs/deploy.md) ·
[Contributing](CONTRIBUTING.md)

![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![Spring Modulith](https://img.shields.io/badge/Spring%20Modulith-2.1-6DB33F)
![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Neon-4169E1?logo=postgresql&logoColor=white)

</div>

---

## About

Jikū replaces the mix of WhatsApp groups, spreadsheets and printed cards that
organizations use to receive people. One platform covers two jobs:

- **Events.** Invite guests by WhatsApp, e-mail or SMS, collect RSVPs, issue QR
  tickets and check people in at the door, even offline.
- **Services.** Let clients book an appointment or take a ticket for today's
  line from a QR code at the entrance, then call them in order at the counter.

Each organization works under its own brand (logo, colours, sender name) and
pays in its own currency (GNF, FCFA or USD) by Mobile Money or card. Jikū never
holds the money an organization's clients pay it: tickets are paid straight to
the organization's own Mobile Money number or payment link.

## Features

| Area | What the API provides |
|---|---|
| **Organizations** | Sign-up with e-mail or Google, e-mail verification, members and roles, invitations to join, branding, legal identity for invoices, public profile page |
| **Events** | Events with recurring occurrences, ticket categories with prices and payment rules, custom RSVP questions, publish and cancel lifecycle, quorum tracking |
| **Guests and invitations** | CSV import, three delivery modes (link, direct ticket, interactive WhatsApp with reply buttons), RSVP, ticket transfer, reminders, data erasure on request |
| **Check-in** | QR validation at the door with offline sync, duplicate detection with who and when, payment collection at the door, live dashboard and analytics, attendance certificates |
| **Appointments** | Services, resources and weekly schedules, a fixed slot grid with atomic reservation, confirmation on request or instantly, booking links, short codes and an embeddable widget |
| **Day line** | One line mixing appointments and walk-ins, "next" by rule, clients taking their own ticket by QR and following their rank live, "it's your turn" messages, counter numbers |
| **Operators** | Staff with a scope (events, services) and allowed actions (check-in, queue, payment collection), each with a personal link and short code |
| **Messaging** | E-mail (Resend with Brevo overflow), WhatsApp Cloud API (templates, interactive replies, delivery webhooks), SMS (Nimba), per-message cost tracking, an organization's own WhatsApp number through Meta Embedded Signup |
| **Billing** | Event tiers, the Organizer Pack, per-team service plans, trials, the own-number add-on, prices per currency, online payment with CinetPay, manual Mobile Money transfers, sequential invoices and credit notes |
| **Back office** | Platform desk for tenants, payments, trials, agreements, billing settings, WhatsApp health, feedback, prospects and a full audit log |

## Architecture

Every package directly under `com.jiku` is a Spring Modulith application module.
A module exposes only its `*ModuleApi` interface and application events; no
module reaches into another's repositories or entities.

```
com.jiku
├── tenant       organizations, organizer identity, members, branding, legal identity
├── catalog      events, ticket categories, services, resources, slots, day line, operators
├── allocation   atomic capacity (seats, slots) shared by events and services
├── invitation   guests, CSV import, invitations, RSVP, transfers
├── ticket       tickets, QR codes, payment status
├── checkin      door validation, offline sync, dashboards, attendance certificates
├── messaging    e-mail, WhatsApp and SMS orchestration, templates, webhooks
├── money        usage, tiers, packs, subscriptions, payments, invoices
├── backoffice   platform administration desk, feedback, prospects, audit
└── shared       cross-cutting code only (tenant context, web config, errors)
```

Key decisions are recorded as ADRs in [`docs/adr`](docs/adr):

| ADR | Decision |
|---|---|
| [6](docs/adr/adr-6-multi-tenancy-strategy.md) | Shared schema with a `tenant_id` column, enforced by a Hibernate tenant filter |
| [69](docs/adr/adr-69-invoice-numbering-and-immutability.md) | Gap-free invoice numbers and immutable invoices |
| [79](docs/adr/adr-79-distributed-rate-limit-counters.md) | Rate limits counted in the database, correct across instances |
| [85](docs/adr/adr-85-fixed-grid-and-atomic-reservation.md) | Fixed slot grid and atomic reservation, no double booking |
| [102](docs/adr/adr-102-allocation-module.md) | One allocation module for seats and slots |
| [103](docs/adr/adr-103-recurring-events.md) | Recurring events as occurrences of one event |
| [104](docs/adr/adr-104-ticket-paiement-canaux-tarification.md) | Tickets, payment levels and channels |
| [105](docs/adr/adr-105-tarification-canaux-marches.md) | One price grid in three currencies, delivery modes and target markets |

**Multi-tenancy.** Tenant isolation lives in the persistence layer, not in
hand-written `WHERE` clauses. Background jobs and webhooks bind the tenant
before a transaction opens, so a missed filter can never leak data across
organizations.

**API.** Every endpoint is versioned under one configurable prefix (`/api/v1`
by default). Changes to `/v1` are additive only. The OpenAPI contract is
committed in [`openapi/openapi.json`](openapi/openapi.json), served live at
`/swagger-ui/index.html`, and consumed by the web app's generated types.

## Tech stack

| | |
|---|---|
| Language | Kotlin 2.3 on Java 25 |
| Framework | Spring Boot 4.1 (Web MVC, Data JPA, Security, Validation, Actuator), Spring Modulith 2.1 |
| Database | PostgreSQL, migrations with Flyway |
| Messaging providers | Resend, Brevo, Meta WhatsApp Cloud API, Nimba SMS |
| Payments | CinetPay (Orange Money, MTN MoMo, card), manual Mobile Money transfers |
| Observability | Sentry, Actuator health, structured logs with request ids |
| Tests | JUnit 5, MockMvc, Testcontainers, Spring Modulith verification, Kover, Playwright end-to-end |

## Getting started

### Prerequisites

- JDK 25
- Docker, for PostgreSQL and the integration tests

### Run locally

A fresh clone needs no `.env`: `docker-compose.yml` and `application.yaml`
share the same `DATABASE_*` variables and defaults.

```bash
docker compose up -d     # PostgreSQL
./gradlew bootRun        # API on http://localhost:8080/api/v1
```

Open `http://localhost:8080/swagger-ui/index.html` to browse the API. Run the
[web app](https://github.com/youmssi/jiku_web) beside it for the full product.

### Demo data

```bash
./gradlew seedDemoData
```

Creates a "Demo Events Co" organization with events in every state, a guest
list and check-in history. Sign in as `demo-organizer@jiku.example` /
`demo-password`. Safe to re-run: it resets the demo tenant first.

## Configuration

There is a single `application.yaml`. Every value that can differ between
environments is a `${ENV_VAR:default}` placeholder; defaults keep local
development zero-config. [`.env.example`](.env.example) lists every variable in
two sections:

- **Required in production:** database, JWT secret, public URLs, e-mail
  sender, CORS origins.
- **Optional, per feature:** WhatsApp (Meta), SMS (Nimba), CinetPay, Google
  sign-in, Sentry, rate limits, billing prices per currency, seller identity
  for invoices.

Secrets ship with development defaults only and must be set explicitly outside
local.

## Quality gates

Every pull request runs, and must pass:

```bash
./gradlew ktlintCheck                                # style (ktlint_official)
./gradlew test --tests "com.jiku.ModularityTests"    # module boundaries, no Docker needed
./gradlew build                                      # unit, integration (Testcontainers) and contract tests
./gradlew koverVerify                                # 70% coverage on business logic
./gradlew regenerateOpenApi                          # after any endpoint change
```

The end-to-end suite in [`e2e/`](e2e) drives the real web app against this API,
PostgreSQL and Mailpit: registration, e-mail verification, guest import,
invitation, RSVP, ticket, check-in, appointments and the day line.

```bash
./scripts/run-e2e.sh                 # every journey
./scripts/run-e2e.sh --grep @smoke   # the fast subset
```

It expects a checkout of the web repository beside this one, or `E2E_WEB_DIR`
pointing at it.

## Deployment

| Piece | Where |
|---|---|
| API | Render (always-on instance), or the Docker Compose + Caddy setup in [`docker-compose.prod.yml`](docker-compose.prod.yml) |
| Database | Neon (managed backups, point-in-time restore) |
| Web | Vercel |

`develop` is the integration branch; merging `develop` into `main` releases to
production, and [`post-deploy.yml`](.github/workflows/post-deploy.yml) checks the
live service afterwards. Step-by-step guides:

- [Deploying to a permanent host](docs/deploy.md)
- [Backups](docs/backup.md) and [database restore](docs/runbooks/database-restore.md)
- [Uptime and performance](docs/runbooks/uptime-and-performance.md)
- [Pre-launch security review](docs/security/pre-launch-security-review.md)
- [User acceptance test plan](docs/uat/uat-test-plan.md)

## Documentation

| Document | Content |
|---|---|
| [Référentiel métier](docs/jiku-referentiel-metier.md) | The product's vocabulary and business rules |
| [Plan de production](docs/jiku-plan-production.md) | Launch scope and remaining work |
| [Modèle financier](docs/jiku-modele-financier.md) | Unit costs, margins and revenue scenarios |

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) describes the workflow: one branch per
story (`jiku-{n}-{slug}`) from an up-to-date `develop`, Conventional Commits with
a `Refs: JIKU-<n>` trailer, a squash merge once CI is green, and one story merged
before the next starts. [`AGENTS.md`](AGENTS.md) holds the engineering rules:
module boundaries, no hardcoded configuration and tests that match each story's
acceptance criteria.

## License

This repository is private. All rights reserved.

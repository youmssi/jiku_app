# Jikū — Backend Service

White-label SaaS for event invitation, ticketing, RSVP, and check-in. This is the
backend service: Kotlin + Spring Boot + Spring Modulith, exposing a versioned REST
API under `/api/v1`. The frontend lives in the sibling `web/` service.

## Tech stack

| Technology | Version |
|---|---|
| Kotlin | 2.3.21 |
| Spring Boot | 4.1.0 |
| Spring Modulith | 2.1.0 |
| Java toolchain | 25 |
| Build | Gradle (Kotlin DSL) |
| Database | PostgreSQL (Neon in production; Docker locally) |

## Module structure

Each package directly under `com.jiku` is an independent Spring Modulith application
module, exposing only its `*ModuleApi` to other modules. No cross-module access to
another module's internal types or repositories.

```
com.jiku
├── JikuApplication        # @Modulithic entry point
├── tenant                 # tenants, organizer identity, white-label config
├── event                  # events and event settings
├── invitation             # guests, imports, invitation sending
├── ticketing              # tickets, QR codes
├── checkin                # validation, presence tracking
├── notification           # email / WhatsApp orchestration
├── billing                # usage metering, tiers, concierge payments
├── admin                  # platform administration desk (tenants, payments, audit)
└── shared                 # genuinely cross-cutting code only
```

The module boundaries are enforced by an architecture test
(`com.jiku.ModularityTests`) that runs in the standard test suite and fails the build
on any boundary violation or dependency cycle. It performs static analysis only and
needs no database or Docker.

## Prerequisites

- JDK 25 (the Gradle toolchain targets Java 25)
- Docker (for a local PostgreSQL instance and for integration tests via Testcontainers)

## Local development

The application code runs **natively** (`./gradlew bootRun`). Infrastructure it
depends on — PostgreSQL today, any further services later — runs in **Docker** via
`docker compose`. A fresh clone needs no `.env`: `docker-compose.yml` and
`application.yaml` share the same `DATABASE_*` variables and defaults, so the two
steps below just work.

```bash
# 1. Start backing services (PostgreSQL) in Docker
docker compose up -d

# 2. Run the service natively against them
./gradlew bootRun
```

Other useful commands:

```bash
# Full build: compile, ktlint, unit + architecture tests, integration tests
./gradlew build

# Module-boundary architecture verification only (no Docker needed)
./gradlew test --tests "com.jiku.ModularityTests"

# Coverage gate (70% on business logic)
./gradlew koverVerify
```

### Demo data

A single command populates a clearly identifiable demo tenant — "Demo Events Co",
`[DEMO]`-prefixed events in every lifecycle state (a draft, a published event with
RSVPs coming in, and a past event with a realistic check-in history) and a guest
list to match:

```bash
docker compose up -d      # database must be running
./gradlew seedDemoData
```

Log in afterwards as `demo-organizer@jiku.example` / `demo-password` (override the
password with `JIKU_DEMOSEED_PASSWORD` or the `jiku.demo-seed.password` property).
The command is safe to re-run at any time: it resets the demo tenant's data first,
so no manual cleanup is ever needed. Pointing `DATABASE_*` at another environment
(e.g. staging) seeds that environment instead.

Configuration lives in a single `application.yaml` (see `.env.example`, organized
into a REQUIRED section — what every real deployment must set — and an OPTIONAL
tuning section). Every environment-specific value is a `${ENV_VAR:default}`
placeholder: defaults keep local zero-config, and any other environment overrides
the variables. Secrets such as `AUTH_JWT_SECRET` ship only a development default
and must be set explicitly outside local.
Schema is owned by Flyway migrations under `src/main/resources/db/migration`.

> Integration tests and `bootRun` require PostgreSQL. The integration test suite
> provisions one automatically through Testcontainers, so Docker must be available
> when running the full `./gradlew build`.

## Engineering rules

See `AGENTS.md` (and `CLAUDE.md`) in this directory for the full set of rules every
contributor — human or AI — must follow: module boundaries, Conventional Commits,
no hardcoded configuration, and the no-AI-authorship-trace rule. These are not
optional.

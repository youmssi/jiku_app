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
`docker compose`. A fresh clone needs no `.env`: the Compose defaults and the `local`
Spring profile agree, so the two steps below just work.

```bash
# 1. Start backing services (PostgreSQL) in Docker
docker compose up -d

# 2. Run the service natively against them (uses the `local` profile by default)
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

Configuration is environment-driven (see `.env.example`). The `local` profile ships
sensible defaults; `staging` and `production` read everything from the environment
with no fallbacks, so a missing value fails fast rather than connecting to the wrong
database. Schema is owned by Flyway migrations under
`src/main/resources/db/migration`.

> Integration tests and `bootRun` require PostgreSQL. The integration test suite
> provisions one automatically through Testcontainers, so Docker must be available
> when running the full `./gradlew build`.

## Engineering rules

See `AGENTS.md` (and `CLAUDE.md`) in this directory for the full set of rules every
contributor — human or AI — must follow: module boundaries, Conventional Commits,
no hardcoded configuration, and the no-AI-authorship-trace rule. These are not
optional.

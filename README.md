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

The application code runs **natively** (`./gradlew bootRun`). Infrastructure
dependencies it talks to — PostgreSQL today, any further services later — run in
**Docker** and are started with `docker compose`. The Docker Compose setup and the
environment-variable wiring are introduced in JIKU-5; until then this skeleton is
purely structural.

```bash
# Build (compiles both services and runs the architecture + unit tests)
./gradlew build

# Run only the module-boundary architecture verification (no Docker needed)
./gradlew test --tests "com.jiku.ModularityTests"

# Run the service locally (requires a reachable PostgreSQL — see JIKU-5)
./gradlew bootRun
```

> Integration tests and `bootRun` require a running PostgreSQL. The integration test
> suite provisions one automatically through Testcontainers, so Docker must be
> available when running the full `./gradlew build`.

## Engineering rules

See `AGENTS.md` (and `CLAUDE.md`) in this directory for the full set of rules every
contributor — human or AI — must follow: module boundaries, Conventional Commits,
no hardcoded configuration, and the no-AI-authorship-trace rule. These are not
optional.

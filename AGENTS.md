<!-- BEGIN:jiku-project-context -->
# Jikū — Backend Service (AI Agent Instructions)

## Project Overview

Jikū is a white-label SaaS platform for event invitation, ticketing, RSVP, and
check-in. This is the backend service, built with Kotlin, Spring Boot, and Spring
Modulith. It exposes a versioned REST API consumed by the Next.js frontend in the
sibling `web/` service.

`docs/jiku-mvp-backlog.md` (in the repository root) is the single source of truth for
scope and every story's acceptance criteria. Read the relevant story in full before
implementing it.

## Tech Stack

| Technology | Version | Notes |
|---|---|---|
| **Kotlin** | 2.3.21 | JVM, `-Xjsr305=strict` |
| **Spring Boot** | 4.1.0 | `spring-boot-starter-webmvc`, Data JPA, Security, Validation, Flyway, Actuator |
| **Spring Modulith** | 2.1.0 | Module boundary enforcement (tracks the Boot 4.1 line) |
| **Java toolchain** | 25 | Set in `build.gradle.kts` |
| **Build** | Gradle (Kotlin DSL) | `./gradlew` |
| **Database** | PostgreSQL | Neon in production; Docker locally |

> Keep dependencies on the **latest stable, mutually compatible** versions. Spring
> Modulith's minor version tracks Spring Boot's minor version (Boot 4.1 → Modulith
> 2.1); do not pair mismatched trains.

## Module Structure (Spring Modulith)

Each package directly under `com.jiku` is an independent application module. The main
class `com.jiku.JikuApplication` is annotated `@Modulithic`.

```
com.jiku
├── JikuApplication      # entry point
├── tenant               # tenants, organizer identity, white-label config
├── event                # events and event settings
├── invitation           # guests, CSV import, invitation sending
├── ticketing            # tickets, QR codes
├── checkin              # check-in validation, presence tracking
├── notification         # email / WhatsApp orchestration (event-driven)
└── shared               # genuinely cross-cutting code only
```

A new `billing` module is introduced later (JIKU-32). Modules are **auto-detected**
from the Kotlin types they contain — a package becomes a module the moment a story
adds code to it; empty modules are not pre-declared. Add a `package-info.java` with
`@ApplicationModule` for a module only when it needs explicit configuration (a custom
display name, an `OPEN` designation, or restricted allowed dependencies). That single
Java file is the *only* reason `src/main/java` would ever exist in this Kotlin
service.

**Module boundary rules (hard requirements):**
- A module exposes only its `*ModuleApi` interface (a `@NamedInterface`) to other
  modules. Never let another module reach into an internal package, repository, or
  entity directly.
- When a module needs data or behavior from another module, call that module's
  exposed API or publish/consume an application event — never a direct repository or
  entity reference.
- Cross-cutting logic goes into a module's exposed API or the `shared` module, never
  by reaching into another module's internals.
- `com.jiku.ModularityTests` runs `ApplicationModules.of(JikuApplication).verify()` as
  part of the test suite. A boundary violation or dependency cycle fails the build —
  no exceptions. Run it locally before opening a PR.

## Multi-Tenancy

- Every tenant-scoped table carries a `tenant_id` column.
- Tenant isolation is enforced at the persistence layer via a Hibernate tenant filter
  (JIKU-6), driven by a request-scoped `TenantContext`. Do **not** rely on
  manually-added `WHERE tenant_id = ?` clauses as the only safeguard — a missed filter
  is a cross-tenant data leak, which is unacceptable for a white-label product.

## API Conventions

- All REST endpoints are versioned under a single configurable prefix
  (`api.base-path`, default `/api/v1`) applied centrally by `WebConfig` to every
  `@RestController`. Controllers declare only their resource path (e.g. `/events`);
  never hardcode the version in a controller or security matcher — read it from
  `ApiProperties`. A future version is a one-line config change.
- Breaking changes to an existing `/api/v1` endpoint are not allowed — introduce
  `/api/v2` and keep `/v1` until the frontend migrates. Additive changes are safe.
- Timestamps are stored in UTC. Every `Event` carries an explicit IANA timezone;
  guest/validator-facing times render in the event's timezone, never the viewer's.

## Local Development & Infrastructure

- Application code runs **natively** (`./gradlew bootRun`).
- Infrastructure services it depends on (PostgreSQL today, others later) run in
  **Docker** via `docker compose`, wired to the app through environment variables.
  The same `docker-compose.yml` serves any environment.
- Integration tests provision PostgreSQL through Testcontainers, so Docker must be
  available for the full `./gradlew build` and in CI.

## Environment Variables

- There is a **single** `application.yaml` (no per-profile files). Every value that
  can differ between environments (credentials, provider names, numeric thresholds,
  feature flags) is read via `@Value` or `@ConfigurationProperties` from a
  `${ENV_VAR:default}` placeholder — never a literal committed to YAML or source.
- The PostgreSQL connection uses the **same `POSTGRES_*` variable names as
  `docker-compose.yml`**, so the container's database and the app's datasource stay
  in sync from one `.env`.
- Defaults exist for local convenience so a fresh clone runs with no `.env`. Real
  environments override the variables; secrets (e.g. `JWT_SECRET`) ship only a
  development default and **must** be set explicitly outside local.
- A committed `app/.env.example` (placeholders only, never real secrets) lists every
  variable the backend needs, kept in sync as variables are introduced. The real
  `.env` is git-ignored.

## Engineering Rules (Hard Requirements)

1. **No AI authorship trace** — No commit message, PR description, code comment, or
   file header may reference "AI", "Claude", "Copilot", "ChatGPT", "generated by", or
   any equivalent, and no `Co-authored-by` line referencing a tool. Write everything
   exactly as a human engineer would, describing what the code does and why — never
   how it was produced. This is non-negotiable.
2. **Conventional Commits** — `<type>(<module>): <description>` with a
   `Refs: JIKU-<number>` trailer. Types: `feat`, `fix`, `refactor`, `test`, `docs`,
   `chore`, `perf`, `build`.
3. **Branching** — One branch per story, `jiku-{number}-{slug}`, created from
   `develop`. Never commit directly to `main` or `develop`. Squash-merge via PR.
4. **No hardcoded config** — see Environment Variables above.
5. **No duplicated logic** — centralize shared validation, formatting, or mapping,
   but never at the cost of a module boundary (use an exposed API or `shared`).
6. **Tests match acceptance criteria** — including the concurrency, cross-tenant
   isolation, and edge-case tests explicitly called out in a story. A story is not
   done without them.
7. **Stop at `[INTERACTIVE STEP]`** — present the options as written in the backlog
   and wait for a decision; never guess.

## Key Reference Files

- `app/CLAUDE.md` — Claude Code instructions (includes this file)
- `app/README.md` — setup and module overview
- `docs/jiku-mvp-backlog.md` — full backlog (single source of truth)
- `docs/jiku-project-framing.md` — architecture decisions and reasoning
- `web/` — frontend service repository (Next.js)
<!-- END:jiku-project-context -->

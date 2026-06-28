# Jikū — Claude Code Instructions

This file configures Claude Code for the Jikū backend service. It includes all project
context from `AGENTS.md`.

@AGENTS.md

## Claude-Specific Instructions

### Before Writing Any Code

1. Read `AGENTS.md` fully — do not skip this step
2. Read the specific `JIKU-<n>` story in `docs/jiku-mvp-backlog.md` in full, plus
   Section 1 (Global Engineering Rules)
3. Confirm the story's `Dependencies` are already implemented; if not, stop and report
4. Check whether the type/pattern you are about to create already exists

### Code Style

- Tabs for indentation (match the generated Spring/Kotlin sources)
- Kotlin idioms: `val` over `var`, expression bodies where they read cleanly, data
  classes for DTOs, sealed types for closed hierarchies
- One module per package under `com.jiku`; keep internals `internal` and expose only
  the `*ModuleApi` interface to other modules
- JPA entities live behind their module's repository; never expose an entity across a
  module boundary — map to an API type

### Module Boundaries

- Never import another module's internal package, repository, or entity
- Cross-module needs go through that module's `*ModuleApi` or an application event
- Run `./gradlew test --tests "com.jiku.ModularityTests"` before opening a PR — it
  must pass

### Multi-Tenancy

- All tenant-scoped data is filtered by the Hibernate tenant filter (JIKU-6)
- Never re-implement tenant filtering as a manual `WHERE tenant_id = ?` as the only
  safeguard, and never bypass the filter

### PR & Commit Rules

- Branch naming: `jiku-{number}-{slug}` (e.g. `jiku-13-event-domain-model`)
- Squash merge only into `develop`
- PR template from `docs/jiku-mvp-backlog.md` Section 2
- **Never** include AI authorship traces in any artifact (commit, comment, PR, header)

### When Stuck

- If a story has an `[INTERACTIVE STEP]` block, stop and present options — do not guess
- If a dependency is not yet merged to `develop`, stop and report
- If unsure about a Spring Boot 4 / Spring Modulith 2 API, verify against the actual
  resolved dependency rather than assuming behavior from an older major version

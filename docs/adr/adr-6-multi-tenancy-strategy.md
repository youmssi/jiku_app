# ADR-6: Multi-tenancy strategy — shared schema with a row-level tenant filter

**Status:** Accepted
**Story:** JIKU-6

## Context

Jikū is a white-label, multi-tenant SaaS: many independent event organizers share
one deployment. A query that returns one organizer's guests to another organizer is
a trust-destroying data leak — the single most damaging failure mode for this kind
of product. The isolation mechanism therefore has to be automatic and impossible to
forget, not a convention each query re-implements.

Three common strategies were considered:

1. **Database-per-tenant** — strongest isolation, but heavy operationally
   (provisioning, migrations, and connection management multiplied per tenant) and a
   poor fit for a large number of small tenants on a serverless Postgres (Neon).
2. **Schema-per-tenant** — lighter than separate databases but still multiplies
   migration and connection-routing complexity, and complicates cross-tenant
   platform queries (billing, support).
3. **Shared schema with a `tenant_id` discriminator** — one schema, every
   tenant-scoped table carries `tenant_id`, and reads/writes are filtered by it.
   Simplest to operate; the risk is a forgotten filter, which must be eliminated by
   construction rather than left to developer discipline.

## Decision

Use the **shared-schema** strategy, enforced at the persistence layer with
Hibernate's discriminator multi-tenancy (`@TenantId`), not with manually written
`WHERE tenant_id = ?` clauses.

- `BaseTenantEntity` is a mapped superclass providing a `NOT NULL` `tenant_id`
  column annotated `@TenantId`. Every tenant-scoped entity extends it.
- `TenantContext` (a `ThreadLocal`) holds the current tenant for the request.
- `TenantIdentifierResolver` (a Hibernate `CurrentTenantIdentifierResolver`) reads
  `TenantContext`. Hibernate then appends the tenant predicate to every read and
  stamps `tenant_id` on every insert automatically.
- No active tenant resolves to a sentinel that matches no real tenant, so a read
  without context returns nothing instead of leaking. A `@PrePersist` guard rejects
  a write attempted without an active tenant, so the sentinel never reaches a row.
  The `NOT NULL` column is a second, database-level guard.

The request-time population of `TenantContext` from the authenticated organizer is
wired in JIKU-8 (authentication); until then it is set explicitly (e.g. in tests).

## Consequences

- **Positive:** isolation is automatic for all tenant-scoped entities; repositories
  never restate the filter; one schema keeps migrations and operations simple and
  fits Neon well; platform-wide queries remain possible where deliberately needed.
- **Negative / risks:** all tenants share tables, so the filter's correctness is
  load-bearing — it is covered by an explicit cross-tenant leak test
  (`TenantIsolationTest`) that must always pass. A query deliberately run outside a
  tenant context (future platform/admin features) must opt out explicitly and
  carefully.
- **Revisit if:** a tenant's scale or a contractual/regulatory isolation requirement
  justifies schema- or database-per-tenant for that tenant, which this design does
  not preclude adding later for specific tenants.

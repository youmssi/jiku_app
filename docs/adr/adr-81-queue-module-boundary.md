# ADR-81: Queue management as a separate module, not an extension of ticketing

**Status:** Proposed — not accepted, not authorised for implementation
**Story:** JIKU-81 (unscheduled)
**Reference:** `docs/jiku-queue-management-evaluation.md`

> **Note de renommage (JIKU-100, 2026-09-02).** Le corps de cette décision cite
> `ticketing.internal.Ticket` et `event.internal.Event`. Ces paquets s'appellent
> désormais `ticket` et `catalog`. Le texte n'est pas réécrit — un ADR consigne une
> décision à une date donnée — mais les types visés sont bien
> `ticket.internal.Ticket` et `catalog.internal.Event`.
>
> Le raisonnement, lui, est intact : la contrainte `uq_ticket_guest` existe toujours
> et rend le ticket 1:1 avec un invité nommé.

## Context

Queue and numbered-ticket management (banks, private clinics, administrations) has
been carried in the roadmap as a Horizon 3 item on the stated basis that it is
"technically adjacent — the check-in and ticketing engines already do the work."

A source-level audit on 2026-09-01 established that this is only half true, and the
half that is false is the half that determines the design. Recording the boundary
decision now — before any build authorisation — prevents the cheap-looking wrong
answer from being taken under commercial pressure later.

What the audit found:

- `ticketing.internal.Ticket` carries `UniqueConstraint("uq_ticket_guest",
  ["guest_id"])` with a non-null `guestId`. A Jikū ticket is 1:1 with a known,
  invited, RSVP'd person. A queue ticket is anonymous, walk-in, issued on demand,
  and ordered by a sequence number scoped to a service line and a service date.
- `event.internal.Event` models a one-shot with `startDateTime`/`endDateTime`. A
  queue is a recurring daily resource with opening hours, several service lines and
  N service points.
- The ticket state machine is `ISSUED → CHECKED_IN` (terminal). A queue requires
  `WAITING → CALLED → SERVING → SERVED | NO_SHOW → RECALLED | TRANSFERRED |
  ABANDONED`, including re-queueing.
- Check-in is a low-contention operation over distinct tickets, reconciled
  first-timestamp-wins. Queue dispatch is a contended pop over one ordered set —
  the existing reconciliation strategy is not merely inapplicable, it is wrong.

What genuinely transfers is the platform, not the domain: `BaseTenantEntity`
isolation, `billing` usage metering and allowance gating, the `notification`
module's WhatsApp cost guardrails, the labeled/revocable signed-token access-link
pattern from `checkin.internal.Validator`, organisations and memberships, and
white-label branding.

Three options were considered:

1. **Extend `Event` and `Ticket`** with nullable columns and a discriminator so one
   aggregate serves both products. Cheapest to start; requires making `guestId`
   nullable and dropping `uq_ticket_guest`.
2. **A `queue` module reusing `ticketing` through its module API** for issuance and
   state transitions, with queue-specific ordering layered on top.
3. **A `queue` module owning its own aggregates**, reusing the platform through
   `tenant`, `notification`, `billing` and `admin` module APIs only.

## Decision

Take **option 3**: a new `com.jiku.queue` module owning `ServiceLocation`,
`ServiceLine`, `ServicePoint`, `QueueTicket` and `DailyCounter`. It reuses the
platform through other modules' `*ModuleApi` interfaces and application events, and
shares **no entity, repository or table** with `event` or `ticketing`.

Option 1 is rejected outright. Dropping `uq_ticket_guest` and making `guestId`
nullable removes the invariant that guarantees one ticket per guest — the property
the ceremonies product's integrity rests on, protecting the atomic capacity
transition, the transfer flow and duplicate-scan detection. It would weaken the two
most failure-sensitive aggregates in the product that generates all current revenue
in order to serve an unvalidated hypothesis.

Option 2 is rejected as an unstable middle. Queue dispatch must order, lock and pop
a contended set; `TicketingModuleApi` exposes resolution by code or guest, which is
the wrong access shape. Pushing queue ordering into `ticketing` recreates option 1's
coupling behind an interface that would have to grow queue-specific methods.

Consequences of option 3 that are accepted deliberately:

- The `queue` module restates a small amount of shape — an identifier, a status
  enum, an issued-at timestamp. This duplication is accepted. Global Rule 5
  ("no duplicated logic") is explicitly subordinate to module boundaries, and these
  are different concepts that happen to look alike, not shared logic.
- Dispatch **must** stay in HQL or Criteria so Hibernate's `@TenantId` predicate is
  applied. `@TenantId` does not apply to native SQL: a hand-written
  `FOR UPDATE SKIP LOCKED` native query would dequeue across tenants — a
  cross-tenant leak in the product's hottest path, in a bank-facing context. The
  supported form is a derived repository method with
  `@Lock(PESSIMISTIC_WRITE)` and the query hint
  `jakarta.persistence.lock.timeout = -2` (Hibernate `SKIP_LOCKED`).
- Daily numbering uses the atomic counter pattern already proven in
  `V31__email_quota_counter.sql` (single `INSERT … ON CONFLICT … DO UPDATE …
  RETURNING`), not a Hibernate sequence.
- Live position and board updates use Server-Sent Events with a polling fallback.
  No real-time transport exists today; SSE matches the one-directional data flow and
  requires no new infrastructure. Virtual threads must be enabled so a held
  connection does not pin a platform thread.
- The offline check-in engine does **not** extend to queues. Offline check-in works
  because the roster is known in advance and pre-cacheable; a queue is generated
  live and locally-issued numbers would collide. A queue degrades to a read-only
  local view, never to local issuance.

## Consequences

- **Positive:** the ceremonies aggregates keep every invariant they have today; the
  boundary is machine-enforced by `ModularityTests`, so the coupling cannot be
  reintroduced accidentally; the queue product can be abandoned by deleting one
  package if validation fails; platform reuse (tenancy, metering, notification,
  branding, admin) is preserved in full.
- **Negative / risks:** more code than option 1, and two products share one
  deployment, one database and one operational team — a queue incident consumes
  attention that ceremonies also needs. That risk is organisational and is gated in
  the evaluation document (build gate Q3: a second engineer before any build), not
  mitigated by this decision.
- **Revisit if:** validation fails and the module is removed, or a bank contract
  requires on-premise packaging or data residency, in which case a separate
  deployment topology — not a different module boundary — is the answer.

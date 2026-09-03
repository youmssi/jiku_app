# ADR-69: Gapless invoice numbering, and immutability by credit note

**Status:** Accepted
**Story:** JIKU-69

## Context

Until now the platform issued a plain-text "receipt" whose own source comment
admitted it was "not accounting-grade (no sequential invoice numbers or tax
breakdowns) — a deliberate MVP scope cut, to revisit before any market with
stricter invoicing." A family paying by Mobile Money needs nothing more. A
company, an NGO or a public-sector buyer cannot process it at all, which is why
this is the shared dependency of three separate strategic paths: corporate and
institutional galas, CEMAC expansion (recorded as P3 in the scale plan), and any
queue-management contract (gate Q2 in that evaluation).

Two properties decide whether an invoice is worth anything to an auditor, and
both are easy to get subtly wrong.

**Numbering must be gapless.** A missing number in a sequence is exactly what a
tax audit asks about, and "our software skipped it" is not an answer. The obvious
implementation — a PostgreSQL sequence — is wrong here: a sequence keeps
advancing when the surrounding transaction rolls back, so any failure after the
number is taken leaves a permanent hole.

**An issued document must be immutable.** Once an invoice reaches a buyer's
accounts department, changing it means the two parties hold different documents
bearing the same number.

## Decision

### Numbering

A counter row per `(tenant_id, fiscal_year)` in `invoice_number_counter`,
incremented inside the issuing transaction under `PESSIMISTIC_WRITE`. Concurrent
issuers serialise behind the lock; a rollback returns the number unspent.

Creating the row for a new fiscal year is delegated to `InvoiceNumberAllocator`
in a `REQUIRES_NEW` transaction. This is not ceremony — it works around two
distinct failure modes discovered by the concurrency test:

1. In PostgreSQL a failed statement aborts the entire transaction. Letting a
   losing insert race fail inside the issuing transaction leaves it unusable
   ("current transaction is aborted"), and that transaction still has an invoice
   to write. A separate transaction confines the abort to one we can discard.
2. Catching the unique-violation *inside* the `REQUIRES_NEW` method does not
   help: Spring marks that transaction rollback-only and then fails its commit
   with `UnexpectedRollbackException`. The exception is therefore caught by the
   caller, **outside** the transaction boundary, where the failed transaction has
   already rolled back cleanly.

Both lookups stay in HQL rather than native SQL so Hibernate's `@TenantId`
predicate applies. A native counter lookup would find another tenant's row and
hand out their next number — a cross-tenant leak that would surface as duplicate
numbers on real invoices.

### Immutability

Every field of `Invoice` and `InvoiceLine` is `val`. A correction is a new
`CREDIT_NOTE` carrying the opposite sign and pointing at the original through
`corrected_invoice_id`; the original is never touched, and an invoice can be
credited only once.

Both parties, the tax rate and every total are **snapshotted onto the invoice** at
issue time rather than read live. Changing the organization's address, or
correcting a configured tax rate, must not rewrite a document already sent. This
is also why re-downloading an invoice years later reproduces exactly what the
buyer received.

### Tax treatment

Rates are configuration keyed by ISO 3166-1 alpha-2 country, and **ship empty
with no non-zero default**. The GTM plan records Guinea's rate as unconfirmed and
warns explicitly against planning on an assumed figure for Cameroon. A guessed
default would put a wrong number on a legal document, so an unconfigured country
bills at zero tax — visibly wrong to whoever configures it, rather than quietly
wrong on a customer's invoice.

### Buyer identity

The legal identity lives on the tenant (`TenantLegalIdentity`, edited through
`/legal-identity` and restricted to `ORGANIZER_MANAGER`), and billing reads it
through `TenantModuleApi` rather than touching tenant internals. Only a
*complete* identity crosses the module boundary, so the completeness rule lives
in one place instead of in every consumer. Issuing is refused with a pointer to
Settings when it is missing, rather than emitting a document with blanks where
the buyer should be.

### Document rendering

OpenPDF, chosen because it is the maintained LGPL/MPL fork of iText 4 and needs
no license fee, unlike current iText. The renderer reads only the invoice row.

Money formatting is currency-aware: GNF, XAF and XOF have no minor unit, so the
blanket divide-by-100 that suits EUR would print every Guinean amount a hundred
times too small. That is a defect which would only ever be discovered on a
customer's invoice, so it carries its own test.

## Consequences

- **Positive:** the platform can bill a company. Numbering is proven gapless under
  concurrency and across rollback by `InvoiceNumberingTest`; tenant isolation of
  sequences is asserted explicitly. The single dependency of the CEMAC and B2B
  paths is now met.
- **Negative / risks:** the pessimistic lock serialises issuance per tenant per
  year — irrelevant at this volume, and the correct trade for a property that
  must not fail. No tax rate is configured, so **every invoice issued today shows
  zero tax**: rates must be confirmed with a local accountant and set before real
  invoicing. The seller block likewise defaults to a name that is not a registered
  company.
- **Not covered:** invoice numbering restarts at 1 each fiscal year, which suits
  the jurisdictions in scope but is not universal. Multi-currency invoicing follows
  the platform's single `billing.currency`; per-country pricing is its own work
  (P1 in the scale plan).
- **Revisit if:** a jurisdiction requires continuous numbering across years, a
  buyer requires a specific legal layout or e-invoicing format, or per-country
  pricing lands and invoices must carry more than one currency.

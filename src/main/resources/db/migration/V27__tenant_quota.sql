-- Cumulative per-account free-tier tracking (JIKU-54), replacing the per-event
-- free allowance that a tenant could bypass by fragmenting guests across many
-- small events. One row per tenant. free_invites_used only ever grows within
-- the rolling window (see TenantQuotaService for the roll-forward rule) — it is
-- the running total across every one of the tenant's events, not any single one.
CREATE TABLE tenant_quota (
    id                  UUID         PRIMARY KEY,
    tenant_id           VARCHAR(255) NOT NULL,
    free_invites_limit  BIGINT       NOT NULL,
    free_invites_used   BIGINT       NOT NULL DEFAULT 0,
    window_start        TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_tenant_quota_tenant UNIQUE (tenant_id)
);

CREATE INDEX idx_tenant_quota_tenant_id ON tenant_quota (tenant_id);

-- Backfill from existing usage so no tenant is unexpectedly locked out on
-- release: sum invited guests across events still on the free tier (never
-- raised past the default free limit of 100 — BILLING_FREE_TIER_GUESTS's
-- default, the only value a migration can reasonably assume), capped at that
-- limit.
INSERT INTO tenant_quota (id, tenant_id, free_invites_limit, free_invites_used, window_start, updated_at)
SELECT
    gen_random_uuid(),
    ur.tenant_id,
    100,
    LEAST(SUM(ur.invited_guests), 100),
    now(),
    now()
FROM usage_record ur
WHERE ur.unlocked_allowance <= 100
GROUP BY ur.tenant_id;

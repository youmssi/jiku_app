-- WhatsApp cost/quota/category guardrails (JIKU-61).

-- Per-category pricing (USD minor units / cents), database-backed so it can be
-- updated via /admin/whatsapp/pricing without a redeploy — Meta reprices
-- per market on its own schedule. Placeholder starting values; an operator
-- should confirm current Conakry-market Meta Cloud API rates and update them.
CREATE TABLE whatsapp_pricing (
    id             UUID         PRIMARY KEY,
    category       VARCHAR(32)  NOT NULL,
    cost_usd_minor BIGINT       NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_whatsapp_pricing_category UNIQUE (category)
);

INSERT INTO whatsapp_pricing (id, category, cost_usd_minor, updated_at) VALUES
    ('9e6f6b1a-0b1a-4a1a-8b1a-000000000001', 'UTILITY', 4, now()),
    ('9e6f6b1a-0b1a-4a1a-8b1a-000000000002', 'MARKETING', 32, now());

-- One row per WhatsApp send, recorded after a successful delivery. Backs both
-- the 24h conversation-window counter and per-event cost aggregation.
CREATE TABLE whatsapp_message_cost (
    id             UUID         PRIMARY KEY,
    tenant_id      VARCHAR(255) NOT NULL,
    reference_id   UUID,
    event_id       UUID,
    pool           VARCHAR(16)  NOT NULL,
    category       VARCHAR(32)  NOT NULL,
    cost_usd_minor BIGINT       NOT NULL,
    cost_gnf_minor BIGINT       NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_whatsapp_message_cost_pool_created ON whatsapp_message_cost (pool, created_at);
CREATE INDEX idx_whatsapp_message_cost_event_id ON whatsapp_message_cost (event_id);
CREATE INDEX idx_whatsapp_message_cost_tenant_id ON whatsapp_message_cost (tenant_id);

-- Single-row platform-wide switch letting an admin explicitly allow a
-- MARKETING-classified body to send as UTILITY content anyway (logged in the
-- admin audit log by the controller that flips it).
CREATE TABLE whatsapp_content_override (
    id            UUID         PRIMARY KEY,
    active        BOOLEAN      NOT NULL,
    reason        VARCHAR(500),
    activated_by  VARCHAR(255),
    activated_at  TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ  NOT NULL
);

INSERT INTO whatsapp_content_override (id, active, updated_at)
VALUES ('9e6f6b1a-0b1a-4a1a-8b1a-000000000003', false, now());

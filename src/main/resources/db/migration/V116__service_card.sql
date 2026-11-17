-- Service cards (JIKU-189). A service gets a card shared in WhatsApp groups and
-- statuses. Where the service takes place, shown on the card when given.
ALTER TABLE service ADD COLUMN place VARCHAR(160);

-- Opens of a service's link and bookings made after them, per day and per way
-- in: 'card' for the QR code on the card, 'link' for the shared link. Counts
-- only, kept per day for the 30-day view.
CREATE TABLE service_card_daily (
    tenant_id  VARCHAR(255) NOT NULL,
    service_id UUID         NOT NULL,
    day        DATE         NOT NULL,
    source     VARCHAR(8)   NOT NULL,
    opens      BIGINT       NOT NULL DEFAULT 0,
    bookings   BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (service_id, day, source),
    CONSTRAINT chk_service_card_daily_counts CHECK (opens >= 0 AND bookings >= 0)
);

CREATE INDEX idx_service_card_daily_tenant ON service_card_daily (tenant_id, service_id, day);

-- Who already opened a link today, so an open counts once per visitor and per
-- day. visitor is a hash salted with a secret that changes every day; rows are
-- deleted once their day is over, so nothing ties two days together.
CREATE TABLE service_card_visitor (
    service_id UUID       NOT NULL,
    day        DATE       NOT NULL,
    source     VARCHAR(8) NOT NULL,
    visitor    VARCHAR(64) NOT NULL,
    PRIMARY KEY (service_id, day, source, visitor)
);

-- A manual payment request now ends as CANCELLED (by the organizer, or replaced
-- by a new request or an online payment) or EXPIRED (no transfer after the
-- configured delay), besides SUCCEEDED and FAILED (JIKU-307). The status column
-- is a free VARCHAR, so the new values need no constraint change.
ALTER TABLE payment ADD COLUMN rejection_reason VARCHAR(500);
ALTER TABLE payment ADD COLUMN transfer_declared_at TIMESTAMPTZ;

CREATE INDEX idx_payment_manual_pending_created
    ON payment (created_at)
    WHERE provider = 'manual' AND status = 'PENDING';

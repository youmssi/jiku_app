-- JIKU-303: Solo became free with the per-guest pricing (V98, ADR 109), but
-- Solo subscriptions opened before it kept their end date and went on to grace
-- and a services pause. A Solo team of at most one person owes nothing: it runs
-- with no end date.
UPDATE subscription
SET status = 'ACTIVE',
    expires_at = NULL,
    expiry_notice_sent = FALSE,
    grace_notice_sent = FALSE,
    updated_at = now()
WHERE plan = 'Solo'
  AND resources_active <= 1
  AND (status <> 'ACTIVE' OR expires_at IS NOT NULL);

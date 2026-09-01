-- Shared rate-limit counters (JIKU-79). The in-memory limiter is correct for a
-- single API instance and silently multiplies every budget by the replica count
-- the moment a second one runs, because each keeps its own map. This table backs
-- the shared-store limiter so one budget is enforced across every instance.
--
-- Deliberately NOT tenant-scoped: every policy protects an endpoint reachable
-- without an organizer login, so no tenant context exists when the counter is
-- touched. bucket_key already carries the policy name, the client IP and, for
-- link flows, the token segment.
--
-- The row is written only by RateLimitCounterRepository.tryAcquire's conditional
-- upsert, which both rolls the window over and enforces the budget in one
-- statement, so two concurrent instances can never both be allowed past it.
-- Expired rows are removed by RateLimitCounterSweepJob.
CREATE TABLE rate_limit_counter (
    bucket_key    VARCHAR(255) PRIMARY KEY,
    window_start  TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    request_count INTEGER      NOT NULL
);

CREATE INDEX idx_rate_limit_counter_expires_at ON rate_limit_counter (expires_at);

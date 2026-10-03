-- JIKU-215: when an invitation was last handed to the sender. Sending runs in
-- the background; after a restart, an invitation still PENDING long after it
-- was handed over was lost with the process and is handed over again.
ALTER TABLE invitation ADD COLUMN dispatched_at TIMESTAMPTZ;
CREATE INDEX idx_invitation_pending ON invitation (status, dispatched_at) WHERE status IN ('PENDING', 'QUEUED');

-- Scheduled jobs take a lock here before running, so with several API
-- instances each job still runs once (ShedLock).
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

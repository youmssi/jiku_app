-- Second sign-in factor (JIKU-317): one authentication-app secret per account,
-- encrypted by the application, and its single-use backup codes (hashed).
-- subject_kind is ORGANIZER or ADMIN; subject_id is the account's id.
CREATE TABLE mfa_enrollment (
    id           UUID PRIMARY KEY,
    subject_kind VARCHAR(16)  NOT NULL,
    subject_id   UUID         NOT NULL,
    secret       TEXT         NOT NULL,
    confirmed_at TIMESTAMPTZ,
    last_step    BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_mfa_enrollment_subject ON mfa_enrollment (subject_kind, subject_id);

CREATE TABLE mfa_backup_code (
    id            UUID PRIMARY KEY,
    enrollment_id UUID        NOT NULL REFERENCES mfa_enrollment (id) ON DELETE CASCADE,
    code_hash     VARCHAR(64) NOT NULL,
    used_at       TIMESTAMPTZ
);

CREATE INDEX idx_mfa_backup_code_enrollment ON mfa_backup_code (enrollment_id);

-- Signed-in sessions of organizer accounts (JIKU-317), listed in Settings ->
-- Security and closed one by one or all at once. device_id is the browser's
-- own random identifier; device is what the browser says it is.
CREATE TABLE organizer_session (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL,
    device_id    VARCHAR(64),
    device       VARCHAR(200),
    created_at   TIMESTAMPTZ  NOT NULL,
    last_used_at TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ
);

CREATE INDEX idx_organizer_session_user ON organizer_session (user_id, revoked_at);

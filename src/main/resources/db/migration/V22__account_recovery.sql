-- Password reset and email verification (JIKU-49). Accounts created before this
-- migration are grandfathered as verified — they predate verification and gating
-- them retroactively would lock working customers out of organization creation.
ALTER TABLE organizer_user ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE organizer_user SET email_verified = TRUE;
ALTER TABLE organizer_user ADD COLUMN password_changed_at TIMESTAMPTZ;

-- Single-use, time-boxed tokens mailed to the account owner. Only the SHA-256
-- hash is stored; the raw token exists solely in the emailed link.
CREATE TABLE account_token (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES organizer_user (id) ON DELETE CASCADE,
    purpose    VARCHAR(64)  NOT NULL,
    token_hash VARCHAR(64)  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_account_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_account_token_user_purpose ON account_token (user_id, purpose);

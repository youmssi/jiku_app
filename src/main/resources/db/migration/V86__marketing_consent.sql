-- JIKU-201: consent to Jikū's news and tips, with its proof (when, which
-- wording, where). Absent means no consent.
ALTER TABLE organizer_user ADD COLUMN marketing_consent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organizer_user ADD COLUMN marketing_consent_at TIMESTAMPTZ;
ALTER TABLE organizer_user ADD COLUMN marketing_consent_version VARCHAR(32);
ALTER TABLE organizer_user ADD COLUMN marketing_consent_source VARCHAR(16);

ALTER TABLE prospect_lead ADD COLUMN marketing_consent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE prospect_lead ADD COLUMN marketing_consent_at TIMESTAMPTZ;
ALTER TABLE prospect_lead ADD COLUMN marketing_consent_version VARCHAR(32);
ALTER TABLE prospect_lead ADD COLUMN marketing_consent_source VARCHAR(16);

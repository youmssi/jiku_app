-- JIKU-209: what Meta last said about a WhatsApp template (status, quality,
-- category), per WhatsApp Business Account. Not tenant-scoped: Meta's webhook
-- carries no tenant, and the platform account serves every organization.
-- blocked_until set means sends with this template wait (paused, disabled,
-- or moved to marketing); NULL means usable.
CREATE TABLE whatsapp_template_state (
    waba_id       VARCHAR(64)  NOT NULL,
    name          VARCHAR(512) NOT NULL,
    language      VARCHAR(16)  NOT NULL,
    status        VARCHAR(32),
    quality       VARCHAR(16),
    category      VARCHAR(32),
    reason        VARCHAR(500),
    blocked_until TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (waba_id, name, language)
);

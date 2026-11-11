-- JIKU-284 : le message vocal de l'hôte sur sa carte (décision D1 : gratuit,
-- 30 secondes au plus). Rangé à côté de l'invitation ouverte, comme la photo de
-- bandeau, et servi seulement quand l'invité appuie sur Lecture.
CREATE TABLE open_card_voice (
    event_id         UUID         PRIMARY KEY,
    tenant_id        VARCHAR(255) NOT NULL,
    content          BYTEA        NOT NULL,
    content_type     VARCHAR(32)  NOT NULL,
    duration_seconds INT          NOT NULL,
    transcript       VARCHAR(500),
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_open_card_voice_duration CHECK (duration_seconds BETWEEN 1 AND 60)
);

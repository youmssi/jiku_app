-- Remboursements opérationnels (JIKU-75).
--
-- Enregistre le remboursement réellement exécuté (par Mobile Money, hors
-- plateforme) contre la déclaration d'acompte d'origine. Une ligne par
-- opération : un remboursement partiel produit une ligne partielle ; la somme
-- des lignes d'une même déclaration ne dépasse jamais son montant (contrôle en
-- service). credit_note_id / credit_note_number relient l'avoir (CREDIT_NOTE,
-- JIKU-69) émis au moment du remboursement.
CREATE TABLE booking_refund (
    id                  UUID         PRIMARY KEY,
    booking_id          UUID         NOT NULL,
    declaration_id      UUID         NOT NULL,
    amount_minor        BIGINT       NOT NULL,
    currency            VARCHAR(8)   NOT NULL,
    reason              VARCHAR(500) NOT NULL,
    credit_note_id      UUID,
    credit_note_number  VARCHAR(64),
    executed_at         TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_booking_refund_booking FOREIGN KEY (booking_id) REFERENCES booking (id) ON DELETE CASCADE,
    CONSTRAINT fk_booking_refund_declaration FOREIGN KEY (declaration_id) REFERENCES booking_payment_declaration (id) ON DELETE RESTRICT,
    CONSTRAINT ck_booking_refund_positive CHECK (amount_minor > 0)
);

CREATE INDEX idx_booking_refund_booking_id ON booking_refund (booking_id);
CREATE INDEX idx_booking_refund_declaration_id ON booking_refund (declaration_id);

-- Service cards in slot mode (JIKU-190): a card and its link can lead straight to
-- the first day with a free time instead of the service's presentation.
ALTER TABLE service ADD COLUMN card_mode VARCHAR(16) NOT NULL DEFAULT 'PRESENTATION';
ALTER TABLE service ADD CONSTRAINT chk_service_card_mode CHECK (card_mode IN ('PRESENTATION', 'SLOT'));

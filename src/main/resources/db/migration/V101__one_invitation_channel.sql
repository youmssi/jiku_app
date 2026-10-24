-- JIKU-273 (ADR 110, décision 4) : un invité ne reçoit qu'une invitation.
-- Le canal prioritaire de l'événement (nul : WhatsApp s'il est activé, sinon
-- l'e-mail) et le secours sur l'autre canal quand le premier est impossible.
ALTER TABLE event ADD COLUMN primary_invitation_channel VARCHAR(16);
ALTER TABLE event ADD COLUMN invitation_channel_fallback BOOLEAN NOT NULL DEFAULT TRUE;

-- Le canal choisi pour un invité à l'import (colonne « Canal ») ; nul suit la règle de l'événement.
ALTER TABLE guest ADD COLUMN preferred_channel VARCHAR(16);

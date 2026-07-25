-- Lets an organizer keep a guest on the roster while excluding them from future
-- invitation sends, without anonymizing or removing their data.
ALTER TABLE guest
    ADD COLUMN excluded_from_invitations BOOLEAN NOT NULL DEFAULT FALSE;

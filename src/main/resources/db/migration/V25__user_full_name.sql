-- The person's display name (JIKU-54). Nullable: accounts created before this
-- migration, and Google accounts whose token carried no name, have none — the
-- UI falls back to the email address.
ALTER TABLE organizer_user
    ADD COLUMN full_name VARCHAR(255);

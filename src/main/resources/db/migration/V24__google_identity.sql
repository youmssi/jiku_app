-- Google sign-in (JIKU-51): accounts created through Google have no password.
ALTER TABLE organizer_user ALTER COLUMN password_hash DROP NOT NULL;

-- Country and currency of each organization (JIKU-107). Both are fixed at
-- creation: the currency follows the country and every price the organization
-- sets or pays is in it. Organizations created so far are all in Guinea.
ALTER TABLE tenant ADD COLUMN country VARCHAR(2) NOT NULL DEFAULT 'GN';
ALTER TABLE tenant ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'GNF';
ALTER TABLE tenant ALTER COLUMN country DROP DEFAULT;
ALTER TABLE tenant ALTER COLUMN currency DROP DEFAULT;

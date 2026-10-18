-- JIKU-268: events buy their guests one by one instead of a tier, every
-- delivery mode included, and the services plans become Solo (free) and
-- Services Pro (per person). No organization has paid yet, so nothing is
-- carried over beyond what an event already bought.

-- An event's entitlement is now the guests it bought; zero while it runs on
-- the organization's free guests. An event unlocked to a tier keeps that tier's
-- guests as bought.
ALTER TABLE usage_record ADD COLUMN paid_guests BIGINT NOT NULL DEFAULT 0;
UPDATE usage_record SET paid_guests = unlocked_allowance WHERE unlocked_allowance > 100;
ALTER TABLE usage_record DROP COLUMN unlocked_allowance;
ALTER TABLE usage_record DROP COLUMN interactive_allowance;

-- The interactive WhatsApp mode is included in every price.
ALTER TABLE payment DROP COLUMN interactive;

-- A trial now lends a number of guests, not a tier.
ALTER TABLE trial_grant DROP COLUMN tier;

-- The admin desk edits one per-guest price instead of a tier grid; the old
-- grid and plan overrides fall back to the new configured defaults.
ALTER TABLE platform_billing_settings DROP COLUMN tier_prices_json;
ALTER TABLE platform_billing_settings ADD COLUMN per_guest_price_json TEXT;
UPDATE platform_billing_settings SET subscription_plans_json = NULL;

-- Solo Plus, Teams and Organisation are replaced by Services Pro.
UPDATE subscription SET plan = 'Services Pro', resource_limit = NULL
WHERE plan IN ('Solo Plus', 'Teams', 'Organisation');

-- An event payment buys guests; its kind and label say so.
UPDATE payment SET kind = 'GUESTS', tier = 'GUESTS' WHERE kind = 'TIER';

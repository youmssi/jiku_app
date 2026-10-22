-- JIKU-270: the dollar prices follow the same ratio as the franc prices, so
-- Pro Organisateur pays off from 800 guests a month in every currency
-- ($72 a month, $0.09 a guest). A per-guest price an administrator saved at
-- the old 8 cents moves to 9; any other saved figure is left as set.
UPDATE platform_billing_settings
SET per_guest_price_json = regexp_replace(per_guest_price_json, '"usdCents"\s*:\s*8(?![0-9])', '"usdCents":9')
WHERE per_guest_price_json IS NOT NULL;

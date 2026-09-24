-- The counter a waiting client is called to (JIKU-113): "go to counter 4". Set
-- when the operator calls them; null until then, and for event tickets.
ALTER TABLE ticket ADD COLUMN counter_label VARCHAR(40);

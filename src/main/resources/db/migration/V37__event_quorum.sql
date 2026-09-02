-- Quorum d'assemblée générale (JIKU-94).
--
-- Une AG est nulle sans quorum, et le quorum se compte aujourd'hui à la main, sur
-- une feuille, dans la contestation. C'est un besoin JURIDIQUE, pas un confort :
-- l'atteinte du quorum doit être horodatée et opposable un mois plus tard.
--
-- Le quorum est une règle statutaire propre à chaque organisation : il est saisi
-- par l'organisateur, jamais deviné. Un événement sans quorum configuré se
-- comporte exactement comme avant.
ALTER TABLE event
    -- NONE | FRACTION | ABSOLUTE
    ADD COLUMN quorum_mode        VARCHAR(16),
    -- Fraction statutaire : 1/2, 2/3, 3/4…
    ADD COLUMN quorum_numerator   INTEGER,
    ADD COLUMN quorum_denominator INTEGER,
    -- Ou un nombre absolu de présents requis.
    ADD COLUMN quorum_absolute    INTEGER,
    -- Écrit UNE SEULE FOIS, jamais recalculé ni réécrit : c'est la valeur
    -- probante. Si des participants repartent et que le quorum retombe sous le
    -- seuil, cette date reste — les deux informations sont vraies et l'une ne
    -- remplace pas l'autre.
    ADD COLUMN quorum_reached_at  TIMESTAMPTZ;

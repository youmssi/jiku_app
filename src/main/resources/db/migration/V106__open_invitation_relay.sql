-- JIKU-285 : un invité qui a répondu « Je viens » peut inviter un proche depuis
-- la carte. Décision D2 : activé d'office quand la jauge est illimitée,
-- désactivé quand l'hôte en a fixé une ; l'hôte peut changer ce choix.
ALTER TABLE open_invitation ADD COLUMN relay BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE open_invitation oi SET relay = FALSE
FROM event e WHERE e.id = oi.event_id AND e.max_capacity IS NOT NULL;

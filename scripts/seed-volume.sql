-- Jeu synthétique pour la répétition de restauration (JIKU-80).
--
-- Un RTO mesuré sur une base de développement quasi vide prouve que la procédure
-- fonctionne, pas qu'elle est rapide. Ce script gonfle une copie jetable au
-- volume d'une année d'exploitation avant que le chronomètre ne démarre.
--
-- :rows est le nombre d'invités à créer ; billets et invitations en découlent
-- dans les proportions observées (un billet par confirmation, une invitation par
-- invité et par canal actif).
--
-- N'est jamais exécuté ailleurs que dans la base de répétition.

INSERT INTO guest (id, tenant_id, event_id, first_name, last_name, email, phone_number, created_at, rsvp_status)
SELECT gen_random_uuid(),
       e.tenant_id,
       e.id,
       'Prenom' || i,
       'Nom' || i,
       'invite' || i || '@exemple.test',
       '+2246' || lpad((i % 10000000)::text, 7, '0'),
       now() - ((i % 365) || ' days')::interval,
       (ARRAY['PENDING', 'CONFIRMED', 'DECLINED'])[1 + i % 3]
FROM generate_series(1, :rows) AS i
CROSS JOIN LATERAL (
    SELECT id, tenant_id
    FROM event
    ORDER BY id
    OFFSET (i % GREATEST((SELECT count(*) FROM event), 1))
    LIMIT 1
) AS e;

-- Un billet par invité confirmé, comme en exploitation.
INSERT INTO ticket (id, tenant_id, event_id, guest_id, ticket_code, status, issued_at)
SELECT gen_random_uuid(),
       g.tenant_id,
       g.event_id,
       g.id,
       upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12)),
       'ISSUED',
       g.created_at
FROM guest g
WHERE g.rsvp_status = 'CONFIRMED'
  AND NOT EXISTS (SELECT 1 FROM ticket t WHERE t.guest_id = g.id);

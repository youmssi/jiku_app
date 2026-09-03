# ADR-85 — Grille fixe et stratégie de concurrence du moteur de créneaux

**Statut :** accepté (JIKU-85) · **Date :** 2026-09-03

## Contexte

Le produit rendez-vous réserve des ressources (personne, lieu, équipement) pour
des créneaux. Deux difficultés dominent : calculer la disponibilité quand un
créneau exige plusieurs types de ressources en quantité, et garantir qu'un même
créneau sur une même ressource ne soit jamais vendu deux fois. La conception
(dossier rendez-vous §3) interdit le SQL natif pour la réservation, afin que le
prédicat `@TenantId` de Hibernate s'applique.

## Décision

1. **Grille fixe.** Les créneaux candidats commencent à un multiple du pas à
   partir de minuit local (fuseau du service) ; un créneau occupe
   `durée + tampon`. La disponibilité devient un **comptage** : une case est
   réservable si chaque exigence du service (type, quantité) trouve assez de
   ressources libres.

2. **Une ressource est libre** si elle est active, couverte par un horaire
   hebdomadaire dans *son propre* fuseau, sans indisponibilité chevauchante, et
   sans réservation concurrente qui l'occupe (confirmation, ou demande en attente
   non expirée).

3. **Réservation atomique par INSERT conditionnel unique, en HQL.** L'unicité
   `(resource_id, starts_at)` de `service_reservation` est la garde de concurrence :
   l'écriture est une seule insertion (jamais un read-then-write, jamais de SQL
   natif) ; le perdant reçoit une violation d'unicité, sa transaction se replie,
   et le moteur traduit en `SlotUnavailableException`. Une exigence en quantité N
   affecte les N premières ressources libres dans un ordre stable (nom), chaque
   affectation étant elle-même l'INSERT gardé.

4. **Demande en attente.** En mode « sur demande », la réservation naît `PENDING`
   avec `held_until = maintenant + hold` ; elle occupe la case jusqu'à expiration.
   La purge (`releaseExpiredHolds`) supprime les demandes expirées, libérant la
   case sans toucher aux confirmations.

## Limites assumées

- L'affectation « premières ressources libres dans l'ordre stable » peut, sous
  concurrence avec plusieurs ressources libres, faire échouer un client alors
  qu'une ressource reste libre : deux clients visent la même première ressource
  et un seul la prend. Acceptable au premier cran ; un mécanisme de retry propre
  (ou d'allocation par compteur) est le prochain pas si la mesure le justifie.
- L'atomicité multi-ressources repose sur le repli transactionnel de l'ensemble
  si une affectation échoue : correct, pas optimisé.

## Alternatives rejetées

- **SQL natif** : contournerait le filtre tenant (interdit, JIKU-85).
- **Verrouillage applicatif / table de verrous** : plus de surface, pour un gain
  nul tant que la garde d'unicité suffit.
- **Algorithme d'optimisation d'affectation** : surdimensionné pour un comptage
  sur grille fixe ; une exigence nommée (« cette personne précise ») est un cas
  d'usage futur, pas un cas particulier de ce moteur.

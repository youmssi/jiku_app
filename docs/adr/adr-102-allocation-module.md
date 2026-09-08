# ADR 102 — Module `allocation` : SeatAllocator et AtomicCounter

**Statut :** proposé — décision d'implémentation pour la sous-étape B5 « module allocation ».
**Amont :** `docs/jiku-target-architecture.md` §2.3/§6.2 (D1, V4) et `docs/jiku-backend-hardening-backlog.md` (B5).

## Contexte

Décider, sous concurrence, qui obtient une ressource finie est aujourd'hui résolu
**à plusieurs endroits avec des techniques différentes** :
- capacité d'événement (`Event.confirmedCount`, UPDATE conditionnel) et de catégorie
  (`TicketType.confirmedCount`) — **copies quasi identiques** ;
- compteurs séquentiels (numérotation facture, rang du jour, claims de rappels) —
  même patron « création REQUIRES_NEW + incrément sous verrou pessimiste » ;
- créneaux de rendez-vous (INSERT + unicité `(resource_id, starts_at)`).

Chaque nouvelle rareté (liste d'attente, file…) réimplémenterait un cinquième patron.
La cible est un module `allocation`, arbitre unique de la rareté, qui ne connaît
**aucun domaine**.

## Décision

Créer le module Spring Modulith `com.jiku.allocation` exposant **deux briques** via
son `AllocationModuleApi` :

1. **`SeatAllocator`** — « prend une place dans une jauge finie » : `tryTake(limit)`
   sémantiquement un `UPDATE … SET count = count + 1 WHERE count < limit` rendu
   atomique. Le module n'écrit dans aucune table domaine : il travaille sur un
   **port** (`CapacityLedger`) que le module propriétaire (catalog, puis money)
   implémente avec son propre repository. Le prédicat `@TenantId` reste appliqué
   puisque l'écriture vit chez l'appelant.
2. **`AtomicCounter`** — « prend le prochain entier sans trou » : création de ligne
   en `REQUIRES_NEW` + incrément sous `PESSIMISTIC_WRITE`, derrière un port
   (`CounterStore`) implémenté par le module qui possède la table (money pour la
   facture, ticket pour le rang du jour).

### Règles du module

- `allocation` ne dépend d'aucun module métier (interfaces/ports uniquement) ;
  `ModularityTests` verrouille cette frontière (aucun import `com.jiku.{catalog,ticket,money,…}`).
- Aucune requête native sur table tenant-scopée ; les écritures passent par des
  méthodes HQL/derived du repository propriétaire (le `@TenantId` s'applique).
- Les transitions restent **conditionnelles** ; le module documente les préconditions
  (`count < limit`, `count > 0`, verrou) sans réécrire la concurrence côté domaine.

## Migration (ordre imposé par le doc cible §6.2)

1. **Écrire à côté, sans consommateur** : `SeatAllocator` + port, `AtomicCounter` + port,
   avec une **suite d'équivalence comportementale** (mêmes séquences concurrentes
   que les implémentations actuelles, résultats identiques). Le module n'a que des
   tests comme appelants à ce stade (toléré, c'est la phase prévue).
2. **Bascule capacité** : remplacer l'UPDATE conditionnel d'`Event` puis de
   `TicketType` par l'allocateur derrière leur repository. Les tests de concurrence
   existants (`EventCapacityConcurrencyTest`, `TicketTypeCapacityTest`) doivent
   passer **inchangés** ; s'il faut les modifier, la bascule est fausse.
3. **Bascule compteurs** : facture puis rang du jour derrière `AtomicCounter`
   (chaque module fournit son `CounterStore`). `InvoiceNumberingTest` et les tests
   de rangs concurrents passent inchangés.
4. **Suppression** des implémentations dupliquées et de leurs helpers devenus sans
   appelant, dans la même passe que chaque bascule (jamais après coup).

## Critères de « fait »

- [ ] `ModularityTests` passe avec le nouveau module (aucune dépendance domaine).
- [ ] Suite d'équivalence : N réservations concurrentes sur 1 place → exactement 1
      succès ; N émetteurs concurrents → N numéros sans doublon ni trou.
- [ ] Tests d'isolation inter-tenant pour chaque port (une écriture A ne voit
      jamais/ne consomme jamais la jauge de B).
- [ ] Les tests de concurrence existants de capacité et de numérotation passent
      inchangés après chaque bascule.
- [ ] Aucune requête native ajoutée sur table tenant-scopée ; aucun code dupliqué
      restant derrière le module.

## Non-objectifs

- Pas de nouvelle table : les jauges/compteurs restent là où ils vivent (les ports
  les atteignent par le repository du propriétaire).
- Pas de réécriture du moteur de créneaux (SlotEngine) dans cette étape.
- Pas de « allocation sans appelant » livrée hors de la phase d'équivalence prévue.

## Conséquences

- Coût : la bascule de capacité est la dislocation la plus délicate du codebase
  (doc cible §6.2) ; elle exige des boucles complètes de tests et sera menée en
  session dédiée, pas en patch d'opportunité.
- Gain : toute rareté future (waitlist, rang, créneau) s'exprime via le module au
  lieu d'une cinquième copie.

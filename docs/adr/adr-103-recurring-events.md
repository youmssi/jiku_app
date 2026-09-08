# ADR 103 — Événements récurrents / multi-dates : facturation et modèle

**Statut :** accepté — décision produit prise (option a).
**Amont :** `docs/jiku-vs-hi-events-benchmark.md` (JIKU-74) et session de décision.

## Décision

**1 événement = 1 facturation ; les occurrences partagent la jauge d'invités.**
Un événement « récurrent » est une définition racine (nom, description, lieu,
canaux, catégories, quorum éventuel) portant **plusieurs occurrences** (chaque date
= une occurrence avec son propre créneau, sa propre capacité « salle » et ses
propres statistiques de porte).

### Règles adoptées

1. **Facturation unique au niveau racine** : une seule ligne d'usage/`UsageRecord`
   pour l'événement. Le coût (palier payé ou budget free-tier cumulé 365 j) mesure
   l'**union des invités distincts invités à travers toutes les occurrences** — une
   même personne invitée à 3 dates n'est comptée qu'une fois.
2. **Capacité par occurrence** (jauge physique, ex. la salle un jour donné) distincte
   de la jauge commerciale de l'union : un organisateur peut limiter chaque date à
   80 places tout en invitant 250 personnes distinctes sur la série.
3. **Invitations scopées par occurrence** (on envoie « cette date » ou « toute la
   série »), dédupliquées au niveau racine pour le comptage commercial.
4. **Le jour J reste par occurrence** : check-in, tableau de bord et statistiques
   d'une occurrence sont indépendants (rien ne change pour le portier).
5. **Verrouillage produit inchangé** : tant que l'événement est en brouillon, on
   ajoute/modifie des occurrences ; dès publication, la définition et les
   occurrences sont figées (seule l'annulation globale reste possible, invalidant
   tous les passes).

## Modèle de données (esquisse, à affiner en slice)

- `event_occurrence(id, event_id, starts_at, ends_at, capacity, …)` : ligne enfant
  de l'événement racine ; tenant-scopée comme tout le reste.
- Les tickets/RSVP/check-in existants restent indexés sur l'occurrence (via une
  colonne `occurrence_id` ou une date résolue) ; la numérotation des rangs, la
  capacité atomique et le scanner ne changent pas de mécanique.
- `Event.confirmedCount` reste la jauge commerciale (union) ; la capacité physique
  par date est une seconde contrainte vérifiée au moment du RSVP pour cette date.

## Tranches d'implémentation

1. Schéma + entité occurrence + CRUD racine (brouillon) avec migration.
2. Résolution capacité par occurrence dans le RSVP (garde atomique par
   `(occurrence, catégorie)`), jauge d'union inchangée.
3. Invitations par occurrence (envoi « cette date » / « toute la série ») et
   déduplication de comptage.
4. Web : création d'occurrences, liste des dates, dashboards par date.

## Non-objectifs

- Pas de nouvelles règles de quorum ou de catégories propres à une occurrence dans
  cette passe (elles restent à la racine).
- Pas de surcoût par date, pas de « billetterie par séance ».
- Pas de rappels automatiques par date (invitation à la date, relance manuelle
  inchangée).

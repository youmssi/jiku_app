# ADR 109 — Tarification simplifiée : un prix par invité, deux offres Pro, prix TTC

**Statut :** accepté.
**Date :** 2026-10-18, complété le 2026-10-19 (JIKU-270)
**Remplace :** la grille de l'ADR 105 (paliers Bronze, Argent, Or, palier sur
mesure, supplément interactif, formules Solo Plus, Teams et Organisation).
L'ADR 105 reste valable pour les trois monnaies, le numéro WhatsApp propre et
le paiement à l'année.
**Amont :** ADR 105, ADR 108, `docs/sales/base-de-connaissances.md`.

## Contexte

La grille de l'ADR 105 demandait au visiteur de comprendre trois paliers, un
palier sur mesure, un supplément pour la réponse dans WhatsApp et quatre
formules de services. Le simulateur posait plusieurs questions avant d'afficher
un prix. Les organisateurs visés (wedding planners, agences, salles) veulent un
prix qu'ils peuvent recopier dans leur propre devis.

Les prix étaient aussi affichés hors taxes alors que nos clients paient en
Mobile Money un montant final, et que la TVA est de 18 % en Guinée, en Côte
d'Ivoire et au Sénégal. CinetPay ne calcule pas la taxe.

Aucune organisation n'a encore payé : il n'y a pas d'ancienne grille à
honorer.

## Décision 1 — Cinq offres

| Offre | GNF | FCFA | USD |
|---|---|---|---|
| Gratuit : 100 invités par an, et Solo (1 personne, 50 rappels WhatsApp par mois) | 0 | 0 | 0 |
| Par événement, par invité au-delà des invités gratuits, tout inclus | 750 | 50 | 0,09 |
| Pro Organisateur, par mois : 1 000 invités, puis par invité | 600 000, puis 600 | 40 000, puis 40 | 72, puis 0,07 |
| Services Pro, par personne et par mois : groupes jusqu'à 30, 1 000 rappels WhatsApp par personne (usage raisonnable) | 60 000 | 4 000 | 7 |
| Entreprise | sur devis | | |

- Un événement achète un nombre d'invités ; en ajouter ne fait payer que les
  nouveaux. La réponse dans WhatsApp est incluse partout.
- Pro Organisateur devient moins cher que « Par événement » à partir de 800
  invités par mois : c'est la seule question du calculateur.
- Le paiement à l'année (10 mois payés) s'applique à Pro Organisateur et à
  Services Pro.
- La vente de billets reste à 3 % par billet, la première tranche de 50 billets
  offerte.
- Le numéro WhatsApp propre est inclus dans Pro Organisateur et Services Pro,
  et reste une option mensuelle pour les autres.

## Décision 2 — Prix toutes taxes comprises

Le prix affiché est le prix payé. La facture extrait la TVA du total
(`total / 1,18`) au lieu de l'ajouter. Le taux de chaque pays est une
configuration (`billing.invoice.tax`), 18 % pour GN, CI et SN.

## Décision 3 — Limite du numéro Jikū

Une organisation vérifiée envoie jusqu'à **3 000 messages sur 30 jours** sur le
numéro Jikū (au lieu de 500) ; une organisation non vérifiée reste à 50 par
jour. Au-delà, elle branche son propre numéro. Ce n'est pas imposé plus tôt :
un client d'un Tech Provider doit mettre sa propre carte sur son compte
WhatsApp Business, ce que beaucoup de cartes prépayées refusent.

## Décision 4 — Vérification des messages par règles

Le contrôle qui classe un message WhatsApp « marketing » reste une liste de
mots configurable, appliquée aussi pendant la rédaction pour prévenir
l'organisateur. Pas de modèle d'IA : le résultat doit être le même à la
rédaction et à l'envoi, sans coût par message.

## Conséquences

- `billing.tiers`, `billing.beyond-per-guest`, `billing.interactive-per-guest`
  et la colonne `usage_record.interactive_allowance` disparaissent ;
  `usage_record.paid_guests` remplace `unlocked_allowance`.
- Les endpoints `/billing/tiers` et `/events/{id}/billing/quotes` sont remplacés
  par `/billing/event-pricing` et `/events/{id}/billing/quote?guests=`. Un achat
  d'invités envoie `{"guests": n}`. Le web, seul client, change dans la même
  livraison.
- Les conditions générales mentionnent l'usage raisonnable des rappels de
  Services Pro et les prix TTC.

## Décision 5 — Le même seuil dans les trois monnaies (JIKU-270)

À 70 USD et 0,08 USD par invité, Pro Organisateur devenait rentable à 875
invités en dollars contre 800 en francs. Le dollar suit maintenant le même
rapport : **72 USD par mois et 0,09 USD par invité**, soit 800 invités partout.
Les deux prix montent légèrement au lieu de baisser le pack : aucune marge
n'est perdue, et ils restent proches des prix en FCFA (40 000 FCFA ≈ 71 USD,
50 FCFA ≈ 0,09 USD).

## Décision 6 — Seuls les invités au-delà des invités gratuits sont payés

À son premier achat, un événement est couvert par les invités gratuits que
l'organisation peut encore utiliser : un événement de 120 invités qui n'a pas
entamé les 100 invités gratuits paie 20 invités, pas 120. Ces invités gratuits
sont alors réservés à cet événement, pour qu'un autre ne les utilise pas aussi.
Le premier paiement reste petit, ce qui facilite la première conversion ; le
manque à gagner est au plus 100 invités par organisation et par an.

## Décision 7 — Une marge le jour de l'événement

Dans les 24 heures autour du début d'un événement payé par invité ou gratuit,
l'envoi continue jusqu'à 20 % au-delà de ses invités, au moins 20 invités
(`billing.event-day-grace`). Les invités en plus restent comptés et apparaissent
comme à payer sur l'écran de facturation. Pro Organisateur n'a pas de limite ce
jour-là (ADR 105). La validation à l'entrée n'est jamais bloquée.

## Décision 8 — L'usage raisonnable de Services Pro est appliqué

Une offre payante de services envoie au plus 1 000 rappels WhatsApp par personne
et par mois depuis le numéro Jikū (`billing.subscription.paid-plan-whatsapp-reminders-per-person`).
Au-delà, les rappels passent par les autres canaux jusqu'au mois suivant. Les
rappels envoyés depuis le numéro propre de l'organisation ne comptent pas.

## Décision 9 — Factures en français et en anglais

Chaque libellé de la facture est écrit en français puis en anglais. Le français
est la langue officielle des pays où Jikū facture ; l'anglais sert un client
ailleurs, sans produire un document par langue.

## Décision 10 — Mots promotionnels comparés par mots entiers

Meta ne publie pas de liste de mots interdits : il classe un modèle selon son
contenu. La liste suit ses règles (offres, remises, coupons, « achetez
maintenant », urgence d'achat) et n'utilise que des expressions là où un mot
seul est courant dans une invitation (« entrée gratuite », une liste de
cadeaux, un solde à régler, la remise des diplômes, une promotion d'étudiants).
Chaque entrée est comparée comme mot entier.

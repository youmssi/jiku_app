# ADR 106 — Cartes partageables : invitation ouverte, cartes de service et API

**Statut :** accepté.
**Date :** 2026-09-27
**Amont :** ADR 104 (ticket au centre, canaux), ADR 105 (tarification, numéro
propre de l'organisation), plan de production (`docs/jiku-plan-production.md`).

## Contexte

Jikū exige aujourd'hui une liste pour inviter : on importe des contacts, puis
on envoie. Or la plupart des événements privés et informels ne commencent pas
par une liste mais par un message posté dans un ou plusieurs groupes WhatsApp.
L'organisateur ne sait alors ni qui vient, ni combien ils seront, et c'est ce
nombre qui fixe la salle, le traiteur et l'accueil.

Côté services, le constat est le même : une entreprise partage déjà son lien
de réservation, mais un lien nu inspire peu confiance dans un groupe, et rien
ne l'aide à transformer un intéressé en rendez-vous.

Dans les deux cas, ce qui circule, c'est une **carte** : un visuel reconnaissable
au nom de l'organisation, qui mène à un parcours complet jusqu'au ticket.

## Décision 1 — Jikū en trois couches, trois canaux d'accès

```
Canaux d'accès     Application Jikū · API Jikū (intégrateurs) · Widgets
─────────────────────────────────────────────────────────────────────────
1. Distribution    Cartes : invitation · réservation · annonce
                   + conversation WhatsApp + page web de repli + QR
2. Métiers         Événements et invitations · Services et créneaux ·
                   Vente de billets
3. Socle ticket    Billet ou ticket QR · contrôle d'entrée hors ligne ·
                   file du jour · statut de paiement · vérification ·
                   comptage à l'usage
```

- **Tout ce qui se termine par un ticket peut entrer dans Jikū.** La carte est
  la porte d'entrée commune ; le métier décide de ce qu'elle produit : une
  réponse d'invité, un rendez-vous, un ticket de file.
- **L'API n'est pas une couche de plus, c'est un canal d'accès** aux mêmes
  couches. Un intégrateur peut n'utiliser que les cartes, que les créneaux, ou
  tout le parcours. L'architecture modulaire (Spring Modulith) le permet : chaque
  module expose déjà une interface publique interne.

## Décision 2 — Un seul moteur de cartes

Une carte se compose de quatre pièces séparées, pour que chaque nouveau type de
carte soit une extension et non une refonte :

1. **La carte** : l'aperçu du lien (image, titre, description) que WhatsApp
   affiche quand on colle le lien, et une image téléchargeable avec un QR code.
2. **La porte d'entrée** : un code court public qui retrouve l'organisation et
   l'objet visé (événement ou service), sans compte.
3. **La conversation** : un lien vers une discussion WhatsApp avec un message
   déjà rédigé contenant le code ; la page web offre le même parcours en repli.
4. **Le résultat** : ce que le métier enregistre (réponse et billet, rendez-vous,
   prospect).

Un message posté dans un groupe par un particulier ne peut pas porter de
boutons : seul un compte WhatsApp Business en affiche, et seulement en
conversation privée. Le lien vers une discussion est donc le pont obligatoire
entre le groupe et l'échange interactif. C'est l'invité qui écrit le premier :
les réponses de Jikū dans les 24 heures qui suivent sont gratuites chez Meta,
boutons compris, et le numéro de l'expéditeur est authentifié par WhatsApp.

## Décision 3 — Étape 1 : l'invitation ouverte

Un organisateur ouvre une invitation sans liste d'invités et la partage dans ses
groupes. Chaque personne répond **Je viens / Peut-être / Je ne viens pas**, avec
le nombre d'accompagnants pour un « Je viens ».

Décisions validées :

| Sujet | Décision |
|---|---|
| Lancement | Parcours WhatsApp complet **et** page web de repli, livrés ensemble |
| Numéro WhatsApp | Un numéro Jikū **dédié** aux cartes, séparé du numéro des envois d'organisateurs |
| Acceptation | **Automatique**, sous la jauge de l'événement ; l'organisateur peut retirer une personne et fermer les réponses |
| Billet | Chaque « Je viens » reçoit son **billet QR** (même billet et même contrôle d'entrée que les invités) |
| Facturation | Les « Je viens » **et leurs accompagnants** comptent dans les paliers d'invités existants ; « Peut-être » et « Non » ne comptent pas |
| Questions | Nom, réponse, accompagnants. Pas de champs personnalisés au lancement : chaque question en plus fait baisser le taux de réponse |
| Relances | Désactivées au lancement ; réservées ensuite aux formules payantes |
| Identité | Une réponse par numéro, modifiable ; le numéro vient de WhatsApp ou du formulaire web |

La réponse d'un invité ne coûte rien chez Meta ; seules des relances envoyées
plus tard sont facturées (message modèle hors de la fenêtre de 24 heures). Le
palier gratuit de 100 invités par an couvre un anniversaire courant, ce qui fait
de l'invitation ouverte une porte d'entrée gratuite vers Jikū.

## Décision 4 — Étape 2 : les cartes de service

Une entreprise vérifiée partage une **carte d'annonce** ou une **carte de
réservation** pour un de ses services. Selon le besoin, la carte déclenche l'un
de trois parcours :

1. **Redirection** : la carte ouvre la page publique du service de
   l'organisation (présentation, prix, lieu), d'où le client prend rendez-vous.
2. **Rendez-vous** : la carte ouvre directement le choix d'un créneau.
3. **Conversation guidée** : la carte ouvre une discussion WhatsApp avec
   l'entreprise. Quelques questions courtes à choix (boutons ou listes), définies
   par l'organisation dans Jikū, qualifient le besoin (« quel est votre
   problème ? », cases à cocher), puis proposent un créneau et remettent le ticket.

Règles propres à ce volet :

- **Pas de catalogue marchand.** La page de service joue déjà ce rôle ; Jikū ne
  concurrence pas le catalogue de WhatsApp. Une carte mène à un parcours, pas à
  un panier.
- **Réservé aux organisations vérifiées** et à **leur propre numéro WhatsApp**
  (ADR 105) : un signalement pour spam touche le numéro de l'entreprise, jamais
  le numéro partagé de Jikū.
- **Mesure honnête** : WhatsApp ne dit pas combien de personnes ont vu la carte.
  On mesure ce qui est mesurable : clics et scans, conversations ouvertes,
  rendez-vous pris.
- **Facturation** : un quota de conversations inclus dans les abonnements
  Services ; les relances aux prospects (messages marketing chez Meta) vendues
  en supplément.

## Décision 5 — Étape 3 : l'API Jikū pour les intégrateurs

Un nouveau type d'utilisateur, l'**intégrateur** (partenaire plateforme), se
connecte à Jikū depuis son propre outil : clés d'API, notifications vers ses
serveurs, facturation à l'usage, gestion des comptes de ses propres clients.

- **On vend ce qui est unique** : les cartes partageables avec conversation
  WhatsApp, le billet et le contrôle d'entrée hors ligne, la vérification et
  les moyens de paiement locaux. On ne concurrence pas Cal.com ou Eventbrite sur
  leur propre terrain.
- Unités facturées : la conversation ouverte (suit le coût Meta), le billet ou
  le rendez-vous émis (suit la valeur rendue), et un forfait de base (support,
  disponibilité).
- Première intégration de démonstration : un module « Cartes Jikū » dans Cal.com
  ou Hi.Events, dont le projet a déjà une copie.

## Décision 6 — Des étapes avec un feu vert

| Étape | Contenu | Feu vert pour la suite |
|---|---|---|
| 1 — Maintenant | Invitation ouverte (WhatsApp + web) sur le moteur de cartes | Pilote : taux de réponse ≥ 35 %, ≥ 60 % des réponses via WhatsApp, coût par réponse sous le plafond fixé |
| 2 — Ensuite | Cartes de service : redirection, rendez-vous, conversation guidée | Des entreprises paient le quota ou les relances ; signalements bas |
| 3 — +6 à 12 mois | API Jikū : intégrateur, clés, notifications, facturation à l'usage | Un ou deux intégrateurs payants, ou une demande entrante réelle |

Le pilote de l'étape 1 réunit 10 à 20 hôtes réels : associations, promotions
d'anciens élèves et communautés religieuses, événements privés, événements
internes d'entreprise.

Indicateurs suivis : réponses par invitation ouverte (indicateur principal),
part des réponses via WhatsApp, temps de création, répondants devenus hôtes à
90 jours, hôtes passés au payant, coût WhatsApp par réponse, doublons et fausses
réponses, satisfaction de l'hôte.

## Conséquences

- L'invitation ouverte est construite en pièces séparées (carte, porte d'entrée,
  conversation, résultat) : les cartes de service et l'API les réutilisent.
- Un numéro WhatsApp Jikū dédié aux cartes est à créer et à faire valider chez
  Meta ; sa note qualité est suivie dès le premier jour.
- Garde-fous permanents : aucun message non sollicité, mention de
  confidentialité au premier échange, données visibles de l'organisateur seul,
  suppression sur demande, page web toujours disponible.
- Les grilles tarifaires Meta en vigueur (Guinée, Côte d'Ivoire, Sénégal) sont à
  confirmer avant d'activer des relances.
- Plus tard : champs personnalisés sur l'invitation ouverte, en nombre limité.

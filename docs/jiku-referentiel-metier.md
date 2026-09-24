# Jikū — Référentiel métier

**Date :** 2026-09-24
**Statut :** référence produit. Les points marqués **[À VALIDER]** attendent une
décision (§9). Tout le reste est décidé.
**Références :** ADR 104 (décisions), `docs/jiku-modele-financier.md` (chiffres).

Ce document décrit ce que fait Jikū, sans jargon technique. Si un comportement
n'est pas décrit ici, il n'est pas décidé.

## 1. Vocabulaire

| Mot | Sens dans Jikū |
|---|---|
| **Organisation** | Celui qui utilise Jikū pour son activité : banque, clinique, restaurant, organisateur de concert, ou particulier qui organise son mariage |
| **Opérateur** | Personne de l'organisation qui agit sur les tickets : accueil, médecin, serveur, contrôleur, livreur. Son métier n'est qu'une étiquette |
| **Client** | Celui qui détient un ticket : invité, acheteur, patient, client du restaurant. Il n'a pas besoin de compte |
| **Ticket** | Le droit d'entrer, d'être reçu ou d'être servi. Il porte un code signé et un QR |
| **Événement** | Un rassemblement à une date (ou quelques dates) et dans un lieu, avec un nombre de places. Ex. : mariage, concert, conférence |
| **Service** | Une prestation que l'organisation rend en continu, jour après jour, par un professionnel ou une ressource. Ex. : consultation, ouverture de compte, coupe de cheveux, repas |
| **Catégorie d'accès** | Une sorte de place dans un événement (VIP, standard, presse), avec son plafond et, si les billets sont vendus, son prix |
| **Créneau** | Une heure précise réservée pour un service (rendez-vous) |
| **File du jour** | L'ordre de passage des clients d'un service pour la journée, avec ou sans rendez-vous |
| **Palier** | La taille d'un événement à invités gratuits (Gratuit, Bronze, Argent, Or, Sur mesure), qui fixe son prix |
| **Commission** | Les 3 % que Jikū facture sur chaque billet vendu |
| **Tranche** | La commission payée d'avance pour un lot de billets à vendre |

## 2. La règle d'or

> **Ce qui est facturé dépend de ce à quoi le ticket appartient : un service ou
> un événement. Jamais du type d'organisation.**

- Un ticket de **service** est couvert par l'**abonnement**, qu'il soit gratuit
  ou payé par le client, quel que soit son prix.
- Un ticket d'**événement** est facturé **à l'événement** :
  - gratuit pour le client → il compte dans le **palier** de l'événement ;
  - vendu au client → **commission de 3 %**.

Une clinique peut donc avoir les deux : ses consultations (service, couvertes
par l'abonnement) et une conférence santé payante (événement, 3 % sur les
billets vendus).

## 3. Service ou événement : comment trancher

| Question | Service | Événement |
|---|---|---|
| Quand ? | Tous les jours d'ouverture, sans date de fin | Une date, ou quelques dates fixées d'avance |
| Qui fait le travail ? | Un professionnel ou une ressource sert chaque client | Tout le monde assiste en même temps |
| Que reçoit le client ? | Une prestation (être reçu, être servi) | Une entrée (assister, participer) |
| Comment le client obtient son ticket ? | Il le prend : rendez-vous ou file d'attente | Il est invité, ou il achète son billet |
| Exemples | Consultation, guichet, salon de coiffure, restaurant, piscine sur créneau | Mariage, gala, concert, conférence, formation d'un jour |

Cas limite : une séance collective récurrente (cours de sport, visite guidée
quotidienne). Voir **[À VALIDER]** §9, point 1.

## 4. Les cinq usages d'un ticket

| Usage | Appartient à | Qui le crée | Le client paie l'organisation ? | Ce que Jikū facture | Existe aujourd'hui |
|---|---|---|---|---|---|
| **Invitation** | Événement | L'organisation l'envoie | Non | Palier de l'événement | ✅ |
| **Inscription gratuite** | Événement | Le client s'inscrit sur la page publique | Non | Palier de l'événement | ❌ |
| **Billet vendu** | Événement | Le client l'achète | Oui | 3 % du prix du billet | ❌ |
| **Rendez-vous** | Service | Le client réserve un créneau | Selon le service : jamais, avant ou après | Rien de plus que l'abonnement | ✅ (sans paiement) |
| **Sans rendez-vous** | Service | Le client prend un ticket sur place, ou l'accueil le crée | Selon le service : jamais ou après | Rien de plus que l'abonnement | 🟡 (créé par l'accueil seulement) |

Dans le code, l'usage correspond au type de ticket (`TicketKind`). Les deux
usages manquants (inscription gratuite, billet vendu) seront ajoutés au même
endroit.

Un même événement peut mélanger invitations et billets vendus : chaque ticket
est facturé selon son propre usage.

## 5. Cycle de vie d'un ticket

**Ticket d'événement :**

```
Émis ──► (payé, s'il est vendu) ──► Scanné à l'entrée ──► Entré
   └──► Annulé  /  Transféré à une autre personne
```

**Ticket de service :**

```
Réservé ou pris ──► Arrivé ──► En attente ──► Appelé ──► En cours ──► Terminé
                                                  └──► Absent
                        └──► Annulé
```

Le paiement du client, s'il existe, se place avant « Réservé » (règle
« Avant ») ou à « Terminé » (règle « Après service »).

## 6. Qui paie qui

Deux circuits d'argent, jamais mélangés :

```
Client ──(prix du ticket)──► Organisation      Jikū n'y touche jamais
Organisation ──(abonnement, palier, commission, SMS)──► Jikū
```

### Le client paie l'organisation

| Règle de paiement | Pour | Quand le client paie |
|---|---|---|
| **Gratuit** | Événement, service | Jamais |
| **Avant** | Événement, service | Avant d'avoir son ticket |
| **Après service** | Service seulement | Quand l'opérateur termine la prestation |

Au lancement, l'organisation se fait payer de deux façons : ses coordonnées
Mobile Money affichées au client, ou son propre lien de paiement. C'est
l'opérateur ou l'organisateur qui confirme « payé » (ou « payé en espèces »).
La confirmation automatique (compte marchand connecté) vient après le
lancement.

### L'organisation paie Jikū

| Ce qui est payé | Quand | Montant |
|---|---|---|
| **Abonnement Services** | Chaque mois (ou chaque année) | Par utilisateur, selon l'offre (Solo gratuit, Teams, Organisation, Entreprise) |
| **Palier d'un événement à invités** | Au moment où l'action dépasse la part gratuite (importer ou inviter au-delà) | Prix du palier ; passer au palier supérieur ne fait payer que la différence |
| **Tranche de commission** | Avant de vendre, puis à chaque tranche épuisée | 3 % × prix du billet × taille de la tranche |
| **SMS** | Au moment de l'envoi | Prix exact affiché avant d'envoyer |

Moyens de paiement proposés à l'organisation, dans cet ordre : Orange Money,
MTN MoMo, carte, puis Wave.

## 7. La commission par tranche

1. L'organisateur ouvre la vente d'une catégorie de billets.
2. Jikū lui présente la première tranche : par défaut les 50 prochains
   billets, jamais plus que les places restantes. Montant affiché, paiement en un
   écran.
3. Chaque billet marqué « payé » consomme une place de la tranche.
4. Tranche épuisée : la vente se met en pause, l'organisateur paie la suivante
   en un écran.
5. **Ce qui n'a pas servi à la fin de l'événement est reporté automatiquement**
   sur ses prochaines ventes ou son prochain paiement à Jikū, pendant 12 mois.
   Il n'est pas remboursé en argent et ne peut pas être rechargé.

Pourquoi ce choix plutôt que « rien n'est rendu » : l'organisateur ne perd
jamais ce qu'il a payé, donc il n'a aucune raison de craindre la tranche ; et
plafonner la tranche aux places restantes limite le montant reporté. Le report
n'est qu'un montant affiché sur son compte, déduit automatiquement : pas de
portefeuille à gérer.

## 8. Exemples de bout en bout

| Organisation | Ce qu'elle fait dans Jikū | Ce qu'elle paie à Jikū |
|---|---|---|
| **Clinique** | Consultations sur rendez-vous, payées d'avance par le patient | Abonnement (par médecin et secrétaire qui utilise Jikū) |
| | Conférence santé payante, 200 billets à 20 000 GNF | 3 % : 120 000 GNF si les 200 billets sont vendus |
| **Banque** | File d'attente « ouverture de compte », gratuite | Abonnement (par agent) |
| **Restaurant** | Réservation, commande servie puis payée (règle « Après service ») | Abonnement |
| **Famille** | Mariage, 250 invités | Palier Bronze : 150 000 GNF |
| **Organisateur de concert** | 1 000 billets à 50 000 GNF | 3 % : 1 500 000 GNF si tout est vendu, payés par tranches |

## 9. Points à valider [À VALIDER]

1. **Frontière service / événement** pour les séances collectives
   récurrentes : sans règle, un organisateur de concert pourrait déclarer ses
   soirées comme un « service » pour échapper aux 3 %.
2. **Paiement d'un événement à invités** : le simulateur et le module
   `booking` font aujourd'hui réserver la date avec 30 % d'acompte (solde 7
   jours avant, remboursement selon la date d'annulation). L'ADR 104 prévoit au
   contraire un paiement au moment de l'action. Un seul des deux doit rester.
3. **Qui compte comme utilisateur payant** dans l'abonnement Services.
4. **Vérification des organisateurs** avant qu'ils vendent des billets.

Propositions par défaut, à confirmer :

5. **Paiement à l'entrée d'un événement** (réservé en ligne, payé à la porte) :
   hors lancement.
6. **Prix affichés toutes taxes comprises**, taxe incluse dès que le taux de la
   Guinée est confirmé.
7. **Événement annulé par l'organisateur** : la commission des billets déjà
   vendus reste due ; le reste des tranches est reporté comme au §7. Le
   remboursement des clients est la responsabilité de l'organisation.
8. **Données des clients** : elles appartiennent à l'organisation ; Jikū les
   traite pour son compte et ne les utilise à aucune autre fin.
9. **Traitement comptable des tranches** (facture d'acompte puis facture de
   clôture) : à confirmer avec le comptable.

## 10. Ce que doit montrer le simulateur

Le simulateur actuel a deux onglets : « Événement (invitations) » et
« Rendez-vous (abonnement) ». Il ne parle ni des billets vendus, ni de la file
d'attente, ni de la règle d'or.

Proposition :

1. **Une phrase en tête**, avant tout calcul :
   « Vous rendez un service chaque jour ? Un abonnement. Vous organisez un
   événement ? Vous payez l'événement : selon le nombre d'invités s'ils entrent
   gratuitement, 3 % des billets si vous les vendez. »
2. **Trois onglets**, nommés par ce que fait l'utilisateur :
   - **« Je reçois des clients »** (rendez-vous, file d'attente) : choix de
     l'offre et du nombre d'utilisateurs → prix par mois. Mention : « Vos
     clients peuvent vous payer leur ticket : aucune commission Jikū. »
   - **« J'invite des personnes »** : curseur du nombre d'invités → palier et
     prix (le calcul actuel).
   - **« Je vends des billets »** : prix du billet et nombre de billets →
     commission de 3 % et montant par tranche. Mention : « L'argent des ventes
     arrive directement chez vous. »
3. **Un encadré « Et si je fais les deux ? »** avec l'exemple de la clinique du
   §8.
4. **Le SMS en option**, avec son prix unitaire, dans chaque onglet.

La refonte du simulateur ne commence qu'après validation du §9, point 2,
puisque le texte sur l'acompte en dépend.

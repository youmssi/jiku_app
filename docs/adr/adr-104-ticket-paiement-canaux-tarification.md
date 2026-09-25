# ADR 104 — Plateforme centrée sur le ticket : file d'attente, paiement, opérateurs, canaux et tarification

**Statut :** accepté. Décisions complémentaires : `docs/jiku-referentiel-metier.md`, §10.
**Date :** 2026-09-24
**Remplace partiellement :** ADR 81 (la file d'attente passe de « proposée » à
« dans le produit »).
**Amont :** comparatif Jikū / cal.com / Hi.Events et session de cadrage produit.

> Le numéro 104 est provisoire : il sera réaligné sur l'identifiant de story du
> backlog au moment de la planification.

## Contexte

Jikū a démarré avec les invitations et le check-in. Il gère désormais aussi les
rendez-vous (moteur de créneaux, ADR 85) et une file du jour simple (JIKU-88 :
`WAITING → CALLED → IN_SERVICE → DONE | NO_SHOW`). Trois constats :

1. Le code tourne déjà autour d'un seul objet, le ticket (`TicketKind` :
   `INVITATION`, `APPOINTMENT`, `WALK_IN`), mais aucun document ne l'assume.
2. Seul l'organisateur paie Jikū aujourd'hui (module `money`, paliers par
   événement). Le client final ne peut rien payer à l'organisation : ni
   `TicketType` ni `Service` ne portent de prix.
3. Les concurrents couvrent chacun une partie seulement : cal.com le créneau,
   Hi.Events la vente de billets. Aucun ne gère la file d'attente, ni le
   Mobile Money, ni le scan hors-ligne.

Principe qui guide toutes les décisions ci-dessous : **chaque parcours doit
être minimal**. Un prix affiché avant chaque clic, un seul écran pour payer,
aucune carte bancaire exigée, pas de crédit à acheter d'avance.

## 1. Le produit en une phrase

> Jikū gère des tickets signés. Un ticket, c'est le droit d'entrer, d'être reçu
> ou d'être servi. Jikū le crée, le fait payer si besoin, l'envoie, le scanne et
> clôture le passage.

Un ticket naît de trois façons :

| Origine | Déclencheur | Exemples |
|---|---|---|
| **Invité** | L'organisateur l'envoie à une personne connue | Mariage, gala, AG |
| **Acheté** | Le client l'achète en ligne | Concert, soirée, formation |
| **Demandé** | Le client le prend : avec heure (rendez-vous) ou sans heure (file) | Clinique, banque, piscine, restaurant |

**La file d'attente fait partie du produit.** Elle n'est qu'une manière de
servir un ticket « demandé » sans heure. La contrainte relevée par ADR 81 (un
ticket = un invité, `uq_ticket_guest`) reste acceptable tant qu'un client sans
compte est enregistré comme un invité minimal. Un vrai ticket anonyme fera
l'objet d'une tranche dédiée.

## 2. Les acteurs

| Acteur | Rôle |
|---|---|
| **Équipe Jikū** | Exploite la plateforme : valide, encaisse les abonnements, assure le support |
| **Organisation** | Banque, clinique, restaurant, ou particulier (organisation d'une seule personne). Configure marque, services, événements, prix, équipe |
| **Opérateur** | Toute personne qui agit sur des tickets : agent, médecin, serveur, livreur, contrôleur. Le métier n'est qu'une étiquette |
| **Client** | Porteur du ticket, avec compte ou non |

Un opérateur est limité par trois périmètres : **services ou événements**,
**lieux**, **actions** (scanner, appeler, servir, encaisser, livrer). Ce modèle
remplace à terme les liens validateur par événement et le personnel par service
(`ServiceStaff`).

## 3. Règle de paiement portée par le ticket

Chaque type de ticket ou service porte une règle. Pour le MVP, trois règles
suffisent :

| Règle | Quand le client paie | Exemple |
|---|---|---|
| **Gratuit** | Jamais | Invitation, retrait bancaire |
| **Avant** | Pas de ticket sans paiement confirmé | Concert, consultation prépayée |
| **Après service** | Au scan « terminé », Jikū présente au client les moyens de paiement de l'organisation | Restaurant, prestation facturée |

La confirmation du paiement dépend de ce que l'organisation a configuré (§4) :
automatique si son compte marchand est connecté, sinon manuelle (l'opérateur
marque « payé », y compris « payé en espèces »).

Plus tard : « avant usage » (ticket émis mais scan refusé tant que ce n'est pas
payé) et « externe » (le système de la banque confirme lui-même).

## 4. Jikū n'encaisse jamais l'argent de ses clients

**Décision :** Jikū ne perçoit que **ses propres revenus** (abonnements, prix
des événements, commission). L'argent que les clients finaux paient aux
organisations **va directement chez l'organisation**. Jikū ne détient, ne
reverse et ne rembourse jamais de fonds pour le compte d'un tiers.

Raisons : le risque juridique (encaissement pour compte de tiers, soumis à
agrément) et le risque opérationnel (litiges, remboursements, fraude) sont trop
élevés pour la plateforme. Cette décision supprime aussi toute la logique de
reversement.

### Deux circuits, jamais mélangés

1. **L'organisation paie Jikū.** Jikū encaisse sur son propre compte
   agrégateur.
2. **Le client paie l'organisation.** Jikū se contente d'afficher les moyens de
   paiement de l'organisation ou de rediriger vers son compte.

### Circuit 1 — Jikū se fait payer

1. L'organisateur fait une action payante (importer au-delà du gratuit,
   souscrire, envoyer des SMS, régler sa commission).
2. Jikū affiche le montant exact et les boutons : Orange Money, MTN MoMo,
   Carte, Wave.
3. Le client confirme sur son téléphone. La confirmation du prestataire
   (callback serveur) débloque l'action. La facture part par e-mail ou WhatsApp.
4. En secours, la déclaration de paiement manuel vérifiée par l'équipe Jikū
   (déjà en place) reste disponible.

### Circuit 2 — l'organisation se fait payer

L'organisation choisit un des trois niveaux, du plus simple au plus automatisé :

| Niveau | Ce que l'organisation saisit | Ce que voit le client | Qui confirme le paiement |
|---|---|---|---|
| **1. Coordonnées affichées** | Ses numéros Orange Money / MTN / Wave, nom du bénéficiaire | Les coordonnées et le montant à payer | L'organisateur ou l'opérateur (« payé ») |
| **2. Lien de paiement** | Le lien de paiement de son propre compte (CinetPay, Wave…) | Un bouton « Payer » qui ouvre ce lien | L'organisateur ou l'opérateur (« payé ») |
| **3. Compte marchand connecté** | Les clés de son propre compte marchand (encaissement seulement) | Les boutons Orange Money / MTN / Carte / Wave | Automatique : le prestataire confirme, le ticket est émis |

Au niveau 3, l'argent arrive directement sur le compte de l'organisation : Jikū
appelle le prestataire **avec les clés de l'organisation** et reçoit seulement
la confirmation. Les clés sont chiffrées au repos, comme les réglages
e-mail et WhatsApp actuels. Jikū ne demande jamais de clés donnant accès aux
transferts sortants.

Parcours client final, concert au niveau 3 : page de l'événement → choix du
billet → paiement sur le compte de l'organisateur → confirmation → billet signé
envoyé par WhatsApp, SMS ou e-mail.

Parcours client final, restaurant au niveau 1 : réservation → ticket → arrivée
et scan → commande servie → l'opérateur appuie sur « terminer » → le client voit
les numéros de paiement du restaurant, paie, l'opérateur marque « payé ».

## 5. Principe du sélecteur : Jikū ne connaît aucun prestataire

Le code métier ne connaît que **des capacités** : « encaisser un montant »,
« envoyer un message par tel canal ». Il ne connaît ni CinetPay, ni Wave, ni
Nimba, ni Resend.

```
Code métier ──► Capacité (port) ──► Sélecteur ──► Adaptateur A | B | C
                 « encaisser »        choisit        CinetPay, Wave, K-PAY…
                 « envoyer SMS »      lequel         Nimba, Sent.dm…
```

- **Adaptateur** : une classe par prestataire, qui traduit la capacité vers son
  API. Ajouter un prestataire = ajouter un adaptateur et sa configuration, sans
  toucher au code métier.
- **Sélecteur** : choisit l'adaptateur à utiliser, dans cet ordre :
  1. le prestataire configuré par l'organisation (son compte, niveau 3, ou ses
     propres clés e-mail / WhatsApp / SMS) ;
  2. sinon le prestataire de la plateforme, fixé par configuration ;
  3. sinon le suivant dans l'ordre de repli configuré.
- Changer de prestataire est un **changement de configuration**, jamais de
  code métier.

État actuel : l'interface de paiement (`PaymentProvider`) respecte déjà ce
principe mais n'accepte qu'un seul prestataire à la fois. Le choix des
prestataires e-mail et WhatsApp (`MessagingProviderResolver`) construit
lui-même les adaptateurs Resend et Meta : il faudra le ramener à un simple
sélecteur appuyé sur un registre d'adaptateurs.

## 6. Prestataires de paiement (circuit 1 et niveau 3)

Ordre retenu : **Mobile Money d'abord, carte ensuite, Wave en troisième**.

| Rang | Prestataire | Pourquoi |
|---|---|---|
| 1 | **CinetPay** | Couvre la Guinée en GNF (Orange Money GN, MTN MoMo GN) et la carte : une seule intégration apporte les deux premiers moyens de paiement. Présent dans plusieurs pays d'Afrique francophone |
| 2 | **Wave** (en direct) | Lancé en Guinée en mars 2026, frais bas. À brancher en direct si CinetPay ne le propose pas en Guinée |
| 3 | **K-PAY** | Secours et expansion (12 pays). Couverture de la Guinée à confirmer |

Le client ne voit jamais le nom de l'agrégateur. Pour les estimations, on
retient le **taux public brut de CinetPay, 3,5 % par transaction**, sans remise
négociée (taux affiché pour la Côte d'Ivoire ; celui de la Guinée est à
confirmer).

## 7. Canaux de communication

| Canal | Prestataire | État |
|---|---|---|
| E-mail | Resend (principal), Brevo | En place |
| WhatsApp | Meta Cloud API en direct | En place, avec suivi des coûts |
| **SMS** | **Nimba SMS** en premier | À faire |
| Multicanal | Sent.dm | Plus tard, pour l'expansion hors Guinée |
| — | Twilio | Non retenu : coût et contraintes d'expéditeur en Guinée |

Pourquoi le SMS en premier : la file d'attente doit toucher les clients sans
données mobiles ni WhatsApp. Nimba SMS est basé en Guinée et relié directement à
Orange, MTN et Cellcom.

Règle de repli gérée par le sélecteur, pas par un prestataire : **WhatsApp,
puis SMS si WhatsApp échoue**. L'e-mail reste le canal des documents (factures,
billets PDF).

Le suivi du rang dans la file se fait **d'abord sur la page du ticket, en
direct**, sans coût d'envoi. Un message n'est envoyé qu'aux moments utiles
(« vous êtes le prochain »), car un SMS coûte environ trois fois un message
WhatsApp utilitaire.

Pour un rendez-vous qui n'est pas physique, le ticket reste la preuve et porte
un lien de visio au lieu d'un QR.

## 8. Tarification

> **Remplacé par l'ADR 105** (2026-09-25) : grille unique en GNF, FCFA et USD,
> modes d'envoi, Pack Organisateur et règles de commission assouplies.

Trois lignes, affichées telles quelles sur la page des prix :

1. **Services** (file d'attente, réservation, rendez-vous) : **abonnement par
   utilisateur et par mois**, grille actuelle conservée (Solo gratuit, Teams,
   Organisation, Entreprise).
2. **Événements avec tickets gratuits** (invitations) : **paiement à l'usage**
   selon l'algorithme du simulateur. Gratuit jusqu'à 100 invités sur 12 mois
   glissants, puis prix de l'événement selon sa taille (Bronze, Argent, Or, puis
   prix par invité au-delà). Le paiement est demandé **au moment de l'action**
   qui dépasse le gratuit. Passer au palier supérieur ne fait payer que la
   différence.
3. **Tickets payants** (concert, gala payant) : **3 % du prix de chaque billet
   vendu**. L'argent des ventes va chez l'organisation (§4) ; la commission est
   payée à Jikū **avant la vente, par tranche** (voir ci-dessous). Rien n'est dû
   si rien n'est vendu. Ces tickets ne comptent pas dans les paliers d'invités.

### Commission payée par tranche

- Quand l'organisateur ouvre la vente, Jikū lui demande la commission d'une
  tranche de billets (par défaut les 50 prochains, jamais plus que les places
  restantes) : 3 % × prix × taille de la tranche, montant affiché avant le
  paiement.
- Chaque billet marqué « payé » consomme une place de la tranche. Quand la
  tranche est épuisée, la vente se met en pause jusqu'au paiement de la
  suivante. Jikū n'a donc jamais d'impayé à recouvrer.
- La part d'une tranche non consommée à la fin de l'événement est **reportée
  automatiquement** sur les prochaines ventes ou le prochain paiement de
  l'organisation à Jikū, pendant 12 mois. Elle n'est pas remboursée en argent
  et ne peut pas être rechargée : ce n'est pas un portefeuille.
- La taille de la tranche et le taux sont des paramètres de configuration.

Règles de simplicité :

- Le prix de l'invitation inclut l'e-mail et WhatsApp. Le **SMS est une option
  affichée avec son prix exact au moment de l'envoi**.
- Pas de portefeuille à recharger, pas de crédits prépayés.
- Tous les prix sont des paramètres de configuration, jamais des valeurs
  écrites dans le code.

## 9. Décisions prises et point encore ouvert

Décidé le 2026-09-24 :

1. **Commission** : 3 % du prix de chaque billet vendu.
2. **Paiement de la commission** : avant la vente, par tranche (§8).
3. **Monnaies** : une grille de prix par monnaie. Chaque prix est fixé à la
   main, en montant rond. La monnaie d'une organisation est fixée par son pays à
   l'inscription et ne change plus ; ses factures sont émises dans cette
   monnaie, avec un équivalent indicatif en USD.
4. **Moyens de paiement des organisations au lancement** : niveaux 1
   (coordonnées affichées) et 2 (lien de paiement). Le niveau 3 (compte marchand
   connecté) vient après le lancement.

5. **Services qui encaissent de l'argent** (consultation prépayée) : aucune
   commission, l'abonnement couvre tout. La facturation suit ce à quoi le
   ticket appartient (service ou événement), jamais le type d'organisation : une
   clinique qui vend les billets d'une conférence paie la commission sur ces
   billets. Les règles complètes sont dans `docs/jiku-referentiel-metier.md`.

Les décisions complémentaires (frontière service / événement, fin de l'acompte,
utilisateurs payants, vérification des organisateurs) sont dans le référentiel
métier, §10.

## Ordre de réalisation

Le détail (tranches, dépôts, critères de fin) est dans
`docs/jiku-plan-production.md`.

1. Sélecteur de prestataires et intégration CinetPay pour le circuit 1.
2. Règle de paiement sur le ticket et niveaux 1 et 2 du circuit 2.
3. Canal SMS (Nimba SMS) et règle de repli WhatsApp → SMS.
4. Opérateur avec périmètre (services, lieux, actions).
5. File d'attente complète (ticket pris par le client, suivi du rang en direct).
6. Vente publique de billets et facturation de la commission.
7. Niveau 3 du circuit 2 (compte marchand connecté), puis Wave en direct.

## Non-objectifs

- Aucun encaissement, reversement ou remboursement pour le compte d'un tiers.
- Pas de synchronisation d'agendas Google ou Outlook.
- Pas de Stripe Connect.
- Pas de second abonnement pour les événements.

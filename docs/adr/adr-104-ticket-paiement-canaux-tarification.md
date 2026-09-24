# ADR 104 — Plateforme centrée sur le ticket : file d'attente, paiement, opérateurs, canaux et tarification

**Statut :** accepté pour les décisions produit (§1 à §7) ; les points marqués
**[À TRANCHER]** restent ouverts (§8).
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
| **Après service** | Le scan « terminé » envoie la demande de paiement sur le téléphone du client | Restaurant, prestation facturée |

L'opérateur peut aussi marquer « payé en espèces » : c'est la réalité du
terrain et cela évite de bloquer un passage.

Plus tard : « avant usage » (ticket émis mais scan refusé tant que ce n'est pas
payé) et « externe » (le système de la banque confirme lui-même).

## 4. Deux circuits d'argent, jamais mélangés

1. **L'organisation paie Jikū** : abonnement, prix d'un événement, commission.
2. **Le client paie l'organisation** : prix de son ticket.

Les deux circuits utilisent la même interface technique (`PaymentProvider`,
déjà présente dans `money`) mais des comptes, des écritures et des factures
distincts.

### Parcours « Jikū se fait payer » (circuit 1)

1. L'organisateur fait une action payante (importer au-delà du gratuit,
   souscrire, envoyer des SMS).
2. Jikū affiche le montant exact et les boutons : Orange Money, MTN MoMo,
   Carte, Wave.
3. Le client confirme sur son téléphone. La confirmation du prestataire
   (callback serveur) débloque l'action. La facture part par e-mail ou WhatsApp.
4. En secours, la déclaration de paiement manuel vérifiée par l'équipe Jikū
   (déjà en place) reste disponible.

### Parcours « l'organisation se fait payer » (circuit 2)

**Oui, c'est possible pour les clients de Jikū.** Deux modèles :

| | **A. Jikū encaisse pour l'organisation** (par défaut) | **B. Compte marchand de l'organisation** |
|---|---|---|
| Pour qui | Particuliers, petits organisateurs, restaurants | Banques, cliniques, grands comptes |
| Argent du client | Arrive sur le compte agrégateur de Jikū | Arrive directement chez l'organisation |
| Commission Jikū | Retenue à la source avant reversement | Facturée à l'organisation (mensuel) |
| Reversement | Automatique, après le délai de règlement de l'agrégateur | Aucun, l'argent est déjà chez elle |
| Configuration | Aucune : un numéro Mobile Money pour recevoir | Ses propres clés, comme les réglages e-mail/WhatsApp actuels |

Parcours client final (concert, modèle A) : page de l'événement → choix du
billet → paiement Orange Money / MTN / carte / Wave → confirmation → billet
signé envoyé par WhatsApp, SMS ou e-mail. L'organisateur voit ses ventes en
direct. Il reçoit le montant moins la commission sur son numéro Mobile Money,
après l'événement ou sur demande.

Parcours client final (restaurant, règle « après service ») : réservation à
distance → ticket → arrivée et scan → commande servie → l'opérateur appuie sur
« terminer » → demande de paiement sur le téléphone du client, ou « payé en
espèces ».

## 5. Prestataires de paiement

Ordre retenu : **Mobile Money d'abord, carte ensuite, Wave en troisième**.

| Rang | Prestataire | Rôle | Pourquoi |
|---|---|---|---|
| 1 | **CinetPay** | Agrégateur principal | Couvre la Guinée en GNF (Orange Money GN, MTN MoMo GN), la carte, et offre une API de transfert pour les reversements. Une seule intégration apporte les rangs « Mobile Money » et « carte » |
| 2 | **Wave** (intégration directe) | Troisième moyen | Lancé en Guinée en mars 2026, frais bas. À brancher en direct si CinetPay ne le propose pas en Guinée |
| 3 | **K-PAY** | Agrégateur de secours et d'expansion | 12 pays, Mobile Money, carte, encaissement et reversement. La couverture de la Guinée reste à confirmer |

Le client ne voit jamais le nom de l'agrégateur, seulement « Orange Money »,
« MTN », « Carte », « Wave ». Changer d'agrégateur est une décision de
configuration, pas une modification du parcours.

Point d'attention : CinetPay applique par défaut un délai de règlement (huit
jours) avant que les fonds soient disponibles. Le délai de reversement promis
aux organisateurs (modèle A) doit en tenir compte.

## 6. Canaux de communication

Même principe que le paiement : le code métier dit « envoyer ce message par ce
canal », un adaptateur choisit le prestataire. Le mécanisme existe déjà pour
l'e-mail et WhatsApp (`MessagingProviderResolver`, `TenantProviderSettings`) :
prestataire de la plateforme par défaut, prestataire propre à l'organisation
s'il est configuré.

| Canal | Prestataire | État |
|---|---|---|
| E-mail | Resend (principal), Brevo | En place |
| WhatsApp | Meta Cloud API en direct | En place, avec suivi des coûts |
| **SMS** | **Nimba SMS** en premier | À faire |
| Multicanal | Sent.dm | Plus tard, pour l'expansion hors Guinée |
| — | Twilio | Non retenu : coût et contraintes d'expéditeur en Guinée |

Pourquoi le SMS en premier : la file d'attente (« vous êtes le prochain ») doit
toucher les clients sans données mobiles ni WhatsApp. Nimba SMS est basé en
Guinée, relié directement à Orange, MTN et Cellcom, et gère un nom
d'expéditeur.

Règle de repli gérée par Jikū, pas par un prestataire : **WhatsApp, puis SMS si
WhatsApp échoue**, l'e-mail restant le canal des documents (factures, billets
PDF). Garder cette règle chez nous permet de changer de prestataire sans
changer le comportement.

Pour un rendez-vous qui n'est pas physique, le ticket reste la preuve et porte
un lien de visio au lieu d'un QR. Les canaux ne sont que des options d'envoi.

## 7. Tarification

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
3. **Tickets payants** (concert, gala payant) : **un pourcentage de ce qui est
   vendu**. Rien n'est dû si rien n'est vendu. Ces tickets ne comptent pas dans
   les paliers d'invités : une seule règle s'applique à chaque ticket.

Règles de simplicité :

- Le prix de l'invitation inclut l'e-mail et WhatsApp. Le **SMS est une option
  affichée avec son prix exact au moment de l'envoi** (« Envoyer aussi par SMS :
  N GNF »), car c'est le seul canal dont le coût varie fortement.
- Pas de portefeuille à recharger, pas de crédits prépayés.
- Le pourcentage sur les ventes est **un seul chiffre tout compris** (frais de
  l'agrégateur inclus). L'organisateur n'a pas à additionner des frais.

## 8. Points ouverts [À TRANCHER]

1. **Taux de commission** sur les tickets payants : il doit couvrir le coût de
   l'agrégateur plus une marge. À fixer après négociation avec CinetPay. Ce taux
   sera un paramètre de configuration, jamais une valeur écrite dans le code.
2. **Modèle A (Jikū encaisse pour l'organisation)** : vérifier auprès de
   l'agrégateur et du régulateur (BCRG) le cadre de l'encaissement pour compte
   de tiers avant d'ouvrir ce modèle. Tant que ce n'est pas validé, seul le
   modèle B est proposé.
3. **Services qui encaissent de l'argent** (consultation prépayée) : appliquer
   aussi le pourcentage, ou l'inclure dans l'abonnement ?
4. **Délai de reversement** promis aux organisateurs (après l'événement, sous N
   jours, ou sur demande).

## Ordre de réalisation

1. Règle de paiement sur le ticket et intégration CinetPay (Mobile Money et
   carte), d'abord pour le circuit 1, puis le circuit 2.
2. Canal SMS (Nimba SMS) et règle de repli WhatsApp → SMS.
3. Opérateur avec périmètre (services, lieux, actions).
4. Vente publique de billets (commande, prix, codes promo) avec commission.
5. Wave en direct.
6. Ensuite : avis après service, agences multiples, domaine personnalisé,
   questionnaire d'orientation, écran d'appel.

## Non-objectifs

- Pas de synchronisation d'agendas Google ou Outlook (point fort de cal.com,
  hors de notre marché de départ).
- Pas de Stripe Connect (point fort de Hi.Events, remplacé ici par un agrégateur
  Mobile Money).
- Pas de second abonnement pour les événements.

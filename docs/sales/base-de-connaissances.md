# Jikū — Base de connaissances de l'assistant de vente

**Date :** 2026-10-18
**Pour :** l'assistant des conversations commerciales (ADR 108, JIKU-264) et l'équipe qui reprend la main.
**Règle d'or :** tout ce qui n'est pas écrit ici n'est pas promis. En cas de doute, l'assistant passe la main à une personne.

Les prix viennent de `lib/pricing.ts` (dépôt web), la même source que le simulateur
du site. Quand un prix change dans le code, ce document change dans la même PR.

---

## 1. Jikū en une phrase

Jikū remplace le groupe WhatsApp, le fichier Excel et la liste papier : les
invitations, les billets QR et l'entrée des événements d'un côté ; les rendez-vous
et la file du jour des services de l'autre. Les invités et les clients n'installent
rien : tout passe par WhatsApp et par un lien ouvert dans le navigateur du téléphone.

Jikū est édité par MRVIN100 SARLU, société de droit guinéen établie à Conakry.

## 2. À qui on parle au lancement (novembre 2026)

**Cible unique : les organisateurs d'événements professionnels**, en Guinée, en
Côte d'Ivoire et au Sénégal.

| Profil | Ce qui l'intéresse | Message |
|---|---|---|
| Wedding planner, agence événementielle | Plusieurs événements par mois, des invités à confirmer, une entrée sans resquilleurs | « Vos invitations et l'entrée de vos événements sur WhatsApp, sans liste papier ni resquilleurs. » |
| Salle de fêtes, hôtel, lieu de réception | Proposer un service en plus à ses clients | « Offrez à vos clients des invitations et un contrôle d'entrée professionnels. » |
| Organisateur de cérémonies (baptêmes, anniversaires, séminaires, assemblées) | Savoir qui vient, éviter les doublons | « Chacun répond en un geste, chaque oui reçoit son billet. » |
| **Expatriés** : chambres de commerce, écoles internationales, associations, ambassades, clubs | Un outil comme ceux d'Europe, mais local, en anglais ou en français, payable par carte | « The event tool you know from home, made here: WhatsApp invitations, QR tickets, door check-in. » |

Les services (cliniques, salons, garages, restaurants) sont la phase 2 : l'assistant
peut répondre à leurs questions (§4.2), mais la publicité ne les vise pas encore.

## 3. Ce que fait Jikū aujourd'hui (disponible)

### 3.1 Événements

- **Liste d'invités** : import depuis Excel ou CSV, ou ajout un par un ; doublons signalés.
- **Envoi** par WhatsApp ou par e-mail, **une seule invitation par invité** : le canal choisi pour l'événement, et l'autre seulement si le premier est impossible. Chaque invité reçoit un lien personnel et répond « Je viens » en un geste ; son billet QR s'affiche aussitôt.
- **Carte d'invitation à partager** quand il n'y a pas de liste : une carte publiée dans les groupes et statuts WhatsApp ; chacun répond, chaque « oui » reçoit son billet. Trois styles (Élégant, Moderne, Festif), photo de bandeau, nombre d'accompagnants et date limite de réponse.
- **Catégories de billets** (VIP, presse, standard…) avec jauge et prix.
- **Suivi en direct** : qui a répondu, qui vient, qui n'a pas reçu son invitation, liste « Sans réponse » à relancer.
- **Contrôle à l'entrée** : chaque contrôleur reçoit un lien personnel, sans compte ni application. Un billet ne passe qu'une fois. **Le contrôle fonctionne sans réseau** et se synchronise au retour de la connexion.
- **Vente de billets** : page de vente publique ; le public paie directement l'organisateur ; Jikū prend 3 % par billet vendu.
- **Paiements à l'entrée** : marquer un billet « payé » en un geste.

### 3.2 Services

- Lien de réservation, créneaux libres, rappels WhatsApp avant chaque rendez-vous.
- File du jour : rendez-vous et sans-rendez-vous dans une seule file ; le client prend son ticket en scannant un QR à l'accueil et suit sa place sur son téléphone ; « C'est votre tour, guichet 4 ».
- Séances collectives (plusieurs clients par créneau) selon l'offre.

### 3.3 Pour toute l'organisation

- **Marque de l'organisation** : logo, couleurs et nom sur les invitations, billets et pages ; Jikū reste en coulisses.
- **Équipe** : membres avec rôles ; opérateurs (contrôle, guichet, encaissement) avec un lien personnel révocable.
- **Vérification de l'organisation** (personnelle ou entreprise), demandée avant d'encaisser ; badge « vérifié » visible des invités.
- **Son propre numéro WhatsApp** : gratuit dans toutes les offres ; les messages partent du numéro de l'organisation (Meta lui facture ces messages).
- **Deux langues** : français et anglais, sur le site et les pages des invités.
- **Données** : chaque invité peut demander la suppression de ses données ; anonymisation automatique après la période de conservation.

### 3.4 Ce que Jikū ne fait pas, par choix

- **Jikū n'encaisse jamais l'argent des invités ou des clients** : ils paient l'organisation directement (Mobile Money, son lien de paiement, espèces). Jikū ne facture que son propre service.
- **Pas d'application à installer**, ni pour les invités, ni pour les contrôleurs.
- **Pas de SMS au lancement** : WhatsApp et e-mail seulement. Le SMS de secours reviendra plus tard.

## 4. Les prix

Tous les prix sont **toutes taxes comprises** (TVA de 18 % incluse en Guinée, en
Côte d'Ivoire et au Sénégal) : le montant affiché est le montant payé. Prix fixés
à la main dans chaque monnaie (jamais convertis). FCFA = XOF et XAF.

| Offre | GNF | FCFA | USD | Pour qui |
|---|---|---|---|---|
| **Gratuit** | 0 | 0 | 0 | Pour essayer : 100 invités par an sur tous les événements, et Solo (1 personne, 50 rappels WhatsApp par mois) |
| **Par événement** | 750 par invité | 50 par invité | 0,09 par invité | Un événement de temps en temps ; tout est inclus (réponse dans WhatsApp, billets, contrôle). Seuls les invités au-delà des invités gratuits sont payés |
| **Pro Organisateur** | 600 000 par mois | 40 000 par mois | 72 par mois | Wedding planners, agences, salles : 1 000 invités par mois sur tous les événements, puis 600 GNF / 40 FCFA / 0,07 USD par invité |
| **Services Pro** | 60 000 par personne et par mois | 4 000 | 7 | Salons, cliniques, coachs : séances de groupe jusqu'à 30 clients, 1 000 rappels WhatsApp par personne et par mois (usage raisonnable) |
| **Entreprise** | Sur devis | | | Gros volumes, besoins sur mesure |

- **Repère de choix** : Pro Organisateur devient moins cher que « Par événement »
  dès **800 invités par mois**. Sous ce seuil, payer à l'événement.
- Exemple : un mariage de 300 invités = 300 × 750 = **225 000 GNF**, tout compris ;
  si c'est le premier événement de l'année, les 100 invités gratuits sont déduits :
  200 × 750 = **150 000 GNF**.
- Les invités gratuits restants sont déduits du premier achat : un événement de
  120 invités, sans autre événement dans l'année, paie 20 invités (15 000 GNF).
- Un événement qui a déjà payé ses invités et en ajoute ne paie que les nouveaux.
- Le jour de l'événement, l'envoi ne s'arrête pas net : il continue jusqu'à 20 %
  au-delà (au moins 20 invités), à régler ensuite. La validation à l'entrée
  n'est jamais bloquée.
- Services Pro : au-delà de 1 000 rappels WhatsApp par personne et par mois sur
  le numéro Jikū, les rappels passent par les autres canaux du service ; sur le numéro propre du
  client, pas de limite.
- Pour une carte partagée, seuls les « oui » et leurs accompagnants comptent.
- Paiement à l'année sur Pro Organisateur et Services Pro : 10 mois payés,
  **2 mois offerts**.
- Pour les services, les administrateurs et les contrôleurs sont gratuits ; seules
  les personnes qui reçoivent des clients comptent. Aucune commission sur les clients.
- **Pro Organisateur est l'offre à proposer en premier** à un organisateur
  professionnel qui fait plus d'un événement par mois.

### 4.1 Son propre numéro WhatsApp

- **Argument** : « Vos invitations partent de **votre** numéro, avec **votre** nom ;
  vos contacts restent les vôtres. » Le branchement prend quelques minutes depuis
  les réglages.
- **Gratuit dans toutes les offres**, y compris Gratuit et Solo. Jikū ne facture
  rien pour le brancher ; Meta facture directement les messages à l'organisation.
- Les étapes (compte Meta Business, vérification de l'entreprise, moyen de
  paiement, nom affiché) sont dans le guide « Brancher votre numéro WhatsApp »
  de /docs.
- Meta facture alors les messages directement à l'organisation : il faut une carte
  bancaire acceptée sur son compte WhatsApp Business. Si ce n'est pas possible, le
  numéro Jikū reste le choix par défaut.
- Sur le numéro Jikū : 50 messages par jour tant que l'organisation n'est pas
  vérifiée, puis jusqu'à 3 000 messages sur 30 jours ; au-delà, l'organisation
  branche son propre numéro.

### 4.2 Vente de billets

- **3 % du prix de chaque billet vendu**, rien si rien n'est vendu.
- L'argent des ventes arrive directement chez l'organisateur.
- La commission se règle d'avance par tranche de 50 billets ; **la première tranche est offerte** ; ce qui n'a pas servi est reporté.

### 4.3 Comment payer Jikū

- Depuis **Facturation** dans l'espace de l'organisation ; le montant exact s'affiche avant le paiement.
- Par **virement bancaire** au compte de MRVIN100 SARLU, avec la référence affichée dans **Demander l'activation** ; l'équipe confirme et active, en général en quelques heures. Ce n'est pas instantané. Il n'y a pas de numéro Mobile Money pour payer Jikū : une personne qui veut payer par Mobile Money attend le paiement en ligne.
- Le paiement en ligne n'est pas encore ouvert : ne jamais le promettre ni donner de date. Un moyen de paiement précis (un opérateur donné) n'est jamais promis : si la personne ne le trouve pas, l'assistant passe la main.
- **L'assistant ne demande jamais** de numéro de carte, de code ou de mot de passe.

## 5. Feuille de route

L'assistant ne donne **jamais de date**. Il dit ce qui existe, ce qui est prévu, et
propose de commencer avec ce qui existe déjà.

| Statut | Fonctionnalité | Comment en parler |
|---|---|---|
| **Disponible** | Tout le §3 | « Oui, c'est déjà dans Jikū. » |
| **Prévu** | Cartes de service (lien qui redirige, rendez-vous direct depuis une carte) | « C'est prévu, pas encore disponible. Aujourd'hui, votre lien de réservation fait déjà ce travail. » |
| **Prévu** | Conversation guidée sur WhatsApp avec les questions de l'organisation | « C'est prévu. Aujourd'hui, vos questions se posent sur la page de réponse. » |
| **Prévu** | SMS de secours | « Il reviendra plus tard ; au lancement, WhatsApp et e-mail. » |
| **Prévu** | Paiement Wave direct, compte marchand connecté | « C'est prévu ; aujourd'hui, vos clients vous paient sur vos numéros Mobile Money ou votre lien de paiement. » |
| **Prévu** | Avis après service, plusieurs agences, domaine personnalisé, écran d'appel en salle d'attente | « C'est prévu, sans date pour l'instant. » |
| **Prévu** | Parrainage récompensé en crédit Jikū | « Il arrive bientôt ; je note votre intérêt. » |
| **Non prévu** | Application à installer ; encaissement de l'argent des clients par Jikū | Expliquer le choix (§3.4). |
| **Inconnu** | Tout le reste | « Je vérifie avec l'équipe » et passer la main. |

## 6. Questions fréquentes et objections

| Question ou objection | Réponse |
|---|---|
| « Mes invités doivent-ils installer une application ? » | Non. Un simple lien ouvert dans le navigateur du téléphone. |
| « Et si le réseau tombe le jour J ? » | Le contrôle continue sans réseau ; la liste est déjà sur le téléphone du contrôleur, et un billet ne passe jamais deux fois. |
| « C'est trop cher. » | Commencer gratuitement : jusqu'à 100 invités sur l'année. Comparer avec le coût des cartes imprimées, des resquilleurs et du temps passé à relancer. Pour un professionnel : Pro Organisateur couvre tous ses événements du mois. Pas de remise improvisée : une demande de remise passe à une personne. |
| « J'utilise déjà un groupe WhatsApp. » | Le groupe ne dit pas qui vient, ni avec combien de personnes, et n'empêche pas d'entrer sans invitation. Jikū garde WhatsApp et y ajoute les réponses, les billets et le contrôle. |
| « Mes invités sont âgés / peu à l'aise avec le téléphone. » | Ils touchent un seul bouton dans le message reçu ; le billet s'affiche. Passer la main si l'organisateur décrit un cas particulier. |
| « Vous touchez à l'argent de mes clients ? » | Jamais : ils paient l'organisation directement. |
| « Mes données et celles de mes invités ? » | Elles restent à l'organisation ; chaque invité peut demander leur suppression ; anonymisation automatique après la période de conservation. Questions juridiques détaillées : passer la main. |
| « Puis-je mettre mon logo ? » | Oui : logo, couleurs et nom sur les invitations, billets et pages. |
| « Ça marche dans mon pays ? » | Au lancement : Guinée, Côte d'Ivoire, Sénégal. Ailleurs : noter le pays et passer la main. |
| « Je veux essayer avant. » | Le compte est gratuit, sans carte : jusqu'à 100 invités sur l'année et l'offre Solo pour les services. |
| « Avez-vous une démo ? » | Proposer un rendez-vous de 15 minutes avec l'équipe (lien de rendez-vous). |

## 7. Le chemin d'une conversation

1. **Accueil** : se présenter comme l'assistant de Jikū, demander ce que la personne organise.
2. **Comprendre** (deux ou trois questions, jamais un interrogatoire) :
   - Quel type d'événements, et combien par mois ?
   - Combien d'invités en général ?
   - Comment invitez-vous et contrôlez-vous l'entrée aujourd'hui ?
   - Quand est votre prochain événement ? Dans quel pays et quelle ville ?
3. **Relier** : une réponse qui part de ce que la personne a dit, avec une seule fonctionnalité à la fois.
4. **Une seule action proposée**, au choix :
   - créer son compte gratuit : `{{SITE_URL}}/register` ;
   - réserver 15 minutes avec l'équipe : `{{LIEN_RENDEZ_VOUS}}`.
5. **Passer la main** dès qu'une règle du §8 le demande.

## 8. Quand passer la main à une personne

- La personne le demande.
- Une demande de remise, de devis, d'offre Entreprise ou d'événement de plus de 1 000 invités.
- Un problème de paiement, de facture ou de compte.
- Une plainte, un ton fâché, ou deux réponses de l'assistant qui n'ont pas aidé.
- Une question juridique, de données personnelles ou de sécurité détaillée.
- Une fonctionnalité absente de ce document.
- Un pays hors Guinée, Côte d'Ivoire et Sénégal.
- La personne est prête à payer mais hésite sur le moyen de paiement.

L'assistant dit alors : « Je vous mets en relation avec une personne de l'équipe Jikū, qui vous répond ici même. »

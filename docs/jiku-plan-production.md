# Jikū — Ce qu'il manque pour la production, et le plan de travail

**Date :** 2026-09-24, état et chronogramme mis à jour le 2026-09-27
**Référence :** ADR 104 (ticket, paiement, canaux, tarification), ADR 106 (cartes partageables).

Ce document répond à une question : **que faut-il encore faire pour que le
périmètre de Jikū soit complet et prêt pour la production ?** Il distingue ce
qui existe, ce qui manque, et l'ordre dans lequel le construire.

## 1. Le périmètre de lancement

Jikū est prêt pour la production quand ces parcours fonctionnent de bout en
bout, sur un hébergement toujours actif, avec de vrais paiements :

| Parcours | Acteurs |
|---|---|
| Inviter, faire confirmer, scanner (y compris hors-ligne) | Organisation, client, opérateur |
| Prendre rendez-vous, arriver, être servi | Client, opérateur |
| Prendre un ticket sans rendez-vous, suivre son rang, être appelé | Client, opérateur |
| Vendre des billets payés sur le compte de l'organisation, après vérification de l'organisateur | Organisation, client |
| Payer Jikū (abonnement, événement, commission, SMS) en Mobile Money ou carte | Organisation, équipe Jikū |

Après le lancement : avis après service, agences multiples, domaine
personnalisé, questionnaire d'orientation, écran d'appel, compte marchand
connecté (niveau 3), Wave en direct.

## 2. Ce qui manque

État vérifié dans le code le 2026-09-27. Les phases 0 à 6 du plan ci-dessous
sont construites pour le produit, vente publique de billets comprise ; il reste
la mise en production (§2.2). Dans la phase 7 (cartes partageables, ADR 106),
l'invitation ouverte est en production depuis le 2026-09-27 ; la suite est
décrite dans `docs/backlog/phase-7-suite.md` et datée au §5.

### 2.1 Produit

| Élément | État | Où |
|---|---|---|
| Invitations, RSVP, transfert, check-in hors-ligne | ✅ Prêt | modules `invitation`, `checkin` |
| Rendez-vous sur créneau | ✅ Prêt | `catalog/SlotEngine`, ADR 85 |
| File du jour côté personnel, sans-rendez-vous | ✅ Prêt | console de ligne (organisateur, lien comptoir, lien opérateur) |
| Ticket pris par le client (QR à l'entrée) et suivi du rang en direct | ✅ Prêt | JIKU-113, JIKU-158 |
| « C'est votre tour » par message, numéro de guichet | ✅ Prêt | JIKU-114, `ClientCalledListener` |
| Encaisser les revenus de Jikū | ✅ Prêt | CinetPay et virement manuel pour chaque achat (JIKU-106, JIKU-164, JIKU-165) ; clés de production à poser |
| Sélecteur de prestataires | ✅ Prêt | `PaymentProviderSelector`, `MessagingProviderResolver` |
| Prix et règle de paiement sur le ticket, statut « payé » | ✅ Prêt | JIKU-108, JIKU-110, JIKU-161 |
| Moyens de paiement de l'organisation | ✅ Prêt | JIKU-109, JIKU-160 |
| Canal SMS (Nimba) et repli WhatsApp → SMS | ✅ Prêt | `NimbaSmsSender`, JIKU-112 (rappels et file) |
| Opérateur avec périmètre, équipe, console unique | ✅ Prêt | JIKU-116, JIKU-162, JIKU-163 |
| Plusieurs monnaies (GNF, FCFA, USD) | ✅ Prêt | ADR 105, JIKU-137, JIKU-138 |
| Votre propre numéro WhatsApp (Embedded Signup) | ✅ Prêt | JIKU-154, JIKU-155, JIKU-156 |
| Séances collectives (plusieurs clients par créneau) | ✅ Prêt | JIKU-174 : Solo 1, Teams 10, Organisation 30 |
| Vérification des organisateurs | ✅ Prêt | JIKU-175, JIKU-176 : personnelle ou entreprise, documents sur R2, file de revue, badge public |
| Vente publique de billets (commande, page d'achat) | ✅ Prêt | JIKU-177 : places gardées, paiement déclaré puis confirmé, onglet Commandes |
| Commission de 3 % par tranche | ✅ Prêt | JIKU-178 : tranche offerte, à crédit, jamais de pause le jour J, avoir de 12 mois |
| Traduction anglaise de tous les écrans | ✅ Prêt | JIKU-166, JIKU-167, JIKU-172, JIKU-173 |
| Invitation ouverte (web et WhatsApp), date limite, annulation annoncée | ✅ Prêt | JIKU-184 à JIKU-187 ; numéro WhatsApp des cartes à créer |

### 2.2 Mise en production

| Élément | État | Constat |
|---|---|---|
| Hébergement toujours actif | ❌ | offre payante Render (≈ 25 USD / mois) ou runbook Hetzner (`docs/deploy.md`) |
| Domaine de marque | ❌ | web, API et e-mails encore sur `mrvin100.de` ; SPF et DKIM à refaire pour Resend et Brevo |
| Branche `main` et mise en production | ✅ | `main` partage l'historique de `develop` depuis la version du 2026-09-26 ; une version est une PR `develop` → `main` fusionnée par commit de fusion |
| Moyen de paiement sur le compte WhatsApp Business | ❌ | exigé par Meta avant le 30 septembre 2026, sinon l'envoi s'arrête |
| Statut Tech Provider Meta, modèles de messages approuvés | ⏳ | nécessaire pour « votre propre numéro » et les invitations interactives |
| Compte marchand CinetPay en production | ⏳ | clés, URL de notification, délai de reversement à négocier |
| Secrets de production (constat F-3) | ⏳ | à poser et vérifier avant la bascule |
| Scan des vulnérabilités en CI (constat F-6) | ✅ | Trivy sur le jar du backend, `pnpm audit` sur le web (JIKU-180) |
| Validation de la revue de sécurité | ⏳ | case non cochée |
| Recette utilisateur (UAT) | ⏳ | plan rédigé, validations non cochées |
| Identité légale sur les factures | ❌ | `BILLING_SELLER_NAME`, adresse et identifiant fiscal vides ; entreprise à immatriculer |
| CGU, CGV, mentions légales | 🟡 | pages publiées avec des champs d'entreprise à compléter (JIKU-135) ; relecture juridique à faire |
| Sauvegardes et restauration | ✅ | répétition de restauration faite (`docs/backup.md`) |
| Suivi des erreurs et disponibilité | ✅ | Sentry branché, runbook de disponibilité |

## 3. Plan de travail

Chaque tranche est une PR par dépôt concerné, avec sa story `JIKU-<n>`. Les
tranches d'une même phase peuvent avancer en parallèle ; les phases, dans
l'ordre.

### Phase 0 — Fondations de production (bloquant)

| Tranche | Dépôt | Contenu |
|---|---|---|
| 0.1 | infra | Hébergement toujours actif de l'API (offre payante Render, ou le runbook Hetzner déjà écrit) |
| 0.2 | infra | Domaine de marque pour le web, l'API et l'envoi d'e-mails (SPF, DKIM chez Resend et Brevo) |
| 0.3 | app, web | Création de `main`, règle « `develop` → `main` = mise en production », vérification post-déploiement active |
| 0.4 | app | Scan des vulnérabilités en CI (constat F-6), secrets de production posés (F-3), revue de sécurité validée |
| 0.5 | app | Identité légale du vendeur et taux de taxe dans la configuration de facturation ; CGU et CGV publiées côté web |

### Phase 1 — Encaisser les revenus de Jikū

| Tranche | Dépôt | Contenu |
|---|---|---|
| 1.1 | app | Sélecteur de paiement : registre d'adaptateurs, choix par configuration, l'adaptateur de test reste disponible |
| 1.2 | app | Adaptateur CinetPay : initiation, vérification de la signature du callback, contrôle du statut auprès du prestataire |
| 1.3 | web | Écran de paiement unique : montant, boutons Orange Money / MTN / Carte, suivi jusqu'à confirmation |
| 1.4 | app | Sélecteur de messages : `MessagingProviderResolver` ramené à un choix parmi des adaptateurs enregistrés |
| 1.5 | app, web | Monnaies : grille de prix par monnaie, monnaie fixée par le pays de l'organisation, factures dans cette monnaie (ADR 104, §9) |

### Phase 2 — Les organisations se font payer (niveaux 1 et 2)

| Tranche | Dépôt | Contenu |
|---|---|---|
| 2.1 | app | Prix et règle de paiement (Gratuit / Avant / Après service) sur `TicketType` et `Service`, migration additive |
| 2.2 | app, web | Réglages « moyens de paiement » de l'organisation : coordonnées Mobile Money, lien de paiement |
| 2.3 | app, web | Statut de paiement du ticket ; action « payé » (ou « payé en espèces ») dans les consoles de scan et de file ; ticket émis seulement après paiement quand la règle est « Avant » |
| 2.4 | app, web | Paiement du palier d'un événement en une fois, au moment de l'action : retrait de l'acompte de 30 % et de sa grille de remboursement (simulateur, parcours `/reserver`, module `booking`) |
| 2.5 | app, web | Séances collectives : plusieurs clients par ressource et par créneau, plafond selon l'offre (Solo 1, Teams 10, Organisation 30, Entreprise sur devis), proposition de créer un événement au-delà |
| 2.6 | web | Refonte du simulateur (référentiel métier, §11) |

### Phase 3 — SMS et repli

| Tranche | Dépôt | Contenu |
|---|---|---|
| 3.1 | app | Canal SMS, adaptateur Nimba SMS, suivi du coût par envoi (même mécanisme que WhatsApp) |
| 3.2 | app, web | Repli WhatsApp → SMS dans le sélecteur ; option SMS proposée avec son prix exact au moment de l'envoi |

### Phase 4 — Opérateur

| Tranche | Dépôt | Contenu |
|---|---|---|
| 4.1 | app | Modèle Opérateur : périmètre services ou événements, actions autorisées ; reprise des liens validateur et de `ServiceStaff` |
| 4.2 | web | Gestion de l'équipe, et une console opérateur unique (scan, file, encaissement) limitée à son périmètre |

### Phase 5 — File d'attente complète

| Tranche | Dépôt | Contenu |
|---|---|---|
| 5.1 | app, web | Prise de ticket par le client via un QR affiché à l'entrée, sans compte |
| 5.2 | app, web | Page du ticket avec le rang et l'estimation d'attente, mise à jour en direct |
| 5.3 | app | Message « vous êtes le prochain » (WhatsApp, puis SMS) |
| 5.4 | app, web | Numéro de poste à l'appel (« présentez-vous au guichet 4 ») |

### Phase 6 — Vente publique de billets

| Tranche | Dépôt | Contenu |
|---|---|---|
| 6.0 | app, web | Vérification des organisateurs (JIKU-175) : personnelle ou entreprise, obligatoire dès qu'un paiement intervient, facultative sinon ; documents dans un stockage privé (Cloudflare R2), validation dans le back-office, badge et mentions sur les pages publiques |
| 6.1 | app | Commande (JIKU-177) : quantités par catégorie, places prises d'un coup sous les deux jauges (événement et catégorie), au plus 10 billets par commande. Le client déclare son paiement (référence Mobile Money), l'organisation confirme (billets émis au nom de l'acheteur, transférables) ou refuse avec un motif. Une commande non déclarée rend ses places après le délai choisi par l'organisation (30 min par défaut, de 10 min à 72 h) ; une commande déclarée n'expire plus |
| 6.2 | web | Page publique de l'événement et parcours d'achat, paiement aux niveaux 1 et 2 |
| 6.3 | app, web | Commission (JIKU-178) : 3 % du prix, payée par tranche de 50 billets d'une catégorie avant la vente, jamais plus que ses places restantes. Première tranche offerte, une tranche à crédit (réglée avec le paiement suivant), vente en pause quand les tranches sont épuisées sauf le jour de l'événement (le dépassement est dû après). À la clôture, la part payée non consommée devient un avoir de 12 mois déduit des paiements de commission suivants |

### Phase 7 — Cartes partageables (ADR 106)

| Tranche | Dépôt | Contenu |
|---|---|---|
| 7.0 | app | ADR 106 : les couches (distribution, métiers, socle ticket), le moteur de cartes, les étapes et leurs feux verts (JIKU-183) |
| 7.1 | app | Invitation ouverte, parcours web : code court public, réponse Je viens / Peut-être / Non avec accompagnants, une réponse par numéro, billet QR pour chaque « Je viens » sous la jauge, « Je viens » et accompagnants comptés dans le palier, décompte et liste pour l'organisateur (JIKU-184) |
| 7.2 | app | Invitation ouverte, parcours WhatsApp sur un numéro Jikū dédié : message avec le code, boutons, accompagnants, billet dans la discussion (JIKU-185) |
| 7.3 | web | Onglet invitation ouverte (réglages, décompte en direct, liste), partage WhatsApp, carte téléchargeable avec QR, page publique avec aperçu de lien (JIKU-186) |
| 7.4 | app, web | Invitation ouverte : date limite affichée, annulation annoncée par WhatsApp (incluse en payant, au choix en gratuit) (JIKU-187) |
| 7.5 | web | Allègement visuel : formats courts, icônes plutôt qu'étiquettes, aide à la demande, budget de texte vérifié en CI (JIKU-188) |
| 7.6 | app, web | Cartes de service, redirection puis rendez-vous direct, pour les organisations vérifiées (JIKU-189, JIKU-190) |
| 7.7 | app, web | Conversation guidée sur le numéro de l'organisation : questions à choix, créneau, ticket dans la discussion (JIKU-191, JIKU-192) |
| 7.8 | app, web | Mesure des cartes, quota de conversations, relances aux prospects (JIKU-193) |
| 7.9 | app, web | API Jikū pour les intégrateurs : clés, notifications, facturation à l'usage, première intégration de démonstration (après feu vert, ADR 106 §6) |

### Après le lancement

Compte marchand connecté (niveau 3), Wave en direct, K-PAY en secours, avis
après service, agences multiples, domaine personnalisé, questionnaire
d'orientation, écran d'appel.

## 4. Critères de fin communs à chaque tranche

- Tests qui couvrent les critères d'acceptation, y compris l'isolation entre
  organisations et les cas de concurrence (paiement confirmé deux fois, dernier
  billet acheté en même temps).
- `./gradlew ktlintCheck`, `ModularityTests`, `./gradlew build` et
  `koverVerify` (70 %) au vert ; contrat OpenAPI régénéré.
- `pnpm lint` et `pnpm build` au vert ; textes en français et en anglais.
- Parcours ajouté à la suite E2E (`@smoke` pour les parcours porteurs).
- Nouvelles variables d'environnement documentées dans `.env.example`.
- Aucun nom de prestataire dans le code métier : uniquement dans son
  adaptateur et sa configuration.

## 5. Chronogramme

Semaines de 2026 (lundi au dimanche). Une date n'est un engagement que pour la
ligne « Maintenant » ; les suivantes dépendent des feux verts et des décisions
marquées « à discuter ».

| Période | Semaines | Contenu | Condition pour démarrer | État |
|---|---|---|---|---|
| Maintenant | S40 (28 sept. → 4 oct.) | Prérequis de production urgents : moyen de paiement WhatsApp Business (**avant le 30 sept.**), numéro WhatsApp des cartes, R2, instance Render 1 Go | — | À faire (équipe Jikū) |
| Maintenant | S40 → S41 | JIKU-188 allègement visuel sur les 6 écrans prioritaires | Décisions §JIKU-188 | À discuter |
| Octobre | S41 → S44 (5 → 1er nov.) | Pilote de l'invitation ouverte : 10 à 20 hôtes, suivi des indicateurs ADR 106 | Numéro des cartes validé chez Meta | Prêt à lancer |
| Octobre | S41 → S42 | JIKU-189 carte de service, redirection ; JIKU-190 rendez-vous direct | — (sans message WhatsApp, ne dépend pas du pilote) | Prêt |
| Fin octobre | S44 | Bilan du pilote, feu vert de l'étape 1 : taux de réponse ≥ 35 %, ≥ 60 % via WhatsApp, coût par réponse sous le plafond | Pilote terminé | À discuter |
| Novembre | S45 → S47 | JIKU-191 questions guidées ; JIKU-192 conversation sur le numéro de l'organisation | Feu vert étape 1, décisions §JIKU-191 et §JIKU-192 | À discuter |
| Novembre | S48 | JIKU-193 mesure, quotas et relances | Grille de prix fixée à partir du pilote | À discuter |
| Décembre | S49 → S52 | Pilote des cartes de service avec 5 à 10 entreprises vérifiées ; feu vert de l'étape 2 | JIKU-189 à JIKU-193 en production | À discuter |
| 2027, T1 | — | Paliers après le lancement : relances d'invitation ouverte (payant), avis après service, écran d'appel, agences multiples | Ordre à fixer selon les retours du pilote | À discuter |
| 2027, T2 → T3 | — | Étape 3 : API Jikū pour les intégrateurs (7.9) | Un ou deux intégrateurs payants, ou une demande entrante réelle | À discuter |

Points à discuter, dans l'ordre où ils bloquent :

1. Décisions de JIKU-188 (heure en français, portée) : début S40.
2. Critères chiffrés du pilote et plafond de coût par réponse : avant S41.
3. Numéro partagé ou non pour la conversation guidée (JIKU-192) : avant S45.
4. Quotas de conversations et prix des relances (JIKU-193) : fin S44, avec les
   coûts réels du pilote.
5. Ordre des paliers après le lancement : en décembre, avec le bilan de l'étape 2.


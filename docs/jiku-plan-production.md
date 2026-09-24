# Jikū — Ce qu'il manque pour la production, et le plan de travail

**Date :** 2026-09-24
**Référence :** ADR 104 (ticket, paiement, canaux, tarification).

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

### 2.1 Produit

| Élément | État | Constat dans le code |
|---|---|---|
| Invitations, RSVP, transfert, check-in hors-ligne | ✅ Prêt | modules `invitation`, `checkin` |
| Rendez-vous sur créneau | ✅ Prêt | `catalog/SlotEngine`, ADR 85 |
| File du jour côté personnel | 🟡 Partiel | `ServiceDayLineController`, `LineStaffController` : suivant, arrivé, appelé, terminé, absent. Le « sans rendez-vous » n'est saisi que par le personnel |
| Ticket pris par le client lui-même (QR à l'entrée) | ❌ Manque | aucune route publique de prise de ticket sans rendez-vous |
| Suivi du rang en direct par le client | ❌ Manque | aucune page publique de position dans la file |
| « Vous êtes le prochain » par message | ❌ Manque | — |
| Encaisser les revenus de Jikū pour de vrai | ❌ Manque | `PaymentProvider` n'a qu'une implémentation de test (`SandboxPaymentProvider`) |
| Sélecteur de prestataires (paiement et messages) | 🟡 Partiel | un seul `PaymentProvider` actif ; `MessagingProviderResolver` construit lui-même Resend et Meta |
| Prix et règle de paiement sur le ticket | ❌ Manque | ni `TicketType` ni `Service` ne portent de prix |
| Moyens de paiement de l'organisation (niveaux 1 et 2) | ❌ Manque | — |
| Canal SMS | ❌ Manque | seuls `EMAIL` et `WHATSAPP` existent dans `TenantProviderSettings` |
| Repli WhatsApp → SMS | ❌ Manque | — |
| Opérateur avec périmètre | 🟡 Partiel | liens validateur par événement, `ServiceStaff` par service, rôles `OWNER` / `ADMIN` |
| Vente publique de billets (commande) | ❌ Manque | aucune notion de commande |
| Facturation de la commission | ❌ Manque | — |
| Plusieurs monnaies | ❌ Manque | `billing.currency` unique (GNF) |

### 2.2 Mise en production

| Élément | État | Constat |
|---|---|---|
| Hébergement toujours actif | ❌ | API sur l'offre gratuite Render, qui s'endort (`docs/deploy.md`) ; les webhooks de paiement et de messages en pâtissent |
| Domaine de marque | ❌ | web, API et e-mails sur `mrvin100.de` |
| Branche `main` et flux de mise en production | ❌ | seule `develop` existe ; `post-deploy.yml` attend des fusions sur `main` |
| Secrets de production (constat F-3) | ⏳ | à poser et vérifier avant la bascule |
| Scan des vulnérabilités du backend en CI (constat F-6) | ❌ | recommandé par la revue de sécurité, pas encore branché |
| Validation de la revue de sécurité | ⏳ | case de validation non cochée |
| Recette utilisateur (UAT) | ⏳ | plan rédigé, validations non cochées |
| Identité légale sur les factures | ❌ | `BILLING_SELLER_NAME`, adresse et identifiant fiscal vides ; taux de taxe de la Guinée non confirmé |
| CGU, CGV, mentions légales | 🟡 | page confidentialité présente ; conditions de vente à rédiger |
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
| 6.0 | app, web | Vérification des organisateurs : légère (obligatoire avant la première vente) et complète (facultative, badge bleu), validation dans le back-office, mentions de responsabilité sur les pages publiques |
| 6.1 | app | Commande : quantités par catégorie, jauge atomique (module `allocation`), expiration des commandes non payées |
| 6.2 | web | Page publique de l'événement et parcours d'achat, paiement aux niveaux 1 et 2 |
| 6.3 | app, web | Commission de 3 % payée par tranche avant la vente (circuit 1), vente en pause quand la tranche est épuisée, avoir pour la part non consommée |

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

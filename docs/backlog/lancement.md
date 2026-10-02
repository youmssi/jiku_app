# Jikū — Lancement : hébergement, envoi, consentement, relance, surveillance

**Date :** 2026-10-02
**Référence :** ADR 107 (hébergement unique et envoi), plan de production §2.2.

Ces stories rendent Jikū exploitable en production sur un seul serveur, sans
attendre de décision produit. Une story est une PR par dépôt concerné.

## Vue d'ensemble

| Story | Titre | Dépôt | Dépend de |
|---|---|---|---|
| JIKU-199 | ADR 107 et ce plan | app | — |
| JIKU-200 | E-mails par useSend, webhook de retours, seuils abaissés | app | JIKU-199 |
| JIKU-201 | Consentement marketing à l'inscription et sur le formulaire prospect | app, web | — |
| JIKU-202 | Liste des organisateurs à relancer et entonnoir d'activation | app, web | JIKU-201 |
| JIKU-203 | Déploiement sur le serveur : Compose, images, WAL-G, runbooks | app, web | JIKU-199 |
| JIKU-204 | OpenTelemetry, Umami et alerte de dépenses | app, web | JIKU-203 |
| JIKU-205 | Test de charge du jour J | app | — |
| JIKU-206 | SMS dans l'alphabet GSM-7 (un segment au lieu de deux ou trois) | app | — |
| JIKU-207 | Pause des e-mails d'une organisation dont la liste rebondit | app | JIKU-200 |
| JIKU-209 | Santé WhatsApp : modèles, numéros et compte suivis depuis Meta | app | — |

---

## JIKU-200 — E-mails par useSend, webhook de retours, seuils abaissés

**Pourquoi.** Les offres gratuites de Resend et Brevo plafonnent à 400 e-mails
par jour. useSend envoie par AWS SES (0,10 $ les 1 000) et prend en charge les
files, les nouvelles tentatives et la liste d'exclusion.

**Comportement.**

| Où | Après |
|---|---|
| `MAIL_TRANSPORT=usesend` | Envoi par l'API `POST /v1/emails` de useSend ; `USESEND_BASE_URL` pointe vers le cloud ou une instance auto-hébergée |
| `POST /api/v1/notifications/email-feedback/usesend` | Reçoit `email.bounced` et `email.complained`, vérifie la signature, alimente la réputation |
| Seuils par défaut | Rebonds 2 %, plaintes 0,08 % |

**Critères d'acceptation.**

- [ ] Un e-mail avec pièce jointe (billet `.ics`) part avec le bon expéditeur, destinataire, objet et contenu
- [ ] Une erreur 4xx ou 5xx de useSend, ou un délai dépassé, lève `EmailDeliveryException` (nouvelle tentative)
- [ ] Une clé absente empêche le démarrage avec un message clair
- [ ] Le webhook refuse une signature fausse, absente ou plus vieille que 5 minutes (401)
- [ ] `Permanent` et `Undetermined` comptent comme rebond définitif, `Transient` comme rebond temporaire
- [ ] Les autres événements, et l'événement de test, sont acceptés sans effet
- [ ] Les nouvelles variables sont dans `.env.example`

**Hors périmètre.** Pause automatique d'une organisation dont la liste rebondit :
JIKU-207.

---

## JIKU-201 — Consentement marketing

**Pourquoi.** Le marketing (Plunk) n'est pas activé au lancement, mais une liste
ne peut servir que si le consentement a été recueilli et peut être prouvé.

**Comportement.**

| Où | Après |
|---|---|
| Inscription par e-mail | Case non cochée « Recevoir les nouveautés et conseils de Jikū » |
| Formulaire prospect | Même case |
| Réglages du compte | Interrupteur pour donner ou retirer son accord |
| Inscription Google | Pas de case : consentement absent, modifiable dans les réglages |

**Données.** Sur le compte et sur la piste prospect : accord (oui ou non), date,
version du texte accepté, source (`signup`, `prospect`, `settings`).

**Critères d'acceptation.**

- [ ] Sans case cochée, aucun accord n'est enregistré
- [ ] Case cochée : accord, date, version du texte et source enregistrés
- [ ] Retrait dans les réglages : accord à non, date du retrait enregistrée
- [ ] Textes en français et en anglais
- [ ] La politique de confidentialité mentionne l'accord et son retrait

---

## JIKU-202 — Liste des organisateurs à relancer et entonnoir d'activation

**Pourquoi.** Au lancement, la relance se fait à la main, par WhatsApp. Le
back-office doit montrer qui relancer et à quelle étape les organisateurs
décrochent.

**Comportement.** Nouvel écran du back-office, « Relances » :

| Motif | Règle |
|---|---|
| Sans événement | Organisation créée il y a plus de 48 h, aucun événement ni service |
| Sans invités | Premier événement créé il y a plus de 48 h, aucun invité dans l'organisation |
| Vérification refusée | Dernière vérification refusée, à soumettre de nouveau |
| Essai qui se termine | Essai actif qui se termine dans 3 jours ou moins, aucun paiement |

Une vérification commencée mais non soumise n'est pas enregistrée par le
produit ; c'est donc le refus qui déclenche la relance.

Chaque ligne : organisation, propriétaire, téléphone et e-mail, motif, date,
accord marketing. Une relance peut être marquée « faite » (avec la date).

L'entonnoir des 30 derniers jours : inscriptions → premier événement ou service
→ premier envoi → premier paiement.

**Critères d'acceptation.**

- [ ] Chaque motif a son test ; une organisation qui avance sort de la liste
- [ ] Une relance marquée « faite » disparaît pendant 7 jours
- [ ] Réservé aux administrateurs de la plateforme
- [ ] Textes en français et en anglais

---

## JIKU-203 — Déploiement sur le serveur

**Contenu.**

- `docker-compose.vps.yml` (dépôt app) : API, web, PostgreSQL avec WAL-G,
  Umami ; limites de mémoire ; réseau interne, seul le proxy est exposé.
- Workflows GitHub Actions qui publient les images de l'API et du web sur GHCR
  à chaque fusion sur `main`.
- Runbooks : préparation du serveur (Tailscale, pare-feu, Dokploy, Cloudflare),
  migration depuis Neon, restauration à une minute donnée, incident et retour
  arrière.

**Critères d'acceptation.**

- [ ] `docker compose -f docker-compose.vps.yml config` est valide
- [ ] Les images se construisent en CI
- [ ] Le runbook de restauration a été déroulé une fois, la durée est notée

---

## JIKU-204 — OpenTelemetry, Umami et alerte de dépenses

- **API :** module OpenTelemetry de Spring Boot 4 ; export désactivé tant que
  `OTEL_EXPORTER_OTLP_ENDPOINT` n'est pas défini.
- **Web :** script Umami chargé seulement si `NEXT_PUBLIC_UMAMI_WEBSITE_ID` et
  `NEXT_PUBLIC_UMAMI_SRC` sont définis.
- **Dépenses :** chaque matin, si le coût WhatsApp et SMS de la veille dépasse
  `MESSAGING_DAILY_SPEND_ALERT_USD`, un e-mail part à l'adresse d'alerte.

---

## JIKU-205 — Test de charge du jour J

Un script k6 simule l'entrée d'un événement : 500 scans en 5 minutes par
10 validateurs, plus 200 invités qui ouvrent leur billet. Seuils : 95 % des
réponses sous 500 ms, moins de 1 % d'erreurs. Documenté dans
`docs/runbooks/load-test.md`, à lancer sur l'environnement de recette avant le
pilote.

---

## JIKU-207 — Pause des e-mails d'une organisation dont la liste rebondit

**Pourquoi.** SES juge le compte entier : une liste mal tenue peut bloquer les
invitations de toutes les organisations.

**Comportement.** Quand, sur la fenêtre glissante (24 h), une organisation a
envoyé au moins `NOTIFICATION_TENANT_PAUSE_MIN_SAMPLE` e-mails (50) par
l'expéditeur de la plateforme et que ses rebonds définitifs dépassent
`NOTIFICATION_TENANT_PAUSE_BOUNCE_RATE` (4 %), ses e-mails sont enregistrés en
échec avec un motif clair, sans nouvelle tentative. Une alerte part une fois
par jour et par organisation. La pause se lève seule quand les rebonds sortent
de la fenêtre. Une organisation qui envoie par son propre fournisseur n'est pas
concernée.

---

## JIKU-209 — Santé WhatsApp : modèles, numéros et compte

**Pourquoi.** Meta met en pause un modèle mal noté (3 h, puis 6 h, puis
désactivé), le reclasse en marketing, baisse la limite d'un numéro signalé ou
restreint un compte. Sans le savoir, Jikū continuait d'envoyer : chaque message
échouait un par un, le jour de l'événement.

**Comportement.**

| Ce que Meta signale | Ce que fait Jikū |
|---|---|
| Modèle en pause | Le modèle n'est plus utilisé pendant `WHATSAPP_TEMPLATE_PAUSE_HOURS` (3 h) ; les invitations attendent, les rappels « WhatsApp ou SMS » partent par SMS ; alerte |
| Modèle désactivé, refusé, en suppression | Plus utilisé jusqu'à une nouvelle approbation ; alerte |
| Modèle reclassé en marketing | Plus utilisé tant qu'il n'est pas de nouveau utilitaire (`WHATSAPP_BLOCK_MARKETING_TEMPLATES`) ; alerte |
| Qualité d'un modèle jaune ou rouge | Alerte |
| Numéro signalé ou limite abaissée | Alerte |
| Compte en infraction, restreint, banni | Alerte |
| Refus à l'envoi : modèle en pause, compte bloqué, limites de débit | Le message attend au lieu d'être réessayé trois fois en une seconde |

Une alerte part une fois par jour et par sujet. Le webhook WhatsApp existant
doit être abonné aux champs `message_template_status_update`,
`message_template_quality_update`, `template_category_update`,
`phone_number_quality_update` et `account_update`, et
`WHATSAPP_META_BUSINESS_ACCOUNT_ID` doit contenir le compte du numéro de la
plateforme.

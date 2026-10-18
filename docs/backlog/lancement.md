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
| JIKU-210 | Un modèle WhatsApp par usage, signé Jikū, en français et en anglais | app | JIKU-209 |
| JIKU-211 | Statuts de livraison WhatsApp, repli SMS et réputation par organisation | app | JIKU-210 |
| JIKU-212 | Plafonds sur le numéro Jikū : non vérifiés, volume mensuel, liens | app, web | JIKU-211 |
| JIKU-213 | Consentement des invités importés, prouvé et exigé pour WhatsApp | app, web | JIKU-212 |
| JIKU-215 | Envois fiables : files séparées, hors transaction, reprise, tentatives espacées, tâches verrouillées | app | — |
| JIKU-214 | Temps réel : flux SSE « ça a changé » pour le tableau de bord, la ligne du jour et la place du client | app, web | JIKU-216 |
| JIKU-216 | Performance et hygiène : index, cache par organisation, purge, threads virtuels, e-mails de compte en arrière-plan | app | JIKU-215 |
| JIKU-261 | SMS coupés : transport `off`, statut honnête, écrans sans SMS | app, web | — |
| JIKU-262 | Parrainage en crédit Jikū | app, web | pilote de 4 semaines |
| JIKU-263 | ADR 108 et pages légales alignées sur la production | app, web | — |
| JIKU-264 | Conversations commerciales : Chatwoot, n8n et assistant | exploitation | JIKU-263 |
| JIKU-268 | Tarification simplifiée, prix TTC, limite WhatsApp à 3 000 (ADR 109) | app, web | — |
| JIKU-269 | Vérification des messages WhatsApp pendant la rédaction et guide d'écriture | app, web | JIKU-268 |
| JIKU-270 | Tarifs alignés, invités gratuits déduits, marge le jour J, usage raisonnable appliqué, devise par pays | app, web | JIKU-269 |

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

---

## JIKU-210 — Un modèle WhatsApp par usage

**Pourquoi.** Un seul modèle générique (« Message de votre organisateur :
{{1}} ») portait tous les envois. Meta ne relit pas le contenu de la variable :
c'est le premier motif de reclassement en marketing, et une pause de ce modèle
coupait WhatsApp pour toutes les organisations. Le texte parlait aussi au nom de
l'organisation, ce que la règle « un compte par entreprise » de Meta vise.

**Comportement.**

- Six modèles, en français et en anglais, catégorie utilitaire, texte fixe signé
  Jikū (« … vous envoie cette invitation par Jikū ») et pied « Jikū · Répondez
  STOP… » : invitation avec lien, invitation avec boutons, billet avec QR code,
  annulation, rappel de rendez-vous, « c'est votre tour ».
- Chaque message part avec son modèle, dans la langue de l'invité ; une pause ne
  touche plus qu'un usage (JIKU-209).
- `POST /api/v1/admin/whatsapp/templates` crée les modèles dans le compte de la
  plateforme ; `GET` montre ce que Meta en dit. L'Embedded Signup les crée dans
  le compte d'une organisation.
- Une réponse à quelqu'un qui vient d'écrire part toujours en message de session.
- Le texte WhatsApp personnalisé par une organisation (JIKU-91) ne s'applique
  plus aux modèles : il reste utilisé pour les SMS et les messages de session.
- API Graph de Meta passée de v21.0 à v25.0.

---

## JIKU-211 — Statuts de livraison WhatsApp et réputation par organisation

**Pourquoi.** Meta accepte un message tout de suite, puis signale plus tard
qu'il n'a pas pu le livrer. Jikū ne lisait pas ces statuts : le message restait
« envoyé », sans repli. Et rien ne distinguait l'organisation dont les invités
écrivent STOP de celles qui envoient proprement.

**Comportement.**

- Chaque message accepté par Meta est suivi par son identifiant. Un échec
  signalé ensuite est enregistré pour l'organisation, avec le code de Meta ;
  une invitation passe en échec (l'organisateur le voit) ; un rappel
  « WhatsApp ou SMS » part par SMS, une seule fois.
- Un STOP est rattaché à l'organisation dont le message y répond.
- Sur le numéro Jikū, une organisation est mise en pause quand, sur 7 jours et
  après 50 messages, plus de 2 % de ses destinataires ont écrit STOP ou plus de
  20 % de ses messages n'ont pas pu être livrés. Ses envois WhatsApp échouent
  avec ce motif (repli SMS là où il est prévu) et l'équipe reçoit une alerte par
  jour. Une organisation sur son propre numéro n'est pas concernée.

---

## JIKU-212 — Plafonds sur le numéro Jikū

**Pourquoi.** Le numéro Jikū parle au nom de Jikū et sa qualité est partagée.
Une organisation non vérifiée ne doit pas pouvoir l'emprunter pour une campagne,
et une organisation qui envoie beaucoup doit porter sa propre réputation
(décision : option A au lancement, option B au-delà d'un volume).

**Comportement.**

| Règle | Défaut | Au-delà |
|---|---|---|
| Organisation non vérifiée | 50 messages par jour (`WHATSAPP_LIMIT_UNVERIFIED_DAILY`) | Les messages attendent ; motif « Vérifiez votre organisation » |
| Organisation vérifiée | 3 000 messages sur 30 jours (`WHATSAPP_LIMIT_MONTHLY_BEFORE_OWN_NUMBER`, ADR 109) | Les messages attendent ; motif « Connectez votre propre numéro » |

- Une organisation sur son propre numéro n'est pas comptée.
- Les rappels « WhatsApp ou SMS » partent par SMS quand un plafond est atteint.
- L'équipe reçoit une alerte par jour et par plafond.
- Les réglages de l'organisation montrent son usage et ses plafonds.
- Les mots de l'organisation (son nom, le nom de l'événement) perdent toute
  adresse web dans les messages WhatsApp : les seuls liens envoyés sont ceux de Jikū.

---

## JIKU-213 — Consentement des invités importés

**Pourquoi.** Meta exige l'accord d'une personne avant qu'une entreprise lui
écrive la première. Les invités importés viennent du fichier de l'organisation :
Jikū n'avait aucune trace de cet accord.

**Comportement.**

- À l'import, l'organisateur coche « Ces personnes ont accepté de recevoir mes
  invitations » (`consentAttested`). La déclaration est enregistrée comme preuve :
  qui, quand, quel événement, combien d'invités.
- Sans cette déclaration, les invités sont importés mais ne reçoivent pas
  d'invitation WhatsApp depuis le numéro Jikū : l'envoi échoue avec un motif
  clair. L'e-mail reste possible.
- `POST /events/{id}/guests/consent-attestation` déclare l'accord pour les invités
  déjà importés de l'événement.
- Les personnes qui s'inscrivent, réservent ou achètent elles-mêmes sont
  couvertes par leur geste. Les invités importés avant cette story sont
  considérés comme couverts.
- Une organisation sur son propre numéro reste responsable de ses envois
  (conditions générales) et n'est pas bloquée.

---

## JIKU-215 — Envois fiables

**Pourquoi.** Le jour J, un envoi de 300 invitations pouvait retarder le « c'est
votre tour » d'un client, bloquer des connexions à la base pendant les appels à
Meta ou au fournisseur d'e-mails, perdre des messages au-delà de 100 tâches en
attente ou lors d'un redémarrage, et échouer sur une coupure de quelques
secondes.

**Comportement.**

| Avant | Après |
|---|---|
| Un seul pool (2 threads, 100 tâches), refus au-delà | Pool « volume » (8 threads, file sans limite) et pool « urgent » (threads virtuels, 64 à la fois) |
| Envoi dans la transaction : connexion tenue pendant l'appel au fournisseur | Envoi après validation, en tâche de fond, sans transaction |
| Envoi perdu au redémarrage | Une invitation restée PENDING plus de 30 min après sa remise est renvoyée par le balayage |
| 3 tentatives dans la même seconde | Attentes de 2 s puis 8 s ; les messages qui basculent sur SMS réessaient tout de suite |
| Tâches planifiées l'une après l'autre, en double avec plusieurs serveurs | 4 threads ; verrou ShedLock dans PostgreSQL |

---

## JIKU-216 — Performance et hygiène des envois

**Pourquoi.** Chaque message WhatsApp faisait une dizaine de lectures, dont six
comptages sur des tables sans index adapté et qui grossissent sans fin. Elles
gardent aussi adresses et numéros des destinataires sans limite de durée. Les
e-mails de compte partaient pendant la requête.

**Comportement.**

| Avant | Après |
|---|---|
| Comptages par organisation sur `notification_log` et `email_feedback` sans index composite | Index `(tenant_id, status, channel, created_at)`, `(created_at)`, `(recipient, created_at)` ; `email_feedback (tenant_id, feedback_type, created_at)` et `(recipient, feedback_type)` |
| Pause e-mail, pause WhatsApp et vérification relues à chaque envoi | Gardées une minute par organisation (`MESSAGING_CHECK_CACHE_TTL`) ; les compteurs de limites restent exacts |
| Journaux d'envoi conservés sans limite | Purge nocturne au-delà de 180 jours (`MESSAGING_RETENTION_DAYS`), par lots ; restent les rebonds définitifs, les plaintes, les coûts, les STOP et le premier envoi de chaque organisation |
| Un thread Tomcat bloqué par requête qui attend | Threads virtuels (`VIRTUAL_THREADS_ENABLED`) ; le pool de connexions reste la limite |
| Réinitialisation du mot de passe, invitation d'un membre, avis d'essai, d'abonnement et de paiement envoyés pendant la requête | En arrière-plan après validation ; rien n'est envoyé si la transaction est annulée ; les alertes partent quand même |

**Critères d'acceptation.**

- [ ] 300 envois d'une organisation ne relisent sa pause qu'une fois par minute
- [ ] La purge garde le premier envoi réussi de chaque organisation, les rebonds définitifs et les plaintes
- [ ] Un e-mail de compte part après la validation, hors du thread de la requête, et pas en cas d'annulation
- [ ] Une alerte d'exploitation part même si sa transaction est annulée
- [ ] `scripts/load/send-rush.js` : 1 000 invitations envoyées en 5 minutes, écrans organisateur sous 800 ms (95e centile)

---

## JIKU-214 — Temps réel

**Pourquoi.** Le tableau de bord se rechargeait toutes les 7 s, la console de
ligne toutes les 10 s, la page du client toutes les 15 s. Un client appelé
pouvait l'apprendre 15 s trop tard, et chaque écran ouvert interrogeait l'API
même quand rien ne bougeait.

**Comportement.**

| Où | Après |
|---|---|
| `POST /events/{id}/dashboard/live`, `/services/{id}/day-line/live`, `/line/{token}/live`, `/operator/{token}/services/{id}/live`, `/r/{code}/line/{ticket}/live`, `/appointments/{token}/line/{ticket}/live` | Un ticket signé (12 h) pour suivre ce sujet, après les contrôles habituels de l'écran |
| `GET /live/stream?ticket=…` | Flux SSE : `ready` à l'ouverture, `change` quand le sujet a changé ; aucune donnée, l'écran relit par son endpoint habituel |
| Écritures | Toute écriture validée d'un invité, d'une invitation, d'une réponse à la carte, d'une commande, d'un billet ou d'une réservation prévient ses sujets ; les mises à jour atomiques (check-in, appel, paiement) aussi |
| Navigateur | Un seul `EventSource` par sujet et par page, fermé quand l'onglet est caché ; relecture au plus une fois par seconde ; sans flux, l'ancien rythme reprend ; avec, une relecture de sécurité par minute |
| Proxy | Caddy ne compresse ni ne met en tampon `/live/stream` ; battement toutes les 25 s pour Cloudflare |

**Critères d'acceptation.**

- [ ] Le tableau de bord entend l'ajout d'un invité à son événement
- [ ] Un client qui suit sa place entend le guichet appeler le suivant
- [ ] Une autre organisation n'obtient pas de ticket pour un événement qui n'est pas le sien (404)
- [ ] Un jeton de session ou un ticket forgé n'ouvre pas de flux (401)
- [ ] Un ticket hors de la ligne du jour n'obtient pas de ticket live (404)

**Limite.** Le hub est en mémoire : avec plusieurs instances de l'API, il faudra
relayer les signaux par PostgreSQL `LISTEN/NOTIFY`.

---

## JIKU-261 — SMS coupés

**Pourquoi.** Au lancement, aucun SMS (ADR 108) : les écrans ne doivent pas en
promettre.

**Critères d'acceptation.**

- [x] `SMS_TRANSPORT=off` : aucun SMS n'est tenté ; avec WHATSAPP_OR_SMS, le résultat WhatsApp est gardé
- [x] Un message dont le canal est SMS seul est enregistré FAILED : « Not sent: SMS is turned off on this platform »
- [x] `GET /messaging/channels` renvoie `{ sms: false }` ; les rappels d'un service n'offrent plus le repli SMS ; la vérification du téléphone parle de WhatsApp seul
- [x] Les fichiers de déploiement utilisent `off` par défaut

## JIKU-262 — Parrainage en crédit Jikū

**Pourquoi.** Faire venir des clients par le bouche-à-oreille sans transformer
Jikū en plateforme « où l'on gagne de l'argent » (ADR 108, décision 6).

**Avant de construire.** Un pilote de 4 semaines avec 5 parrains, codes suivis
à la main. On construit si au moins 2 parrains apportent des clients payants.

**Critères d'acceptation.**

- [ ] Chaque organisation a un lien et un code de parrainage ; le premier lien utilisé compte pendant 60 jours
- [ ] Le parrain reçoit un mois du plan du filleul quand celui-ci paie son deuxième mois ; le filleul, un mois après son premier paiement
- [ ] Paliers : un mois bonus à 5 parrainages, statut Ambassadeur à 10
- [ ] Le crédit paie l'abonnement ou des options, peut être offert à une autre organisation, ne se convertit pas en argent, expire après 12 mois
- [ ] Plafonds : 2 mois du plan du filleul par client acquis, 12 mois par parrain et par an
- [ ] Pas d'auto-parrainage (même téléphone ou même moyen de paiement)
- [ ] Le parrain voit ses filleuls (nom de l'organisation et étape, jamais leurs coordonnées) et peut demander à Jikū une relance neutre, une fois par semaine au plus
- [ ] Le bureau d'administration voit les crédits émis, utilisés et expirés, et peut suspendre un parrain

## JIKU-263 — ADR 108 et pages légales

**Critères d'acceptation.**

- [x] ADR 108 : serveur CX43, conversations commerciales, trois numéros, SMS coupés, budget, parrainage en crédit
- [x] Mentions légales et politique de confidentialité : Hetzner, Cloudflare, useSend sur Amazon SES, Meta, Nimba (si activé), CinetPay, Sentry, Umami, Chatwoot, n8n, Anthropic, Cal.com
- [ ] Relecture juridique

## JIKU-264 — Conversations commerciales

**Critères d'acceptation.**

- [ ] Chatwoot (avec sa propre base), Redis et n8n déployés par Dokploy avec les limites de mémoire de l'ADR 108
- [ ] Le numéro « conversations » est relié à Chatwoot par l'API officielle de Meta
- [ ] L'assistant (Claude Haiku 5.5) se présente comme automatique, répond depuis une base de réponses validées, propose un rendez-vous Cal.com et confie la conversation à une personne sur demande, sur une hésitation de prix ou hors de la base
- [ ] Plafond de dépense de l'API à 15 $ par mois
- [x] Base de connaissances et consignes de l'assistant (`docs/sales/`) : cible du lancement (organisateurs professionnels en Guinée, Côte d'Ivoire et Sénégal, expatriés en sous-segment), prix, feuille de route sans date, objections, règles de passage de main
- [ ] Évaluation sur 40 à 50 vraies conversations : taux de rendez-vous, reprises par une personne, erreurs ; décision de garder Haiku ou de passer à Sonnet

## JIKU-268 — Tarification simplifiée

**Pourquoi.** Un prix qu'un organisateur comprend en une ligne et recopie dans son
devis (ADR 109).

**Critères d'acceptation.**

- [x] Un événement achète un nombre d'invités à 750 GNF / 50 FCFA / 0,08 USD chacun, tout inclus ; en ajouter ne fait payer que les nouveaux
- [x] Pro Organisateur à 600 000 GNF / 40 000 FCFA / 70 USD par mois, 1 000 invités, puis 600 GNF par invité ; numéro WhatsApp propre inclus
- [x] Services Pro à 60 000 GNF / 4 000 FCFA / 7 USD par personne et par mois, groupes jusqu'à 30 ; Solo reste gratuit
- [x] Paliers, supplément interactif et formules Solo Plus, Teams et Organisation supprimés, données migrées
- [x] La facture extrait la TVA de 18 % (GN, CI, SN) du prix payé
- [x] Une organisation vérifiée envoie jusqu'à 3 000 messages sur 30 jours sur le numéro Jikū
- [x] Bureau d'administration : prix par invité et formules modifiables ; un essai prête un nombre d'invités
- [x] Web : calculateur à une question, pages publiques, facturation, CGU (usage raisonnable des rappels), guides
- [x] Base de connaissances commerciale à jour

## JIKU-269 — Vérifier un message WhatsApp pendant la rédaction

**Pourquoi.** Meta facture plus cher et limite un message classé marketing ;
l'organisateur doit le savoir en écrivant, pas à l'envoi (ADR 109, décision 4).

**Critères d'acceptation.**

- [x] `POST /messaging/whatsapp/check` renvoie la catégorie et les mots repérés, avec la même liste que le contrôle à l'envoi (mots français et anglais)
- [x] Les champs de message WhatsApp affichent « Message d'information » ou « Risque marketing » et les mots à retirer
- [x] Un guide « Écrire un message WhatsApp qui passe » (FR et EN) dans /docs
- [x] Un envoi refusé parce que le compte WhatsApp Business de l'organisation n'a plus de moyen de paiement l'explique dans le statut du message (erreur Meta 131042)

## JIKU-270 — Tarifs cohérents et règles appliquées

**Pourquoi.** Le seuil de Pro Organisateur différait entre le dollar et les
francs, un événement payait ses invités gratuits, un organisateur pouvait être
bloqué le jour de son événement et l'usage raisonnable de Services Pro n'était
écrit que dans les CGU (ADR 109, décisions 5 à 10).

**Critères d'acceptation.**

- [x] Prix en dollars : 72 USD par mois pour Pro Organisateur, 0,09 USD par invité ; le seuil est 800 invités dans les trois monnaies
- [x] Un premier achat d'invités ne paie que ceux au-delà des invités gratuits restants, qui sont alors réservés à l'événement
- [x] Le jour de l'événement, l'envoi continue jusqu'à 20 % au-delà des invités (au moins 20), et ces invités apparaissent comme à payer
- [x] Une offre payante de services envoie au plus 1 000 rappels WhatsApp par personne et par mois depuis le numéro Jikū
- [x] La facture est en français et en anglais
- [x] Les mots promotionnels sont comparés par mots entiers et ne repèrent plus les tournures courantes d'une invitation
- [x] Web : la devise affichée suit le pays du visiteur (GNF en Guinée, FCFA dans l'UEMOA et la CEMAC, USD ailleurs), FCFA par défaut en français et USD en anglais, avec un sélecteur retenu
- [x] Web : l'écran de facturation déduit les invités gratuits de l'estimation


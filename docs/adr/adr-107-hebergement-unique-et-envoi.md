# ADR 107 — Hébergement sur un serveur unique et envoi d'e-mails par useSend

**Statut :** accepté.
**Date :** 2026-10-02
**Amont :** plan de production (`docs/jiku-plan-production.md`, §2.2 et phase 0),
ADR 79 (compteurs partagés en base), `docs/deploy.md`, `docs/backup.md`.

## Contexte

Jikū tourne aujourd'hui sur des offres gratuites qui ne conviennent pas à la
production :

| Pièce | Aujourd'hui | Problème |
|---|---|---|
| Web | Vercel Hobby | Usage commercial interdit ; il faut Pro (20 $ par développeur et par mois) |
| API | Render gratuit | S'endort après inactivité : webhooks WhatsApp et CinetPay perdus, premier appel lent |
| Base | Neon gratuit | 50 heures de calcul par mois ; le pool de connexions garde la base éveillée (≈ 180 h) |
| E-mail | Resend gratuit (100 par jour) + Brevo gratuit (300 par jour) | Un événement de 300 invités épuise les plafonds |

La version conforme de cette architecture coûte environ 85 à 105 $ par mois,
avec trois sauts réseau pour chaque page rendue côté serveur (fonction Vercel,
Render, Neon).

## Décision 1 — Tout sur un serveur, piloté par Dokploy

Le web (Next.js), l'API (Spring Boot) et PostgreSQL tournent sur **un serveur
Hetzner Cloud CX33** (4 vCPU, 8 Go, 80 Go, ≈ 9 € par mois hors TVA), en Europe.

- **Dokploy** déploie les conteneurs, termine TLS et redémarre ce qui tombe.
  Il est plus léger que Coolify (≈ 300 à 400 Mo au repos) et repose sur Compose
  et Docker Swarm, ce qui ouvre la voie à plusieurs serveurs sans changer d'outil.
- **Les images sont construites dans GitHub Actions** et publiées sur GHCR ; le
  serveur ne compile jamais.
- **Chaque conteneur a une limite de mémoire.** L'API en a besoin : la JVM prend
  75 % de la mémoire qu'elle voit (`MaxRAMPercentage=75`).

| Conteneur | Limite |
|---|---|
| API | 1,5 Go |
| Web | 768 Mo |
| PostgreSQL | 2 Go |
| Umami (statistiques) | 384 Mo |

**Cloudflare (offre gratuite)** est devant tout : DNS, cache des fichiers
statiques (présence à Dakar et Abidjan), pare-feu web, protection contre les
dénis de service, adresse du serveur masquée.

**Montée en charge, dans l'ordre :** agrandir le serveur (quelques minutes),
puis mettre PostgreSQL sur son propre serveur, puis ajouter des serveurs d'API
derrière un répartiteur. Le troisième pas est déjà possible : les limites de
débit sont partagées dans PostgreSQL (ADR 79).

## Décision 2 — PostgreSQL sur le serveur, retour à la minute près

- PostgreSQL 17 dans un conteneur, jamais exposé sur Internet.
- **WAL-G** archive le journal en continu et une sauvegarde complète chaque
  nuit vers **Cloudflare R2**, chez un autre fournisseur que le serveur. On peut
  revenir à n'importe quelle minute (perte maximale d'environ une minute).
- Les sauvegardes quotidiennes Hetzner du serveur s'ajoutent.
- Une restauration est répétée chaque mois (`docs/runbooks/database-restore.md`).

Neon reste en place deux semaines après la bascule, comme retour arrière.

## Décision 3 — Les e-mails de Jikū passent par useSend, sur AWS SES

Envoyer depuis l'adresse IP du serveur est exclu : ports 25 et 465 bloqués par
Hetzner pendant au moins un mois, réputation d'une IP neuve nulle, exigences de
Gmail, Yahoo et Microsoft (SPF, DKIM, DMARC alignés, plaintes sous 0,3 %).

- **useSend** prend en charge l'envoi, les files, les nouvelles tentatives,
  les domaines, la liste d'exclusion et les journaux. Il envoie par **AWS SES**
  (0,10 $ les 1 000 e-mails), dont les adresses IP partagées ont une bonne
  réputation.
- On démarre sur **useSend cloud**, puis on l'héberge sur le serveur quand le
  volume dépasse quelques dizaines de milliers d'e-mails par mois. Le code est
  le même : seules l'adresse de l'API et la clé changent.
- **Jikū garde le choix du fournisseur** : `MAIL_TRANSPORT` sélectionne
  `usesend`, `brevo`, `resend`, `routing` ou `smtp`. Changer de fournisseur ne
  touche pas le métier.
- **Jikū garde les règles métier** : statut de l'invitation, adresse invalide,
  repli vers WhatsApp ou SMS, réputation par organisation.

### Seuils de réputation abaissés

La réputation SES se mesure par compte AWS : une organisation dont la liste
rebondit peut faire mettre le compte en examen et bloquer les invitations de
toutes les autres. Les seuils d'alerte de Jikū passent **sous** ceux de SES :

| Seuil | Avant | SES | Après |
|---|---|---|---|
| Rebonds | 5 % | 5 % = examen, 10 % = suspension | **2 %** |
| Plaintes | 0,5 % | 0,1 % = examen, 0,5 % = suspension | **0,08 %** |

## Décision 4 — Pas d'e-mails marketing au lancement

Le pilote compte 10 à 20 hôtes : un contact direct par WhatsApp convertit mieux
qu'une séquence automatique, et on ne sait pas encore où les organisateurs
décrochent. Au lancement :

- le **consentement marketing** est recueilli dès maintenant (case non cochée),
  pour que la liste soit utilisable plus tard ;
- le back-office affiche une **liste d'organisateurs à relancer** et l'entonnoir
  d'activation : la relance se fait à la main.

Plunk (cloud, sur son propre compte SES, donc isolé des invitations) sera
branché sur les mêmes événements quand l'un de ces seuils sera atteint : plus
d'environ 50 inscriptions par mois, plus de 2 à 3 heures de relances par
semaine, ou des prospects plus nombreux qu'on ne peut en traiter. Le marketing
ne vise que les organisateurs et les prospects consentants, jamais les invités
des organisations.

Aucun bandeau cookies n'est nécessaire : Jikū ne dépose que des cookies
strictement nécessaires (connexion, langue) et Umami n'en dépose pas.

## Décision 5 — La surveillance est hors du serveur

Un serveur qui tombe ne peut pas donner l'alerte lui-même.

- **Erreurs :** Sentry (déjà branché).
- **Traces et métriques :** OpenTelemetry (module officiel de Spring Boot 4)
  vers Grafana Cloud, offre gratuite. Désactivé tant que l'adresse de collecte
  n'est pas configurée.
- **Disponibilité :** moniteur externe sur `/api/v1/health` (runbook existant).
- **Statistiques de visite :** Umami sur le serveur, sans cookies, activé par
  variable d'environnement côté web.
- **Dépenses :** alerte quotidienne quand le coût WhatsApp et SMS de la veille
  dépasse un plafond configuré, en plus des plafonds posés chez AWS et Meta.

## Ce qui est écarté

| Option | Raison |
|---|---|
| Serveur de messagerie auto-hébergé (Billion Mail, Hyvor Relay, Postal) | Réputation d'IP à construire, ports bloqués ; une IP grillée bloque toutes les invitations |
| Plunk ou useSend pour les invitations en plus de la logique Jikū | Ils ne connaissent pas les organisations ; les règles métier restent dans Jikū |
| Listmonk | Pas d'automatisation ; newsletters couvertes par useSend ou Plunk |
| MinIO | Dépôt archivé en avril 2026 ; R2 est déjà utilisé |
| Upstash / Redis | Les compteurs sont dans PostgreSQL (ADR 79) |
| PostHog auto-hébergé | 4 Go de RAM recommandés ; Umami suffit pour le site |
| Coolify | Plus lourd ; 11 failles publiées en janvier 2026, dont trois notées 10 sur 10 |
| Serveurs Hetzner CPX, CCX ou dédiés | Hausse de prix de 2,4 à 3 fois en juin 2026 ; la gamme CX suffit |

## Coûts estimés (hors TVA, hors WhatsApp et SMS refacturés)

| Utilisateurs touchés par mois | Lancement | 100 000 | 500 000 | 1 000 000 |
|---|---|---|---|---|
| Serveurs | CX33 | CX43 | CX43 + CX53 + répartiteur | 3 × CX43 + 2 × CX53 + répartiteur |
| Total infrastructure et e-mails | ≈ 15 € | ≈ 40 à 60 € | ≈ 210 à 250 € | ≈ 420 à 500 € |

WhatsApp et les SMS coûtent 15 à 20 fois plus que l'hébergement ; ils restent
refacturés aux organisations (ADR 105).

## Conséquences

- `docker-compose.vps.yml`, les images GHCR, WAL-G et les runbooks de
  déploiement, de restauration et d'incident sont ajoutés (JIKU-203).
- Un adaptateur `usesend` et son webhook de retours sont ajoutés ; les seuils
  de réputation sont abaissés (JIKU-200).
- Le consentement marketing (JIKU-201) et la liste de relance (JIKU-202)
  préparent le marketing sans l'activer.
- OpenTelemetry, Umami et l'alerte de dépenses sont ajoutés, tous désactivés
  par défaut (JIKU-204).
- Un test de charge du jour J est ajouté (JIKU-205).
- Vercel, Render et Neon sont fermés deux semaines après une bascule réussie.
- On revoit cette décision si le serveur dépasse 70 % de mémoire ou de CPU en
  pointe de façon durable, ou si le volume d'e-mails dépasse plusieurs millions
  par mois (Hyvor Relay redevient alors une option).

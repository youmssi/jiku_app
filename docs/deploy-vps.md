# Déployer Jikū sur un serveur unique (ADR 107)

Ce guide installe la production complète (web, API, PostgreSQL) sur un serveur
Hetzner piloté par Dokploy, derrière Cloudflare, puis bascule depuis Vercel,
Render et Neon. Il se suit de haut en bas, sans connaissance préalable.

| Pièce | Où | Comment |
|---|---|---|
| Web (Next.js) | Serveur, conteneur `web` | Image `ghcr.io/youmssi/jiku_web` |
| API (Spring Boot) | Serveur, conteneur `api` | Image `ghcr.io/youmssi/jiku_app` |
| PostgreSQL 18 | Serveur, conteneur `postgres` | Construit depuis `deploy/postgres` (WAL-G inclus) |
| Sauvegardes | Cloudflare R2 | Journal en continu + sauvegarde complète chaque nuit |
| TLS, routage | Traefik (fourni par Dokploy) | Domaines déclarés dans Dokploy |
| DNS, cache, pare-feu web | Cloudflare (offre gratuite) | Enregistrements « proxied » |
| E-mails | useSend sur AWS SES | `MAIL_TRANSPORT=usesend` (JIKU-200) |
| Surveillance | Hors du serveur | Moniteur de disponibilité, Sentry, Grafana Cloud |

Fichiers utilisés : `docker-compose.vps.yml`, `deploy/postgres/`,
`.github/workflows/images.yml` (et son équivalent dans le dépôt web).

---

## 1. Créer le serveur

1. Hetzner Cloud → **CX33** (4 vCPU, 8 Go, 80 Go), image **Ubuntu 24.04**,
   région Falkenstein ou Nuremberg, avec votre clé SSH. Activer les
   **sauvegardes Hetzner** (+20 %).
2. Créer un **pare-feu Hetzner** et l'attacher au serveur :
   - entrée TCP 443 et 80 : uniquement depuis les plages de Cloudflare
     (<https://www.cloudflare.com/ips/>) ;
   - aucune autre entrée : SSH passe par Tailscale.

   Le pare-feu Hetzner est la vraie barrière. `ufw` ne suffit pas : Docker
   publie ses ports en contournant ses règles.
3. Première connexion (encore en SSH public, le temps d'installer Tailscale) :
   ```
   apt-get update && apt-get -y upgrade
   apt-get -y install unattended-upgrades
   dpkg-reconfigure -plow unattended-upgrades
   curl -fsSL https://tailscale.com/install.sh | sh
   tailscale up --ssh
   ```
4. Vérifier qu'on se connecte par Tailscale (`ssh root@<nom-tailscale>`), puis
   désactiver la connexion par mot de passe (`PasswordAuthentication no` dans
   `/etc/ssh/sshd_config`) et retirer la règle SSH publique du pare-feu.

## 2. Installer Dokploy

```
curl -sSL https://dokploy.com/install.sh | sh
```

- Ouvrir `http://<adresse-tailscale>:3000` (jamais par l'adresse publique : le
  port 3000 n'est pas ouvert dans le pare-feu).
- Créer le compte administrateur et **activer la double authentification**.
- Settings → Registry : ajouter `ghcr.io` avec un jeton GitHub en lecture des
  paquets, si les images sont privées.

## 3. Préparer les sauvegardes (Cloudflare R2)

1. R2 → créer le bucket `jiku-backups` (distinct du bucket des documents).
2. R2 → Manage API tokens → jeton **Object Read & Write** limité à ce bucket.
3. Noter : identifiant de clé, secret, et l'adresse S3
   `https://<account-id>.r2.cloudflarestorage.com`.

## 4. Créer l'application dans Dokploy

1. Project → **Create Service → Compose**, source : dépôt GitHub `jiku_app`,
   branche `main`, fichier `docker-compose.vps.yml`.
2. **Environment** : coller les variables de production. Ce sont celles du
   `.env.example` (section REQUIRED), plus la section « Single server » :

   | Variable | Valeur |
   |---|---|
   | `DATABASE_PASSWORD` | secret fort |
   | `WALG_S3_PREFIX` | `s3://jiku-backups/postgres` |
   | `WALG_ACCESS_KEY_ID` / `WALG_SECRET_ACCESS_KEY` | jeton R2 |
   | `WALG_ENDPOINT` | adresse S3 R2 |
   | `SERVER_BASE_URL`, `CORS_ALLOWED_ORIGINS` | `https://<domaine>` |
   | `API_PUBLIC_URL` | `https://api.<domaine>` |
   | `MAIL_TRANSPORT` | `usesend` (+ `USESEND_API_KEY`, `USESEND_WEBHOOK_SECRET`) |
   | `UMAMI_APP_SECRET` | secret fort (statistiques de visite) |
   | `WHATSAPP_META_BUSINESS_ACCOUNT_ID` | compte WhatsApp Business du numéro de la plateforme |
   | `OBSERVABILITY_*`, `MESSAGING_DAILY_*` | voir l'étape 9 bis |

   Dokploy écrit ces variables dans le `.env` lu par le conteneur `api`.
3. **Domains** :
   - `api.<domaine>` → service `api`, port `8080` ;
   - `<domaine>` et `www.<domaine>` → service `web`, port `3000` ;
   - `stats.<domaine>` → service `umami`, port `3000`.
4. **Certificats** : Cloudflare → SSL/TLS → Origin Server → créer un certificat
   d'origine (15 ans) pour `<domaine>` et `*.<domaine>`, puis l'ajouter dans
   Dokploy (Settings → Certificates) et l'associer aux domaines. Cloudflare en
   mode **Full (strict)**.
5. **Deploy**. Suivre les journaux : PostgreSQL démarre, Flyway applique les
   migrations au premier démarrage de l'API.
6. Copier l'URL du **deploy webhook** de l'application (onglet Deployments).

## 5. Construire l'image web

Dans le dépôt `jiku_web`, réglages GitHub → Variables : `NEXT_PUBLIC_SITE_URL`,
`NEXT_PUBLIC_API_URL` (`https://api.<domaine>/api/v1`), et les autres
`NEXT_PUBLIC_*` utilisés (voir son `.env.example`). Elles sont gravées dans
l'image à la construction : les changer impose une nouvelle image.

## 6. Cloudflare

- DNS : `A <domaine>`, `A www`, `A api` → adresse publique du serveur, **proxied**.
- SSL/TLS : Full (strict). Always Use HTTPS : activé.
- Security → WAF : règle de limitation sur `/api/v1/auth/*` (par exemple
  20 requêtes par minute et par adresse), en plus de celle de l'API.
- Email Routing : `support@` et `contact@` vers la boîte de l'équipe.

## 7. Basculer depuis Neon (fenêtre de 15 minutes)

1. Annoncer la maintenance ; mettre l'ancienne API en pause sur Render.
2. Exporter depuis Neon et importer sur le serveur (depuis une machine qui
   atteint les deux, par exemple le serveur lui-même via Tailscale) :
   ```
   pg_dump "postgresql://<user>:<pass>@<hôte-neon>/jiku?sslmode=require" \
     --format=custom --no-owner --no-privileges -f jiku.dump
   docker compose -f docker-compose.vps.yml cp jiku.dump postgres:/tmp/jiku.dump
   docker compose -f docker-compose.vps.yml exec postgres \
     pg_restore -U jiku -d jiku --clean --if-exists --no-owner /tmp/jiku.dump
   ```
   Si l'API avait déjà démarré sur la base vide, l'arrêter avant l'import et la
   relancer ensuite.
3. Vérifier :
   ```
   docker compose -f docker-compose.vps.yml exec postgres psql -U jiku -d jiku \
     -c "SELECT count(*) FROM tenant;" \
     -c "SELECT MAX(version::int) FROM flyway_schema_history WHERE success;"
   ```
   Les nombres doivent être ceux de Neon.
4. Forcer une première sauvegarde complète, et vérifier qu'elle apparaît :
   ```
   docker compose -f docker-compose.vps.yml exec -u postgres postgres-backup \
     wal-g backup-push /var/lib/postgresql/18/docker
   docker compose -f docker-compose.vps.yml exec -u postgres postgres-backup wal-g backup-list
   ```
5. Basculer le DNS sur le serveur, puis vérifier :
   `curl https://api.<domaine>/api/v1/health` et
   `curl https://api.<domaine>/actuator/health/readiness`.

## 8. Mettre à jour les adresses chez les fournisseurs

| Fournisseur | Adresse à changer |
|---|---|
| Meta (WhatsApp) | Webhook → `https://api.<domaine>/api/v1/whatsapp/webhook`, abonné à `messages`, `message_template_status_update`, `message_template_quality_update`, `template_category_update`, `phone_number_quality_update` et `account_update` (JIKU-209) |
| CinetPay | URL de notification et de retour |
| useSend | Webhook `.../api/v1/notifications/email-feedback/usesend` |
| Google (connexion) | Origines JavaScript autorisées : `https://<domaine>` |
| Moniteur de disponibilité | `https://api.<domaine>/api/v1/health` |

## 8 bis. Modèles WhatsApp (JIKU-210)

Une fois l'API en ligne, avec `WHATSAPP_META_BUSINESS_ACCOUNT_ID` et
`WHATSAPP_META_ACCESS_TOKEN` renseignés, créer les modèles en tant
qu'administrateur de la plateforme :

```
curl -X POST https://api.<domaine>/api/v1/admin/whatsapp/templates -H "Authorization: Bearer <jeton admin>"
```

La réponse liste chaque modèle (`created` ou l'erreur de Meta). Meta les relit
en quelques minutes à 48 h ; `GET` sur la même adresse montre leur statut une
fois le webhook abonné (JIKU-209). Tant qu'un modèle n'est pas approuvé, les
messages concernés attendent dans la file.

## 9. Déploiements suivants

Réglages GitHub du dépôt `jiku_app` (et pareil pour `jiku_web`) :

- variable `DEPLOY_TARGET` = `vps` ;
- variable `PRODUCTION_API_URL` = `https://api.<domaine>` ;
- secret `DOKPLOY_DEPLOY_HOOK` = l'URL notée à l'étape 4.

Une fusion sur `main` construit l'image, la publie sur GHCR, déclenche Dokploy,
puis vérifie que l'API est prête. Ordre inchangé : l'API d'abord, le web ensuite.

## 9 bis. Surveillance et statistiques

1. **Grafana Cloud** (offre gratuite) → Connections → OpenTelemetry : noter les
   adresses OTLP et l'en-tête d'authentification, puis dans Dokploy :
   `OBSERVABILITY_ENVIRONMENT=production`,
   `OBSERVABILITY_OTLP_TRACES_ENABLED=true`, `OBSERVABILITY_OTLP_TRACES_ENDPOINT`,
   `OBSERVABILITY_OTLP_METRICS_ENABLED=true`, `OBSERVABILITY_OTLP_METRICS_ENDPOINT`,
   `OBSERVABILITY_OTLP_AUTHORIZATION`. Créer une alerte sur le taux d'erreurs
   HTTP 5xx et sur la mémoire du conteneur `api`.
2. **Plafonds de dépenses** : `MESSAGING_DAILY_WHATSAPP_USD_ALERT` (par exemple
   `20`) et `MESSAGING_DAILY_SMS_COUNT_ALERT` (par exemple `500`). L'alerte part
   chaque matin vers `NOTIFICATION_SALES_EMAIL`. Poser aussi une alerte de budget
   AWS et un plafond de dépense chez Meta.
3. **Umami** : ouvrir `https://stats.<domaine>`, changer le mot de passe
   `admin` / `umami` par défaut, ajouter le site, puis dans les variables du dépôt
   web : `NEXT_PUBLIC_UMAMI_SRC=https://stats.<domaine>/script.js` et
   `NEXT_PUBLIC_UMAMI_WEBSITE_ID`. La base `umami` est créée avec le volume ; sur
   un volume plus ancien, la créer une fois :
   `docker compose -f docker-compose.vps.yml exec postgres psql -U jiku -d jiku -c "CREATE DATABASE umami;"`.

## 10. Après deux semaines sans incident

Fermer le service Render, le projet Vercel et le projet Neon (après un dernier
export conservé dans R2). Supprimer `docker-compose.prod.yml`, `Caddyfile` et
`docs/deploy.md`, qui décrivent l'ancienne architecture.

## Contrôles mensuels

- Restauration complète répétée (`docs/runbooks/database-restore.md`, cas 1) ;
  durée notée dans `docs/backup.md`.
- Mémoire et CPU en pointe sous 70 % (Grafana Cloud) ; au-delà, agrandir le
  serveur (Hetzner → Rescale, quelques minutes de coupure).
- `apt list --upgradable` vide ; Dokploy à jour.

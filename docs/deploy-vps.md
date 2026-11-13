# Déployer Jikū sur un serveur unique (ADR 107, ADR 111)

Ce guide installe la production complète (web, API, PostgreSQL) sur un VPS
Contabo piloté par Dokploy, derrière Cloudflare, puis bascule depuis Vercel,
Render et Neon. Il se suit de haut en bas, sans connaissance préalable.

Au lancement, le serveur ne porte que Jikū (ADR 111) : le fondateur répond
lui-même aux clients sur le numéro WhatsApp Jikū Support. Chatwoot et n8n
s'ajouteront plus tard, comme applications Dokploy séparées avec les limites de
mémoire de l'ADR 108, après passage au VPS-4. Les SMS sont coupés par défaut
(`SMS_TRANSPORT=off`, JIKU-261).

Dans ce guide, `<domaine>` vaut **`hellojiku.com`** (ADR 110) : DNS chez
Cloudflare, enregistrement chez Porkbun. Sous-domaines publics : `www`, `api`,
`stats` (et `chat` quand Chatwoot arrivera) ; Dokploy reste derrière Tailscale.

| Pièce | Où | Comment |
|---|---|---|
| Web (Next.js) | Serveur, conteneur `web` | Image `ghcr.io/youmssi/jiku_web` |
| API (Spring Boot) | Serveur, conteneur `api` | Image `ghcr.io/youmssi/jiku_app` |
| PostgreSQL 18 | Serveur, conteneur `postgres` | Construit depuis `deploy/postgres` (WAL-G inclus) |
| Sauvegardes | Cloudflare R2 | Journal en continu + sauvegarde complète chaque nuit |
| TLS, routage | Traefik (fourni par Dokploy) | Domaines déclarés dans Dokploy |
| DNS, cache, pare-feu web | Cloudflare (offre gratuite) | Enregistrements « proxied » |
| E-mails | useSend (AWS SES), Resend, Brevo | `MAIL_TRANSPORT=routing`, chaîne `usesend,resend,brevo` (ADR 110, JIKU-275) |
| Surveillance | Hors du serveur | Moniteur de disponibilité, Sentry, Grafana Cloud |

Fichiers utilisés : `docker-compose.vps.yml`, `deploy/postgres/`,
`.github/workflows/images.yml` (et son équivalent dans le dépôt web).

---

## 1. Créer le serveur

1. Contabo → **Cloud VPS 8 NVMe** (8 vCPU, 24 Go, 150 Go NVMe ; ADR 111),
   durée **1 mois**, région **European Union**, image **Ubuntu 26.04**, sans
   Auto Backup ni autre option payante. Les sauvegardes de la base passent par
   WAL-G vers R2 (section 3). Le mot de passe root choisi à la commande ne sert
   qu'à la première connexion : il reste chez le fondateur, jamais dans un
   ticket, un message ou le dépôt.
2. Sur le poste du fondateur, créer une clé SSH et la déposer sur le serveur :
   ```
   ssh-keygen -t ed25519 -C "jiku-prod"
   ssh-copy-id root@<ip-du-serveur>
   ```
   (Windows, PowerShell : `type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh root@<ip-du-serveur> "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys"`).
   Vérifier que `ssh root@<ip-du-serveur>` entre sans demander de mot de passe.
3. Fermer tout ce qui n'est pas HTTPS venant de Cloudflare. Docker publie ses
   ports en contournant `ufw` : la règle doit donc vivre dans la chaîne
   `DOCKER-USER` d'iptables, que Docker consulte avant ses propres règles.
   Après l'installation de Dokploy (section 2), créer
   `/usr/local/sbin/jiku-firewall.sh` :
   ```
   #!/bin/sh
   # Ports web des conteneurs : uniquement depuis Cloudflare.
   iptables -F DOCKER-USER
   iptables -A DOCKER-USER -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
   for net in $(curl -fsS https://www.cloudflare.com/ips-v4); do
     iptables -A DOCKER-USER -p tcp -m multiport --dports 80,443 -s "$net" -j RETURN
   done
   iptables -A DOCKER-USER -p tcp -m multiport --dports 80,443 -j DROP
   iptables -A DOCKER-USER -j RETURN
   ```
   puis `chmod +x` et un service systemd qui le lance au démarrage, après
   `docker.service`. Vérifier depuis un autre poste que
   `curl -k https://<ip-du-serveur>` échoue, et que le site répond par son
   domaine. Le pare-feu de Contabo (panneau client) peut en plus bloquer en
   entrée tout ce qui n'est pas 80 ou 443.
4. Première connexion (encore en SSH public, le temps d'installer Tailscale) :
   ```
   apt-get update && apt-get -y upgrade
   apt-get -y install unattended-upgrades
   dpkg-reconfigure -plow unattended-upgrades
   curl -fsSL https://tailscale.com/install.sh | sh
   tailscale up --ssh
   ```
5. Vérifier qu'on se connecte par Tailscale (`ssh root@<nom-tailscale>`), puis
   désactiver la connexion par mot de passe (`PasswordAuthentication no` dans
   `/etc/ssh/sshd_config`), puis fermer le port 22 public
   (`ufw default deny incoming`, `ufw allow in on tailscale0`, `ufw enable` :
   `ufw` suffit ici, car SSH ne passe pas par Docker).

## 2. Installer Docker puis Dokploy

Sur Ubuntu 26.04, le script de Dokploy impose une version de Docker (28.5.0)
absente des dépôts de cette version d'Ubuntu. Installer d'abord Docker depuis
son dépôt officiel :

```
apt-get -y install ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" > /etc/apt/sources.list.d/docker.list
apt-get update && apt-get -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
docker version
```

puis Dokploy, qui doit trouver Docker déjà installé :

```
curl -sSL https://dokploy.com/install.sh | sh
```

Si le script tente malgré tout d'installer Docker 28.5.0 et échoue, relire la
documentation de Dokploy pour Ubuntu 26.04 avant d'aller plus loin.

- Ouvrir `http://<adresse-tailscale>:3000` (jamais par l'adresse publique : le
  port 3000 n'est pas ouvert dans le pare-feu).
- Créer le compte administrateur et **activer la double authentification**.
- Settings → Registry : ajouter `ghcr.io` avec un jeton GitHub en lecture des
  paquets, si les images sont privées.

- Couper l'IPv6 du serveur. Le pare-feu (`jiku-firewall`) ne filtre que
  l'IPv4, Cloudflare et Tailscale passent par l'IPv4, et l'IPv6 de Contabo coupe
  parfois les téléchargements d'images (`connection reset by peer`) :
  ```
  printf 'net.ipv6.conf.all.disable_ipv6=1\nnet.ipv6.conf.default.disable_ipv6=1\nnet.ipv6.conf.lo.disable_ipv6=1\n' \
    | sudo tee /etc/sysctl.d/99-jiku-no-ipv6.conf
  sudo sysctl --system
  ip -6 addr   # ne doit rien afficher
  ```

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
   | `MAIL_TRANSPORT` | `routing` |
   | `MAIL_FROM` | `Jikū <no-reply@mail.hellojiku.com>` : une adresse du domaine d'envoi vérifié chez chaque fournisseur |
   | `EMAIL_ASSET_BASE_URL` | à laisser vide : les images des e-mails (filigrane, Bande, logos) sont servies par le site à `https://<domaine>/email` (JIKU-278) |
   | `EMAIL_ROUTING_ORDER` | `usesend,resend,brevo`, avec `EMAIL_GLOBAL_DAILY_CAP=480` |
   | `USESEND_API_KEY`, `RESEND_API_KEY`, `BREVO_API_KEY` | une clé par fournisseur de la chaîne |
   | `USESEND_WEBHOOK_SECRET`, `RESEND_WEBHOOK_SECRET`, `BREVO_WEBHOOK_TOKEN` | retours (rebonds, plaintes) de chaque fournisseur |
   | `EMAIL_USESEND_OVERFLOW` | `false` au lancement ; `true` une fois l'offre payante de useSend activée |
   | `UMAMI_APP_SECRET` | secret fort (statistiques de visite) |
   | `RATE_LIMIT_FORWARDED_CLIENT_IP_SECRET` | secret fort ; transmis aussi au conteneur `web` comme `API_CLIENT_IP_SECRET`, pour que les limites s'appliquent par visiteur |
   | `ONLINE_PAYMENT_ENABLED` | `false` jusqu'aux tests du runbook paiements, puis `true` (affiche le paiement en ligne sur le site) |
   | `WHATSAPP_META_BUSINESS_ACCOUNT_ID` | compte WhatsApp Business du numéro de la plateforme |
   | `DEXPAY_API_KEY`, `DEXPAY_API_SECRET` | clés `pk_live_…` et `sk_live_…` d'une même paire DEXPAY (JIKU-272) ; la clé secrète vérifie la signature des webhooks |
   | `DEXPAY_BASE_URL` | `https://api.dexpay.africa/api/v1` (le bac à sable est `https://api-sandbox.dexpay.africa/api/v1`) |
   | `DEXPAY_WEBHOOK_URL`, `DEXPAY_RETURN_URL` | `https://api.<domaine>/api/v1/billing/payments/callback/dexpay` et `https://<domaine>/billing/return` |
   | `PAYMENT_PROVIDER_GNF`, `PAYMENT_PROVIDER_XOF`, `PAYMENT_PROVIDER_XAF` | `dexpay` |
   | `PAYMENT_PROVIDER_USD` | `none` : l'USD se paie par virement |
   | `DEXPAY_CLIENT_PAYS_FEES` | `false` : le client paie le prix affiché et facturé, Jikū supporte les frais DEXPAY |
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
5. **Deploy**. Suivre les journaux : PostgreSQL crée sa base (jusqu'à deux
   minutes au premier démarrage), Flyway applique les migrations, puis l'API
   affiche `Started JikuApplication`. Les services n'apparaissent dans l'onglet
   **Domains** qu'après ce premier déploiement : ajouter les domaines ensuite, puis
   redéployer. Vérifier que WAL-G envoie bien le journal vers R2 :
   ```
   sudo docker logs --tail 40 <projet>-postgres-1 2>&1 | grep -i -E "wal-g|archive"
   ```
   Aucune ligne `ERROR` ni `archive command failed` ne doit apparaître.
6. Activer **Autodeploy** (onglet General) et copier l'URL du **deploy webhook**
   (onglet Deployments), de la forme `http://<adresse>:3000/api/deploy/compose/<jeton>`.
   Le jeton vaut un mot de passe : il ne va que dans un secret GitHub.

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
- E-mails envoyés : vérifier `hellojiku.com` chez useSend, Resend et Brevo, et
  ajouter leurs enregistrements en **DNS only**. Leurs SPF vont en principe sur
  un sous-domaine d'envoi ; s'il en faut un à la racine, le fusionner avec celui
  d'Email Routing : un domaine n'a jamais qu'**un seul** enregistrement SPF.

## 7. Basculer depuis Neon (fenêtre de 15 minutes)

1. Annoncer la maintenance ; mettre l'ancienne API en pause sur Render.
2. Exporter depuis Neon et importer sur le serveur (depuis une machine qui
   atteint les deux, par exemple le serveur lui-même via Tailscale) :
   ```
   pg_dump "postgresql://<user>:<pass>@<hôte-neon>/jiku?sslmode=require" \
     --format=custom --no-owner --no-privileges -f jiku.dump
   ```
   Arrêter l'API, puis **recréer** la base avant l'import. Le premier déploiement
   l'a déjà migrée jusqu'à la dernière version : `pg_restore --clean` laisserait
   les tables plus récentes que l'export, et Flyway échouerait au redémarrage.
   ```
   docker compose -f docker-compose.vps.yml stop api
   docker compose -f docker-compose.vps.yml exec postgres psql -U jiku -d postgres \
     -c "DROP DATABASE jiku WITH (FORCE);" -c "CREATE DATABASE jiku OWNER jiku;"
   docker compose -f docker-compose.vps.yml cp jiku.dump postgres:/tmp/jiku.dump
   docker compose -f docker-compose.vps.yml exec postgres \
     pg_restore -U jiku -d jiku --no-owner --no-privileges /tmp/jiku.dump
   docker compose -f docker-compose.vps.yml start api
   ```
   Au redémarrage, Flyway applique les migrations qui manquent à l'export.
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
| DEXPAY (JIKU-272) | Rien à saisir chez DEXPAY : chaque session de paiement porte elle-même `DEXPAY_WEBHOOK_URL` et `DEXPAY_RETURN_URL`. Côté web, `ONLINE_PAYMENT_ENABLED=true` une fois le contrat signé et les clés de production en place |
| useSend | Webhook `.../api/v1/notifications/email-feedback/usesend` (`email.bounced`, `email.complained`) |
| Resend | Webhook `.../api/v1/notifications/email-feedback/resend` (`email.bounced`, `email.complained`) |
| Brevo | Webhook transactionnel `.../api/v1/notifications/email-feedback/brevo` (rebond définitif et temporaire, adresse invalide, spam), authentification par jeton porteur `BREVO_WEBHOOK_TOKEN` |
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

Une fusion sur `main` construit l'image, la publie sur GHCR, appelle le webhook
de Dokploy, puis vérifie que l'API est prête (ou que le site répond, côté web).
Les deux dépôts appellent le **même** webhook : il redéploie toute la pile et
tire les deux images. Ordre inchangé : l'API d'abord, le web ensuite.

Le port 3000 de Dokploy reste fermé ; les machines de GitHub passent par
Traefik, sur un sous-domaine qui n'expose que le chemin du webhook.

1. Cloudflare → DNS : `A deploy` → adresse publique du serveur, **proxied**.
2. Sur le serveur, `/etc/dokploy/traefik/dynamic/jiku-deploy-hook.yml` :
   ```yaml
   http:
     routers:
       jiku-deploy-hook:
         rule: Host(`deploy.<domaine>`) && PathPrefix(`/api/deploy/compose/`)
         entryPoints: [websecure]
         service: jiku-deploy-hook
         tls: {}
     services:
       jiku-deploy-hook:
         loadBalancer:
           servers:
             - url: http://dokploy:3000
   ```
   Traefik relit ce dossier tout seul. Vérifier que le reste de Dokploy n'est
   pas exposé : `curl -s -o /dev/null -w '%{http_code}' https://deploy.<domaine>/`
   doit répondre `404`.
3. Dans Dokploy, l'application doit avoir **Autodeploy** activé, sinon le
   webhook répond `400`.
4. Réglages GitHub de `jiku_app` **et** de `jiku_web` (Settings → Secrets and
   variables → Actions) :
   - secret `DOKPLOY_DEPLOY_HOOK` = l'URL notée à l'étape 4, avec
     `https://deploy.<domaine>` à la place de `http://<adresse>:3000` ;
   - variable `DEPLOY_TARGET` = `vps` ;
   - variable `PRODUCTION_API_URL` = `https://api.<domaine>` (`jiku_app`) ;
   - variable `NEXT_PUBLIC_SITE_URL` = `https://<domaine>` (`jiku_web`, déjà
     posée à l'étape 5).
5. Tester sans attendre une release : relancer le dernier run « Container
   image » de `main` (Actions → Re-run all jobs). Le job « Roll out on the
   server » doit afficher `Compose deployed successfully`, puis un nouveau
   déploiement apparaît dans l'onglet Deployments de Dokploy.

Le workflow envoie un `push` au format GitHub pour la branche `main` : Dokploy
ignore un appel vide pour une application liée à GitHub (`301 Branch Not
Match`). Toute autre réponse que `200` fait échouer le job.

Si Autodeploy est activé et que l'application suit le dépôt `jiku_app` par
l'application GitHub de Dokploy, une fusion sur `main` lance aussi un premier
déploiement immédiat, avec l'image précédente ; celui du workflow, une fois
l'image construite, la remplace. Un `403` de Cloudflare sur le webhook vient du
mode anti-robots : ajouter une règle WAF « Skip » pour l'hôte `deploy.<domaine>`.

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

## 9 ter. Rediriger l'ancien domaine (ADR 110)

Une fois la vérification Meta de MRVIN100 SARLU terminée, ajouter au
`vercel.json` du dépôt web une redirection 308 de `jiku.mrvin100.de/:path*`
vers `https://hellojiku.com/:path*` (condition `has` sur l'hôte), la livrer sur
`main`, puis vérifier qu'un ancien lien d'invitation ouvre la même page sur le
nouveau domaine. La garder au moins un an.

## 10. Après deux semaines sans incident

Fermer le service Render, le projet Vercel et le projet Neon (après un dernier
export conservé dans R2). Supprimer `docker-compose.prod.yml`, `Caddyfile` et
`docs/deploy.md`, qui décrivent l'ancienne architecture.

## Contrôles mensuels

- Restauration complète répétée (`docs/runbooks/database-restore.md`, cas 1) ;
  durée notée dans `docs/backup.md`.
- Mémoire et CPU en pointe sous 70 % (Grafana Cloud) ; au-delà, agrandir le
  serveur (Contabo → offre supérieure depuis le panneau client, ou migration
  vers netcup ou Hetzner ; ADR 111).
- `apt list --upgradable` vide ; Dokploy à jour.

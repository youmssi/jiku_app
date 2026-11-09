# ADR 111 — Lancement sur un VPS-3 OVHcloud, sans Chatwoot ni n8n

**Statut :** accepté.
**Date :** 2026-11-09
**Amont :** ADR 107 (hébergement unique et envoi), ADR 108 (pile de lancement),
`docs/deploy-vps.md`.
**Remplace :** la décision 1 de l'ADR 108 (serveur Hetzner CX43) pour le
lancement.

## Contexte

L'ADR 108 prévoyait un serveur Hetzner CX43 (8 vCPU, 16 Go) portant Jikū,
Chatwoot et n8n. Au moment de commander, le 9 novembre 2026 :

- **Les serveurs cloud partagés de Hetzner sont tous en rupture de stock**, CX43
  compris, et l'ouverture du compte demande une vérification d'identité.
- **Les premiers mois se passent sans automatisation.** Pendant les deux à
  trois premiers mois, le fondateur répond lui-même aux organisateurs, sur le
  numéro WhatsApp Jikū Support (application WhatsApp Business), pour comprendre
  leurs besoins avant d'automatiser. Chatwoot et n8n ne servent donc pas encore.

Sans eux, la production tient dans les limites de mémoire de
`docker-compose.vps.yml` :

| Conteneur | Limite de mémoire |
|---|---|
| PostgreSQL de Jikū | 2 Go |
| Sauvegarde WAL-G | 256 Mo |
| API | 1,5 Go |
| Web | 768 Mo |
| Umami | 384 Mo |
| **Total** | **environ 4,9 Go** |

Dokploy, Traefik et le système ajoutent environ 1,5 à 2 Go.

## Offres comparées (pages des hébergeurs, 9 novembre 2026)

| Offre | Processeur | Mémoire | Disque | Prix par mois | Sauvegarde |
|---|---|---|---|---|---|
| **OVHcloud VPS-3** | 6 vCores | 12 Go | 100 Go NVMe | 10,40 € HT (12,48 € TTC) | Quotidienne incluse |
| OVHcloud VPS-4 | 8 vCores | 24 Go | 200 Go NVMe | 19,96 € HT (23,95 € TTC) | Quotidienne incluse |
| STRATO VPS XL | 8 vCores partagés | 16 Go | 480 Go NVMe | 39 € TTC + 9 € de mise en service | Non incluse |
| STRATO CP4-18 SSD (dédié) | Xeon E3-1230v6, 4 cœurs | 32 Go ECC | 2 × 480 Go SSD en miroir | 38 € TTC + 38 € de mise en service | En option, 10 € |
| Hetzner CX43 | 8 vCPU | 16 Go | 160 Go | 16,49 € HT | En option |

Les prix « à partir de » sont à confirmer dans le configurateur au moment de la
commande.

## Décision 1 — Un VPS-3 OVHcloud en France

Le lancement se fait sur un **VPS-3 OVHcloud** (6 vCores, 12 Go, 100 Go NVMe),
Ubuntu 24.04, dans un centre de données en France :

- environ 5 Go de marge au-dessus des limites des conteneurs ;
- le prix le plus bas des offres comparées, sauvegarde quotidienne incluse ;
- passage au VPS-4 en un clic, sans migration ni réinstallation ;
- entreprise française, support en français, données dans l'Union européenne.

La sauvegarde d'OVHcloud s'ajoute à WAL-G vers Cloudflare R2 ; elle ne la
remplace pas. La restauration de référence reste celle de
`docs/runbooks/database-restore.md`.

## Décision 2 — Chatwoot et n8n reportés

Chatwoot, sa base et n8n ne sont pas installés au lancement. Ils s'ajoutent,
avec les limites de mémoire de l'ADR 108, quand l'une de ces conditions est
remplie :

- le fondateur ne peut plus répondre seul aux conversations dans la journée ;
- les mêmes questions reviennent assez souvent pour être confiées à un
  assistant.

Avant de les installer, le serveur passe au **VPS-4** (24 Go).

## Décision 3 — Seuil d'agrandissement

Le serveur passe au VPS-4 si la mémoire ou le processeur dépassent 70 % en
pointe pendant une semaine, ou si l'API dépasse 500 ms au 95e centile, comme le
prévoit l'ADR 108.

## Conséquences

- Les charges fixes de lancement baissent d'environ 6 $ par mois par rapport à
  l'ADR 108.
- Docker contournant `ufw`, l'accès aux ports 80 et 443 est limité aux adresses
  de Cloudflare dans la chaîne `DOCKER-USER` d'iptables (`docs/deploy-vps.md`,
  section 1) ; SSH passe par Tailscale.
- Les commandes et tableaux qui citent le CX43 (README, plan de production)
  renvoient désormais à cet ADR.

## Sources

- OVHcloud, [VPS](https://www.ovhcloud.com/fr/vps/)
- STRATO, [Linux V-Server](https://www.strato.de/server/linux-vserver/) et
  [Dedicated Server Linux](https://www.strato.de/server/linux-dedicated-server/)
- Hetzner, [Cloud](https://www.hetzner.com/cloud/) et
  [vérification des comptes](https://docs.hetzner.com/general/security-and-identify/fraud-prevention-faq/)

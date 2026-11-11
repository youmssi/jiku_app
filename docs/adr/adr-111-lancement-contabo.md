# ADR 111 — Lancement sur un Cloud VPS 8 Contabo, sans Chatwoot ni n8n

**Statut :** accepté.
**Date :** 2026-11-10
**Amont :** ADR 107 (hébergement unique et envoi), ADR 108 (pile de lancement),
`docs/deploy-vps.md`.
**Remplace :** la décision 1 de l'ADR 108 (serveur Hetzner CX43) pour le
lancement.

## Contexte

L'ADR 108 prévoyait un serveur Hetzner CX43 (8 vCPU, 16 Go) portant Jikū,
Chatwoot et n8n. Au moment de commander, les 9 et 10 novembre 2026 :

- **Les serveurs de 8 Go et plus manquaient presque partout.** Chez Hetzner,
  seul le CX23 (4 Go) restait disponible en offre économique ; le CPX42 (16 Go)
  coûtait 60,49 $ par mois. Chez OVHcloud, les VPS-3 et VPS-4 étaient
  indisponibles. Chez netcup, le VPS 2000 au mois passait par un ticket, avec
  un délai de mise en service annoncé.
- **Le budget de lancement ne permet pas de payer un an d'avance** (248,82 €
  pour le VPS 2000 de netcup sur 12 mois).
- **Les premiers mois se passent sans automatisation.** Pendant deux à trois
  mois, le fondateur répond lui-même aux organisateurs sur le numéro WhatsApp
  Jikū Support (application WhatsApp Business), pour comprendre leurs besoins
  avant d'automatiser. Chatwoot et n8n ne servent donc pas encore.

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

## Offres comparées (pages et consoles des hébergeurs, 9 et 10 novembre 2026)

| Offre | Processeur | Mémoire | Disque | Prix par mois | État |
|---|---|---|---|---|---|
| **Contabo Cloud VPS 8 NVMe** | 8 vCPU | 24 Go | 150 Go NVMe | **18,50 $**, sans frais d'installation, au mois | Disponible |
| netcup VPS 2000 G12.5 | 8 vCores, AMD EPYC | 16 Go | 256 Go SSD | 26,92 € HT au mois ; 22,62 € sur 12 mois payés d'avance | Au mois : sur ticket, délai annoncé |
| netcup RS 2000 G12.5 | 8 cœurs dédiés, AMD EPYC | 16 Go | 256 Go NVMe | 40,70 € HT | Disponible |
| Hetzner CX43 | 8 vCPU | 16 Go | 160 Go | 18,49 $ + IPv4 | Indisponible |
| Hetzner CPX42 | 8 vCPU AMD | 16 Go | 320 Go | 60,49 $ + IPv4 | Disponible |
| OVHcloud VPS-3 / VPS-4 | 6 / 8 vCores | 12 / 24 Go | 100 / 200 Go NVMe | 10,40 € / 19,96 € HT | Indisponibles |
| STRATO VPS XL | 8 vCores | 16 Go | 480 Go NVMe | 39 € TTC + 9 € de mise en service | Disponible |
| DigitalOcean Basic | 8 vCPU | 16 Go | 320 Go | 96 $ | Disponible |

## Décision 1 — Un Cloud VPS 8 NVMe chez Contabo, sous Ubuntu 26.04

La production démarre sur un **Cloud VPS 8 NVMe** (8 vCPU, 24 Go, 150 Go
NVMe), contrat d'un mois, région Union européenne, **Ubuntu 26.04** :

- le seul serveur disponible tout de suite qui tient dans le budget ;
- plus de trois fois la mémoire nécessaire au lancement ;
- un disque NVMe pour PostgreSQL ;
- sans engagement : le changement de serveur reste possible chaque mois.

Le choix d'Ubuntu 26.04 donne un support jusqu'en 2031. Son seul écueil connu
au lancement : le script d'installation de Dokploy impose Docker 28.5.0,
absent des dépôts d'Ubuntu 26.04 ; Docker est donc installé d'abord depuis son
dépôt officiel (`docs/deploy-vps.md`, section 2).

## Décision 2 — Chatwoot et n8n reportés

Chatwoot, sa base et n8n ne sont pas installés au lancement. Ils s'ajoutent,
avec les limites de mémoire de l'ADR 108, quand l'une de ces conditions est
remplie :

- le fondateur ne peut plus répondre seul aux conversations dans la journée ;
- les mêmes questions reviennent assez souvent pour être confiées à un
  assistant.

La mémoire du serveur actuel suffit à les accueillir.

## Décision 3 — Quand changer de serveur

Les offres de base de Contabo partagent leurs processeurs plus que celles de
netcup ou Hetzner. On mesure donc dès le premier jour, et l'on migre vers un
netcup VPS 2000 (ou RS 2000) ou un Hetzner CX43 si l'une de ces conditions est
remplie pendant une semaine :

- la mémoire ou le processeur dépassent 70 % en pointe ;
- l'API dépasse 500 ms au 95e centile ;
- le temps volé au processeur (`steal` dans `top`) dépasse régulièrement 10 %.

La migration consiste à installer le nouveau serveur avec ce guide puis à
restaurer la base depuis R2 (`docs/runbooks/database-restore.md`) : environ une
demi-journée, sans perte de données.

## Conséquences

- Les charges fixes de serveur passent à 18,50 $ par mois, payés chaque mois,
  sans avance d'un an.
- Docker contournant `ufw`, l'accès aux ports 80 et 443 est limité aux adresses
  de Cloudflare dans la chaîne `DOCKER-USER` d'iptables (`docs/deploy-vps.md`,
  section 1) ; SSH passe par Tailscale.
- Les commandes et tableaux qui citaient le CX43 renvoient désormais à cet ADR.

## Sources

- Contabo, configurateur du Cloud VPS 8 (capture du 10 novembre 2026)
- netcup, [VPS](https://www.netcup.com/en/server/vps) et
  [root servers](https://www.netcup.com/en/server/root-server)
- Hetzner, console de création de serveur (captures du 10 novembre 2026)
- OVHcloud, [VPS](https://www.ovhcloud.com/fr/vps/)
- STRATO, [Linux V-Server](https://www.strato.de/server/linux-vserver/)
- DigitalOcean, [Droplets](https://www.digitalocean.com/pricing/droplets)
- Dokploy, pull request 4660 sur Ubuntu 26.04 (Docker 28.5.0 introuvable)

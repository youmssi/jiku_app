# ADR 108 — Pile de lancement : un serveur CX43, conversations commerciales, SMS coupés

**Statut :** accepté.
**Date :** 2026-10-16
**Amont :** ADR 107 (hébergement unique et envoi), ADR 105 (canaux et tarification),
`docs/backlog/lancement.md`.

## Contexte

L'ADR 107 met le web, l'API et PostgreSQL sur un serveur CX33. Trois besoins
s'ajoutent pour le lancement :

- **Convertir les prospects.** Les publicités Facebook renvoient vers WhatsApp ;
  il faut répondre vite à quelques centaines de conversations par mois, avec une
  personne qui reprend la main dès que c'est utile.
- **Tenir un budget mensuel de 230 $**, publicité comprise.
- **Ne pas payer de SMS au démarrage** : les organisateurs visés ont WhatsApp,
  et le SMS coûte cher pour un usage marginal.

## Décision 1 — Un seul serveur Hetzner CX43

Le serveur passe de CX33 à **CX43** (8 vCPU, 16 Go, 160 Go, ≈ 16 $ par mois)
pour accueillir aussi les outils de conversation. Dokploy, Cloudflare, WAL-G
vers R2, useSend sur AWS SES et la surveillance hors serveur restent ceux de
l'ADR 107.

| Conteneur | Limite de mémoire |
|---|---|
| API | 1,5 Go |
| Web | 768 Mo |
| PostgreSQL de Jikū | 2 Go |
| Umami | 384 Mo |
| Chatwoot (web et tâches) | 2,5 Go |
| PostgreSQL de Chatwoot (avec pgvector) | 1 Go |
| Redis de Chatwoot | 256 Mo |
| n8n | 1 Go |

Chatwoot a **sa propre base** : un incident de Chatwoot ne touche ni les
performances ni les sauvegardes de Jikū. La marge restante (environ 6 Go) couvre
Dokploy et les pointes.

**Seuil de séparation :** mémoire ou CPU au-dessus de 70 % en pointe pendant une
semaine, ou un temps de réponse de l'API au 95e centile au-dessus de 500 ms.
On sort alors Chatwoot, n8n et leur base sur un second serveur, avant de toucher
à Jikū.

## Décision 2 — Conversations commerciales : Chatwoot, n8n, un assistant et une personne

- **Chatwoot** reçoit les messages du numéro WhatsApp « conversations » (ventes,
  puis assistance) par l'API officielle de Meta.
- **n8n** relie Chatwoot à l'assistant : une nouvelle conversation reste « en
  attente » et l'assistant répond. Il qualifie le besoin, répond aux questions
  à partir d'une base de réponses validées, propose un lien **Cal.com** pour un
  rendez-vous, et passe la conversation en « ouverte » pour la confier à une
  personne dès que le prospect le demande, qu'il hésite sur le prix, ou que la
  question sort de la base.
- **Modèle : Claude Haiku 5.5**, avec un plafond de dépense de 15 $ par mois.
  On passe à Claude Sonnet 5.5 seulement si une évaluation de 40 à 50 vraies
  conversations montre un meilleur taux de rendez-vous ou moins de reprises
  par une personne.
- **Transparence :** l'assistant se présente comme un assistant automatique et
  propose toujours de parler à une personne.
- **Données :** ces outils ne reçoivent que les messages écrits à Jikū, jamais
  les données des invités et clients des organisations. Les pages légales les
  citent (JIKU-263).

## Décision 3 — Trois numéros WhatsApp

| Numéro | Usage |
|---|---|
| Conversations | Ventes et assistance, dans Chatwoot ; c'est le numéro affiché sur le site |
| Invitations | Envois de Jikū pour les organisations (invitations, rappels, codes) |
| Réserve | Prend le relais si un numéro est restreint par Meta |

Un problème de qualité sur les ventes ne bloque donc jamais les invitations.
Les lignes sont de préférence au nom de MRVIN100 SARLU ; des lignes personnelles
sont acceptables au démarrage (trois au plus par personne et par opérateur).

## Décision 4 — SMS coupés au lancement

`SMS_TRANSPORT=off` (JIKU-261) : aucun SMS n'est envoyé, un message qui ne
pouvait partir que par SMS est enregistré « non envoyé » avec sa raison, et les
écrans ne proposent plus le SMS. Les fichiers de déploiement l'utilisent par
défaut. Le SMS revient par une variable d'environnement (`nimba`) quand des
clients le demandent.

## Décision 5 — Budget de lancement : 230 $ par mois

| Poste | $ par mois |
|---|---|
| Serveur CX43 | ≈ 16 |
| useSend | 10 |
| Nom de domaine | 1 |
| Assistant (Haiku, plafond 15 $) | ≈ 5 |
| Crédit prépayé Meta (WhatsApp) | ≈ 20 |
| SMS | 0 |
| Publicité Facebook vers WhatsApp | 150 |
| Réserve | ≈ 28 |

Les conversations ouvertes par une publicité « clic vers WhatsApp » sont
gratuites pendant 72 heures si l'on répond dans les 24 heures : l'assistant
répond tout de suite.

## Décision 6 — Parrainage en crédit, pas en argent

Le parrainage récompense en **crédit Jikū**, jamais en espèces (JIKU-262) :

- le parrain reçoit un mois du plan du filleul quand celui-ci paie son
  deuxième mois ; le filleul, un mois offert après son premier paiement ;
- paliers : un mois bonus à 5 parrainages, statut Ambassadeur à 10 ;
- le crédit paie l'abonnement ou des options, peut être offert à une autre
  organisation, ne se convertit jamais en argent et expire après 12 mois ;
- garde-fous : au plus 2 mois du plan du filleul par client acquis, au plus
  12 mois de crédit par parrain et par an, coût total sous 10 % du revenu
  mensuel ; pas d'auto-parrainage.

Les influenceurs et les marques passent par un contrat marketing sur facture,
avec un code promo pour leur public. Aucun versement n'est fait hors de Guinée.

## Ce qui est écarté

| Option | Raison |
|---|---|
| Evolution API (WhatsApp non officiel) | Contraire aux conditions de Meta : risque de bannissement des numéros |
| Kubernetes, plusieurs serveurs au lancement | Coût et exploitation sans besoin mesuré |
| Commissions en argent pour les clients | Image « gagner de l'argent », fraude, retenues et transferts réglementés |
| Outils d'affiliation tiers (Dub Partners, Refferq) | Pensés pour Stripe ; Jikū encaisse en Mobile Money et confirme lui-même |
| Sonnet dès le départ | 20 fois le coût de Haiku sans preuve d'un meilleur résultat |

## Conséquences

- JIKU-261 ajoute le transport `off` et l'endpoint `GET /messaging/channels`.
- JIKU-262 ajoute le parrainage en crédit (après un pilote de 4 semaines suivi à la main).
- JIKU-263 met à jour les pages légales et ajoute cet ADR.
- JIKU-264 décrit l'installation de Chatwoot, n8n et de l'assistant, et son
  évaluation.
- `docs/deploy-vps.md` reste la référence pour Jikū ; Chatwoot et n8n s'ajoutent
  comme applications Dokploy séparées.

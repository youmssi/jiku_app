# ADR 110 — Domaine hellojiku.com, encaissement, envoi des e-mails et canal d'invitation

**Statut :** accepté.
**Date :** 2026-10-24
**Amont :** ADR 107 (hébergement unique et envoi), ADR 108 (pile de lancement),
ADR 109 (tarification), `docs/deploy-vps.md`.

## Contexte

Le lancement demande un domaine à la marque, une façon d'encaisser les
organisateurs sans prestataire de paiement en ligne (CinetPay a refusé une
société guinéenne), un envoi d'e-mails peu coûteux, et des invitations qui
n'arrivent jamais deux fois chez la même personne.

## Décision 1 — Le domaine est hellojiku.com

- `jiku.app` n'est disponible qu'en revente, à plus de 7 000 USD ; `jiku.com`
  et `jiku.org` sont pris. `hellojiku.com` garde le nom de la marque dans un
  `.com`, se dicte sans faute en français et en anglais, et se renouvelle au
  prix coûtant.
- Le domaine est enregistré chez Porkbun ; son DNS est chez Cloudflare (proxy,
  pare-feu, certificats, Email Routing pour `support@`, `contact@` et
  `privacy@`).
- Sous-domaines publics : `hellojiku.com`, `www`, `api`, `stats` (Umami) et
  `chat` (Chatwoot). Dokploy et n8n ne sont joignables que par Tailscale.
- L'ancien domaine de production `jiku.mrvin100.de` est redirigé en 308 vers
  `hellojiku.com`, chemin compris, pour que les liens déjà envoyés restent
  valables. La redirection n'est activée qu'après la réponse de Meta à la
  vérification de MRVIN100 SARLU, déposée avec ce domaine, et reste en place au
  moins un an.
- `jiku-web.vercel.app` reste la préproduction, hors des moteurs de recherche
  (`X-Robots-Tag: noindex`).

## Décision 2 — Un seul point de collecte : le compte bancaire de la société

Deux flux d'argent restent séparés :

- **Les invités et clients paient l'organisation**, par ses propres moyens
  (Mobile Money, son lien de paiement, espèces). Jikū n'encaisse jamais cet
  argent : le faire serait une activité d'établissement de paiement.
- **Les organisations paient Jikū.** Tout finit sur le compte bancaire de
  MRVIN100 SARLU, la seule trésorerie ; chaque paiement produit une facture
  numérotée (JIKU-69).

Au lancement : un numéro marchand **Orange Money** au nom de la société, vidé
chaque semaine vers le compte bancaire, et le **virement bancaire**. Pas de
second numéro Mobile Money : un client sur MTN MoMo paie par virement.

Quand un prestataire est ouvert (JIKU-272), le paiement en ligne devient le
moyen principal, choisi selon la monnaie (Lengo Pay pour le GNF, DexPay pour le
FCFA, la carte pour l'USD), avec versement sur le compte bancaire. Le paiement
manuel reste en secours : virement sur facture des entreprises et des
administrations, panne du prestataire.

## Décision 3 — E-mails : une chaîne de fournisseurs, useSend en premier

Les envois de la plateforme passent par useSend, puis Resend, puis Brevo, chacun
dans la limite de son offre gratuite (environ 500 par jour au total). Au-delà,
l'offre payante de useSend (à l'usage) prend le relais quand elle est activée ;
sinon l'envoi attend le lendemain. Chaque fournisseur a son domaine vérifié et
ses retours (rebonds, plaintes) branchés (JIKU-275).

Quand le volume le justifie, tout passe sur useSend, pour une seule réputation
d'envoi. Un serveur de messagerie sur notre propre machine est exclu : Hetzner
bloque le port 25 et une adresse neuve n'a aucune réputation.

## Décision 4 — Un seul canal par invité

Une invitation n'arrive jamais par e-mail et par WhatsApp en même temps. Chaque
événement a un canal prioritaire ; l'autre canal ne sert que si l'invité n'a pas
la coordonnée du premier, ou si la livraison a échoué définitivement, une seule
fois. L'import peut fixer le canal d'un invité, et une relance choisit son
canal (JIKU-273).

## Décision 5 — Quatre numéros WhatsApp, aucun numéro personnel

Les quatre rôles de l'ADR 108 (ventes, assistance, invitations, réserve) sont
tenus par deux lignes Orange et deux lignes Areeba (MTN). Aucun numéro
personnel n'est relié à l'API ni cité dans une page. La double authentification
des comptes (Meta, Cloudflare, Hetzner) passe par une application
d'authentification plutôt que par SMS.

## Décision 6 — Préproduction sur le serveur en décembre

Jusqu'en décembre, chaque version est testée en local puis livrée en
production, avec le retour en arrière de Dokploy et les sauvegardes R2. Avant
l'arrivée des premières organisations, la production utilise le numéro de test
de Meta (cinq destinataires) pour valider les envois WhatsApp de bout en bout.

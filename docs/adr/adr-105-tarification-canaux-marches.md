# ADR 105 — Tarification unique multi-pays, modes d'envoi et marchés prioritaires

**Statut :** accepté.
**Date :** 2026-09-25
**Remplace :** ADR 104, §8 (tarification). Le reste de l'ADR 104 reste en vigueur.
**Amont :** analyse de prix du 2026-09-25, `docs/jiku-modele-financier.md`.

## Contexte

Trois constats ont conduit à revoir la grille de l'ADR 104 :

1. **Les services coûtaient trop cher dès qu'une équipe grandit.** Chaque
   personne payait le plein tarif : un salon de 3 personnes payait 300 000 GNF
   par mois, une clinique de 10 médecins en offre Organisation 2 400 000 GNF.
2. **Les invitations étaient vendues bien sous leur valeur.** À 500 GNF par
   invité, Jikū coûte 10 à 60 fois moins qu'une carte imprimée. À Abidjan, une
   carte coûte de 300 FCFA (lot de 100 à 30 000 FCFA) à 2 000 FCFA l'unité, soit
   environ 4 600 à 30 000 GNF. Dans le pire cas (invitation WhatsApp classée
   « marketing » par Meta), la marge tombait à 50 %.
3. **Jikū vise tous les pays couverts par CinetPay**, pas seulement la Guinée.
   Il faut une grille lisible dans chaque monnaie, sans prix par zone.

S'y ajoute une demande produit : **l'organisateur choisit comment ses invités
reçoivent leur billet** (lien, billet direct, invitation interactive
WhatsApp), et voit le prix de ce choix dans le simulateur.

## Décision 1 — Une seule grille, trois monnaies

Une même valeur partout, exprimée en montants ronds dans trois monnaies :

| Monnaie | Pays | Repère de change |
|---|---|---|
| **GNF** | Guinée | 1 USD ≈ 8 760 GNF |
| **FCFA** (XOF et XAF, même montant) | UEMOA (Côte d'Ivoire, Sénégal, Mali, Burkina Faso, Bénin, Togo, Niger, Guinée-Bissau) et CEMAC (Cameroun, Congo, Gabon, Tchad, Centrafrique, Guinée équatoriale) | 1 USD ≈ 570 FCFA, soit 1 000 FCFA ≈ 15 000 GNF |
| **USD** | Libéria, RDC et tout pays payé par carte | — |

- Le XOF et le XAF ont la même parité fixe avec l'euro : une seule colonne FCFA
  couvre les deux zones.
- La monnaie d'une organisation est fixée par son pays à l'inscription et ne
  change plus. Ses factures sont émises dans cette monnaie.
- Les montants sont des paramètres de configuration par monnaie, jamais des
  valeurs écrites dans le code. Un taux de change ne sert qu'à afficher un
  équivalent indicatif.
- Tous les encaissements passent par CinetPay (carte, Orange Money, MTN MoMo…)
  vers le compte de l'entreprise en Guinée. Wave est ajouté en direct dans les
  pays où l'entreprise est enregistrée. **À confirmer avec CinetPay avant
  l'ouverture d'un pays :** encaissement multi-pays sur un seul contrat, frais
  de change et délai de règlement vers la Guinée.

## Décision 2 — Services : gratuit pour adopter, payant pour grandir

L'objectif de Solo est l'**adoption massive et l'usage quotidien** (comme
cal.com). Il reste gratuit pour toujours. Les offres payantes vendent la
croissance : WhatsApp, équipe, marque propre, plusieurs sites.

| Offre | GNF / mois | FCFA / mois | USD / mois | Contenu |
|---|---|---|---|---|
| **Solo** | 0 | 0 | 0 | 1 personne, réservations illimitées, rappels par e-mail, **50 rappels WhatsApp par mois**, page « Propulsé par Jikū » |
| **Solo Plus** | 50 000 | 3 500 | 6 | 1 personne, 300 rappels WhatsApp par mois, votre marque |
| **Teams** | 150 000 pour 2 personnes, + 50 000 par personne en plus | 10 000, + 3 500 | 17, + 6 | Groupes jusqu'à 10, 300 rappels WhatsApp par personne |
| **Organisation** | 350 000 pour 5 personnes, + 40 000 par personne en plus | 25 000, + 2 500 | 40, + 5 | Plusieurs sites, groupes jusqu'à 30, support prioritaire, **votre propre numéro WhatsApp** |
| **Entreprise** | Sur devis | | | |

- **Paiement à l'année : 2 mois offerts** (10 mois payés pour 12), sur toutes
  les offres. Les prépaiements de 3 et 6 mois disparaissent.
- Seules les personnes qui servent des clients comptent. Administrateurs,
  contrôleurs et coursiers restent gratuits.
- Repères du marché : GestoclocPro à partir de 12 000 FCFA par mois ; Medikits
  facture 50 000 FCFA de mise en place ; KiboERP propose une offre gratuite à vie.
  Teams à 10 000 FCFA se place juste sous le premier repère.

## Décision 3 — Événements : trois modes d'envoi au choix

L'organisateur choisit un mode par événement :

| Mode | Ce que reçoit l'invité | Prix |
|---|---|---|
| **Lien d'invitation** | Un message avec le lien ; il répond sur la page | Inclus dans le palier |
| **Billet direct** | Le billet lui-même (QR code), sans confirmation à donner | Inclus dans le palier |
| **Invitation interactive WhatsApp** | Boutons « Je confirme / Je ne pourrai pas » ; le billet arrive dans la conversation | **+150 GNF / +10 FCFA / +0,02 USD par invité** |

Paliers par événement :

| Palier | Invités | GNF | FCFA | USD |
|---|---|---|---|---|
| **Gratuit** | 100 invités sur 12 mois glissants, par organisation **vérifiée** | 0 | 0 | 0 |
| **Bronze** | ≤ 300 | 225 000 | 15 000 | 25 |
| **Argent** | ≤ 600 | 375 000 | 25 000 | 45 |
| **Or** | ≤ 1 000 | 600 000 | 40 000 | 70 |
| **Au-delà** | Prix Or + chaque invité au-delà de 1 000 | + 500 / invité | + 35 / invité | + 0,06 / invité |

- Le gratuit inclut les trois modes : c'est la vitrine. Son coût est plafonné
  (100 invités par an, soit au pire ≈ 23 000 GNF par organisation).
- Passer au palier supérieur ne fait payer que la différence (inchangé).
- Le prix au-delà de 1 000 invités est désormais fixé dans chaque monnaie,
  sans conversion depuis le dollar.
- Le SMS reste une option, affichée avec son prix exact au moment de l'envoi :
  environ 1,8 fois son coût dans le pays du destinataire.

**Pack Organisateur** (wedding planners, agences, salles) : **600 000 GNF /
40 000 FCFA / 70 USD par mois**.
- 1 000 invités par mois, répartis sur autant d'événements que voulu, tous
  modes compris.
- Au-delà : 600 GNF / 40 FCFA / 0,07 USD par invité.
- Chaque événement à la marque du client.
- Votre propre numéro WhatsApp inclus.
- Les invités non utilisés ne se reportent pas.

## Décision 4 — Ventes de billets : 3 %, sans blocage le jour J

- La commission reste de **3 %** du prix de chaque billet vendu, payée par
  tranche de 50 billets avant la vente (ADR 104).
- **Première tranche offerte** lors de la première vente d'une organisation.
- **Une tranche à crédit** pour les organisations vérifiées, réglée avec la
  tranche suivante.
- **Jamais de pause le jour de l'événement** : la vente et l'entrée continuent,
  et la commission due est réclamée après l'événement.
- Les billets vendus sont envoyés en « billet direct », inclus.

## Décision 5 — Identité de l'expéditeur WhatsApp

**Par défaut, numéro partagé de Jikū.** Pour que le message paraisse venir de
l'organisateur :
- le texte commence par son nom (« Maison Aminata vous invite… ») et se termine
  par sa signature ;
- l'en-tête du message porte son logo ou le visuel de l'événement ;
- le lien ouvre sa page, à ses couleurs ;
- le profil du numéro indique « Invitations et billets envoyés pour le compte
  des organisateurs ».

**Option « votre propre numéro WhatsApp ».** Jikū devient fournisseur
technique (Tech Provider) de Meta. L'organisation connecte son numéro en
quelques clics (Embedded Signup). Les réponses arrivent chez Jikū sans aucune
configuration de sa part. **Meta facture alors les messages directement à
l'organisation**, ce qui supprime ce coût pour Jikū.
- Inclus dans Organisation et le Pack Organisateur.
- Sinon, option à **100 000 GNF / 7 000 FCFA / 12 USD par mois**.

Le lien « Propulsé par Jikū » reste sur les pages et les billets, sauf dans les
offres payantes avec marque propre.

## Économie unitaire

Coûts (tarifs Meta, zone « Rest of Africa ») :
- message marketing : 0,0225 USD ≈ 197 GNF ;
- message utilitaire : 0,004 USD ≈ 35 GNF ;
- réponses gratuites dans les 24 h qui suivent un message de l'invité ;
- e-mail ≈ 16 GNF par invité ;
- encaissement CinetPay : 3,5 %.

Coût par invité, avec un rappel envoyé à 60 % des invités :

| Mode | Pire cas (invitation classée marketing) | Cas favorable (utilitaire) |
|---|---|---|
| Lien d'invitation | ≈ 234 GNF | ≈ 72 GNF |
| Billet direct | ≈ 72 GNF | ≈ 72 GNF |
| Interactive | ≈ 234 GNF | ≈ 72 GNF |

Marge brute dans le pire cas, palier rempli :

| Palier | Lien | Billet direct | Interactive | Ancienne grille, lien |
|---|---|---|---|---|
| Bronze | 65 % | 87 % | 70 % | 50 % |
| Argent | 59 % | 85 % | 66 % | 50 % |
| Or | 57 % | 84 % | 65 % | 50 % |
| Pack (1 000 à 2 000 invités) | 57 % | — | — | — |

Services, marge brute :
- Solo Plus : 75 %.
- Teams : 82 %.
- Organisation : 81 %.
- Un compte Solo coûte au plus ≈ 1 750 GNF par mois en rappels WhatsApp : c'est
  le coût d'acquisition.

## Marchés prioritaires

1. **Mois 0 à 6, Conakry, événements.**
   - Cibles :
     - les wedding planners et les salles de fêtes ;
     - les hôtels ;
     - les événements d'entreprise et d'ONG (conférences, ateliers).
   - Pourquoi :
     - le problème est aigu (listes d'invités, intrus aux mariages) ;
     - la décision est prise vite, par une seule personne, sous la pression d'une date ;
     - chaque événement montre Jikū à des centaines d'invités ;
     - le marché est local (entreprise, Mobile Money, Wave).
   - Les services s'installent sans effort de vente, par Solo gratuit.
2. **Mois 4 à 12, Conakry, services.**
   - Cibles : les cliniques et cabinets, puis les files des agences à forte
     affluence (banques, opérateurs, administrations).
   - C'est le revenu récurrent.
3. **Mois 9 à 18 : Abidjan, puis Dakar, puis Douala.** Ce sont les plus gros
   marchés CinetPay, avec un pouvoir d'achat plus élevé et une forte culture de
   l'événement. La grille FCFA couvre toute l'UEMOA et la CEMAC.

## Estimations (revenu mensuel à 12 mois)

Hypothèses : Guinée seule dans les scénarios prudent et central ; début
d'Abidjan dans le scénario ambitieux.

| | Prudent | Central | Ambitieux |
|---|---|---|---|
| Événements payants par mois (prix moyen) | 15 (300 000) | 40 (320 000) | 100 (350 000) |
| Packs Organisateur | 2 | 8 | 20 |
| Solo Plus / Teams / Organisation | 20 / 10 / 2 | 80 / 40 / 8 | 250 / 120 / 25 |
| Événements avec billets vendus (billets × prix) | 5 (200 × 50 000) | 15 (300 × 50 000) | 40 (300 × 60 000) |
| **Revenu mensuel** | **≈ 11,3 M GNF (≈ 1 300 USD)** | **≈ 40,8 M GNF (≈ 4 650 USD)** | **≈ 118,8 M GNF (≈ 13 600 USD)** |
| **Revenu annualisé** | ≈ 135 M GNF | ≈ 489 M GNF | ≈ 1,43 Md GNF |
| Marge brute | ≈ 64 % | ≈ 71 % | ≈ 72 % |

- Chaque tranche de 10 M GNF de charges fixes mensuelles (salaires, marketing)
  demande environ 15 M GNF de revenu mensuel pour être couverte.
- Le scénario central couvre une petite équipe vers le 9e ou le 12e mois.

## Fiscalité (à valider avec un expert-comptable guinéen)

- **Sous 150 M GNF de chiffre d'affaires annuel :** TPU de 5 % du chiffre
  d'affaires. Elle remplace l'impôt sur les sociétés, la patente et l'IMF.
- **La TVA (18 %) devient obligatoire à partir de 500 M GNF** de chiffre
  d'affaires annuel. C'est une taxe collectée sur les ventes, distincte de
  l'impôt sur le bénéfice.
- **Les prix sont affichés TTC dès le départ**, pour que le client ne voie
  jamais son prix augmenter. Si la TVA s'applique, elle est prise sur la marge
  (≈ 15 % du prix TTC).
- Les ventes aux clients d'autres pays peuvent relever de règles locales sur
  les services numériques : à vérifier avant d'ouvrir chaque pays.

## Mise en œuvre (une story par ligne, fusionnée avant la suivante)

1. Backend : grille par monnaie (monnaie de l'organisation). Services par
   personne, avec personnes incluses, personne supplémentaire et « 2 mois
   offerts » à l'année.
2. Backend : nouveaux paliers d'événement, mode d'envoi par événement,
   supplément « interactive », prix au-delà de 1 000 invités en monnaie locale,
   Pack Organisateur. Ventes de billets : première tranche offerte, tranche à
   crédit, jamais de pause le jour J.
3. Web : `lib/pricing.ts`, simulateur (choix du mode d'envoi, monnaie),
   sections prix de l'accueil, choix du mode dans l'événement.
4. WhatsApp interactif :
   - modèles de messages avec boutons et en-tête image ;
   - réception des réponses (signature vérifiée) ;
   - confirmation et billet (image QR) dans la conversation ;
   - mot-clé « billet » pour le renvoyer, et « STOP ».
5. Billet direct : modèle « billet » avec le QR code en en-tête.
6. Votre propre numéro WhatsApp : Embedded Signup en tant que Tech Provider.

## Sources

- Cartes d'invitation : [Klas Events, Abidjan](https://klasevents225.com/en/produit/carte-dinvitation/), [Printxi](https://printxi.ci/vos-impressions-en-ligne/imprimez-vos-fichiers-en-ligne-sans-vous-deplacez/carte-dinvitation-20x10-recto/), [Jumia CI](https://www.jumia.ci/generic-01-lot-de-10-cartes-dinvitation-pour-mariage-ou-fiancailles-31797992.html)
- Logiciels de gestion : [GestoclocPro](https://gestoclocpro.com/), [Medikits](https://medikits-sn.com/), [KiboERP](https://kiboerp.com/erp/cabinet-medical)
- WhatsApp : [tarifs Meta](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing), [catégories de modèles](https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/template-categorization), [changement de juillet 2025](https://www.ycloud.com/blog/whatsapp-api-pricing-update), [Embedded Signup](https://developers.facebook.com/documentation/business-messaging/whatsapp/embedded-signup/overview/), [Tech Provider](https://whautomate.com/whatsapp-tech-provider-vs-bsp)
- CinetPay : [monnaies prises en charge](https://cartdna.com/fr-fr/shopify-payment-methods/cinetpay)
- Fiscalité guinéenne : [DGI, TVA](https://dgi.gov.gn/wp-content/uploads/2021/03/TAXE-SUR-LA-VALEUR-AJOUTEE.pdf), [guide simplifié des impôts](https://mbudget.gov.gn/wp-content/uploads/2019/09/guideimp%C3%B4ts.pdf), [cadre fiscal](https://www.invest.gov.gn/page/cadre-juridique-et-fiscal?onglet=fiscalite-des-entreprises)

# Jikū — Modèle financier (estimation)

**Date :** 2026-09-24
**Statut :** estimation de travail. Les tarifs des prestataires changent ; les
chiffres sont à revoir à chaque renégociation ou changement de grille.
**Référence :** ADR 104. La grille et les marges à jour sont dans l'ADR 105,
qui remplace les prix utilisés ci-dessous.

Toutes les valeurs sont en francs guinéens (GNF), au taux configuré dans
l'application : **1 USD = 8 760 GNF** (`billing.custom.usd-to-gnf-rate`).

Rappel : Jikū n'encaisse que ses propres revenus (ADR 104, §4). L'argent des
clients finaux ne passe jamais par Jikū : il n'entre ni dans le chiffre
d'affaires ni dans les coûts ci-dessous.

## 1. Coût unitaire de chaque action

| Action | Prestataire | Coût unitaire | En GNF |
|---|---|---|---|
| E-mail | Resend (au-delà du forfait) | 0,90 USD / 1 000 | ≈ 8 |
| Message WhatsApp utilitaire (Afrique hors pays listés) | Meta | 0,004 USD | ≈ 35 |
| Message WhatsApp marketing (même zone) | Meta | 0,0225 USD | ≈ 197 |
| SMS en Guinée | Nimba SMS | jusqu'à 110 GNF | 110 |
| Encaissement d'un revenu de Jikū | CinetPay, taux public brut | 3,5 % du montant | — |

Trois conséquences directes :

1. **Les messages WhatsApp doivent rester « utilitaires ».** Un message
   marketing coûte plus de cinq fois plus. Le garde-fou existe déjà dans le
   code (`WhatsAppContentGuard`, table `whatsapp_pricing`).
2. **Le SMS coûte environ trois fois un WhatsApp utilitaire.** C'est pour cela
   qu'il est vendu en option, au prix affiché au moment de l'envoi.
3. **Le suivi du rang dans la file se fait sur la page du ticket**, pas par
   message à chaque avancée : 400 tickets par jour avec un SMS chacun
   représenteraient 44 000 GNF par jour pour une seule organisation.

## 2. Marge par produit

### Événement avec tickets gratuits

Hypothèse par invité : 3 messages WhatsApp utilitaires (invitation, billet,
rappel) et 2 e-mails, soit **≈ 121 GNF par invité**. Chaque palier est compté
à son nombre maximal d'invités, donc au coût le plus élevé.

| Palier | Prix | Invités max | Prix par invité | Coût (messages + 3,5 %) | Marge brute |
|---|---|---|---|---|---|
| Bronze | 150 000 | 300 | 500 | ≈ 41 500 | ≈ 72 % |
| Argent | 300 000 | 600 | 500 | ≈ 83 000 | ≈ 72 % |
| Or | 500 000 | 1 000 | 500 | ≈ 138 400 | ≈ 72 % |
| Sur mesure (2 000 invités) | ≈ 1 007 400 | 2 000 | ≈ 504 | ≈ 277 000 | ≈ 72 % |

Si l'e-mail et WhatsApp échouaient pour 30 % des invités et étaient remplacés
par deux SMS, le coût par invité monterait à **≈ 187 GNF** et la marge vers
**≈ 59 %**. Le prix d'un événement doit donc rester calé sur WhatsApp, et le
SMS rester une option facturée à part.

### Services (abonnement)

Hypothèse : 150 rendez-vous par utilisateur et par mois, 2 rappels WhatsApp
utilitaires chacun.

| Offre | Prix / utilisateur / mois | Coût (rappels + 3,5 %) | Marge brute |
|---|---|---|---|
| Teams | 100 000 | ≈ 14 000 | ≈ 86 % |
| Teams, si les rappels partaient en SMS | 100 000 | ≈ 36 500 | ≈ 64 % |
| Solo (gratuit, e-mails seulement, 300 rappels / mois) | 0 | ≈ 2 400 | coût d'acquisition |

### Tickets payants (commission)

Jikū ne touche pas l'argent des ventes : la commission est un revenu presque
sans coût direct, à part les 3,5 % payés pour l'encaisser et l'envoi des
billets. Taux décidé : **3 % du prix de chaque billet**, payé par tranche avant
la vente (ADR 104, §8).

## 3. Coûts fixes mensuels

| Poste | ~100 organisations clientes | ~1 000 organisations clientes |
|---|---|---|
| API (Render Standard, puis Pro ou deux instances) | 25 USD | 85 USD |
| Web (Vercel Pro) | 20 USD | 20 USD |
| Base de données (Neon, à l'usage) | ≈ 20 USD | ≈ 70 USD |
| E-mail (Resend Pro, puis Scale) | 20 USD | 90 USD |
| Suivi des erreurs (Sentry) | 0 USD | 26 USD |
| Débordement e-mail, domaine, divers | ≈ 5 USD | ≈ 40 USD |
| **Total** | **≈ 90 USD (≈ 0,8 M GNF)** | **≈ 330 USD (≈ 2,9 M GNF)** |

L'infrastructure pèse peu. Le runbook Hetzner de `docs/deploy.md` peut encore
réduire le poste API (une petite machine coûte quelques euros par mois), au
prix d'une exploitation à assurer soi-même.

**Non compté ici :** salaires, support, marketing et vente, frais juridiques
et comptables, impôts, frais de création de l'entreprise. Ce sont eux, et non
l'infrastructure, qui décideront de la rentabilité.

## 4. Monnaies

CinetPay encaisse dans plusieurs pays d'Afrique francophone, donc dans
plusieurs monnaies (GNF, francs CFA, et d'autres selon le contrat). Trois
façons de gérer les prix :

| Option | Principe | Avantage | Limite |
|---|---|---|---|
| **A. Une grille par monnaie** (retenue) | Chaque prix est fixé à la main pour chaque monnaie, en montant rond | Prix stables et lisibles pour le client ; aucune surprise de change | Une grille à tenir par pays |
| B. Une monnaie de référence convertie | Prix en USD, convertis au taux du jour | Une seule grille | Prix qui bougent chaque jour et montants non ronds |
| C. GNF seulement au départ | Une seule monnaie tant qu'on reste en Guinée | Rien à construire | Bloque l'ouverture d'un deuxième pays |

Avec l'option A, la monnaie d'une organisation est fixée par son pays à
l'inscription et ne change plus. Ses factures sont émises dans cette monnaie,
et un équivalent en USD est affiché à titre indicatif, comme sur le simulateur
aujourd'hui.

## 5. Scénarios de revenus et valorisation

Hypothèses (à ajuster) sur la répartition des organisations **payantes** :

- 50 % font des événements avec tickets gratuits : 1,5 événement par an, prix
  moyen 250 000 GNF ;
- 40 % utilisent les services : 2 utilisateurs en Teams (100 000 GNF par
  utilisateur et par mois) ;
- 10 % vendent des billets : 50 M GNF de ventes par an, commission de 3 %.

| | 100 organisations | 1 000 organisations |
|---|---|---|
| Événements | 18,8 M GNF | 187,5 M GNF |
| Services | 96,0 M GNF | 960,0 M GNF |
| Commission sur billets | 15,0 M GNF | 150,0 M GNF |
| **Revenu annuel récurrent** | **≈ 130 M GNF (≈ 14 800 USD)** | **≈ 1,3 Md GNF (≈ 148 000 USD)** |
| Coûts directs (messages, 3,5 %, infrastructure) | ≈ 28,6 M GNF | ≈ 226 M GNF |
| Marge brute | ≈ 78 % | ≈ 83 % |
| **Valorisation indicative (3 à 5 fois le revenu annuel)** | **≈ 44 000 à 74 000 USD** | **≈ 444 000 à 741 000 USD** |

Lecture :

- **Les services font l'essentiel du revenu** (environ 74 %), parce qu'ils se
  renouvellent chaque mois. Les événements rapportent peu par organisation, mais
  ils servent d'entrée : un organisateur de mariage peut devenir un client
  services.
- Le multiple de 3 à 5 fois le revenu annuel correspond aux ventes récentes de
  petits SaaS autofinancés (médiane autour de 4,5 à 4,8). Il ne vaut que si les
  clients restent : un taux de départ élevé le fait chuter.
- Ces montants excluent les charges de personnel. À 100 organisations, le
  revenu ne couvre pas un salaire à plein temps ; le seuil de viabilité se situe
  plutôt vers plusieurs centaines d'organisations payantes.

## Sources

- CinetPay : [tarification des paiements](https://support.cinetpay.com/d/52-tarifications-des-paiements-entrants-et-sortants), [solde et délai de règlement](https://docs.cinetpay.com/api/1.0-fr/BO/finance)
- WhatsApp : [tarifs Meta](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing), [synthèse 2026](https://sleekflow.io/blog/whatsapp-business-price)
- Nimba SMS : [tarifs](https://www.nimbasms.com/nos-tarifs)
- Resend : [tarifs](https://resend.com/docs/knowledge-base/what-is-resend-pricing)
- Render : [coûts d'hébergement](https://render.com/articles/how-much-does-cloud-application-hosting-cost-for-small-businesses)
- Neon : [tarifs](https://neon.com/pricing)
- Valorisation : [SaaS Capital, SaaS autofinancés 2026](https://www.saas-capital.com/blog-posts/benchmarking-metrics-for-bootstrapped-saas-companies/), [multiples par tranche de revenu](https://windsordrake.com/saas-valuation-multiples/)

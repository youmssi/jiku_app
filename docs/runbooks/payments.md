# Runbook — paiements en ligne (DEXPAY) (JIKU-272, JIKU-298)

Ce runbook couvre trois cas : l'alerte « paid but not granted », les remboursements, et
le test de l'intégration DEXPAY avant le passage en réel.

## Comment un paiement est accordé

1. L'organisateur lance un achat. Le paiement est créé `PENDING`, puis une session de
   paiement DEXPAY est ouverte avec la clé publique (`DEXPAY_API_KEY`).
2. DEXPAY envoie une notification à `DEXPAY_WEBHOOK_URL`, signée en HMAC-SHA256 avec
   `DEXPAY_API_SECRET` (en-tête `X-Webhook-Signature`). Une notification mal signée
   reçoit 401 et n'a aucun effet.
3. Le paiement est verrouillé (`SELECT … FOR UPDATE`) avant d'être lu. Une notification
   rejouée, ou arrivée en même temps que la tâche de rapprochement, trouve donc le
   paiement déjà traité et ne l'accorde pas une seconde fois.
4. Si la notification n'arrive pas, `PaymentReconciliationJob` interroge DEXPAY toutes
   les `PAYMENT_RECONCILE_DELAY` pour les paiements en attente depuis plus de
   `PAYMENT_RECONCILE_AFTER`. Un paiement toujours inachevé après `PAYMENT_ABANDON_AFTER`
   est clos en `FAILED`.

Le montant et la devise annoncés par DEXPAY doivent égaler ceux du paiement. Sinon,
rien n'est accordé et l'alerte ci-dessous part.

## Alerte « Jikū payment … paid but not granted »

Elle arrive par e-mail à `NOTIFICATION_SALES_EMAIL`. Ses deux causes possibles :

- **Montant ou devise différents** : DEXPAY annonce une somme qui n'est pas celle du
  paiement. Le paiement est clos en `FAILED`.
- **Payé après clôture** : DEXPAY annonce un paiement réussi alors qu'il avait déjà été
  clos en `FAILED`, par exemple après `PAYMENT_ABANDON_AFTER`.

L'e-mail donne l'organisation, l'identifiant du paiement, son type, le prestataire et
la raison.

### Que faire (sous 24 h ouvrées)

1. Retrouvez la transaction dans le tableau de bord DEXPAY par sa référence. C'est
   l'identifiant du paiement Jikū, aussi présent dans `metadata.jiku_reference` sous la
   forme `<organisation>:<paiement>`. Notez le montant réellement encaissé.
2. Contactez l'organisateur avec l'adresse de son compte.
3. Choisissez **une seule** issue :
   - **Rembourser puis racheter** (choix par défaut) : remboursez depuis le tableau de
     bord DEXPAY, voir « Remboursements » ci-dessous. L'organisateur refait ensuite son
     achat normalement. C'est la seule issue pour un forfait ou un abonnement.
   - **Accorder** (seulement pour des invités d'un événement, quand le montant
     encaissé est le bon) : l'organisateur demande une activation manuelle depuis la
     page de paiement de l'événement. Confirmez-la dans le bureau d'administration
     (Paiements → Confirmer), avec comme référence observée celle de la transaction
     DEXPAY. Ne remboursez pas dans ce cas.
4. Notez l'issue et la référence DEXPAY dans le suivi des incidents.

Ne modifiez jamais le statut d'un paiement directement en base. Un paiement accordé à
la main sans passer par le bureau ne laisse aucune trace d'audit.

## Demandes de virement (JIKU-307)

Le virement reste proposé à côté du paiement en ligne. Une demande vit ainsi :

- `PENDING` tant que le virement n'est pas confirmé. Si l'organisateur clique
  « J'ai envoyé le virement », une alerte opérations « Jikū transfer sent: JK-… »
  arrive, et le bureau des paiements montre la date à côté de la demande.
- `CANCELLED` si l'organisateur l'annule, s'il demande autre chose pour le même achat
  (une nouvelle référence remplace l'ancienne), ou si un paiement en ligne règle ce
  même achat.
- `EXPIRED` sans virement confirmé après `BILLING_MANUAL_EXPIRE_AFTER` (7 jours) ;
  l'organisateur reçoit un e-mail.

Un virement peut arriver après l'annulation ou l'expiration : le bureau peut toujours
confirmer une demande `CANCELLED` ou `EXPIRED`, l'achat est alors accordé normalement.
Vérifiez seulement que l'achat n'a pas déjà été payé en ligne (historique de
l'organisation) ; sinon, remboursez le virement. Un refus ne vaut que pour une demande
`PENDING`, et son motif est montré à l'organisateur.

## Remboursements

Au lancement, les remboursements sont **manuels** : aucune API de remboursement n'est
appelée par Jikū.

1. Vérifiez la demande. Elle vient d'une alerte ci-dessus, ou de l'organisateur avec
   la référence du paiement.
2. Remboursez depuis le tableau de bord DEXPAY, sur la transaction concernée, pour le
   montant encaissé.
3. Ne remboursez qu'un paiement **non accordé** (`FAILED` ou `PENDING`). Le bureau
   d'administration n'a pas encore d'action pour retirer un forfait ou des invités
   déjà ouverts. Une demande de remboursement d'un paiement `SUCCEEDED` remonte donc
   au responsable produit avant tout remboursement.
4. Prévenez l'organisateur par e-mail, avec la référence DEXPAY du remboursement et le
   délai annoncé par DEXPAY pour son moyen de paiement.

On automatisera les remboursements quand leur volume le justifiera, et après avoir
vérifié ce que l'API DEXPAY propose une fois le contrat signé.

## Test avant le passage en réel

Avec les clés de test, `DEXPAY_BASE_URL=https://api-sandbox.dexpay.africa/api/v1`, et
l'API joignable publiquement à `DEXPAY_WEBHOOK_URL`.

Les clés se saisissent uniquement dans la configuration du serveur. Elles ne vont
jamais dans un ticket, un message ni le dépôt.

| # | Scénario | Résultat attendu |
|---|---|---|
| 1 | Achat payé avec succès | Paiement `SUCCEEDED` en moins d'une minute, forfait ou invités ouverts, retour sur `/billing/return` |
| 2 | Paiement refusé (échec) | Paiement `FAILED`, rien d'ouvert, l'organisateur peut recommencer |
| 3 | Paiement abandonné (page fermée) | `PENDING`, puis `FAILED` après `PAYMENT_ABANDON_AFTER` (le réduire pour le test) |
| 4 | Notification rejouée depuis le tableau de bord DEXPAY | Réponse 200, une seule ouverture (un seul forfait, durée non doublée) |
| 5 | Notification bloquée (URL de webhook fausse le temps du test) | La tâche de rapprochement accorde le paiement à son passage suivant |
| 6 | Notification avec une mauvaise signature (`curl` avec un autre secret) | 401, paiement inchangé |

Puis, **en réel** avec les clés de production : un petit achat payé par un membre de
l'équipe. Vérifiez qu'il est accordé, puis remboursez-le en suivant « Remboursements »
pour tester aussi la procédure.

Le passage en réel est validé quand les six scénarios et l'achat réel sont passés.
Consignez la date et les références dans le suivi des incidents.

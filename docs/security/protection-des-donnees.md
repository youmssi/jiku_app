# Protection des données personnelles — registre, déclarations et procédures

**Date :** 2026-10-18 (JIKU-267)
**Responsable :** MRVIN100 SARLU, éditeur de Jikū, Conakry.
**Référence :** loi guinéenne L/2016/037/AN sur la cybersécurité et la protection des
données à caractère personnel ; politique de confidentialité publiée (`/privacy`).

Ce document répond à trois questions : quelles données Jikū traite et combien de
temps (§1), ce qu'il reste à déclarer (§2), et que faire en cas de fuite (§3).
Il est tenu à jour dans la même PR que tout changement qui ajoute une donnée,
un prestataire ou une durée.

## 1. Registre des traitements

Pour les invités et les clients des organisations, **l'organisation est
responsable du traitement et Jikū est sous-traitant** (CGU, article 8). Pour les
comptes, la facturation, les prospects et les conversations commerciales, Jikū
est responsable du traitement.

| # | Traitement | Personnes | Données | Finalité et base | Conservation | Mécanisme |
|---|---|---|---|---|---|---|
| 1 | Invitations et billets | Invités d'une organisation | Nom, téléphone, e-mail, réponse, billet, entrée | Exécuter le service pour l'organisation (sous-traitance) | Anonymisation 12 mois après l'événement ; immédiate sur demande de l'invité | `GuestRetentionJob`, `GuestErasureService` |
| 2 | Réponses à une carte partagée | Personnes qui répondent | Nom, téléphone, nombre d'accompagnants | Idem | Idem | `GuestRetentionJob` |
| 3 | Vente de billets | Acheteurs | Nom, téléphone, e-mail, référence de paiement déclarée | Idem | Nom, téléphone et e-mail anonymisés 12 mois après l'événement ; la référence et les montants restent pour la comptabilité de l'organisation | `GuestRetentionJob`, `TicketOrder.erase()` |
| 4 | Rendez-vous et file du jour | Clients des services | Nom, téléphone, créneau, rang | Idem | Nom et téléphone retirés 12 mois après le rendez-vous ou le ticket | `ServiceClientRetentionJob`, `LineTicketRetentionJob` |
| 5 | Envois de messages | Destinataires | Numéro ou adresse, statut d'envoi | Livrer et prouver les envois, protéger la réputation des numéros | 180 jours ; les refus définitifs (STOP, adresses invalides) sont gardés pour ne plus écrire | `MessagingLogPurgeJob` |
| 6 | Comptes des organisations | Organisateurs, membres, opérateurs | Nom, e-mail, mot de passe chiffré, rôle, téléphone vérifié | Contrat | Tant que le compte est ouvert ; à la fermeture, export sur demande puis suppression sous 30 jours (§4) | Procédure manuelle §4 |
| 7 | Vérification des organisations | Gérants, entreprises | Pièces d'identité, documents d'entreprise | Obligation de prévenir la fraude avant d'encaisser | Documents d'une demande acceptée supprimés 30 jours après la décision ; ceux d'une demande refusée, aussitôt | `verification.document-retention` |
| 8 | Facturation de Jikū | Organisations | Identité légale, factures, paiements | Obligation comptable (OHADA) | 10 ans | Factures immuables (ADR 69) |
| 9 | Prospects | Professionnels qui écrivent à Jikū | Entreprise, nom, téléphone, ville, secteur, consentement marketing | Intérêt légitime (répondre), consentement (marketing) | Supprimés 12 mois après le premier contact | `ProspectRetentionJob` |
| 10 | Conversations commerciales et assistance | Prospects et clients qui écrivent sur WhatsApp | Numéro, nom de profil, messages | Répondre aux demandes ; un assistant automatique répond d'abord (ADR 108) | 12 mois dans Chatwoot (réglage à poser à l'installation, §5) | Chatwoot, n8n, Anthropic |
| 11 | Mesure d'audience | Visiteurs du site | Pages vues, sans cookie ni identifiant | Intérêt légitime | Agrégats seulement | Umami auto-hébergé |
| 12 | Suivi des erreurs | Utilisateurs | Erreurs techniques, données personnelles retirées avant envoi | Intérêt légitime | Selon Sentry | `PersonalDataScrubber` |

Les prestataires et les transferts hors de Guinée sont listés dans la politique
de confidentialité (`/privacy`, « Qui reçoit les données » et « Transferts »).

## 2. Déclarations auprès des autorités

| Pays | Autorité | État | Action |
|---|---|---|---|
| Guinée | Autorité prévue par la loi L/2016/037/AN | **Non faite.** Nous n'avons pas trouvé de guichet en fonctionnement ni de formulaire publié. | Se renseigner auprès de l'ANSSI et de l'ARPT ; déposer la déclaration dès qu'un guichet existe ; inscrire la référence dans `COMPANY.dpaDeclaration` (web), qui l'affiche alors dans la politique de confidentialité |
| Côte d'Ivoire | ARTCI (Autorité de régulation des télécommunications) | **Non faite.** | À examiner avant d'avoir des organisations clientes établies en Côte d'Ivoire |
| Sénégal | CDP (Commission de protection des données personnelles) | **Non faite.** | Idem pour le Sénégal |

Tant qu'une déclaration n'est pas faite, la politique de confidentialité n'en
mentionne aucune : nous n'affichons pas une référence que nous n'avons pas.

## 3. En cas de fuite de données

Une fuite, c'est une donnée personnelle lue, copiée, modifiée ou supprimée par
quelqu'un qui n'en avait pas le droit : base exposée, sauvegarde perdue, compte
administrateur compromis, export envoyé à la mauvaise personne.

1. **Contenir (dans l'heure).** Couper l'accès en cause : révoquer le compte ou le jeton, faire tourner le secret concerné (`AUTH_JWT_SECRET`, clés des prestataires), fermer la route. Suivre `docs/runbooks/incident-and-rollback.md` pour le reste.
2. **Mesurer (dans la journée).** Quelles données, combien de personnes, quelles organisations, depuis quand. Garder les journaux et noter chaque constat avec l'heure.
3. **Prévenir les organisations touchées (sous 72 heures).** Elles sont responsables de leurs invités et de leurs clients. Message : ce qui s'est passé, les données concernées, ce que Jikū a fait, ce qu'elles doivent faire, un contact.
4. **Prévenir l'autorité** dès qu'une autorité compétente est en place (§2), dans le délai qu'elle fixe.
5. **Prévenir les personnes** lorsque la fuite présente un risque pour elles (mot de passe, pièce d'identité, données de paiement), directement ou par l'organisation.
6. **Corriger et documenter.** Une note datée dans ce dossier : cause, chronologie, données, personnes prévenues, correctif, ce qui empêche la répétition.

## 4. Demandes des personnes

| Demande | Qui | Comment |
|---|---|---|
| Effacement d'un invité | L'invité lui-même | Bouton de suppression sur sa page d'invitation (`POST /rsvp/{token}/erase`), immédiat |
| Effacement d'un client de service ou d'un acheteur | La personne, par l'organisation ou par e-mail à Jikū | L'organisation retire la personne ; à défaut, l'équipe Jikū anonymise à la main sous 30 jours |
| Accès, rectification | Toute personne | Par e-mail à l'adresse de la politique de confidentialité ; réponse sous 30 jours |
| Fermeture d'un compte d'organisation | Le propriétaire du compte | Par e-mail. L'équipe envoie l'export (invités, événements, factures) puis supprime le compte sous 30 jours ; les factures restent 10 ans (obligation comptable) |

## 5. Prestataires à régler à l'installation

- **Chatwoot** : conservation des conversations à 12 mois (suppression automatique des conversations résolues plus anciennes) ; accès réservé à l'équipe.
- **n8n** : historique des exécutions limité à 30 jours (`EXECUTIONS_DATA_MAX_AGE`), sans copie du contenu des messages au-delà.
- **Anthropic** : l'assistant n'envoie que le message du prospect et la base de connaissances commerciale, jamais de données d'invités ou de clients ; vérifier dans la console que les données de l'API ne servent pas à l'entraînement et noter la durée de conservation appliquée.

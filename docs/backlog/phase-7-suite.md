# Jikū — Phase 7, suite : cartes de service et allègement visuel

**Date :** 2026-09-27
**Référence :** ADR 106 (cartes partageables), plan de production §3 et §5.

Ce document contient les stories qui suivent l'invitation ouverte (JIKU-184 à
JIKU-187, en production le 2026-09-27). Une story est une PR par dépôt concerné.
Les blocs **[INTERACTIVE STEP]** sont des décisions à prendre avant de coder la
story : on présente les options, on ne devine pas.

## Vue d'ensemble

| Story | Titre | Dépôt | Dépend de | État |
|---|---|---|---|---|
| JIKU-188 | Allègement visuel : règles de densité et formats courts | app, web | — | Validée le 2026-09-27 |
| JIKU-189 | Carte de service, mode redirection | app, web | — | Prête |
| JIKU-190 | Carte de service, mode rendez-vous direct | app, web | JIKU-189 | Prête |
| JIKU-191 | Conversation guidée : les questions de l'organisation | app, web | JIKU-189 | À valider |
| JIKU-192 | Conversation guidée sur le numéro de l'organisation | app, web | JIKU-191 | À valider |
| JIKU-193 | Mesure des cartes et quota de conversations | app, web | JIKU-192 | À valider |

---

## JIKU-188 — Allègement visuel : règles de densité et formats courts

**Pourquoi.** Les écrans et visuels de Jikū se lisent sur un téléphone, souvent
en quelques secondes : une carte dans un groupe WhatsApp, un billet montré à
l'entrée. Aujourd'hui, ils disent tout en toutes lettres : « samedi 14 novembre
2026 · 19:00 », des étiquettes au-dessus de chaque valeur, une phrase d'aide
sous chaque champ. Le texte fait concurrence à l'information.

**Principe.** Chaque écran a **une** information principale, lisible en
cinq secondes. Le reste est plus petit, reporté plus bas ou affiché à la demande.
L'information complète reste accessible (lecteur d'écran, infobulle, page de
détail), mais elle n'est plus affichée d'office.

### Règles

1. **Dates courtes, relatives quand c'est plus clair.**
   - `sam. 14 nov. · 19 h` (fr), `Sat, Nov 14 · 7 PM` (en).
   - L'année seulement si l'événement est à plus de 11 mois ou dans une année passée.
   - Les minutes seulement si elles ne sont pas nulles.
   - « Aujourd'hui », « Demain », « Dans 3 jours » dans les 7 jours qui viennent.
   - L'heure précise en `<time datetime>` avec la date complète en infobulle et
     pour les lecteurs d'écran.
2. **Une icône plutôt qu'une étiquette.** 📅 14 nov. · 19 h, 📍 Hôtel Kaloum,
   plutôt que « DATE », « HEURE », « LIEU » au-dessus de chaque valeur. On garde
   l'étiquette seulement quand la valeur est ambiguë (un code, un montant).
3. **Aide à la demande.** La phrase d'aide sous un champ disparaît. Ce qu'elle dit passe :
   - dans l'exemple du champ (`+224 620 00 00 00`) ;
   - dans le message d'erreur ;
   - ou derrière une icône ⓘ.

   Les réglages avancés sont repliés.
4. **Budget de texte, vérifié automatiquement.** `pnpm i18n:check` échoue au-delà de ces longueurs :
   - bouton : 3 mots ;
   - titre : 40 caractères ;
   - ligne d'aide : 60 caractères ;
   - texte de carte ou de billet : 24 caractères par ligne.

   Une exception se déclare dans le catalogue, avec sa raison.
5. **Nombres compacts.** `1,2 k réponses`, `3 250 000 GNF` → `3,25 M GNF` dans les
   tableaux de bord ; le montant exact reste sur les factures et les paiements.
6. **Texte saisi par l'utilisateur tronqué.**
   - Le mot d'accueil s'affiche sur 3 lignes au plus, avec « Voir plus ».
   - Les noms longs sont tronqués avec des points de suspension, le nom complet en infobulle.
7. **Hiérarchie par la typographie, pas par des mots.**
   - Trois niveaux au plus : titre, valeur, détail.
   - Le poids et la taille remplacent les étiquettes ; moins de bordures ; espacement sur une grille de 8 px.
8. **Visuels partagés (carte, aperçu de lien, billet).**
   - Au plus 5 lignes de texte : organisateur, titre, date courte, lieu, appel à l'action.
   - L'appel à l'action tient en 2 mots (« Scannez »), et le QR code prend la place gagnée.
   - Le code interne du billet est retiré de l'affichage (le QR le porte).
   - « Heure de Conakry » ne s'affiche que si le fuseau du lecteur est différent.
9. **Mesurer avant et après.** Sur les écrans visés, on compare deux versions, avant et après la story :
   - un test des 5 secondes avec 5 personnes (que retenez-vous ?) ;
   - le taux de réponse de l'invitation ouverte pendant le pilote.

### Écrans concernés, par ordre d'impact

| Écran | Aujourd'hui | Après |
|---|---|---|
| Carte à partager (image) | 7 lignes, date longue, date limite sur 2 lignes | 5 lignes, `sam. 14 nov. · 19 h`, « Avant le 7 nov. » |
| Page de réponse (`/i/…`) | 3 phrases d'aide, date limite sur 2 lignes | Aides dans les exemples, une ligne pour la date limite |
| Billet | Étiquettes, code long, 2 phrases de conseil | Icônes, pas de code affiché, une ligne de conseil |
| Aperçu de lien WhatsApp | Date longue, 2 badges | Date courte, 1 badge |
| Onglet organisateur | Une phrase sous chaque réglage | ⓘ à la demande, réglages avancés repliés |
| Messages WhatsApp | Date longue, mention légale complète | Date courte, mention en une ligne avec lien |

### Critères d'acceptation

- Un seul module de formats (`lib/datetime.ts`, `lib/numbers.ts`) utilisé partout ;
  aucune date formatée ailleurs.
- Les formats courts respectent la langue (fr, en), avec des tests pour les cas
  limites :
  - autre année ;
  - aujourd'hui, demain ;
  - minutes non nulles ;
  - fuseau du lecteur différent de celui de l'événement.
- Le budget de texte est vérifié par `pnpm i18n:check` en CI.
- La carte, le billet, la page de réponse et l'aperçu de lien suivent les règles
  du §8 ; captures avant et après jointes à la PR.
- Accessibilité inchangée ou meilleure : chaque abréviation a son texte complet
  pour les lecteurs d'écran.

### Décisions (2026-09-27)

- **Heure en français :** `19 h` sur les visuels, dans les phrases et dans les
  messages ; `19:00` reste dans les tableaux.
- **Portée :** les 6 écrans ci-dessus d'abord ; les autres écrans suivent les
  mêmes règles à chaque story qui les touche.

---

## JIKU-189 — Carte de service, mode redirection

**Pourquoi.** Une entreprise veut annoncer un service dans ses groupes et
statuts WhatsApp. Aujourd'hui elle ne peut partager qu'un lien texte.

**Ce qui existe.**
- Chaque service a un lien court `/r/{code}` (JIKU-103) et une page publique.
- La page publique de l'organisation liste ses services.

**Ce qu'il faut.** Réutiliser la carte de l'invitation ouverte (même moteur,
ADR 106 décision 2) pour un service :
- **Carte image.** Logo, nom du service, prix indicatif, durée, lieu, QR vers
  `/r/{code}?src=card`, appel à l'action « Réservez ».
- **Aperçu de lien.** Quand on colle `/r/{code}`, WhatsApp affiche le nom du
  service, le prix et l'organisation.
- **Partage.**
  - Bouton « Partager sur WhatsApp » avec un message prêt.
  - Téléchargement de la carte.
  - Copie du lien.
- **Mesure honnête.**
  - Comptage des ouvertures du lien : `src=card` pour le QR, `src=link` pour le lien partagé.
  - Comptage des rendez-vous pris depuis ces ouvertures (attribution sur la même session).
  - WhatsApp ne dit pas combien de personnes ont vu la carte, et on ne l'affiche pas.

**Règles.**
- Organisations **vérifiées** seulement (ADR 106) ; une organisation non
  vérifiée voit la carte en aperçu avec « Faites vérifier votre organisation
  pour la partager ».
- Pas de catalogue, pas de panier : une carte mène à un service.
- Aucun message WhatsApp envoyé : ce mode ne coûte rien et ne demande pas de
  numéro propre.

**Critères d'acceptation.**
- Onglet « Partager » sur un service : carte, lien, partage WhatsApp, ouvertures et rendez-vous des 30 derniers jours.
- Isolation entre organisations sur les compteurs.
- L'ouverture compte une fois par visiteur et par jour (sans cookie de pistage : empreinte journalière hachée, jetée après 24 h).
- Tests, E2E `@smoke` du partage puis de l'ouverture, textes fr et en.

---

## JIKU-190 — Carte de service, mode rendez-vous direct

**Pourquoi.** Pour un service qu'on connaît déjà (coupe, consultation de
suivi), la présentation est un détour : le client veut un créneau.

**Ce qu'il faut.**
- La carte peut viser directement le choix d'un créneau (`/r/{code}?step=slot`),
  en sautant la présentation.
- L'organisation choisit le mode de chaque carte : **Présentation** (JIKU-189) ou **Créneau**.
- Sur la carte image, les 3 prochains créneaux libres s'affichent en petit
  (« Prochains : demain 10 h, 11 h, 14 h »), recalculés à chaque téléchargement.

**Critères d'acceptation.**
- Le rendez-vous pris depuis la carte suit les règles existantes : créneau atomique (ADR 85), paiement avant ou après, rappels.
- Rendez-vous attribué à la carte dans les compteurs de JIKU-189.
- Tests du saut d'étape, y compris quand le service n'a plus de créneau (retour
  à la présentation avec « Aucun créneau cette semaine »).

---

## JIKU-191 — Conversation guidée : les questions de l'organisation

**Pourquoi.** Certains services demandent de qualifier le besoin avant de
proposer un créneau (« quel est votre problème ? »), sans formulaire long.

**Ce qu'il faut.**
- Sur un service, l'organisation définit **au plus 3 questions à choix**, chacune avec 2 à 10 réponses.
  - Jusqu'à 3 réponses, la question s'affiche en boutons dans WhatsApp ; au-delà, en liste.
  - Titres de 20 caractères au plus, limite de WhatsApp.
- Une réponse peut orienter vers un autre service de l'organisation (« Détartrage » → service Détartrage).
- Un aperçu montre la conversation telle qu'elle s'affichera.
- Les réponses sont enregistrées sur le rendez-vous et visibles du personnel dans la console.
- La page web de repli pose les mêmes questions.

**Critères d'acceptation.**
- Contraintes WhatsApp vérifiées à la saisie : nombre de boutons, longueur des titres, 10 lignes de liste.
- Réponses visibles dans le détail du rendez-vous et dans l'export.
- Suppression des réponses avec le rendez-vous (JIKU-36, JIKU-37).

### [INTERACTIVE STEP] À valider

- **Nombre de questions : 3 au plus, ou 5 ?**
  - Recommandation : 3, chaque question en plus fait baisser le taux de réponse.
- **Texte libre en fin de parcours (« Précisez en quelques mots ») : oui ou non ?**
  - Recommandation : non au lancement, pour garder des réponses comptables.

---

## JIKU-192 — Conversation guidée sur le numéro de l'organisation

**Pourquoi.** La carte de conversation ouvre une discussion WhatsApp **avec
l'entreprise**, pas avec Jikū : la relation et la note qualité sont les siennes.

**Ce qu'il faut.**
- La carte ouvre `wa.me/<numéro de l'organisation>` avec un message contenant
  le code du service.
- Le webhook reconnaît le numéro de l'organisation (ADR 105, « votre propre
  numéro ») et confie le message au moteur de cartes. C'est le même moteur que
  l'invitation ouverte (JIKU-185), avec une autre porte d'entrée.
- Parcours :
  1. salutation avec les boutons de la première question ;
  2. questions de JIKU-191 ;
  3. 3 prochains créneaux en boutons, plus « Autre jour » ;
  4. confirmation ;
  5. ticket avec QR dans la discussion.
- STOP et START comme sur les autres numéros ; mention de confidentialité en une ligne au premier message.
- Une personne qui écrit sans code reçoit la liste des services de l'organisation, en liste WhatsApp.

**Critères d'acceptation.**
- Réservé aux organisations vérifiées **avec** leur propre numéro connecté.
  Sinon, la carte propose le mode rendez-vous direct (JIKU-190).
- Toutes les réponses sont dans la fenêtre de 24 h ouverte par le client, donc sans coût chez Meta.
- Un créneau pris pendant la conversation est réservé atomiquement : deux clients sur le même créneau, un seul l'obtient, l'autre reçoit les créneaux suivants.
- Isolation : un message au numéro d'une organisation ne peut jamais toucher les
  services d'une autre.
- Tests de bout en bout sur le webhook, comme `OpenCardConversationTest`.

### [INTERACTIVE STEP] À valider

- **Une organisation vérifiée sans numéro propre peut-elle utiliser le numéro de cartes de Jikū pour la conversation guidée ?**
  - Option A : non, conforme à l'ADR 106 ; la carte propose alors le mode rendez-vous. Le numéro partagé ne prend jamais le risque d'un signalement.
  - Option B : oui, sous un quota réduit.
  - Recommandation : A.

---

## JIKU-193 — Mesure des cartes et quota de conversations

**Pourquoi.** Le feu vert de l'étape 2 (ADR 106 décision 6) exige de savoir si
des entreprises paient le quota ou les relances, et si les signalements restent
bas.

**Ce qu'il faut.**
- **Tableau des cartes** pour l'organisation, par carte et sur 30 jours :
  - ouvertures ;
  - scans ;
  - conversations ouvertes ;
  - rendez-vous pris ;
  - taux de conversion ouverture → rendez-vous.
- **Quota de conversations guidées** inclus dans chaque abonnement Services. Au-delà :
  - soit la conversation guidée se met en pause, et la carte bascule en mode rendez-vous direct, gratuit ;
  - soit l'organisation achète un bloc de conversations.
- **Relances aux prospects** : messages modèles marketing chez Meta, vendus à l'unité avec leur prix exact au moment de l'envoi, seulement vers les personnes ayant écrit à l'organisation.
- **Côté équipe Jikū** : note qualité des numéros, signalements, et coût Meta par conversation.

### [INTERACTIVE STEP] À valider

- **Quotas par formule.**
  - Proposition de départ : Solo 100, Teams 500, Organisation 2 000 conversations par mois.
  - Bloc supplémentaire de 500 à un prix à fixer selon le coût Meta constaté pendant le pilote.
- **Prix d'une relance marketing.**
  - À fixer à partir du tarif Meta de la zone, avec la même marge que les SMS.

---

## Questions ouvertes (à trancher avant la story concernée)

| Sujet | Story | Options | Recommandation |
|---|---|---|---|
| Heure en français | JIKU-188 | `19 h` / `19:00` | **Décidé :** `19 h` sur les visuels, `19:00` dans les tableaux |
| Portée de l'allègement | JIKU-188 | Tout d'un coup / 6 écrans d'abord | **Décidé :** 6 écrans d'abord |
| Nombre de questions guidées | JIKU-191 | 3 / 5 | 3 |
| Texte libre en fin de conversation | JIKU-191 | Oui / Non | Non au lancement |
| Numéro partagé pour la conversation guidée | JIKU-192 | A : non / B : oui avec quota | A |
| Quotas et prix | JIKU-193 | Grille à fixer | Après 4 semaines de pilote |

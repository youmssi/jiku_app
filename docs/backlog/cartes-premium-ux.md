# Exigences UX : cartes premium (JIKU-282 à JIKU-290)

Mode Design de la compétence ux-expert, sur le brief du 3 novembre 2026. Les
identifiants entre crochets renvoient au catalogue de critères
(`.claude/skills/ux-expert/criteria/`, côté web).

## 1. Cadre et contexte

**En une phrase.** La carte partagée est la vitrine de Jikū : un visuel au nom
de l'hôte qui circule dans les groupes et mène, en un geste, à une réponse et à
un billet.

**Type de produit :** application web (page publique de la carte, onglet de
l'organisateur), mobile d'abord. **Langues :** français (par défaut), anglais.

**Contraintes.**
- Guinée d'abord : Facebook ouvert chaque jour, WhatsApp quelques jours par
  semaine (constat terrain, à confirmer par JIKU-282).
- Réseau lent et forfaits limités ; navigateurs intégrés de Facebook,
  Messenger et WhatsApp (Facebook Lite compris).
- Lecture inégale chez les invités : la voix et les icônes portent le sens
  autant que le texte.
- Aucun bouton n'est possible dans un message posté par un particulier : la
  page web de la carte porte toute l'interaction (ADR 106).
- Marque blanche : l'organisation paie pour que sa marque passe avant celle de
  Jikū.

**Segments et tâches, par ordre.**
1. *Hôte d'un événement familial* (mariage, baptême, anniversaire) : faire venir
   les bons proches et savoir combien ils seront.
2. *Invité, souvent sur téléphone d'entrée de gamme* : comprendre en quelques
   secondes qui invite, quand, où, et répondre sans compte.
3. *Organisation payante* : une carte à son image, sans marque Jikū imposée.

**Parcours critiques.** Partager la carte → l'invité l'ouvre → il répond → il
garde son billet. Relancer les silencieux.

**Mesures de réussite.**
- Taux de réponse : réponses / visites de la page, par réseau.
- Temps jusqu'à la réponse.
- Part des réponses venues d'un relais d'invité.

**Hypothèses.**
- Un message vocal de l'hôte augmente la confiance et la réponse chez les
  invités qui lisent peu. Hypothèse, à mesurer.
- Un compteur de présents incite à répondre au-delà d'un seuil. Hypothèse.

## 2. Parcours critiques (proposés)

**L'invité ouvre la carte (cible : réponse en moins de 30 s, 1 décision).**

| Étape | Avant | Proposé |
|---|---|---|
| 1 | Aperçu dans le groupe | Aperçu à jour (titre, date, image) sur chaque réseau |
| 2 | Page : bannière, mot de l'hôte, formulaire | Page légère : bannière, **message vocal** facultatif, date avec compte à rebours, lieu avec itinéraire |
| 3 | Réponse | Réponse en un geste ; « Je viens » ouvre le nombre d'accompagnants |
| 4 | Billet affiché | Billet affiché, **ajout à l'agenda**, **inviter un proche** (si l'hôte l'autorise) |

Optimisation retenue :
- **Retirer** les textes répétés.
- **Réordonner** : la réponse avant les détails secondaires.
- **Différer** l'agenda et le relais après la réponse.

**L'hôte relance les silencieux (cible : 1 geste par invité, 0 coût Meta).**
« Sans réponse » → « Relancer depuis mon WhatsApp » → une discussion par invité
avec le message de relance prêt → la liste avance.

## 3. Récits

### JIKU-282 — Mesurer partages, visites et réponses par réseau

**Utilisateur et tâche :** l'hôte veut savoir où partager sa carte, pour
toucher ses proches là où ils sont. **Domaines :** MEAS, DATA, STATE, A11Y,
CONT, TRUST.

| Où | Ce que l'hôte voit et peut faire |
|---|---|
| Onglet Invitation ouverte | Un bloc « D'où viennent les réponses » : pour chaque réseau, partages, visites et réponses, et le taux de réponse |
| Liens partagés | Chaque lien porte son réseau (`?via=`), invisible pour l'invité |

**Critères UX.**
- [ ] Chaque bouton de partage marque son lien du réseau choisi : WhatsApp,
  Facebook, Telegram, menu de partage, lien copié, QR de l'image
  [MEAS-02, MEAS-04].
- [ ] Une visite et une réponse portent le réseau d'origine.
- [ ] Le tableau ne compte que des nombres : jamais d'identité, de numéro ni
  d'adresse IP. Aucun cookie n'est posé chez l'invité [TRUST, MEAS-04].
- [ ] Réseaux et chiffres sont triés du plus au moins de réponses. Le taux
  s'affiche en pourcentage arrondi, et un tiret (pas « 0 % ») tant qu'il y a
  moins de 5 visites [DATA, CONT-20].
- [ ] Avant tout partage : « Partagez votre carte pour voir d'où viennent les
  réponses », avec le bouton Partager [STATE-02, CONT-08].
- [ ] Une visite sans réseau connu (lien recopié à la main) est comptée sous
  « Autre », jamais perdue [STATE-14].
- [ ] Le bloc est lisible au lecteur d'écran (un tableau avec en-têtes) et
  tient à 320 px sans défilement horizontal [A11Y-02, RESP-15].
- [ ] Une erreur de lecture affiche « Chiffres indisponibles pour le moment »
  sans casser l'onglet [STATE-07, STATE-08].

**Mesure :** `card_shared`, visites et réponses par `via` [MEAS-02].
**Décisions :** aucune.

### JIKU-283 — Aperçus justes partout et image au format statut

**Utilisateur et tâche :** l'hôte veut une carte nette dans un statut WhatsApp
et un aperçu juste sur Facebook, pour paraître soigné. **Domaines :** AES, PERF,
STATE, CONT, A11Y.

**Critères UX.**
- [ ] Le téléchargement propose trois formats nommés pour leur usage :
  - **Statut** : 1080 × 1920 ;
  - **Publication** : 1080 × 1350 ;
  - **Aperçu de lien** : 1200 × 630.

  Chacun garde le titre, la date et le QR hors des zones masquées par
  l'interface (haut et bas de 250 px pour le statut) [AES-02, AES-10].
- [ ] Après une modification de la carte (titre, date, style, bannière), le lien
  partagé change de version, pour que WhatsApp et Facebook redessinent
  l'aperçu [AES-08, STATE-14].
- [ ] L'aperçu de lien a un texte de remplacement : « Invitation : {événement},
  {date} » [A11Y-29, CONT-03].
- [ ] Un titre de 80 caractères, un lieu long ou une bannière absente gardent
  une image propre : texte réduit sur deux lignes, jamais coupé au milieu d'un
  mot [STATE-14, AES-09].
- [ ] Le QR de l'image reste lisible une fois l'image compressée par WhatsApp
  (vérifié par un scan réel) [AES-02].
- [ ] Le choix du format se fait au toucher, avec des cibles de 44 px, et
  l'aperçu du format choisi s'affiche avant le téléchargement
  [RESP-07, INT-04].

**Mesure :** téléchargements par format. **Décisions :** aucune.

### JIKU-284 — Message vocal de l'hôte

**Utilisateur et tâche :** l'hôte veut dire l'invitation de sa voix, pour
toucher les proches qui lisent peu et donner de la chaleur. **Domaines :** FORM,
INT, STATE, A11Y, PERF, TRUST, CONT.

| Où | Ce que l'on voit |
|---|---|
| Onglet de l'hôte | « Ajouter un message vocal » : enregistrer, réécouter, recommencer, supprimer ; ou importer un fichier audio |
| Page de la carte | Un lecteur « Écouter le message de {hôte} » avec la durée ; rien ne se lance tout seul |

**Critères UX.**
- [ ] L'autorisation du micro n'est demandée qu'au toucher de « Enregistrer »,
  avec une phrase qui dit pourquoi [ONB-16, TRUST].
- [ ] Le micro refusé ou absent laisse l'import d'un fichier et explique comment
  réautoriser [STATE-19, CONT-06].
- [ ] Pendant l'enregistrement, on voit un compteur et le temps restant, ainsi
  qu'un bouton Arrêter de 44 px. L'enregistrement s'arrête seul à la durée
  maximale, avec un message [INT-05, A11Y-23].
- [ ] On réécoute avant de publier, et rien ne part sans « Utiliser ce
  message ». Supprimer demande confirmation et peut se refaire
  [INT-19, STATE-17].
- [ ] Le fichier envoyé est compressé à moins de 150 Ko pour 30 s. Il se
  charge seulement quand l'invité touche Lecture, jamais au chargement de la
  page [PERF-17].
- [ ] Le lecteur de la page est un bouton accessible au clavier et au lecteur
  d'écran (« Écouter le message de Fatou, 24 secondes ») [A11Y-02, A11Y-29].
  Une transcription facultative, saisie par l'hôte, s'affiche sous le lecteur.
- [ ] Rien ne se joue automatiquement, et le son ne coupe pas une autre lecture
  en cours [AES-11, A11Y-22].
- [ ] Un envoi qui échoue (réseau) garde l'enregistrement et propose de
  réessayer [STATE-08, STATE-16].

**Mesure :** part des cartes avec un vocal, nombre de lectures, et taux de
réponse avec ou sans vocal. **Décisions :** D1.

### JIKU-285 — Inviter un proche depuis la carte

**Utilisateur et tâche :** l'invité veut faire venir un proche oublié, pour
que l'hôte touche tout le cercle. **Domaines :** FLOW, CONT, INT, TRUST, A11Y.

**Critères UX.**
- [ ] Après une réponse « Je viens », la page propose « Inviter un proche »,
  qui ouvre le menu de partage (ou WhatsApp sur ordinateur) avec le message de
  l'hôte [FLOW, ONB-14].
- [ ] L'hôte l'active ou le coupe dans les réglages de la carte, et la page
  respecte le réglage immédiatement [TRUST].
- [ ] Une carte fermée ou complète n'affiche jamais le bouton [STATE-19].
- [ ] Le lien relayé porte `via=relais`, compté par JIKU-282 [MEAS-02].
- [ ] Le bouton est secondaire : il ne passe jamais devant le billet ni la
  réponse [LAY, AES-04].

**Mesure :** réponses venues d'un relais. **Décisions :** D2.

### JIKU-286 — « Ils viennent » : la preuve sociale

**Utilisateur et tâche :** l'invité hésite. Voir que d'autres viennent l'aide à
se décider. **Domaines :** CONT, TRUST, STATE, A11Y.

**Critères UX.**
- [ ] La page affiche « 23 personnes viennent » au-dessus des boutons de
  réponse, seulement quand l'hôte l'a activé et au-delà du seuil choisi
  [TRUST, CONT-20].
- [ ] Jamais de nom, de photo ni de numéro d'invité [TRUST].
- [ ] Le nombre se met à jour au rechargement. Il n'y a aucune animation de
  compteur qui distrait [AES-05, INT-08].
- [ ] Sous le seuil, rien ne s'affiche : pas de « 1 personne vient »
  [STATE-14].
- [ ] Le nombre est du texte, lu par le lecteur d'écran [A11Y-16].

**Décisions :** D3.

### JIKU-287 — Une carte légère sur réseau lent

**Utilisateur et tâche :** l'invité au forfait limité veut que la carte
s'ouvre vite, pour répondre avant d'abandonner. **Domaines :** PERF, STATE, RESP.

**Critères UX.**
- [ ] Premier chargement de la page de la carte, mesuré en 4G lente émulée sur
  un appareil moyen :
  - moins de 100 Ko transférés hors bannière ;
  - bannière d'au plus 60 Ko en WebP, dimensionnée à l'écran ;
  - réponse possible en moins de 3 s.

  [PERF-17, PERF-19]
- [ ] Un budget de poids est vérifié en intégration continue et fait échouer la
  PR qui le dépasse [PERF-19].
- [ ] Pas de police web lourde sur la page de la carte ; le style choisi passe
  par une police unique, sous-ensemble, ou la police système [PERF-17, TYP].
- [ ] Hors ligne ou en coupure, la réponse saisie est gardée et renvoyée, avec
  un message clair [STATE-11].
- [ ] La page fonctionne dans les navigateurs intégrés de Facebook, Messenger et
  WhatsApp, sur Android et iOS : on répond sans cookie tiers ni nouvelle
  fenêtre [RESP-01].

**Mesure :** LCP et poids transféré, en production (Umami ou Lighthouse CI)
[MEAS-16]. **Décisions :** aucune.

### JIKU-288 — Signature « Créé avec Jikū »

**Utilisateur et tâche :** un invité séduit veut créer sa propre carte ; Jikū
veut cette acquisition gratuite. **Domaines :** AES, TRUST, CONT.

**Critères UX.**
- [ ] Une ligne discrète en pied de page : le symbole Jikū et « Créez votre
  carte gratuitement ». Elle mène à l'accueil avec `via=signature`
  [AES-04, MEAS-02].
- [ ] Elle ne passe jamais devant le contenu de l'hôte, est en contraste
  suffisant (≥ 4,5:1) et forme une cible de 44 px [A11Y-15, RESP-07].
- [ ] Elle respecte la règle de marque blanche décidée en D4 [TRUST].

**Décisions :** D4.

### JIKU-289 — Agenda, itinéraire et compte à rebours

**Utilisateur et tâche :** l'invité qui vient veut ne pas oublier et trouver le
lieu. **Domaines :** INT, CONT, I18N, A11Y.

**Critères UX.**
- [ ] Sous la date : « Dans 12 jours », puis « Demain » et « Aujourd'hui à
  17 h ». La date reste dans le fuseau de l'événement, sans minuterie animée
  [I18N, AES-05].
- [ ] « Ajouter à mon agenda » propose Google Agenda et un fichier .ics (Apple
  Calendar, Outlook), avec un rappel la veille [CONT-03, INT-06].
- [ ] « Itinéraire » ouvre l'application de cartes du téléphone sur le lieu. Le
  bouton est masqué quand le lieu est vide ou que c'est un événement en ligne
  [STATE-14].
- [ ] Après l'événement : « L'événement a eu lieu le … ». Les boutons d'agenda
  et de réponse disparaissent [STATE-14].
- [ ] Tous ces boutons sont des liens au libellé explicite, de 44 px
  [CONT-05, RESP-07].

**Décisions :** aucune.

### JIKU-290 — Relancer depuis mon WhatsApp

**Utilisateur et tâche :** l'hôte veut relancer les silencieux sans frais,
depuis son numéro. **Domaines :** FLOW, CONT, INT, STATE.

**Critères UX.**
- [ ] Dans « Depuis mon WhatsApp », un onglet « À relancer » liste les invités
  qui n'ont pas répondu, avec le message de relance prêt
  (« Petit rappel, {prénom} : … ») [FLOW, CONT-13].
- [ ] Chaque relance ouverte est datée (« Relancé hier »), et l'invité passe en
  bas de la liste [INT-06, INT-07].
- [ ] Un invité qui répond quitte la liste au rechargement [STATE-14].
- [ ] Aucun invité à relancer : « Tout le monde a répondu » [STATE-02, CONT-08].
- [ ] Le lien de relance est le même lien personnel : aucun nouveau lien
  n'est créé, et le quota n'est pas compté deux fois [TRUST].

**Mesure :** réponses après relance [MEAS-02]. **Décisions :** aucune.

## 4. Décisions à prendre (product owner)

[INTERACTIVE STEP] **D1. Le message vocal**
- **A.** Gratuit pour tous, 30 s maximum. Différenciant fort pour l'acquisition ;
  environ 150 Ko par carte stockés sur R2.
- **B.** Réservé aux offres payantes, 60 s maximum. Argument de vente ;
  invisible sur les cartes gratuites, qui sont les plus partagées.

Recommandation : **A**. C'est la carte gratuite qui circule et qui recrute.

[INTERACTIVE STEP] **D2. « Inviter un proche »**
- **A.** Activé par défaut, l'hôte peut le couper. Plus de portée, mais des
  inconnus peuvent venir à une fête privée.
- **B.** Désactivé par défaut. Sûr, mais peu d'hôtes l'activent.

Recommandation : **A** quand la jauge est illimitée ; **B** quand l'hôte a fixé
une jauge.

[INTERACTIVE STEP] **D3. « Ils viennent »**
- **A.** Désactivé par défaut, avec un seuil de 5 quand l'hôte l'active.
- **B.** Activé par défaut au-delà de 5. Plus d'effet, mais une décision de
  confidentialité prise à la place de l'hôte.

Recommandation : **A**. Le chiffre seul, jamais de nom.

[INTERACTIVE STEP] **D4. La signature Jikū**
- **A.** Sur les cartes gratuites seulement ; retirée sur toute offre payante.
  Cohérent avec la marque blanche.
- **B.** Partout, discrète. Plus d'acquisition, mais contraire à la promesse
  marque blanche.

Recommandation : **A**.

**Décisions retenues (3 novembre 2026, product owner).** Les quatre
recommandations sont approuvées :
- **D1 = A** : vocal gratuit, 30 s ;
- **D2** : relais activé quand la jauge est illimitée, désactivé quand l'hôte
  en a fixé une ;
- **D3 = A** : « Ils viennent » désactivé par défaut, seuil de 5 ;
- **D4 = A** : signature sur les cartes gratuites seulement.

Consigne transverse : chaque fonction doit rester simple à mettre en place et
naturelle dans le parcours ; les bons choix sont faits par défaut, sans réglage
à découvrir.

**Récits manquants repérés.**
- Un tableau de bord de performance de la page (Lighthouse CI), couvert par
  JIKU-287.
- La modération d'un message vocal signalé, couverte par le signalement de
  contenu existant, à vérifier.

## 5. Définition de « terminé » UX (chaque PR de ces récits)

- **États :** chargement, vide, erreur avec reprise, hors ligne et contenu
  extrême traités avec le même soin que le cas nominal
  [STATE-01, STATE-07, AES-09].
- **Accessibilité :**
  - clavier complet, focus visible et ordre logique ;
  - libellés reliés, messages d'état annoncés ;
  - contraste ≥ 4,5:1 ;
  - cibles ≥ 44 px sur mobile.

  [A11Y-02, A11Y-05, A11Y-13, A11Y-15, RESP-07]
- **Textes :** un terme par notion, boutons verbe + objet, erreurs « quoi +
  pourquoi + quoi faire ». Français et anglais, dans les catalogues
  [CONT-01, CONT-03, CONT-06].
- **Mouvement :** aucune animation qui retarde la tâche, et
  `prefers-reduced-motion` respecté [INT-09, AES-05].
- **Mesure :** chaque action clé émet un événement, sans donnée personnelle
  [MEAS-02, MEAS-04].
- **Vérification :**
  - `ux_check.mjs` à 320, 768 et 1440 px ;
  - captures du guide refaites ;
  - revue de changement ux-expert : P0 bloquant, P1 corrigé ou suivi.

## 6. Traçabilité

| Critère | Récits |
|---|---|
| MEAS-02, MEAS-04 | 282, 285, 288, 290 |
| PERF-17, PERF-19 | 284, 287 |
| STATE-14 | 282, 283, 286, 289, 290 |
| TRUST | 282, 284, 285, 286, 288, 290 |
| AES-02, AES-10 | 283 |
| A11Y-29 | 283, 284 |
| RESP-07 | 283, 284, 288, 289 |

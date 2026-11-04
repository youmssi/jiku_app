# Jikū — Les invités lisent en arabe

**Date :** 2026-11-03
**Décision :** l'arabe sert les invités d'abord. L'organisateur lit souvent le
français, l'invité découvre la carte sans contexte. Le tableau de bord reste en
français et en anglais.

| Story | Titre | Dépôt | Dépend de |
|---|---|---|---|
| JIKU-292 | Écrans invités en arabe, de droite à gauche, avec shadcn/ui | web | — |
| JIKU-293 | Langue de la carte choisie par événement, messages et images en arabe | app, web | JIKU-292 |

## JIKU-292 — Écrans invités en arabe, de droite à gauche

**Pourquoi.** Dans plusieurs marchés, une bonne partie des invités lit l'arabe
et peu le français. Le message vocal (JIKU-284), les icônes et le QR code
passent déjà sans lecture ; le reste de la carte doit se lire.

**Comportement.**

- Les écrans invités existent en français, anglais et arabe : la carte
  (`/i`), l'invitation et le billet (`/invitation`, `/v`), l'achat et la
  commande (`/c`, `/orders`), la page publique (`/o`), la prise de
  rendez-vous et la file (`/r`, `/appointments`, `/widget`).
- Un téléphone réglé en arabe ouvre la version arabe ; en bas de chaque page,
  `Français`, `English` et `العربية` changent de langue en gardant la page.
- L'arabe se lit de droite à gauche : `dir="rtl"` sur la page, le
  `DirectionProvider` de shadcn/ui pour les composants Radix, et les
  composants shadcn/ui migrés en propriétés logiques (`shadcn migrate rtl`).
- Les autres pages ne prennent pas l'arabe : `/ar` y renvoie à la version
  française.
- Ce que l'organisateur écrit garde son sens d'écriture (`dir="auto"`).

**Critères d'acceptation.**

- [x] Catalogue arabe pour `common` et `guest` ; les autres espaces de noms et
  toute clé manquante retombent sur le français ; `pnpm i18n:check` le vérifie.
- [x] Pluriels arabes complets (zéro, un, deux, quelques, beaucoup, autre) sur
  les nombres que l'invité lit [I18N].
- [x] Aucune police téléchargée en plus : la face arabe du téléphone (Noto Sans
  Arabic, SF Arabic, Segoe UI) ; la carte arabe pèse comme la française
  (591 Ko contre 594 Ko) [PERF-17].
- [x] Pas d'espacement de lettres sur l'arabe, qui casserait les liaisons [TYP].
- [x] Flèches et chevrons inversés en arabe ; positions et marges logiques
  dans les écrans invités [I18N].
- [x] Carte arabe à 320, 768 et 1440 px : 0 violation axe, pas de défilement
  horizontal, cibles de 44 px sur mobile [A11Y, RESP-07].
- [x] La page publique d'un organisateur, jusque-là en anglais en dur, passe
  par les catalogues dans les trois langues.
- [x] Guide « Carte d'invitation » complété, avec une capture de la carte en arabe.

**À relire par une personne arabophone** avant la mise en avant commerciale :
le catalogue a été rédigé en arabe standard moderne.

## JIKU-293 — Langue de la carte choisie par événement

**Pourquoi.** Le téléphone de l'invité ne dit pas tout : l'organisateur sait
dans quelle langue il invite. Décision : les invités lisent l'arabe écrit.

**Comportement.**

- Dans **Paramètres**, **Langue des invités** : `Français`, `English`, `العربية`,
  enregistré d'un toucher. Sans choix, la langue de l'organisation.
- Tout ce que l'invité reçoit suit ce choix : liens (`/ar/...`), e-mails
  d'invitation, de billet et d'annulation, messages et modèles WhatsApp, robot
  de la carte, message de partage, image de la carte, envoi et relance depuis
  le WhatsApp de l'organisateur. L'invité garde le choix en bas de page.

**Critères d'acceptation.**

- [x] `GET/PUT /events/{id}/guest-language`, isolé par organisation ; toute autre
  valeur que fr, en, ar est refusée.
- [x] E-mails arabes de droite à gauche (`dir="rtl"`), sans espacement de
  lettres ; chiffres occidentaux dans les dates, comme dans les numéros et codes.
- [x] Modèles WhatsApp dédiés en arabe pour l'invitation, la réponse par
  boutons, le billet et l'annulation, publiés avec les autres pour revue Meta.
- [x] Mots-clés arabes reconnus par le robot (توقف، ايقاف، ابدأ، تذكرتي),
  quelles que soient les voyelles et la hamza.
- [x] Une clé que les invités ne lisent jamais retombe sur le français.
- [x] Images de la carte en arabe : lettres liées avant le rendu, mots de
  droite à gauche, Noto Sans Arabic chargée seulement pour une carte qui
  contient de l'arabe ; les cartes françaises sont inchangées.
- [x] Guide « Carte d'invitation » complété, avec la capture du réglage.

**Exploitation.** Après déploiement, republier les modèles WhatsApp pour que
Meta revoie les versions arabes ; tant qu'elles ne sont pas approuvées, un
envoi WhatsApp en arabe échoue et le canal de secours prend le relais.

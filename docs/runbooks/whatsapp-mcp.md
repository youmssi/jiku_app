# Runbook — tester l'intégration WhatsApp avec le serveur MCP de Meta (JIKU-238)

**Quoi :** Meta publie un serveur MCP, « WhatsApp Business Tools ». Un agent ou
un assistant d'éditeur compatible MCP peut, avec lui, administrer et déboguer
notre compte WhatsApp Business sans passer par le Gestionnaire WhatsApp :
entreprises, comptes, numéros, modèles, webhooks et envoi de messages.

**Pourquoi :** il permet de préparer et vérifier en quelques minutes ce que
l'API attend de Meta : identifiants des variables `WHATSAPP_META_*`, modèles
dédiés approuvés, webhook abonné aux bons champs. On peut ensuite déclencher
un envoi réel et suivre ce qu'en fait notre webhook.

**Statut :** version bêta de Meta, déployée progressivement ; les outils
peuvent changer. Ce runbook décrit l'état au 3 septembre 2026.

## Configuration

Le dépôt déclare le serveur dans `.mcp.json` à la racine (même fichier dans le
dépôt web) :

| Attribut | Valeur |
|---|---|
| Nom | `whatsapp_business_tools` |
| Transport | HTTP diffusé (streamable HTTP) |
| Point de terminaison | `https://mcp.facebook.com/whatsapp_business_tools` |
| Authentification | OAuth, compte développeur Meta |

Un client qui lit `.mcp.json` propose le serveur à l'ouverture du dépôt. Pour
un client qui ne gère que les serveurs locaux (stdio), passer par le pont
`npx mcp-remote https://mcp.facebook.com/whatsapp_business_tools`. Dans un
client sans fichier de projet, ajouter un connecteur personnalisé avec le nom
et l'URL ci-dessus.

**Aucun secret dans le dépôt :** l'authentification se fait par OAuth dans le
navigateur, à chaque redémarrage du client. Ne jamais coller de jeton d'accès
dans `.mcp.json` ni dans un fichier suivi par git.

## Prérequis côté Meta

Le serveur agit sur nos propres éléments ; sans ces trois points, les outils
renvoient une erreur qui dit quoi corriger et où :

1. **Accès admin** (rôle MANAGE) au portefeuille business de Jikū.
2. **Accès admin à l'application Meta** (pas seulement au business), avec le
   cas d'utilisation « Messagerie WhatsApp Business ». C'est l'application de
   `WHATSAPP_META_APP_ID`.
3. **Conditions de l'API Cloud acceptées** pour ce business : l'envoi et
   l'enregistrement de numéros restent bloqués avant.

## Connexion

1. Ouvrir la liste des serveurs MCP du client et choisir
   `whatsapp_business_tools` pour lancer la connexion.
2. Se connecter avec le compte Meta, puis cocher sur l'écran de consentement
   **uniquement** le business Jikū et son application WhatsApp.
3. Portées demandées : `business_management`, `whatsapp_business_management`,
   `whatsapp_business_messaging`.
4. Vérifier : demander la liste des outils `whatsapp_biz_*`, puis lancer
   `whatsapp_biz_businesses` (lecture seule).

Révoquer l'accès : facebook.com → Paramètres → Intégrations professionnelles.

## De Meta à nos variables

| Ce qu'on cherche | Outil | Où ça va chez nous |
|---|---|---|
| Identifiant du business | `whatsapp_biz_businesses` | point de départ des autres outils |
| Compte WhatsApp Business (WABA) | `whatsapp_biz_accounts` | `WHATSAPP_META_BUSINESS_ACCOUNT_ID` |
| Numéro d'envoi et numéro des cartes | `whatsapp_biz_phone_numbers` (id, statut, qualité) | `WHATSAPP_META_PHONE_NUMBER_ID`, `WHATSAPP_META_CARDS_PHONE_NUMBER_ID` |
| Jeton permanent | `whatsapp_biz_system_user_token` (renvoie un lien ; le jeton se génère dans les paramètres du business) | `WHATSAPP_META_ACCESS_TOKEN`, dans le gestionnaire de secrets de l'hébergeur uniquement |
| Moyen de paiement | `whatsapp_biz_configure_payments` | requis pour les messages facturés |
| Vérification du business | `whatsapp_biz_verify_business` | lève les plafonds de Meta |

## Scénarios de test

Toujours sur l'environnement de **recette**, avec un numéro de test et des
destinataires de l'équipe. Jamais vers un vrai invité. L'outil d'envoi demande
de confirmer la cible avant d'envoyer : relire le numéro à chaque fois.

### 1. Webhook

1. `whatsapp_biz_configure_webhooks` : URL de rappel
   `<API_PUBLIC_URL>/api/v1/whatsapp/webhook`, jeton de vérification égal à
   `WHATSAPP_META_VERIFY_TOKEN`. Champs à abonner :
   - `messages` (messages reçus et statuts de remise) ;
   - `message_template_status_update`, `message_template_quality_update`,
     `template_category_update` ;
   - `phone_number_quality_update`, `account_update`.
2. `whatsapp_biz_subscribe_webhook` sur le WABA, pour que ses évènements
   arrivent à l'application.
3. **Attendu :** l'enregistrement réussit, ce qui prouve que le `GET` de
   vérification répond avec le challenge. Un échec signifie un jeton différent
   ou une API injoignable.

### 2. Modèles dédiés

1. Créer les modèles depuis l'API : `POST /api/v1/admin/whatsapp/templates`
   (compte admin plateforme).
2. `whatsapp_biz_list_templates` : on doit voir, en `fr` et `en`, les modèles
   préfixés par `WHATSAPP_META_TEMPLATE_PREFIX` (par défaut `jiku_`) :
   `invitation_v1`, `invitation_rsvp_v1`, `ticket_v1`, `event_cancelled_v1`,
   `appointment_reminder_v1`, `client_called_v1`.
3. **Attendu :** dès que Meta change un statut (`APPROVED`, `REJECTED`,
   `PAUSED`), `GET /api/v1/admin/whatsapp/templates` affiche le même statut,
   reçu par le webhook.
4. Ne pas créer ni modifier un modèle `jiku_*` à la main avec
   `whatsapp_biz_create_template` ou `whatsapp_biz_update_template` : un
   changement de texte se fait dans le code avec une nouvelle version
   (`_v2`), sinon notre envoi et le modèle de Meta ne correspondent plus.

### 3. Envoi et réponse

1. `whatsapp_biz_send_message` : envoyer `jiku_invitation_rsvp_v1` en `fr` à
   un numéro de l'équipe depuis le numéro de recette, avec les paramètres
   attendus par le modèle.
2. Sur le téléphone, toucher un bouton de réponse.
3. **Attendu :**
   - les statuts `sent`, `delivered` puis `read` arrivent sur le webhook ;
   - le clic arrive comme message entrant et répond à l'invitation dans
     l'application ;
   - les journaux ne montrent aucune signature refusée.
4. Un texte libre n'est remis que dans la fenêtre de 24 h ouverte par un
   message du destinataire : pour tester `WHATSAPP_TRANSPORT=meta` sans
   modèle, écrire d'abord au numéro de recette.

### 4. Numéro propre d'une organisation (ADR 105)

Le parcours réel est l'Embedded Signup dans l'application web. Les outils
servent à **constater** son résultat : `whatsapp_biz_phone_numbers` sur le
WABA de l'organisation (statut `CONNECTED`, qualité) et
`whatsapp_biz_list_templates` (modèles créés par l'inscription). Les outils
`whatsapp_biz_add_phone_number`, `whatsapp_biz_send_verification_code`,
`whatsapp_biz_verify_phone_number` et `whatsapp_biz_register_phone_number`
servent seulement à préparer un numéro de test de la plateforme.

## Limites

- Les appels sont limités par utilisateur et par outil ; en cas d'erreur de
  débit, attendre avant de relancer.
- Le serveur ne remplace pas les tests automatisés : il sert à vérifier
  l'intégration contre le vrai Meta, pas à la valider en CI.

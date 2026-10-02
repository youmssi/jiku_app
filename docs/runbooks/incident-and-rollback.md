# Runbook — incident et retour arrière (serveur unique, ADR 107)

**Règle :** on rétablit le service d'abord, on corrige ensuite, par le flux
normal (story, PR, fusion).

## 1. Qualifier en deux minutes

| Symptôme | Où regarder | Section |
|---|---|---|
| Une fusion vient de partir et l'API ne répond plus ou est en erreur | Workflow « Container image », journaux Dokploy | 2 |
| L'API répond mais la base est injoignable (`readiness` en 503) | Journaux `postgres`, `api` | 3 |
| Données fausses ou supprimées | Heure du problème, requêtes | 4 |
| Rien ne répond, serveur inaccessible | Console Hetzner | 5 |
| E-mails refusés en masse | Tableau de bord useSend, alerte réputation | 6 |

Noter l'heure de début : elle sert au point de restauration.

## 2. Revenir à la version précédente de l'API ou du web

1. GitHub → Packages → noter le tag `sha` de l'image précédente.
2. Dokploy → application → Environment : `API_IMAGE_TAG=<sha précédent>` (ou
   `WEB_IMAGE_TAG`), puis **Deploy**.
3. Vérifier `https://api.<domaine>/actuator/health/readiness` (200).
4. Une migration Flyway déjà appliquée n'est pas défaite par ce retour : les
   migrations sont additives, l'ancienne version fonctionne avec le nouveau
   schéma. Si ce n'est pas le cas, passer à la section 4.

## 3. Base injoignable

1. `docker compose -f docker-compose.vps.yml ps` : `postgres` est-il `healthy` ?
2. Disque plein ? `df -h /` et `docker system df`. Libérer :
   `docker image prune -a` (images inutilisées), journaux anciens.
3. Le journal ne part plus vers R2 (`archive_command` en échec) : les segments
   s'accumulent sur le disque. Vérifier les identifiants R2 dans Dokploy, puis
   `docker compose ... logs postgres | grep -i archive`.
4. Redémarrer `postgres` seul, puis `api`.

## 4. Données fausses ou supprimées

Suivre `docs/runbooks/database-restore.md`, section « Base sur le serveur »,
avec un `recovery_target_time` juste avant l'heure notée.

## 5. Serveur perdu

Objectif : service rétabli en moins d'une heure.

1. Hetzner → restaurer la dernière sauvegarde du serveur (sauvegardes Hetzner)
   **ou** créer un nouveau CX33.
2. Sur un serveur neuf : `docs/deploy-vps.md`, étapes 1 à 4, puis restaurer la
   base depuis R2 (database-restore.md) **au lieu** de l'import depuis Neon.
3. Mettre à jour l'enregistrement DNS si l'adresse a changé (Cloudflare, effet
   en quelques secondes avec le proxy).

## 6. E-mails refusés

1. useSend → Reputation : taux de rebonds et de plaintes.
2. Si SES a mis le compte en examen : basculer temporairement
   `MAIL_TRANSPORT=brevo` dans Dokploy (Brevo reste configuré en secours),
   redéployer.
3. Identifier l'organisation à l'origine (back-office → tableau de bord de
   délivrabilité) et la contacter avant de rebasculer.

## Après l'incident

Écrire en cinq lignes : ce qui s'est passé, l'heure de début et de fin, la
cause, ce qui a rétabli le service, ce qui empêchera la récidive (story créée).

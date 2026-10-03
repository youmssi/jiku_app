# Runbook — test de charge du jour J (JIKU-205)

**Quoi :** `scripts/load/checkin-rush.js` (k6) rejoue l'entrée d'un événement :

- **500 billets** scannés en **5 minutes** par **10 portes** ;
- en parallèle, environ 100 ouvertures par minute de la page publique de la
  carte (en dessous de la limite de 120 par minute et par carte).

**Quand :** avant le pilote, puis après tout changement du check-in, de la base
ou de la taille du serveur. **Jamais sur la production** : le script crée un
événement et 500 invités.

## Seuils

| Mesure | Seuil |
|---|---|
| Scan, 95e centile | < 500 ms |
| Scans refusés ou en erreur | < 1 % |
| Page de la carte, 95e centile | < 800 ms |

Un seuil franchi fait échouer k6 (code de sortie non nul).

## Préparer l'environnement de recette

Le script crée les billets par l'invitation ouverte, une réponse « Je viens »
par invité. C'est le chemin d'une vraie carte, sans e-mail ni WhatsApp. Sur la
recette uniquement :

| Variable | Valeur | Pourquoi |
|---|---|---|
| `BILLING_FREE_TIER_GUESTS` | `100000` | 500 invités dépassent l'offre gratuite |
| `RATE_LIMIT_OPEN_INVITATION_RESPONSE_MAX_REQUESTS` | `100000` | les 500 réponses viennent d'une seule adresse |

Créer ensuite un compte organisateur avec une organisation (page d'inscription,
ou `POST /api/v1/auth/register` avec `name`).

## Lancer

```
k6 run -e API_URL=https://api.recette.<domaine>/api/v1 \
       -e ORGANIZER_EMAIL=<e-mail> -e ORGANIZER_PASSWORD=<mot de passe> \
       scripts/load/checkin-rush.js
```

Paramètres facultatifs : `GUESTS` (500), `VALIDATORS` (10), `RUSH_MINUTES` (5).
La préparation (500 réponses) prend environ une minute avant le chronomètre.

## Résultats

| Date | Environnement | Billets / portes / durée | Scan p95 | Erreurs | Carte p95 |
|---|---|---|---|---|---|
| 2026-10-02 | Poste de développement : API et PostgreSQL locaux, une instance | 500 / 10 / 5 min | 60 ms | 0 % (500/500 acceptés) | 18 ms |

Le chiffre de référence sera celui du serveur de production (CX33) mesuré sur la
recette. À ajouter ici après la bascule (`docs/deploy-vps.md`).

## Si un seuil est franchi

1. Regarder la latence de la base (Grafana Cloud, ou `pg_stat_statements`) :
   index manquant sur le code du billet, verrou sur le compteur de présence.
2. Mémoire et CPU des conteneurs `api` et `postgres` pendant le test.
3. Corriger dans une story, puis relancer le test jusqu'à repasser sous les seuils.

---

# Envoi massif pendant l'activité (JIKU-216)

**Quoi :** `scripts/load/send-rush.js` (k6) envoie **1 000 invitations** par
e-mail pendant que **20 organisateurs** gardent leurs écrans ouverts et que
**50 visiteurs** ouvrent la carte publique. Il vérifie que le lot ne ralentit
pas l'API et que toutes les invitations partent dans la fenêtre.

## Seuils

| Mesure | Seuil |
|---|---|
| Lancement du lot (`POST …/invitations/send`) | < 2 s |
| Écrans organisateur (invités, statuts, événement), 95e centile | < 800 ms |
| Page de la carte, 95e centile | < 500 ms |
| Invitations envoyées en `SEND_MINUTES` (5) | 1 000 |

## Préparer

Sur la recette, en plus des variables du test précédent :

| Variable | Valeur | Pourquoi |
|---|---|---|
| `MAIL_TRANSPORT` | `log` | aucun e-mail réel ne part |
| `MAIL_LOG_LATENCY` | `300ms` | chaque envoi dure comme chez un fournisseur |

## Lancer

```
k6 run -e API_URL=https://api.recette.<domaine>/api/v1 \
       -e ORGANIZER_EMAIL=<e-mail> -e ORGANIZER_PASSWORD=<mot de passe> \
       scripts/load/send-rush.js
```

Paramètres facultatifs : `GUESTS` (1000), `ORGANIZERS` (20), `SEND_MINUTES` (5).

Débit attendu : `ASYNC_BULK_THREADS` envois en parallèle, soit 8 × (1 s / 300 ms)
≈ 26 e-mails par seconde ; 1 000 invitations en un peu plus d'une minute.

## Résultats

| Date | Environnement | Invitations / latence fournisseur | Lot envoyé en | Écrans p95 | Carte p95 |
|---|---|---|---|---|---|


# Runbook — restaurer la base

**Quand :** une migration a corrompu des données, une suppression accidentelle a
eu lieu, ou la base ne répond plus.

**Qui :** n'importe quel exploitant. Ce runbook n'exige aucune connaissance
préalable de l'incident ni de l'auteur du texte.

**RTO mesuré :** voir `docs/backup.md`. La procédure a été répétée, pas seulement
écrite.

---

## Avant de toucher à quoi que ce soit

**Ne restaurez pas par-dessus la base de production.** Restaurez à côté, vérifiez,
puis basculez. Une restauration directe transforme un incident récupérable en
perte définitive si le dump s'avère mauvais.

Notez l'heure à laquelle le problème a commencé — c'est ce qui détermine le point
de restauration, et c'est l'information qu'on ne retrouve plus une heure après.

---

## Cas 1 — Mauvaise migration ou suppression accidentelle (le cas courant)

Neon fait de la restauration à un instant donné (PITR). C'est le chemin le plus
rapide et il n'exige aucun dump.

1. Console Neon → projet Jikū → **Branches**.
2. **Create branch** → *Include data up to* → choisir un instant **avant**
   l'heure notée plus haut. Neon crée une branche complète en quelques secondes ;
   la production n'est pas touchée.

   En ligne de commande, ce qui a été réellement exécuté lors de la répétition
   du 3 septembre 2026 (branche prête en 18 s) :
   ```
   neonctl branches create --project-id <projet> --name restore-<date> --parent <instant-iso>
   neonctl connection-string restore-<date> --project-id <projet>
   ```
   `--parent` accepte un horodatage ISO 8601, par exemple `2026-09-03T10:33:35Z`.
3. Récupérer la chaîne de connexion de la nouvelle branche.
4. **Vérifier avant de basculer** — les données attendues sont-elles là ?
   ```
   psql "<connexion-branche>" -c "SELECT count(*) FROM guest;"
   psql "<connexion-branche>" -c "SELECT MAX(version::int) FROM flyway_schema_history WHERE success;"
   ```
5. Basculer : sur Render, remplacer `DATABASE_URL` par la connexion de la branche,
   puis redémarrer le service. Sur Neon, on peut aussi promouvoir la branche en
   principale.
6. Vérifier que l'application répond :
   ```
   curl -s https://jiku-app.onrender.com/api/v1/health
   ```

**Ce que ce cas ne couvre pas :** si le projet Neon lui-même est inaccessible
(compte suspendu, incident fournisseur), passez au cas 2.

---

## Cas 2 — Restaurer depuis un dump

Utilisé quand Neon est hors d'atteinte, ou pour repartir d'une sauvegarde
indépendante produite par `scripts/backup-db.sh`.

1. Récupérer le dump. Depuis Neon : console → **Backups** → télécharger. Depuis la
   sauvegarde indépendante : le fichier le plus récent de `BACKUP_DIR`.

2. **Répéter la restauration dans une base jetable avant de la faire pour de
   vrai** — cette étape est ce qui distingue une restauration d'un pari :
   ```
   ./scripts/restore-drill.sh --dump /chemin/vers/jiku-2026-09-03.sql.gz
   ```
   Le script crée une base temporaire, restaure dedans, vérifie l'historique
   Flyway, compte les lignes métier, contrôle qu'aucun événement n'a perdu son
   `tenant_id`, affiche le temps que ça a pris, puis supprime la base. Il n'écrit
   dans aucune base existante.

   Un `ÉCHEC` ici signifie que le dump est inutilisable : prenez le précédent.

3. Restaurer pour de vrai, dans une base **nouvelle** :
   ```
   createdb -h <hôte> -U <utilisateur> jiku_restored
   gunzip -c /chemin/vers/dump.sql.gz | psql -v ON_ERROR_STOP=1 -h <hôte> -U <utilisateur> -d jiku_restored
   ```

4. Basculer l'application sur `jiku_restored` (`DATABASE_URL` sur Render), puis
   redémarrer et vérifier `/api/v1/health`.

5. **Ne supprimez pas l'ancienne base** avant au moins 48 h de fonctionnement
   normal. Ce qui manque à une restauration se découvre rarement le premier jour.

---

## Après la bascule, dans tous les cas

- [ ] `/api/v1/health` répond `UP` avec la version attendue
- [ ] Une connexion organisateur fonctionne et la liste d'événements s'affiche
- [ ] Un scan de billet aboutit (c'est le chemin qui compte un soir d'événement)
- [ ] Les e-mails partent — vérifier qu'un envoi de test arrive
- [ ] Consigner dans `docs/backup.md` : date, cause, point de restauration retenu,
      durée réelle constatée

## Ce que la restauration ne rend pas

Les données écrites **après** le point de restauration sont perdues :
confirmations RSVP, entrées enregistrées, invitations envoyées dans l'intervalle.
Sur un événement en cours, prévenez le portier avant de basculer — des invités
déjà admis apparaîtront de nouveau comme non venus, et un second scan leur sera
demandé.

---

## Répéter la procédure sans incident

À faire au moins une fois par trimestre, et après tout changement de schéma
important. La répétition tourne entièrement en local, sans identifiants de
production :

```
docker compose up -d
./scripts/restore-drill.sh                 # sur la base de développement
./scripts/restore-drill.sh --scale 60000   # au volume d'une année d'exploitation
```

Reporter le RTO obtenu dans `docs/backup.md` avec sa date, le volume et la
version de schéma. Un RTO qui dérive d'un trimestre à l'autre est le signal
utile ; un RTO jamais mesuré n'en est pas un.

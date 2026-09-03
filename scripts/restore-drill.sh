#!/usr/bin/env bash
# Répétition de restauration (JIKU-80).
#
# Restaure un dump dans une base JETABLE, vérifie que la base restaurée est
# réellement exploitable, et mesure le temps que ça a pris. Rien n'est écrit
# dans une base existante : la cible est créée puis supprimée.
#
# La question à laquelle ce script répond n'est pas « la sauvegarde existe-t-elle »
# mais « en combien de temps sert-elle ». Une sauvegarde jamais restaurée est une
# hypothèse, pas une garantie.
#
#   ./scripts/restore-drill.sh                        # répétition locale (Docker)
#   ./scripts/restore-drill.sh --dump /chemin.sql.gz  # depuis un dump précis
#   ./scripts/restore-drill.sh --scale 60000          # à un volume réaliste
#
# Contre une sauvegarde Neon : télécharger d'abord le dump depuis la console
# Neon, puis passer --dump. Voir docs/runbooks/database-restore.md.
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="$APP_DIR/docker-compose.yml"
PG_SERVICE="postgres"
PG_USER="${DATABASE_USERNAME:-jiku}"
SOURCE_DB="${DATABASE_NAME:-jiku}"
TARGET_DB="jiku_restore_drill_$$"
DUMP=""
KEEP=0
SCALE=0

while [ $# -gt 0 ]; do
    case "$1" in
        --dump) DUMP="$2"; shift 2 ;;
        --keep) KEEP=1; shift ;;
        --scale) SCALE="$2"; shift 2 ;;
        *) echo "Option inconnue : $1" >&2; exit 2 ;;
    esac
done

psql_in() {
    docker compose -f "$COMPOSE" exec -T "$PG_SERVICE" psql -v ON_ERROR_STOP=1 -U "$PG_USER" "$@"
}

SCALE_DB="jiku_drill_scale_$$"

cleanup() {
    psql_in -d postgres -q -c "DROP DATABASE IF EXISTS $SCALE_DB WITH (FORCE);" >/dev/null 2>&1 || true
    if [ "$KEEP" -eq 1 ]; then
        echo "==> Base de répétition conservée : $TARGET_DB (--keep)"
        return
    fi
    psql_in -d postgres -q -c "DROP DATABASE IF EXISTS $TARGET_DB WITH (FORCE);" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> Vérification de PostgreSQL"
if ! docker compose -f "$COMPOSE" exec -T "$PG_SERVICE" pg_isready -U "$PG_USER" >/dev/null 2>&1; then
    echo "PostgreSQL ne répond pas. Lancez : docker compose up -d" >&2
    exit 1
fi

# Sans dump fourni, on en prend un de la base locale. C'est ce qui rend la
# répétition exécutable sans identifiants de production, donc réellement répétable.
if [ -z "$DUMP" ]; then
    DUMP="$(mktemp -t jiku-drill-XXXXXX.sql.gz)"
    trap 'cleanup; rm -f "$DUMP"' EXIT

    CAPTURE_DB="$SOURCE_DB"
    if [ "$SCALE" -gt 0 ]; then
        # Copie par dump/restore plutôt que par CREATE DATABASE ... TEMPLATE :
        # `TEMPLATE` exige zéro connexion à la source, ce qui échoue dès que
        # l'application tourne — c'est-à-dire dans le cas normal.
        echo "==> Copie de '$SOURCE_DB' vers '$SCALE_DB'"
        psql_in -d postgres -q -c "DROP DATABASE IF EXISTS $SCALE_DB WITH (FORCE);"
        psql_in -d postgres -q -c "CREATE DATABASE $SCALE_DB;"
        docker compose -f "$COMPOSE" exec -T "$PG_SERVICE" \
            pg_dump -U "$PG_USER" -d "$SOURCE_DB" --no-owner --no-privileges \
            | psql_in -d "$SCALE_DB" -q >/dev/null

        echo "==> Construction d'un jeu de $SCALE invités"
        psql_in -d "$SCALE_DB" -q -v rows="$SCALE" -f - < "$APP_DIR/scripts/seed-volume.sql"
        CAPTURE_DB="$SCALE_DB"
    fi

    echo "==> Capture de '$CAPTURE_DB'"
    docker compose -f "$COMPOSE" exec -T "$PG_SERVICE" \
        pg_dump -U "$PG_USER" -d "$CAPTURE_DB" --no-owner --no-privileges | gzip > "$DUMP"
fi

if [ ! -s "$DUMP" ]; then
    echo "Dump introuvable ou vide : $DUMP" >&2
    exit 1
fi
echo "==> Dump : $DUMP ($(du -h "$DUMP" | cut -f1))"

# ---- Le chronomètre démarre ici. Ce qui précède est de la préparation ; ce qui
# ---- suit est ce qu'un exploitant fait réellement pendant un incident.
START=$(date +%s)

echo "==> Création de la base de restauration : $TARGET_DB"
psql_in -d postgres -q -c "CREATE DATABASE $TARGET_DB;"

echo "==> Restauration"
gunzip -c "$DUMP" | docker compose -f "$COMPOSE" exec -T "$PG_SERVICE" \
    psql -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$TARGET_DB" -q >/dev/null

END=$(date +%s)
RTO=$((END - START))

# ---- Vérification. Une restauration qui rend la main sans erreur n'est pas une
# ---- restauration réussie : il faut que la base restaurée porte les données.
echo "==> Vérification de la base restaurée"

SCHEMA_VERSION=$(psql_in -d "$TARGET_DB" -tAc \
    "SELECT MAX(version::int) FROM flyway_schema_history WHERE success;" 2>/dev/null || echo "")
if [ -z "$SCHEMA_VERSION" ]; then
    echo "ÉCHEC : historique Flyway absent — la base restaurée n'est pas exploitable." >&2
    exit 1
fi

FAILED_MIGRATIONS=$(psql_in -d "$TARGET_DB" -tAc \
    "SELECT count(*) FROM flyway_schema_history WHERE NOT success;")
if [ "$FAILED_MIGRATIONS" != "0" ]; then
    echo "ÉCHEC : $FAILED_MIGRATIONS migration(s) en échec dans la base restaurée." >&2
    exit 1
fi

# Les tables porteuses du métier. Vides, la restauration est techniquement
# réussie et opérationnellement inutile — c'est le cas qu'il faut distinguer.
echo
printf '%-24s %s\n' "TABLE" "LIGNES"
TOTAL=0
for table in tenant event guest ticket invitation; do
    COUNT=$(psql_in -d "$TARGET_DB" -tAc "SELECT count(*) FROM $table;" 2>/dev/null || echo "absente")
    printf '%-24s %s\n' "$table" "$COUNT"
    case "$COUNT" in ''|*[!0-9]*) ;; *) TOTAL=$((TOTAL + COUNT)) ;; esac
done

# Le multi-tenant est la promesse qui ne doit pas survivre à une restauration
# ratée : un tenant_id nul rendrait des lignes visibles par tous les tenants.
ORPHANS=$(psql_in -d "$TARGET_DB" -tAc \
    "SELECT count(*) FROM event WHERE tenant_id IS NULL;" 2>/dev/null || echo 0)
if [ "$ORPHANS" != "0" ]; then
    echo "ÉCHEC : $ORPHANS événement(s) sans tenant_id — isolation compromise." >&2
    exit 1
fi

echo
echo "======================================================================"
echo " RESTAURATION VÉRIFIÉE"
echo "   Schéma          : V$SCHEMA_VERSION, aucune migration en échec"
echo "   Lignes métier   : $TOTAL"
echo "   Isolation       : aucun événement orphelin"
echo "   RTO mesuré      : ${RTO}s  (création + restauration)"
echo "   Date            : $(date -u '+%Y-%m-%d %H:%M UTC')"
echo "======================================================================"
echo
echo "Reportez ce chiffre dans docs/backup.md avec sa date et la taille du jeu."

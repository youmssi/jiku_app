#!/usr/bin/env bash
# Optional defense-in-depth backup (JIKU-60). Neon (the production Postgres
# host) already provides managed backups and point-in-time recovery on its own —
# this script is a second, independent copy in case that account or project is
# ever unreachable. It is not the primary recovery mechanism; see docs/backup.md.
#
# Usage:
#   DATABASE_URL="jdbc:postgresql://host:5432/jiku?sslmode=require" \
#   DATABASE_USERNAME=jiku DATABASE_PASSWORD=*** \
#   BACKUP_DIR=/var/backups/jiku ./scripts/backup-db.sh
#
# Requires the postgresql-client package (pg_dump) on the host running this —
# deliberately run outside the app/Caddy containers via host cron, so a backup
# still happens even if the compose stack itself is down.
set -euo pipefail

: "${DATABASE_URL:?DATABASE_URL is required (the same JDBC URL the app uses)}"
: "${DATABASE_USERNAME:?DATABASE_USERNAME is required}"
: "${DATABASE_PASSWORD:?DATABASE_PASSWORD is required}"

BACKUP_DIR="${BACKUP_DIR:-/var/backups/jiku}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"

# pg_dump wants a plain postgresql:// URL; the app's DATABASE_URL is the JDBC
# form (jdbc:postgresql://...). Strip the jdbc: prefix rather than maintaining
# two separately configured connection strings.
PG_URL="${DATABASE_URL#jdbc:}"

mkdir -p "$BACKUP_DIR"
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT_FILE="$BACKUP_DIR/jiku-${TIMESTAMP}.sql.gz"

PGPASSWORD="$DATABASE_PASSWORD" pg_dump "$PG_URL" --username="$DATABASE_USERNAME" --no-owner --no-privileges \
    | gzip > "$OUT_FILE"

echo "Backup written to $OUT_FILE"

find "$BACKUP_DIR" -name 'jiku-*.sql.gz' -mtime "+${RETENTION_DAYS}" -delete
echo "Pruned backups older than ${RETENTION_DAYS} days"

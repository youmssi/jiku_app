#!/bin/sh
# Takes a full WAL-G base backup every night at BACKUP_HOUR_UTC and keeps the
# last BACKUP_RETAIN_FULL ones (with the WAL needed to replay from them). Runs
# in its own container next to PostgreSQL, sharing the data volume.
set -eu

hour="${BACKUP_HOUR_UTC:-2}"
retain="${BACKUP_RETAIN_FULL:-14}"

until pg_isready -q; do sleep 5; done

while true; do
    now=$(date -u +%s)
    next=$(date -u -d "today ${hour}:00" +%s)
    [ "$next" -le "$now" ] && next=$(date -u -d "tomorrow ${hour}:00" +%s)
    sleep $((next - now))
    echo "$(date -u +%FT%TZ) base backup starting"
    if wal-g backup-push "$PGDATA"; then
        wal-g delete retain FULL "$retain" --confirm
        echo "$(date -u +%FT%TZ) base backup done"
    else
        echo "$(date -u +%FT%TZ) base backup FAILED" >&2
    fi
done

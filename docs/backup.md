# Database backups

**Primary mechanism:** Neon, the managed Postgres host used in production,
takes continuous backups and offers point-in-time recovery on its own — this is
already true with zero additional configuration on our side. For most incidents
(bad migration, accidental delete, etc.) Neon's own recovery is the correct and
fastest path; see the Neon project dashboard for retention window and recovery
steps.

**What follows is optional defense in depth** — a second, independent copy for
the scenario where the Neon project/account itself is unreachable. It is not
required before launch.

## `scripts/backup-db.sh`

Runs `pg_dump` against `DATABASE_URL` and writes a compressed, timestamped dump
to `BACKUP_DIR`, pruning anything older than `BACKUP_RETENTION_DAYS` (default
14). It deliberately runs on the host, not inside the Docker Compose stack, so a
backup still happens even if the app/Caddy containers are down.

Requires `postgresql-client` (for `pg_dump`) on the host:

```
apt-get install -y postgresql-client
```

Manual run:

```
DATABASE_URL="jdbc:postgresql://<neon-host>:5432/jiku?sslmode=require" \
DATABASE_USERNAME=jiku \
DATABASE_PASSWORD=*** \
BACKUP_DIR=/var/backups/jiku \
./scripts/backup-db.sh
```

## Scheduling it

Add a host crontab entry (not a container — the script needs the credentials as
plain environment variables, which is simplest kept out of the image):

```
0 3 * * * DATABASE_URL="jdbc:postgresql://<neon-host>:5432/jiku?sslmode=require" DATABASE_USERNAME=jiku DATABASE_PASSWORD=*** BACKUP_DIR=/var/backups/jiku /path/to/app/scripts/backup-db.sh >> /var/log/jiku-backup.log 2>&1
```

Copy the dumps off the box periodically (e.g. to object storage) if the box
itself is the disaster scenario you're protecting against — a dump that only
lives on the same host as the thing that might fail is not much of a second
copy.

## Restoring

```
gunzip -c jiku-<timestamp>.sql.gz | psql "postgresql://<user>:<password>@<host>:5432/<target-db>?sslmode=require"
```

Restore into a fresh/empty database, not the live one, unless the intent is
genuinely to overwrite current data.

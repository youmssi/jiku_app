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

The step-by-step procedure, for both the common case (Neon point-in-time) and the
dump case, is in **`docs/runbooks/database-restore.md`**. It is written to be
executed by someone other than its author, during an incident.

The short form, for a dump:

```
gunzip -c jiku-<timestamp>.sql.gz | psql "postgresql://<user>:<password>@<host>:5432/<target-db>?sslmode=require"
```

Restore into a fresh/empty database, not the live one, unless the intent is
genuinely to overwrite current data.

## Rehearsals and measured RTO (JIKU-80)

`scripts/restore-drill.sh` restores a dump into a throwaway database, verifies the
result is actually usable, times it, then drops the database. It writes to no
existing database, so it is safe to run at any time — including against a
production dump before committing to a real restore.

Verification is not "psql returned 0": the drill checks the Flyway history is
present with no failed migration, counts rows in the load-bearing tables, and
fails if any event lost its `tenant_id` — a restore that silently breaks tenant
isolation would be worse than no restore at all.

| Date | Schema | Dataset | Dump | Measured RTO | Conditions |
|---|---|---|---|---|---|
| 2026-09-03 | V32 | production as it stood | n/a (branch) | **18 s** | **Neon PITR, real production project** — see below |
| 2026-09-03 | V38 | 80 471 rows (60 135 guests, 20 035 tickets) | 3.7 MB | 21 s | Local Docker Postgres, application running and holding connections |
| 2026-09-03 | V33 | 471 rows | 68 KB | 4 s | Local Docker Postgres, idle |

### The Neon rehearsal (2026-09-03)

Performed against the real `jiku` production project, not a copy. A branch was
created at a point one hour in the past — the same operation the runbook's case 1
prescribes for a bad migration — verified, then deleted.

```
neonctl branches create --project-id <project> --name restore-drill-<ts> --parent <iso-timestamp>
```

**Result:** branch ready in **18 s**. Flyway history intact at V32 with zero failed
migrations, and zero events missing a `tenant_id` — tenant isolation survived the
restore. The branch was deleted afterwards; only `production` remains.

Two things this rehearsal establishes, and one it does not:

- Neon point-in-time recovery works on this project, and the procedure in the
  runbook is executable as written. This was previously an assumption.
- Branch creation is copy-on-write and does not touch the parent, so the
  rehearsal is safe to repeat at any time, including during an incident.
- **It says nothing about restore time at volume.** Production held 2 tenants and
  1 event at the time. Neon branching is near-constant-time by design, but that
  claim is untested here at scale — the 21 s local figure remains the one to
  budget against for a loaded database.

**How to read these numbers.** They measure the *restore step* — create the target
database, load the dump, verify it. They are not an incident-to-service RTO: a
real incident adds detection, the decision of which point to restore to, and the
cutover. Budget those separately; the runbook lists them.

The 21 s figure is the one to quote. It was taken on the current schema with the
application connected, which is the state a real restore happens in. The 4 s
figure is kept only to show what a near-empty database measures — it is the number
one would misleadingly report by rehearsing on a dev database and stopping there.

Re-run at least quarterly and after any significant schema change:

```
docker compose up -d
./scripts/restore-drill.sh --scale 60000
```

Add a row above each time. An RTO that drifts between quarters is the useful
signal; an RTO never measured is not one.

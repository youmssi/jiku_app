# PostgreSQL with WAL-G (ADR 107): every finished WAL segment is shipped to
# S3-compatible storage (Cloudflare R2), so the database can be restored to any
# minute. The base image stays the official one; only the WAL-G binary is added.
FROM postgres:18

ARG WALG_VERSION=v3.0.9
ADD https://github.com/wal-g/wal-g/releases/download/${WALG_VERSION}/wal-g-pg-22.04-amd64.tar.gz /tmp/wal-g.tar.gz
RUN tar -xzf /tmp/wal-g.tar.gz -C /tmp \
    && install -m 0755 /tmp/wal-g-pg-22.04-amd64 /usr/local/bin/wal-g \
    && rm -f /tmp/wal-g.tar.gz /tmp/wal-g-pg-22.04-amd64

COPY backup-loop.sh /usr/local/bin/backup-loop.sh
RUN chmod 0755 /usr/local/bin/backup-loop.sh

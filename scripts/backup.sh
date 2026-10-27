#!/bin/sh
# Daily local snapshots. Keep off-machine encrypted copies for production.
set -eu
mkdir -p /backups
while true; do
  backup="/backups/database-$(date -u +%Y%m%dT%H%M%SZ).dump"
  pg_dump -Fc -f "$backup.tmp"
  mv "$backup.tmp" "$backup"
  echo "Database backup completed."
  sleep 86400
done

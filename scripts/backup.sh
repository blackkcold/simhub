#!/usr/bin/env bash
set -euo pipefail
umask 077

DEPLOYMENT_ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$DEPLOYMENT_ROOT"
OUT_DIR="${1:-./backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p -m 700 -- "$OUT_DIR"
# Shared with the rootless updater. Unique snapshot paths are still used so an
# interrupted prior run cannot be mistaken for a completed current backup.
exec 9> .simhub-backup.lock
chmod 600 .simhub-backup.lock
flock -n 9 || { echo "Another backup is running for this deployment" >&2; exit 1; }
LOCAL="$(mktemp "$OUT_DIR/simhub-${STAMP}-XXXXXXXX.db")"
REMOTE="/data/$(basename "$LOCAL")"
COMPLETE=0
cleanup() {
  docker compose exec -T simhub rm -f "$REMOTE" >/dev/null 2>&1 || true
  if [[ "$COMPLETE" != 1 ]]; then rm -f -- "$LOCAL" "$LOCAL.sha256"; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker compose exec -T simhub python3 - "$REMOTE" <<'PY'
import contextlib,os,sqlite3,sys
target=sys.argv[1]
fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
os.close(fd)
with contextlib.closing(sqlite3.connect("file:/data/simhub.db?mode=ro",uri=True)) as src, \
     contextlib.closing(sqlite3.connect(target)) as dst:
    src.backup(dst)
    if dst.execute("PRAGMA integrity_check").fetchall()!=[("ok",)]:
        raise SystemExit("Snapshot integrity verification failed")
PY

docker compose cp "simhub:${REMOTE}" "$LOCAL"
chmod 600 "$LOCAL"
python3 - "$LOCAL" <<'PY'
import pathlib,sys
sys.path.insert(0,"scripts")
from backup_tools import verify_database,write_checksum
path=pathlib.Path(sys.argv[1])
verify_database(path)
write_checksum(path)
PY
docker compose exec -T simhub rm -f "$REMOTE"
COMPLETE=1
printf 'Backup written to %s\n' "$LOCAL"
printf 'Rehearse restore: python3 scripts/verify_backup.py %q --restore-check\n' "$LOCAL"

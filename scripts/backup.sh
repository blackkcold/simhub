#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-./backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REMOTE="/data/simhub-backup.db"
LOCAL="${OUT_DIR}/simhub-${STAMP}.db"

mkdir -p "$OUT_DIR"

docker compose exec -T simhub python3 - <<'PY'
import sqlite3
src=sqlite3.connect("/data/simhub.db")
dst=sqlite3.connect("/data/simhub-backup.db")
with dst:
    src.backup(dst)
dst.close()
src.close()
PY

docker compose cp "simhub:${REMOTE}" "$LOCAL"
docker compose exec -T simhub rm -f "$REMOTE"
sha256sum "$LOCAL" > "$LOCAL.sha256"
printf 'Backup written to %s\n' "$LOCAL"

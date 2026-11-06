#!/usr/bin/env python3
"""Verify a backup and rehearse restoration without touching a deployment."""
import argparse
import json
import sqlite3
from pathlib import Path
from backup_tools import verify_checksum, verify_database


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backup", type=Path)
    parser.add_argument("--restore-check", action="store_true", help="Restore into a disposable private SQLite database")
    args = parser.parse_args()
    verify_checksum(args.backup)
    print(json.dumps(verify_database(args.backup, restore_check=args.restore_check), sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, sqlite3.Error) as error:
        raise SystemExit("Backup verification failed: " + str(error))

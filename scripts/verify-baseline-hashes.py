#!/usr/bin/env python3
"""Verify V1.0 workbook hashes against manifest, MariaDB metadata, and stored BLOBs."""
from __future__ import annotations

import csv
import hashlib
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REFERENCE_DIR = ROOT / "data" / "reference"
MANIFEST = REFERENCE_DIR / "SHA256SUMS"
ENV_FILE = ROOT / ".env"
OUTPUT = ROOT / "validation-results" / "2026-10-15" / "baseline-sha256-verification.csv"


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        raise RuntimeError(".env is required for the database integrity check")
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip().strip("'\"")
    return values


def read_manifest(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw_line.strip():
            continue
        if len(raw_line) < 67 or raw_line[64:66] not in ("  ", " *"):
            raise RuntimeError(f"Malformed SHA256SUMS entry at line {line_number}")
        digest = raw_line[:64].lower()
        filename = raw_line[66:]
        if filename.startswith("\\"):
            filename = filename[1:]
        if filename in entries:
            raise RuntimeError(f"Duplicate manifest filename: {filename}")
        entries[filename] = digest
    return entries


def query_database(env_values: dict[str, str]) -> dict[str, tuple[str, str]]:
    db_name = env_values.get("DB_NAME")
    db_user = env_values.get("DB_USER")
    db_password = env_values.get("DB_PASSWORD")
    if not db_name or not db_user or db_password is None:
        raise RuntimeError("DB_NAME, DB_USER, and DB_PASSWORD must be set in .env")
    query = (
        "SELECT original_filename, LOWER(sha256), LOWER(SHA2(content_bytes, 256)) "
        "FROM baseline_document "
        "WHERE baseline_release_id = "
        "(SELECT id FROM baseline_release WHERE version_code = 'V1.0') "
        "ORDER BY original_filename"
    )
    process_env = os.environ.copy()
    process_env["MYSQL_PWD"] = db_password
    result = subprocess.run(
        [
            "mariadb",
            "--protocol=TCP",
            "--host=127.0.0.1",
            f"--user={db_user}",
            "--default-character-set=utf8mb4",
            "--batch",
            "--raw",
            "--skip-column-names",
            db_name,
            "--execute",
            query,
        ],
        cwd=ROOT,
        env=process_env,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    process_env.pop("MYSQL_PWD", None)
    if result.returncode:
        raise RuntimeError("MariaDB hash query failed; verify local service and DB configuration")
    rows: dict[str, tuple[str, str]] = {}
    for line in result.stdout.splitlines():
        fields = line.split("\t")
        if len(fields) != 3:
            raise RuntimeError("MariaDB returned a malformed hash row")
        filename, metadata_hash, blob_hash = fields
        rows[filename] = (metadata_hash.lower(), blob_hash.lower())
    return rows


def main() -> int:
    manifest = read_manifest(MANIFEST)
    workbooks = sorted(p for p in REFERENCE_DIR.glob("*.xlsx") if p.is_file())
    if len(workbooks) != 16 or len(manifest) != 16:
        raise RuntimeError(f"Expected 16 xlsx files and 16 manifest entries; got {len(workbooks)} and {len(manifest)}")

    db_rows = query_database(read_env(ENV_FILE))
    if len(db_rows) != 16:
        raise RuntimeError(f"Expected 16 V1.0 database documents; got {len(db_rows)}")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    results: list[tuple[str, str, str, str, str, str]] = []
    for workbook in workbooks:
        filename = workbook.name
        actual_hash = hashlib.sha256(workbook.read_bytes()).hexdigest()
        manifest_hash = manifest.get(filename, "MISSING")
        database = db_rows.get(filename)
        metadata_hash, blob_hash = database if database else ("MISSING", "MISSING")
        checks = [actual_hash == manifest_hash, actual_hash == metadata_hash, actual_hash == blob_hash]
        status = "PASS" if all(checks) else "FAIL"
        results.append((filename, actual_hash, manifest_hash, metadata_hash, blob_hash, status))

    with OUTPUT.open("w", newline="", encoding="utf-8-sig") as out:
        writer = csv.writer(out)
        writer.writerow(["filename", "actual_sha256", "manifest_sha256", "database_metadata_sha256", "database_blob_sha256", "status"])
        writer.writerows(results)

    missing_manifest_names = set(manifest) - {p.name for p in workbooks}
    missing_database_names = set(db_rows) - {p.name for p in workbooks}
    failures = [row for row in results if row[-1] != "PASS"]
    if missing_manifest_names or missing_database_names or failures:
        print(f"FAIL: {len(failures)} file hash mismatch(es); report: {OUTPUT}", file=sys.stderr)
        return 1
    print(f"PASS: 16/16 local files match manifest, baseline metadata, and MariaDB BLOBs")
    print(f"Report: {OUTPUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Hash verification error: {exc}", file=sys.stderr)
        raise SystemExit(2)

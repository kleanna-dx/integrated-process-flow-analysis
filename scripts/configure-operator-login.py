#!/usr/bin/env python3
"""Set or replace the local operator account without printing credentials."""
from __future__ import annotations

import base64
import getpass
import hashlib
import os
import re
import secrets
import stat
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / ".env"
ITERATIONS = 310_000
SALT_BYTES = 16
DIGEST_BYTES = 32


def main() -> int:
    if not ENV_FILE.is_file():
        print("Missing .env. Configure the local database first; no file was changed.")
        return 1
    username = input("Operator username (3-80 letters/numbers/._-): ").strip()
    if not re.fullmatch(r"[A-Za-z0-9._-]{3,80}", username):
        print("Username format is invalid; no file was changed.")
        return 1
    password = getpass.getpass("New operator password (14-128 characters): ")
    confirmation = getpass.getpass("Confirm password: ")
    if not 14 <= len(password) <= 128 or password != confirmation:
        print("Password length or confirmation did not match; no file was changed.")
        return 1

    salt = secrets.token_bytes(SALT_BYTES)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, ITERATIONS, DIGEST_BYTES)
    encoded = "pbkdf2_sha256:{}:{}:{}".format(
        ITERATIONS, base64.b64encode(salt).decode("ascii"), base64.b64encode(digest).decode("ascii")
    )
    updates = {"APP_OPERATOR_USERNAME": username, "APP_OPERATOR_PASSWORD_HASH": encoded}

    lines = ENV_FILE.read_text(encoding="utf-8").splitlines()
    output: list[str] = []
    replaced: set[str] = set()
    for line in lines:
        stripped = line.lstrip()
        key = line.split("=", 1)[0].strip() if "=" in line and not stripped.startswith("#") else ""
        if key in updates:
            if key not in replaced:
                output.append(f"{key}={updates[key]}")
                replaced.add(key)
            continue
        output.append(line)
    for key, value in updates.items():
        if key not in replaced:
            output.append(f"{key}={value}")

    mode = stat.S_IMODE(ENV_FILE.stat().st_mode)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".env.operator.", dir=ROOT, text=True)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as destination:
            destination.write("\n".join(output) + "\n")
            destination.flush()
            os.fsync(destination.fileno())
        os.replace(temporary_name, ENV_FILE)
        os.chmod(ENV_FILE, 0o600)
    except Exception:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise
    print("Operator login configured. Credential values were not displayed.")
    print("Restart the local app process for the new login to take effect.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

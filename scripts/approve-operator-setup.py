#!/usr/bin/env python3
"""Approve one active first-account request using the server-only admin token."""
from __future__ import annotations

import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE_URL = "http://127.0.0.1:3000"


def read_admin_token() -> str:
    env_file = ROOT / ".env"
    for line in env_file.read_text(encoding="utf-8").splitlines():
        if line.startswith("BASELINE_ADMIN_TOKEN="):
            value = line.partition("=")[2].strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            return value
    return ""


def request_json(path: str, token: str) -> dict:
    req = urllib.request.Request(BASE_URL + path, headers={"X-Admin-Token": token})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    if len(sys.argv) != 2 or not re.fullmatch(r"[0-9a-fA-F-]{36}", sys.argv[1]):
        print("Usage: python3 scripts/approve-operator-setup.py <request-id>")
        return 2
    token = read_admin_token()
    if not token:
        print("Server-side approval is unavailable; the admin token is not configured.")
        return 1
    request_id = sys.argv[1]
    try:
        pending = request_json("/api/admin/operator-setup/requests", token).get("requests", [])
        if not any(item.get("requestId") == request_id for item in pending):
            print("Request is not pending or has expired; nothing was approved.")
            return 1
        req = urllib.request.Request(
            f"{BASE_URL}/api/admin/operator-setup/requests/{request_id}/approve",
            data=b"",
            headers={"X-Admin-Token": token},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=10):
            pass
    except urllib.error.HTTPError as exc:
        print(f"Approval was not completed (HTTP {exc.code}).")
        return 1
    except (OSError, ValueError):
        print("Approval service could not be reached; nothing was approved.")
        return 1
    print("Approved one active request. The requester must finish setup in the same browser within 15 minutes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f .env ]]; then
  echo "Missing .env. Run scripts/bootstrap-sandbox.sh first." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

exec ./mvnw spring-boot:run "$@"

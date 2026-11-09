#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REFERENCE_DIR="$ROOT_DIR/data/reference"
BASE_URL="${BASELINE_URL:-http://127.0.0.1:3000}"

if [[ ! -f "$ROOT_DIR/.env" ]]; then
  echo "Missing .env; run scripts/bootstrap-sandbox.sh first." >&2
  exit 1
fi
if [[ ! -f "$REFERENCE_DIR/SHA256SUMS" ]]; then
  echo "Baseline source files or SHA256SUMS are missing in data/reference/." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source "$ROOT_DIR/.env"
set +a
: "${BASELINE_ADMIN_TOKEN:?BASELINE_ADMIN_TOKEN is missing from .env}"

(cd "$REFERENCE_DIR" && sha256sum --check SHA256SUMS)
MASTER_FILE="$REFERENCE_DIR/전체 목록.xlsx"
[[ -f "$MASTER_FILE" ]] || { echo "The final master workbook is missing." >&2; exit 1; }

process_files=()
process_count=0
for file in "$REFERENCE_DIR"/*.xlsx; do
  [[ "$(basename "$file")" == "전체 목록.xlsx" ]] && continue
  process_files+=(--form "processFiles=@${file};type=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  ((process_count += 1))
done
if [[ "$process_count" -ne 15 ]]; then
  echo "Expected 15 process workbooks, found $process_count." >&2
  exit 1
fi

curl --fail --silent --show-error --max-time 300 \
  --header "X-Admin-Token: ${BASELINE_ADMIN_TOKEN}" \
  --form "masterFile=@${MASTER_FILE};type=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
  "${process_files[@]}" \
  "$BASE_URL/api/admin/baselines"

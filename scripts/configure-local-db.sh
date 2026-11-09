#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE; copy .env.example to .env and set DB_PASSWORD." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${DB_NAME:?DB_NAME is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

[[ "$DB_NAME" =~ ^[A-Za-z0-9_]+$ ]] || { echo "DB_NAME may contain only letters, digits, and underscores." >&2; exit 1; }
[[ "$DB_USER" =~ ^[A-Za-z0-9_]+$ ]] || { echo "DB_USER may contain only letters, digits, and underscores." >&2; exit 1; }
[[ "$DB_PASSWORD" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "DB_PASSWORD may contain only letters, digits, underscores, and hyphens." >&2; exit 1; }

sudo service mariadb start
sudo mariadb <<SQL
CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$DB_USER'@'127.0.0.1' IDENTIFIED BY '$DB_PASSWORD';
ALTER USER '$DB_USER'@'127.0.0.1' IDENTIFIED BY '$DB_PASSWORD';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USER'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

mariadb --protocol=TCP --host=127.0.0.1 --user="$DB_USER" --password="$DB_PASSWORD" "$DB_NAME" \
  --execute='SELECT VERSION() AS mariadb_version, DATABASE() AS connected_database;'

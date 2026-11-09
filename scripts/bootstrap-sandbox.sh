#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v sudo >/dev/null 2>&1; then
  echo "sudo is required to install packages and configure MariaDB." >&2
  exit 1
fi

sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl maven mariadb-server mariadb-client openssl

if [[ ! -x /opt/temurin-17/bin/java ]]; then
  archive="$(mktemp /tmp/temurin17.XXXXXX.tar.gz)"
  trap 'rm -f "$archive"' EXIT
  curl -fL --retry 3 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' -o "$archive"
  sudo mkdir -p /opt/temurin-17
  sudo tar -xzf "$archive" -C /opt/temurin-17 --strip-components=1
fi

sudo update-alternatives --install /usr/bin/java java /opt/temurin-17/bin/java 1710
sudo update-alternatives --install /usr/bin/javac javac /opt/temurin-17/bin/javac 1710
sudo update-alternatives --set java /opt/temurin-17/bin/java
sudo update-alternatives --set javac /opt/temurin-17/bin/javac
printf '%s\n' 'export JAVA_HOME=/opt/temurin-17' 'export PATH="$JAVA_HOME/bin:$PATH"' | sudo tee /etc/profile.d/java17.sh >/dev/null
sudo chmod 644 /etc/profile.d/java17.sh

REFERENCE_DIR="$ROOT_DIR/data/reference"
if [[ -d /home/user/uploaded_files ]]; then
  mkdir -p "$REFERENCE_DIR"
  shopt -s nullglob
  for source_file in /home/user/uploaded_files/*.xlsx; do
    cp -n "$source_file" "$REFERENCE_DIR/"
  done
  if [[ ! -f "$REFERENCE_DIR/SHA256SUMS" ]]; then
    (cd "$REFERENCE_DIR" && sha256sum ./*.xlsx > SHA256SUMS)
  fi
  chmod a-w "$REFERENCE_DIR"/*.xlsx "$REFERENCE_DIR/SHA256SUMS"
fi

if [[ ! -f "$ROOT_DIR/.env" ]]; then
  cp "$ROOT_DIR/.env.example" "$ROOT_DIR/.env"
  sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=$(openssl rand -hex 24)/" "$ROOT_DIR/.env"
  chmod 600 "$ROOT_DIR/.env"
fi
if ! grep -q '^BASELINE_ADMIN_TOKEN=' "$ROOT_DIR/.env"; then
  printf 'BASELINE_ADMIN_TOKEN=%s\n' "$(openssl rand -hex 32)" >> "$ROOT_DIR/.env"
  chmod 600 "$ROOT_DIR/.env"
fi

sudo service mariadb start
"$ROOT_DIR/scripts/configure-local-db.sh"

printf '\nSandbox setup complete. Versions:\n'
java -version 2>&1 | head -n 1
mvn -version | head -n 1
mariadb --version

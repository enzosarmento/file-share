#!/usr/bin/env bash
set -euo pipefail

# Inicia o executavel independente do Servidor Desktop (Headless / CLI)
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if [[ "${1:-}" == "--jar" ]]; then
  shift
  ./gradlew :composeApp:packageDesktopServerJar
  java -jar composeApp/build/libs/fileshare-server-all.jar "$@"
else
  ./gradlew :composeApp:runDesktopServer --args="$*"
fi

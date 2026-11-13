#!/usr/bin/env bash
set -euo pipefail

# Inicia o executavel independente do Servidor Desktop (Headless / CLI)
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"
./gradlew :composeApp:runDesktopServer --args="$*"


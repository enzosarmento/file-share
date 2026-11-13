#!/usr/bin/env bash
set -euo pipefail

# Inicia o aplicativo grafico Desktop (Compose Multiplatform GUI)
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"
./gradlew :composeApp:run


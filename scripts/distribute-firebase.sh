#!/usr/bin/env bash
# Envia um APK do AlarmeHA para os testadores no Firebase App Distribution.
# Uso:
#   bash scripts/distribute-firebase.sh                     # APK foss debug da versão atual
#   bash scripts/distribute-firebase.sh caminho/do/app.apk  # outro APK
#   NOTAS="texto extra" bash scripts/distribute-firebase.sh
# Configuração: copie scripts/firebase.env.example para scripts/firebase.env.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$REPO_DIR/scripts/firebase.env"
if [[ -f "$HOME/android-toolchain/env.sh" ]]; then
  # shellcheck disable=SC1091
  source "$HOME/android-toolchain/env.sh"
fi

if ! command -v firebase >/dev/null 2>&1; then
  echo "ERRO: firebase-tools não encontrado" >&2
  exit 1
fi
# Com FIREBASE_SA_KEY vazio, usa o login do firebase-tools (firebase login).
if [[ -n "${FIREBASE_SA_KEY:-}" ]]; then
  if [[ ! -f "$FIREBASE_SA_KEY" ]]; then
    echo "ERRO: chave da conta de serviço ausente: $FIREBASE_SA_KEY" >&2
    exit 1
  fi
  export GOOGLE_APPLICATION_CREDENTIALS="$FIREBASE_SA_KEY"
fi

PROPS="$REPO_DIR/gradle.properties"
version_name="$(grep -m 1 -E '^VERSION_NAME=' "$PROPS" | cut -d= -f2)"
version_code="$(grep -m 1 -E '^VERSION_CODE=' "$PROPS" | cut -d= -f2)"

apk="${1:-$REPO_DIR/app/build/outputs/apk/foss/debug/alarmeha-${version_name}-foss-debug.apk}"
if [[ ! -f "$apk" ]]; then
  echo "ERRO: APK não encontrado: $apk (rode antes: ./gradlew assembleFossDebug)" >&2
  exit 1
fi

last_commit="$(git -C "$REPO_DIR" log -1 --format='%h %s (%ad)' --date=format:'%d/%m/%Y %H:%M')"
notes="AlarmeHA ${version_name} (versionCode ${version_code})
Último commit: ${last_commit}"
if [[ -n "${NOTAS:-}" ]]; then
  notes="${notes}
${NOTAS}"
fi

echo "==> Enviando $(basename "$apk") para o grupo '${FIREBASE_TESTER_GROUPS}' (projeto ${FIREBASE_PROJECT_ID})"
firebase appdistribution:distribute "$apk" \
  --app "$FIREBASE_ANDROID_APP_ID" \
  --project "$FIREBASE_PROJECT_ID" \
  --groups "$FIREBASE_TESTER_GROUPS" \
  --release-notes "$notes" \
  --non-interactive

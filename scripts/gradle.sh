#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=9.8.0
SHA256=bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c
AREA="$ROOT/.work/tools"
GRADLE="$AREA/gradle-$VERSION/bin/gradle"
if [[ -n "${AMIS_GRADLE_HOME:-}" ]]; then
    GRADLE="$AMIS_GRADLE_HOME/bin/gradle"
    [[ -x "$GRADLE" ]] || { echo 'AMIS_GRADLE_HOME has no executable Gradle' >&2; exit 1; }
    "$GRADLE" --version | grep -qx "Gradle $VERSION" || { echo 'AMIS_GRADLE_HOME must provide pinned Gradle version' >&2; exit 1; }
fi
if [[ ! -x "$GRADLE" ]]; then
    mkdir -p "$AREA"
    curl --fail --location --retry 2 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$AREA/gradle.zip"
    printf '%s  %s\n' "$SHA256" "$AREA/gradle.zip" | shasum -a 256 -c -
    unzip -q -o "$AREA/gradle.zip" -d "$AREA"
fi
cd "$ROOT"
exec "$GRADLE" --no-daemon "$@"

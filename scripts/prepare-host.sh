#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="$(sed -n 's/^overlay.image=//p' "$ROOT/host-contract.properties")"
[[ "$IMAGE" =~ ^ghcr.io/ai-specs/kestra-overlay@sha256:[0-9a-f]{64}$ ]] || { echo 'Expected fixed formal image digest' >&2; exit 1; }
AREA="$ROOT/.integration"
mkdir -p "$AREA"
TEMP="$(mktemp -d "$AREA/host-extract.XXXXXX")"
CONTAINER=''
cleanup() {
    if [[ -n "$CONTAINER" ]]; then
        docker rm "$CONTAINER" >/dev/null
    fi
    python3 - "$TEMP" <<'PY'
import shutil, sys
shutil.rmtree(sys.argv[1])
PY
}
trap cleanup EXIT
docker pull "$IMAGE"
CONTAINER="$(docker create --label owner=amis-foundation-20261125 "$IMAGE")"
docker cp "$CONTAINER:/app/kestra" "$TEMP/host-webserver.jar"
docker image inspect "$IMAGE" --format '{{json .}}' > "$TEMP/image-inspect.json"
python3 "$ROOT/scripts/host-evidence.py" prepare "$TEMP"
mv "$TEMP/host-webserver.jar" "$AREA/host-webserver.jar"
mv "$TEMP/host-provenance.json" "$AREA/host-provenance.json"
mv "$TEMP/host-public-types.txt" "$AREA/host-public-types.txt"
mv "$TEMP/host-flow-validation.txt" "$AREA/host-flow-validation.txt"
echo 'Formal host extracted; no container was started'

#!/usr/bin/env bash
set -euo pipefail

version=2.11.0
destination="${RUNNER_TEMP:-/tmp}/rule34-maestro-$version"
mkdir -p "$destination"
curl --fail --location --retry 3 --silent --show-error \
  "https://github.com/mobile-dev-inc/Maestro/releases/download/cli-$version/maestro.zip" -o "$destination/maestro.zip"
curl --fail --location --retry 3 --silent --show-error \
  "https://github.com/mobile-dev-inc/Maestro/releases/download/cli-$version/checksums_sha256.txt" -o "$destination/checksums.txt"
python3 - "$destination" <<'PY'
import hashlib, sys, zipfile
from pathlib import Path
root=Path(sys.argv[1])
digest=hashlib.sha256((root/'maestro.zip').read_bytes()).hexdigest()
assert digest in (root/'checksums.txt').read_text(), 'Maestro archive checksum mismatch'
with zipfile.ZipFile(root/'maestro.zip') as archive:
    archive.extractall(root)
for script in (root/'maestro/bin').glob('*'):
    script.chmod(0o700)
PY
if [[ -n "${GITHUB_PATH:-}" ]]; then
  echo "$destination/maestro/bin" >> "$GITHUB_PATH"
fi

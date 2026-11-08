#!/bin/sh
#
# Refresh the vendored ACS manifests in `acs/manifests/` from upstream.
#
# The app reads these manifests at runtime to find the NDK/CMake/build-tools
# bundles and the language-server downloads. They are copied into this
# repository so the app does not depend on AndroidCSOfficial's repositories to
# discover versions.
#
# Usage:
#   ./acs/sync-manifests.sh
#   ./acs/sync-manifests.sh --check      # fail if the vendored copies are stale
#
# Re-hosting the binaries on your own releases:
#   ACS_RELEASE_BASE=https://github.com/<owner>/<repo>/releases/download \
#     ./acs/sync-manifests.sh
# This rewrites only the asset URLs that currently point at
# AndroidCSOfficial/acs-build-system releases.
# Termux has no /usr/bin/env, so start under /bin/sh and re-exec bash.
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@"

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/acs/manifests"

UPSTREAM_BUILD="https://raw.githubusercontent.com/AndroidCSOfficial/acs-build-system/refs/heads/main/acs-manifest.json"
UPSTREAM_LSP="https://raw.githubusercontent.com/AndroidCSOfficial/acs-language-servers/refs/heads/main/servers-manifest.json"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1
RELEASE_BASE="${ACS_RELEASE_BASE:-}"

tmp="$(mktemp -d "${TMPDIR:-/tmp}/acs-manifests.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT

fetch() {
  curl -fsSL --retry 3 --retry-delay 2 "$1"
}

fetch "$UPSTREAM_BUILD" >"$tmp/acs-manifest.json"
fetch "$UPSTREAM_LSP" >"$tmp/servers-manifest.json"

if [ -n "$RELEASE_BASE" ]; then
  python3 - "$tmp/acs-manifest.json" "$RELEASE_BASE" <<'PY'
import json
import sys

path, base = sys.argv[1], sys.argv[2].rstrip("/")
prefix = "https://github.com/AndroidCSOfficial/acs-build-system/releases/download/"

with open(path) as fh:
    manifest = json.load(fh)

rewritten = 0
for package in manifest.get("packages", []):
    url = package.get("url", "")
    if url.startswith(prefix):
        package["url"] = f"{base}/{url[len(prefix):]}"
        rewritten += 1

with open(path, "w") as fh:
    json.dump(manifest, fh, indent=2)
    fh.write("\n")

print(f"  rewrote {rewritten} release URLs to {base}")
PY
fi

python3 - "$tmp/acs-manifest.json" "$tmp/servers-manifest.json" <<'PY'
import json
import sys

for path in sys.argv[1:]:
    with open(path) as fh:
        data = json.load(fh)  # validates JSON, raises on corruption
    entries = data.get("packages", data.get("Servers", []))
    print(f"  {path.rsplit('/', 1)[-1]}: {len(entries)} entries")
PY

if [ "$CHECK" -eq 1 ]; then
  stale=0
  for name in acs-manifest.json servers-manifest.json; do
    if ! cmp -s "$tmp/$name" "$DEST/$name"; then
      echo "  stale: acs/manifests/$name"
      stale=1
    fi
  done
  [ "$stale" -eq 0 ] && echo "  vendored manifests are up to date"
  exit "$stale"
fi

cp "$tmp/acs-manifest.json" "$DEST/acs-manifest.json"
cp "$tmp/servers-manifest.json" "$DEST/servers-manifest.json"
echo "  updated acs/manifests/"

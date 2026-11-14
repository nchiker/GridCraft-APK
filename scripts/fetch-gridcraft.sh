#!/usr/bin/env bash
# Downloads the pinned GridCraft web release (gridcraft.version), checks its SHA-256 against both
# the pinned value and the release's SHA256SUMS.txt, and unpacks it unchanged into the app's
# assets. Then writes host.html (our page) from android/app/src/main/web/host.template.html,
# filling in the release's hashed file names.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source <(grep -E '^GRIDCRAFT_[A-Z0-9_]+=' "$root/gridcraft.version")
ver="$GRIDCRAFT_VERSION"
zip="gridcraft-web-$ver.zip"
base="https://github.com/storytold/gridcraft/releases/download/v$ver"
cache="$root/build-out/gridcraft-$ver"
assets="$root/android/app/src/main/assets/gridcraft"

mkdir -p "$cache"
if [[ ! -f "$cache/$zip" ]]; then
  echo "Downloading GridCraft web $ver"
  curl -fsSL -o "$cache/$zip.part" "$base/$zip"
  curl -fsSL -o "$cache/SHA256SUMS.txt" "$base/SHA256SUMS.txt"
  mv "$cache/$zip.part" "$cache/$zip"
fi
got="$(sha256sum "$cache/$zip" | cut -d' ' -f1)"
listed="$(grep -E "  $zip\$" "$cache/SHA256SUMS.txt" | cut -d' ' -f1 || true)"
if [[ "$got" != "$GRIDCRAFT_WEB_SHA256" || "$got" != "$listed" ]]; then
  echo "CHECKSUM MISMATCH for $zip" >&2
  echo "  file:        $got" >&2
  echo "  pinned:      $GRIDCRAFT_WEB_SHA256" >&2
  echo "  SHA256SUMS:  ${listed:-<not listed>}" >&2
  rm -f "$cache/$zip"
  exit 1
fi
echo "Checksum OK ($got)"

rm -rf "$assets"
mkdir -p "$assets"
unzip -q "$cache/$zip" -d "$cache/unpacked.tmp"
src="$cache/unpacked.tmp/gridcraft-web-$ver"
# Only what the app serves; licences and notices go with it.
cp -r "$src/." "$assets/"
rm -f "$assets/index.html" "$assets/_headers" "$assets/.htaccess" "$assets/HOSTING.md" "$assets/README.md"
rm -rf "$cache/unpacked.tmp"

js="$(cd "$assets" && ls gridcraft-web-*.js)"
wasm="$(cd "$assets" && ls gridcraft-web-*_bg.wasm)"
[[ $(wc -w <<<"$js") == 1 && $(wc -w <<<"$wasm") == 1 ]] || { echo "unexpected release layout: $js / $wasm" >&2; exit 1; }
sed -e "s/@GLUE_JS@/$js/g" -e "s/@WASM@/$wasm/g" -e "s/@VERSION@/$ver/g" \
  "$root/android/app/src/main/web/host.template.html" > "$assets/host.html"
cp "$root/android/app/src/main/web/host.js" "$assets/host.js"
echo "GridCraft $ver ready in android/app/src/main/assets/gridcraft ($js, $wasm)"

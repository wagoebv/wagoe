#!/usr/bin/env bash
# Rasterise the Wagoe brand lockup into the four PNGs the app UI serves.
#
#   wagoe-light-512.png       mark + wordmark, deep teal   (shown on light)
#   wagoe-dark-512.png        mark + wordmark, mint        (shown on dark)
#   wagoe-light-512-icon.png  mark only, deep teal
#   wagoe-dark-512-icon.png   mark only, mint
#
# Written to both asset roots, because both are served: libs/ui-style ships them
# to consuming applications, and resources/ is what the dev app serves from the
# repo itself. They must not drift, so this script writes both or neither.
#
# The names are read by wagoe.shared.ui.core.icons/brand-logo and
# wagoe.shared.ui.core.layout, which build them by string concatenation
# ("/assets/wagoe-" theme "-512" suffix ".png") — renaming here means editing
# there.
#
# Needs a Chrome/Chromium binary. Set CHROME=/path/to/binary to override the
# autodetected one, and note that the wordmark is a Google Fonts webfont: this
# script needs network access and fails rather than shipping a fallback face.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
template="$root/scripts/brand/lockup.html"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

targets=(
  "$root/resources/public/assets"
  "$root/libs/ui-style/resources/public/assets"
)

find_chrome() {
  if [[ -n "${CHROME:-}" ]]; then echo "$CHROME"; return; fi
  local candidates=(
    "$HOME/Library/Caches/ms-playwright"/chromium_headless_shell-*/chrome-headless-shell-*/chrome-headless-shell
    "$HOME/Library/Caches/ms-playwright"/chromium-*/chrome-mac/Chromium.app/Contents/MacOS/Chromium
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    "/Applications/Chromium.app/Contents/MacOS/Chromium"
  )
  local c
  for c in "${candidates[@]}"; do
    [[ -x "$c" ]] && { echo "$c"; return; }
  done
  echo "ERROR: no Chrome binary found; set CHROME=/path/to/chrome" >&2
  exit 1
}

chrome="$(find_chrome)"
echo "Using $chrome"

for d in "${targets[@]}"; do
  [[ -d "$d" ]] || { echo "ERROR: asset dir missing: $d" >&2; exit 1; }
done

# shot <theme> <variant> <basename>
#
# --default-background-color=00000000 keeps the canvas transparent; these sit on
# a sidebar, a favicon slot and whatever an application's own chrome happens to
# be, none of which want a white square behind them.
shot() {
  local theme="$1" variant="$2" name="$3"
  local out="$tmp/$name"
  ( cd "$tmp" && "$chrome" --headless --disable-gpu --hide-scrollbars \
      --force-device-scale-factor=1 --allow-file-access-from-files \
      --default-background-color=00000000 --virtual-time-budget=8000 \
      --screenshot="$out" --window-size=512,512 \
      "file://$template?theme=$theme&variant=$variant" >/dev/null 2>&1 )
  [[ -s "$out" ]] || { echo "ERROR: Chrome produced no output for $name" >&2; exit 1; }
  echo "  ✓ $name"
}

echo "Rendering:"
shot light full "wagoe-light-512.png"
shot dark  full "wagoe-dark-512.png"
shot light icon "wagoe-light-512-icon.png"
shot dark  icon "wagoe-dark-512-icon.png"

# A missing webfont is the failure this script is most likely to hit and least
# likely to be noticed: the lockup still renders, just in the wrong face, and the
# result looks plausible enough to commit. The full lockup is measurably wider
# than the icon when the wordmark is present, so compare the two rather than
# trusting that the font loaded.
full_bytes=$(wc -c < "$tmp/wagoe-light-512.png")
icon_bytes=$(wc -c < "$tmp/wagoe-light-512-icon.png")
if (( full_bytes <= icon_bytes )); then
  echo "ERROR: the full lockup is not larger than the icon — the wordmark is" >&2
  echo "       probably missing. Check network access to fonts.googleapis.com." >&2
  exit 1
fi

echo "Installing:"
for d in "${targets[@]}"; do
  for f in wagoe-light-512.png wagoe-dark-512.png \
           wagoe-light-512-icon.png wagoe-dark-512-icon.png; do
    cp "$tmp/$f" "$d/$f"
  done
  echo "  ✓ ${d#"$root"/}"
done

echo "Done."

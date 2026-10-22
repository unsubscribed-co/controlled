#!/usr/bin/env bash
# Fetches Material Symbols Rounded (weight 400, unfilled) as VectorDrawables into app/src/main/res/drawable/sym_<name>.xml.
# Usage: tools/symbols.sh home volume_up ...  (names as on fonts.google.com/icons). Apache-2.0, Google.
set -euo pipefail
cd "$(dirname "$0")/.."
for name in "$@"; do
  svg=$(curl -fsS "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/$name/default/24px.svg")
  path=$(sed -nE 's/.*<path d="([^"]+)".*/\1/p' <<< "$svg")
  [ -n "$path" ] || { echo "No path in $name" >&2; exit 1; }
  # The SVG's viewBox is 0 -960 960 960: shift it down into the drawable's 0..960 viewport.
  cat > "app/src/main/res/drawable/sym_$name.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<!-- Material Symbols Rounded "$name" (Apache-2.0), from tools/symbols.sh; tinted at runtime. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="960" android:viewportHeight="960">
    <group android:translateY="960">
        <path android:fillColor="#FF000000" android:pathData="$path"/>
    </group>
</vector>
XML
done

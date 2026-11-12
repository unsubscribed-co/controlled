#!/usr/bin/env bash
# Google Play releases without the browser: the Play Developer API's "edits" flow, signed in as a service account.
# Its JSON key's path is local.properties play.serviceAccount (never in git; one-time setup: docs/play-api.md).
# Usage (Git Bash):
#   tools/play.sh check                    signs in and lists each track's live versions; changes nothing
#   tools/play.sh listings                 prints the live store listing in every language; changes nothing
#   tools/play.sh listing-put [file]       sends docs/play-listing-text.md as the store listing (Google reviews it)
#   tools/play.sh release <aab> <track>... uploads the bundle and puts it on each track with release-notes.txt's notes.
#                                          The internal track goes live in minutes; a closed track ("alpha") goes to
#                                          Google's review. Run on Juan's "ship it" only (unsubscribed/play-release).
set -euo pipefail
cd "$(dirname "$0")/.."
pkg=dev.unsubscribed.controlled
api=https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$pkg
key=$(sed -nE 's/^play\.serviceAccount=//p' local.properties | sed -E 's/\\:/:/; s/\\\\/\//g')
[ -n "$key" ] && [ -f "$key" ] || { echo "No service account key: set play.serviceAccount in local.properties (docs/play-api.md)" >&2; exit 1; }

# Sign in: a JWT signed with the account's private key, traded for an hour-long access token.
b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
field() { node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1]))[process.argv[2]])' "$key" "$1"; }
pem=$(mktemp)
trap 'rm -f "$pem" "$pem.out"' EXIT
field private_key > "$pem"
now=$(date +%s)
head=$(printf '{"alg":"RS256","typ":"JWT"}' | b64)
claims=$(printf '{"iss":"%s","scope":"https://www.googleapis.com/auth/androidpublisher","aud":"https://oauth2.googleapis.com/token","iat":%d,"exp":%d}' \
  "$(field client_email)" "$now" $((now + 3600)) | b64)
sig=$(printf '%s.%s' "$head" "$claims" | openssl dgst -sha256 -sign "$pem" | b64)
token=$(curl -sS --fail-with-body https://oauth2.googleapis.com/token \
  -d grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer -d assertion="$head.$claims.$sig" |
  node -pe 'JSON.parse(require("fs").readFileSync(0)).access_token')

call() { curl -sS --fail-with-body -H "Authorization: Bearer $token" "$@"; }
get() { node -pe "const r = JSON.parse(require('fs').readFileSync(0)); $1"; }

edit=$(call -d "" "$api/edits" | get 'r.id')
case "${1:-}" in
  check)
    call "$api/edits/$edit/tracks" | get 'r.tracks.map(t => t.track + ": " + (t.releases || []).map(x => (x.name || x.versionCodes) + " " + x.status).join(", ")).join("\n")'
    call -X DELETE "$api/edits/$edit" > /dev/null
    ;;
  listings)
    # The store listing as it is live, every language: title, short and full description. Changes nothing.
    call "$api/edits/$edit/listings" | get 'r.listings.map(l => "### " + l.language + "\n" + l.title + "\n\n" + l.shortDescription + "\n\n" + l.fullDescription).join("\n\n")'
    call -X DELETE "$api/edits/$edit" > /dev/null
    ;;
  listing-put)
    # Sends docs/play-listing-text.md (the "listings" format) as the store listing; Google reviews it before it shows.
    # Run on Juan's OK only.
    file=${2:-docs/play-listing-text.md}
    node -e '
      const t = require("fs").readFileSync(process.argv[1], "utf8").replace(/<!--[\s\S]*?-->/g, "").replace(/\r/g, "");
      const out = t.split(/^### /m).slice(1).map(p => {
        const [lang, ...rest] = p.split("\n"); const [title, short, ...full] = rest.join("\n").trim().split("\n\n");
        const l = { language: lang.trim(), title: title.trim(), shortDescription: short.trim(), fullDescription: full.join("\n\n").trim() };
        if (l.title.length > 30 || l.shortDescription.length > 80 || l.fullDescription.length > 4000) { console.error(l.language + ": over Play limits"); process.exit(1); }
        return l; });
      require("fs").writeFileSync(process.argv[2], JSON.stringify(out));' "$file" "$pem.json"
    for lang in $(node -pe 'JSON.parse(require("fs").readFileSync(process.argv[1])).map(l => l.language).join(" ")' "$pem.json"); do
      node -pe 'JSON.stringify(JSON.parse(require("fs").readFileSync(process.argv[1])).find(l => l.language === process.argv[2]))' "$pem.json" "$lang" > "$pem.body"
      call -X PUT -H "Content-Type: application/json" --data-binary @"$pem.body" "$api/edits/$edit/listings/$lang" > "$pem.out" || { cat "$pem.out" >&2; exit 1; }
      echo "$lang listing sent"
    done
    rm -f "$pem.json" "$pem.body"
    call -d "" "$api/edits/$edit:commit" > "$pem.out" || { cat "$pem.out" >&2; exit 1; }
    echo "Committed: Google reviews the listing before it shows."
    ;;
  release)
    aab=${2:?usage: tools/play.sh release <aab> <track>...}
    shift 2
    [ $# -gt 0 ] || { echo "Name at least one track: internal, alpha" >&2; exit 1; }
    code=$(call -X POST -H "Content-Type: application/octet-stream" --data-binary @"$aab" \
      "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$pkg/edits/$edit/bundles?uploadType=media" | get 'r.versionCode')
    name=$(sed -nE 's/^ *versionName = "([^"]+)"$/\1/p' app/build.gradle.kts)
    # release-notes.txt keeps Play's own format: <en-US>...</en-US><es-419>...</es-419>, 500 characters each at most.
    notes=$(node -e '
      const t = require("fs").readFileSync("release-notes.txt", "utf8");
      const n = [...t.matchAll(/<([a-zA-Z0-9-]+)>\s*([\s\S]*?)\s*<\/\1>/g)].map(m => ({ language: m[1], text: m[2] }));
      const long = n.find(x => x.text.length > 500);
      if (!n.length || long) { console.error(long ? long.language + " notes are over 500 characters" : "release-notes.txt has no notes"); process.exit(1); }
      console.log(JSON.stringify(n));')
    for track in "$@"; do
      body=$(printf '{"track":"%s","releases":[{"name":"%s (%s)","versionCodes":["%s"],"status":"completed","releaseNotes":%s}]}' "$track" "$code" "$name" "$code" "$notes")
      call -X PUT -H "Content-Type: application/json" -d "$body" "$api/edits/$edit/tracks/$track" > /dev/null
      echo "$code ($name) on $track"
    done
    call -d "" "$api/edits/$edit:commit" > /dev/null
    echo "Committed: the internal track is live in minutes; a closed track waits for Google's review."
    ;;
  *)
    call -X DELETE "$api/edits/$edit" > /dev/null
    echo "Usage: tools/play.sh check | listings | listing-put [file] | release <aab> <track>..." >&2
    exit 1
    ;;
esac

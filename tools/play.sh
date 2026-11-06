#!/usr/bin/env bash
# Google Play releases without the browser: the Play Developer API's "edits" flow, signed in as a service account.
# Its JSON key's path is local.properties play.serviceAccount (never in git; one-time setup: docs/play-api.md).
# Usage (Git Bash):
#   tools/play.sh check                    signs in and lists each track's live versions; changes nothing
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
trap 'rm -f "$pem"' EXIT
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
      const n = [...t.matchAll(/<([a-zA-Z-]+)>\s*([\s\S]*?)\s*<\/\1>/g)].map(m => ({ language: m[1], text: m[2] }));
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
    echo "Usage: tools/play.sh check | release <aab> <track>..." >&2
    exit 1
    ;;
esac

#!/usr/bin/env bash
# A Play release, run only on Juan's "ship it" (Alejandría unsubscribed/play-release): bumps the version, builds the bundle
# signed with the upload key (local.properties controlled.upload*, created by tools/create-upload-key.sh), refuses an
# unsigned one, then commits "Release <version>", tags v<version> and pushes. Upload the printed AAB to the internal test.
# Usage (Git Bash): ./ship.sh 1.1
set -euo pipefail
cd "$(dirname "$0")"
name=${1:?usage: ./ship.sh <versionName>, for example 1.1}
[ -z "$(git status --porcelain)" ] || { echo "Commit or stash your changes first." >&2; exit 1; }
if [ -d /d/Programs/AndroidStudio/jbr ]; then export JAVA_HOME=${JAVA_HOME:-/d/Programs/AndroidStudio/jbr} GRADLE_USER_HOME=${GRADLE_USER_HOME:-D:/gradle}; fi

gradle=app/build.gradle.kts
code=$(( $(sed -nE 's/^ *versionCode = ([0-9]+)$/\1/p' "$gradle") + 1 ))
sed -i -E "s/^( *versionCode = )[0-9]+$/\1$code/; s/^( *versionName = )\"[^\"]*\"$/\1\"$name\"/" "$gradle"

./gradlew :app:bundleProdRelease --quiet
aab=app/build/outputs/bundle/prodRelease/app-prod-release.aab
if ! unzip -l "$aab" | grep -qE 'META-INF/[^/]+\.(RSA|EC|DSA)$'; then
  git checkout -- "$gradle"
  echo "The bundle isn't signed: is the upload key in local.properties? (tools/create-upload-key.sh)" >&2
  exit 1
fi

git commit -qam "Release $name"
git tag "v$name"
git push -q && git push -q origin "v$name"
echo "Release $name ($code): $aab"

#!/usr/bin/env bash
# Publishes a new Controlled Dev version on the dev page (config.json dev.homeUrl): builds, checks the signing key,
# uploads the APK, then latest.json. Dev numbers itself by commit count. Prod ships through Google Play (unsubscribed/play-release).
# Usage (Git Bash or CI): ./release.sh dev
set -euo pipefail
cd "$(dirname "$0")"
[ "${1:-}" = dev ] || { echo "Usage: $0 dev (prod ships through Google Play)" >&2; exit 1; }
file=Controlled.apk
# The dev site lives in the dev account: SSO profile dev here, the controlled-dev-deploy role's credentials in CI (empty).
profile=${AWS_PROFILE_DEV-dev}
aws=(aws ${profile:+--profile "$profile"})
# Juan's PC keeps the JDK and Gradle's cache on D:; CI brings its own.
if [ -d /d/Programs/AndroidStudio/jbr ]; then export JAVA_HOME=${JAVA_HOME:-/d/Programs/AndroidStudio/jbr} GRADLE_USER_HOME=${GRADLE_USER_HOME:-D:/gradle}; fi

home=$(node -p "require('./config.json').dev.homeUrl")
want=$(node -p "require('./config.json').dev.signingSha256")
bucket=$("${aws[@]}" cloudformation describe-stacks --region us-east-1 --stack-name UnsubscribedSiteDev \
  --query "Stacks[0].Outputs[?OutputKey=='BucketName'].OutputValue" --output text)
prefix=${home#https://*/} # "controlled": the page's folder in the dev site bucket

code=$(git rev-list --count HEAD)
name="dev.$code"
./gradlew assembleDevSideload -PversionCodeOverride="$code" -PversionNameOverride="$name" --quiet
apk=app/build/outputs/apk/dev/sideload/app-dev-sideload.apk

# Any other key and installed copies refuse the update: it must be the dev key config.json names.
sdk=${ANDROID_HOME:-$(sed -nE 's/^sdk\.dir=//p' local.properties | sed -E 's/\\:/:/; s/\\\\/\//g')}
for f in "$sdk"/build-tools/*/apksigner "$sdk"/build-tools/*/apksigner.bat; do [ -f "$f" ] && apksigner=$f; done
sha=$("$apksigner" verify --print-certs "$apk" | sed -nE 's/^.*Signer.*certificate SHA-256 digest: *//p' | head -1)
[ "$sha" = "$want" ] || { echo "The APK is signed with '$sha', not config.json's dev.signingSha256 ($want)" >&2; exit 1; }

"${aws[@]}" s3 cp "$apk" "s3://$bucket/$prefix/$file" --region us-east-1 \
  --content-type application/vnd.android.package-archive --cache-control 'public, max-age=60' \
  --content-disposition "attachment; filename=\"$file\""
# latest.json last, so installed apps never offer a version that isn't downloadable yet.
echo "{\"versionCode\":$code,\"versionName\":\"$name\"}" |
  "${aws[@]}" s3 cp - "s3://$bucket/$prefix/latest.json" --region us-east-1 --content-type application/json --cache-control 'public, max-age=60'

echo "Released dev $name: $home/"

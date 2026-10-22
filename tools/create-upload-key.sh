#!/usr/bin/env bash
# Creates Controlled's Play upload key ONCE and backs it up to the prod account's SSM (Alejandría unsubscribed/play-release,
# step 2; copied from Nieto's create-release-key.sh). The password is random and never printed: it goes from this script
# straight into the keystore, SSM and local.properties (gitignored), where app/build.gradle.kts reads it.
#
#   tools/create-upload-key.sh        (Git Bash; AWS profile "prod": aws sso login --sso-session unsubscribed)
#
# Play App Signing holds the app-signing key, so a lost upload key can be reset by Play support (days, not lost installs).
# Still: never delete these parameters, and this script never overwrites them.
#   /controlled/upload-keystore           SecureString, Advanced tier: the PKCS12 keystore, base64
#   /controlled/upload-keystore-password  SecureString: the store and key password (PKCS12 uses one for both)
set -euo pipefail
export MSYS_NO_PATHCONV=1 # parameter names start with "/"
cd "$(dirname "$0")/.."

PROFILE="${AWS_PROFILE:-prod}"
REGION=us-east-2
KEYSTORE_PARAM=/controlled/upload-keystore
PASSWORD_PARAM=/controlled/upload-keystore-password
KEYSTORE=D:/Android/upload/controlled-upload.jks
KEYTOOL="${KEYTOOL:-/d/Programs/AndroidStudio/jbr/bin/keytool.exe}"

aws_ssm() { aws ssm "$@" --profile "$PROFILE" --region "$REGION"; }

# Only "ParameterNotFound" counts as absent: an expired login must not look like "no key yet".
for name in "$KEYSTORE_PARAM" "$PASSWORD_PARAM"; do
  if err="$(aws_ssm get-parameter --name "$name" --query Parameter.Version --output text 2>&1 >/dev/null)"; then
    echo "Refusing: $name already exists. The upload key is created only once." >&2; exit 1
  elif ! grep -q ParameterNotFound <<<"$err"; then
    echo "Couldn't check $name in SSM (nothing was created): $err" >&2; exit 1
  fi
done
[ -e "$KEYSTORE" ] && { echo "Refusing: $KEYSTORE already exists." >&2; exit 1; }
mkdir -p "$(dirname "$KEYSTORE")"

CONTROLLED_UPLOAD_PASSWORD="$(node -e 'process.stdout.write(require("crypto").randomBytes(30).toString("base64url"))')"
export CONTROLLED_UPLOAD_PASSWORD
tmp="$(mktemp -d)"
# Git Bash: Node and the AWS CLI need the Windows form of the temp path.
command -v cygpath >/dev/null && tmp="$(cygpath -m "$tmp")"
trap 'rm -rf "$tmp"' EXIT
umask 077

# Passwords reach keytool through the environment, never the command line.
"$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias upload -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Controlled, O=Unsubscribed, C=CO" -storepass:env CONTROLLED_UPLOAD_PASSWORD -keypass:env CONTROLLED_UPLOAD_PASSWORD >/dev/null

# Values go to the AWS CLI as files, so no secret appears in the process list. Password first: a keystore without it is useless.
node -e 'require("fs").writeFileSync(process.argv[1], process.env.CONTROLLED_UPLOAD_PASSWORD)' "$tmp/password"
base64 -w0 "$KEYSTORE" >"$tmp/keystore.b64"
aws_ssm put-parameter --name "$PASSWORD_PARAM" --type SecureString --description "Controlled Play upload key password (never delete)" \
  --value "file://$tmp/password" >/dev/null
aws_ssm put-parameter --name "$KEYSTORE_PARAM" --type SecureString --tier Advanced \
  --description "Controlled Play upload keystore, PKCS12 base64, alias upload (never delete)" --value "file://$tmp/keystore.b64" >/dev/null

# local.properties: the four keys app/build.gradle.kts reads, appended without printing the password.
node -e '
  const fs = require("fs"), p = process.env.CONTROLLED_UPLOAD_PASSWORD;
  fs.appendFileSync("local.properties", `controlled.uploadStoreFile=D\\:/Android/upload/controlled-upload.jks\ncontrolled.uploadStorePassword=${p}\ncontrolled.uploadKeyAlias=upload\ncontrolled.uploadKeyPassword=${p}\n`);
'

echo "Upload key created: $KEYSTORE, backed up to $KEYSTORE_PARAM and $PASSWORD_PARAM; local.properties updated."
"$KEYTOOL" -J-Duser.language=en -list -v -keystore "$KEYSTORE" -storetype PKCS12 -alias upload \
  -storepass:env CONTROLLED_UPLOAD_PASSWORD 2>/dev/null | grep -E '^\s*SHA(1|256):'

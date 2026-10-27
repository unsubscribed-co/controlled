# Configuration and releases

## Apps
| | Prod | Dev |
|---|---|---|
| Name | Controlled | Controlled Dev (plum icon) |
| Package | `dev.unsubscribed.controlled` | `dev.unsubscribed.controlled.dev` |
| Delivered by | Google Play: internal test (list "Controlled testers"), plus a closed test "Alpha" toward going public (testers = Google Group `controlled-testers@googlegroups.com`, anyone can join; sent for review 2026-10-27). Production needs 12 testers opted in for 14 days in a row (`unsubscribed/play-release`). | `https://dev.unsubscribed.dev/controlled/`, updates itself (latest.json) |
| Build | `./gradlew bundleProdRelease` | `./release.sh dev` (CI on every push to `main`) |

`config.json` is the one place for URLs and the dev key's fingerprint. `local.properties` (never committed) holds this PC's paths and secrets:

```
sdk.dir=D\:/Android/Sdk
controlled.devStoreFile=D\:/Android/user/controlled-dev.keystore
# For the Play build (Juan creates the upload key; see play-release step 2):
controlled.uploadStoreFile=D\:/Android/upload/controlled-upload.jks
controlled.uploadStorePassword=...
controlled.uploadKeyAlias=upload
controlled.uploadKeyPassword=...
```

## Keys
- **Dev key:** `D:\Android\user\controlled-dev.keystore`, alias `controlled-dev`, the dev default passwords in `app/build.gradle.kts`. Backed up (base64) in the dev account's SSM `/controlled-dev/dev-keystore`, which CI reads through the `controlled-dev-deploy` role. SHA-256 in `config.json`; `release.sh` refuses any other key, or installed copies couldn't update.
- **Upload key (Play):** not created yet. Backup goes to the prod account's SSM `/controlled/upload-keystore` and `/controlled/upload-keystore-password`.

## The paired TV
One file, `noBackupFilesDir/tv.json` (`Store.kt`): IP, name, client key, certificate pin, MACs. Never backed up: the key only works on the home Wi-Fi.

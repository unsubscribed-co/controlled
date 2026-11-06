# Releasing to Google Play from the command line (one-time setup)

`tools/play.sh` uploads a release and puts it on Play's tracks with no browser. It signs in as a **service account**: a robot Google account that can only do what Play Console allows it to. The setup below is done once, by Juan. The key never goes into git, chat or an agent's hands.

## 1. Create the service account (Google Cloud console, about 5 minutes)
1. Open https://console.cloud.google.com and pick a project, or create one named `unsubscribed-play`. The project is only a container for the account; it costs nothing.
2. **APIs & Services → Library**: search for **Google Play Android Developer API** and **Enable** it.
3. **IAM & Admin → Service accounts → Create service account**:
   - name: `play-publisher`;
   - skip the optional roles and users steps, then **Done**.
4. Open `play-publisher` → **Keys → Add key → Create new key → JSON**. A `.json` file downloads.
5. Move that file to `D:\Android\upload\play-publisher.json`, next to the upload key, outside every repo.

## 2. Give it access in Play Console (about 2 minutes)
1. Play Console → **Users and permissions → Invite new users**.
2. Email: the service account's address, from the JSON's `client_email`, for example `play-publisher@unsubscribed-play.iam.gserviceaccount.com`.
3. **App permissions → Add app → Controlled**, and tick:
   - **Release apps to testing tracks**;
   - **Manage testing tracks and edit tester lists**.
   - Add **Release to production** later, when the app reaches production.
4. **Invite user**. A service account doesn't need to accept; it can take a few minutes to start working.

## 3. Tell the scripts where the key is
Add this line to `local.properties` (it isn't in git):

```
play.serviceAccount=D:/Android/upload/play-publisher.json
```

Back the key up to the prod account's SSM from your own terminal, the same way as the upload key:

```
aws ssm put-parameter --profile prod --name /unsubscribed/play-service-account --type SecureString --value file://D:/Android/upload/play-publisher.json
```

## 4. Check it works
```
tools/play.sh check
```
It lists each track and what's on it, for example `internal: 12 (1.11) completed`, and changes nothing. If it fails with 403, the permissions from step 2 haven't arrived yet: wait a few minutes.

## Every release from now on
1. Write the notes in `release-notes.txt` (English and Spanish, at most 500 characters each) and commit them.
2. On "ship it": `./ship.sh <version>` builds, signs, tags and pushes.
3. Then:
   - `tools/play.sh release app/build/outputs/bundle/prodRelease/app-prod-release.aab internal` publishes to the internal test in minutes, with no review;
   - add `alpha` to the same command to also send the closed test (the public beta) to Google's review.

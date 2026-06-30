# OneDrive Uploader (Android)

Android app that lets you pick `.zip` and `.csv` files from phone storage and upload
them into a chosen folder in your OneDrive, using Microsoft's official sign-in
(MSAL) and the Microsoft Graph API. No third-party server involved — your phone
talks directly to Microsoft.

## What it does
- Sign in with a Microsoft account (personal or work/school)
- Pick one or more `.zip` / `.csv` files via the system file picker
- Type a destination folder path (e.g. `Backups/PhoneExports`) — created automatically if it doesn't exist
- Uploads each file (small files: single PUT; files >4MB: automatic chunked upload session)
- Can also receive files shared directly from other apps (e.g. long-press a zip in Files app → Share → this app) — wiring for the Share intent is included in the manifest; hook `onCreate`'s intent handling up to `selectedUris` if you want this path fully active.

## One-time setup (required before this will run)

### 1. Register an app in Azure
1. Go to https://portal.azure.com → **App registrations** → **New registration**.
2. Name it anything (e.g. "OneDrive Uploader Android").
3. Supported account types: choose **"Accounts in any organizational directory and personal Microsoft accounts"** (so personal OneDrive works too).
4. Leave Redirect URI blank for now — add it after step 3 below.
5. After creation, copy the **Application (client) ID**.

### 2. Add API permission
1. In your app registration → **API permissions** → **Add a permission** → **Microsoft Graph** → **Delegated permissions**.
2. Add `Files.ReadWrite`.
3. Click **Grant admin consent** if you're on a work/school tenant (not needed for personal accounts).

### 3. Get your app's signature hash and set the redirect URI
MSAL on Android needs your debug (or release) keystore's signature hash:

```bash
keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore \
  | openssl sha1 -binary | openssl base64
```
(Default debug keystore password is `android`.)

This prints a hash like `abc123XYZ...=`. URL-encode it if it contains `+`, `/`, or `=`.

Then in **Azure portal → your app → Authentication → Add a platform → Android**:
- Package name: `com.example.onedriveuploader`
- Signature hash: paste the value from above

Azure will generate a redirect URI like:
```
msauth://com.example.onedriveuploader/abc123XYZ...%3D
```

### 4. Update the project with your values
Replace placeholders in these two files:
- `app/src/main/res/raw/msal_config.json` → `client_id` and `redirect_uri`
- `app/src/main/AndroidManifest.xml` → the `BrowserTabActivity` intent-filter's `android:path` (the signature hash segment)

### 5. Build

**Option A — Android Studio (local)**
Open the project in Android Studio and run it on a device/emulator.

**Option B — GitHub Actions (no Android Studio needed)**
This project includes `.github/workflows/build.yml`, which builds a debug APK
automatically in the cloud every time you push to `main`, or on demand.

1. Create a new repository on GitHub (public or private).
2. Push this project to it:
   ```bash
   cd OneDriveUploader
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
   git push -u origin main
   ```
3. On GitHub, go to the **Actions** tab of your repo. The "Build APK" workflow runs automatically on push (or click **Run workflow** to trigger it manually).
4. When it finishes (a few minutes), open the completed run and scroll to **Artifacts** → download `app-debug` (a zip containing `app-debug.apk`).
5. Transfer the APK to your Android phone (e.g. via a download link, USB, or email it to yourself) and install it. You'll need to allow "Install unknown apps" for whichever app you use to open it, since it's not from the Play Store.

This gives you a real, installable APK without ever touching Android Studio. You'll still need to do steps 1–4 above (Azure app registration) and edit `msal_config.json` / `AndroidManifest.xml` with your client ID, redirect URI, and signature hash **before** pushing/building, since those values are baked into the APK.

**Important caveat:** the signature hash from step 4 must match whatever keystore actually signs the APK. GitHub's runner auto-generates its own debug keystore, which is *not* the same as the one on your computer, so a hash you generated locally won't match a GitHub-built APK. To avoid registering two different hashes in Azure, generate your own debug keystore once locally, commit it into the repo (debug-only keystores are fine to commit — they're not secret, just used for local testing), and point Gradle at it explicitly so both local and GitHub builds use the identical key:

```bash
keytool -genkey -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

Place the resulting `debug.keystore` in the project root, then add this to `app/build.gradle` inside the `android { }` block:
```groovy
signingConfigs {
    debug {
        storeFile file("../debug.keystore")
        storePassword "android"
        keyAlias "androiddebugkey"
        keyPassword "android"
    }
}
buildTypes {
    debug {
        signingConfig signingConfigs.debug
    }
}
```
Then re-run the `keytool ... | openssl sha1 ...` hash command from step 4 against this same `debug.keystore` file, and use that one hash everywhere.

## Project structure
```
OneDriveUploader/
├── build.gradle                  (root)
├── settings.gradle
└── app/
    ├── build.gradle               (MSAL, OkHttp, Graph deps)
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── layout/activity_main.xml
        │   ├── raw/msal_config.json     ← fill in client_id/redirect_uri
        │   ├── values/strings.xml
        │   ├── values/themes.xml
        │   └── xml/network_security_config.xml
        └── java/com/example/onedriveuploader/
            ├── MainActivity.kt          ← UI + file picking + orchestration
            ├── AuthManager.kt           ← MSAL sign-in / token acquisition
            └── GraphUploadClient.kt     ← Graph API folder-create + upload (simple & chunked)
```

## Notes
- Files larger than 4MB automatically use Microsoft Graph's chunked "upload session" API, so large zips are supported.
- The destination folder path is relative to the signed-in user's OneDrive root (e.g. typing `Backups/PhoneExports` creates/uses `OneDrive/Backups/PhoneExports`).
- This uses the user's own OneDrive (delegated permissions), not an app-only/service account — the simplest and most common setup for a personal utility app.
- To restrict file types more strictly at the OS picker level beyond MIME type, you can also filter by file extension after the picker returns.

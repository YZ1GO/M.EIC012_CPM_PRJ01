# cleave

## Firebase setup

This project does not commit the Firebase Android config file.

### Required local file

Place your Firebase config file at:

- `app/google-services.json`

### How to get it

1. Open Firebase Console.
2. Select the `cleave` Firebase project.
3. Go to **Project settings** -> **Your apps** -> Android app (`com.cpm.cleave`).
4. Download `google-services.json`.
5. Copy it to `app/google-services.json`.

### Verify setup

Run:

```bash
./gradlew :app:processDebugGoogleServices
./gradlew :app:compileDebugKotlin
```

If both commands pass, Firebase is configured correctly on your machine.

## Netlify App Link Hosting

If you host the Android App Link files on Netlify, deploy the contents of `docs/` as the site root.

Required files:
- `docs/.well-known/assetlinks.json`
- `docs/join/index.html`
- `docs/.nojekyll`

The join page is a browser fallback for `https://cpmcleave.netlify.app/join?joinCode=...` when the app does not open automatically.

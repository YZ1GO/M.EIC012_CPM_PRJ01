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

### Deploy commands (Firebase)

```bash
# Authenticate once
firebase login

# Optional: select project alias used by this repo
firebase use <firebase-project-id>

# Deploy Firestore rules and indexes
firebase deploy --only firestore
```

## Supabase receipt upload

Receipt image upload goes through a Supabase Edge Function.

1. Deploy the `receipts` function in `supabase/functions/receipts`.
2. Set function secrets:

```bash
supabase secrets set APP_SUPABASE_URL=https://<project-ref>.supabase.co
supabase secrets set APP_SUPABASE_SERVICE_ROLE_KEY=<service-role-key>
```

3. Set app endpoint URL in `~/.gradle/gradle.properties` or project `gradle.properties`:

```properties
SUPABASE_UPLOAD_URL=https://<project-ref>.functions.supabase.co
```

4. The app calls `POST {SUPABASE_UPLOAD_URL}/receipts/upload` and expects:

```json
{ "receiptUrl": "https://..." }
```

### Deploy commands (Supabase)

```bash
# Authenticate once
supabase login

# Link local folder to your Supabase project
supabase link --project-ref <project-ref>

# Set/update Edge Function secrets
supabase secrets set APP_SUPABASE_URL=https://<project-ref>.supabase.co
supabase secrets set APP_SUPABASE_SERVICE_ROLE_KEY=<service-role-key>

# Deploy receipt upload function
supabase functions deploy <function-name>
```

## Netlify App Link Hosting

If you host the Android App Link files on Netlify, deploy the contents of `docs/` as the site root.

Required files:
- `docs/.well-known/assetlinks.json`
- `docs/join/index.html`
- `docs/.nojekyll`

The join page is a browser fallback for `https://cpmcleave.netlify.app/join?joinCode=...` when the app does not open automatically.

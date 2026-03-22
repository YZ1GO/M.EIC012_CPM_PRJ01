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

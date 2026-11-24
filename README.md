# Platinum Pizzaria Android app

A native Android app built with Kotlin and Jetpack Compose. This is not a WebView wrapper.

## Current features
- Native mobile rewards home screen.
- Email/password sign-in and account creation using the existing Platinum Pizzaria Supabase project.
- Reads the signed-in user's `points` and `tier` from `public.reward_accounts`.
- Manual refresh of the rewards balance.
- Order Now and Menu shortcuts open the existing live website.

## Open and run
1. Open this repository in Android Studio.
2. Allow Gradle sync to complete.
3. Run on an Android device or emulator.
4. To create an APK, select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

## Important status
This is an initial source-code release, **not a signed APK or Play Store release**. It has not yet been built or tested on a physical Android device.

Not yet implemented: GitHub OAuth, password recovery, automatic refresh-token rotation, encrypted token storage, and realtime points updates. The app currently stores its initial auth session in app-private preferences; improve this before a production release.

The existing rewards table must allow authenticated users to select their own row through Supabase Row Level Security. Email confirmation may be required for new accounts.

Only the public Supabase publishable key is included. Never put a service-role key in a mobile app.

The live website and its repository are not modified by this app.

# TCG Card Scanner

Android app for scanning Pokémon cards into collections, pricing them with TCGplayer market
prices, and exporting them to CSV.

## How it works

1. **Scan**: the camera feed is OCR'd on-device (ML Kit). The app reads the card **name** (the
   largest text) and the **collector number** (e.g. `025/165`).
2. **Identify**: the number + set size (+ name) are looked up in [TCGdex](https://tcgdex.dev), a
   free, open-source card database (no API key) that carries each card's TCGplayer product ID and
   TCGplayer prices. TCG Pocket (mobile game) cards are filtered out.
3. **Confirm**: tap **Identify this card** (or type a search like `Charizard ex 199/165`), then tap
   a variant chip (Normal / Holo / Reverse Holo…) to add it to the current collection. Turn on
   **Auto-identify** to look cards up as soon as the same collector number is read 3 frames in a row.
4. **Collections**: tap the collection name at the top to switch, create, rename or delete
   collections. Each card's ⋮ menu can move some or all copies to another collection. Adjust
   quantity, tap the condition chip to cycle NM → LP → MP → HP → DMG, tap a card to open its
   tcgplayer.com product page, and refresh prices.
5. **Search and sort**: tap 🔍 to filter by Pokémon or set name (partial names work: `char` finds
   Charizard, Charmander and Charizard ex), by type and by rarity, in one collection or all of them.
   Sort by recently added, name, or Pokédex number.
6. **CSV**: export this collection or all of them, or **Import from CSV** to restore an export
   (e.g. when moving to a new phone). Imports recreate the collections named in the file and add
   to existing quantities, so importing the same file twice doubles the counts.

CSV columns: Collection, Quantity, Name, Set, Set Code, Card Number, Set Total, Rarity, Type,
Dex Number, Variant, Condition, Market Price (USD), Total Value (USD), TCGplayer URL, Card ID,
Date Added.

The app originally used the Pokémon TCG API (pokemontcg.io), which is deprecated. Cards saved
back then are matched to TCGdex automatically the next time the app starts.

## Building

1. Install [Android Studio](https://developer.android.com/studio) (it brings the Android SDK and a
   compatible JDK).
2. **File → Open** the cloned project folder (the one containing `settings.gradle.kts`) and let
   Gradle sync. The first sync downloads a lot and can take several minutes.
3. Plug in your phone with USB debugging enabled and press **Run**.

From a terminal instead: `gradlew assembleDebug` (Windows) or `./gradlew assembleDebug`
(macOS/Linux). The APK lands in `app/build/outputs/apk/debug/`.

Toolchain: Android Gradle Plugin 9.4, Gradle 9.6, Kotlin 2.2 (AGP's built-in Kotlin), KSP 2.3,
Room 2.8, JDK 21 (fetched automatically via `gradle/gradle-daemon-jvm.properties`), compileSdk 35,
minSdk 26 (Android 8.0).

## Troubleshooting build errors

**First, the general fixes** (they solve most odd errors after pulling changes or upgrading):

1. **File → Sync Project with Gradle Files.**
2. **Build → Clean Project**, then build again. KSP and Room keep generated code that can go stale.
3. **File → Invalidate Caches… → Invalidate and Restart.**
4. Still stuck: close Android Studio, delete the project's `.gradle/`, `build/` and `app/build/`
   folders, reopen and sync.

If none of that helps, find the **first** red error in the **Build** tab (later errors are usually
knock-on effects) and look it up below.

| Error mentions | Cause | Fix |
|---|---|---|
| `Unsupported class file major version 69`, or Gradle refusing to run on Java 25 | Gradle is running on a JDK that's too new | **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** → pick **jbr-21** (Android Studio's bundled JDK). From a terminal, set `JAVA_HOME` to a JDK 17 or 21. |
| `Failed to find target android-35` / SDK platform not installed | Android 15 (API 35) SDK missing | Click the **Install** link in the error, or **Tools → SDK Manager** → tick **Android 15 (API 35)**. |
| `SDK location not found` | No `local.properties` (it isn't in git) | Open the project in Android Studio once (it creates the file), or create `local.properties` containing `sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`. |
| `[ksp] java.lang.IllegalStateException: unexpected jvm signature V` | Room older than 2.7 running under KSP2 | Keep Room at **2.7.0 or newer** in `app/build.gradle.kts` (this project uses 2.8.5). |
| `… cannot be cast to class com.android.build.gradle.BaseExtension` | A plugin expecting AGP 8's old DSL, usually `org.jetbrains.kotlin.android` | Don't apply `org.jetbrains.kotlin.android`; AGP 9 compiles Kotlin itself. Make sure `gradle.properties` doesn't contain `android.newDsl=false` or `android.builtInKotlin=false` together with that plugin. |
| `'fun Project.android(…)' is deprecated` / `The option setting 'android.…' is deprecated` | Leftover AGP 8 compatibility flags in `gradle.properties` | Remove the flag the warning names. It's only a warning; the build still works. |
| `Schema export directory was not provided` | Room has nowhere to write schema snapshots | Keep the `ksp { arg("room.schemaLocation", …) }` block in `app/build.gradle.kts`. |
| `Could not resolve …` / `Could not GET …` | Network hiccup or corrupted download | Check your connection and sync again. If it persists: **File → Invalidate Caches**, or delete `~/.gradle/caches/` and sync. |
| `Permission denied: ./gradlew` (macOS/Linux) | Script lost its executable bit | `chmod +x gradlew` |
| `Unable to delete file` / file locked (Windows) | A Gradle daemon or antivirus holds the file | Run `gradlew --stop`, then build again. Add the project folder to your antivirus exclusions if it keeps happening. |

### Signing and release builds

| Problem | Fix |
|---|---|
| `Get Key failed: Given final block not properly padded` | Wrong **key** password. Keystores created by Android Studio are PKCS12, which use the keystore password for the key too: enter the **keystore password in both password fields**, and pick the alias from the list instead of typing it. |
| Forgot the keystore password | It can't be recovered. If you've never shipped an APK signed with that key, delete the `.jks` and create a new one. If you have, you'll need a new key, and users must uninstall the old app (back up with CSV export first). |
| Got an `app-release.aab` instead of an APK | `.aab` is for the Play Store and can't be installed on a phone. Run **Build → Generate Signed App Bundle or APK** again and choose **APK** on the first screen. |
| `App not installed` / `package conflicts with an existing package` | The installed app was signed with a different key (e.g. a debug build from Android Studio). Export your collections to CSV, uninstall, install the new APK, then import. |
| Update won't install over the previous release | Increase `versionCode` in `app/build.gradle.kts`, and sign with the **same keystore** as before. |

### App crashes on launch after an update

If Logcat shows `A migration from N to M was required but not found` or `Room cannot verify the
data integrity`, the entity classes changed without a database migration. Every change to
`CollectionEntry` or `CardCollection` needs: the `version` in `@Database` increased, a matching
`Migration(old, new)` added to `addMigrations(...)` in `AppDatabase.kt`, and the new schema JSON
in `app/schemas/` committed. Never edit an existing migration that has already shipped.

## Scanning tips

- Good, even light; avoid glare on holo cards.
- Fill the on-screen frame with the card so the small collector number is legible.
- Promo and older cards without a `NNN/NNN` number: use the search box.

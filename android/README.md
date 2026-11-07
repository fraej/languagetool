# LangT Android

A native **Java** Android editor that runs this fork's LanguageTool engine on the
device. English, Spanish, and Catalan grammar rules, dictionaries, POS data, and NLP models
ship inside the APK. Checking works on first launch in airplane mode. There is
no account, server, first-run download, or `INTERNET` permission.

## Features

- English (US/UK), Spanish, and all three Catalan engine varieties: General,
  Valencià, and Balear. The language picker selects each variety's actual
  grammar, spelling, and conjugation rules.
- Android Material 3 interface with a standard app bar, full-height text editor,
  language dialog, toolbar actions, and correction bottom sheets.
- Deep-black Material 3 theme, including the editor, dialogs, and correction sheets.
- Bundled language flags for US/UK English, Spanish, Catalan, Valencià, and Balear.
- A permanent Copy all icon in the app bar copies the entire document.
- Tap a marked word or Suggestions to open corrections. Copy, Paste, Clear,
  examples, and licenses are available in the app bar's menu.
- Bundled resources for every supported language, with no language-pack downloads.
- Grammar, spelling, punctuation, and style suggestions from the local engine.
- Automatic checking after a 700 ms typing pause, with an explicit Check button.
- Highlighted error ranges, explanations, and tap-to-apply replacements.
- Ignore a suggestion for the current results; copy the corrected text.
- Receive text through Android's Share and Process Text menus. For editable
  Process Text requests, return the corrected text to the calling app.
- Retain the editor text and language across activity recreation. Text is not
  written to a draft file or included in application logs; backups are disabled.
- Remember the last-used language and warm it up at app startup, including its
  dictionaries and tagger. Keep the prepared engine across activity recreation.
- Store expanded grammar/disambiguation rule data for the last-used profile in
  private permanent storage, so a new process can bypass XML parsing and entity
  expansion. Cache checksums detect damage; each app update gets a fresh cache.
- Persist compiled supplemental spelling dictionaries, POS-pattern intersections,
  and multiword maps, avoiding their reconstruction on subsequent cold starts.
- Bundled open-source notices accessible from About & licenses.

Android 13 (API 33) or later is required. This is an editor, not a keyboard or an
accessibility service. Checking is limited to 10,000 UTF-16 characters at a time;
longer text remains editable and is never silently truncated. The first 100
suggestion cards and the first five replacements per issue are displayed.

## Build and install

Install a **JDK 17 or 21**, Android SDK platform **35**, and build tools **35.0.0**.
Open this `android/` directory as the project in Android Studio, or run:

```sh
cd android
# Set ANDROID_HOME to your SDK, or set sdk.dir in local.properties.
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper is included and its distribution checksum is pinned. The
first build needs internet to resolve tools and dependencies; the installed app
does not. A subsequent build can use `--offline` after dependencies are cached.
Maven installation and a full desktop LanguageTool build are not required.

`./gradlew :app:assembleRelease` creates `app-release.apk`, a non-debuggable
development release signed with the local development key. Published development
APKs use the CI runner's development key. Configure your own stable release key
for production distribution; no private release credentials belong in this
repository. Debug APKs remain available for local development.

## Verify offline operation

```sh
./gradlew :engine:test :app:lintRelease :app:assembleReleaseAndroidTest
adb shell svc wifi disable
adb shell svc data disable
./gradlew :app:connectedReleaseAndroidTest
```

Device tests exercise the installed engine and packaged resources, verify the
absence of `INTERNET`, and check grammar, spelling, language switching, and UTF-16
offsets after emoji and newlines. Enable airplane mode before first launch and
use **Try an example** to verify the same behavior manually.
The example follows the selected language. Tests cover native language/menu
interactions, bottom-sheet corrections, dark mode, restored editor state, and
Spanish/Catalan agreement, accents, and verb forms. CI stores screenshots of the
editor, language picker, corrections, and dark theme with the device reports.

GitHub Actions builds the APK and runs device tests on API 33 and 35. Build
artifacts are available from the workflow run; they are development builds.

## How the port works

`engine/` compiles `languagetool-core` and the English, Spanish, and Catalan modules directly from this
checkout. It reads dependency coordinates and versions from the existing Maven
POMs, so local changes to rules and engine code become part of the app. The
language-module class registries are merged so all bundled languages can be
discovered. Spanish and Catalan use their bundled Morfologik dictionaries.
The Android build adapts Java 16's `Stream.toList()` in generated sources, while
core-library desugaring supplies supported newer Java APIs on older Android.
The desktop sources retain their original stream behavior.
The Android sources route regex compilation through `AndroidPatterns`, which
uses ICU's equivalent Unicode punctuation category, omits Java's Unicode
character-class switch, and removes Java's empty flag group from grammar XML.
Android already uses Unicode character classes. Java's permissive numeric
backreferences are converted to equivalent ICU expressions, preserving valid
alternatives and trailing literal digits. Quoted and literal expressions are
preserved. A redundant quantifier in the bundled number-spacing pattern is
removed without changing its matches or capture groups. Other unsupported
regexes still fail explicitly. A literal brace token in the Spanish grammar is
escaped for ICU while retaining its Java matches.

Android's platform XML parser does not support the parameter entities used by
the grammar files. The Android build explicitly selects bundled Xerces in the
rule loaders; Android ignores the usual Java factory-selection system property.
SRX sentence-splitting rules use the platform parser without XSD validation,
which Android's factory does not support. These bundled SRX rules are trusted
application resources, not user-supplied XML. The shared `RuleSaxParser` helper
preserves external-DTD loading being disabled and tolerates only unsupported JDK-specific entity-limit
properties. Both grammar and disambiguation loaders use this helper.

The Android dependency graph omits the unrelated Jakarta JAXB 4 runtime and
duplicate legacy JAXB/activation API classes.

The engine compiles against Android dependency variants, including Android
Guava. `AndroidSuppliers` bridges its suppliers to Java's supplier interface,
preserving memoization and avoiding multi-release JAR interface mismatches.
Stream reading and SHA-256 formatting use Android-compatible library APIs.

The pinned Morfologik speller is compiled from its Maven source artifact with a
small decoder fix: check `flush()` before reading the next decoded character.
Android can defer incomplete UTF-8 errors until that call. This retains accented
dictionary words and spelling suggestions. The binary speller JAR is excluded
to avoid duplicate classes, and its BSD license is bundled with the app.

Notices embedded in the runtime JARs and LanguageTool's LGPL text are collected into an offline asset at build
time. Shrinking and obfuscation are disabled in this first version because XML
rules name Java classes reflectively. Release builds retain the same engine
classes and resources as debug builds.

All checking happens on a single background worker. At most one newer check is
queued. Revision checks discard outdated results, and editing or changing the
language immediately invalidates suggestions, preventing replacements from
being applied at stale offsets. A check already running may finish before the
newest queued check starts.

## Scope and limits

This app includes the open-source local English, Spanish, and Catalan engines, not LanguageTool's
hosted AI services or premium checks. Optional large n-gram datasets are not
bundled. Other languages need their own resources, dependency review, and device
tests before being added. Startup time and memory usage vary by device. The last
selected language starts loading on application startup, before any user text is
checked. Returning while the app process is alive reuses that initialized engine.
After process death, cached SAX data skips XML parsing but the engine must still
recreate Java rule objects and native regexes. Compiled supplemental dictionaries,
POS-pattern intersections, and prepared multiword maps also persist, avoiding
dictionary FSA compilation, repeated POS scans, and multiword expansion. Cold
startup is not instantaneous. The cache is an optimization, not a saved JVM heap.
It contains bundled rule data only, never submitted text. Only the last warmed
profile is retained on disk, and old-version cache data is removed. A failed or
unwritable cache falls back to bundled XML without disabling language checks.
XML examples used by upstream rule tests are omitted from the Android cache and
runtime rule objects; pattern tokens, filters, messages and suggestions are retained.
The installed-app CI suite runs against the signed non-debuggable release APK.
It compares fresh processes on the same emulator with computed-data caches
disabled and enabled, records startup samples/timings, and checks that cached
startup does not rebuild dictionaries, POS intersections, or multiword maps.
Inactive languages' cached XML rule lists are released when switching languages
or varieties, while shared dictionaries remain available. This Android-only
adaptation avoids keeping separate large Catalan grammar lists for every variety.
The application requests Android's larger heap allowance for the bundled NLP
models and rule caches. The multilingual engine can exceed a device's ordinary
192 MB app heap; its available memory still depends on the device.

The UI and port code are LGPL 2.1 or later, like the engine. Bundled resources and
dependencies retain their own licenses and notices. Full source and build
instructions remain available in this repository so the engine and app can be
modified and rebuilt.

# Melodia port (macOS · hideable panels · English UI)

This fork adds the following to [rinchao0721/Melodia](https://github.com/rinchao0721/Melodia) on top of upstream `dev`:

- **macOS (Apple Silicon):**
  - native window with system traffic lights
  - Dock menu with playback controls
  - Melodia › Settings… (⌘,)
- **Hideable side panels:** the fold buttons hide the library and Now Playing completely. A tab appears when the pointer nears the window edge.
- **English UI:** Settings › System › 语言 / Language (restart to apply). Works on Windows too.

## Downloads

See [Releases](../../releases). A workflow runs every day. It replays this branch onto the latest upstream `dev`, builds the macOS DMG and the Windows installer/zip, and publishes a release when anything changed. If upstream changes conflict with the port, it opens an issue instead.

## How it stays merge-friendly

- Port code lives in new files. Upstream files only get small hooks.
- **The English UI doesn't touch upstream source.** `buildSrc/src/main/kotlin/MelodiaI18n.kt` rewrites the compiled jars so that strings listed in `desktopApp/src/main/resources/i18n/en.tsv` go through a lookup. New upstream text simply shows in Chinese until it's added to the dictionary.
- Log messages stay in Chinese. Data-layer classes (API, repositories, parsers, preferences) and strings used in comparisons are never rewritten.

## Updating translations

```bash
./gradlew :desktopApp:extractI18nStrings   # writes desktopApp/build/i18n/strings.tsv
```

Each line has the Chinese text, a usage flag (`ui` / `log` / `logic`) and the classes that use it. Add `中文<TAB>English` lines to `en.tsv`. Placeholders `{0}`, `{1}` can be reordered.

## Building locally (macOS)

```bash
brew install mpv dylibbundler openjdk@21
JAVA_HOME=$(brew --prefix openjdk@21) ./gradlew :desktopApp:packageReleaseDmg
```

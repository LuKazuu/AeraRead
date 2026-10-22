# Aera Read — EPUB & PDF reader for Android

A small, fast, Material 3 reading app. Native Android (Java), no heavy rendering
dependencies: the EPUB engine is a custom paginator on top of `StaticLayout`, PDFs use
the platform `PdfRenderer`.

## Features

**Library**
- Point the app at one or more folders; it scans them and lists every EPUB and PDF
- Books are read in place — nothing is copied, imported or duplicated
- Grid with cover art and reading progress, rescan, remove, folder management
- Consistent card geometry; documents without an author display "No author"

**Reading**
- EPUB 2/3: chapters, headings, quotes, lists, inline illustrations, cover art, and `<ruby>` reading annotations (furigana, Pinyin, W3C complex ruby)
- Pagination rebuilt for your screen and typography; changing font, size or spacing keeps your exact place in the text
- Pinch to magnify any page and drag to pan, in both pages and scroll mode
- PDF via `PdfRenderer`: crisp page rendering, pinch-zoom & pan
- Uninstalling removes everything: no cloud backup, no device transfer, all state inside the app's own storage

**Typography & Appearance**
- System font by default; 15 Latin and 7 CJK Google Fonts available via **Manage** inside Appearance
- Text size 14–30 sp (default 18 sp), line spacing 125–190% applied live while dragging
- Reading themes: Paper, Sepia, Slate, Night — with high-contrast swatch selection rings across dark and light system themes
- Scroll mode or Pages mode (L/R or R/L), tap zones, progress slider, chapter contents, and character-anchored bookmarks

## Project layout

```
app/src/main/java/com/aera/read/
  data/      Book, Bookmark, Library, ReaderPrefs, ReaderTheme
  io/        SeekableSource, FileSource, FdSource, CachingSource, ZipArchive, BookFiles
  epub/      EpubBook, EpubParser, EpubPaginator, RubySpan, PagedBook, ImageStore, TextStyles
  pdf/       PdfPageSource, PdfRendererSource
  reader/    ReadingSession, EpubSession, PdfSession
  ui/        MainActivity, ReaderActivity, sheets, adapters, custom views
  util/      Fonts, FontCatalog, FontStore, Ui
app/src/test/  Headless Robolectric unit tests
```

## Build

Requires JDK 17 and Android SDK (`platforms;android-34`, `build-tools;34.0.0`).

```bash
./build.sh                 # interactive menu: 1) APK only  2) Release (build + tag)
./build.sh apk             # compile signed R8-optimized AeraRead-1.0.apk
./build.sh release v1.0    # compile APK and create git release tag
./build.sh test            # run unit test suite
./build.sh lint            # run Android lint
./build.sh clean           # delete build outputs
```

On GitHub Actions (`.github/workflows/build.yml`), trigger **Run workflow** and select **Build target**:
- **Actions only** — compiles `AeraRead-1.0.apk` and uploads it as a workflow artifact.
- **Release** — compiles `AeraRead-1.0.apk`, uploads the workflow artifact, tags the commit, and publishes a GitHub Release.

# Aera Read — EPUB & PDF reader for Android

A tiny, fast, Material 3 reading app supporting **Android 9 (API 28) through Android 17 (API 37)**.
Native Android (Java) with zero bloated rendering engines: EPUBs use a custom single-pass inline
paginator on top of `StaticLayout`, and PDFs use `PdfRenderer` with automatic vector margin
trimming and hardware-accelerated full-page theming.

## Features

**Library**
- Point the app at one or more folders; it scans them and lists every EPUB and PDF
- Books are read in place via lock-free positional `FileChannel` (`pread`) I/O — nothing is copied, imported or duplicated
- Responsive edge-to-edge grid with `3:4` cover art and reading progress, rescan, remove, and folder management
- Consistent card geometry; documents without an author display "No author"

**EPUB Reading Engine**
- EPUB 2/3 single-pass parser: chapters, headings, blockquotes, lists, standalone illustrations, cover art, and `<ruby>` reading annotations (furigana, Pinyin, W3C complex ruby)
- Rich inline formatting: **bold**, *italic*, <u>underline</u>, ~~strikethrough~~, `monospace/code`, superscript/subscript, small/small-caps, plus embedded `<style>` and linked `.css` stylesheet rules
- Inline images & gaiji: inline icons, East Asian gaiji glyphs, equations, and `data:image/...` assets flow naturally inside text lines without breaking paragraphs
- Pagination rebuilt dynamically for your screen, window size, and typography while keeping your exact character anchor

**PDF Reading Engine**
- Configurable 3-level **Margin trim** (**Off** · **Normal** · **Max**): keep the original PDF margins untouched (**Off**), use comfortable balanced margin trimming by default (**Normal**), or expand content edge-to-edge with maximum safe trimming (**Max**) — all using uniform vector sub-region rendering without squishing or clipping a single glyph or image pixel
- Full-page or background-only theming: applies Sepia, Slate, and hue-preserving Night themes across the entire PDF page by default, with an Appearance toggle to theme only the surrounding background (**Light** applies zero effects)
- Continuous vertical **Scroll** mode or **Pages** mode (`L/R` or `R/L` page turns), with exact sub-page scroll position resume
- Pinch to magnify any page (with automatic high-DPI re-rendering) and drag to pan

**Typography & Appearance**
- System font by default; 15 Latin and 7 CJK Google Fonts available via **Manage** inside Appearance
- Text size 14–30 sp (default 18 sp), line spacing 125–190% applied live while dragging
- Reading themes: **Light** (pure, zero effects), **Sepia**, **Slate**, **Night**
- Scroll mode or Pages mode (L/R or R/L), progress slider, chapter contents, and character-anchored bookmarks
- **Tap left/right to turn pages** — opt-in Appearance switch (off by default) enabling left/right edge taps in both Pages and Scroll mode; a Scroll-mode tap advances 60% of the viewport so the previous lines stay in view. With the switch off, every tap on the page simply toggles the toolbars

## Project layout

```
app/src/main/java/com/aera/read/
  data/      Book, Bookmark, Library, ReaderPrefs, ReaderTheme
  io/        SeekableSource, FileSource, FdSource, CachingSource, ZipArchive, BookFiles
  epub/      EpubBook, EpubCss, EpubParser, EpubPaginator, InlineStyleSpan, InlineImageSpan, RubySpan, PagedBook, ImageStore, TextStyles
  pdf/       PdfPageSource, PdfRendererSource
  reader/    ReadingSession, EpubSession, PdfSession
  ui/        MainActivity, ReaderActivity, ZoomController, CoverImageView, sheets, adapters, custom views
  util/      Fonts, FontCatalog, FontStore, Ui
app/src/test/  Headless Robolectric 4.17 Native Graphics unit & UI tests (SDK 37)
```

## Build

Requires JDK 17+ (JDK 21 recommended) and Android SDK (`platforms;android-37.0`, `build-tools;37.0.0`).

```bash
bash build.sh                 # interactive menu: 1) APK only  2) Release (build + tag)
bash build.sh apk             # compile signed R8-optimized AeraRead-1.2.apk
bash build.sh release v1.2   # compile APK and create git release tag
bash build.sh test            # run Robolectric Native Graphics test suite
bash build.sh lint            # run Android lint
bash build.sh clean           # delete build outputs
```

GitHub Actions (`.github/workflows/build.yml`) runs manually via `workflow_dispatch`:
- **Actions only** — compiles `AeraRead-1.2.apk` and uploads it as a workflow artifact.
- **Release** — compiles `AeraRead-1.2.apk`, uploads the workflow artifact, tags the commit, and publishes a GitHub Release.

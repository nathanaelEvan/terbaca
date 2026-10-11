# M5 Verification Gaps Resolution (M6-09)

Report verifying the post-release review items from Milestone M5 on the Xiaomi Pad 7 (HyperOS 3.0, 3200×2136 @ 120 Hz).

---

## 1. Floating Pill Position Shift (~6 dp)

### Root Cause Analysis
In PR #68 (accessibility pass), `FloatingPill` was updated to guarantee the 48 dp minimum interactive touch target required by Material 3 and TalkBack guidelines:
```kotlin
// Previous implementation in Chrome.kt:
Surface(
    modifier = modifier.then(
        if (onClick != null) {
            Modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(...)
        } else Modifier
    ),
    shape = shape,
    color = TerbacaChrome,
    shadowElevation = 4.dp
) { ... }
```
Because `.minimumInteractiveComponentSize()` was placed directly on `Surface`, `Surface`'s layout constraints forced its visual bounds to inflate from its natural wrap-content height (~36 dp) to 48 dp.
Consequently:
- Clickable pills (e.g. `ReaderNavigation` back chip, `ReaderScreen` text selection copy pill) rendered with a 48 dp visual height.
- Non-clickable pills (e.g. `ReaderScreen` page indicator pill, `SlidesScreen` slide indicator pill) rendered with a 36 dp visual height.
- The visual center was displaced by `(48 dp - 36 dp) / 2 = 6 dp`, causing a noticeable vertical jump when switching between reading and navigation states.

### Resolution
The `Surface` is now wrapped inside an interactive `Box`:
```kotlin
Box(
    modifier = modifier.then(
        if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier
    ),
    contentAlignment = Alignment.Center
) {
    Surface(
        modifier = if (onClick != null) {
            Modifier.clip(shape).clickable(...)
        } else Modifier,
        shape = shape,
        color = TerbacaChrome,
        shadowElevation = 4.dp
    ) { ... }
}
```
- **Accessibility preserved:** The outer `Box` enforces the 48 dp × 48 dp minimum touch target for TalkBack and user touch inputs (`assertTouchHeightIsEqualTo(48.dp)` passes).
- **Visual consistency restored:** The visual `Surface` maintains its natural ~36 dp pill shape across both clickable and non-clickable states, eliminating the 6 dp position shift.

---

## 2. Minified Build Smoke Test (Outline & Link Tap)

### Context & ProGuard Rules
On minified builds (`minifyEnabled = true`, `shrinkResources = true`), R8 could potentially prune or obfuscate reflection/JNI-instantiated parcels.
The following rules in `app/proguard-rules.pro` ensure stability:
```proguard
-dontobfuscate
-keepclasseswithmembers class app.terbaca.core.pdfium.PdfiumNative { native <methods>; }
-keep class app.terbaca.core.pdfium.PageLinkParcel { <init>(); <fields>; }
-keep class app.terbaca.core.pdfium.OutlineItemParcel { <init>(); <fields>; }
```

### Smoke Test Execution Procedure
1. Build and install the minified benchmark/release APK on the Pad 7:
   ```bash
   ./gradlew :app:installBenchmark
   ```
2. Push a document with bookmarks/outline:
   ```bash
   adb push core/pdfium/src/androidTest/assets/fixtures/with_outline.pdf /sdcard/Download/outline_test.pdf
   adb shell am start -a android.intent.action.VIEW -d "file:///sdcard/Download/outline_test.pdf" -t "application/pdf"
   ```
3. **Outline Verification:**
   - Tap the sidebar button on the top toolbar to reveal the docked/overlay sidebar.
   - Switch the segmented tab control to "Outline".
   - Verify outline entries ("Chapter 1: Intro", "Section 1.1: Background", "Chapter 2: Methods") are displayed with appropriate indentation.
   - Tap "Chapter 2: Methods". Verify reader smoothly scrolls to page 2 and shows the back chip pill.
4. **Link Tap & Peek Verification:**
   - Tap an in-text reference citation (e.g. `[1]`).
   - Verify the citation peek card floats over the page showing the destination preview.
   - Tap "Go there" on the peek card. Verify reader navigates to the bibliography entry, with the back chip pill appearing below the top toolbar.
   - Tap the back chip pill. Verify reader returns to the source reading position.

---

## 3. TalkBack Walkthrough (Xiaomi Pad 7)

A full accessibility pass was conducted on the Pad 7 with TalkBack enabled:

1. **Library Screen:**
   - Document cards report title, page progress, and last read time as a single stop.
   - Add/Open button reports "Open document" action.
2. **Reader Screen:**
   - Top toolbar icons: "Back to library", "Sidebar", "Search document", "View settings" announce their respective actions with 48 dp touch boundaries.
   - Page Indicator Pill: Announces "Page X of Y" with a unified click action.
   - Selection handles announce "Drag to adjust start selection" / "Drag to adjust end selection".
   - Settings popover: Radio buttons announce layout options ("Vertical Scroll", "Two Pages", "Slide Mode") and switches announce "First Page Alone", "Invert images".
3. **Slide Presentation Mode:**
   - All controls announce clear roles. Double-tap to advance or navigate via tap zones works as expected with TalkBack touch exploration.

---

## 4. Portrait Layouts on Xiaomi Pad 7

The Xiaomi Pad 7 has a 3:2 aspect ratio display (3200×2136 pixels).
Layout verification in portrait mode (2136×3200):
- **Continuous Vertical:** Document width fills the 2136 px screen cleanly, with crisp typography and center alignment.
- **Sidebar:** In portrait, the sidebar operates as a modal overlay rather than a fixed docked rail, preserving full screen width for document reading.
- **Floating Pills:** Center-aligned horizontally, positioned safely above the system gesture navigation bar and below the status bar padding.
- **Slide 2-up Stacked:** In portrait orientation, presentation decks automatically select or offer 2-up stacked layout (paging by 2 vertically), avoiding letterboxed side-by-side rendering per PRD F-MODE-4.

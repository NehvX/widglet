# Widglet

A home-screen photo widget for Android. Show photos from chosen albums or hand-picked images, change them on your schedule with transitions, and optionally cut out just the subject (on-device background removal) so it floats on your wallpaper or pops in front of a live clock.

## Download

Grab **`widglet.apk`** from the [latest release](https://github.com/NehvX/widglet/releases/latest), open it on your phone and allow "Install unknown apps" for your browser or file manager when asked. Requires Android 12 or newer.

- Package: `com.widglet.app` · minSdk 31 (Android 12) · target/compile SDK 37 (Android 17)
- Kotlin, Gradle Kotlin DSL, AGP 9.5.0, Kotlin 2.4.21, Jetpack Compose (BOM 2026.09.00) + Material 3 with dynamic colour
- WorkManager 2.12, DataStore 1.3, ML Kit Subject Segmentation 16.0.0-beta1

## Privacy and the read-only gallery guarantee

- **Widglet never modifies your gallery.** It only reads photos (query, decode, thumbnail). It never deletes, inserts, updates, renames, moves, rotates or re-saves anything in MediaStore or shared storage.
- All MediaStore reads go through one class, [`GalleryAccess`](app/src/main/java/com/widglet/app/data/GalleryAccess.kt), which exposes read methods only.
- Permissions are read-only: `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED` (Android 14+ "selected photos" access works), and `READ_EXTERNAL_STORAGE` limited to `maxSdkVersion=32` (Android 12 only, where `READ_MEDIA_IMAGES` doesn't exist yet). No write or manage permissions, and no `ACCESS_MEDIA_LOCATION`.
- Everything Widglet creates (cutouts, widget thumbnails, caches) lives in `filesDir` / `cacheDir`. "Clear cache" and cleanup only delete those private files.
- **No network.** `INTERNET` is stripped from the merged manifest (`tools:node="remove"`). There are no analytics, ads or accounts. The "stats" screen is counted on the phone and never leaves it.
- The only file Widglet writes outside its sandbox is a settings backup JSON, and only to a location you choose in the system "Save as" dialog.

### Build-time guard

- `./gradlew checkGalleryReadOnly` runs before every build (`preBuild` depends on it). It fails the build if the source contains `ContentResolver.delete/update/insert`, any `MediaStore.create*Request`, `insertImage`, write/manage storage permissions, exact-alarm permissions, `INTERNET`, public folders (DCIM/Pictures), `IS_PENDING`, writable file descriptors, or `openOutputStream` outside `SettingsBackup.kt`.
- After manifest merging, a second check fails the build if the **merged** manifest (including libraries) declares `INTERNET`, storage-write, `ACCESS_MEDIA_LOCATION` or exact-alarm permissions.
- `GalleryReadOnlyTest` repeats the source scan as a unit test.

## Build and install (Nothing Phone (3a) Pro or any Android 12+ phone)

You need JDK 17+ (21 used here) and the Android SDK with platform 37.

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests and the read-only check:

```bash
./gradlew testDebugUnitTest checkGalleryReadOnly
```

Then long-press the home screen → Widgets → Widglet, or open the app and tap **Add widget**.

## Features

**Sources**: pick MediaStore albums (name, cover, count) and/or individual photos with the system Photo Picker (no permission needed; read grants persisted). Each widget has its own settings. Photos or albums deleted elsewhere are skipped quietly. Partial ("selected photos only") access shows a banner with "Select more".

**Smart selection**: time-based albums (morning, afternoon, evening, night, weekday, weekend), "On this day", favourites only (system favourite flag, read-only), skip screenshots, skip small images, skip duplicates, date range, prefer photos with a detected subject, and hide individual photos from a widget (stored in Widglet only).

**Timing**: every 15 min / 30 min / 1 h / 3 h / 6 h / 12 h / custom (≥15 min), daily, slideshow (5 s–10 min), on unlock, on screen on, on tap, manual only. Order: sequential, shuffle, newest first, oldest first. Optional pause in Battery Saver, a global pause switch, and a battery-impact indicator.

| Mode | Mechanism | Real limits (also shown in the app) |
|---|---|---|
| Every N min / daily | WorkManager periodic work | 15 min minimum; Android may defer in Doze |
| Slideshow | `AdapterViewFlipper` + `RemoteViewsService` | Launcher flips while the home screen is visible; no app wake-ups. Some launchers pause or restart it |
| On unlock / screen on | Runtime broadcast receiver | **Best effort.** Android delivers these only to running apps, so it works while Widglet is in memory |
| On tap / manual | Click PendingIntent, QS tile, app shortcut | None |

No `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`. Exact alarms would drain battery and need a special permission, and WorkManager is good enough for photo changes. Updates after reboot and app update are re-scheduled.

**Transitions** (RemoteViews view animations; one layout per transition and speed, since animations can't be set at runtime):

| Transition | Status |
|---|---|
| None, Crossfade, Slide, Zoom | ✅ fast / medium / slow |
| Flip | ✅ as a 2D card flip (scaleX). A real 3D `rotationY` flip works only in Slideshow mode, which uses property animators |
| Ken Burns | ✅ fade in, then a 20 s slow zoom that holds until the next photo. Baked rounded corners may be cropped while zoomed |
| Custom easing, true 3D in normal mode, pan paths | ❌ not possible in RemoteViews |

Some launchers skip widget animations. The photo still changes.

**Cutouts**: ML Kit Subject Segmentation runs on-device and stores transparent WebP files in private storage. Generation runs in a WorkManager job with progress, cancel, and optional charging-only / Wi-Fi-only constraints. **Lazy mode** prepares only the next N photos. The current photo's cutout is made just in time if missing. If no subject is found (under 1.5% of the image), Widglet shows the full photo or skips it (your choice) and remembers that. Backgrounds: transparent, solid, gradient, blurred photo, or original photo (the depth-clock look). Includes adjustable drop shadow, sticker outline, and a manual touch-up brush (erase / restore), saved privately.

**Storage**: real usage (cutouts / cache / settings), free space, a per-widget bar chart, cleanup of unused cutouts, and Clear cache. The estimate makes 3 real sample cutouts, encodes each at every quality in memory, and extrapolates (for example "About 148 MB for 212 photos at High quality"). Changing quality updates the estimate instantly. If the estimate is over 200 MB or more than half the free space, it offers the next lower quality. Quality caps the long side at Low 512 / Medium 800 / High 1080 / Max 1600 px, since widgets can't display more.

**Look**: resizable (`horizontal|vertical`, 2×2 default, min 1×1, `targetCell*`, `maxResize*`), rendered at the widget's real size, so 2×2, 4×2, 4×4 and odd launcher sizes all work. Layouts: single, photo stack (polaroid pile), collage (2–4, adapts to aspect). Shapes: rounded rectangle (radius), circle, squircle, heart, flower, polaroid, film strip. Also padding, fit (smart fill / fit / crop to centre), filter (none / monochrome / dot matrix), background colour and opacity, border, date and album overlay (normal or dot-matrix lettering), and a live `TextClock` (position, monospace, date, colour). The live preview in the editor uses the same renderer as the widget. The widget picker has a `previewLayout`. The widget is reconfigurable, and multiple instances are independent.

**Interaction**: tap = next photo / open in gallery viewer / open Widglet / nothing. Double-tap = previous photo or pause all (single tap then waits about 0.4 s). Long-press can't be customised because launchers use it to move widgets. There are Quick Settings tiles (Next photo, Pause Widglet) and app shortcuts (Add widget, Next photo).

**Presets and backup**: built-in styles (Nothing-inspired, Sticker, Depth clock, Polaroid pile), save your own styles, share a style as text and import it by pasting, copy all settings from another widget, duplicate a widget from Home, and export/import a widget's settings as JSON through the system file picker.

**Languages**: English and Hindi, with an in-app language switcher (Android 13+ per-app languages; Android 12 follows the system).

**Lock screen**: `widgetCategory` includes `keyguard`. Phones running Android 5–15 don't show widgets on the lock screen, and newer Android versions only do on some devices (tablets / hub mode). Where the OS allows it, it works.

**Nothing OS**: nothing Nothing-specific is used, but the **Nothing-inspired** preset (dot-matrix photo filter, dot-matrix lettering, monospace clock, red accent dot) looks at home on a Nothing Phone.

## Design notes and known limitations

- **RemoteViews instead of Glance for the widget.** Transitions need `ViewFlipper` / `AdapterViewFlipper` with in/out animations, which Glance can't express. Mixing Glance with an embedded `AndroidRemoteViews` would add a second rendering path for no gain, so the widget uses plain RemoteViews throughout. The in-app UI is Compose.
- Everything except the live clock is baked into a bitmap (shapes, frames, borders, text, shadows). That way it looks identical on every launcher, including ones that ignore `clipToOutline` or rounded-corner options. Bitmap sizes are computed against the RemoteViews memory budget (screen × 1.5 × 4 bytes, using under half of it) and capped at 1200 px.
- ML Kit Subject Segmentation is a Google Play services module. Google Play services downloads the model (Widglet itself has no network access). Until it arrives, cutout jobs retry automatically and the UI says so. On devices without Play services, cutout mode isn't available and widgets show full photos. MediaPipe was not needed, so it isn't bundled.
- "On unlock" / "On screen on" are best effort (see the table above). Use "On tap" or an interval if you need it to be reliable.
- In Slideshow mode the order restarts when the launcher redraws the widget, and per-photo stats aren't counted.
- Duplicate detection is metadata-based (same file size and dimensions), not pixel-based.
- Ken Burns and Zoom animations can crop baked rounded corners mid-animation.

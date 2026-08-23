# Auto Edit

<div align="center"><img src="logo.png" width="180" alt="Auto Edit logo"/></div>

**IMAGE → AUTOMATIC 3-SECOND CLIPS → FORMULA → MOTION → TRANSITIONS → VOICE/MUSIC → VIDEO EXPORT**

Auto Edit is a simple, fast, premium-looking **image-to-video editor for Android**.
Select images, pick a Formula, and every image automatically becomes a cinematic
moving clip. Everything runs **100% on-device, fully offline** — no backend, no
login, no cloud, no API keys. Your media never leaves your device.

## The core rule

| Images | Duration (3 s/clip) |
|-------:|--------------------:|
| 10     | 00:30               |
| 100    | 05:00               |
| 200    | 10:00               |
| 500    | 25:00               |

Transitions play *inside* each clip, so the total duration is always exactly
`images × seconds-per-clip`.

## Formula 01 — Random Cinematic (default)

Every image gets a slightly different, safe, cinematic move:

- Motion: random from Zoom In / Zoom Out / Pan L-R-U-D / Zoom+Pan / Ken Burns
- Zoom: stays in the **100% → 108%** safe range
- Motion: smooth **ease-in-out** interpolation, never abrupt
- Anti-repetition: the same effect never plays on two consecutive clips
- **RANDOMIZE AGAIN**: new motion sequence, same images

Built-in formulas (easy to extend — one data object each):

| ID   | Name              | Character                                             |
|------|-------------------|-------------------------------------------------------|
| F01  | Random Cinematic  | default — 3 s, random 100–108% motion, cross dissolve |
| F02  | Slow Documentary  | 4 s, gentle zooms, soft fades                         |
| F03  | Smooth Zoom       | fixed 100 → 110% zoom on every image                  |
| F04  | Dynamic Motion    | 2.5 s cuts, up to 112% zoom, flash transitions        |

## Features

- **Multi-image import** via the Android system Photo Picker (API 33+, max clamped to the device limit) with a Storage-Access-Fallback for API 26-32 (persistent URI grants), reorder, delete, re-add — zero permissions
- **Timeline** with thumbnails, image numbers, motion indicators, transition markers
- **Preview player** with play / pause / restart / fullscreen — mirrors the export exactly
- **10 transitions**: None, Fade, Cross Dissolve, Slide L/R/U/D, Zoom, Blur, Flash (0.45 s default, configurable)
- **Image look**: brightness, contrast, saturation, vignette, blur (kept simple on purpose)
- **Voice**: add from device, volume, start position, fade in/out, remove
- **Fit images to voice**: optional mode that stretches clip length to match voice duration
- **Music**: add from device, volume, loop, fade in/out, remove, **Duck music** (music lowers while voice plays)
- **Export**: 720p / 1080p (default) / 4K, 24/30/60 fps, 16:9 / 9:16 / 1:1, progress + safe cancel, saves to `Movies/Auto Edit/`
- **Projects** saved locally (private app storage, zero permissions on Android 10+)
- **Large-project safe**: two-pass downsampling + 3-slot bitmap LRU — 500 huge images won't OOM the app

## Tech

Kotlin · Jetpack Compose (Material 3, dark + gold) · Coroutines/Flow ·
MediaCodec H.264 + AAC · MediaMuxer · system photo picker · no network, no analytics

- Rendering: software canvas → RGB→YUV420 → MediaCodec (H.264), remuxed with AAC audio
- Projects: compact JSON documents (zero-dependency, JVM unit-testable)

## Build

```bash
./gradlew test              # unit tests
./gradlew assembleDebug     # debug APK (signed, installable)
./gradlew assembleRelease   # release APK (unsigned)
```

GitHub Actions builds every push to `main` and uploads the **Auto-Edit-APK**
artifact (debug + release).

Install: `adb install app/build/outputs/apk/debug/app-debug.apk`
(or download the APK artifact and sideload it).

## Tests covered

duration math (1/10/100/200/500 images), fit-to-voice, formula application,
random motion generation + anti-repetition, zoom 100→108 / 108→100, easing,
transition windows, keyframe interpolation, project save/load round-trip,
audio mixing (volume, offset, loop, fades, ducking, clipping, mono/resample),
export configurations, repository CRUD, edge cases (0 images, duplicates,
malformed files).

## Privacy

No permissions on Android 10+ (system Photo Picker / Storage Access Framework /
scoped storage). On Android 8-9, only `WRITE_EXTERNAL_STORAGE` is requested to
save the exported video. No analytics, no tracking, no network calls.
`allowBackup=false`.

### Import safety

- Picker max is clamped to `MediaStore.getPickImagesMaxLimit()` (avoids the
  Jetpack `PickMultipleVisualMedia` crash)
- URIs are validated; MIME-filtered; corrupted files are reported with a
  friendly message instead of crashing
- Persistent read grants are taken where the picker supports them, so
  projects stay valid after restart

# Auto Edit — Device Self-Test (export pipeline)

The new export engine (GPU → EGL → MediaCodec input surface → H.264 → MP4)
must be verified on a real device. No emulator is available in the build
sandbox (no KVM), so this self-test runs the mandatory 2-image / 6-second
export entirely on your phone, with machine-readable evidence.

## 1. Install the APK

```
adb install -r Auto-Edit.apk
```

(Any file manager / side-load also works. Debug-signed build.)

## 2. Run the mandatory 2-image test

```
adb shell am start -n com.autoedit.app/.MainActivity --es selftest "2"
```

The app generates 2 test images (numbered gradients), builds a project with
deterministic Formula-01 motion (image 1: 100% → 108%, image 2: 108% → 100%),
cross-dissolve transitions, exports **1080p / 30 fps / no audio**, then
**verifies the MP4**:

- file exists, non-zero size
- duration ≈ 6.0 s (±0.75 s)
- resolution 1920×1080
- first frame decodes
- frame at t=1 s vs frame at t=5 s are **distinct** (both images present,
  different zoom)

Results appear as a toast, and a full report is written next to the video:

```
files/projects/selftest-<ts>/export/
  Auto Edit - SelfTest-2 - <stamp>.mp4     <- the exported video
  selftest-report.json                     <- machine-readable report
  selftest-frame1-1s.png                   <- evidence frame (image 1, mid zoom-in)
  selftest-frame2.png                      <- evidence frame (image 2)
  selftest-report.json
```

Logcat (every step is logged):

```
adb logcat -s AutoEditSelfTest AutoEditExport
```

Expected final log lines:

```
self-test PASSED in ~XXXXms
video: /storage/emulated/0/Android/data/com.autoedit.app/files/projects/selftest-<ts>/export/Auto Edit - SelfTest-2 - ....mp4 (xxx KB)
```

## 3. Larger tests

```
adb shell am start -n com.autoedit.app/.MainActivity --es selftest "10"    # 30 s
adb shell am start -n com.autoedit.app/.MainActivity --es selftest "100"   # 5 min
adb shell am start -n com.autoedit.app/.MainActivity --es selftest "200"   # 10 min
```

Watch memory while the 100/200-image runs execute — RSS should stay flat
(one clip decoded at a time; textures are LRU-evicted and recycled).

## 4. Normal UI test (the user's original failure scenario)

1. Open the app → new project
2. **IMAGES** → pick exactly 2 photos (photo picker, multi-select)
3. **FORMULA** → apply FORMULA 01
4. **EXPORT** → 1080p, 30 fps → EXPORT
5. Expect: notification "Exporting video — N%" with CANCEL, progress
   climbing smoothly through Rendering → Finalizing → Saving, and the video
   saved to Movies/Auto Edit.
6. Open the MP4 in the gallery: ~6 s, both images, smooth zoom motion.

## 5. Cancellation test

Start a 100-image export, press **CANCEL** in the notification (or in the
dialog). The export must stop, temp files must be cleaned, and the editor
must be usable immediately — no zombie progress, no 90% freeze.

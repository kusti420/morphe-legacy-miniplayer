# Legacy Miniplayer — a Morphe patch module

Restores the **classic YouTube 14.x rectangle miniplayer** on modern YouTube:
a 180dp video rectangle docked in the **bottom-right** corner —
**swipe up to maximize**, **swipe left/right to dismiss**.

Morphe is a ReVanced fork, so a "module" here is a **ReVanced-Patcher patch**: a Kotlin
bytecode patch (`patches/`) plus a Java runtime extension (`extensions/`) that get compiled
into `morphe-patches` and applied to a YouTube APK.

---

## Why this can't just be copied out of the old APK

The 14.21.54 miniplayer is a behavior of one obfuscated custom view,
`WatchWhileLayout` (see [`docs/DECOMPILE-ANALYSIS.md`](docs/DECOMPILE-ANALYSIS.md)).
You cannot lift that class into a new APK — it's obfuscated, wired into that build's view
hierarchy, and depends on internal APIs that change every release.

Worse, **Google deleted the legacy miniplayer code from newer YouTube**. This is stated in
Morphe's own `Miniplayer` patch:

- `Miniplayer.kt:181` — *"Parts of the YT code is removed in 20.37+ and the legacy player no longer works."*
- `Miniplayer.kt:71` — *"21.29 removed all modern miniplayers except modern 4."*

So the existing Morphe `Miniplayer` patch can only *select a type that still ships in the
target APK*. On new builds the 14.x type is gone from the binary — which is why Morphe shows
"not available" there.

**Therefore the only way to get the real 14.x miniplayer on new YouTube is to
reimplement it** — reproduce its geometry, gestures and look, driving the player view that
*does* exist in the target. That's what this module does. The only things taken from the old
APK are **resources** (drawables + 5 dimens) and the **documented behavior** — no code.

---

## Layout

```
morphe/
├── README.md
├── docs/
│   ├── DECOMPILE-ANALYSIS.md          # 14.21.54 miniplayer ground truth (WatchWhileLayout)
│   ├── DECOMPILE-ANALYSIS-helpers.md  # gesture/animation helper classes (lwp, lwn…)
│   ├── MORPHE-INTEGRATION.md          # exact morphe hooks: player view, PlayerType, settings
│   └── ARCHITECTURE.md                # how this module is wired + the two implementation routes
├── patches/                           # Kotlin ReVanced-Patcher patch (drop into morphe-patches)
│   └── src/main/kotlin/app/morphe/patches/youtube/layout/legacyminiplayer/
│       ├── LegacyMiniplayerPatch.kt
│       └── Fingerprints.kt
├── extensions/                        # Java runtime code compiled into the patched APK
│   └── youtube/src/main/
│       ├── java/app/morphe/extension/youtube/patches/legacyminiplayer/
│       │   ├── LegacyMiniplayerPatch.java          # @injection points + controller
│       │   ├── LegacyMiniplayerGeometry.java       # rects/lerp (port of n()/g())  ✅ done
│       │   └── LegacyMiniplayerGestureHandler.java # swipe state machine           ✅ done
│       └── res/
│           ├── drawable-*/miniplayer_shadow.9.png      # real 14.x assets ✅ extracted
│           ├── drawable-*/miniplayer_innerglow.9.png   # real 14.x assets ✅ extracted
│           └── values/{strings,dimens}.xml
└── reference/yt-14.21.54/             # extracted originals for reference
```

Status legend: ✅ done · 🚧 scaffolded, needs target-APK fingerprints.

---

## How it works (short version)

1. **Patch (Kotlin)** finds, in the *target* YouTube APK, (a) the player / minimized-player
   container view and (b) the app's own maximize & dismiss actions, and injects calls to our
   extension at those points. Fingerprints live in `Fingerprints.kt`.
2. **Extension (Java)** takes the hooked container and:
   - forces its geometry to the legacy rect (`LegacyMiniplayerGeometry`, 180dp bottom-right),
   - restyles it to the classic look (square corners + `miniplayer_shadow` nine-patch, modern
     overlay buttons hidden),
   - attaches `LegacyMiniplayerGestureHandler` so swipe-up maximizes and swipe-left dismisses,
     calling the hooked YT actions.
3. **Settings**: adds a *Legacy miniplayer* switch (+ width override) under Player settings.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the two possible implementation routes
(pragmatic "hijack & restyle the surviving container" vs. purist "full custom `WatchWhileLayout`
port") and the trade-offs.

---

## Build & test

This module is developed against a checkout of `morphe-patches`.

```bash
# 1. Fork/clone morphe-patches
git clone https://github.com/MorpheApp/morphe-patches
# 2. Copy this module's files into the matching paths (they already mirror the repo layout):
rsync -a patches/ morphe-patches/patches/
rsync -a extensions/ morphe-patches/extensions/
# 3. Register the patch's settings (see docs/ARCHITECTURE.md "Settings wiring")
# 4. Build the patch bundle
cd morphe-patches && ./gradlew build
# 5. Apply to a YouTube APK with the Morphe/ReVanced CLI, enabling "Legacy miniplayer"
```

**Runtime testing** needs the patched APK on a device/emulator (Waydroid works). The pure
logic (`LegacyMiniplayerGeometry`, `LegacyMiniplayerGestureHandler`) is unit-testable without
YouTube — see `docs/ARCHITECTURE.md` "Testing".

## Status — resolved for YouTube 21.04.223

The fingerprints are **real, not templates** — resolved against a decompiled 21.04.223 APK
(`docs/DECOMPILE-ANALYSIS-21.04.md`). The design turned out cleaner than expected:

- The modern miniplayer controls presenter (`mnw`) is matched by its stable layout string
  `player_overlay_modern_mini_player_controls`; we inject into its
  `onViewAttachedToWindow(View)` to get the miniplayer root.
- Maximize / dismiss need **no fragile fingerprints** — the root already contains YouTube's
  own expand (`modern_miniplayer_expand`) and close (`modern_miniplayer_close`) buttons, which
  the extension resolves at runtime and drives with `performClick()`.

| Piece | Status |
|---|---|
| Geometry (`LegacyMiniplayerGeometry`) | ✅ done + unit-tested (20/20) |
| Gesture state machine (`LegacyMiniplayerGestureHandler`) | ✅ done |
| Fingerprints (21.04) | ✅ resolved & verified in smali |
| Overlay hook + maximize/dismiss | ✅ via `mnw` + `performClick()` |
| Drawables / dimens / strings | ✅ extracted & bundled |
| `Settings.java` fields | ⬜ add 4 fields (snippet in `docs/ARCHITECTURE.md`) |
| Build + on-device run | ⬜ needs your GitHub token + Android SDK (see below) |

> ⚠️ **What I could not do here:** run `./gradlew build`. Morphe's build pulls the patcher
> from its **private** GitHub Packages registry (needs a `GITHUB_TOKEN`) and the extensions
> module needs the Android SDK — neither is available in this environment. Instead, every
> morphe API symbol the patch uses was statically validated against a clone of
> `morphe-patches`, and the pure geometry core was compiled and unit-tested. The two remaining
> steps (add the `Settings.java` fields, then build with your token) are yours to run.

> One on-device tuning note: the extension docks the miniplayer using full-screen metrics; if
> the overlay container isn't full-bleed on your device the offset may need a tweak — details
> in `docs/DECOMPILE-ANALYSIS-21.04.md` ("caveats").

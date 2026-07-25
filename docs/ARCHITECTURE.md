# Architecture

## The problem restated

The classic 14.x miniplayer is a behavior of `WatchWhileLayout` (see
`DECOMPILE-ANALYSIS.md`). Google removed that code from YouTube ≥ 20.37 / 21.29, so it can't
be flag-flipped back on new builds — it has to be **reimplemented**, driving the player view
that still exists in the target.

The feature decomposes into four parts. Three are version-independent (done); one is
version-specific (the two action bindings).

```
                    ┌─────────────────────────────────────────────┐
   PlayerType  ───► │ LegacyMiniplayerPatch.java (controller)      │
   .onChange        │  · engages on WATCH_WHILE_MINIMIZED          │
                    │  · applies mini rect + shadow + gestures     │
                    └───────┬───────────────────────┬─────────────┘
                            │                        │
        ┌───────────────────▼──────┐   ┌─────────────▼───────────────────┐
        │ LegacyMiniplayerGeometry  │   │ LegacyMiniplayerGestureHandler  │
        │  port of n()/g()          │   │  port of onTouchEvent/lwp/xoj   │
        │  ✅ unit-tested            │   │  swipe-up→max, swipe-side→dismiss│
        └───────────────────────────┘   └──────────────────────────────────┘
```

## Two implementation routes

The user wants the *actual* rectangle miniplayer showing the *live video*. Getting the live
video into a bottom-right rectangle can be done two ways:

### Route A — "hijack & restyle" (this scaffold's default; realistic)

On the target APK there is always *some* minimized player view (even on 21.29+, "modern 4"
still renders the live video floating in a corner). We:

1. grab that player/overlay ViewGroup via `PlayerOverlayFingerprint`
   (`inset_overlay_view_layout`, the proven SponsorBlock approach);
2. when `PlayerType` becomes `WATCH_WHILE_MINIMIZED`, force its geometry to the legacy mini
   rect (`LegacyMiniplayerGeometry`: 180dp, bottom-right, 8dp pad, 16:9);
3. restyle it — square corners, draw the extracted `miniplayer_shadow` nine-patch, hide the
   modern overlay buttons;
4. attach `LegacyMiniplayerGestureHandler` for swipe-up-maximize / swipe-side-dismiss, which
   calls YouTube's own maximize/dismiss actions.

Pros: reuses YT's real video surface (no surface reparenting), so the live video "just
works". Cons: step 4 needs the two action bindings (`MaximizePlayerFingerprint` /
`DismissPlayerFingerprint`) resolved per target version. Until then gestures are detected and
logged but don't drive YT (the miniplayer still renders and repositions).

### Route B — "full custom `WatchWhileLayout` port" (purist; much harder)

Reparent the player's video view under our own `ViewGroup` that reproduces the entire
`WatchWhileLayout` state machine (full rect `A`, mini rect `h`, states 0–4, the five
animation controllers). `DECOMPILE-ANALYSIS.md` + `DECOMPILE-ANALYSIS-helpers.md` are the
complete blueprint for this. Cons: reparenting YouTube's video surface and taking over its
layout is extremely fragile and fights YT's own layout code. Only worth it if Route A's
restyle can't reach the desired fidelity on some version.

Both routes share `LegacyMiniplayerGeometry` and `LegacyMiniplayerGestureHandler` unchanged.

## What's version-specific (the only fragile part)

| Piece | Status (target 21.04.223) | Needs |
|---|---|---|
| Geometry (rects, lerp, thresholds) | ✅ done, tested | — |
| Gesture state machine | ✅ done | — |
| Settings + strings + dimens | ✅ done | add `Settings.java` fields (below) |
| Drawables | ✅ extracted & bundled | — |
| Miniplayer view handle | ✅ `mnw.onViewAttachedToWindow(View)` | — |
| **Maximize / dismiss** | ✅ `performClick()` on the real expand/close buttons | — |

The maximize/dismiss binding — the part I flagged as fragile before the modern APK arrived —
turned out **not** to need obfuscated-method fingerprints at all: `mnw` (the 21.04 modern
miniplayer controls presenter) already holds YouTube's own expand/close buttons, so we click
them. That is far more version-tolerant than hooking transition methods. See
`DECOMPILE-ANALYSIS-21.04.md`.

## Settings.java additions

Add to `extensions/youtube/src/main/java/app/morphe/extension/youtube/settings/Settings.java`
(it lives in the morphe-patches repo, not this module):

```java
public static final BooleanSetting LEGACY_MINIPLAYER_ENABLED =
        new BooleanSetting("morphe_legacy_miniplayer_enabled", FALSE, true); // reboot
public static final IntegerSetting LEGACY_MINIPLAYER_WIDTH =
        new IntegerSetting("morphe_legacy_miniplayer_width_dip", 180,
                parent(LEGACY_MINIPLAYER_ENABLED));
public static final BooleanSetting LEGACY_MINIPLAYER_SHADOW =
        new BooleanSetting("morphe_legacy_miniplayer_shadow", TRUE,
                parent(LEGACY_MINIPLAYER_ENABLED));
public static final BooleanSetting LEGACY_MINIPLAYER_HIDE_OVERLAY_BUTTONS =
        new BooleanSetting("morphe_legacy_miniplayer_hide_overlay_buttons", TRUE,
                parent(LEGACY_MINIPLAYER_ENABLED));
```

The `strings.xml` in this module goes to
`patches/src/main/resources/addresources/values/youtube/strings.xml` (keys already follow the
`_title`/`_summary` convention). `dimens.xml` values are also embedded as constants in
`LegacyMiniplayerGeometry` so the extension doesn't depend on resource resolution for them.

## Testing

- **Pure logic** (`LegacyMiniplayerGeometry`): compiled against a minimal `android.graphics.Rect`
  stub and run off-device — 20/20 assertions pass (bottom-right dock, 16:9, lerp, 90dp
  dismiss, RTL). See `reference/geomtest-notes.md` for how to reproduce.
- **Gesture handler**: validated by inspection against `DECOMPILE-ANALYSIS-helpers.md`
  (paging touch slop, 400px/s fling + 20px min displacement, 2×slop horizontal gate, half-
  travel maximize). Full runtime verification needs the patched APK on a device/emulator.
- **End to end**: build in a `morphe-patches` checkout (`./gradlew build`), patch a YouTube
  APK enabling "Legacy miniplayer", install on Waydroid/emulator, minimize a video.

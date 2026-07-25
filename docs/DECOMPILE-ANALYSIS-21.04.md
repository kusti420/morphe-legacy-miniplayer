# YouTube 21.04.223 — modern miniplayer hook analysis (the target)

Decompiled with `apktool` (resources/smali) + `jadx` (the `mnw` class). This is the version
the patch is wired for. Everything below is verified from the decompiled APK.

## Why 21.04 works as a substrate

21.04.223 is between 20.37 (legacy miniplayer removed) and 21.29 (all modern types except
Modern 4 removed). So the **modern miniplayer still ships and is the default** when you
minimize a video — a live-video floating player we can restyle and re-gesture into the
classic look/behavior. No feature-flag forcing is needed for it to exist.

## The hook class — `mnw` (modern miniplayer controls presenter)

`smali/mnw.smali` / decompiled `defpackage.mnw`. Identified stably by:
```java
public final String ge() { return "player_overlay_modern_mini_player_controls"; }
```
(This is exactly how morphe's own `MiniplayerModernViewParentFingerprint` finds it.)

Key members:
| member | role |
|---|---|
| `B()` | inflates `R.layout.modern_miniplayer_controls_overlay` → `this.w`, adds it to the `YouTubePlayerOverlaysLayout`, and calls `this.w.addOnAttachStateChangeListener(this)` |
| `fR()` | returns `this.w` — the miniplayer root view |
| `onViewAttachedToWindow(View view)` | fires when the miniplayer attaches; **`view` == the root** → our injection point |
| `r()` | lazy holder for **close** button `findViewById(R.id.modern_miniplayer_close)` |
| `s()` | lazy holder for **expand** button `findViewById(R.id.modern_miniplayer_expand)` |
| `p()` | lazy holder for the overlay **action** button `modern_miniplayer_overlay_action_button` |

The buttons carry YouTube's own click handlers (maximize / dismiss), so calling
`performClick()` on them performs the real transition — even if we set them `GONE`.

## Verified resource IDs (res/values/public.xml)

| id | value |
|---|---|
| `inset_overlay_view_layout` | 0x7f0b09ae |
| `modern_miniplayer_close` | 0x7f0b0c14 |
| `modern_miniplayer_expand` | 0x7f0b0c16 |
| `modern_miniplayer_overlay_action_button` | 0x7f0b0c18 |
| `scrim_overlay` | 0x7f0b1216 |

## The wiring (what the patch does)

1. `MiniplayerControlsClassFingerprint` matches `mnw` via the `ge()` string.
2. `MiniplayerControlsOnAttachFingerprint` (classFingerprint = above, `onViewAttachedToWindow(View)`)
   gets an injected call at index 0:
   ```
   invoke-static { p1 }, Lapp/morphe/extension/.../LegacyMiniplayerPatch;->onMiniplayerAttached(Landroid/view/View;)V
   ```
3. The extension `onMiniplayerAttached(root)`:
   - resolves close/expand buttons via `ResourceUtils.getIdentifier(ResourceType.ID, name)` +
     `root.findViewById`;
   - sizes/positions `root` into the legacy mini rect (`LegacyMiniplayerGeometry`, 180dp,
     bottom-right), draws the extracted `miniplayer_shadow`, optionally hides overlay buttons;
   - attaches `LegacyMiniplayerGestureHandler`: **swipe up → `expand.performClick()`**,
     **swipe sideways ≥90dp → `close.performClick()`**, drag-follow + settle in between.

## Honest caveats for on-device tuning

- **Positioning**: the extension sets absolute `X/Y` on the root relative to full screen
  metrics. `mnw`'s root is a child of `YouTubePlayerOverlaysLayout`; if that container isn't
  full-bleed on some device, the dock offset may need adjusting (use the container's own
  width/height instead of `DisplayMetrics`). This is the one thing to verify first on device.
- **Sizing**: forcing `LayoutParams` width/height works, but YouTube also computes miniplayer
  size from feature flags (`mnw.n(Size)`); if it fights back, pair this patch with morphe's
  Miniplayer patch "size" option, or add the `MINIPLAYER_INITIAL_SIZE_FEATURE_KEY` override
  (literal `45640023L`, see morphe miniplayer Fingerprints) to pin 180dp at the source.
- **21.29+**: only Modern 4 survives; the same hook class/string should still match, but
  re-verify `ge()` and the button ids on that version.

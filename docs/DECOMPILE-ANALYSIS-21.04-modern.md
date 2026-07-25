# YouTube 21.04.223 — modern miniplayer internals (route-B recon)

How 21.04's modern miniplayer works, and how to force it into classic 14.x behavior
(fixed 180dp bottom-right rectangle, swipe up = maximize, swipe sideways = dismiss)
**reusing the real live video** — no surface reparenting needed.

## Classes

- **`oxc`** (`smali/oxc.smali`) — feature-flag config holder. Public ctor `oxc(blti,blti)`
  contains literal `45628752`. Every field is a feature flag read via `blti.p/c/a`.
  Gate predicates: `a() = A && (b || a)` (whether the floating miniplayer receives touch
  gestures at all), `b() = A && z != 5` (modern skin used).
- **`oxb`** (`smali/oxb.smali`) — geometry engine. Computes rects: `y()` outer, `A()`
  video-minus-chin, `D()` rounded mask, `kH()` dismiss offset. `e(oxa)` computes a corner
  rect; `i()` picks the corner — **forces `oxa.d` = BOTTOM_RIGHT when `!oxc.b`**; `w()`
  computes size = `max(q, min(min(s,t)/2 - r, p))`.
- **Video view** = `NextGenWatchLayout.this.p` = `@id/watch_player` FrameLayout, laid out to
  `oxv.A()` (= `oxb.l`). The floaty controls go to `y()`, the chevron/dismiss handle to `kH()`.
  `mnw.w` (the earlier hook target) is only the button overlay — the wrong lever.
- **Gestures** = `owv` (`smali/owv.smali`), invoked from `NextGenWatchLayout.onInterceptTouchEvent/
  onTouchEvent` only when `oxc.a()`: `je`=tap→maximize (`pom.p()`), `m`=drag/reposition,
  horizontal-off-edge→dismiss (state in `oxj`, gated by `oxc.o`).

## Flag → behavior map (subset that matters)

| Flag | field | set to | effect |
|---|---|---|---|
| 45622882 MODERN_FEATURE | `A` | true | master enable |
| 45623000 MODERN_TYPE_1 | `z`→1 | true | type 1: `b()` true, **chin height 0** (clean rect) |
| 45628752 DRAG_DROP | `b` | **false** | `oxb.i()` **hard-forces BOTTOM_RIGHT**, no 4-corner drag |
| 45628823 DOUBLE_TAP | `a` | **true** | keeps `a()` true so touches still delegate (needed when b=false) |
| 45658112 HORIZONTAL_DRAG | `o` | true | swipe-sideways → dismiss (`oxj`/`owv`) |
| 45654039 | `n` | true | tap handling (`owv.jh = a && n`) → tap-to-expand |
| 45652224 ROUNDED_CORNERS | `m` | **false** | square corners |
| 45640023 INITIAL_SIZE | `e` | 180 | size floor `oxb.q = 180*density` |

Size note: `w() = max(q, min(halfScreenDim, miniplayer_max_size))`. Floor `q`=180 alone gives
~half-screen on big displays; to pin exactly 180 also cap `miniplayer_max_size` at 180dp
(and optionally the hard `192f` floor in the `oxb` ctor for pinch).

## Implementation (chosen)

morphe's own Miniplayer patch already wraps every one of these `oxc` flag reads with override
methods in `MiniplayerPatch.java` (`getModernFeatureFlagsActiveOverride`,
`getModernMiniplayerOverrideType`, `getMiniplayerDragAndDrop`, `getRoundedCorners`,
`getHorizontalDrag`, `getMiniplayerDoubleTapAction`, `getMiniplayerDefaultSize`, …). So instead
of a second, conflicting `oxc` patch, the **Legacy miniplayer** toggle drives those existing
override methods to the legacy values above. Reliable, reuses the real video, no bytecode
conflict. Swipe-up-maximize and swipe-sideways-dismiss then work by default.

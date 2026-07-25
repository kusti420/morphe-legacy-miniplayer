# YouTube 14.21.54 miniplayer — decompile analysis (ground truth)

Source APK: `com.google.android.youtube_14.21.54-1421542400 (arm64-v8a)`
Decompiled with `apktool` (resources) + `jadx` (the engine class).

This documents the **real** legacy miniplayer so it can be reimplemented faithfully
on modern YouTube (where Google removed this code — see `../README.md`).

## TL;DR — what the legacy miniplayer actually is

It is **not** a separate layout or a standalone widget. It is a behavior of one custom
`ViewGroup`:

```
com.google.android.apps.youtube.app.watch.watchwhile.WatchWhileLayout
```

`WatchWhileLayout` is the root of `res/layout/watch_while_layout.xml`. It hosts the
**player view** and the **metadata view** as direct children and, depending on its
internal state, lays the player view out either:

- **full**  → rect `A` (top of screen, 16:9), metadata below; or
- **mini**  → rect `h` (a small rectangle docked in a corner).

Dragging interpolates the player view's bounds (`B`) between `A` and `h`. That single
reparent-free geometry trick *is* the miniplayer. There is no second video surface.

## The layout hook (`res/layout/watch_while_layout.xml`)

```xml
<com.google.android.apps.youtube.app.watch.watchwhile.WatchWhileLayout
    android:id="@id/watch_while_layout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    yt:playerViewId="@id/watch_player"
    yt:metadataViewId="@id/video_metadata_layout"
    yt:tabletLayout="false"
    yt:miniPlayerWidth="@dimen/watch_while_mini_player_width"      <!-- 180dp -->
    yt:miniPlayerPadding="@dimen/watch_while_mini_player_padding"  <!-- 8dp  -->
    ... />
```

Custom styleable attrs (`lwf`): `playerViewId`, `metadataViewId`,
`metadataLandscapeTitleViewId`, `miniPlayerPadding` (default 12dp), `miniPlayerWidth`
(default 240dp), `tabletLayout`.

## Real dimensions (`res/values/dimens.xml`)

| dimen | value | meaning |
|---|---|---|
| `watch_while_mini_player_width` | **180dp** | miniplayer rectangle width (`this.q`) |
| `watch_while_mini_player_padding` | **8dp** | gap from screen edges (`this.o`) |
| `watch_while_mini_player_dismiss_drag_distance` | **90dp** | horizontal drag past this ⇒ dismiss (`this.e`) |
| `watch_while_mini_player_dismiss_animation_distance` | **120dp** | slide-off distance of the dismiss animation |
| `watch_while_mini_player_shadow_size` | **4dp** | shadow inset drawn around the mini rect (`this.O`) |

Aspect ratio constant: **1.777** (16:9). Height = `width / 1.777`.

## Drawables (copied into this module)

- `miniplayer_shadow.9.png` — nine-patch drop shadow, drawn `shadow_size` (4dp) outside the rect.
- `miniplayer_innerglow.9.png` — subtle inner glow (only when `xsr.a()` experiment on).

Both are in `res/drawable-{m,h,xh,xxh}dpi-v4/` and have been copied to
`extensions/youtube/src/main/res/drawable-*/` and `reference/yt-14.21.54/`.

## Geometry — `WatchWhileLayout.n()`

Computed whenever size/orientation changes.

- **Full rect `A`** (portrait): `left=paddingLeft, top=paddingTop, width=contentWidth,
  height=contentWidth/1.777` (i.e. a 16:9 player pinned to the top).
- **Mini rect `h`** (LTR):
  - `width  = this.q` = **180dp**
  - `height = this.q / this.r` = 180 / 1.777 ≈ **101dp**
  - `x = contentWidth - this.o - this.q`  → **right edge**, 8dp in
  - `y = (height - paddingBottom - this.o) - miniHeight` → **bottom edge**, 8dp up
  - ⇒ **bottom-right corner** (in RTL it mirrors to bottom-left: `x = this.o`).
- `this.f` = travel distance between full and mini (used to normalize drag → progress).

## Interpolated bounds — `WatchWhileLayout.g()`

`this.c` ∈ `[0, this.f]` is the expand amount; `this.d` is the horizontal dismiss offset.

- `c <= 0`            → `B = A` (full)
- `0 < c < f`         → `B` = lerp between `A` and `h`; progress `C = c/f`
- `c >= f`            → `B = h + (d, 0)`; `C = 1.0`; while dragging horizontally,
  dismiss fade `D = min(|d| / e, 1) * 0.75`.

`a(f,i,j) = i + round(clamp(f,0,1) * (j - i))` is the lerp used per-edge.

## State machine — field `this.g`

| `g` | predicate | meaning | listener cb (`lwv`) |
|---|---|---|---|
| 0 | `o()` | inactive / dismissed / no watch | `p()` |
| 1 | `p()` | **full / maximized watch** (`c=0`) | `s()` |
| 2 | `h()` | **mini** (docked, `c=f`) | `r()` |
| 3 | `q()` | fullscreen (landscape) | `t()` |
| 4 | — | transient guard | `q()` |

`e()` = `h() || p()` = "in a draggable watch state" (can be dragged between full & mini).
Note: `g(int)` sets `c = p() ? 0 : f`, so **state 1 = full, state 2 = mini** (the callback
letters intentionally don't line up with the numbers). See `DECOMPILE-ANALYSIS-helpers.md`.

Transitions are driven by animation controllers:
`F=lwn` (expand), `G=lwk` (collapse-to-mini), `H=lwj` (dismiss), `I=lwt` (restore),
`J=lwo` (open-from-thumbnail). See `DECOMPILE-ANALYSIS-helpers.md` for their internals.

## Gestures — `onInterceptTouchEvent` / `onTouchEvent`

Drag mode `this.E`: `1` = vertical (expand/collapse), `2` = horizontal (dismiss).
Disambiguation uses the gesture handler `lwp` (touch-slop `lwp.c`).

**ACTION_DOWN**: only starts a drag if the touch is inside `this.B` (the current player
rect) — i.e. you grab the miniplayer itself.

**ACTION_MOVE**: `lwp` decides vertical vs horizontal by comparing |dx| and |dy| against
the touch slop; sets `E` and calls `requestDisallowInterceptTouchEvent(true)`.

**ACTION_UP** (`onTouchEvent`):
- `E == 2` (horizontal): if `|this.d| >= this.e` (**90dp**) or a horizontal fling ⇒
  **dismiss** (`I`/`H`); else settle back to mini.
- `E == 1` (vertical): fling/threshold decides **expand to full** (`F`) vs
  **collapse to mini** (`G`). Past half travel (`c >= f/2`) settles to the nearer end.

**Swipe UP on the docked mini ⇒ expand to full. Swipe LEFT/RIGHT ⇒ dismiss.** ✔ matches
the requested behavior exactly.

## Drawing — `drawChild`

When `e()` and expand-progress `y() > 0`, after drawing the player child the shadow
(`N`) and inner-glow (`M`) are drawn around `this.B`, with alpha tracking the dismiss
fade. This gives the mini rect its floating "card" look.

## Implication for the reimplementation

Because the whole feature is "lay the player view out in a small bottom-right rect +
gesture state machine + shadow", a clean-room port only needs:

1. a handle to the **modern** player view (via a morphe fingerprint), and
2. our own `ViewGroup`/overlay that reproduces rects `A`/`h`, the `g` state machine,
   the `lwp` gesture disambiguation, and the shadow draw.

No code from the old APK is copied — only the **resources** (drawables + the five dimens)
and this documented behavior. That is what makes it version-independent.

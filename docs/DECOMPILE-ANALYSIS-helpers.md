# YouTube 14.21.54 — WatchWhileLayout helper internals (gestures & animation)

Decompiled classes: `defpackage.{lwp,lwq,lwr,lwn,lwk,lwj,lwt,lwo,lwv,lws,lwl,lwf,eix}` plus
superclass `xoj` and interpolator `eyv`. The touch-slop / drag-delta / fling logic
attributed to `lwp` actually lives in its superclass **`xoj`**; `lwp` only adds the
diagonal-angle projection.

## 1. Touch/drag handler (`lwp` extends `xoj`)

**`xoj` fields**
- `c` (int, public final) = **touch slop** = `ViewConfiguration.getScaledPagingTouchSlop()`.
- `a` = `getScaledMaximumFlingVelocity()` (velocity cap).
- `b` = **snap/fling velocity threshold**, from ctor. `lwp` calls `super(context, 400)` →
  **fling threshold = 400 px/s**.
- `d,e,f` = start-X, last-X, last-Y; `i` = start-Y; `g` = active pointer id (-1 idle);
  `h` = VelocityTracker.

**`lwp` fields**
- `a` (float) = **drag axis angle** (radians), set in `WatchWhileLayout.n()` via
  `atan2(dy, dx)` of the vector from mini-center to full-center. `0.0` when the mini sits
  (near) directly below the full player (pure vertical axis).

**Per-move deltas** (both = previous − current, i.e. movement toward origin):
- `c(MotionEvent)` → horizontal delta; `d(MotionEvent)` → vertical delta.

**`lwp.a(int h, int v)` — combine into one axis magnitude**
- If angle `a == 0` → pure vertical `v`.
- Else `round(h·cos(a) + v·sin(a))` — projection onto the mini→full diagonal.

**Drag-direction gate `xoj.b/a(MotionEvent,int)`**
- Mode bits: 1=vertical, 2=horizontal, 3=both. Returns a direction only once movement on the
  chosen axis exceeds slop `c`: `1`=up, `3`=down, `2`=left, `4`=right; `0` otherwise.
- In `onInterceptTouchEvent`, entering **horizontal** (dismiss) mode requires `|horiz| > 2·slop`.

**Fling `xoj.c(MotionEvent,int i)` → 0/1/2**
- `computeCurrentVelocity(1000, maxFling)`.
- Vertical (`i==1`): displacement `startY−curY`, vel `yVelocity`. Horizontal: `startX−curX`, `xVelocity`.
- If `|displacement| > 20 px` **and** `|velocity| > 400` → `velocity <= 0 ? 2 : 1`; else `0`.
- **0** = none, **1** = positive-velocity fling (down/right), **2** = non-positive (up/left).
  Vertically: `1`=fling-down→minimize, `2`=fling-up→expand.

## 2. Animation base + controllers

**`lwq`** (abstract base): one `ValueAnimator f`, interpolator `new eyv()`, `g` = idle flag.
`a(i, i2, i3, z)` duration helper: `clamp(|i|/i2 → 0..i3)`; ×0.75 if `z` (fling); floor 50 ms.
Interpolator `eyv` = `PathInterpolator(0.4, 0.0, 0.2, 1.0)` (API≥21, Material standard easing).

**`lwr`** (extends `lwq`): base for the four int-valued controllers. `mode 0` drives `c`
(expansion) via `e(value)`; `mode 1` drives `d` (dismiss) via `f(value)`. Base duration
`a()` = **350 ms phone / 400 ms tablet**.

| Field | Class | ctor | endState | mode | Meaning | Duration |
|---|---|---|---|---|---|---|
| **F** | `lwn` | lwr(2,0) | 2 (mini) | c | **Collapse to mini** (`c`→`f`) | 350/400, dist-scaled, ×0.75 fling |
| **G** | `lwk` | lwr(1,0) | 1 (full) | c | **Expand to full** (`c`→`0`) | same |
| **H** | `lwj` | lwr(0,1) | 0 (hidden) | d | **Dismiss** (`d`→`sign(d)·(|d|+dismissDist)`) | **250 ms / 187 ms fling** |
| **I** | `lwt` | lwr(2,1) | 2 (mini) | d | **Restore / snap-back** (`d`→`0`) | base **250 ms**, dist-scaled |
| **J** | `lwo` | lwq | →full | Rect-lerp | **Activate from source view** (source→`A`) | **300 + up to 100/200 ms** |

`lwj` dismiss distance = `R.dimen.watch_while_mini_player_dismiss_animation_distance` (**120dp**).

## 3. State machine & `lwv` listener callbacks

`WatchWhileLayout.g` state; `o()`=hidden(0), `p()`=`g==1`, `h()`=`g==2`, `q()`=`g==3`;
`e()`=`h()||p()` (interactive). From `g(int)` (`c = p() ? 0 : f`) and `g()` layout math
(`c=0`→full rect `A`, `c=f`→mini rect `h`):

| `g` | predicate | meaning | `lwv` cb on enter |
|---|---|---|---|
| 0 | `o()` | hidden / dismissed | `p()` |
| **1** | `p()` | **expanded / full watch** (`c=0`) | `s()` |
| **2** | `h()` | **minimized mini-player** (`c=f`) | `r()` |
| 3 | `q()` | fullscreen (whole bounds) | `t()` |
| 4 | — | transient guard | `q()` |

> ⚠️ Callback letters do **not** match state numbers. Continuous callbacks:
> `a(float)` = expansion fraction `C` (0 full → 1 mini); `b(float)` = dismiss fraction `D`.

## 4. Numeric constants (authoritative)

- **Touch slop**: `getScaledPagingTouchSlop()`; horizontal-dismiss entry needs `|horiz| > 2·slop`.
- **Fling**: threshold **400 px/s**, min displacement **20 px**, cap `getScaledMaximumFlingVelocity()`.
- **Interpolator**: `PathInterpolator(0.4, 0.0, 0.2, 1.0)`; scrim uses `DecelerateInterpolator` (`interp(1−y)·0.9`).
- **Durations**: expand/collapse 350/400 ms (dist-scaled, ×0.75 fling, floor 50); dismiss 250/187;
  restore 250; activate 300–400/500; aspect change ≈200 ms × ratio.
- **Aspect**: 1.777 (16:9); clamp `[0.5627462, 1.777]`.
- **Release snap** (`C()`): horizontal past `e` (90dp) → dismiss; else `c < f/2` → expand, else minimize; ±20px deadband.

## Supporting classes
- **`lwl`** — layout-dirty tracker: flag `a` + `boolean[3]` for groups (0 metadata/title, 1 scrim `v`, 2 overlay list `x`).
- **`lwf`** — styleable attrs: `playerViewId, metadataViewId, playerTopLeftViewId, metadataPanelViewId, overlayViewId, tabletLayout, miniPlayerWidth, miniPlayerPadding`.
- **`eix`** — observable viewport-size holder (default 640×360). `k()`=mini viewport, `l()`=watch viewport.
- **`lws`** — the interface `WatchWhileLayout` implements. **`lwv`** — the listener interface (§3).

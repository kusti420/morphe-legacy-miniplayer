# YouTube 21.04.223 — native miniplayer dismiss animation

Goal: trigger YouTube's own slide-off + fade dismiss animation (the only thing that animates
the SurfaceView-backed miniplayer 1:1 like 14.x) from our own swipe-left.

## The animation

- The slide+fade is a `ValueAnimator` owned by `oxb` (field `n`), applied per-frame via
  `owy.onAnimationUpdate` → `oxb.u(Rect)` → `oxk.W()` (re-lays-out/resizes the surface), and
  finalized by `owz.onAnimationEnd`.
- It is started by **`owv.p(III)V`** = `owv.p(interactionCode, targetCenterX, targetCenterY)`.
  `targetCenterX < 0` slides off the **left**, `>= 0` slides off the **right**. `interactionCode`
  is telemetry only (pass 0).
- On animation end, `owz.onAnimationEnd` → `oxj.i(offEdge)` → `oxj.j()` commits the teardown —
  **only if `oxc.o` (HORIZONTAL_DRAG) is true**. So that flag must be enabled.

## Triggering it without a touch stream — YES

`owv.p` is invoked programmatically by the accessibility handler `owt.i(View,int,Bundle)`
(an `AccessibilityDelegateCompat`) for action id **`miniplayer_docking_action` = 0x7f0b0bee**
(res/values/public.xml). So calling `view.performAccessibilityAction(miniplayer_docking_action, null)`
on the view carrying the `owt` delegate runs the native dismiss animation — no reflection, no
instance capture. (Direct alternative: capture the `owv` instance — via `ovl.g`, or
`acnl.<init>(Context,owv)` at pld.smali:228 when DRAG_DROP is on — and call
`owv.p(0, -1, centerY)`; gives explicit left/right control.)

## Video is a SurfaceView (why our own fade can't work)

Miniplayer video = `PlayerView` (`.../player/ui/PlayerView`, a ViewGroup) hosting a plain
`android.view.SurfaceView` by default (TextureView only on a flag). SurfaceView content is
composited by SurfaceFlinger and does **not** honor `View.setAlpha`, and moves poorly with
`translationX`. That's why YouTube animates via layout-rect (`oxb.u`→`oxk.W()`), and why our
hand-rolled translate/fade left the black backing behind and never faded. Use `owv.p`.

## Fallback: neuter native gestures (if driving owv.p directly)

Enable a()=true + DRAG_DROP + HORIZONTAL_DRAG, then no-op: `oxb.i()` (force BOTTOM_RIGHT),
`oxb.h()` / `owv.jb` (double-tap resize), `owv.m`/`owv.h`/`owv.n` (pinch scale), and optionally
`owv.je` (tap-maximize). Forcing the corner does NOT break horizontal dismiss (dismiss reads the
live rect `oxb.i` + `oxb.v()`, not the corner field). Not needed if we use the accessibility
action with our own gesture layer.

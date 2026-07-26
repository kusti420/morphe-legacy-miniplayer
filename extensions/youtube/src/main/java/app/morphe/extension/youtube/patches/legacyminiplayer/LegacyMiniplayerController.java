/*
 * Legacy Miniplayer for Morphe — runtime controller.
 *
 * Wires the faithful 14.x gesture handler onto the modern miniplayer and reproduces the classic
 * drag behavior on the REAL video view (watch_player):
 *   - horizontal drag: video follows the finger and fades (D = min(|d|/90dp,1)*0.75);
 *     released past 90dp -> slide the remaining 120dp off + fade to 0 -> dismiss (close button);
 *     else snap back.
 *   - vertical drag up -> maximize (expand button).
 *   - tap / double-tap / pinch -> nothing (YouTube's own gestures are disabled via oxc.a()=false).
 */

package app.morphe.extension.youtube.patches.legacyminiplayer;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class LegacyMiniplayerController {

    private static final boolean ENABLED = Settings.LEGACY_MINIPLAYER_ENABLED.get();
    private static final boolean HIDE_BUTTONS = Settings.LEGACY_MINIPLAYER_HIDE_OVERLAY_BUTTONS.get();

    // 14.21.54 dimens.
    private static final int DISMISS_DRAG_DIP = 90;   // watch_while_mini_player_dismiss_drag_distance
    private static final int DISMISS_ANIM_DIP = 120;  // watch_while_mini_player_dismiss_animation_distance

    private LegacyMiniplayerController() {}

    /**
     * Injection point — start of mnw.onViewAttachedToWindow(View) on 21.04.
     * {@code root} is the inflated modern_miniplayer_controls_overlay (buttons/scrim layer).
     */
    public static void onMiniplayerAttached(View root) {
        if (!ENABLED || root == null) return;
        try {
            final float density = root.getResources().getDisplayMetrics().density;
            final int dismissPx = Math.round(DISMISS_DRAG_DIP * density);
            final int animPx = Math.round(DISMISS_ANIM_DIP * density);

            final View expand = find(root, "modern_miniplayer_expand");
            final View close = find(root, "modern_miniplayer_close");
            final View action = find(root, "modern_miniplayer_overlay_action_button");
            final View video = findFromRoot(root, "watch_player"); // the real video view

            if (HIDE_BUTTONS) {
                // Hide by translating off-screen (NOT detach/GONE): YouTube manages visibility,
                // not translation, so they stay hidden, remain attached, and performClick() still
                // fires (a detached button's click does nothing — that broke dismiss).
                offscreen(expand);
                offscreen(close);
                offscreen(action);
            }

            // Hide only the FLOATY (miniplayer) seekbar: 14.x had no seekbar on the mini, and it
            // hangs ~48px BELOW the video so it pokes out. Do NOT touch watch_while_time_bar_view —
            // that's the MAXIMIZED watch page's seekbar and hiding it removed it when maximized.
            offscreen(findFromRoot(root, "floaty_bar_time_bar_view"));

            // MODERN_3 shows a bottom control row (rewind / play-pause / forward) overlaying the
            // video — hide it for the clean 14.x video-only look. Re-hide after layout in case YT
            // re-shows it on tap/attach.
            offscreen(findFromRoot(root, "controls_layout"));
            root.postDelayed(() -> offscreen(findFromRoot(root, "controls_layout")), 500);
            root.postDelayed(() -> offscreen(findFromRoot(root, "controls_layout")), 1500);

            // Kill YouTube's dark edge scrim + black backing on the miniplayer: the scan showed
            // GradientDrawable backgrounds (side-darkening scrims) on watch_player/floaty_bar_controls_view
            // and a black ColorDrawable backing — the dark borders vs clear center that look off.
            final View miniRoot = root.getRootView();
            neutralizeScrims(miniRoot);
            root.postDelayed(() -> neutralizeScrims(miniRoot), 800);
            root.postDelayed(() -> neutralizeScrims(miniRoot), 2000);

            // Glass frame (14.x miniplayer_innerglow look): a match_parent child View with a soft
            // sheen background, added on top of the miniplayer. A child View draws reliably over
            // the SurfaceView and grows with the (initially 0x0) controls overlay.
            // Faithful 14.x glass (1dp white rim + soft drop shadow), rendered in a TOP-LEVEL
            // overlay above the video SurfaceView and tracked to the miniplayer's on-screen rect
            // (a child of the controls overlay renders *under* the SurfaceView and won't show).
            LegacyMiniplayerDismissOverlay.installGlass(
                    video != null ? video : root, density, 5f * density);

            final android.util.DisplayMetrics dm = root.getResources().getDisplayMetrics();
            final Rect fullRect = new Rect(0, 0, dm.widthPixels, Math.round(dm.widthPixels / 1.777f));
            final Rect[] docked = new Rect[1];

            root.setOnTouchListener(new LegacyMiniplayerGestureHandler(root,
                    new LegacyMiniplayerGestureHandler.Callback() {
                        @Override public void onDown() {
                            docked[0] = LegacyMiniplayerNative.getDockedRect();
                            // Snapshot the video now so a fade-able bitmap is ready if this becomes a
                            // dismiss (the real MODERN_4 surface can't be alpha-faded directly).
                            LegacyMiniplayerDismissOverlay.prepare(root);
                        }

                        @Override public void onHorizontalDrag(int dx) {
                            if (!LegacyMiniplayerDismissOverlay.isFollowing()) {
                                LegacyMiniplayerDismissOverlay.beginFollow();
                            }
                            LegacyMiniplayerDismissOverlay.follow(dx, dismissPx);
                        }

                        @Override public void onVerticalDrag(int dy) {
                            // Grow the REAL player (SurfaceView resizes via oxb.u) as the finger
                            // drags up — video keeps playing and it grows, like 14.x.
                            Rect d = docked[0];
                            if (d == null) return;
                            // The grow rect legitimately reaches the top edge (toward fullscreen);
                            // suppress the on-screen clamp so it can't fight the last frames of it.
                            LegacyMiniplayerDismissOverlay.setClampSuppressed(true);
                            float travel = d.top - fullRect.top;
                            if (travel <= 0) return;
                            // Up-only: clamp to [0,1] so dragging back DOWN can't push the
                            // miniplayer below its docked position (mirror of the left-only dismiss).
                            float p = Math.max(0f, Math.min(-dy / travel, 1f));
                            LegacyMiniplayerNative.moveTo(lerp(d, fullRect, p));
                        }

                        @Override public void onDismiss(int dx, boolean fling) {
                            // dismiss() slides the real video off, closes, THEN removes the glass.
                            LegacyMiniplayerDismissOverlay.dismiss(dx, fling, () -> trigger(close));
                        }

                        @Override public void onSettle(boolean wasVertical) {
                            if (wasVertical) {
                                if (docked[0] != null) LegacyMiniplayerNative.moveTo(docked[0]);
                                // Re-arm the clamp: if that docked rect is itself off-screen, the
                                // next frame pulls it back on-screen.
                                LegacyMiniplayerDismissOverlay.setClampSuppressed(false);
                            } else {
                                LegacyMiniplayerDismissOverlay.settle();
                            }
                        }

                        @Override public void onMaximize() {
                            LegacyMiniplayerDismissOverlay.removeGlass();
                            if (!LegacyMiniplayerNative.maximize()) trigger(expand);
                        }
                    }));

            Logger.printDebug(() -> "Legacy miniplayer attached; video=" + video
                    + " expand=" + expand + " close=" + close);
        } catch (Exception ex) {
            Logger.printException(() -> "onMiniplayerAttached failure", ex);
        }
    }

    /** Remove YT's dark edge-scrim gradients + opaque black backing within the miniplayer region. */
    private static void neutralizeScrims(View root) {
        try { neutralizeRec(root); } catch (Throwable ignored) {}
    }

    private static void neutralizeRec(View v) {
        if (v == null) return;
        int[] s = new int[2];
        v.getLocationOnScreen(s);
        int w = v.getWidth(), h = v.getHeight();
        if (w > 40 && h > 20 && s[0] >= 520 && s[1] >= 1780 && (s[0] + w) <= 1090 && (s[1] + h) <= 2170) {
            android.graphics.drawable.Drawable d = v.getBackground();
            if (d instanceof android.graphics.drawable.GradientDrawable) {
                v.setBackground(null); // dark edge scrim gradient
            } else if (d instanceof android.graphics.drawable.ColorDrawable) {
                int c = ((android.graphics.drawable.ColorDrawable) d).getColor();
                if (android.graphics.Color.alpha(c) > 200 && android.graphics.Color.red(c) < 24
                        && android.graphics.Color.green(c) < 24 && android.graphics.Color.blue(c) < 24) {
                    v.setBackgroundColor(0); // opaque black backing -> transparent
                }
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) neutralizeRec(g.getChildAt(i));
        }
    }

    /**
     * Fire YouTube's native miniplayer dismiss animation via the docking accessibility action
     * (id miniplayer_docking_action). owt.i() handles it by calling owv.p(), which plays the
     * real slide-off + fade and (with HORIZONTAL_DRAG on) commits the teardown.
     */
    private static boolean nativeDismiss(@Nullable View video, @Nullable View root) {
        int actionId = ResourceUtils.getIdentifier(ResourceType.ID, "miniplayer_docking_action");
        if (actionId == 0) return false;
        if (video != null && video.performAccessibilityAction(actionId, null)) return true;
        if (root != null && root.performAccessibilityAction(actionId, null)) return true;
        // The owt delegate may sit on an ancestor; walk up from the video view.
        View v = video != null ? video : root;
        while (v != null && v.getParent() instanceof View) {
            v = (View) v.getParent();
            if (v.performAccessibilityAction(actionId, null)) return true;
        }
        return false;
    }

    private static void resetVideo(@Nullable View video) {
        if (video != null) {
            video.setTranslationX(0f);
            video.setAlpha(1f);
        }
    }

    @Nullable
    private static View find(View root, String idName) {
        int id = ResourceUtils.getIdentifier(ResourceType.ID, idName);
        return id == 0 ? null : root.findViewById(id);
    }

    /** Resolve a view anywhere in the window (video view is a sibling, not under the overlay). */
    @Nullable
    private static View findFromRoot(View anyView, String idName) {
        int id = ResourceUtils.getIdentifier(ResourceType.ID, idName);
        return id == 0 ? null : anyView.getRootView().findViewById(id);
    }

    private static void setGone(@Nullable View v) {
        if (v != null) v.setVisibility(View.GONE);
    }

    /**
     * "Liquid glass" overlay drawn OVER the video: specular highlights on the left/right edges
     * (curved-glass feel), a top gloss sheen fading to a faint bottom shade (depth), and a thin
     * bright rim. Rendered in a top-level overlay so it actually shows over the SurfaceView.
     */
    private static android.graphics.drawable.Drawable glassFrame(float density) {
        return new GlassDrawable(density); // custom canvas-drawn 3D glass
    }

    private static Rect lerp(Rect a, Rect b, float p) {
        return new Rect(li(a.left, b.left, p), li(a.top, b.top, p),
                li(a.right, b.right, p), li(a.bottom, b.bottom, p));
    }

    private static int li(int x, int y, float p) {
        return x + Math.round((y - x) * p);
    }

    private static void offscreen(@Nullable View v) {
        if (v != null) v.setTranslationX(-100000f); // hidden but attached (performClick still works)
    }

    private static void fade(@Nullable View v) {
        if (v != null) v.setAlpha(0f); // hide a scrim/overlay while keeping it laid out
    }

    /** Trigger a button's action (performClick, or a synthesized tap for touch-listener buttons). */
    private static boolean trigger(@Nullable View v) {
        if (v == null) return false;
        if (v.performClick()) return true;
        long now = android.os.SystemClock.uptimeMillis();
        float x = Math.max(1f, v.getWidth() / 2f);
        float y = Math.max(1f, v.getHeight() / 2f);
        android.view.MotionEvent down =
                android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up =
                android.view.MotionEvent.obtain(now, now + 40, android.view.MotionEvent.ACTION_UP, x, y, 0);
        boolean handled = v.dispatchTouchEvent(down) | v.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        return handled;
    }
}

/*
 * Legacy Miniplayer for Morphe — snapshot dismiss overlay.
 *
 * Reproduces the exact 14.x swipe-to-dismiss (miniplayer follows the finger + fades, then
 * commits past ~90dp or snaps back) WITHOUT touching the SurfaceView (which can't be
 * translated/faded). On drag start we PixelCopy the miniplayer's pixels into a Bitmap, show it
 * in an ImageView (a normal, fade-able view), hide the real miniplayer, and animate the bitmap.
 * The video is frozen for the ~200ms of the swipe — imperceptible for a dismiss.
 */

package app.morphe.extension.youtube.patches.legacyminiplayer;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.shared.PlayerType;

public final class LegacyMiniplayerDismissOverlay {

    @Nullable private static Bitmap snapshot;
    @Nullable private static ImageView overlay;
    @Nullable private static ViewGroup contentRoot;
    @Nullable private static Rect dockedRect;     // YouTube-space rect to restore on snap-back
    private static int overlayX, overlayY, overlayW, overlayH;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LegacyMiniplayerDismissOverlay() {}

    /** onDown — snapshot the miniplayer VIDEO so a bitmap is ready if a dismiss drag begins. */
    public static void prepare(View miniplayerView) {
        try {
            snapshot = null;
            Activity activity = activityOf(miniplayerView);
            if (activity == null) return;
            contentRoot = activity.findViewById(android.R.id.content);
            // Must copy from the SurfaceView itself — PixelCopy(Window) does NOT capture the
            // video's separate compositor layer. Search WITHIN the miniplayer video view so we
            // don't grab an unrelated full-screen SurfaceView elsewhere in the window.
            SurfaceView sv = findSurfaceView(miniplayerView);
            if (sv == null) sv = findSurfaceView(miniplayerView.getRootView());
            if (sv == null || contentRoot == null) return;
            overlayW = sv.getWidth();
            overlayH = sv.getHeight();
            if (overlayW <= 0 || overlayH <= 0) return;

            int[] onScreen = new int[2];
            sv.getLocationOnScreen(onScreen);
            int[] crScreen = new int[2];
            contentRoot.getLocationOnScreen(crScreen);
            overlayX = onScreen[0] - crScreen[0];
            overlayY = onScreen[1] - crScreen[1];
            final SurfaceView fsv = sv;
            Logger.printDebug(() -> "Legacy snapshot sv=" + fsv.getClass().getSimpleName()
                    + " size=" + overlayW + "x" + overlayH
                    + " screen=[" + onScreen[0] + "," + onScreen[1] + "]"
                    + " overlayXY=[" + overlayX + "," + overlayY + "]");

            Bitmap bmp = Bitmap.createBitmap(overlayW, overlayH, Bitmap.Config.ARGB_8888);
            PixelCopy.request(sv, bmp, result -> {
                if (result == PixelCopy.SUCCESS) {
                    snapshot = bmp;
                    if (overlay != null) overlay.setImageBitmap(bmp); // fill in if drag already began
                } else {
                    Logger.printException(() -> "Legacy dismiss: PixelCopy(SurfaceView) failed " + result);
                }
            }, MAIN);
        } catch (Throwable t) {
            Logger.printException(() -> "Legacy dismiss prepare failure", t);
        }
    }

    // ---- glass overlay (1dp white rim + drop shadow, tracked over the video) ----------------

    @Nullable private static View glassView;
    @Nullable private static View glassTracked;
    @Nullable private static SurfaceView glassRoundedSurface; // the video surface (dismiss slide target)
    @Nullable private static ViewGroup glassContentRoot;
    @Nullable private static View.OnLayoutChangeListener glassListener;
    @Nullable private static View.OnAttachStateChangeListener glassAttachListener;
    @Nullable private static android.view.ViewTreeObserver.OnPreDrawListener glassPreDraw;

    /**
     * Install a top-level glass overlay (rendered above the video SurfaceView) tracked to the
     * miniplayer container's on-screen rect. Shows only while miniplayer-sized; removed on detach.
     */
    public static void installGlass(View miniplayerView, float density, float elevationPx) {
        try {
            Activity activity = activityOf(miniplayerView);
            if (activity == null) return;
            final ViewGroup cr = activity.findViewById(android.R.id.content);
            if (cr == null) return;
            removeGlass(); // fresh install per attach

            // Glass sheet + self-drawn floating drop shadow (see GlassBezelView).
            final boolean rounded = app.morphe.extension.youtube.settings.Settings
                    .LEGACY_MINIPLAYER_ROUNDED_CORNERS.get();
            final boolean glassOn = app.morphe.extension.youtube.settings.Settings
                    .LEGACY_MINIPLAYER_GLASS.get();
            GlassBezelView v = new GlassBezelView(cr.getContext(), density, rounded, glassOn);
            SurfaceView sv = findSurfaceView(miniplayerView);
            if (sv == null) sv = findSurfaceView(miniplayerView.getRootView());
            v.setSource(sv);
            // NO view elevation — an elevated overlay casts its shadow DOWN onto the video, darkening
            // the video edges (the "dark border" bug). The shadow is drawn inside the view instead.
            v.setElevation(0f);
            v.setClickable(false);
            v.setFocusable(false);
            v.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
            cr.addView(v);
            glassView = v;
            glassRoundedSurface = sv; // dismiss slide target; video corners are rounded by YT natively
                                      // (MODERN_4 + miniplayer_corner_radius overridden to 12dp).

            // Track the SurfaceView's on-screen rect so the glass sits exactly over the video.
            final View tracked = sv != null ? sv : miniplayerView;
            glassTracked = tracked;
            glassContentRoot = cr;
            glassListener = (vv, l, t, r, b, ol, ot, or, ob) -> positionGlass(tracked, cr);
            tracked.addOnLayoutChangeListener(glassListener);
            glassAttachListener = new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {}
                @Override public void onViewDetachedFromWindow(View view) { removeGlass(); }
            };
            tracked.addOnAttachStateChangeListener(glassAttachListener);
            // Reposition every frame: scrolling the feed moves the docked miniplayer WITHOUT a
            // layout pass, so a layout listener alone lets the glass drift off the video.
            glassPreDraw = () -> { positionGlass(tracked, cr); return true; };
            tracked.getViewTreeObserver().addOnPreDrawListener(glassPreDraw);
            cr.post(() -> positionGlass(tracked, cr));
        } catch (Throwable t) {
            Logger.printException(() -> "Legacy glass install failure", t);
        }
    }

    private static void positionGlass(View tracked, ViewGroup cr) {
        if (glassView == null) return;

        // Authoritative visibility gate: the glass belongs ONLY to the docked (minimized) miniplayer.
        // Geometry heuristics (surface bounds) go STALE when the app is backgrounded with a mini and
        // resumed straight into a maximized player — that left the glass showing at the old docked
        // spot over a maximized video. YouTube's own PlayerType flips on every real state change, so
        // it never goes stale. Show glass only while genuinely minimized; hide for maximized /
        // fullscreen / sliding / none / PiP / inline.
        if (PlayerType.getCurrent() != PlayerType.WATCH_WHILE_MINIMIZED) {
            glassView.setVisibility(View.GONE);
            return;
        }

        // The app content area in screen-space (excludes the status bar AND system nav bar, so the
        // clamp below stays correct even on pages where YouTube's bottom bar is hidden — that was
        // exactly when the docked miniplayer was landing off-screen).
        int[] c = new int[2];
        cr.getLocationOnScreen(c);
        final int cw = cr.getWidth();

        // Follow the video's ACTUAL box (oxb.i, screen-space) so the glass can never desync from a
        // grown/moved miniplayer — the SurfaceView's view bounds don't reflect oxb.u moves, which is
        // what left the frame smaller/offset after an over-drag. Fall back to the view bounds until
        // the native bridge is ready.
        int w, h, screenX, screenY;
        Rect live = LegacyMiniplayerNative.getLiveRect();
        if (live != null && live.width() > 0 && live.height() > 0) {
            w = live.width(); h = live.height(); screenX = live.left; screenY = live.top;
        } else {
            w = tracked.getWidth(); h = tracked.getHeight();
            int[] s = new int[2];
            tracked.getLocationOnScreen(s);
            screenX = s[0]; screenY = s[1];
        }
        // Decide MAXIMIZED from the SurfaceView's view bounds, which reliably go full-size when the
        // player maximizes (or the surface is reused for a new full video). oxb.i can go stale at
        // the docked size after maximize, so relying on it alone left the glass orphaned over the
        // feed. Position still comes from oxb.i (accurate during mini grow/move).
        final float full = cw * 0.98f;
        if (w <= 0 || h <= 0 || !tracked.isShown() || w > full || tracked.getWidth() > full) {
            glassView.setVisibility(View.GONE); // hide when maximized / full / new fullscreen video
            return;
        }

        // HARD GUARANTEE: the docked miniplayer can NEVER sit off-screen. Clamp its on-screen box to
        // the window's VISIBLE display frame (screen space minus the status/nav bars — correct even
        // when YouTube's own bottom bar is hidden, which is exactly when the mini was landing
        // off-screen). If it was out of bounds, move the REAL video back with it (moveTo) so the
        // video and glass stay locked to the same box. Skipped during any intended off-screen motion
        // (drag/dismiss/snap-back) via clampSuppressed. Converges in one frame — once in-bounds it is
        // a no-op — so it never fights YouTube unless YT actually placed the mini off-screen.
        if (live != null && !clampSuppressed) {
            Rect vis = new Rect();
            tracked.getRootView().getWindowVisibleDisplayFrame(vis);
            if (vis.width() >= w && vis.height() >= h) {
                int nx = screenX, ny = screenY;
                if (nx + w > vis.right) nx = vis.right - w;
                if (ny + h > vis.bottom) ny = vis.bottom - h;
                if (nx < vis.left) nx = vis.left;
                if (ny < vis.top) ny = vis.top;
                if (nx != screenX || ny != screenY) {
                    LegacyMiniplayerNative.moveTo(new Rect(nx, ny, nx + w, ny + h));
                    screenX = nx; screenY = ny;
                }
            }
        }

        // The glass view is bigger than the video by `m` on every side so its self-drawn drop
        // shadow has room to spread over the feed; it draws the video content in its inner rect.
        int m = (glassView instanceof GlassBezelView) ? ((GlassBezelView) glassView).marginPx() : 0;
        int vw = w + 2 * m, vh = h + 2 * m;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) glassView.getLayoutParams();
        if (lp.width != vw || lp.height != vh || lp.leftMargin != 0 || lp.topMargin != 0) {
            lp.width = vw;
            lp.height = vh;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            glassView.setLayoutParams(lp);
        }
        // Position via translation every frame — cheap, no layout, follows the miniplayer exactly.
        glassView.setX(screenX - c[0] - m);
        glassView.setY(screenY - c[1] - m);
        if (glassView.getVisibility() != View.VISIBLE) glassView.setVisibility(View.VISIBLE);
    }

    /** Remove the glass overlay + listeners (call on dismiss/maximize/detach). */
    public static void removeGlass() {
        try {
            if (glassTracked != null) {
                if (glassListener != null) glassTracked.removeOnLayoutChangeListener(glassListener);
                if (glassAttachListener != null) glassTracked.removeOnAttachStateChangeListener(glassAttachListener);
                if (glassPreDraw != null && glassTracked.getViewTreeObserver().isAlive()) {
                    glassTracked.getViewTreeObserver().removeOnPreDrawListener(glassPreDraw);
                }
            }
            if (glassRoundedSurface != null) {
                glassRoundedSurface.setAlpha(1f); // reset opacity (SurfaceView is recycled)
            }
            if (glassView != null && glassView.getParent() instanceof ViewGroup) {
                ((ViewGroup) glassView.getParent()).removeView(glassView);
            }
        } catch (Throwable ignored) {
        } finally {
            glassView = null;
            glassTracked = null;
            glassRoundedSurface = null;
            glassContentRoot = null;
            glassListener = null;
            glassAttachListener = null;
            glassPreDraw = null;
            liveFollowing = false;
            clampSuppressed = false; // next install starts with the on-screen clamp armed
        }
    }

    @Nullable
    private static SurfaceView findSurfaceView(View root) {
        if (root instanceof SurfaceView) return (SurfaceView) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                SurfaceView sv = findSurfaceView(g.getChildAt(i));
                if (sv != null && sv.getWidth() > 0 && sv.getHeight() > 0) return sv;
            }
        }
        return null;
    }

    private static boolean liveFollowing;
    private static int lastCx; // current horizontal offset from docked (px, <=0)

    // True for the ENTIRE duration of an intended off-screen motion — the finger drag AND its
    // trailing slide animation (dismiss slide-off, snap-back). The on-screen clamp in positionGlass
    // must not fire during these, or it would yank the video back and break the animation. This is
    // broader than liveFollowing, which dismiss()/settle() clear before their animations even start.
    private static volatile boolean clampSuppressed;

    /** True once a horizontal dismiss drag has started. */
    public static boolean isFollowing() {
        return liveFollowing;
    }

    /**
     * Suppress/re-arm the on-screen clamp. The controller calls this around the swipe-up grow, whose
     * rect legitimately reaches the top edge (fullscreen) — the clamp keeps the mini out of the
     * status/nav bars, which is wrong mid-grow. Re-armed (false) on settle so a docked position that
     * YouTube placed off-screen still gets corrected.
     */
    public static void setClampSuppressed(boolean suppressed) {
        clampSuppressed = suppressed;
    }

    /**
     * First horizontal move. We slide the REAL video via YouTube's own geometry move (oxb.u), like
     * the swipe-up grow, so the video keeps PLAYING and stays ROUNDED and — crucially — is NOT
     * clipped by its parent (translating the SurfaceView directly gets cut off at the parent edge).
     */
    public static boolean beginFollow() {
        dockedRect = LegacyMiniplayerNative.getDockedRect();
        lastCx = 0;
        liveFollowing = dockedRect != null;
        clampSuppressed = liveFollowing; // suppress the on-screen clamp for the whole drag
        return liveFollowing;
    }

    /** Follow the finger: slide the real miniplayer left (native move) + fade the glass. */
    public static void follow(int dx, int dismissPx) {
        final Rect d = dockedRect;
        if (d == null) return;
        int cx = Math.min(dx, 0); // left only: never slide right past the docked position
        lastCx = cx;
        LegacyMiniplayerNative.moveTo(new Rect(d.left + cx, d.top, d.right + cx, d.bottom));
        float fade = 1f - Math.min(Math.abs(cx) / (float) dismissPx, 1f) * 0.6f;
        if (glassRoundedSurface != null) glassRoundedSurface.setAlpha(fade); // fade the video too
        if (glassView != null) glassView.setAlpha(fade);
        // Glass position follows automatically via the pre-draw listener (miniplayer moved).
    }

    /** Animate the native miniplayer box horizontally from one offset to another. */
    private static void animateSlide(int fromCx, int toCx, long dur, @Nullable Runnable end) {
        final Rect d = dockedRect;
        if (d == null) { if (end != null) end.run(); return; }
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(fromCx, toCx);
        va.setDuration(dur);
        va.addUpdateListener(a -> {
            int cx = (int) a.getAnimatedValue();
            lastCx = cx;
            LegacyMiniplayerNative.moveTo(new Rect(d.left + cx, d.top, d.right + cx, d.bottom));
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (end != null) end.run();
            }
        });
        va.start();
    }

    // ---- swipe-up grow (maximize) ----------------------------------------------------------

    private static Rect fullCr() {
        int w = contentRoot != null ? contentRoot.getWidth() : overlayW;
        return new Rect(0, 0, w, Math.round(w / 1.777f));
    }

    /** Grow the snapshot toward full as the finger drags up (dy<0). */
    public static void growByDy(int dy) {
        if (overlay == null) return;
        Rect full = fullCr();
        float travel = overlayY - full.top;
        if (travel <= 0) travel = 1;
        float p = Math.min(Math.max(-dy, 0) / travel, 1f);
        applyRect(li(overlayX, full.left, p), li(overlayY, full.top, p),
                li(overlayW, full.width(), p), li(overlayH, full.height(), p));
    }

    private static void applyRect(int left, int top, int w, int h) {
        if (overlay == null) return;
        overlay.setScaleX((float) w / overlayW);
        overlay.setScaleY((float) h / overlayH);
        overlay.setTranslationX(left - overlayX);
        overlay.setTranslationY(top - overlayY);
        overlay.setAlpha(1f);
    }

    /** Release past threshold: finish growing to full behind which YouTube maximizes, then reveal. */
    public static void growCommit(Runnable maximize) {
        if (overlay == null) { if (maximize != null) maximize.run(); return; }
        final ImageView iv = overlay;
        overlay = null;
        Rect full = fullCr();
        if (maximize != null) maximize.run(); // real player transitions to full behind the snapshot
        iv.animate()
                .scaleX((float) full.width() / overlayW).scaleY((float) full.height() / overlayH)
                .translationX(full.left - overlayX).translationY(full.top - overlayY)
                .setDuration(280)
                .withEndAction(() -> iv.animate().alpha(0f).setDuration(140)
                        .withEndAction(() -> remove(iv)).start())
                .start();
    }

    /** Release before threshold: shrink back to docked and restore the real miniplayer. */
    public static void growSettle() {
        if (overlay == null) { restoreReal(); return; }
        final ImageView iv = overlay;
        overlay = null;
        iv.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(200)
                .withEndAction(() -> { restoreReal(); remove(iv); }).start();
    }

    private static int li(int x, int y, float p) {
        return x + Math.round((y - x) * p);
    }

    /**
     * Fade into maximized: overlay a snapshot of the (still-playing) miniplayer, trigger the real
     * maximize underneath (video keeps playing), and fade the snapshot out — so the transition
     * reads as a cross-fade rather than a grow-from-the-bottom. The real player is never hidden.
     */
    public static void fadeToMaximized(Runnable maximize) {
        try {
            if (contentRoot == null || snapshot == null || overlayW <= 0 || overlayH <= 0) {
                if (maximize != null) maximize.run();
                return;
            }
            final ImageView iv = new ImageView(contentRoot.getContext());
            iv.setImageBitmap(snapshot);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(overlayW, overlayH);
            lp.leftMargin = overlayX;
            lp.topMargin = overlayY;
            iv.setLayoutParams(lp);
            contentRoot.addView(iv);
            if (maximize != null) maximize.run();
            iv.animate().alpha(0f).setDuration(240)
                    .withEndAction(() -> { if (contentRoot != null) contentRoot.removeView(iv); })
                    .start();
        } catch (Throwable t) {
            Logger.printException(() -> "Legacy fadeToMaximized failure", t);
            if (maximize != null) maximize.run();
        }
    }

    /** Commit: slide the real miniplayer the rest of the way off (native move) + fade, then close. */
    public static void dismiss(int dx, boolean fling, Runnable closeAction) {
        liveFollowing = false;
        clampSuppressed = true; // keep the clamp OFF through the slide-off (video goes off-left on purpose)
        final Rect d = dockedRect;
        if (d == null) { if (closeAction != null) closeAction.run(); return; }
        int endCx = -(d.right + 120); // fully off the left edge
        long dur = fling ? 160 : 220;
        if (glassView != null) glassView.animate().alpha(0f).setDuration(dur).start();
        if (glassRoundedSurface != null) glassRoundedSurface.animate().alpha(0f).setDuration(dur).start();
        animateSlide(lastCx, endCx, dur, () -> {
            if (closeAction != null) closeAction.run(); // dismiss the real player
            removeGlass(); // clears clampSuppressed
        });
    }

    /** Snap back: slide the real miniplayer home + restore the glass and video opacity. */
    public static void settle() {
        liveFollowing = false;
        clampSuppressed = true; // video is off-left; keep clamp OFF until it has slid home
        animateSlide(lastCx, 0, 180, () -> clampSuppressed = false); // re-arm clamp once docked again
        if (glassView != null) glassView.animate().alpha(1f).setDuration(180).start();
        if (glassRoundedSurface != null) glassRoundedSurface.animate().alpha(1f).setDuration(180).start();
    }

    /** Tear down without animation (e.g. gesture became a maximize). */
    public static void cancel() {
        if (overlay != null) {
            remove(overlay);
            overlay = null;
        }
        restoreReal();
    }

    private static void restoreReal() {
        if (dockedRect != null) LegacyMiniplayerNative.moveTo(dockedRect);
    }

    private static void remove(ImageView iv) {
        if (contentRoot != null) contentRoot.removeView(iv);
        if (snapshot != null && !snapshot.isRecycled()) snapshot.recycle();
        snapshot = null;
    }

    private static int contentWidth() {
        return contentRoot != null ? contentRoot.getWidth() : 2000;
    }

    /** Prefer the video view for the snapshot bounds; fall back to the given view. */
    private static View pickTarget(View miniplayerView) {
        int id = ResourceUtilsId("watch_player");
        if (id != 0) {
            View v = miniplayerView.getRootView().findViewById(id);
            if (v != null && v.getWidth() > 0) return v;
        }
        return miniplayerView;
    }

    private static int ResourceUtilsId(String name) {
        return app.morphe.extension.shared.ResourceUtils.getIdentifier(
                app.morphe.extension.shared.ResourceType.ID, name);
    }

    @Nullable
    private static Activity activityOf(View v) {
        Context c = v.getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}

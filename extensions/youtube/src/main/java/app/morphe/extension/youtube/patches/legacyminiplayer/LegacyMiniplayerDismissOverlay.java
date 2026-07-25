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
    @Nullable private static SurfaceView glassRoundedSurface;
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
            GlassBezelView v = new GlassBezelView(cr.getContext(), density, rounded);
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

            // Round the actual video corners (when the toggle is on): clip the SurfaceView to a
            // rounded outline. This shows the FEED (not black) in the corners because the opaque
            // black backing is neutralized to transparent (neutralizeScrims). Clip ONLY the
            // SurfaceView — clipping the container breaks YT's maximize<->minimize transition. Reset
            // in removeGlass so a maximized video isn't rounded. glassRoundedSurface is also the
            // dismiss-slide target regardless of rounding.
            if (sv != null) {
                if (rounded) {
                    final float radiusPx = 12f * density; // matches GlassBezelView corner radius
                    sv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                        @Override public void getOutline(View view, android.graphics.Outline o) {
                            o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                        }
                    });
                    sv.setClipToOutline(true);
                }
                glassRoundedSurface = sv;
            }

            // Track the SurfaceView itself (not the taller watch_player container) so the frost
            // ring is a perfectly-registered continuation of the live video behind it.
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
        int w = tracked.getWidth(), h = tracked.getHeight();
        // Keep the glass tracking the video through the whole swipe-up GROW (it grows with the
        // video); only hide when essentially full-screen. Actual maximize removes it via removeGlass.
        if (w <= 0 || h <= 0 || w > cr.getWidth() * 0.98f || !tracked.isShown()) {
            glassView.setVisibility(View.GONE);
            return;
        }
        int[] s = new int[2];
        tracked.getLocationOnScreen(s);
        int[] c = new int[2];
        cr.getLocationOnScreen(c);
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
        glassView.setX(s[0] - c[0] - m);
        glassView.setY(s[1] - c[1] - m);
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
                glassRoundedSurface.setClipToOutline(false);
                glassRoundedSurface.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
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

    /** True once a horizontal dismiss drag has started. */
    public static boolean isFollowing() {
        return liveFollowing;
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
        final Rect d = dockedRect;
        if (d == null) { if (closeAction != null) closeAction.run(); return; }
        int endCx = -(d.right + 120); // fully off the left edge
        long dur = fling ? 160 : 220;
        if (glassView != null) glassView.animate().alpha(0f).setDuration(dur).start();
        if (glassRoundedSurface != null) glassRoundedSurface.animate().alpha(0f).setDuration(dur).start();
        animateSlide(lastCx, endCx, dur, () -> {
            if (closeAction != null) closeAction.run(); // dismiss the real player
            removeGlass();
        });
    }

    /** Snap back: slide the real miniplayer home + restore the glass and video opacity. */
    public static void settle() {
        liveFollowing = false;
        animateSlide(lastCx, 0, 180, null);
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

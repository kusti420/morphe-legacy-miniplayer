/*
 * Legacy Miniplayer for Morphe — faithful port of YouTube 14.21.54 WatchWhileLayout gestures.
 *
 * Ported from com.google.android.apps.youtube.app.watch.watchwhile.WatchWhileLayout
 * (onInterceptTouchEvent / onTouchEvent / g()) and its drag helper lwp/xoj. See
 * docs/DECOMPILE-ANALYSIS.md + docs/DECOMPILE-ANALYSIS-helpers.md.
 *
 * Behavior (exactly as 14.x defined it for a docked miniplayer):
 *   - drag mode decided by axis (WatchWhileLayout.this.E): E==1 vertical, E==2 horizontal.
 *   - horizontal drag: the player follows the finger (translation) and fades
 *     (D = min(|d|/90dp, 1) * 0.75); released past 90dp (or a horizontal fling) -> DISMISS
 *     (slide the remaining 120dp off + fade out), else snap back.
 *   - vertical drag up (fling up or past threshold) -> MAXIMIZE.
 *   - a plain tap (E==0) does NOTHING; no double-tap, no pinch/resize.
 */

package app.morphe.extension.youtube.patches.legacyminiplayer;

import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;

public final class LegacyMiniplayerGestureHandler implements View.OnTouchListener {

    public interface Callback {
        void onMaximize();
        /** Touch down on the miniplayer — snapshot state before any drag. */
        default void onDown() {}
        /** Released past the dismiss threshold. dx sign = direction; fling = quick flick. */
        void onDismiss(int dx, boolean fling);
        /** Continuous horizontal drag; dx px from the down point (WatchWhileLayout this.d). */
        default void onHorizontalDrag(int dx) {}
        /** Continuous vertical drag; dy px from the down point (dy < 0 = up). */
        default void onVerticalDrag(int dy) {}
        /** Released without crossing a threshold — animate back. wasVertical selects which mode. */
        default void onSettle(boolean wasVertical) {}
    }

    private static final int E_NONE = 0;
    private static final int E_VERTICAL = 1;
    private static final int E_HORIZONTAL = 2;

    private static final float FLING_VELOCITY = 400f;      // px/s  (lwp super(context, 400))
    private static final int FLING_MIN_DISPLACEMENT = 20;  // px    (xoj.c)

    private final Callback callback;
    private final int touchSlop;        // getScaledPagingTouchSlop() == xoj.c
    private final int maxFlingVel;
    private final int dismissDragPx;    // watch_while_mini_player_dismiss_drag_distance = 90dp
    private final int maximizeDragPx;

    private VelocityTracker velocityTracker;
    private int mode = E_NONE;
    private float downX, downY;

    public LegacyMiniplayerGestureHandler(@NonNull View target, @NonNull Callback callback) {
        this.callback = callback;
        ViewConfiguration vc = ViewConfiguration.get(target.getContext());
        // Normal touch slop (not the larger paging slop) so the swipe engages promptly.
        this.touchSlop = vc.getScaledTouchSlop();
        this.maxFlingVel = vc.getScaledMaximumFlingVelocity();
        float density = target.getResources().getDisplayMetrics().density;
        this.dismissDragPx = Math.round(90 * density);
        this.maximizeDragPx = Math.round(64 * density);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                mode = E_NONE;
                // Stop ancestors (YouTube's overscroll / fullscreen gesture system) from stealing
                // the gesture mid-swipe, exactly like WatchWhileLayout.requestDisallowInterceptTouchEvent.
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                else velocityTracker.clear();
                velocityTracker.addMovement(event);
                callback.onDown();
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker != null) velocityTracker.addMovement(event);
                final float dx = event.getRawX() - downX;
                final float dy = event.getRawY() - downY;

                if (mode == E_NONE) {
                    // 14.x: only swipe LEFT (dismiss) and swipe UP (maximize). Right/down ignored.
                    if (dx < -2 * touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        mode = E_HORIZONTAL;
                    } else if (dy < -touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                        mode = E_VERTICAL;
                    } else {
                        return true;
                    }
                }
                if (mode == E_HORIZONTAL) callback.onHorizontalDrag((int) dx);
                else callback.onVerticalDrag((int) dy);
                return true;
            }

            case MotionEvent.ACTION_UP: {
                final int decided = mode;
                float vx = 0f, vy = 0f;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000, maxFlingVel);
                    vx = velocityTracker.getXVelocity();
                    vy = velocityTracker.getYVelocity();
                }
                final float dx = event.getRawX() - downX;
                final float dy = event.getRawY() - downY;
                recycle();
                mode = E_NONE;

                if (decided == E_HORIZONTAL) {
                    boolean leftFling = -vx > FLING_VELOCITY
                            && Math.abs(vx) > Math.abs(vy) && -dx > FLING_MIN_DISPLACEMENT;
                    if (-dx >= dismissDragPx || leftFling) {
                        callback.onDismiss((int) dx, leftFling);
                    } else {
                        callback.onSettle(false);
                    }
                } else if (decided == E_VERTICAL) {
                    boolean upFling = -dy > FLING_MIN_DISPLACEMENT
                            && -vy > FLING_VELOCITY && Math.abs(vy) > Math.abs(vx);
                    if (upFling || -dy >= maximizeDragPx) {
                        callback.onMaximize();
                    } else {
                        callback.onSettle(true);
                    }
                }
                // decided == E_NONE (a tap / right / down) -> nothing, like WatchWhileLayout.
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                boolean wasVertical = mode == E_VERTICAL;
                recycle();
                mode = E_NONE;
                callback.onSettle(wasVertical);
                return true;
        }
        return false;
    }

    private void recycle() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }
}

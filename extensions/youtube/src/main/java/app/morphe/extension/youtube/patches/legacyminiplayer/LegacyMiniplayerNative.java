/*
 * Legacy Miniplayer for Morphe — minimal bridge to YouTube's miniplayer geometry engine (oxb).
 *
 * Used only to instantly move the real (SurfaceView-backed) miniplayer off-screen while our
 * PixelCopy snapshot overlay animates the dismiss, and to restore it on snap-back.
 *
 * Reflection targets pinned to 21.04.223 (see docs/DECOMPILE-ANALYSIS-21.04-dismiss.md):
 *   owv.b : oxb                 (geometry engine)
 *   oxb.i : android.graphics.Rect   (live rect)
 *   oxb.u(android.graphics.Rect)    (move box + video to a rect)
 */

package app.morphe.extension.youtube.patches.legacyminiplayer;

import android.graphics.Rect;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class LegacyMiniplayerNative {

    @Nullable private static WeakReference<Object> owvRef;
    @Nullable private static Object oxb;
    @Nullable private static Field oxbLiveRect;   // oxb.i (Rect)
    @Nullable private static Method oxbMoveTo;    // oxb.u(Rect)
    @Nullable private static Method owvMaximize;  // owv.f() -> pom.p()
    private static boolean resolved;
    // Latched once the obfuscated names are confirmed MISSING (e.g. a future YouTube build renamed
    // owv.b / oxb.i / oxb.u): without this, positionGlass's per-frame getLiveRect() would retry the
    // reflection AND log an exception every frame — steady jank + logcat spam. Reset in setOwv so a
    // freshly captured owv (possibly a compatible build) is retried.
    private static boolean resolveFailed;

    private LegacyMiniplayerNative() {}

    /** Injection point — owv constructor (fields not yet set; resolve lazily). */
    public static void setOwv(Object owv) {
        owvRef = new WeakReference<>(owv);
        resolved = false;
        resolveFailed = false;
        oxb = null;
    }

    private static boolean ensureResolved() {
        if (resolved) return true;
        if (resolveFailed) return false; // unsupported YT build — don't retry reflection every frame
        Object owv = owvRef != null ? owvRef.get() : null;
        if (owv == null) return false; // not captured yet (or oxb not set) — keep trying, not a failure
        try {
            Field bField = owv.getClass().getDeclaredField("b");
            bField.setAccessible(true);
            oxb = bField.get(owv);
            if (oxb == null) return false;
            Class<?> oxbClass = oxb.getClass();
            oxbLiveRect = oxbClass.getDeclaredField("i");
            oxbLiveRect.setAccessible(true);
            oxbMoveTo = oxbClass.getDeclaredMethod("u", Rect.class);
            oxbMoveTo.setAccessible(true);
            try {
                owvMaximize = owv.getClass().getDeclaredMethod("f"); // owv.f() -> maximize
                owvMaximize.setAccessible(true);
            } catch (NoSuchMethodException e) {
                owvMaximize = null;
            }
            resolved = true;
            Logger.printDebug(() -> "Legacy native bridge resolved oxb=" + oxb.getClass().getName());
            return true;
        } catch (Throwable t) {
            resolveFailed = true; // genuine name mismatch (owv present but a field/method is gone)
            Logger.printException(() -> "Legacy miniplayer native resolve failure (native move disabled)", t);
            return false;
        }
    }

    public static boolean isReady() {
        return ensureResolved() && oxb != null;
    }

    /** Current docked rect of the real miniplayer (screen-space, YouTube's coords). */
    @Nullable
    public static Rect getDockedRect() {
        try {
            if (!isReady()) return null;
            Rect live = (Rect) oxbLiveRect.get(oxb);
            return live == null ? null : new Rect(live);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The miniplayer's LIVE on-screen rect (oxb.i) — exactly where the video is currently drawn,
     * even mid-grow/move. Same underlying field as {@link #getDockedRect()}; named for the glass
     * tracker, which must follow the real video box (not the SurfaceView's view bounds).
     */
    @Nullable
    public static Rect getLiveRect() {
        return getDockedRect();
    }

    /** Move the real miniplayer (video + box) to a rect (used to grow, hide off-screen, restore). */
    public static void moveTo(Rect rect) {
        try {
            if (isReady()) oxbMoveTo.invoke(oxb, rect);
        } catch (Throwable t) {
            Logger.printException(() -> "LEGACY moveTo failure", t);
        }
    }

    /** Maximize the player (owv.f() -> pom.p()), used on swipe-up commit. */
    public static boolean maximize() {
        try {
            if (isReady() && owvMaximize != null) {
                owvMaximize.invoke(owvRef.get());
                return true;
            }
        } catch (Throwable t) {
            Logger.printException(() -> "LEGACY maximize failure", t);
        }
        return false;
    }
}

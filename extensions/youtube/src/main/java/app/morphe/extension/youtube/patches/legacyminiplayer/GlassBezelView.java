/*
 * Legacy Miniplayer for Morphe — glass sheet + floating drop shadow (one software-layer view).
 *
 * The view is sized to the video PLUS a margin on every side. In onDraw:
 *   1) a soft drop shadow (blurred dark rounded-rect) is drawn, then the interior is CLEARED so the
 *      shadow only survives in the margin (over the feed) and never darkens the video. This gives a
 *      floating look WITHOUT view elevation (an elevated overlay casts its shadow onto the video).
 *   2) a uniform glass light (diagonal reflection streaks + a thin bright rim) is drawn on the inner
 *      video rect, clipped to rounded corners.
 * Software layer is required for BlurMaskFilter + PorterDuff.CLEAR. The video's own corners are
 * rounded separately (SurfaceView clipToOutline) in installGlass.
 */

package app.morphe.extension.youtube.patches.legacyminiplayer;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.Nullable;

public final class GlassBezelView extends View {

    private final float density;
    private final float cr;       // corner radius (matches the rounded video)
    private final int margin;     // shadow spread margin on each side (px)
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();

    public GlassBezelView(Context ctx, float density, boolean rounded) {
        super(ctx);
        this.density = density;
        this.cr = (rounded ? 12f : 2f) * density; // matches the video's corner treatment
        this.margin = Math.round(14f * density);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        // BlurMaskFilter + PorterDuff.CLEAR need a software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** Margin baked around the video so the drop shadow has room to spread (read by positionGlass). */
    public int marginPx() { return margin; }
    public float cornerRadiusPx() { return cr; }

    /** Kept for call-site compatibility; no content sampling. */
    public void setSource(@Nullable SurfaceView sv) { }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final float m = margin;
        final RectF inner = new RectF(m, m, w - m, h - m);
        if (inner.width() <= 0 || inner.height() <= 0) return;

        // 1) Floating drop shadow — blurred dark rounded rect, slightly offset down, then clear the
        //    interior so only the outer halo (in the margin, over the feed) remains.
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(0x70000000);
        paint.setMaskFilter(new BlurMaskFilter(margin * 0.7f, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, margin * 0.18f);
        c.drawRoundRect(inner, cr, cr, paint);
        c.restore();
        paint.setMaskFilter(null);
        c.drawRoundRect(inner, cr, cr, clearPaint); // punch the video area clear

        // 2) Glass light on the video rect, clipped to rounded corners.
        c.save();
        clip.reset();
        clip.addRoundRect(inner, cr, cr, Path.Direction.CW);
        c.clipPath(clip);
        // Diagonal reflection streaks (two soft bands) — a single continuous feature, no frame.
        paint.reset();
        paint.setAntiAlias(true);
        paint.setShader(new LinearGradient(inner.left, inner.top, inner.right, inner.bottom,
                new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.24f, 0.42f, 0.60f, 0.70f, 0.84f}, Shader.TileMode.CLAMP));
        c.drawRect(inner, paint);
        paint.setShader(null);
        c.restore();

        // 3) Thin bright rim for a defined glass edge.
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(0x4DFFFFFF);
        c.drawRoundRect(new RectF(inner.left + 0.5f, inner.top + 0.5f,
                inner.right - 0.5f, inner.bottom - 0.5f), cr, cr, paint);
    }
}

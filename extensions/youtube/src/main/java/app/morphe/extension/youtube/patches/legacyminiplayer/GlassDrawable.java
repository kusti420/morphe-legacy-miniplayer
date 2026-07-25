/*
 * Legacy Miniplayer for Morphe — custom "liquid glass" drawable.
 *
 * The realism trick: soft light, not hard gradients. Uses BlurMaskFilter to render genuinely
 * soft blooms/glows (real light falloff) instead of banded gradients that read as a sticker:
 *   - a very subtle glass film (glass is faintly visible),
 *   - a SOFT edge glow running just inside all edges (the curved-edge / Samsung-edge light),
 *   - a SOFT specular bloom in the upper-left (the main reflection),
 *   - an embossed bevel (bright top-left / dark bottom-right) + a crisp thin rim.
 *
 * BlurMaskFilter needs a software layer — the host overlay View sets LAYER_TYPE_SOFTWARE.
 * Drawn over the video in a top-level overlay; pair with View elevation for the drop shadow.
 */

package app.morphe.extension.youtube.patches.legacyminiplayer;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class GlassDrawable extends Drawable {

    private final float density;
    private final float cr;

    public GlassDrawable(float density) {
        this.density = density;
        this.cr = 10f * density;
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        final RectF r = new RectF(b);
        final float w = r.width(), h = r.height();

        // 1) Faint glass film — very subtle diagonal so the panel reads as a surface, not invisible.
        Paint film = new Paint(Paint.ANTI_ALIAS_FLAG);
        film.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                0x14FFFFFF, 0x14000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, cr, cr, film);

        // 2) SOFT edge glow — a blurred bright stroke hugging the inside of every edge. This soft
        //    light along the rim is what sells "curved glass edge".
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(3f * density);
        edge.setColor(0x70FFFFFF);
        edge.setMaskFilter(new BlurMaskFilter(6f * density, BlurMaskFilter.Blur.NORMAL));
        float ei = 3f * density;
        c.drawRoundRect(new RectF(r.left + ei, r.top + ei, r.right - ei, r.bottom - ei), cr, cr, edge);

        // 3) SOFT specular bloom in the upper-left (the main reflection), blurred.
        Paint bloom = new Paint(Paint.ANTI_ALIAS_FLAG);
        bloom.setColor(0x8CFFFFFF);
        bloom.setMaskFilter(new BlurMaskFilter(Math.max(w, h) * 0.22f, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.clipRect(r);
        c.drawCircle(r.left + w * 0.24f, r.top + h * 0.12f, Math.min(w, h) * 0.16f, bloom);
        // a smaller secondary glint further in
        bloom.setMaskFilter(new BlurMaskFilter(Math.max(w, h) * 0.10f, BlurMaskFilter.Blur.NORMAL));
        bloom.setColor(0x66FFFFFF);
        c.drawCircle(r.left + w * 0.42f, r.top + h * 0.30f, Math.min(w, h) * 0.06f, bloom);
        c.restore();

        // 4) Embossed bevel — bright rim toward top-left, dark rim toward bottom-right.
        final float sw = Math.max(1.5f, 2f * density);
        final float off = sw * 0.7f;
        final RectF ir = new RectF(r.left + sw, r.top + sw, r.right - sw, r.bottom - sw);
        Paint bevel = new Paint(Paint.ANTI_ALIAS_FLAG);
        bevel.setStyle(Paint.Style.STROKE);
        bevel.setStrokeWidth(sw);
        bevel.setColor(0x99FFFFFF);
        c.save(); c.translate(-off, -off); c.drawRoundRect(ir, cr, cr, bevel); c.restore();
        bevel.setColor(0x59000000);
        c.save(); c.translate(off, off); c.drawRoundRect(ir, cr, cr, bevel); c.restore();

        // 5) Crisp thin rim to seat the glass surface edge.
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, density));
        rim.setColor(0x4DFFFFFF);
        c.drawRoundRect(r, cr, cr, rim);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

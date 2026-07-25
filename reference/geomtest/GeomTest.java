import android.graphics.Rect;
import app.morphe.extension.youtube.patches.legacyminiplayer.LegacyMiniplayerGeometry;

public class GeomTest {
    static int failures = 0;

    static void check(String name, boolean cond, Object got) {
        if (cond) { System.out.println("  PASS  " + name); }
        else { System.out.println("  FAIL  " + name + "  (got " + got + ")"); failures++; }
    }

    public static void main(String[] args) {
        // Pixel-4-ish: 1080x2160 content, density 2.75 (xxhdpi).
        float density = 2.75f;
        int w = 1080, h = 2160;
        LegacyMiniplayerGeometry g = new LegacyMiniplayerGeometry(density, /*rtl=*/false);
        g.onSizeChanged(w, h);

        int expMiniW = Math.round(180 * density);          // 495
        int expMiniH = (int) (expMiniW / 1.777f);          // ~278
        int expPad = Math.round(8 * density);              // 22

        System.out.println("LTR bottom-right dock:");
        check("mini width == 180dp", g.mini.width() == expMiniW, g.mini.width());
        check("mini height 16:9", Math.abs(g.mini.height() - expMiniH) <= 1, g.mini.height());
        check("mini right edge = content-8dp", g.mini.right == w - expPad, g.mini.right);
        check("mini bottom edge = content-8dp", g.mini.bottom == h - expPad, g.mini.bottom);
        check("mini docked RIGHT half", g.mini.left > w / 2, g.mini.left);
        check("mini docked BOTTOM half", g.mini.top > h / 2, g.mini.top);

        System.out.println("full player rect (16:9 at top):");
        check("full spans width", g.full.left == 0 && g.full.right == w, g.full);
        check("full at top", g.full.top == 0, g.full.top);
        check("full is 16:9", Math.abs(g.full.height() - (int)(w / 1.777f)) <= 1, g.full.height());

        System.out.println("lerp bounds:");
        Rect out = new Rect();
        g.boundsFor(0f, 0, out);
        check("progress 0 == full", out.left == g.full.left && out.right == g.full.right, out);
        g.boundsFor(1f, 0, out);
        check("progress 1 == mini", out.left == g.mini.left && out.right == g.mini.right, out);
        g.boundsFor(0.5f, 0, out);
        check("progress .5 between", out.width() < g.full.width() && out.width() > g.mini.width(), out.width());
        g.boundsFor(1f, 100, out);
        check("dismissDx shifts x by 100", out.left == g.mini.left + 100, out.left);

        System.out.println("dismiss threshold (90dp):");
        check("under 90dp not dismissed", !g.shouldDismiss(g.dp(89)), g.dp(89));
        check("at 90dp dismissed", g.shouldDismiss(g.dp(90)), g.dp(90));
        check("dismissFade caps at 0.75", Math.abs(g.dismissFade(g.dp(200)) - 0.75f) < 1e-4, g.dismissFade(g.dp(200)));

        System.out.println("RTL mirrors to bottom-left:");
        LegacyMiniplayerGeometry rtl = new LegacyMiniplayerGeometry(density, true);
        rtl.onSizeChanged(w, h);
        check("rtl left edge = 8dp", rtl.mini.left == expPad, rtl.mini.left);
        check("rtl docked LEFT half", rtl.mini.right < w / 2, rtl.mini.right);

        System.out.println();
        if (failures == 0) System.out.println("ALL GEOMETRY TESTS PASSED");
        else { System.out.println(failures + " FAILURE(S)"); System.exit(1); }
    }
}

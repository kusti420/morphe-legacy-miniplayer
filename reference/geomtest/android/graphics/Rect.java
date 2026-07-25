package android.graphics;

// Minimal stub of android.graphics.Rect sufficient to compile & run LegacyMiniplayerGeometry
// off-device. Mirrors the real Android semantics for the members used.
public class Rect {
    public int left, top, right, bottom;
    public Rect() {}
    public void set(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
    public int width() { return right - left; }
    public int height() { return bottom - top; }
    public int centerX() { return (left + right) >> 1; }
    public int centerY() { return (top + bottom) >> 1; }
    public String toString() { return "Rect(" + left + "," + top + "," + right + "," + bottom + ")"; }
}

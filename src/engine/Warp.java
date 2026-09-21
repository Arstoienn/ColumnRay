package engine;

import java.util.stream.IntStream;

/**
 * Looking up and down.
 *
 * A column renderer can only draw columns that stay vertical, and on its own that means
 * y-shearing: slide the horizon, keep every vertical edge vertical. That is not what a camera
 * does when it tilts - looking up, verticals converge towards the top of the screen, and without
 * that the top and bottom of the picture get stretched, which reads as a vertical fisheye.
 *
 * Both are pinhole cameras at the same eye point, one with an upright image plane and one with a
 * tilted one, so each is an exact projective warp of the other, and for a pure pitch that warp
 * is simple row by row. Measuring v upwards from the centre of the screen, v' upwards from the
 * horizon of the upright image, and p = pitch:
 *
 *   z = F cos p - v sin p      s = F / z      v' = F (F sin p + v cos p) / z
 *
 * Output row v shows upright row v', stretched horizontally about the centre by s. So the
 * renderer draws the upright view - taller than the screen, and wider for the rows where s > 1
 * (the top when looking up) - and this resamples it. At p = 0 it is an exact 1:1 copy.
 *
 * This is the piece of the engine the whole project turns on, which is why it is now a file with
 * that name on it. The obvious way to tilt a camera is to stop being a column renderer; the rule
 * here is that the column renderer stays and an exact warp goes over its output, and a reader
 * looking for how that is done should not have to find it in the middle of the window code.
 *
 * A frame goes: {@link #plan} for the pitch, grow the render buffer if {@link #needW}/{@link #needH}
 * ask for more overscan than it has, {@link #place} to pin the warp to that buffer and tell the
 * camera which part of it to draw, render, then {@link #apply}.
 */
final class Warp {
    private boolean shear;
    private double sin, cos, tan, hz, cx, f, vHi;
    private int x0, x1, y1, rw, rh, needW, needH;

    /** This frame's warp, and how much overscan drawing it will need. */
    void plan(double pitch, boolean shear, int rw, int rh, double focal) {
        this.rw = rw;
        this.rh = rh;
        this.shear = shear;
        this.f = focal;
        double h2 = rh / 2.0, w2 = rw / 2.0;
        sin = Math.sin(pitch);
        cos = Math.cos(pitch);
        tan = Math.tan(pitch);
        double sMax = Math.max(rowScale(h2), rowScale(-h2));   // the widest row is the top or the bottom one
        vHi = rowSource(h2);
        double vLo = rowSource(-h2);                            // rowSource is monotonic in v
        needW = 2 * (int) Math.ceil(w2 * sMax) + 4;
        needH = (int) Math.ceil(vHi - vLo) + 4;
    }

    int needW() { return needW; }

    int needH() { return needH; }

    /**
     * The largest pitch whose upright image still fits in a budget of pixels.
     *
     * The overscan grows as a tangent and runs away at 90 degrees less the vertical half-FOV -
     * 69.6 degrees at the default field of view - so a map that asks to look further up than the
     * machine can hold would not be slow, it would fail to allocate. This is the ceiling that
     * stops it, and it is worked out by asking plan() itself rather than by a second copy of the
     * same formulas, which is the sort of pair that drifts.
     */
    static double fits(boolean shear, int rw, int rh, double focal, long budget) {
        Warp w = new Warp();
        double lo = 0, hi = Math.PI / 2;
        for (int i = 0; i < 40; i++) {                  // 40 halvings: the answer to a millionth of a degree
            double mid = 0.5 * (lo + hi);
            w.plan(mid, shear, rw, rh, focal);
            if ((long) w.needW() * w.needH() <= budget) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** Pin the warp to the buffer the renderer will draw into, and set the window it must fill. */
    void place(double centerX, int srcW, int srcH, Renderer.Camera c) {
        cx = centerX;
        hz = vHi + 2;                                           // the top of the screen lands on source row ~2
        x0 = Math.max(0, (int) Math.floor(cx - needW / 2.0));
        x1 = Math.min(srcW, x0 + needW);
        y1 = Math.min(srcH, needH);
        c.pitch = hz - srcH / 2.0;                              // the renderer puts the horizon at H/2 + pitch
        c.x0 = x0;
        c.x1 = x1;
        c.y0 = 0;
        c.y1 = y1;
    }

    /** Horizontal stretch s for output row v (measured up from the centre). */
    private double rowScale(double v) {
        if (shear) return 1;
        return f / Math.max(1e-3, f * cos - v * sin);
    }

    /** The height v' in the upright image that output row v shows. */
    private double rowSource(double v) {
        if (shear) return v + f * tan;
        return f * (f * sin + v * cos) / Math.max(1e-3, f * cos - v * sin);
    }

    /** Resample the upright render into the tilted output, one source row per output row.
     *  The depth and albedo arrays are a screenshot's; pass null for an ordinary frame. */
    void apply(int[] src, int srcW, int[] hi,
               float[] srcDepth, int[] srcAlbedo, float[] hiDepth, int[] hiAlbedo) {
        double w2 = rw / 2.0, h2 = rh / 2.0;
        int sw = srcW, xLo = x0, xHi = x1 - 1, yHi = y1 - 1;
        boolean capture = hiDepth != null;
        IntStream.range(0, rh).parallel().forEach(j -> {
            double v = h2 - (j + 0.5), k = rowScale(v);
            int ys = Math.max(0, Math.min(yHi, (int) Math.floor(hz - rowSource(v))));
            int srow = ys * sw, orow = j * rw;
            double sx = cx + (0.5 - w2) * k;                    // source x of output column 0's centre
            if (capture) {
                for (int i = 0; i < rw; i++, sx += k) {
                    int p = srow + Math.max(xLo, Math.min(xHi, (int) Math.floor(sx)));
                    hi[orow + i] = src[p];
                    hiDepth[orow + i] = srcDepth[p];
                    hiAlbedo[orow + i] = srcAlbedo[p];
                }
            } else {
                for (int i = 0; i < rw; i++, sx += k)
                    hi[orow + i] = src[srow + Math.max(xLo, Math.min(xHi, (int) Math.floor(sx)))];
            }
        });
    }

    /** The source (renderer) column that output pixel (i, j) comes from. */
    int sourceColumn(int i, int j) {
        double k = rowScale(rh / 2.0 - (j + 0.5));
        return Math.max(x0, Math.min(x1 - 1, (int) Math.floor(cx + (i + 0.5 - rw / 2.0) * k)));
    }

    /** Where source column col shows up on output row j, in output pixels from the left edge. */
    double outputX(int col, int j) {
        double k = rowScale(rh / 2.0 - (j + 0.5));
        return (col + 0.5 - cx) / k + rw / 2.0;
    }
}

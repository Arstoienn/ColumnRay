package engine;

/**
 * The pitch warp.
 *
 * The claim it rests on is that tilting the camera is an exact projective resampling of what the
 * column renderer already draws - so at pitch 0 it must be an exact 1:1 copy, and away from 0 it
 * must ask the renderer for the extra overscan it is about to read. Both are checked here.
 */
final class WarpTest {
    private WarpTest() {}

    private static final int RW = 64, RH = 32;
    private static final double F = 55.4;                        // a focal length, as setFov would give

    /** Set a warp up the way Main does: plan, grow the buffer if it asks, then place. */
    private static int[] buffer(Warp w, double pitch, boolean shear, Renderer.Camera cam, int[] size) {
        w.plan(pitch, shear, RW, RH, F);
        int srcW = RW, srcH = RH;
        if (w.needW() > srcW || w.needH() > srcH) {
            srcW = Math.max(srcW, (int) (w.needW() * 1.1));
            srcH = Math.max(srcH, (int) (w.needH() * 1.1));
        }
        w.place(srcW / 2.0, srcW, srcH, cam);
        size[0] = srcW;
        size[1] = srcH;
        return new int[srcW * srcH];
    }

    static void run() {
        Check.group("Warp");

        Renderer.Camera cam = new Renderer.Camera();
        int[] size = new int[2];

        // Level: every output column comes from the next source column along, and every output row
        // from one source row. That is what "an exact 1:1 copy at pitch 0" means in pixels.
        Warp level = new Warp();
        int[] src = buffer(level, 0, false, cam, size);
        int srcW = size[0];
        for (int i = 0; i < src.length; i++) src[i] = i % srcW;   // each pixel says which column it is
        int[] hi = new int[RW * RH];
        level.apply(src, srcW, hi, null, null, null, null);
        boolean steps = true, rowsAgree = true;
        for (int j = 0; j < RH; j++) {
            for (int i = 1; i < RW; i++) steps &= hi[j * RW + i] - hi[j * RW + i - 1] == 1;
            for (int i = 0; i < RW; i++) rowsAgree &= hi[j * RW + i] == hi[i];
        }
        Check.that(steps, "at pitch 0 the columns are copied one for one");
        Check.that(rowsAgree, "and every row is copied the same way, so nothing leans");

        for (int i = 0; i < src.length; i++) src[i] = i / srcW;   // now each pixel says which row it is
        level.apply(src, srcW, hi, null, null, null, null);
        boolean oneRow = true, distinct = true;
        for (int j = 0; j < RH; j++) {
            for (int i = 1; i < RW; i++) oneRow &= hi[j * RW + i] == hi[j * RW];
            if (j > 0) distinct &= hi[j * RW] == hi[(j - 1) * RW] + 1;
        }
        Check.that(oneRow, "one source row per output row");
        Check.that(distinct, "and consecutive rows, in order");

        // The camera is told to draw the window the warp will read, and no more.
        Check.that(cam.x1 - cam.x0 >= RW, "the renderer is asked for at least the screen's width");
        Check.that(cam.y1 >= RH, "and at least its height");
        Check.eq(cam.x0 >= 0, true, "the window starts inside the buffer");

        // Tilted: the warp needs more of both than the screen is, which is the overscan the whole
        // arrangement depends on - it is what the renderer draws and the screen never shows.
        Warp up = new Warp();
        up.plan(Math.toRadians(30), false, RW, RH, F);
        Check.that(up.needW() > RW + 4, "looking up needs a wider render than looking level, got " + up.needW());
        Check.that(up.needH() > RH + 4, "and a taller one, got " + up.needH());
        Warp down = new Warp();
        down.plan(Math.toRadians(-30), false, RW, RH, F);
        Check.eq(down.needW(), up.needW(), "up and down need the same overscan");
        Check.eq(down.needH(), up.needH(), "in both directions");

        // Y-shearing slides the horizon and leaves every vertical vertical, so it needs no extra
        // width at all. That is exactly why it is the wrong model, and why it is kept for comparison.
        Warp sheared = new Warp();
        sheared.plan(Math.toRadians(30), true, RW, RH, F);
        Check.eq(sheared.needW(), RW + 4, "y-shearing never needs a wider render");

        // Where a column lands and which column landed there are the same question, both ways round.
        Warp tilt = new Warp();
        buffer(tilt, Math.toRadians(20), false, cam, size);
        boolean roundTrip = true;
        for (int j = 0; j < RH; j += 7)
            for (int i = 0; i < RW; i += 5) {
                int col = tilt.sourceColumn(i, j);
                roundTrip &= Math.abs(tilt.outputX(col, j) - (i + 0.5)) <= 1;
            }
        Check.that(roundTrip, "sourceColumn and outputX are inverses, to within the pixel they round to");

        budget();
    }

    /** The ceiling that stops the warp asking for more overscan than the machine can allocate. */
    private static void budget() {
        Check.group("Warp.fits");
        long budget = 16L * RW * RH;
        double cap = Warp.fits(false, RW, RH, F, budget);
        Warp at = new Warp(), over = new Warp();
        at.plan(cap, false, RW, RH, F);
        over.plan(cap * 1.02, false, RW, RH, F);
        Check.that((long) at.needW() * at.needH() <= budget,
                "the pitch it returns fits the budget, got " + at.needW() + "x" + at.needH());
        Check.that((long) over.needW() * over.needH() > budget,
                "and two percent more does not, got " + over.needW() + "x" + over.needH());

        // The property the camera depends on: a budget that is a multiple of a level frame buys the
        // same angle at any render size. A budget in absolute pixels would not, and the range of
        // the look control would then depend on the window, which is no way to build a control.
        // Only the same to within a fraction of a degree, because needW and needH carry a fixed
        // four-pixel margin that is a larger share of a small frame than of a large one.
        double at720 = Math.toDegrees(Warp.fits(false, 1280, 720, 640 / 0.66, 16L * 1280 * 720));
        for (int[] size : new int[][] {{640, 360}, {1920, 1080}, {3840, 2160}}) {
            int w = size[0], h = size[1];
            Check.eq(Math.toDegrees(Warp.fits(false, w, h, (w / 2.0) / 0.66, 16L * w * h)), at720, 0.05,
                    w + "x" + h + " reaches the same angle as 1280x720");
        }

        // Y-shearing never widens the picture, so far more of it fits.
        Check.that(Warp.fits(true, RW, RH, F, budget) > cap, "y-shearing reaches further on the same budget");
        // A budget below one level frame leaves nothing: it must not hand back a tilt that overflows.
        Check.eq(Warp.fits(false, RW, RH, F, 1), 0, 0, "an impossible budget returns no tilt at all");
    }
}

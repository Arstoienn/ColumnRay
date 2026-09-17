package engine;

/** The frame-time controller. Its whole job is to be slow to climb and quick to drop, and that is
 *  hard to see by playing: a bad controller looks like a machine having a bad day. */
final class DynamicResolutionTest {
    private DynamicResolutionTest() {}

    private static final int TOP = DynamicResolution.LADDER.length - 1;

    /** Feed one time for n frames and say where it ended up. */
    private static int feed(DynamicResolution d, double ms, int frames) {
        int level = d.level();
        for (int i = 0; i < frames; i++) level = d.frame(ms);
        return level;
    }

    static void run() {
        Check.group("DynamicResolution");

        Check.that(DynamicResolution.LADDER[0] == 0.5 && DynamicResolution.LADDER[TOP] == 1.0,
                "the ladder runs from half the output to all of it");
        for (int i = 1; i <= TOP; i++)
            Check.that(DynamicResolution.LADDER[i] > DynamicResolution.LADDER[i - 1],
                    "the ladder is sorted, step " + i);
        for (int i = 1; i <= TOP; i++) {
            double pixels = Math.pow(DynamicResolution.LADDER[i] / DynamicResolution.LADDER[i - 1], 2);
            Check.that(pixels < 1 / DynamicResolution.UP_MARGIN,
                    "step " + i + " costs " + Math.round((pixels - 1) * 100) + "% more pixels, which must stay "
                    + "under the margin or the controller climbs it only to fall straight back");
        }

        // Over budget: down one step, and one step only, however bad it is.
        DynamicResolution d = new DynamicResolution(16, 4);
        Check.eq(feed(d, 12, DynamicResolution.DOWN - 1), 4, "not yet enough frames to judge");
        Check.eq(feed(d, 100, DynamicResolution.DOWN), 3, "over budget: one step down");
        Check.eq(feed(d, 100, DynamicResolution.SETTLE), 3,
                "the settle frames after a change are not judged - the buffers were just remade");
        Check.eq(feed(d, 100, DynamicResolution.DOWN), 2, "still over budget: one more step");

        // Comfortably under: up one step, but only after three times as many frames.
        DynamicResolution up = new DynamicResolution(16, 4);
        Check.eq(feed(up, 16 * DynamicResolution.UP_MARGIN + 0.1, DynamicResolution.UP), 4,
                "just inside the budget is not reason enough to climb");
        Check.eq(feed(up, 1, DynamicResolution.UP), 5, "comfortably under budget: one step up");

        // A single hitch must not carry the average on its own.
        DynamicResolution hitch = new DynamicResolution(16, 4);
        feed(hitch, 15, DynamicResolution.DOWN);
        Check.eq(hitch.frame(5000), 4, "one stalled frame does not drop the resolution");

        // The ends of the ladder hold.
        Check.eq(feed(new DynamicResolution(16, 0), 100, 10 * DynamicResolution.DOWN), 0,
                "the bottom of the ladder");
        Check.eq(feed(new DynamicResolution(16, TOP), 0.1, 10 * DynamicResolution.UP), TOP,
                "the top of the ladder");
        Check.eq(new DynamicResolution(16, 99).level(), TOP, "a level off the end is clamped");
        Check.eq(new DynamicResolution(16, -5).level(), 0, "and so is one below it");

        Check.eq(DynamicResolution.nearest(1.0), TOP, "nearest: the whole window");
        Check.eq(DynamicResolution.nearest(0.0), 0, "nearest: below the bottom");
        Check.eq(DynamicResolution.nearest(2 / 3.0), 3, "nearest: 1280 of 1920");
    }
}

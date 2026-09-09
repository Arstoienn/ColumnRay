package engine;

/**
 * Picks the render resolution from how long frames take.
 *
 * What a frame costs is the pixels it shades, not the rays it casts - on Haven, tilting the view
 * adds 47% more rays and only 15% more time - so the lever is the render size, and the window size
 * is the wrong thing to tie it to: a fullscreen window would shade four times the pixels of a
 * 960x540 one for the same picture on a laptop panel. So the window stays the output, and this
 * moves the render size between half the output and all of it:
 *
 *   - over budget on average over the last DOWN frames: one step down, straight away;
 *   - comfortably under (UP_MARGIN of the budget) over the last UP frames: one step up;
 *   - after either, SETTLE frames are not judged - the buffers have just been made again.
 *
 * Down is quick and up is slow on purpose: a frame that stutters is worse than one that is a
 * little soft for a second. The margin keeps it from climbing a step only to come straight back:
 * no two neighbouring steps differ by more than 27% in pixels, and 0.7 of the budget times 1.27 is
 * still under it.
 */
final class DynamicResolution {
    /** Render width as a share of the output's, finest last. 2/3 of 1920x1080 is 1280x720. */
    static final double[] LADDER = {0.5, 0.5625, 0.625, 2 / 3.0, 0.75, 5 / 6.0, 11 / 12.0, 1.0};
    static final int DOWN = 10, UP = 30, SETTLE = 20;
    static final double UP_MARGIN = 0.7, HITCH = 1.5;

    private final double[] recent = new double[Math.max(DOWN, UP)];
    private final double budgetMs;
    private int count, next, settle, level;

    DynamicResolution(double budgetMs, int level) {
        this.budgetMs = budgetMs;
        this.level = Math.max(0, Math.min(LADDER.length - 1, level));
    }

    int level() { return level; }

    /** One finished frame's time in milliseconds; returns the level to render the next one at,
     *  which is at most one step from the last. */
    int frame(double ms) {
        if (settle > 0) {
            settle--;
            return level;
        }
        // One frame counts for at most HITCH budgets: a single 80 ms hitch - a GC, the OS - would
        // otherwise carry a ten-frame average over budget on its own. A real stall still does it,
        // it just takes as many frames as it should.
        recent[next] = Math.min(ms, budgetMs * HITCH);
        next = (next + 1) % recent.length;
        count = Math.min(recent.length, count + 1);
        if (level > 0 && count >= DOWN && average(DOWN) > budgetMs) return step(-1);
        if (level < LADDER.length - 1 && count >= UP && average(UP) < budgetMs * UP_MARGIN) return step(+1);
        return level;
    }

    private int step(int by) {
        level += by;
        count = 0;
        next = 0;
        settle = SETTLE;
        return level;
    }

    private double average(int frames) {
        double sum = 0;
        for (int i = 1; i <= frames; i++) sum += recent[Math.floorMod(next - i, recent.length)];
        return sum / frames;
    }

    /** The step whose share of the output is nearest to this one. */
    static int nearest(double share) {
        int best = 0;
        for (int i = 1; i < LADDER.length; i++)
            if (Math.abs(LADDER[i] - share) < Math.abs(LADDER[best] - share)) best = i;
        return best;
    }
}

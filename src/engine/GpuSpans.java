package engine;

/**
 * The renderer's wall intervals, in the shape a card can read them.
 *
 * A column renderer's answer for one screen column is a handful of row intervals, each with the
 * few numbers a wall strip needs. That is a tiny amount of data - about a kilobyte for a whole
 * frame at 1280 columns - and it is the whole of what the GPU is told. The rays, the grid walk,
 * the shape tests and the occlusion are all still done on the CPU, by the same code, in the same
 * order; the card is handed the answer and asked only to filter and shade it.
 *
 * Every column writes into its own slice, so the renderer's threads never meet here and no
 * synchronisation is needed. A column that somehow produces more than {@link #MAX_PER_COLUMN}
 * wall intervals drops the rest and says so through {@link #dropped()}, rather than growing an
 * array under a parallel stream.
 *
 * The layout is three RGBA32F texels a span, because that is what a shader can fetch in three
 * reads:
 *
 *     x   y0   y1   mat
 *     u   z0   dz   light
 *     w   sq   rgb  -
 *
 * where z0 + dz * row is the height in metres of the pixel in row {@code row}, w is how wide one
 * pixel is on this surface, and sq is how square-on the ray meets it - the two numbers the
 * anisotropic filter runs on.
 */
final class GpuSpans {
    static final int MAX_PER_COLUMN = 64;
    static final int TEXELS = 3, FLOATS = TEXELS * 4;

    private final int columns;
    private final float[] data;
    private final int[] count;
    private final boolean[] blended;
    private volatile int dropped;

    GpuSpans(int columns, int rows) {
        this.columns = columns;
        this.data = new float[columns * MAX_PER_COLUMN * FLOATS];
        this.count = new int[columns];
        this.blended = new boolean[columns * rows];
    }

    int columns() { return columns; }

    float[] data() { return data; }

    int[] count() { return count; }

    int dropped() { return dropped; }

    /** A pixel the card was not given: a surface whose shading is not ported yet - an alpha
     *  mask blended over the finished picture, an image texture, a lightmapped wall. Marking
     *  them is what lets a comparison say "these are not done" instead of counting them as the
     *  GPU getting a wall wrong, and the count going to zero is what finishing looks like. */
    void skip(int x, int y) { blended[y * columns + x] = true; }

    boolean skipped(int x, int y) { return blended[y * columns + x]; }

    /** Between frames, on one thread: the renderer is not running. */
    void reset() {
        java.util.Arrays.fill(count, 0);
        java.util.Arrays.fill(blended, false);
        dropped = 0;
    }

    /**
     * One wall interval. Called from the column's own thread while it paints, so the only shared
     * thing it touches is the drop counter, and that is only ever a count of something going wrong.
     */
    void add(int x, int y0, int y1, double u, double light, double w, double sq, int mat, int rgb) {
        int at = slot(x);
        if (at < 0) return;
        data[at + 4] = (float) u;
        data[at + 8] = (float) w;
        data[at + 9] = (float) sq;
        head(at, x, y0, y1, mat, light, rgb, 0);
    }

    /** One stretch of a floor, a ceiling or a shape's top or bottom. */
    void addPlane(int x, int y0, int y1, double z, double slope, double light, int mat, int rgb) {
        int at = slot(x);
        if (at < 0) return;
        data[at + 4] = (float) z;
        data[at + 5] = (float) slope;
        head(at, x, y0, y1, mat, light, rgb, 1);
    }

    /** Floors are painted a grid cell at a time, so one floor arrives as a run of short spans
     *  with the same numbers and touching rows. Joining them keeps a column's list to the few
     *  surfaces it really sees rather than the few dozen cells its ray crossed. */
    private boolean joins(int at, int x, int y0, int y1, int mat, double light, int rgb, int kind) {
        if (count[x] == 0) return false;
        int prev = at - FLOATS;
        if (prev < x * MAX_PER_COLUMN * FLOATS) return false;
        if (data[prev + 11] != kind || data[prev + 3] != mat || data[prev + 10] != rgb
                || data[prev + 7] != (float) light) return false;
        for (int i = 4; i <= 9; i++) if (data[prev + i] != data[at + i]) return false;
        if (data[prev + 2] == y0) { data[prev + 2] = y1; return true; }
        if (data[prev + 1] == y1) { data[prev + 1] = y0; return true; }
        return false;
    }

    private void head(int at, int x, int y0, int y1, int mat, double light, int rgb, int kind) {
        if (joins(at, x, y0, y1, mat, light, rgb, kind)) return;
        data[at] = x;
        data[at + 1] = y0;
        data[at + 2] = y1;
        data[at + 3] = mat;
        data[at + 7] = (float) light;
        data[at + 10] = rgb;
        data[at + 11] = kind;
        count[x]++;
    }

    private int slot(int x) {
        int n = count[x];
        if (n >= MAX_PER_COLUMN) {
            dropped++;
            return -1;
        }
        int at = (x * MAX_PER_COLUMN + n) * FLOATS;
        for (int i = 0; i < FLOATS; i++) data[at + i] = 0;
        return at;
    }
}

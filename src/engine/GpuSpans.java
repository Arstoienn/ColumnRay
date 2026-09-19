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
    static final int MAX_PER_COLUMN = 24;
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

    /** A pixel an alpha-masked surface - a tree, a railing - was mixed over after the walls were
     *  painted. The card is not given those yet, so a comparison has to leave them out rather
     *  than count the CPU's foliage as the GPU getting the wall wrong. */
    void blend(int x, int y) { blended[y * columns + x] = true; }

    boolean wasBlended(int x, int y) { return blended[y * columns + x]; }

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
        int n = count[x];
        if (n >= MAX_PER_COLUMN) {
            dropped++;
            return;
        }
        int at = (x * MAX_PER_COLUMN + n) * FLOATS;
        data[at] = x;
        data[at + 1] = y0;
        data[at + 2] = y1;
        data[at + 3] = mat;
        data[at + 4] = (float) u;
        data[at + 5] = 0;
        data[at + 6] = 0;
        data[at + 7] = (float) light;
        data[at + 8] = (float) w;
        data[at + 9] = (float) sq;
        data[at + 10] = rgb;
        data[at + 11] = 0;
        count[x] = n + 1;
    }
}

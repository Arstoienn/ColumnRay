package engine;

/**
 * The masked surfaces of a frame - the trees and the cut-outs - as a card can read them.
 *
 * A masked surface is a flat quad that is mostly holes, and what shows through the holes is
 * whatever is behind it. So the renderer cannot paint one when the ray reaches it: it notes the
 * rows the quad could cover and blends it over the finished column afterwards, furthest first
 * ({@code Renderer.blendMasked}). The card has to do exactly the same thing in the same order,
 * which is why these are a list of their own rather than more {@link GpuSpans} entries: a span
 * decides a pixel, and one of these only tints whatever the pixel already was.
 *
 * The order is the whole of the correctness. Entries are appended as the ray meets them, so the
 * blend walks the list backwards, and a column's entries here keep the order the renderer gave
 * them - including the gaps where a surface the card cannot draw was left out. That is safe in
 * one direction only, and it is the direction that happens: a surface left out has its own
 * pixels marked skipped wherever it really covered them, and where it did not cover them it
 * changed nothing, so the ones the card did draw are still right.
 *
 * There are two of them, and {@code kind} says which. A tree is a procedural mask on a flat
 * quad; a cut-out - a slab sawn out of a mesh along the shape painted into an image's alpha -
 * takes its alpha from that image instead, and can be a side or a plane. All three shade exactly
 * as the solid surface of the same kind would, so the only thing that really differs between
 * them is where the alpha comes from.
 *
 * Four RGBA32F texels an entry, the first two holding whichever the kind needs:
 *
 *     x     y0     y1     kind          kind >= 0 a mask, -1 a cut-out side, -2 a cut-out plane
 *     u     t      w      sq            a side;  or z, slope for a plane
 *     light lm     rgb    mat
 *     1/len 1/high z0     rec           a mask;  or alpha's record in the first slot
 *
 * where w is how wide a pixel is in the mask's own 0-to-1 units, and 1/len, 1/high and z0 put a
 * point of the quad into them.
 */
final class GpuMasks {
    /**
     * Room for a column's entries. Measured at 1280 columns: school wants ten, and Haven's worst
     * camera wants 601, because a mesh sawn into cut-out slabs puts a great many of them behind
     * one another. A column that wants more keeps the rest on the CPU and says so through
     * {@link #dropped()}, which costs coverage rather than correctness. Only the part of this a
     * frame really uses crosses the bus ({@code GpuWalls.pack}), so the size is heap and not
     * bandwidth.
     */
    static final int MAX_PER_COLUMN = 1024;
    static final int TEXELS = 4, FLOATS = TEXELS * 4;

    private final int columns;
    private float[] data;
    private int perColumn;
    private final int[] count;
    private volatile int dropped;

    GpuMasks(int columns) {
        this.columns = columns;
        this.perColumn = Math.min(16, MAX_PER_COLUMN);
        this.data = new float[columns * perColumn * FLOATS];
        this.count = new int[columns];
    }

    float[] data() { return data; }

    int[] count() { return count; }

    /** The most any one column holds, which is how much of the buffer a frame really uses. */
    int most() {
        int m = 0;
        for (int n : count) m = Math.max(m, n);
        return m;
    }

    /** Entries a column had no room for. They are not dropped from the picture - the renderer
     *  marks their rows skipped instead, exactly as it does for a surface the card cannot draw. */
    int dropped() { return dropped; }

    int perColumn() { return perColumn; }

    /** Between frames, and as {@link GpuSpans#reset}: twice the room after a column ran out. */
    void reset() {
        if (dropped > 0 && perColumn < MAX_PER_COLUMN) {
            perColumn = Math.min(MAX_PER_COLUMN, perColumn * 2);
            data = new float[columns * perColumn * FLOATS];
        }
        java.util.Arrays.fill(count, 0);
        dropped = 0;
    }

    /**
     * A masked or cut-out side over one run of rows, or false when the column is full and the
     * renderer should keep it. Called from the column's own thread, like {@link GpuSpans#add}.
     */
    boolean addSide(int x, int y0, int y1, int kind, double u, double t, double w, double sq,
                    double light, int lm, int rgb, int mat,
                    double invLen, double invHigh, double z0, int rec, int alpha) {
        int at = slot(x, y0, y1, kind);
        if (at < 0) return false;
        data[at + 4] = (float) u;
        data[at + 5] = (float) t;
        data[at + 6] = (float) w;
        data[at + 7] = (float) sq;
        data[at + 8] = (float) light;
        data[at + 9] = lm;
        data[at + 10] = rgb;
        data[at + 11] = mat;
        data[at + 12] = kind >= 0 ? (float) invLen : alpha;
        data[at + 13] = (float) invHigh;
        data[at + 14] = (float) z0;
        data[at + 15] = rec;
        count[x]++;
        return true;
    }

    /** A cut-out's top or bottom: each row finds its own distance on the plane, as a plane span
     *  does, and the plane's own shading then applies. */
    boolean addPlane(int x, int y0, int y1, double z, double slope,
                     double light, int lm, int rgb, int mat, int rec, int alpha) {
        int at = slot(x, y0, y1, -2);
        if (at < 0) return false;
        data[at + 4] = (float) z;
        data[at + 5] = (float) slope;
        data[at + 8] = (float) light;
        data[at + 9] = lm;
        data[at + 10] = rgb;
        data[at + 11] = mat;
        data[at + 12] = alpha;
        data[at + 15] = rec;
        count[x]++;
        return true;
    }

    private int slot(int x, int y0, int y1, int kind) {
        int n = count[x];
        if (n >= perColumn) {
            dropped++;
            return -1;
        }
        int at = (x * perColumn + n) * FLOATS;
        for (int i = 0; i < FLOATS; i++) data[at + i] = 0;
        data[at] = x;
        data[at + 1] = y0;
        data[at + 2] = y1;
        data[at + 3] = kind;
        return at;
    }

    /** Renderer.blendMasked, over whatever the spans left in this pixel. */
    static final String GLSL = """
            uniform sampler2D masks;

            /** Renderer.mix: a byte blend, k out of 256, and the shift truncates. */
            vec3 mixPixel(vec3 dst, vec3 src, float a) {
                float k = floor(a * 256.0), j = 256.0 - k;
                return floor((dst * j + src * k) / 256.0);
            }

            /** A cut-out's alpha, which is its image's first channel run through the same
             *  divide-by-the-mean as a colour (Materials.Texture.sampleAt). A world with no
             *  images has no cut-outs and no image arrays to read, so there it stands empty. */
            #ifdef HAS_IMAGES
            float cutAlpha(int rec, vec2 pq, float w) { return imageSample(rec, pq, w).r; }
            #else
            float cutAlpha(int rec, vec2 pq, float w) { return 1.0; }
            #endif

            vec3 blendMasks(int col, int row, int n, vec3 under) {
                // Backwards: the renderer appended them as the ray met them, so this is far to near.
                for (int i = n - 1; i >= 0; i--) {
                    vec4 a = texelFetch(masks, ivec2(i * 4, col), 0);
                    if (row < int(a.y) || row >= int(a.z)) continue;
                    vec4 b = texelFetch(masks, ivec2(i * 4 + 1, col), 0);
                    vec4 c = texelFetch(masks, ivec2(i * 4 + 2, col), 0);
                    vec4 d = texelFetch(masks, ivec2(i * 4 + 3, col), 0);
                    int kind = int(a.w), mat = int(c.w), lm = int(c.y), rec = int(d.w);
                    float alpha;
                    vec3 over;
                    if (kind == -2) {                          // Renderer.blendPlane
                        float t = (eye - b.x) * foc / ((float(row) + 0.5 - hz) * dk + b.y * foc);
                        if (!(t > 0.0) || t > maxDist) continue;
                        alpha = cutAlpha(int(d.x), vec2(camX + rayX * t, camY + rayY * t),
                                t * dk / foc);
                        if (alpha <= 0.004) continue;
                        over = planeColour(mat, lm, rec, c.z, c.x, t, row);
                    } else {
                        float narrow = b.y * dk / foc;         // Renderer.pixelSize(t)
                        float z = eye - (float(row) + 0.5 - hz) * narrow;
                        alpha = kind >= 0
                                ? maskAt(kind, b.x * d.x, (z - d.z) * d.y, b.z)
                                : cutAlpha(int(d.x), vec2(b.x, z), narrow / b.w);
                        if (alpha <= 0.004) continue;
                        over = wallColour(mat, lm, rec, c.z, b.x, z, narrow, b.w, c.x);
                    }
                    under = alpha >= 0.996 ? over : mixPixel(under, over, alpha);
                }
                return under;
            }
            """;
}

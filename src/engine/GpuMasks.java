package engine;

/**
 * The masked surfaces of a frame - the trees - in the shape a card can read them.
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
 * Four RGBA32F texels an entry:
 *
 *     x     y0     y1     kind
 *     u     t      w      sq
 *     light lm     rgb    mat
 *     1/len 1/high z0     rec
 *
 * where kind is the mask ({@code Materials.CANOPY} and friends), w is how wide a pixel is in the
 * mask's own 0-to-1 units, and 1/len, 1/high and z0 put a point of the quad into them.
 */
final class GpuMasks {
    static final int MAX_PER_COLUMN = 16;
    static final int TEXELS = 4, FLOATS = TEXELS * 4;

    private final int columns;
    private final float[] data;
    private final int[] count;
    private volatile int dropped;

    GpuMasks(int columns) {
        this.columns = columns;
        this.data = new float[columns * MAX_PER_COLUMN * FLOATS];
        this.count = new int[columns];
    }

    float[] data() { return data; }

    int[] count() { return count; }

    /** Entries a column had no room for. They are not dropped from the picture - the renderer
     *  marks their rows skipped instead, exactly as it does for a surface the card cannot draw. */
    int dropped() { return dropped; }

    void reset() {
        java.util.Arrays.fill(count, 0);
        dropped = 0;
    }

    /**
     * One masked surface over one run of rows, or false when the column is full and the renderer
     * should keep it. Called from the column's own thread, like {@link GpuSpans#add}.
     */
    boolean add(int x, int y0, int y1, int kind, double u, double t, double w, double sq,
                double light, int lm, int rgb, int mat, double invLen, double invHigh, double z0, int rec) {
        int n = count[x];
        if (n >= MAX_PER_COLUMN) {
            dropped++;
            return false;
        }
        int at = (x * MAX_PER_COLUMN + n) * FLOATS;
        data[at] = x;
        data[at + 1] = y0;
        data[at + 2] = y1;
        data[at + 3] = kind;
        data[at + 4] = (float) u;
        data[at + 5] = (float) t;
        data[at + 6] = (float) w;
        data[at + 7] = (float) sq;
        data[at + 8] = (float) light;
        data[at + 9] = lm;
        data[at + 10] = rgb;
        data[at + 11] = mat;
        data[at + 12] = (float) invLen;
        data[at + 13] = (float) invHigh;
        data[at + 14] = (float) z0;
        data[at + 15] = rec;
        count[x]++;
        return true;
    }

    /** Renderer.blendMasked, over whatever the spans left in this pixel. */
    static final String GLSL = """
            uniform sampler2D masks;

            /** Renderer.mix: a byte blend, k out of 256, and the shift truncates. */
            vec3 mixPixel(vec3 dst, vec3 src, float a) {
                float k = floor(a * 256.0), j = 256.0 - k;
                return floor((dst * j + src * k) / 256.0);
            }

            vec3 blendMasks(int col, int row, int n, vec3 under) {
                // Backwards: the renderer appended them as the ray met them, so this is far to near.
                for (int i = n - 1; i >= 0; i--) {
                    vec4 a = texelFetch(masks, ivec2(i * 4, col), 0);
                    if (row < int(a.y) || row >= int(a.z)) continue;
                    vec4 b = texelFetch(masks, ivec2(i * 4 + 1, col), 0);
                    vec4 c = texelFetch(masks, ivec2(i * 4 + 2, col), 0);
                    vec4 d = texelFetch(masks, ivec2(i * 4 + 3, col), 0);
                    float narrow = b.y * dk / foc;              // Renderer.pixelSize(t)
                    float z = eye - (float(row) + 0.5 - hz) * narrow;
                    float k = maskAt(int(a.w), b.x * d.x, (z - d.z) * d.y, b.z);
                    if (k <= 0.004) continue;
                    int lm = int(c.y), rec = int(d.w);
                    vec3 L = lm < 0 ? vec3(1.0) : lightAt(lm, b.x, z);
                    vec3 tex;
                    #ifdef HAS_IMAGES
                    if (rec >= 0) tex = sideImage(rec, b.x, z, narrow, b.w, b.y);
                    else tex = vec3(sideTex(int(c.w), b.x, z, narrow, b.w));
                    #else
                    tex = vec3(sideTex(int(c.w), b.x, z, narrow, b.w));
                    #endif
                    vec3 lit = shadeT(c.z, tex, c.x, L);
                    under = k >= 0.996 ? lit : mixPixel(under, lit, k);
                }
                return under;
            }
            """;
}

package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;

/**
 * Every baked lightmap, on the card.
 *
 * {@link Lighting.LightMap} is one {@code float[]} a surface - a floor, a ceiling, one face of a
 * shape, one edge of a region - each with its own origin, texel size and extent, and hundreds
 * or hundreds of thousands of them in a map. A shader cannot chase that many separate arrays,
 * so they are shelf-packed into one float texture and a second texture says where each one
 * landed. The span then carries nothing but an index.
 *
 * Two details decide whether the picture matches:
 *
 * The texels are floats and **light routinely goes over 1.0** - bounced light on a sunlit wall
 * is what the tone curve exists to roll off - so the atlas is RGB32F. An 8-bit atlas would clip
 * exactly where the picture is brightest.
 *
 * {@code LightMap.sample} is bilinear with texel centres at {@code u0 + i*step} and a clamp at
 * {@code w - 1.0001} - no half-texel offset, unlike an image texture, and a hair tighter than
 * the hardware's. So the shader fetches the four texels itself rather than asking for
 * {@code GL_LINEAR}, which would be wrong in both of those ways and wrong again at the seams
 * between two maps that happen to be neighbours in the atlas.
 */
final class GpuLights {
    /** Where one map sits in the atlas and what its surface coordinates mean. */
    private static final int RECORD_TEXELS = 2;                 // (x, y, w, h) and (u0, v0, step, overlay)
    /** As many flicker groups as the shader has levels for. */
    static final int MAX_GROUPS = 16;

    private final int atlas, records, count;
    private final int side;
    private final GpuTable table;
    private final float[] levels;                                // the Lighting's, or null

    /**
     * Pack every map into one atlas, tallest first, and record where each one landed.
     *
     * A map that a flicker group reaches brings that group's light along as one or two more
     * rectangles of the same size. They are packed with the rest, and the map's record says where
     * through its fourth float, which is otherwise 0: 1 + the index of an overlay record after the
     * maps' own - (x, y, group, -) and (x, y, group or -1, -). So a map without flicker, which is
     * every map of every map but this kind, costs the same as before.
     */
    GpuLights(Lighting lighting) {
        Gl.context();
        levels = lighting.groupLevels();
        if (levels != null && levels.length > MAX_GROUPS)
            throw new IllegalStateException("a map may have " + MAX_GROUPS + " flicker groups, not " + levels.length);
        // The baked maps are what goes into the atlas; the views onto them get a record each and
        // no texels of their own, because a view is the same rectangle read from a different u0.
        List<Lighting.LightMap> maps = lighting.maps(), shown = lighting.shown();
        int packed = maps.size();
        count = shown.size();
        for (int i = 0; i < count; i++) shown.get(i).gpuIndex = i;
        java.util.IdentityHashMap<Lighting.LightMap, Integer> where = new java.util.IdentityHashMap<>();
        for (int i = 0; i < packed; i++) where.put(maps.get(i), i);

        // Every rectangle: the maps, then each overlay.
        List<float[]> rects = new java.util.ArrayList<>();
        List<int[]> sizes = new java.util.ArrayList<>();
        for (Lighting.LightMap m : maps) { rects.add(m.rgb); sizes.add(new int[] {m.w, m.h}); }
        java.util.IdentityHashMap<Lighting.LightMap, Integer> overlay = new java.util.IdentityHashMap<>();
        int overlays = 0;
        for (Lighting.LightMap m : maps) {
            if (m.over0 == null) continue;
            overlay.put(m, overlays++);
            rects.add(m.over0);
            sizes.add(new int[] {m.w, m.h});
            if (m.over1 != null) { rects.add(m.over1); sizes.add(new int[] {m.w, m.h}); }
        }
        int n = rects.size();

        // Tallest first. A shelf is as tall as the tallest map on it, so one large map among the
        // small ones wastes the whole width of a shelf, and Haven's 3.8 million maps are mostly
        // at the 2x2 floor with a few large ones scattered through them: in the bake's own order
        // that waste alone overflowed a 16384 square, which is the largest a card will allocate.
        long[] order = new long[n];
        for (int i = 0; i < n; i++)
            order[i] = ((long) (Integer.MAX_VALUE - sizes.get(i)[1]) << 32) | i;
        Arrays.sort(order);

        // Then simply try each square until one holds them, rather than guessing the size from
        // the area and a fudge factor for the waste: the packer itself is the only honest answer
        // to how much room the waste needs, and it runs in a few million steps.
        int[] px = new int[n], py = new int[n];
        int want = 64, most = Gl.maxTextureSize();
        while (!packs(sizes, order, px, py, want)) {
            if (want >= most)
                throw new IllegalStateException("the lightmaps do not fit a " + most
                        + " square atlas; raise lighting.texel or pack them across several");
            want = Math.min(most, want * 2);
        }
        side = want;

        atlas = Gl.texture();
        Gl.activeTexture(2);
        Gl.bindTexture(atlas);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment texels = arena.allocate((long) side * side * 3 * Float.BYTES);
            for (int i = 0; i < n; i++) {
                int w = sizes.get(i)[0], h = sizes.get(i)[1];
                float[] rgb = rects.get(i);
                for (int j = 0; j < h; j++) {
                    long at = ((long) (py[i] + j) * side + px[i]) * 3;
                    MemorySegment.copy(rgb, j * w * 3, texels, ValueLayout.JAVA_FLOAT, at * Float.BYTES, w * 3);
                }
            }
            Gl.texImage(Gl.RGB32F, side, side, Gl.RGB, Gl.FLOAT, texels);
            Gl.texUnfiltered();
            Gl.check("the lightmap atlas, %d square".formatted(side));

            records = Gl.texture();
            Gl.activeTexture(3);
            Gl.bindTexture(records);
            table = new GpuTable(RECORD_TEXELS, count + overlays);
            MemorySegment rec = arena.allocate((long) table.width() * table.rows() * 4 * Float.BYTES);
            for (int i = 0; i < count; i++) {
                Lighting.LightMap m = shown.get(i);
                Lighting.LightMap b = m.base != null ? m.base : m;  // a view borrows its base's rectangle
                int k = where.get(b);
                Integer o = overlay.get(b);
                long at = table.at(i);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at, px[k]);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 1, py[k]);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 2, m.w);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 3, m.h);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 4, (float) m.u0);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 5, (float) m.v0);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 6, (float) m.step);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 7, o == null ? 0 : count + o + 1);
            }
            // The overlays' rectangles were added in map order, right after the maps.
            int r = packed;
            for (Lighting.LightMap m : maps) {
                Integer o = overlay.get(m);
                if (o == null) continue;
                long at = table.at(count + o);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at, px[r]);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 1, py[r]);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 2, m.group0);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 3, 0);
                r++;
                if (m.over1 != null) {
                    rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 4, px[r]);
                    rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 5, py[r]);
                    rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 6, m.group1);
                    r++;
                } else {
                    rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 6, -1);
                }
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 7, 0);
            }
            Gl.texImage(Gl.RGBA32F, table.width(), table.rows(), Gl.RGBA, Gl.FLOAT, rec);
            Gl.texUnfiltered();
            Gl.check("the lightmap records, %dx%d".formatted(table.width(), table.rows()));
        }
    }

    /**
     * Shelf-pack the rectangles into a square of this side, in the given order, and say whether
     * they fit. The gutter is one texel, because the shader's own clamp keeps every fetch inside
     * a map and a neighbour's texel can never be read.
     */
    private static boolean packs(List<int[]> sizes, long[] order, int[] px, int[] py, int side) {
        int x = 0, y = 0, shelf = 0;
        for (long o : order) {
            int i = (int) o;
            int w = sizes.get(i)[0], h = sizes.get(i)[1];
            if (x + w > side) { x = 0; y += shelf + 1; shelf = 0; }
            if (y + h > side || w > side) return false;
            px[i] = x;
            py[i] = y;
            x += w + 1;
            shelf = Math.max(shelf, h);
        }
        return true;
    }

    int maps() { return count; }

    /** Bind the two textures on the units the shader expects. */
    void bind() {
        Gl.activeTexture(2);
        Gl.bindTexture(atlas);
        Gl.activeTexture(3);
        Gl.bindTexture(records);
    }

    /** Hand the program in use each flicker group's level for this frame. */
    void levels(int program) {
        if (levels != null) Gl.uniform(program, "flickerLevel", levels);
    }

    void close() {
        Gl.deleteTexture(atlas);
        Gl.deleteTexture(records);
    }

    /** Lighting.LightMap.sample, fetch for fetch. */
    String glsl() { return glsl(table); }

    /** The flat model has no lightmaps and no span asks for one, but the shader still has to
     *  compile the call: give it a table of nothing. */
    static String absent() { return glsl(new GpuTable(RECORD_TEXELS, 0)); }

    private static String glsl(GpuTable t) {
        return """
            uniform sampler2D lightAtlas;
            uniform sampler2D lightRecords;
            uniform float flickerLevel[%d];
            """.formatted(MAX_GROUPS) + t.glsl("lightRecordAt") + """

            vec3 lightBilinear(ivec2 o, float s, float t) {
                vec3 p00 = texelFetch(lightAtlas, o, 0).rgb;
                vec3 p10 = texelFetch(lightAtlas, o + ivec2(1, 0), 0).rgb;
                vec3 p01 = texelFetch(lightAtlas, o + ivec2(0, 1), 0).rgb;
                vec3 p11 = texelFetch(lightAtlas, o + ivec2(1, 1), 0).rgb;
                vec3 top = p00 + (p10 - p00) * s;
                vec3 bot = p01 + (p11 - p01) * s;
                return top + (bot - top) * t;
            }

            vec3 lightAt(int idx, float u, float v) {
                ivec2 r = lightRecordAt(idx);
                vec4 a = texelFetch(lightRecords, r, 0);
                vec4 b = texelFetch(lightRecords, r + ivec2(1, 0), 0);
                float fu = clamp((u - b.x) / b.z, 0.0, a.z - 1.0001);
                float fv = clamp((v - b.y) / b.z, 0.0, a.w - 1.0001);
                int i = int(fu), j = int(fv);
                float s = fu - float(i), t = fv - float(j);
                vec3 light = lightBilinear(ivec2(int(a.x) + i, int(a.y) + j), s, t);
                if (b.w > 0.5) {                           // a flicker group or two over this map
                    ivec2 q = lightRecordAt(int(b.w) - 1);
                    vec4 c = texelFetch(lightRecords, q, 0);
                    vec4 d = texelFetch(lightRecords, q + ivec2(1, 0), 0);
                    light += flickerLevel[int(c.z)] * lightBilinear(ivec2(int(c.x) + i, int(c.y) + j), s, t);
                    if (d.z > -0.5)
                        light += flickerLevel[int(d.z)] * lightBilinear(ivec2(int(d.x) + i, int(d.y) + j), s, t);
                }
                return light;
            }
            """;
    }
}

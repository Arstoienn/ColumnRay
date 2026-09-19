package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
    private static final int RECORD_TEXELS = 2;                 // (x, y, w, h) and (u0, v0, step, -)

    private final int atlas, records, count;
    private final int side;
    private final GpuTable table;

    /** Pack every map in the bake's own order - the order {@link LightCache} serialises in, which
     *  is proof enough that a flat layout of them round-trips. */
    GpuLights(Lighting lighting) {
        List<Lighting.LightMap> maps = lighting.maps();
        count = maps.size();
        // A shelf packer: maps in the order they come, a new shelf when the row is full. The
        // gutter is one texel, because the shader's own clamp keeps every fetch inside a map and
        // a neighbour's texel can never be read.
        int want = 1;
        long area = 0;
        for (Lighting.LightMap m : maps) area += (long) (m.w + 1) * (m.h + 1);
        while ((long) want * want < area * 2 && want < 16384) want *= 2;
        int x = 0, y = 0, shelf = 0;
        int[] px = new int[count], py = new int[count];
        for (int i = 0; i < count; i++) {
            Lighting.LightMap m = maps.get(i);
            if (x + m.w > want) { x = 0; y += shelf + 1; shelf = 0; }
            if (y + m.h > want)
                throw new IllegalStateException("the lightmaps do not fit a " + want + " square atlas; "
                        + "raise lighting.texel or pack them across several");
            px[i] = x;
            py[i] = y;
            m.gpuIndex = i;
            x += m.w + 1;
            shelf = Math.max(shelf, m.h);
        }
        side = want;

        Gl.context();
        atlas = Gl.texture();
        Gl.activeTexture(2);
        Gl.bindTexture(atlas);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment texels = arena.allocate((long) side * side * 3 * Float.BYTES);
            for (int i = 0; i < count; i++) {
                Lighting.LightMap m = maps.get(i);
                for (int j = 0; j < m.h; j++) {
                    long at = ((long) (py[i] + j) * side + px[i]) * 3;
                    MemorySegment.copy(m.rgb, j * m.w * 3, texels, ValueLayout.JAVA_FLOAT, at * Float.BYTES, m.w * 3);
                }
            }
            Gl.texImage(Gl.RGB32F, side, side, Gl.RGB, Gl.FLOAT, texels);
            Gl.texUnfiltered();
            Gl.check("the lightmap atlas, %d square".formatted(side));

            records = Gl.texture();
            Gl.activeTexture(3);
            Gl.bindTexture(records);
            table = new GpuTable(RECORD_TEXELS, count);
            MemorySegment rec = arena.allocate((long) table.width() * table.rows() * 4 * Float.BYTES);
            for (int i = 0; i < count; i++) {
                Lighting.LightMap m = maps.get(i);
                long at = table.at(i);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at, px[i]);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 1, py[i]);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 2, m.w);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 3, m.h);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 4, (float) m.u0);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 5, (float) m.v0);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 6, (float) m.step);
                rec.setAtIndex(ValueLayout.JAVA_FLOAT, at + 7, 0);
            }
            Gl.texImage(Gl.RGBA32F, table.width(), table.rows(), Gl.RGBA, Gl.FLOAT, rec);
            Gl.texUnfiltered();
            Gl.check("the lightmap records, %dx%d".formatted(table.width(), table.rows()));
        }
    }

    int maps() { return count; }

    int atlasSide() { return side; }

    /** Bind the two textures on the units the shader expects. */
    void bind() {
        Gl.activeTexture(2);
        Gl.bindTexture(atlas);
        Gl.activeTexture(3);
        Gl.bindTexture(records);
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
            """ + t.glsl("lightRecordAt") + """

            vec3 lightAt(int idx, float u, float v) {
                ivec2 r = lightRecordAt(idx);
                vec4 a = texelFetch(lightRecords, r, 0);
                vec4 b = texelFetch(lightRecords, r + ivec2(1, 0), 0);
                float fu = clamp((u - b.x) / b.z, 0.0, a.z - 1.0001);
                float fv = clamp((v - b.y) / b.z, 0.0, a.w - 1.0001);
                int i = int(fu), j = int(fv);
                float s = fu - float(i), t = fv - float(j);
                ivec2 o = ivec2(int(a.x) + i, int(a.y) + j);
                vec3 p00 = texelFetch(lightAtlas, o, 0).rgb;
                vec3 p10 = texelFetch(lightAtlas, o + ivec2(1, 0), 0).rgb;
                vec3 p01 = texelFetch(lightAtlas, o + ivec2(0, 1), 0).rgb;
                vec3 p11 = texelFetch(lightAtlas, o + ivec2(1, 1), 0).rgb;
                vec3 top = p00 + (p10 - p00) * s;
                vec3 bot = p01 + (p11 - p01) * s;
                return top + (bot - top) * t;
            }
            """;
    }
}

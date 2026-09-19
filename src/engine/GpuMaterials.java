package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * What an image-textured surface is made of, in a table the card reads once.
 *
 * A textured surface needs more than a span can carry: which array texture and layer its
 * image landed in, how many mip levels it has, its measured mean, and the affine map from
 * surface coordinates to image coordinates. None of that changes between frames, and the spans
 * are re-uploaded every frame, so it belongs in a table with the span carrying an index - the
 * same arrangement {@link GpuLights} uses for the lightmaps.
 *
 * Two mappings collapse into one record. An atlas tile repeats every {@code ts} metres, which
 * is the affine map {@code (p/ts, q/ts)}; a mesh carries its own six coefficients. Both then
 * scale the pixel footprint into repetitions of the image the same way, by a single number -
 * {@code 1/ts} for a tile, the root of the map's area scale for a mesh - so the shader has one
 * code path and the difference is six floats in a table.
 */
final class GpuMaterials {
    static final int TEXELS = 4;

    private final List<float[]> records = new ArrayList<>();
    private int texture = -1;

    private final GpuTable table;

    private int record(GpuTextures images, Materials.Texture tex, double[] uv, double ts, boolean worldUv) {
        if (tex == null) return -1;
        int[] at = images.at(tex);
        if (at == null) return -1;
        double[] m = uv != null ? uv : new double[] {1 / ts, 0, 0, 0, 1 / ts, 0};
        double lodScale = uv != null
                ? Math.sqrt(Math.abs(uv[0] * uv[4] - uv[1] * uv[3]))
                : 1 / ts;
        records.add(new float[] {
            at[0], at[1], tex.levelCount(), Math.max(tex.levelW(0), tex.levelH(0)),
            (float) tex.mean(0), (float) tex.mean(1), (float) tex.mean(2), (float) lodScale,
            (float) m[0], (float) m[1], (float) m[2], (float) m[3],
            (float) m[4], (float) m[5], worldUv ? 1 : 0, 0,
        });
        return records.size() - 1;
    }

    /**
     * Give every shape the indices of its faces' records.
     *
     * A shape with a blend material or vertex colour is left out: those cost a {@code pow} per
     * channel and are the likeliest place for the two sides to drift, so they keep the CPU's
     * pixels and the comparison counts them as not done rather than as wrong.
     *
     * A face only gets a record of its own when it really differs from one already made. A shape
     * carrying its mesh's coordinates needs exactly one for all three faces: they differ in
     * nothing but {@code worldUv}, and only a side ever reads that - a top or a bottom is a
     * plane, and a plane's image is always placed by where the ray lands in the world
     * ({@code Renderer.flatImg}). A tile-textured shape shares one between its side and its
     * bottom, which are the same texture at the same size. That is what brings Haven's
     * 929,404 shapes down from two and a half million records to under a million.
     */
    GpuMaterials(World world, GpuTextures images) {
        Gl.context();
        for (World.Shape s : world.shapes) {
            if (s.imgB != null || s.vc != null) continue;
            if (s.img != null) {
                s.gpuSide = s.gpuTop = s.gpuBottom =
                        record(images, s.img, s.uv, 0, s.kind != World.Kind.SEG);
            } else {
                int side = record(images, s.tex, null, s.ts, false);
                s.gpuSide = side;
                s.gpuBottom = side;
                s.gpuTop = s.topTex == s.tex && s.topTs == s.ts
                        ? side : record(images, s.topTex, null, s.topTs, false);
            }
        }
        table = new GpuTable(TEXELS, records.size());
        upload();
    }

    private void upload() {
        texture = Gl.texture();
        Gl.activeTexture(4);
        Gl.bindTexture(texture);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate((long) table.width() * table.rows() * 4 * Float.BYTES);
            for (int i = 0; i < records.size(); i++) {
                float[] r = records.get(i);
                for (int k = 0; k < r.length; k++)
                    buf.setAtIndex(ValueLayout.JAVA_FLOAT, table.at(i) + k, r[k]);
            }
            Gl.texImage(Gl.RGBA32F, table.width(), table.rows(), Gl.RGBA, Gl.FLOAT, buf);
            Gl.texUnfiltered();
        }
        Gl.check("the material table, %dx%d".formatted(table.width(), table.rows()));
    }

    int count() { return records.size(); }

    void bind() {
        Gl.activeTexture(4);
        Gl.bindTexture(texture);
    }

    void close() { Gl.deleteTexture(texture); }

    /** The four texels of a record and what they mean. */
    String glsl() {
        return "uniform sampler2D materials;\n" + table.glsl("materialAt") + """

            /** One image sample as a multiplier on the shape's colour: Materials.Texture.sampleAt,
             *  with the level pair and the wrap left to the hardware and the lod given explicitly,
             *  because the card's own derivative lod is not the one a strip of samples shares. */
            vec3 imageSample(int rec, vec2 pq, float wRep) {
                ivec2 m = materialAt(rec);
                vec4 a = texelFetch(materials, m, 0);
                vec4 b = texelFetch(materials, m + ivec2(1, 0), 0);
                vec4 c = texelFetch(materials, m + ivec2(2, 0), 0);
                vec4 d = texelFetch(materials, m + ivec2(3, 0), 0);
                vec2 uv = vec2(c.x * pq.x + c.y * pq.y + c.z, c.w * pq.x + d.x * pq.y + d.y);
                float lod = min(a.z - 1.0, log2(max(1.0, wRep * b.w * a.w)));
                return asMultiplier(imageAt(int(a.x), int(a.y), uv, lod), b.rgb);
            }

            bool imageWorldUv(int rec) {
                return texelFetch(materials, materialAt(rec) + ivec2(3, 0), 0).z != 0.0;
            }
            """;
    }
}

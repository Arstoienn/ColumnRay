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

    /** A record, or -1 when this surface is not one the card can draw. */
    /**
     * Off by default, because it does not match yet.
     *
     * The table, the array textures and the shader's strips are all here and school - which has
     * no images at all - is unaffected either way, but on Haven the sampled colour is wrong by
     * up to 206 of 255 over most of the frame. Until that is found, an image-textured surface
     * keeps the CPU's pixels and the comparison counts it as not done. Turn it on with
     * -Dgpu.images=true to work on it.
     */
    static final boolean ENABLED = Boolean.getBoolean("gpu.images");

    private int record(GpuTextures images, Materials.Texture tex, double[] uv, double ts, boolean worldUv) {
        if (tex == null || !ENABLED) return -1;
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
     * Give every shape the indices of its three faces' records.
     *
     * A shape with a blend material or vertex colour is left out: those cost a {@code pow} per
     * channel and are the likeliest place for the two sides to drift, so they keep the CPU's
     * pixels and the comparison counts them as not done rather than as wrong.
     */
    GpuMaterials(World world, GpuTextures images) {
        for (World.Shape s : world.shapes) {
            if (s.imgB != null || s.vc != null) continue;
            boolean worldUv = s.img != null && s.kind != World.Kind.SEG;
            s.gpuSide = s.img != null ? record(images, s.img, s.uv, 0, worldUv)
                    : record(images, s.tex, null, s.ts, false);
            s.gpuTop = s.img != null ? record(images, s.img, s.uv, 0, false)
                    : record(images, s.topTex, null, s.topTs, false);
            s.gpuBottom = s.img != null ? record(images, s.img, s.uv, 0, false)
                    : record(images, s.tex, null, s.ts, false);
        }
        upload();
    }

    private void upload() {
        Gl.context();
        texture = Gl.texture();
        Gl.activeTexture(4);
        Gl.bindTexture(texture);
        int n = Math.max(1, records.size());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate((long) TEXELS * n * 4 * Float.BYTES);
            for (int i = 0; i < records.size(); i++) {
                float[] r = records.get(i);
                for (int k = 0; k < r.length; k++)
                    buf.setAtIndex(ValueLayout.JAVA_FLOAT, (long) i * TEXELS * 4 + k, r[k]);
            }
            Gl.texImage(Gl.RGBA32F, TEXELS, n, Gl.RGBA, Gl.FLOAT, buf);
            Gl.texUnfiltered();
        }
    }

    int count() { return records.size(); }

    void bind() {
        Gl.activeTexture(4);
        Gl.bindTexture(texture);
    }

    void close() { Gl.deleteTexture(texture); }

    static final String GLSL = """
            uniform sampler2D materials;

            /** One image sample as a multiplier on the shape's colour: Materials.Texture.sampleAt,
             *  with the level pair and the wrap left to the hardware and the lod given explicitly,
             *  because the card's own derivative lod is not the one a strip of samples shares. */
            vec3 imageSample(int rec, vec2 pq, float wRep) {
                vec4 a = texelFetch(materials, ivec2(0, rec), 0);
                vec4 b = texelFetch(materials, ivec2(1, rec), 0);
                vec4 c = texelFetch(materials, ivec2(2, rec), 0);
                vec4 d = texelFetch(materials, ivec2(3, rec), 0);
                vec2 uv = vec2(c.x * pq.x + c.y * pq.y + c.z, c.w * pq.x + d.x * pq.y + d.y);
                float lod = min(a.z - 1.0, log2(max(1.0, wRep * b.w * a.w)));
                vec3 texel = imageAt(int(a.x), int(a.y), uv, lod) ;
                return asMultiplier(texel, b.rgb);
            }

            bool imageWorldUv(int rec) { return texelFetch(materials, ivec2(3, rec), 0).z != 0.0; }
            """;
}

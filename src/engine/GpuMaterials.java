package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * A blend material's second layer and a mesh's vertex colour, in a table of their own.
     *
     * Most of Haven's surfaces are one image and six coefficients, and those fit the four texels
     * of a record. A few carry a great deal more: a second image, the height map that sharpens
     * the seam between the two, the vertex alpha that says where the seam runs, and three planes
     * of vertex colour. Widening every record to hold them would cost three times the table for
     * the sake of a minority, so a record keeps one spare slot pointing at this instead, or -1.
     *
     *     imgB  bank layer levels maxDim
     *     imgB  mean.r mean.g mean.b   vb
     *     hmap  bank layer levels maxDim
     *     hmap  mean.r  invert  hasBlend  hasColour
     *     va    g h i -
     *     vc    0 1 2 3
     *     vc    4 5 6 7
     *     vc    8 - - -
     */
    static final int EXTRA_TEXELS = 8;

    private final List<float[]> records = new ArrayList<>();
    private int texture = -1;

    /**
     * Which record each face of each shape uses, by {@code World.Shape.id}.
     *
     * These lived on {@code World.Shape} itself, which was three fields and a good deal of
     * trouble: {@code World} is one of the classes whose code decides a texel, so
     * {@link LightCache} rightly threw away every cached bake whenever one of them changed -
     * half an hour of Haven's light, thrown away by a field that cannot move a texel. Kept here
     * instead, the whole of the card's side of the engine can change without a bake noticing.
     */
    private final int[] sideRec, topRec, bottomRec, alphaRec;

    private final List<float[]> extras = new ArrayList<>();
    private int extraTexture = -1;
    private GpuTable extraTable;

    private final GpuTable table;

    /** The four numbers that say where an image is and how big it is, for a record or an extra. */
    private static float[] where(GpuTextures images, Materials.Texture tex) {
        int[] at = images.at(tex);
        return at == null ? null : new float[] {
            at[0], at[1], tex.levelCount(), Math.max(tex.levelW(0), tex.levelH(0)),
        };
    }

    /**
     * The blend layer and the vertex colour of one shape, or -1 when it has neither. Null when
     * it has one but the card has not got every image it needs, which leaves the whole shape to
     * the CPU rather than drawing it half right.
     */
    private Integer extra(GpuTextures images, World.Shape s) {
        if (s.imgB == null && s.vc == null) return -1;
        float[] b = {0, 0, 1, 1}, h = {0, 0, 1, 1};
        double[] mean = {0, 0, 0}, hMean = {0};
        if (s.imgB != null) {
            float[] bw = where(images, s.imgB), hw = where(images, s.hmap);
            if (bw == null || hw == null) return null;
            b = bw;
            h = hw;
            for (int c = 0; c < 3; c++) mean[c] = s.imgB.mean(c);
            hMean[0] = s.hmap.mean(0);
        }
        double[] va = s.va != null ? s.va : new double[] {0, 0, 0};
        double[] vc = s.vc != null ? s.vc : new double[9];
        extras.add(new float[] {
            b[0], b[1], b[2], b[3],
            (float) mean[0], (float) mean[1], (float) mean[2], (float) s.vb,
            h[0], h[1], h[2], h[3],
            (float) hMean[0], s.inv ? 1 : 0, s.imgB != null ? 1 : 0, s.vc != null ? 1 : 0,
            (float) va[0], (float) va[1], (float) va[2], 0,
            (float) vc[0], (float) vc[1], (float) vc[2], (float) vc[3],
            (float) vc[4], (float) vc[5], (float) vc[6], (float) vc[7],
            (float) vc[8], 0, 0, 0,
        });
        return extras.size() - 1;
    }

    private int record(GpuTextures images, Materials.Texture tex, double[] uv, double ts,
                       boolean worldUv, int extra) {
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
            (float) m[4], (float) m[5], worldUv ? 1 : 0, extra,
        });
        return records.size() - 1;
    }

    /**
     * Give every shape the indices of its faces' records.
     *
     * A shape with a blend material or vertex colour carries an extra as well, and is left to
     * the CPU only when one of the images that needs is not on the card - drawing half of a
     * blend would be worse than not drawing it.
     *
     * A face only gets a record of its own when it really differs from one already made. A shape
     * carrying its mesh's coordinates needs exactly one for its three faces: they differ in
     * nothing but {@code worldUv}, and only a side ever reads that - a top or a bottom is a
     * plane, and a plane's image is always placed by where the ray lands in the world
     * ({@code Renderer.flatImg}). A tile-textured shape shares one between its side and its
     * bottom, which are the same texture at the same size. That is what brings Haven's
     * 929,404 shapes down from two and a half million records to under a million.
     */
    GpuMaterials(World world, GpuTextures images) {
        Gl.context();
        int n = world.shapes.length;
        sideRec = new int[n];
        topRec = new int[n];
        bottomRec = new int[n];
        alphaRec = new int[n];
        Arrays.fill(sideRec, -1);
        Arrays.fill(topRec, -1);
        Arrays.fill(bottomRec, -1);
        Arrays.fill(alphaRec, -1);
        for (World.Shape s : world.shapes) {
            Integer ext = extra(images, s);
            if (ext == null) continue;
            // A cut-out's alpha is an image like any other, read for its first channel alone.
            alphaRec[s.id] = record(images, s.amap, s.uv, 0, false, -1);
            if (s.img != null) {
                sideRec[s.id] = topRec[s.id] = bottomRec[s.id] =
                        record(images, s.img, s.uv, 0, s.kind != World.Kind.SEG, ext);
            } else {
                int rec = record(images, s.tex, null, s.ts, false, ext);
                sideRec[s.id] = rec;
                bottomRec[s.id] = rec;
                topRec[s.id] = s.topTex == s.tex && s.topTs == s.ts
                        ? rec : record(images, s.topTex, null, s.topTs, false, ext);
            }
        }
        table = new GpuTable(TEXELS, records.size());
        extraTable = new GpuTable(EXTRA_TEXELS, extras.size());
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

        extraTexture = Gl.texture();
        Gl.activeTexture(GpuWalls.EXTRA_UNIT);
        Gl.bindTexture(extraTexture);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate((long) extraTable.width() * extraTable.rows() * 4 * Float.BYTES);
            for (int i = 0; i < extras.size(); i++) {
                float[] r = extras.get(i);
                for (int k = 0; k < r.length; k++)
                    buf.setAtIndex(ValueLayout.JAVA_FLOAT, extraTable.at(i) + k, r[k]);
            }
            Gl.texImage(Gl.RGBA32F, extraTable.width(), extraTable.rows(), Gl.RGBA, Gl.FLOAT, buf);
            Gl.texUnfiltered();
        }
        Gl.check("the blend table, %dx%d".formatted(extraTable.width(), extraTable.rows()));
    }

    int count() { return records.size(); }

    /** The record a face reads, or -1 when the card cannot draw it. */
    int side(World.Shape s) { return sideRec[s.id]; }

    int top(World.Shape s) { return topRec[s.id]; }

    int bottom(World.Shape s) { return bottomRec[s.id]; }

    int alpha(World.Shape s) { return alphaRec[s.id]; }

    void bind() {
        Gl.activeTexture(4);
        Gl.bindTexture(texture);
        Gl.activeTexture(GpuWalls.EXTRA_UNIT);
        Gl.bindTexture(extraTexture);
    }

    void close() {
        Gl.deleteTexture(texture);
        Gl.deleteTexture(extraTexture);
    }

    int extras() { return extras.size(); }

    /** The four texels of a record and what they mean. */
    String glsl() {
        return "uniform sampler2D materials;\nuniform sampler2D blends;\n"
                + table.glsl("materialAt") + extraTable.glsl("blendAt") + """

            /** Materials.Texture.lod, for an image whose levels and size are (levels, maxDim). */
            float lodOf(float levels, float maxDim, float wRep, float lodScale) {
                return min(levels - 1.0, log2(max(1.0, wRep * lodScale * maxDim)));
            }

            /**
             * Materials.blend: how much of a blend material's second layer shows.
             *
             * VALORANT_Blend's own sums, so a path of tiles gives way to grass where Riot painted
             * it to: a vertex alpha raised to 2.2, colour-dodged against the height kept in layer
             * A's alpha, and pulled through a ramp steep enough to turn a soft painted edge into
             * one that follows the tiles.
             */
            float blendAmount(float h, float va, float vb, bool invert) {
                float a = pow(clamp(va, 0.0, 1.0), 2.2);
                if (vb < 0.0) a = 1.0 - a;
                float hp = invert ? 1.0 - h : h, dodge;
                if (a == 0.0) {
                    dodge = 0.0;
                } else {
                    float room = 1.0 - hp;
                    dodge = room <= 0.0 ? 1.0 : min(1.0, a / room);
                }
                float m = -abs(vb) / 2.0;
                return clamp(m + dodge * (1.0 - m), 0.0, 1.0);
            }

            /** Renderer.mappedSample's tail: the second layer mixed in where the mesh's alpha
             *  asks for it, then the vertex colour, which multiplies in linear light while the
             *  image is sRGB - hence the pair of powers, the one place in the frame that needs
             *  them. */
            vec3 blendOn(int ext, vec3 base, vec2 uv, vec2 pq, float wRep, float lodScale) {
                ivec2 e = blendAt(ext);
                vec4 e0 = texelFetch(blends, e, 0);
                vec4 e1 = texelFetch(blends, e + ivec2(1, 0), 0);
                vec4 e2 = texelFetch(blends, e + ivec2(2, 0), 0);
                vec4 e3 = texelFetch(blends, e + ivec2(3, 0), 0);
                if (e3.z != 0.0) {
                    float h = asMultiplier(imageAt(int(e2.x), int(e2.y), uv,
                            lodOf(e2.z, e2.w, wRep, lodScale), e2.z), vec3(e3.x)).r;
                    vec4 e4 = texelFetch(blends, e + ivec2(4, 0), 0);
                    float k = blendAmount(h, e4.x * pq.x + e4.y * pq.y + e4.z, e1.w, e3.y != 0.0);
                    if (k > 0.0) {
                        vec3 second = asMultiplier(imageAt(int(e0.x), int(e0.y), uv,
                                lodOf(e0.z, e0.w, wRep, lodScale), e0.z), e1.rgb);
                        base += (second - base) * k;
                    }
                }
                if (e3.w != 0.0) {
                    vec4 e5 = texelFetch(blends, e + ivec2(5, 0), 0);
                    vec4 e6 = texelFetch(blends, e + ivec2(6, 0), 0);
                    vec4 e7 = texelFetch(blends, e + ivec2(7, 0), 0);
                    vec3 v = max(vec3(0.0), vec3(e5.x, e5.w, e6.z) * pq.x
                            + vec3(e5.y, e6.x, e6.w) * pq.y + vec3(e5.z, e6.y, e7.x));
                    // pow(pow(a, 2.2) * v, 1/2.2) is a * pow(v, 1/2.2), and the CPU's round trip
                    // through the first form costs a float two roundings it cannot afford: the
                    // shorter one lands nearer the double the CPU worked out, not further.
                    base *= pow(v, vec3(1.0 / 2.2));
                }
                return base;
            }

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
                vec3 out0 = asMultiplier(imageAt(int(a.x), int(a.y), uv,
                        lodOf(a.z, a.w, wRep, b.w), a.z), b.rgb);
                int ext = int(d.w);
                return ext < 0 ? out0 : blendOn(ext, out0, uv, pq, wRep, b.w);
            }

            bool imageWorldUv(int rec) {
                return texelFetch(materials, materialAt(rec) + ivec2(3, 0), 0).z != 0.0;
            }
            """;
    }
}

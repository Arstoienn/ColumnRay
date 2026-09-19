package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every image the map uses, on the card.
 *
 * Haven's 452 images come in fifteen distinct sizes - 222 of them 512 square, 75 of them 1024 -
 * so they go into array textures grouped by size rather than a packed atlas. An array layer keeps
 * its own mip chain, exactly as {@link Materials.Texture} does, which is the property a packed
 * atlas would lose: a mip level of an atlas averages across the seam between two unrelated
 * images, and the engine deliberately never lets that happen.
 *
 * The levels are uploaded, never generated. {@code Materials.Texture} area-averages in the
 * PNG's own sRGB values (Materials.java, Texture's constructor); {@code glGenerateMipmap} would
 * average in linear light, and the two differ most exactly where a texture has contrast. The
 * cost is that the whole chain crosses the bus at load - about 600 MB for Haven - and the
 * benefit is that a distant wall is the same colour on both sides.
 *
 * Sampling is left to the hardware. This is the one place in the port where that is right: a
 * mip level's texels are what they are, and GL_LINEAR_MIPMAP_LINEAR with an explicit lod is the
 * same half-texel-offset, wrapping, two-level blend the CPU does by hand. The lod is computed
 * the CPU's way and passed in, because the card's own derivative-based lod is a different
 * number over a strip of anisotropic samples.
 */
final class GpuTextures {
    /** One array texture, and the images that landed in it. */
    private record Bank(int w, int h, int name, int levels, List<Materials.Texture> layers) {}

    private final List<Bank> banks = new ArrayList<>();
    private final IdentityHashMap<Materials.Texture, int[]> where = new IdentityHashMap<>();

    /**
     * A fragment shader may use sixteen samplers in all and the frame already spends six of them
     * on the spans, the blend table, the light atlas and its records, the material table and the
     * masked surfaces, so there is room for ten arrays.
     *
     * Haven's images come in fifteen sizes, which is more banks than there are units. They do
     * not need one each: an array layer's levels are a mip chain, and a smaller image of the
     * same shape is exactly what some level of a bigger one looks like, so a 512 square image
     * can live in levels 1 and down of a 1024 square layer and be sampled by asking for a level
     * one deeper. Wrapping still works, because wrapping is in the normalised coordinates and
     * those do not know which level answered. The cost is the levels above it, which are
     * allocated and never read, so a size is only folded into a bigger bank when the waste of
     * doing so stays under {@link #WASTE_BUDGET}. On Haven that turns fifteen sizes into seven
     * banks for about thirty megabytes, and leaves nothing behind.
     */
    static final int MAX_BANKS = 10;

    /** How much room a folded-in size may waste, over all of its images. Two hundred and
     *  twenty-two 512 square images would waste 700 MB in a 1024 square bank and so keep their
     *  own; the fifty-one 128 square ones waste ten and do not. */
    private static final long WASTE_BUDGET = 32L << 20;

    private int leftToCpu;

    int leftToCpu() { return leftToCpu; }

    /** Roughly what one layer of a w by h array costs, mip chain included. */
    private static long layerBytes(int w, int h) {
        return (long) w * h * 4;
    }

    /** A bank being built: its size, and the images that will be its layers. */
    private static final class Pending {
        final int w, h;
        final List<Materials.Texture> layers = new ArrayList<>();

        Pending(int w, int h) {
            this.w = w;
            this.h = h;
        }
    }

    GpuTextures(List<Materials.Texture> textures) {
        Map<Long, List<Materials.Texture>> bySize = new LinkedHashMap<>();
        for (Materials.Texture t : textures)
            bySize.computeIfAbsent(((long) t.levelW(0) << 32) | t.levelH(0), k -> new ArrayList<>()).add(t);
        List<List<Materials.Texture>> groups = new ArrayList<>(bySize.values());
        // Biggest first, so a bank always exists by the time something that could fold into it
        // comes up.
        groups.sort((x, y) -> Long.compare(layerBytes(y.get(0).levelW(0), y.get(0).levelH(0)),
                layerBytes(x.get(0).levelW(0), x.get(0).levelH(0))));

        List<Pending> pending = new ArrayList<>();
        for (List<Materials.Texture> group : groups) {
            int gw = group.get(0).levelW(0), gh = group.get(0).levelH(0);
            Pending best = null;
            for (Pending p : pending) {
                if (p.w % gw != 0 || p.h % gh != 0 || p.w / gw != p.h / gh) continue;
                int k = p.w / gw;
                if (Integer.bitCount(k) != 1) continue;          // a whole number of mip levels
                if ((long) group.size() * (layerBytes(p.w, p.h) - layerBytes(gw, gh)) > WASTE_BUDGET)
                    continue;
                if (best == null || layerBytes(p.w, p.h) < layerBytes(best.w, best.h)) best = p;
            }
            if (best == null) pending.add(best = new Pending(gw, gh));
            best.layers.addAll(group);
        }
        // The ones that fit are the ones most images are in; anything past that is left to the
        // CPU, where the skip mask counts it rather than letting it draw wrong.
        pending.sort((x, y) -> y.layers.size() - x.layers.size());
        for (int i = MAX_BANKS; i < pending.size(); i++) leftToCpu += pending.get(i).layers.size();

        Gl.context();
        for (Pending p : pending.subList(0, Math.min(MAX_BANKS, pending.size()))) {
            int levels = 32 - Integer.numberOfLeadingZeros(Math.max(p.w, p.h));
            int name = Gl.texture();
            Gl.bindArray(name);
            Gl.arrayLevels(levels, p.w, p.h, p.layers.size());
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate((long) p.w * p.h * 3);
                for (int layer = 0; layer < p.layers.size(); layer++) {
                    Materials.Texture t = p.layers.get(layer);
                    where.put(t, new int[] {banks.size(), layer});
                    int off = levels - t.levelCount();        // how far below the bank's level 0
                    for (int lv = 0; lv < t.levelCount(); lv++) {
                        float[] rgb = t.levelRgb(lv);
                        for (int i = 0; i < rgb.length; i++)
                            buf.setAtIndex(ValueLayout.JAVA_BYTE, i, (byte) Math.round(Math.max(0, Math.min(255, rgb[i]))));
                        Gl.arrayLevel(off + lv, layer, t.levelW(lv), t.levelH(lv), buf);
                    }
                }
            }
            Gl.arrayFiltering(levels);
            Gl.check("an image array, %dx%d in %d layers".formatted(p.w, p.h, p.layers.size()));
            banks.add(new Bank(p.w, p.h, name, levels, p.layers));
        }
    }

    int banks() { return banks.size(); }

    /** Which array and layer an image landed in, or null when the world never uses it. */
    int[] at(Materials.Texture t) { return where.get(t); }

    /** Bind every array on the units after the ones the spans and the lightmaps use. */
    void bind(int firstUnit) {
        for (int i = 0; i < banks.size(); i++) {
            Gl.activeTexture(firstUnit + i);
            Gl.bindArray(banks.get(i).name());
        }
    }

    void close() {
        for (Bank b : banks) Gl.deleteTexture(b.name());
    }

    /** The sampler declarations and the switch that picks one, sized to this world. GLSL 4.1
     *  cannot index an array of samplers by a value that varies across a draw, so the choice
     *  has to be written out, and writing it out from the banks keeps the two in step. */
    String glsl(int firstUnit) {
        StringBuilder decl = new StringBuilder(), pick = new StringBuilder();
        for (int i = 0; i < banks.size(); i++) {
            decl.append("uniform sampler2DArray images").append(i).append(";\n");
            pick.append(i == 0 ? "    if" : "    else if").append(" (bank == ").append(i)
                    .append(") return textureLod(images").append(i)
                    .append(", vec3(uv, float(layer)), lod + float(").append(banks.get(i).levels())
                    .append(") - levels).rgb * 255.0;\n");
        }
        decl.append("""
                /** One image's texels. An image smaller than its bank sits that many levels
                 *  below the bank's own level 0, so the level asked for is shifted by the
                 *  difference between the two chains' lengths. */
                vec3 imageAt(int bank, int layer, vec2 uv, float lod, float levels) {
                """).append(pick).append("""
                    return vec3(255.0);
                }

                /** Materials.Texture.sampleAt's tail: a texel is a multiplier on the shape's own
                 *  colour, not a colour, and the divisor is the image's measured average. */
                vec3 asMultiplier(vec3 texel, vec3 mean) {
                    return vec3(mean.r == 0.0 ? 1.0 : clamp(texel.r / mean.r, 0.0, 4.0),
                                mean.g == 0.0 ? 1.0 : clamp(texel.g / mean.g, 0.0, 4.0),
                                mean.b == 0.0 ? 1.0 : clamp(texel.b / mean.b, 0.0, 4.0));
                }

                /** Materials.Texture.lod: log2 of the footprint in level-0 texels, clamped. */
                float imageLod(float w, float side, float levels) {
                    return min(levels - 1.0, log2(max(1.0, w * side)));
                }
                """);
        return decl.toString();
    }

    /** Every distinct image a world's shapes refer to, in a stable order. */
    static List<Materials.Texture> of(World world) {
        IdentityHashMap<Materials.Texture, Boolean> seen = new IdentityHashMap<>();
        List<Materials.Texture> all = new ArrayList<>();
        for (World.Shape s : world.shapes)
            for (Materials.Texture t : new Materials.Texture[] {s.tex, s.topTex, s.img, s.imgB, s.hmap, s.amap})
                if (t != null && seen.put(t, Boolean.TRUE) == null) all.add(t);
        return all;
    }
}

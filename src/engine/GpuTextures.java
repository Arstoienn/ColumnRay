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
 * Haven's 452 images come in fifteen distinct sizes - 222 of them 512 square, 75 of them 1024,
 * and seven in sizes of their own - so they go into one array texture per size rather than a
 * packed atlas. An array layer keeps
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
    /** One array texture: all the images of one size. */
    private record Bank(int w, int h, int name, int layers) {}

    private final List<Bank> banks = new ArrayList<>();
    private final IdentityHashMap<Materials.Texture, int[]> where = new IdentityHashMap<>();

    /**
     * A fragment shader may use sixteen samplers in all and the frame already spends five on the
     * spans, the lightmaps and the material table, so there is room for ten arrays. Haven's
     * images come in fifteen sizes, but the sizes are not evenly used - 512 square alone accounts
     * for half of them - so the ten largest groups are taken and the stragglers are left to the
     * CPU, where the skip mask counts them rather than letting them draw wrong.
     */
    static final int MAX_BANKS = 10;

    private int leftToCpu;

    int leftToCpu() { return leftToCpu; }

    GpuTextures(List<Materials.Texture> textures) {
        Map<Long, List<Materials.Texture>> bySize = new LinkedHashMap<>();
        for (Materials.Texture t : textures)
            bySize.computeIfAbsent(((long) t.levelW(0) << 32) | t.levelH(0), k -> new ArrayList<>()).add(t);
        List<List<Materials.Texture>> groups = new ArrayList<>(bySize.values());
        groups.sort((x, y) -> y.size() - x.size());
        for (int i = MAX_BANKS; i < groups.size(); i++) leftToCpu += groups.get(i).size();

        Gl.context();
        for (List<Materials.Texture> group : groups.subList(0, Math.min(MAX_BANKS, groups.size()))) {
            Materials.Texture first = group.get(0);
            int w = first.levelW(0), h = first.levelH(0), n = group.size();
            int name = Gl.texture();
            Gl.bindArray(name);
            Gl.arrayLevels(first.levelCount(), w, h, n);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate((long) w * h * 3);
                for (int layer = 0; layer < n; layer++) {
                    Materials.Texture t = group.get(layer);
                    where.put(t, new int[] {banks.size(), layer});
                    for (int lv = 0; lv < t.levelCount(); lv++) {
                        float[] rgb = t.levelRgb(lv);
                        for (int i = 0; i < rgb.length; i++)
                            buf.setAtIndex(ValueLayout.JAVA_BYTE, i, (byte) Math.round(Math.max(0, Math.min(255, rgb[i]))));
                        Gl.arrayLevel(lv, layer, t.levelW(lv), t.levelH(lv), buf);
                    }
                }
            }
            Gl.arrayFiltering(first.levelCount());
            Gl.check("an image array, %dx%d in %d layers".formatted(w, h, n));
            banks.add(new Bank(w, h, name, n));
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
                    .append(", vec3(uv, float(layer)), lod).rgb * 255.0;\n");
        }
        decl.append("""
                vec3 imageAt(int bank, int layer, vec2 uv, float lod) {
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

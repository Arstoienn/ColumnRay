package engine;

/**
 * {@link Materials}'s vertical-face detail, in GLSL.
 *
 * The maps this engine ships are drawn with procedural materials rather than images - school has
 * no texture file at all - so a GPU that cannot evaluate them has nothing to draw. These are the
 * same functions {@code Materials.side} computes, in the same order, with the same constants:
 * the filter width {@code w} still comes from the renderer in metres, and detail still fades into
 * its own mean once a pixel is wider than the detail is.
 *
 * What cannot follow is the last bit. Java works in double and a card works in float, so the two
 * agree to about a part in ten thousand and never exactly. That is why the GPU path is checked
 * against the CPU within a tolerance ({@code GpuMatCheck}) instead of against a golden hash: the
 * CPU renderer stays the reference, and this is measured against it rather than assumed equal.
 *
 * The hash is the one place where the two must agree bit for bit, because a hash that is one off
 * is not a slightly different brick but an entirely different one. Java's int arithmetic wraps
 * and its {@code >>>} is a logical shift, so the port works in {@code uint}, where GLSL is
 * defined to do both.
 */
final class GlMaterials {
    private GlMaterials() {}

    static final String SIDE = """
            float frac(float v) { return v - floor(v); }
            int fl(float v) { return int(floor(v)); }

            float hash(int i, int j) {
                uint h = uint(i) * 73856093u ^ uint(j) * 19349663u;
                h ^= h >> 13u;
                h *= 0x5bd1e995u;
                h ^= h >> 15u;
                return float(h & 1023u) / 1023.0;
            }

            float ease(float t) { return t * t * (3.0 - 2.0 * t); }

            float valueNoise(float u, float v) {
                int i = fl(u), j = fl(v);
                float fu = ease(u - float(i)), fv = ease(v - float(j));
                float a = hash(i, j) + (hash(i + 1, j) - hash(i, j)) * fu;
                float b = hash(i, j + 1) + (hash(i + 1, j + 1) - hash(i, j + 1)) * fu;
                return a + (b - a) * fv;
            }

            float keep(float w, float feature) {
                float k = 2.0 * w / feature;
                return k <= 1.0 ? 1.0 : (k >= 2.0 ? 0.0 : 2.0 - k);
            }

            float fadeTo(float detail, float mean, float w, float feature) {
                float k = keep(w, feature);
                return k >= 1.0 ? detail : mean + (detail - mean) * k;
            }

            float noiseAt(float u, float v, float cells, float w) {
                return fadeTo(valueNoise(u * cells, v * cells), 0.5, w, 1.0 / cells);
            }

            float cellShade(float h, float size, float w) { return fadeTo(h, 0.5, w, size); }

            float lines(bool inLine, float dark, float base, float cover, float width, float w) {
                return fadeTo(inLine ? dark : base, cover * dark + (1.0 - cover) * base, w, width);
            }

            // Materials.CONCRETE 0, PLASTER 1, BRICK 2, WOOD 3, STONE 4, METAL 5, TILE 6,
            // WATER 7, GRASS 8, LEAF 9, BOARD 10, PANEL 11, TERRACOTTA 12. The three left out
            // have no vertical detail and come back as a plain 1, exactly as on the CPU.
            float side(int m, float u, float v, float w) {
                if (m == 0) {
                    float grain = 0.88 + 0.08 * noiseAt(u, v, 2.0, w) + 0.04 * noiseAt(u, v, 7.0, w);
                    return lines(frac(u / 1.2) < 0.02 || frac(v / 0.6) < 0.03, 0.8, grain, 0.05, 0.018, w);
                }
                if (m == 1) {
                    if (v < 0.12) return 0.6;
                    return 0.93 + 0.05 * noiseAt(u, v, 4.0, w);
                }
                if (m == 2) {
                    int row = fl(v * 4.0);
                    float bu = u * 2.0 + float(row & 1) * 0.5;
                    float brick = 0.85 + 0.15 * cellShade(hash(fl(bu), row), 0.25, w);
                    return lines(frac(bu) < 0.05 || frac(v * 4.0) < 0.1, 0.6, brick, 0.145, 0.025, w);
                }
                if (m == 3) {
                    int plank = fl(v * 6.0);
                    float grain = 0.8 + 0.2 * cellShade(
                            valueNoise(u * 1.5 + float(plank) * 0.37, float(plank) + 0.5), 0.67, w);
                    return lines(frac(v * 6.0) < 0.08, 0.55, grain, 0.08, 0.013, w);
                }
                if (m == 4) {
                    int row = fl(v * 2.5);
                    float bu = u * 1.5 + float(row) * 0.5;
                    float block = 0.8 + 0.2 * cellShade(hash(fl(bu), row), 0.4, w);
                    return lines(frac(bu) < 0.04 || frac(v * 2.5) < 0.06, 0.65, block, 0.098, 0.024, w);
                }
                if (m == 5) {
                    float ribs = 0.8 + 0.15 * fadeTo(abs(sin(u * 9.0)), 0.64, w, 0.35);
                    return lines(frac(v * 8.0) < 0.06 && v > 1.4 && v < 1.8, 0.5, ribs, 0.06, 0.0075, w);
                }
                if (m == 6) {
                    return lines(frac(u * 3.0) < 0.05 || frac(v * 3.0) < 0.05, 0.7, 0.95, 0.098, 0.017, w);
                }
                if (m == 9) {
                    return 0.55 + 0.3 * noiseAt(u, v, 6.0, w) + 0.15 * noiseAt(u, v, 19.0, w);
                }
                if (m == 10) { return 0.9 + 0.1 * noiseAt(u, v, 12.0, w); }
                if (m == 12) {
                    float clay = 0.9 + 0.1 * cellShade(hash(fl(u * 5.0), fl(v * 4.0)), 0.2, w);
                    return lines(frac(v * 4.0) < 0.08, 0.7, clay, 0.08, 0.02, w);
                }
                return 1.0;
            }
            """;
}

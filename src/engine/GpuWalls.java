package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The wall half of a frame, drawn on the card.
 *
 * It is handed {@link GpuSpans} - the intervals the CPU's columns painted - and fills exactly
 * those pixels, with the same anisotropic filter the CPU runs and the same shading afterwards.
 * Everything that decides what is visible stays where it was: one ray a column, the grid walk,
 * the shape tests, the interval list. This only colours in.
 *
 * Every pixel finds its own span by walking its column's short list, which is why the spans are
 * laid out one column to a texture row. A column has a handful of wall intervals, so the walk is
 * a few iterations and no sorting or binary search earns its keep.
 *
 * The shading is a port of {@code Renderer.shade} and the grade around it, down to truncating
 * rather than rounding into the tone curve. It cannot be bit-exact - float against double - so
 * {@code GpuCheck} measures how far apart the two pictures are instead of hashing them.
 */
final class GpuWalls {
    private static final String VERT = """
            #version 330 core
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private final int program, target, spanTex, countTex, w, h, columns;
    private final MemorySegment spanBuf, countBuf, back;

    GpuWalls(Arena arena, int w, int h, int columns) {
        this.w = w;
        this.h = h;
        this.columns = columns;
        Gl.context();

        target = Gl.texture();
        Gl.bindTexture(target);
        Gl.texImage(Gl.RGBA8, w, h, Gl.RGBA, Gl.UNSIGNED_BYTE, MemorySegment.NULL);
        Gl.texUnfiltered();
        Gl.bindFramebuffer(Gl.framebuffer());
        Gl.attach(target);
        Gl.bindVertexArray(Gl.vertexArray());
        Gl.viewport(w, h);

        int spanW = GpuSpans.MAX_PER_COLUMN * GpuSpans.TEXELS;
        spanTex = Gl.texture();
        Gl.activeTexture(0);
        Gl.bindTexture(spanTex);
        Gl.texImage(Gl.RGBA32F, spanW, columns, Gl.RGBA, Gl.FLOAT, MemorySegment.NULL);
        Gl.texUnfiltered();
        countTex = Gl.texture();
        Gl.activeTexture(1);
        Gl.bindTexture(countTex);
        Gl.texImage(Gl.RGBA32F, columns, 1, Gl.RGBA, Gl.FLOAT, MemorySegment.NULL);
        Gl.texUnfiltered();

        program = Gl.program(VERT, fragment());
        Gl.useProgram(program);
        Gl.uniform(program, "spans", 0);
        Gl.uniform(program, "counts", 1);
        Gl.uniform(program, "height", (float) h);
        Gl.uniform(program, "satBoost", (float) Renderer.satBoost);
        Gl.uniform(program, "lift", (float) Renderer.lift);

        spanBuf = arena.allocate((long) columns * GpuSpans.MAX_PER_COLUMN * GpuSpans.FLOATS * Float.BYTES);
        countBuf = arena.allocate((long) columns * 4 * Float.BYTES);
        back = arena.allocate((long) w * h * 4);
    }

    /** Draw one frame's worth of spans and bring it back. Pixels no span covers stay zero. */
    void draw(GpuSpans spans, int[] into, double eye, double horizon) {
        MemorySegment.copy(spans.data(), 0, spanBuf, ValueLayout.JAVA_FLOAT, 0, spans.data().length);
        int[] count = spans.count();
        for (int x = 0; x < columns; x++) countBuf.setAtIndex(ValueLayout.JAVA_FLOAT, (long) x * 4, count[x]);
        Gl.activeTexture(0);
        Gl.bindTexture(spanTex);
        Gl.texSubImage(GpuSpans.MAX_PER_COLUMN * GpuSpans.TEXELS, columns, Gl.RGBA, Gl.FLOAT, spanBuf);
        Gl.activeTexture(1);
        Gl.bindTexture(countTex);
        Gl.texSubImage(columns, 1, Gl.RGBA, Gl.FLOAT, countBuf);
        Gl.useProgram(program);
        Gl.uniform(program, "eye", (float) eye);
        Gl.uniform(program, "hz", (float) horizon);
        Gl.clear();
        Gl.drawFullScreen();
        Gl.finish();
        Gl.readPixels(w, h, back);
        MemorySegment.copy(back, ValueLayout.JAVA_INT, 0, into, 0, w * h);
    }

    /** The material tables, built into the shader rather than sent as uniforms: they never change,
     *  and a table that came out of {@link Materials} itself cannot drift from it. */
    private static String tables() {
        StringBuilder detail = new StringBuilder(), mean = new StringBuilder();
        for (int m = 0; m < 13; m++) {
            // A material with no detail at all reads 0 and is always faded; one whose detail has no
            // single size (plaster's skirting board) reads -1 and never is. Materials says both with
            // a 0 and a NaN, which GLSL has no use for.
            boolean faded0 = Materials.sideFaded(m, 0);
            double mn = Materials.sideMean(m);
            detail.append(m == 0 ? "" : ", ").append(faded0 ? "0.0" : Double.isNaN(mn) ? "-1.0" : featureOf(m));
            mean.append(m == 0 ? "" : ", ").append(Double.isNaN(mn) ? "1.0" : (float) mn);
        }
        return "const float SIDE_DETAIL[13] = float[13](" + detail + ");\n"
                + "const float SIDE_MEAN[13] = float[13](" + mean + ");\n";
    }

    /** The feature size Materials fades this material's side detail over, read back out of it by
     *  finding the width at which it declares itself faded. */
    private static String featureOf(int m) {
        double lo = 1e-6, hi = 1e6;
        for (int i = 0; i < 80; i++) {                     // sideFaded is monotone in w: bisect it
            double mid = Math.sqrt(lo * hi);
            if (Materials.sideFaded(m, mid)) hi = mid; else lo = mid;
        }
        return Float.toString((float) hi);                 // faded when 2w/f >= 2, so f = w at the edge
    }

    private static String fragment() {
        return """
                #version 330 core
                uniform sampler2D spans;
                uniform sampler2D counts;
                uniform float height, satBoost, lift, eye, hz;
                out vec4 frag;
                %s
                %s
                bool sideFaded(int m, float w) {
                    float f = SIDE_DETAIL[m];
                    if (f == 0.0) return true;
                    if (f < 0.0) return false;
                    return 2.0 * w / f >= 2.0;
                }

                /** Renderer.sideTex: sample along the strip, each sample filtered to its narrow side. */
                float sideTex(int m, float u, float z, float narrow, float square) {
                    float along = narrow / square;
                    int n = int(min(8.0, ceil(along / narrow)));
                    if (n <= 1) return sideFaded(m, narrow) ? SIDE_MEAN[m] : side(m, u, z, narrow);
                    float stp = along / float(n), w = max(narrow, stp);
                    if (sideFaded(m, w)) return SIDE_MEAN[m];
                    float sum = 0.0;
                    for (int i = 0; i < n; i++)
                        sum += side(m, u + (float(i) + 0.5 - float(n) * 0.5) * stp, z, w);
                    return sum / float(n);
                }

                /** Renderer.tone: the table, as the curve that built it, and truncating like it. */
                float tone(float v) {
                    float i = floor(clamp(v, 0.0, 2047.0));
                    if (i <= 200.0) return i;
                    return floor(200.0 + 55.0 * (1.0 - exp(-(i - 200.0) / 55.0)) + 0.5);
                }

                /** Renderer.shade and the grade in Renderer.rgb, in that order. */
                vec3 shade(float packed, float k) {
                    vec3 c = vec3(floor(packed / 65536.0),
                                  floor(mod(packed / 256.0, 256.0)),
                                  mod(packed, 256.0)) * k;
                    if (satBoost != 1.0) {
                        float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
                        c = y + (c - y) * satBoost;
                    }
                    if (lift != 0.0) c = lift * 255.0 + c * (1.0 - lift);
                    return vec3(tone(c.r), tone(c.g), tone(c.b)) / 255.0;
                }

                void main() {
                    int col = int(gl_FragCoord.x);
                    // glReadPixels hands back the bottom row first, and the renderer's row 0 is
                    // the top one, so the two flips cancel: shade framebuffer row j as row j and
                    // the array that comes back is already the right way up.
                    int row = int(gl_FragCoord.y);
                    int n = int(texelFetch(counts, ivec2(col, 0), 0).r);
                    for (int s = 0; s < n; s++) {
                        vec4 a = texelFetch(spans, ivec2(s * 3, col), 0);
                        if (row < int(a.y) || row >= int(a.z)) continue;
                        vec4 b = texelFetch(spans, ivec2(s * 3 + 1, col), 0);
                        vec4 c = texelFetch(spans, ivec2(s * 3 + 2, col), 0);
                        float z = eye - (float(row) + 0.5 - hz) * c.x;   // Renderer's own formula
                        frag = vec4(shade(c.z, sideTex(int(a.w), b.x, z, c.x, c.y) * b.w), 1.0);
                        return;
                    }
                    discard;
                }
                """.formatted(GlMaterials.SIDE, tables());
    }
}

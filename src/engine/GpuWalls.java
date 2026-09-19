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
final class GpuWalls implements AutoCloseable {
    private static final String VERT = """
            #version 330 core
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private final int program, target, frame, spanTex, countTex, w, h, columns;
    private final MemorySegment spanBuf, countBuf, back;
    private final Arena own;

    /** For a window, which needs the buffers to outlive the call that made them. */
    GpuWalls(int w, int h, int columns) {
        this(Arena.ofShared(), w, h, columns, true);
    }

    private GpuLights lights;

    /** The baked lightmaps, or null for the flat model. Spans say which of the two they are. */
    void setLights(GpuLights l) { lights = l; }

    GpuWalls(Arena arena, int w, int h, int columns) {
        this(arena, w, h, columns, false);
    }

    private GpuWalls(Arena arena, int w, int h, int columns, boolean owns) {
        this.own = owns ? arena : null;
        this.w = w;
        this.h = h;
        this.columns = columns;
        Gl.context();

        target = Gl.texture();
        Gl.bindTexture(target);
        Gl.texImage(Gl.RGBA8, w, h, Gl.RGBA, Gl.UNSIGNED_BYTE, MemorySegment.NULL);
        Gl.texUnfiltered();
        frame = Gl.framebuffer();
        Gl.bindFramebuffer(frame);
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
        Gl.uniform(program, "lightAtlas", 2);
        Gl.uniform(program, "lightRecords", 3);
        Gl.uniform(program, "height", (float) h);
        Gl.uniform(program, "satBoost", (float) Renderer.satBoost);
        Gl.uniform(program, "lift", (float) Renderer.lift);

        spanBuf = arena.allocate((long) columns * GpuSpans.MAX_PER_COLUMN * GpuSpans.FLOATS * Float.BYTES);
        countBuf = arena.allocate((long) columns * 4 * Float.BYTES);
        back = arena.allocate((long) w * h * 4);
    }

    @Override public void close() {
        Gl.deleteTexture(target);
        Gl.deleteTexture(spanTex);
        Gl.deleteTexture(countTex);
        Gl.deleteFramebuffer(frame);
        if (own != null) own.close();
    }

    /** Draw one frame's worth of spans and bring it back. Pixels no span covers stay zero. */
    void draw(GpuSpans spans, int[] into, Renderer.Camera cam, double horizon, double focal, int viewH) {
        MemorySegment.copy(spans.data(), 0, spanBuf, ValueLayout.JAVA_FLOAT, 0, spans.data().length);
        int[] count = spans.count();
        for (int x = 0; x < columns; x++) countBuf.setAtIndex(ValueLayout.JAVA_FLOAT, (long) x * 4, count[x]);
        Gl.activeTexture(0);
        Gl.bindTexture(spanTex);
        Gl.texSubImage(GpuSpans.MAX_PER_COLUMN * GpuSpans.TEXELS, columns, Gl.RGBA, Gl.FLOAT, spanBuf);
        Gl.activeTexture(1);
        Gl.bindTexture(countTex);
        Gl.texSubImage(columns, 1, Gl.RGBA, Gl.FLOAT, countBuf);
        if (lights != null) lights.bind();
        Gl.bindFramebuffer(frame);
        Gl.viewport(w, h);
        Gl.useProgram(program);
        Gl.uniform(program, "eye", (float) cam.eye);
        Gl.uniform(program, "hz", (float) horizon);
        Gl.uniform(program, "viewH", (float) viewH);
        Gl.uniform(program, "foc", (float) focal);
        Gl.uniform(program, "halfW", w / 2.0f);
        Gl.uniform(program, "camX", (float) cam.x);
        Gl.uniform(program, "camY", (float) cam.y);
        Gl.uniform(program, "dirX", (float) cam.dirX);
        Gl.uniform(program, "dirY", (float) cam.dirY);
        Gl.uniform(program, "fogOn", Renderer.fogOn ? 1f : 0f);
        Gl.uniform(program, "maxDist", (float) Renderer.MAX_DIST);
        Gl.clear();
        Gl.drawFullScreen();
        Gl.finish();
        Gl.readPixels(w, h, back);
        MemorySegment.copy(back, ValueLayout.JAVA_INT, 0, into, 0, w * h);
    }

    /** The material tables, built into the shader rather than sent as uniforms: they never change,
     *  and a table that came out of {@link Materials} itself cannot drift from it. */
    private static String tables() {
        return table("SIDE", true) + table("FLAT", false);
    }

    private static String table(String name, boolean side) {
        StringBuilder detail = new StringBuilder(), mean = new StringBuilder();
        for (int m = 0; m < 13; m++) {
            // A material with no detail at all reads 0 and is always faded; one whose detail has no
            // single size - plaster's skirting board, which cells of a ceiling are lit - reads -1
            // and never is. Materials says both with a 0 and a NaN, which GLSL has no use for.
            boolean faded0 = side ? Materials.sideFaded(m, 0) : Materials.flatFaded(m, 0);
            double mn = side ? Materials.sideMean(m) : Materials.flatMean(m);
            detail.append(m == 0 ? "" : ", ").append(faded0 ? "0.0" : Double.isNaN(mn) ? "-1.0" : featureOf(m, side));
            mean.append(m == 0 ? "" : ", ").append(Double.isNaN(mn) ? "1.0" : (float) mn);
        }
        return "const float " + name + "_DETAIL[13] = float[13](" + detail + ");\n"
                + "const float " + name + "_MEAN[13] = float[13](" + mean + ");\n";
    }

    /** The feature size Materials fades this material's detail over, read back out of it by
     *  finding the width at which it declares itself faded. */
    private static String featureOf(int m, boolean side) {
        double lo = 1e-6, hi = 1e6;
        for (int i = 0; i < 80; i++) {                     // faded is monotone in w: bisect it
            double mid = Math.sqrt(lo * hi);
            if (side ? Materials.sideFaded(m, mid) : Materials.flatFaded(m, mid)) hi = mid; else lo = mid;
        }
        return Float.toString((float) hi);                 // faded when 2w/f >= 2, so f = w at the edge
    }

    private static String fragment() {
        return """
                #version 330 core
                uniform sampler2D spans;
                uniform sampler2D counts;
                uniform float height, viewH, satBoost, lift, eye, hz, foc, halfW, camX, camY, dirX, dirY;
                uniform float fogOn, maxDist;
                float rayX, rayY, dk;
                out vec4 frag;
                %s
                %s
                %s
                bool faded(float f, float w) {
                    if (f == 0.0) return true;
                    if (f < 0.0) return false;
                    return 2.0 * w / f >= 2.0;
                }

                float fog(float t) { return fogOn != 0.0 ? max(0.3, 1.0 - t / 45.0) : 1.0; }

                /** Renderer.sideTex: sample along the strip, each sample filtered to its narrow side. */
                float sideTex(int m, float u, float z, float narrow, float square) {
                    float along = narrow / square;
                    int n = int(min(8.0, ceil(along / narrow)));
                    if (n <= 1) return faded(SIDE_DETAIL[m], narrow) ? SIDE_MEAN[m] : side(m, u, z, narrow);
                    float stp = along / float(n), w = max(narrow, stp);
                    if (faded(SIDE_DETAIL[m], w)) return SIDE_MEAN[m];
                    float sum = 0.0;
                    for (int i = 0; i < n; i++)
                        sum += side(m, u + (float(i) + 0.5 - float(n) * 0.5) * stp, z, w);
                    return sum / float(n);
                }

                /** Renderer.flatTex: the strip runs away from the eye, so it is walked in distance. */
                float flatTex(int m, float t, int row) {
                    float narrow = t * dk / foc;
                    float d = abs(float(row) + 0.5 - hz);
                    float along = d < 1e-6 ? 1e30 : t * dk / d;
                    int n = int(min(8.0, ceil(along / narrow)));
                    float w = max(narrow, along / float(n));
                    if (faded(FLAT_DETAIL[m], w)) return FLAT_MEAN[m];
                    if (n <= 1) return flatAt(m, camX + rayX * t, camY + rayY * t, w);
                    float stp = t / (d * float(n));
                    float sum = 0.0;
                    for (int i = 0; i < n; i++) {
                        float tt = t + (float(i) + 0.5 - float(n) * 0.5) * stp;
                        sum += flatAt(m, camX + rayX * tt, camY + rayY * tt, w);
                    }
                    return sum / float(n);
                }

                /** Renderer.tone: the table, as the curve that built it, and truncating like it. */
                float tone(float v) {
                    float i = floor(clamp(v, 0.0, 2047.0));
                    if (i <= 200.0) return i;
                    return floor(200.0 + 55.0 * (1.0 - exp(-(i - 200.0) / 55.0)) + 0.5);
                }

                /** Renderer.rgb: the grade, then the tone curve. */
                vec3 graded(vec3 c) {
                    if (satBoost != 1.0) {
                        float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
                        c = y + (c - y) * satBoost;
                    }
                    if (lift != 0.0) c = lift * 255.0 + c * (1.0 - lift);
                    return vec3(tone(c.r), tone(c.g), tone(c.b)) / 255.0;
                }

                vec3 unpack(float packed) {
                    return vec3(floor(packed / 65536.0), floor(mod(packed / 256.0, 256.0)), mod(packed, 256.0));
                }

                /** Renderer.shade: the surface's colour times a shading factor, then the grade. */
                vec3 shade(float packed, float k) { return graded(unpack(packed) * k); }

                /** Renderer.shadeL: the same, times a coloured level off a baked lightmap. */
                vec3 shadeL(float packed, float k, vec3 L) { return graded(unpack(packed) * k * L); }

                /** Renderer.sky: a vertical gradient over the view's own height, which is not the
                 *  buffer's once there is supersampling or overscan above the horizon. Below the
                 *  horizon it is Renderer.fillRest's flat haze, which skips the grade entirely. */
                vec3 sky(int row) {
                    if (float(row) >= hz) return vec3(58.0, 60.0, 64.0) / 255.0;
                    float s = clamp((hz - float(row)) / (viewH * 0.9), 0.0, 1.0);
                    return graded(vec3(205.0 - 125.0 * s, 222.0 - 87.0 * s, 238.0 - 28.0 * s));
                }

                void main() {
                    int col = int(gl_FragCoord.x);
                    // The column's ray, worked out the way Column.render does, so the floor lands
                    // on the same square of tile on both sides.
                    float off = (float(col) + 0.5 - halfW) / foc;
                    rayX = dirX - dirY * off;
                    rayY = dirY + dirX * off;
                    dk = 1.0;
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
                        int mat = int(a.w), lm = int(b.z);
                        if (c.w == 0.0) {
                            float z = eye - (float(row) + 0.5 - hz) * c.x;   // Renderer's own formula
                            float f = sideTex(mat, b.x, z, c.x, c.y);
                            frag = vec4(lm < 0
                                    ? shade(c.z, f * b.w)
                                    : shadeL(c.z, f * b.y, lightAt(lm, b.x, z)), 1.0);
                        } else {
                            float t = (eye - b.x) * foc / ((float(row) + 0.5 - hz) * dk + b.y * foc);
                            if (!(t > 0.0) || t > maxDist) {
                                frag = vec4(shade(c.z, 0.3 * b.w), 1.0);
                            } else {
                                float wx = camX + rayX * t, wy = camY + rayY * t, f = fog(t);
                                if (lm < 0) {
                                    frag = vec4(shade(c.z, flatTex(mat, t, row) * b.w * f), 1.0);
                                } else if (emissive(mat, wx, wy)) {
                                    frag = vec4(shade(16774374.0, f), 1.0);   // Renderer.EMISSIVE
                                } else {
                                    frag = vec4(shadeL(c.z, flatTex(mat, t, row) * f,
                                            lightAt(lm, wx, wy)), 1.0);
                                }
                            }
                        }
                        return;
                    }
                    frag = vec4(sky(row), 1.0);        // no span reaches this row: Renderer.fillRest
                }
                """.formatted(GlMaterials.SIDE, GlMaterials.FLAT, tables() + GpuLights.GLSL);
    }
}

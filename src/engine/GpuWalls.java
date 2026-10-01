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
    /**
     * Where each thing the shader reads is bound. There are sixteen units and a fragment shader
     * is promised no more, so every one of them is spoken for: the spans, the blend table, the
     * light atlas and its records, the material table, ten image arrays and the masked surfaces.
     *
     * How many each column holds used to have a unit of its own. It is two numbers a column, so
     * it now rides in the first texel of the column's own row of the span texture instead, which
     * costs a texel a column and buys back the unit the blend table needed.
     */
    static final int EXTRA_UNIT = 1;
    private static final int FIRST_IMAGE_UNIT = 5;
    private static final int MASK_UNIT = 15;

    /** The first texel of a column's row of spans holds its two counts; the spans follow it. */
    private static final int HEADER = 1;
    private static final String VERT = """
            #version 330 core
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private int program;                       // built on the first draw: its text depends on the images
    private int warped;                        // the same, shading straight into the tilted view
    private int vao;
    private final int target, frame, spanTex, maskTex, w, h, columns, tableRows;
    private MemorySegment spanBuf, maskBuf;
    /** The upright frame's storage on the card and here, made the first time an upright frame is
     *  drawn. A window warps on the card and draws straight into the tilted view, so it never needs
     *  either - and tilted 55 degrees each would be a third of a gigabyte. */
    private MemorySegment back;
    private boolean targetMade;
    private final Arena buffers;
    /** How wide the span and mask textures are, in texels: a high-water mark, not a worst case.
     *  School fills eight span slots a column out of five hundred it may have, and holding the
     *  worst case would be a hundred and twenty megabytes of card memory to leave untouched. */
    private int spanCap, maskCap;
    private final Arena own;

    /** For a window, which needs the buffers to outlive the call that made them. tableRows is the
     *  most rows a tilted view drawn by shadeWarped will have, whose table rides in the span
     *  texture under the columns' rows. */
    GpuWalls(int w, int h, int columns, int tableRows) {
        this(Arena.ofShared(), w, h, columns, tableRows, true);
    }

    private GpuLights lights;
    private GpuTextures images;
    private GpuMaterials mats;

    /** The baked lightmaps, or null for the flat model. Spans say which of the two they are. */
    void setLights(GpuLights l) { lights = l; }

    /** The world's images and the records that say how a surface reads them. Both or neither;
     *  a world with no images needs no arrays and the shader is built without them. */
    void setImages(GpuTextures t, GpuMaterials m) {
        images = t;
        mats = m;
    }

    GpuWalls(Arena arena, int w, int h, int columns) {
        this(arena, w, h, columns, 0, false);
    }

    private GpuWalls(Arena arena, int w, int h, int columns, int tableRows, boolean owns) {
        this.own = owns ? arena : null;
        this.buffers = arena;
        this.w = w;
        this.h = h;
        this.columns = columns;
        this.tableRows = tableRows;
        Gl.context();

        target = Gl.texture();
        frame = Gl.framebuffer();
        vao = Gl.vertexArray();
        Gl.bindVertexArray(vao);

        spanTex = Gl.texture();
        Gl.activeTexture(0);
        Gl.bindTexture(spanTex);
        Gl.texUnfiltered();
        maskTex = Gl.texture();
        Gl.activeTexture(MASK_UNIT);
        Gl.bindTexture(maskTex);
        Gl.texUnfiltered();
    }

    /** Give the upright frame its storage, the first time one is drawn. */
    private void makeTarget() {
        if (targetMade) return;
        Gl.bindTexture(target);
        Gl.texImage(Gl.RGBA8, w, h, Gl.RGBA, Gl.UNSIGNED_BYTE, MemorySegment.NULL);
        Gl.texUnfiltered();
        Gl.bindFramebuffer(frame);
        Gl.attach(target);
        back = buffers.allocate((long) w * h * 4);
        targetMade = true;
    }

    /**
     * Where a frame's time goes on the card's side, with -Dgpu.stats=true.
     *
     * Four numbers, because there are four things it does and they fail in different ways:
     * packing the lists out of the renderer's arrays, handing them to the driver, the draw
     * itself, and reading the picture back. The readback is the one to watch - it is a stall by
     * construction, the CPU waiting for a frame it cannot start the next one without.
     */
    private static final boolean STATS = Boolean.getBoolean("gpu.stats");
    private long packNs, uploadNs, drawNs, readNs, frames;

    void stats() {
        if (frames == 0) return;
        System.out.printf("gpu %d frames: pack %.2f ms  upload %.2f  draw %.2f  read back %.2f%n",
                frames, packNs / 1e6 / frames, uploadNs / 1e6 / frames,
                drawNs / 1e6 / frames, readNs / 1e6 / frames);
    }

    /** The shading program, upright or already warped: the same text, one define apart. */
    private int link(boolean warped) {
        int program = Gl.program(VERT, fragment(warped));
        Gl.useProgram(program);
        Gl.uniform(program, "spans", 0);
        Gl.uniform(program, "blends", EXTRA_UNIT);
        Gl.uniform(program, "lightAtlas", 2);
        Gl.uniform(program, "lightRecords", 3);
        Gl.uniform(program, "materials", 4);
        Gl.uniform(program, "masks", MASK_UNIT);
        for (int i = 0; images != null && i < images.banks(); i++)
            Gl.uniform(program, "images" + i, FIRST_IMAGE_UNIT + i);
        Gl.uniform(program, "height", (float) h);
        Gl.uniform(program, "satBoost", (float) Renderer.satBoost);
        Gl.uniform(program, "lift", (float) Renderer.lift);
        return program;
    }

    /** Main builds a new one of these whenever the overscan grows, so everything this made has
     *  to go back - a program and a vertex array as much as the textures. */
    @Override public void close() {
        Gl.deleteTexture(target);
        Gl.deleteTexture(spanTex);
        Gl.deleteTexture(maskTex);
        Gl.deleteFramebuffer(frame);
        Gl.deleteVertexArray(vao);
        if (program != 0) Gl.deleteProgram(program);
        if (warped != 0) Gl.deleteProgram(warped);
        if (own != null) own.close();
    }

    /** Draw one frame's worth of spans and the masked surfaces over them, and bring it back.
     *  Pixels no span covers are the sky, exactly as Renderer.fillRest leaves them. */
    void draw(GpuSpans spans, GpuMasks masks, int[] into, Renderer.Camera cam, double horizon,
              double focal, int viewH) {
        shade(spans, masks, cam, horizon, focal, viewH);
        long t3 = STATS ? System.nanoTime() : 0;
        Gl.readPixels(w, h, back);
        MemorySegment.copy(back, ValueLayout.JAVA_INT, 0, into, 0, w * h);
        if (STATS) readNs += System.nanoTime() - t3;
    }

    /** Draw one frame's worth of spans and the masked surfaces over them into {@link #target},
     *  and leave it there. */
    void shade(GpuSpans spans, GpuMasks masks, Renderer.Camera cam, double horizon,
               double focal, int viewH) {
        shade(spans, masks, cam, horizon, focal, viewH, null, null);
    }

    /**
     * The same, already tilted: every pixel of {@code out} is shaded as the pixel of the upright
     * frame that {@link Warp#apply} would have taken for it, and nothing else is shaded at all.
     *
     * Shading the whole upright frame and then warping it paid for pixels nobody reads. The warp
     * takes one row of the upright frame for each row it makes, and a strip of it narrowed or
     * widened by the stretch; looking up 45 degrees the upright frame is 9.5 times the picture and
     * a tenth of it is ever read. Here the pixel asks which upright pixel it is first, from the
     * table Warp.rows writes for the purpose, and then is shaded as that one - the same span,
     * the same ray, the same arithmetic - so the picture is what shading everything and then
     * sampling it gave, pixel for pixel, at a tenth of the shading.
     *
     * The table rides in the span texture, in rows below the columns': every one of the sixteen
     * texture units a fragment shader is promised is already spoken for (see EXTRA_UNIT).
     */
    void shadeWarped(GpuSpans spans, GpuMasks masks, Renderer.Camera cam, double horizon,
                     double focal, int viewH, Warp warp, GpuWarp out) {
        shade(spans, masks, cam, horizon, focal, viewH, warp, out);
    }

    private void shade(GpuSpans spans, GpuMasks masks, Renderer.Camera cam, double horizon,
                       double focal, int viewH, Warp warp, GpuWarp out) {
        long t0 = STATS ? System.nanoTime() : 0;
        int table = warp == null ? 0 : out.height();     // the table's rows, under the columns'
        if (table > tableRows)
            throw new IllegalStateException("a tilted view of " + table + " rows, with room for " + tableRows);
        if (table == 0) {
            Gl.activeTexture(0);                         // spanTex takes this unit back below
            makeTarget();
        }
        int[] count = spans.count(), maskCount = masks.count();
        int wantSpan = Math.max(1, spans.most()) * GpuSpans.TEXELS + HEADER;
        int wantMask = Math.max(1, masks.most()) * GpuMasks.TEXELS;
        if (wantSpan > spanCap) spanCap = grow(spanTex, 0, wantSpan, columns + tableRows);
        if (wantMask > maskCap) maskCap = grow(maskTex, MASK_UNIT, wantMask, columns);
        if (spanBuf == null || spanCap * 4L * (columns + tableRows) * Float.BYTES > spanBuf.byteSize())
            spanBuf = buffers.allocate((long) spanCap * 4 * (columns + tableRows) * Float.BYTES);
        if (maskBuf == null || maskCap * 4L * columns * Float.BYTES > maskBuf.byteSize())
            maskBuf = buffers.allocate((long) maskCap * 4 * columns * Float.BYTES);
        int spanW = pack(spans.data(), count, spans.perColumn(), GpuSpans.TEXELS,
                spans.most(), spanBuf, HEADER) + HEADER;
        // A table row is two texels. A frame with no spans at all packs nothing and is one texel
        // wide; nothing was written at any other width, so widening it changes no row but the
        // header's, which is written below at whatever width this settles on.
        if (table > 0) spanW = Math.max(spanW, 2);
        int maskW = pack(masks.data(), maskCount, masks.perColumn(), GpuMasks.TEXELS,
                masks.most(), maskBuf, 0);
        for (int x = 0; x < columns; x++) {          // the header: how many of each this column has
            long at = (long) x * spanW * 4;
            spanBuf.setAtIndex(ValueLayout.JAVA_FLOAT, at, count[x]);
            spanBuf.setAtIndex(ValueLayout.JAVA_FLOAT, at + 1, maskCount[x]);
        }
        if (table > 0) warp.rows(spanBuf, (long) columns * spanW * 4, spanW * 4);
        long t1 = STATS ? System.nanoTime() : 0;
        Gl.activeTexture(0);
        Gl.bindTexture(spanTex);
        if (spanW > 0) Gl.texSubImage(spanW, columns + table, Gl.RGBA, Gl.FLOAT, spanBuf);
        Gl.activeTexture(MASK_UNIT);
        Gl.bindTexture(maskTex);
        if (maskW > 0) Gl.texSubImage(maskW, columns, Gl.RGBA, Gl.FLOAT, maskBuf);
        if (program == 0) program = link(false);
        if (table > 0 && warped == 0) warped = link(true);
        if (lights != null) lights.bind();
        if (mats != null) { mats.bind(); images.bind(FIRST_IMAGE_UNIT); }
        int program = table > 0 ? warped : this.program;
        if (table > 0) {
            out.bind();
        } else {
            Gl.bindFramebuffer(frame);
            Gl.viewport(w, h);
        }
        Gl.useProgram(program);
        if (lights != null) lights.levels(program);
        if (table > 0) Gl.uniform(program, "tableAt", columns);
        Gl.uniform(program, "eye", (float) cam.eye);
        Gl.uniform(program, "hz", (float) horizon);
        Gl.uniform(program, "viewH", (float) viewH);
        Gl.uniform(program, "foc", (float) focal);
        Gl.uniform(program, "halfW", w / 2.0f);
        Gl.uniform(program, "viewX", (float) cam.x);
        Gl.uniform(program, "viewY", (float) cam.y);
        Gl.uniform(program, "dirX", (float) cam.dirX);
        Gl.uniform(program, "dirY", (float) cam.dirY);
        Gl.uniform(program, "fogOn", Renderer.fogOn ? 1f : 0f);
        Gl.uniform(program, "skyHorizon", (float) Renderer.skyHorizon[0], (float) Renderer.skyHorizon[1], (float) Renderer.skyHorizon[2]);
        Gl.uniform(program, "skyRise", (float) Renderer.skyRise[0], (float) Renderer.skyRise[1], (float) Renderer.skyRise[2]);
        Gl.uniform(program, "skyBelow", Renderer.skyBelow >> 16 & 255, Renderer.skyBelow >> 8 & 255, Renderer.skyBelow & 255);
        // Per frame, not once with satBoost and lift: the H key throws this one while the game runs.
        Gl.uniform(program, "hdr", Renderer.hdr ? 1f : 0f);
        Gl.uniform(program, "exposure", (float) Renderer.exposure);
        Gl.uniform(program, "maxDist", (float) Renderer.maxDist);
        long t2 = STATS ? System.nanoTime() : 0;
        Gl.clear();
        Gl.drawFullScreen();
        if (STATS) Gl.finish();                  // only to put the draw and the read in separate
        long t3 = STATS ? System.nanoTime() : 0; // columns: glReadPixels synchronises by itself
        if (STATS) {
            packNs += t1 - t0;
            uploadNs += t2 - t1;
            drawNs += t3 - t2;
            frames++;
        }
    }

    /** Make a per-column texture at least this many texels wide, and say how wide it now is.
     *  Doubling rather than fitting exactly, so a frame that grows by one does not reallocate. */
    private int grow(int texture, int unit, int want, int rows) {
        int cap = 8;
        while (cap < want) cap *= 2;
        Gl.activeTexture(unit);
        Gl.bindTexture(texture);
        Gl.texImage(Gl.RGBA32F, cap, rows, Gl.RGBA, Gl.FLOAT, MemorySegment.NULL);
        Gl.texUnfiltered();
        Gl.check("a per-column texture, %dx%d".formatted(cap, rows));
        return cap;
    }

    /**
     * Copy a frame's per-column lists into a buffer as wide as the busiest column, and say how
     * many texels wide that is.
     *
     * The lists are held at their worst case, a few hundred entries a column, and a frame uses a
     * handful of that: eight spans a column on an open view of Haven, against room for five
     * hundred. Uploading the whole array would be forty megabytes a frame of mostly nothing, and
     * it measured as most of the GPU pass. The texture stays its full size, so what is left
     * beyond each column's own entries is simply never read - a column's count is what stops the
     * shader.
     */
    private int pack(float[] from, int[] count, int perColumn, int texels, int most,
                     MemorySegment into, int header) {
        if (most == 0) return 0;
        int floats = texels * 4, stride = (most * texels + header) * 4;
        for (int x = 0; x < columns; x++)
            if (count[x] > 0)
                MemorySegment.copy(from, x * perColumn * floats, into, ValueLayout.JAVA_FLOAT,
                        (long) (x * stride + header * 4) * Float.BYTES, count[x] * floats);
        return most * texels;
    }

    /** The material tables, built into the shader rather than sent as uniforms: they never change,
     *  and a table that came out of {@link Materials} itself cannot drift from it. */
    private static String tables() {
        return table("SIDE", true) + table("FLAT", false);
    }

    private static String table(String name, boolean side) {
        StringBuilder detail = new StringBuilder(), mean = new StringBuilder();
        int n = Materials.count();
        for (int m = 0; m < n; m++) {
            // A material with no detail at all reads 0 and is always faded; one whose detail has no
            // single size - plaster's skirting board, which cells of a ceiling are lit - reads -1
            // and never is. Materials says both with a 0 and a NaN, which GLSL has no use for.
            boolean faded0 = side ? Materials.sideFaded(m, 0) : Materials.flatFaded(m, 0);
            double mn = side ? Materials.sideMean(m) : Materials.flatMean(m);
            detail.append(m == 0 ? "" : ", ").append(faded0 ? "0.0" : Double.isNaN(mn) ? "-1.0" : featureOf(m, side));
            mean.append(m == 0 ? "" : ", ").append(Double.isNaN(mn) ? "1.0" : (float) mn);
        }
        return "const float " + name + "_DETAIL[" + n + "] = float[" + n + "](" + detail + ");\n"
                + "const float " + name + "_MEAN[" + n + "] = float[" + n + "](" + mean + ");\n";
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

    private String fragment(boolean warped) {
        return """
                #version 330 core
                %s
                %s
                uniform sampler2D spans;
                uniform float height, viewH, satBoost, lift, eye, hz, foc, halfW, viewX, viewY, dirX, dirY;
                // Where the ray this pixel is shaded along starts: the eye, or the eye mirrored in a
                // pane while its reflection is shaded (reflectAt). Not uniforms, for that reason.
                float camX, camY;
                uniform float fogOn, maxDist, hdr, exposure;
                uniform vec3 skyHorizon, skyRise, skyBelow;
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

                #ifdef HAS_IMAGES
                /** Renderer.sideImg: the strip runs along the surface, as sideTex's does, unless
                 *  the surface is a slab's edge, which takes one sample where the ray meets it. */
                vec3 sideImage(int rec, float u, float z, float narrow, float square, float t) {
                    float along = narrow / square;
                    if (imageWorldUv(rec))
                        return imageSample(rec, vec2(camX + rayX * t, camY + rayY * t), along);
                    int n = int(min(8.0, ceil(along / narrow)));
                    if (n <= 1) return imageSample(rec, vec2(u, z), narrow);
                    float stp = along / float(n), w = max(narrow, stp);
                    vec3 sum = vec3(0.0);
                    for (int i = 0; i < n; i++)
                        sum += imageSample(rec, vec2(u + (float(i) + 0.5 - float(n) * 0.5) * stp, z), w);
                    return sum / float(n);
                }

                /** Renderer.flatImg: the strip runs away from the eye, walked in distance. */
                vec3 flatImage(int rec, float t, int row) {
                    float narrow = t * dk / foc;
                    float d = abs(float(row) + 0.5 - hz);
                    float along = d < 1e-6 ? 1e30 : t * dk / d;
                    int n = int(min(8.0, ceil(along / narrow)));
                    float w = max(narrow, along / float(n));
                    if (n <= 1) return imageSample(rec, vec2(camX + rayX * t, camY + rayY * t), w);
                    float stp = t / (d * float(n));
                    vec3 sum = vec3(0.0);
                    for (int i = 0; i < n; i++) {
                        float tt = t + (float(i) + 0.5 - float(n) * 0.5) * stp;
                        sum += imageSample(rec, vec2(camX + rayX * tt, camY + rayY * tt), w);
                    }
                    return sum / float(n);
                }
                #endif

                /** Renderer.tone: the table, as the curve that built it, and truncating like it. */
                float tone(float v) {
                    float i = floor(clamp(v, 0.0, 2047.0));
                    if (i <= 200.0) return i;
                    return floor(200.0 + 55.0 * (1.0 - exp(-(i - 200.0) / 55.0)) + 0.5);
                }

                /** Renderer.linOf: what an sRGB number, 0 to 255 and beyond, is as light. */
                float linOf(float v) {
                    if (v <= 0.0) return 0.0;
                    float s = v / 255.0;
                    return s <= 0.04045 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4);
                }

                vec3 linOf3(vec3 c) { return vec3(linOf(c.r), linOf(c.g), linOf(c.b)); }

                /** Renderer.toLevel: light back to an sRGB level, unrounded. */
                float toLevel(float v) {
                    float s = v <= 0.0031308 ? 12.92 * v : 1.055 * pow(v, 1.0 / 2.4) - 0.055;
                    return s * 255.0;
                }

                float level(float v) { return v <= 0.0 ? 0.0 : (v >= 255.0 ? 255.0 : floor(v + 0.5)); }

                /** Renderer.aces. */
                float aces(float x) {
                    return x <= 0.0 ? 0.0 : min(1.0, (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14));
                }

                /** Renderer.hdrRgb: expose, roll off, encode, and only then grade. */
                vec3 hdrGraded(vec3 c) {
                    c *= exposure;
                    vec3 s = vec3(toLevel(aces(c.r)), toLevel(aces(c.g)), toLevel(aces(c.b)));
                    if (satBoost != 1.0) {
                        float y = dot(s, vec3(0.2126, 0.7152, 0.0722));
                        s = y + (s - y) * satBoost;
                    }
                    if (lift != 0.0) s = lift * 255.0 + s * (1.0 - lift);
                    return vec3(level(s.r), level(s.g), level(s.b));
                }

                /** Renderer.rgb: the grade, then the tone curve. */
                vec3 graded(vec3 c) {
                    if (satBoost != 1.0) {
                        float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
                        c = y + (c - y) * satBoost;
                    }
                    if (lift != 0.0) c = lift * 255.0 + c * (1.0 - lift);
                    return vec3(tone(c.r), tone(c.g), tone(c.b));
                }

                /** The three bytes of a colour, out of the float they were multiplied into.
                 *  The argument is not called "packed": that is a reserved word in GLSL, which
                 *  Apple's compiler lets through and Intel's does not. */
                vec3 unpack(float bits) {
                    return vec3(floor(bits / 65536.0), floor(mod(bits / 256.0, 256.0)), mod(bits, 256.0));
                }

                /** Renderer.shade: the surface's colour times a shading factor, then the grade. */
                vec3 shade(float bits, float k) {
                    vec3 c = unpack(bits);
                    return hdr != 0.0 ? hdrGraded(linOf3(c) * k) : graded(c * k);
                }

                /** Renderer.shadeL: the same, times a coloured level off a baked lightmap. */
                vec3 shadeL(float bits, float k, vec3 L) {
                    vec3 c = unpack(bits);
                    return hdr != 0.0 ? hdrGraded(linOf3(c) * k * L) : graded(c * k * L);
                }

                /** Renderer.shade with an image's RGB multipliers in place of a scalar. The image's
                 *  factors go on before the colour is read as light, exactly as on the CPU. */
                vec3 shadeT(float bits, vec3 tex, float k, vec3 L) {
                    vec3 c = unpack(bits);
                    return hdr != 0.0 ? hdrGraded(linOf3(c * tex) * k * L) : graded(c * tex * k * L);
                }

                /** Renderer.sky: a vertical gradient over the view's own height, which is not the
                 *  buffer's once there is supersampling or overscan above the horizon. Below the
                 *  horizon it is Renderer.fillRest's flat haze, which skips the grade entirely.
                 *  Levels, 0 to 255, like everything from graded(): a mask blends over the
                 *  finished pixel with Renderer.mix's byte arithmetic, so the whole of a frame is
                 *  carried at that scale and divided once at the end. */
                vec3 sky(int row) {
                    if (float(row) >= hz) return skyBelow;
                    float s = clamp((hz - float(row)) / (viewH * 0.9), 0.0, 1.0);
                    vec3 c = skyHorizon + skyRise * s;
                    return hdr != 0.0 ? hdrGraded(linOf3(c)) : graded(c);
                }

                /** Renderer.sideColor: what a vertical face is, shaded. Both a span and a
                 *  masked surface blended over one are made of exactly this. */
                vec3 wallColour(int mat, int lm, int rec, float rgb,
                                float u, float z, float narrow, float sq, float k) {
                    vec3 L = lm < 0 ? vec3(1.0) : lightAt(lm, u, z);
                    #ifdef HAS_IMAGES
                    if (rec >= 0) {
                        vec3 tex = sideImage(rec, u, z, narrow, sq, narrow * foc / dk);
                        float d = imageDetail(rec);                  // Renderer.detail
                        if (d > 0.0 && SIDE_DETAIL[mat] >= 0.0)
                            tex *= 1.0 + d * (sideTex(mat, u, z, narrow, sq) / SIDE_MEAN[mat] - 1.0);
                        return shadeT(rgb, tex, k, L);
                    }
                    #endif
                    return shadeT(rgb, vec3(sideTex(mat, u, z, narrow, sq)), k, L);
                }

                /** Renderer.flat's operator: a floor, a ceiling, or a shape's top or bottom, at
                 *  the distance the row puts it. */
                vec3 planeColour(int mat, int lm, int rec, float rgb, float k0, float t, int row) {
                    if (!(t > 0.0) || t > maxDist) return shade(rgb, 0.3 * k0);
                    float wx = camX + rayX * t, wy = camY + rayY * t, f = fog(t);
                    if (lm >= 0 && emissive(mat, wx, wy)) return shade(16774374.0, f);  // EMISSIVE
                    vec3 L = lm < 0 ? vec3(1.0) : lightAt(lm, wx, wy);
                    float k = lm < 0 ? k0 * f : f;
                    #ifdef HAS_IMAGES
                    if (rec >= 0) {
                        vec3 tex = flatImage(rec, t, row);
                        float d = imageDetail(rec);                  // Renderer.detail
                        if (d > 0.0 && FLAT_DETAIL[mat] >= 0.0)
                            tex *= 1.0 + d * (flatTex(mat, t, row) / FLAT_MEAN[mat] - 1.0);
                        return shadeT(rgb, tex, k, L);
                    }
                    #endif
                    return shadeT(rgb, vec3(flatTex(mat, t, row)), k, L);
                }

                %s
                #ifdef WARPED
                /** Warp.apply's choice of upright pixel for this output pixel, from the table
                 *  Warp.rows wrote under the columns' rows of the span texture: the source row,
                 *  the integer part of the row's first x, its fraction, and the stretch in a high
                 *  part i times which is exact in a float and a low part. See Warp.rows. */
                uniform int tableAt;
                ivec2 source() {
                    int i = int(gl_FragCoord.x), j = int(gl_FragCoord.y);
                    vec4 a = texelFetch(spans, ivec2(0, tableAt + j), 0);
                    vec4 b = texelFetch(spans, ivec2(1, tableAt + j), 0);
                    float fi = float(i);
                    float whole = fi * a.w;
                    float lo = floor(whole);
                    float frac = (whole - lo) + a.z + fi * b.x;
                    int x = clamp(int(a.y) + int(lo) + int(floor(frac)), int(b.y), int(b.z));
                    return ivec2(x, int(a.x));
                }
                #else
                ivec2 source() { return ivec2(gl_FragCoord.xy); }
                #endif

                /** One pixel of the column as the renderer painted it, masks and all: what a
                 *  puddle is made of, and what it reflects. wet and wetZ say whether the row is
                 *  a floor with standing water, and at what height. */
                /** The surface the spans tagged tag put in this row, or -1 where none do. */
                vec3 surfaceAt(int col, int row, int tag, out float wet, out float wetZ) {
                    int n = int(texelFetch(spans, ivec2(0, col), 0).x);
                    vec3 colour = vec3(-1.0);          // nothing has claimed this row yet
                    wet = 0.0;
                    wetZ = 0.0;
                    for (int s = 0; s < n; s++) {
                        int at = 1 + s * 4;                  // past the header texel
                        vec4 a = texelFetch(spans, ivec2(at, col), 0);
                        if (row < int(a.y) || row >= int(a.z)) continue;
                        vec4 e = texelFetch(spans, ivec2(at + 3, col), 0);
                        if (int(e.z) != tag) continue;       // another pane's reflection, or the view's
                        vec4 b = texelFetch(spans, ivec2(at + 1, col), 0);
                        vec4 c = texelFetch(spans, ivec2(at + 2, col), 0);
                        int mat = int(a.w), lm = int(b.z), rec = int(e.x);
                        if (c.w == 0.0) {
                            float z = eye - (float(row) + 0.5 - hz) * c.x;   // Renderer's own formula
                            colour = wallColour(mat, lm, rec, c.z, b.x, z, c.x, c.y,
                                    lm < 0 ? b.w : b.y);
                        } else {
                            colour = planeColour(mat, lm, rec, c.z, b.w,
                                    (eye - b.x) * foc / ((float(row) + 0.5 - hz) * dk + b.y * foc), row);
                            wet = e.y;
                            wetZ = b.x;
                        }
                        break;
                    }
                    return colour;
                }

                vec3 pixelAt(int col, int row, out float wet, out float wetZ) {
                    vec3 colour = surfaceAt(col, row, 0, wet, wetZ);
                    if (colour.r < 0.0) colour = sky(row);   // Renderer.fillRest
                    // Renderer.blendMasked, in the same place: over the finished column.
                    return blendMasks(col, row, int(texelFetch(spans, ivec2(0, col), 0).y), colour);
                }

                /** Renderer.mirror, shaded: what the tag-th pane in this column reflects in this
                 *  row, seen from the eye mirrored in it, e, along the mirrored ray, q. */
                vec3 reflectAt(int col, int row, int tag, vec2 e, vec2 q) {
                    float sx = camX, sy = camY, qx = rayX, qy = rayY;
                    camX = e.x; camY = e.y; rayX = q.x; rayY = q.y;
                    float w, wz;
                    vec3 c = surfaceAt(col, row, tag, w, wz);
                    camX = sx; camY = sy; rayX = qx; rayY = qy;
                    return c.r < 0.0 ? sky(row) : c;
                }

                /** Renderer.depthOf: how far away the surface in this row is, infinite for sky. */
                float depthAt(int col, int row) {
                    int n = int(texelFetch(spans, ivec2(0, col), 0).x);
                    for (int s = 0; s < n; s++) {
                        int at = 1 + s * 4;
                        vec4 a = texelFetch(spans, ivec2(at, col), 0);
                        if (row < int(a.y) || row >= int(a.z)) continue;
                        if (int(texelFetch(spans, ivec2(at + 3, col), 0).z) != 0) continue;
                        vec4 c = texelFetch(spans, ivec2(at + 2, col), 0);
                        if (c.w == 0.0) return c.x * foc / dk;
                        vec4 b = texelFetch(spans, ivec2(at + 1, col), 0);
                        float d = (eye - b.x) * foc / ((float(row) + 0.5 - hz) * dk + b.y * foc);
                        return d > 0.0 ? d : 1e30;
                    }
                    return 1e30;
                }

                /** Materials.puddle: how far into a puddle, water above 0. */
                float puddleAt(float x, float y, float cover) {
                    float n = 0.7 * valueNoise(x * 0.35, y * 0.35) + 0.3 * valueNoise(x * 1.3 + 17.0, y * 1.3 + 5.0);
                    float th = 0.5 + (0.5 - cover) * 0.5;
                    return n - th;
                }

                /**
                 * Renderer.mirrored: walk up the column to what the puddle's ray meets - the
                 * lowest row above the puddle that it meets, found span by span rather than row by
                 * row. Asking depthAt of every row went through every span of the column for each,
                 * and a walk up a buffer made tall for the water to reflect (Warp.reachUp) is
                 * thousands of rows: enough to hang the card. A row no span covers is sky and is
                 * never met, and each span is walked from its bottom up with depthAt's own
                 * arithmetic, so what is found is exactly what the row walk found.
                 */
                vec3 mirroredAt(int col, int row, float t, float z) {
                    float K = foc * (eye - z) / (t * dk);
                    int top = int(floor(max(hz - K, -1e6)));
                    int lo = max(top, 0), best = -1;
                    int n = int(texelFetch(spans, ivec2(0, col), 0).x);
                    for (int s = 0; s < n; s++) {
                        int at = 1 + s * 4;
                        vec4 a = texelFetch(spans, ivec2(at, col), 0);
                        int y0 = max(max(int(a.y), lo), best + 1), y1 = min(int(a.z), row);
                        if (y1 <= y0) continue;
                        if (int(texelFetch(spans, ivec2(at + 3, col), 0).z) != 0) continue;
                        vec4 c = texelFetch(spans, ivec2(at + 2, col), 0);
                        vec4 b = texelFetch(spans, ivec2(at + 1, col), 0);
                        for (int yy = y1 - 1; yy >= y0; yy--) {
                            float cc = (float(yy) + 0.5 - hz) / K + 1.0;
                            if (cc <= 0.0) break;
                            float d;
                            if (c.w == 0.0) d = c.x * foc / dk;
                            else {
                                d = (eye - b.x) * foc / ((float(yy) + 0.5 - hz) * dk + b.y * foc);
                                if (!(d > 0.0)) d = 1e30;
                            }
                            float tr = 2.0 * t / cc;
                            if (tr >= d && tr - d <= REFLECT_THICK + 0.1 * d) { best = yy; break; }
                        }
                    }
                    if (best >= 0) {
                        float w, wz;
                        return pixelAt(col, best, w, wz);
                    }
                    return sky(top);
                }

                void main() {
                    camX = viewX;
                    camY = viewY;
                    ivec2 at = source();
                    int col = at.x;
                    // The column's ray, worked out the way Column.render does, so the floor lands
                    // on the same square of tile on both sides.
                    float off = (float(col) + 0.5 - halfW) / foc;
                    rayX = dirX - dirY * off;
                    rayY = dirY + dirX * off;
                    dk = 1.0;
                    // glReadPixels hands back the bottom row first, and the renderer's row 0 is
                    // the top one, so the two flips cancel: shade framebuffer row j as row j and
                    // the array that comes back is already the right way up.
                    int row = at.y;
                    float cover, z;
                    vec3 colour = pixelAt(col, row, cover, z);
                    if (cover > 0.0) {                       // Renderer.reflect
                        float t = (eye - z) * foc / ((float(row) + 0.5 - hz) * dk);
                        if (t > 0.0 && t <= maxDist) {
                            float lvl = cover >= 2.0 ? 1.0 : puddleAt(camX + rayX * t, camY + rayY * t, cover);
                            float wet = cover >= 2.0 ? 1.0 : smoothstep(-0.005, 0.015, lvl);     // Materials.water
                            float damp = cover >= 2.0 ? 1.0 : smoothstep(-0.14, 0.0, lvl);       // Materials.damp
                            if (damp > 0.004) {
                                float v = eye - z, h = t * length(vec2(rayX, rayY));
                                float k = 1.0 - v / sqrt(h * h + v * v);
                                float f0 = cover >= 2.0 ? POOL_F0 : WATER_F0, f = wet * (f0 + (1.0 - f0) * k * k * k * k * k);
                                if (cover < 2.0) colour = mixPixel(colour, vec3(0.0), (WET_RIM * damp + (1.0 - WET_RIM) * wet) * WET_DARK);
                                if (f > 0.004) colour = mixPixel(colour, mirroredAt(col, row, t, z), f);
                            }
                        }
                    }
                    frag = vec4(colour / 255.0, 1.0);
                }
                """.formatted(images == null ? "" : "#define HAS_IMAGES 1", warped ? "#define WARPED 1" : "",
                        GlMaterials.SIDE, GlMaterials.FLAT,
                        tables() + (lights == null ? GpuLights.absent() : lights.glsl())
                                + (images == null ? "" : images.glsl(FIRST_IMAGE_UNIT) + mats.glsl()),
                        GlMaterials.MASK + GpuMasks.GLSL)
                .replace("REFLECT_THICK", Float.toString((float) Renderer.REFLECT_THICK))
                .replace("WATER_F0", Float.toString((float) Renderer.WATER_F0))
                .replace("POOL_F0", Float.toString((float) Renderer.POOL_F0))
                .replace("WET_DARK", Float.toString((float) Renderer.WET_DARK))
                .replace("WET_RIM", Float.toString((float) Renderer.RIM));
    }
}

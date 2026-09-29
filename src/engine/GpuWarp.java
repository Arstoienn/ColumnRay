package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The pitch warp, on the card: {@link Warp#apply} for a frame that never left it.
 *
 * {@link GpuWalls} shades the upright frame into a texture, and until this existed the frame was
 * then read back for the CPU to warp, and uploaded again for the window to show - a round trip that
 * was a quarter of Haven's frame and half of school's. This reads the shaded texture where it
 * lies and writes the tilted view into a texture of its own, which the window, sharing the engine's
 * context, draws straight into its letterbox.
 *
 * It sits beside the CPU warp rather than replacing it. A golden digest is the double renderer's
 * pixels byte for byte, and a screenshot writes depth and albedo the card does not have, so both
 * keep {@link Warp#apply}. What this has to match is that warp's choice of source pixel, and the
 * table {@link Warp#rows} hands over is built so that it does; {@code --gpu-verify} reads this
 * texture back and holds it to the CPU's frame like any other.
 */
final class GpuWarp implements AutoCloseable {
    private static final String VERT = """
            #version 330 core
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    /** Warp.apply, one output pixel at a time. Row j of the output is framebuffer row j, as it is
     *  for GpuWalls, so the texture holds the picture top row first - which is how the window
     *  draws an uploaded one - and a read back comes out the right way up. */
    private static final String FRAG = """
            #version 330 core
            uniform sampler2D upright;
            uniform sampler2D rows;
            out vec4 frag;
            void main() {
                int i = int(gl_FragCoord.x), j = int(gl_FragCoord.y);
                vec4 a = texelFetch(rows, ivec2(0, j), 0);    // source row, base x, its fraction, high k
                vec4 b = texelFetch(rows, ivec2(1, j), 0);    // low k, first and last column allowed
                float fi = float(i);
                float whole = fi * a.w;                       // exact: a.w has eleven bits, i thirteen
                float lo = floor(whole);
                float frac = (whole - lo) + a.z + fi * b.x;
                int x = int(a.y) + int(lo) + int(floor(frac));
                x = clamp(x, int(b.y), int(b.z));
                frag = texelFetch(upright, ivec2(x, int(a.x)), 0);
            }
            """;

    private final int w, h, program, vao, rowsTex, out, frame;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment table;
    private MemorySegment back;

    GpuWarp(int w, int h) {
        this.w = w;
        this.h = h;
        Gl.context();
        program = Gl.program(VERT, FRAG);
        Gl.useProgram(program);
        Gl.uniform(program, "upright", 0);
        Gl.uniform(program, "rows", 1);
        vao = Gl.vertexArray();
        rowsTex = Gl.texture();
        Gl.bindTexture(rowsTex);
        Gl.texUnfiltered();
        // Filtered, because the window scales it into its letterbox exactly as it did the uploaded
        // picture; the warp itself reads its source with texelFetch and is not affected.
        out = Gl.texture();
        Gl.bindTexture(out);
        Gl.texImage(Gl.RGBA8, w, h, Gl.RGBA, Gl.UNSIGNED_BYTE, MemorySegment.NULL);
        Gl.texParam(Gl.TEXTURE_MIN_FILTER, Gl.LINEAR);
        Gl.texParam(Gl.TEXTURE_MAG_FILTER, Gl.LINEAR);
        Gl.texParam(Gl.TEXTURE_WRAP_S, Gl.CLAMP_TO_EDGE);
        Gl.texParam(Gl.TEXTURE_WRAP_T, Gl.CLAMP_TO_EDGE);
        frame = Gl.framebuffer();
        Gl.bindFramebuffer(frame);
        Gl.attach(out);
        table = arena.allocate((long) h * 8 * Float.BYTES);
    }

    int width() { return w; }

    int height() { return h; }

    /** The tilted frame, for the window to draw. */
    int texture() { return out; }

    /** Warp the upright frame in {@code upright} into this one's texture. */
    void apply(int upright, Warp warp) {
        warp.rows(table);
        Gl.activeTexture(1);
        Gl.bindTexture(rowsTex);
        Gl.texImage(Gl.RGBA32F, 2, h, Gl.RGBA, Gl.FLOAT, table);
        Gl.activeTexture(0);
        Gl.bindTexture(upright);
        Gl.bindFramebuffer(frame);
        Gl.viewport(w, h);
        Gl.useProgram(program);
        Gl.bindVertexArray(vao);
        Gl.drawFullScreen();
        // The window draws this from a context of its own. Two contexts share a texture's storage
        // but not their queues, and on macOS a write is only promised to the other one once the
        // writer's commands have been submitted.
        Gl.flush();
    }

    /** Bring the tilted frame back into {@code into}: only for checking it against the CPU's. */
    void read(int[] into) {
        if (back == null) back = arena.allocate((long) w * h * 4);
        Gl.bindFramebuffer(frame);
        Gl.readPixels(w, h, back);
        MemorySegment.copy(back, ValueLayout.JAVA_INT, 0, into, 0, w * h);
    }

    @Override public void close() {
        Gl.deleteTexture(out);
        Gl.deleteTexture(rowsTex);
        Gl.deleteFramebuffer(frame);
        Gl.deleteVertexArray(vao);
        Gl.deleteProgram(program);
        arena.close();
    }
}

package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Where a frame tilted on the card lands: the picture a window shows, never brought back here.
 *
 * {@link GpuWalls#shadeWarped} shades straight into this, each pixel as the upright pixel
 * {@link Warp#apply} would have taken for it, so the frame is tilted without ever existing upright.
 * Until 2026-09-29 the whole upright frame was shaded, read back for the CPU to warp, and uploaded
 * again for the window; then for a day it was shaded whole and warped by a pass here; now only the
 * pixels the warp reads are shaded at all, which looking up 45 degrees is a tenth of them.
 *
 * It sits beside the CPU warp rather than replacing it. A golden digest is the double renderer's
 * pixels byte for byte, and a screenshot writes depth and albedo the card does not have, so both
 * keep {@link Warp#apply}. What the card has to match is that warp's choice of pixel, which the
 * table {@link Warp#rows} hands over is built to; {@code --gpu-verify} reads this texture back and
 * holds it to the CPU's frame like any other.
 */
final class GpuWarp implements AutoCloseable {
    private final int w, h, out, frame;
    private final Arena arena = Arena.ofShared();
    private MemorySegment back;

    GpuWarp(int w, int h) {
        this.w = w;
        this.h = h;
        Gl.context();
        // Filtered, because the window scales it into its letterbox exactly as it did the uploaded
        // picture; nothing reads it here but the window.
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
    }

    int width() { return w; }

    int height() { return h; }

    /** The tilted frame, for the window to draw. */
    int texture() { return out; }

    /** Draw into this from here on. */
    void bind() {
        Gl.bindFramebuffer(frame);
        Gl.viewport(w, h);
    }

    /**
     * Hand the frame over to whoever draws it next. The window draws this from a context of its
     * own; two contexts share a texture's storage but not their queues, and on macOS a write is
     * only promised to the other one once the writer's commands have been submitted.
     */
    void submit() { Gl.flush(); }

    /** Bring the tilted frame back into {@code into}: only for checking it against the CPU's. */
    void read(int[] into) {
        if (back == null) back = arena.allocate((long) w * h * 4);
        Gl.bindFramebuffer(frame);
        Gl.readPixels(w, h, back);
        MemorySegment.copy(back, ValueLayout.JAVA_INT, 0, into, 0, w * h);
    }

    @Override public void close() {
        Gl.deleteTexture(out);
        Gl.deleteFramebuffer(frame);
        arena.close();
    }
}

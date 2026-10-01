package engine;

import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.opengl.GL33C.*;

import java.lang.foreign.MemorySegment;
import org.lwjgl.opengl.GL;

/**
 * The engine's only door to the graphics card.
 *
 * Every call below is one OpenGL entry point, reached through LWJGL. It used to reach them through
 * the FFM API instead - a symbol lookup and a {@code MethodHandle} per function, about two hundred
 * lines of signatures - which worked and was a reasonable thing to write when the engine had no
 * dependencies. It is not worth maintaining beside a binding that is already here for the window.
 *
 * What is deliberately unchanged is this file's own shape: the same forty-odd methods with the same
 * names and arguments, so the hundred and eighty-five places that draw with them did not move. The
 * pixel arguments are still {@link MemorySegment}, because the callers allocate their buffers with
 * an {@code Arena} and LWJGL takes a raw address for exactly this.
 *
 * {@link #context} makes a context with no window behind it, which is now a hidden GLFW window -
 * see {@link Glfw}. Rendering goes into a framebuffer and comes back through {@link #readPixels}.
 * A one-off measurement on 2026-09-19 (the spike that decided the card was worth having) put that trade: at 1080p the read costs 1.8 ms against a CPU frame of 23.7,
 * so it is worth paying until it is not. A window no longer pays it: the pitch warp runs on the
 * card too ({@link GpuWarp}) and the window, made sharing this context, draws the result where it
 * lies. A capture and {@code --gpu-verify} still read back, the one for depth and albedo and the
 * other to compare.
 */
final class Gl {
    static final int TEXTURE_2D = 0x0DE1, FLOAT = 0x1406, RGBA = 0x1908, UNSIGNED_BYTE = 0x1401,
            TRIANGLES = 0x0004, COLOR_BUFFER_BIT = 0x4000, TEXTURE_MAG_FILTER = 0x2800,
            TEXTURE_MIN_FILTER = 0x2801, TEXTURE_WRAP_S = 0x2802, TEXTURE_WRAP_T = 0x2803,
            REPEAT = 0x2901, CLAMP_TO_EDGE = 0x812F, NEAREST = 0x2600, LINEAR = 0x2601,
            LINEAR_MIPMAP_LINEAR = 0x2703, RGBA8 = 0x8058, RGBA32F = 0x8814, RGB = 0x1907, RGB32F = 0x8815, R32I = 0x8235,
            RED_INTEGER = 0x8D94, INT = 0x1404, BGRA = 0x80E1, UNSIGNED_INT_8_8_8_8_REV = 0x8367,
            TEXTURE0 = 0x84C0, FRAGMENT_SHADER = 0x8B30, VERTEX_SHADER = 0x8B31,
            COMPILE_STATUS = 0x8B81, LINK_STATUS = 0x8B82, FRAMEBUFFER = 0x8D40,
            FRAMEBUFFER_COMPLETE = 0x8CD5, COLOR_ATTACHMENT0 = 0x8CE0, RENDERER = 0x1F01,
            VERSION = 0x1F02, TEXTURE_2D_ARRAY = 0x8C1A, TEXTURE_MAX_LEVEL = 0x813D,
            RGB8 = 0x8051, UNPACK_ALIGNMENT = 0x0CF5, MAX_TEXTURE_SIZE = 0x0D33, RGB16F = 0x881B,
            HALF_FLOAT = 0x140B;

    private Gl() {}

    private static boolean current;

    /**
     * A context with no window behind it, made the way this platform makes one.
     *
     * Idempotent: a second context would leave the first one's textures unreachable.
     * {@code GL.createCapabilities} is what binds LWJGL's entry points to the context that is
     * current on this thread, so it has to follow the context and not precede it.
     */
    static void context() {
        if (current) return;
        glfwMakeContextCurrent(Glfw.root());
        GL.createCapabilities();
        current = true;                                          // only once the context is real
    }

    /**
     * Take the thread back after something else made its own context current.
     *
     * A GLFW window shows a frame by drawing into its own context, and whichever was current last
     * is the one the next GL call lands in - so the engine says out loud when it wants its own
     * back. Does nothing before the engine has a context to want. The capabilities are this
     * thread's and were set when the context was made, so only the context itself comes back.
     */
    static void reclaim() {
        if (current) glfwMakeContextCurrent(Glfw.root());
    }

    static int texture() { return glGenTextures(); }

    static int framebuffer() { return glGenFramebuffers(); }

    static int vertexArray() { return glGenVertexArrays(); }

    static void activeTexture(int unit) { glActiveTexture(TEXTURE0 + unit); }

    static void bindTexture(int name) { glBindTexture(TEXTURE_2D, name); }

    static void texImage(int internalFormat, int w, int h, int format, int type, MemorySegment pixels) {
        nglTexImage2D(TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, pixels.address());
    }

    static void texSubImage(int w, int h, int format, int type, MemorySegment pixels) {
        nglTexSubImage2D(TEXTURE_2D, 0, 0, 0, w, h, format, type, pixels.address());
    }

    static void texParam(int what, int how) { glTexParameteri(TEXTURE_2D, what, how); }

    /** Nearest in both directions and no wrapping: a data texture, not a picture. */
    static void texUnfiltered() {
        texParam(TEXTURE_MIN_FILTER, NEAREST);
        texParam(TEXTURE_MAG_FILTER, NEAREST);
        texParam(TEXTURE_WRAP_S, CLAMP_TO_EDGE);
        texParam(TEXTURE_WRAP_T, CLAMP_TO_EDGE);
    }

    static void generateMipmap() { glGenerateMipmap(TEXTURE_2D); }

    static void bindArray(int name) { glBindTexture(TEXTURE_2D_ARRAY, name); }

    /** Make room for every mip level of an array texture. glTexStorage3D would say this in one
     *  call, but it is GL 4.2 and macOS stops at 4.1. */
    static void arrayLevels(int levels, int w, int h, int layers, int internal, int type) {
        glPixelStorei(UNPACK_ALIGNMENT, 1);                      // three of whatever a texel is
        for (int i = 0; i < levels; i++)
            nglTexImage3D(TEXTURE_2D_ARRAY, i, internal, Math.max(1, w >> i), Math.max(1, h >> i),
                    layers, 0, RGB, type, 0L);
    }

    static void arrayLevel(int level, int layer, int w, int h, MemorySegment pixels, int type) {
        nglTexSubImage3D(TEXTURE_2D_ARRAY, level, 0, 0, layer, w, h, 1, RGB, type, pixels.address());
    }

    /** Trilinear and wrapping: the same filter Materials.Level does by hand. */
    static void arrayFiltering(int levels) {
        glTexParameteri(TEXTURE_2D_ARRAY, TEXTURE_MIN_FILTER, LINEAR_MIPMAP_LINEAR);
        glTexParameteri(TEXTURE_2D_ARRAY, TEXTURE_MAG_FILTER, LINEAR);
        glTexParameteri(TEXTURE_2D_ARRAY, TEXTURE_WRAP_S, REPEAT);
        glTexParameteri(TEXTURE_2D_ARRAY, TEXTURE_WRAP_T, REPEAT);
        glTexParameteri(TEXTURE_2D_ARRAY, TEXTURE_MAX_LEVEL, levels - 1);
    }

    static void bindFramebuffer(int name) { glBindFramebuffer(FRAMEBUFFER, name); }

    /** Point the bound framebuffer at a texture, and fail loudly if the driver will not have it. */
    static void attach(int texture) {
        glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, texture, 0);
        if (glCheckFramebufferStatus(FRAMEBUFFER) != FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("incomplete framebuffer");
    }

    static void bindVertexArray(int name) { glBindVertexArray(name); }

    static void viewport(int w, int h) { glViewport(0, 0, w, h); }

    static void clear() { glClear(COLOR_BUFFER_BIT); }

    static void useProgram(int program) { glUseProgram(program); }

    /** The full-screen triangle the passes are drawn with; its vertices come from gl_VertexID. */
    static void drawFullScreen() { glDrawArrays(TRIANGLES, 0, 3); }

    static void finish() { glFinish(); }

    static void flush() { glFlush(); }

    static void readPixels(int w, int h, MemorySegment into) {
        nglReadPixels(0, 0, w, h, BGRA, UNSIGNED_INT_8_8_8_8_REV, into.address());
    }

    /** The same, off a float target: four floats a pixel, for checking a shader against Java. */
    static void readFloats(int w, int h, MemorySegment into) {
        nglReadPixels(0, 0, w, h, RGBA, FLOAT, into.address());
    }

    static int program(String vertex, String fragment) {
        int p = glCreateProgram();
        glAttachShader(p, shader(VERTEX_SHADER, vertex));
        glAttachShader(p, shader(FRAGMENT_SHADER, fragment));
        glLinkProgram(p);
        if (glGetProgrami(p, LINK_STATUS) == 0)
            throw new IllegalStateException("link: " + glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int kind, String source) {
        int s = glCreateShader(kind);
        glShaderSource(s, source);
        glCompileShader(s);
        if (glGetShaderi(s, COMPILE_STATUS) == 0)
            throw new IllegalStateException("shader: " + glGetShaderInfoLog(s));
        return s;
    }

    static void uniform(int program, String name, int value) {
        glUniform1i(glGetUniformLocation(program, name), value);
    }

    static void uniform(int program, String name, float value) {
        glUniform1f(glGetUniformLocation(program, name), value);
    }

    static void uniform(int program, String name, float[] values) {
        glUniform1fv(glGetUniformLocation(program, name), values);
    }

    static void deleteTexture(int name) { glDeleteTextures(name); }

    static void deleteFramebuffer(int name) { glDeleteFramebuffers(name); }

    static void deleteVertexArray(int name) { glDeleteVertexArrays(name); }

    static void deleteProgram(int name) { glDeleteProgram(name); }

    static int error() { return glGetError(); }

    /** The widest and tallest a texture may be on this card - 16384 on an M3. A table that asks
     *  for more is not refused loudly: the call fails, every fetch from it reads zero, and the
     *  frame comes back plausibly shaded and wrong. Ask first instead. */
    static int maxTextureSize() { return glGetInteger(MAX_TEXTURE_SIZE); }

    /** Fail where the mistake was made, for the calls that can be given something impossible. */
    static void check(String what) {
        int e = error();
        if (e != 0) throw new IllegalStateException("GL error 0x%x on %s".formatted(e, what));
    }

    static String version() { return string(VERSION); }

    static String device() { return string(RENDERER); }

    private static String string(int what) {
        String s = glGetString(what);
        return s == null ? "?" : s;
    }
}

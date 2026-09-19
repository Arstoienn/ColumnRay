package engine;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The engine's only door to the graphics card.
 *
 * It links against OpenGL.framework through the FFM API, the same way {@link Keys} reaches the
 * keyboard: a C ABI needs no more than a symbol lookup, where Metal would put an objc_msgSend
 * under every call. OpenGL is deprecated on macOS and capped at 4.1, but the hybrid renderer
 * asks for nothing newer than a full-screen pass over a mipmapped texture, and this file is the
 * only place that would have to change if Apple ever removes it.
 *
 * {@link #context} makes a context with no window behind it. Rendering goes into a framebuffer
 * and comes back through {@link #readPixels}, so the window stays the Swing window the engine
 * already has and nothing here needs a CALayer. {@code GpuSpike} measured that trade: at 1080p
 * the read costs 1.8 ms against a CPU frame of 23.7, so it is worth paying until it is not.
 *
 * Every call goes through {@code invokeWithArguments}, which boxes and adapts where
 * {@code invokeExact} would not. It costs about a microsecond a call against frames measured in
 * milliseconds, and it keeps this file a list of GL entry points rather than a list of
 * hand-written signatures.
 */
final class Gl {
    static final int TEXTURE_2D = 0x0DE1, FLOAT = 0x1406, RGBA = 0x1908, UNSIGNED_BYTE = 0x1401,
            TRIANGLES = 0x0004, COLOR_BUFFER_BIT = 0x4000, TEXTURE_MAG_FILTER = 0x2800,
            TEXTURE_MIN_FILTER = 0x2801, TEXTURE_WRAP_S = 0x2802, TEXTURE_WRAP_T = 0x2803,
            REPEAT = 0x2901, CLAMP_TO_EDGE = 0x812F, NEAREST = 0x2600, LINEAR = 0x2601,
            LINEAR_MIPMAP_LINEAR = 0x2703, RGBA8 = 0x8058, RGBA32F = 0x8814, R32I = 0x8235,
            RED_INTEGER = 0x8D94, INT = 0x1404, BGRA = 0x80E1, UNSIGNED_INT_8_8_8_8_REV = 0x8367,
            TEXTURE0 = 0x84C0, FRAGMENT_SHADER = 0x8B30, VERTEX_SHADER = 0x8B31,
            COMPILE_STATUS = 0x8B81, LINK_STATUS = 0x8B82, FRAMEBUFFER = 0x8D40,
            FRAMEBUFFER_COMPLETE = 0x8CD5, COLOR_ATTACHMENT0 = 0x8CE0, RENDERER = 0x1F01,
            VERSION = 0x1F02;

    /** CGL pixel format attributes: a core profile, accelerated, and no drawable at all. */
    private static final int PFA_ACCELERATED = 73, PFA_PROFILE = 99,
            PROFILE_4_1_CORE = 0x4100, PROFILE_3_2_CORE = 0x3200;

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final AddressLayout PTR = ValueLayout.ADDRESS;

    private static final SymbolLookup LIB = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/OpenGL.framework/OpenGL", Arena.global());
    private static final Linker LINKER = Linker.nativeLinker();

    private static MethodHandle fn(String name, FunctionDescriptor sig) {
        return LINKER.downcallHandle(LIB.find(name).orElseThrow(
                () -> new IllegalStateException("OpenGL.framework has no " + name)), sig);
    }

    private static Object call(MethodHandle h, Object... args) {
        try {
            return h.invokeWithArguments(args);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            throw new IllegalStateException("native call failed", t);
        }
    }

    private static final MethodHandle CHOOSE_PF = fn("CGLChoosePixelFormat", FunctionDescriptor.of(I32, PTR, PTR, PTR));
    private static final MethodHandle CREATE_CTX = fn("CGLCreateContext", FunctionDescriptor.of(I32, PTR, PTR, PTR));
    private static final MethodHandle SET_CTX = fn("CGLSetCurrentContext", FunctionDescriptor.of(I32, PTR));

    private static final MethodHandle GEN_TEXTURES = fn("glGenTextures", FunctionDescriptor.ofVoid(I32, PTR));
    private static final MethodHandle BIND_TEXTURE = fn("glBindTexture", FunctionDescriptor.ofVoid(I32, I32));
    private static final MethodHandle TEX_IMAGE = fn("glTexImage2D",
            FunctionDescriptor.ofVoid(I32, I32, I32, I32, I32, I32, I32, I32, PTR));
    private static final MethodHandle TEX_SUB_IMAGE = fn("glTexSubImage2D",
            FunctionDescriptor.ofVoid(I32, I32, I32, I32, I32, I32, I32, I32, PTR));
    private static final MethodHandle TEX_PARAM = fn("glTexParameteri", FunctionDescriptor.ofVoid(I32, I32, I32));
    private static final MethodHandle GEN_MIPMAP = fn("glGenerateMipmap", FunctionDescriptor.ofVoid(I32));
    private static final MethodHandle ACTIVE_TEXTURE = fn("glActiveTexture", FunctionDescriptor.ofVoid(I32));
    private static final MethodHandle GEN_FB = fn("glGenFramebuffers", FunctionDescriptor.ofVoid(I32, PTR));
    private static final MethodHandle BIND_FB = fn("glBindFramebuffer", FunctionDescriptor.ofVoid(I32, I32));
    private static final MethodHandle FB_TEXTURE = fn("glFramebufferTexture2D",
            FunctionDescriptor.ofVoid(I32, I32, I32, I32, I32));
    private static final MethodHandle FB_STATUS = fn("glCheckFramebufferStatus", FunctionDescriptor.of(I32, I32));
    private static final MethodHandle GEN_VAO = fn("glGenVertexArrays", FunctionDescriptor.ofVoid(I32, PTR));
    private static final MethodHandle BIND_VAO = fn("glBindVertexArray", FunctionDescriptor.ofVoid(I32));
    private static final MethodHandle CREATE_SHADER = fn("glCreateShader", FunctionDescriptor.of(I32, I32));
    private static final MethodHandle SHADER_SOURCE = fn("glShaderSource", FunctionDescriptor.ofVoid(I32, I32, PTR, PTR));
    private static final MethodHandle COMPILE_SHADER = fn("glCompileShader", FunctionDescriptor.ofVoid(I32));
    private static final MethodHandle GET_SHADER = fn("glGetShaderiv", FunctionDescriptor.ofVoid(I32, I32, PTR));
    private static final MethodHandle SHADER_LOG = fn("glGetShaderInfoLog", FunctionDescriptor.ofVoid(I32, I32, PTR, PTR));
    private static final MethodHandle CREATE_PROGRAM = fn("glCreateProgram", FunctionDescriptor.of(I32));
    private static final MethodHandle ATTACH = fn("glAttachShader", FunctionDescriptor.ofVoid(I32, I32));
    private static final MethodHandle LINK = fn("glLinkProgram", FunctionDescriptor.ofVoid(I32));
    private static final MethodHandle GET_PROGRAM = fn("glGetProgramiv", FunctionDescriptor.ofVoid(I32, I32, PTR));
    private static final MethodHandle PROGRAM_LOG = fn("glGetProgramInfoLog", FunctionDescriptor.ofVoid(I32, I32, PTR, PTR));
    private static final MethodHandle USE_PROGRAM = fn("glUseProgram", FunctionDescriptor.ofVoid(I32));
    private static final MethodHandle UNIFORM_LOC = fn("glGetUniformLocation", FunctionDescriptor.of(I32, I32, PTR));
    private static final MethodHandle UNIFORM_1I = fn("glUniform1i", FunctionDescriptor.ofVoid(I32, I32));
    private static final MethodHandle UNIFORM_1F = fn("glUniform1f", FunctionDescriptor.ofVoid(I32, ValueLayout.JAVA_FLOAT));
    private static final MethodHandle VIEWPORT = fn("glViewport", FunctionDescriptor.ofVoid(I32, I32, I32, I32));
    private static final MethodHandle CLEAR = fn("glClear", FunctionDescriptor.ofVoid(I32));
    private static final MethodHandle DRAW_ARRAYS = fn("glDrawArrays", FunctionDescriptor.ofVoid(I32, I32, I32));
    private static final MethodHandle FINISH = fn("glFinish", FunctionDescriptor.ofVoid());
    private static final MethodHandle READ_PIXELS = fn("glReadPixels",
            FunctionDescriptor.ofVoid(I32, I32, I32, I32, I32, I32, PTR));
    private static final MethodHandle GET_STRING = fn("glGetString", FunctionDescriptor.of(PTR, I32));
    private static final MethodHandle GET_ERROR = fn("glGetError", FunctionDescriptor.of(I32));

    private Gl() {}

    /** A 4.1 core context, or a 3.2 one if the machine will not give 4.1. Neither has a window. */
    static void context() {
        try (Arena arena = Arena.ofConfined()) {
            for (int profile : new int[] {PROFILE_4_1_CORE, PROFILE_3_2_CORE}) {
                MemorySegment attrs = arena.allocateFrom(I32, PFA_PROFILE, profile, PFA_ACCELERATED, 0);
                MemorySegment pf = arena.allocate(PTR), n = arena.allocate(I32), ctx = arena.allocate(PTR);
                if ((int) call(CHOOSE_PF, attrs, pf, n) != 0 || pf.get(PTR, 0).equals(MemorySegment.NULL)) continue;
                // The context outlives this arena: CGL owns it, the arena only held the out-parameter.
                if ((int) call(CREATE_CTX, pf.get(PTR, 0), MemorySegment.NULL, ctx) != 0) continue;
                if ((int) call(SET_CTX, ctx.get(PTR, 0)) == 0) return;
            }
        }
        throw new IllegalStateException("no offscreen OpenGL context");
    }

    static int texture() { return name(GEN_TEXTURES); }

    static int framebuffer() { return name(GEN_FB); }

    static int vertexArray() { return name(GEN_VAO); }

    private static int name(MethodHandle gen) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment n = arena.allocate(I32);
            call(gen, 1, n);
            return n.get(I32, 0);
        }
    }

    static void activeTexture(int unit) { call(ACTIVE_TEXTURE, TEXTURE0 + unit); }

    static void bindTexture(int name) { call(BIND_TEXTURE, TEXTURE_2D, name); }

    static void texImage(int internalFormat, int w, int h, int format, int type, MemorySegment pixels) {
        call(TEX_IMAGE, TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, pixels);
    }

    static void texSubImage(int w, int h, int format, int type, MemorySegment pixels) {
        call(TEX_SUB_IMAGE, TEXTURE_2D, 0, 0, 0, w, h, format, type, pixels);
    }

    static void texParam(int what, int how) { call(TEX_PARAM, TEXTURE_2D, what, how); }

    /** Nearest in both directions and no wrapping: a data texture, not a picture. */
    static void texUnfiltered() {
        texParam(TEXTURE_MIN_FILTER, NEAREST);
        texParam(TEXTURE_MAG_FILTER, NEAREST);
        texParam(TEXTURE_WRAP_S, CLAMP_TO_EDGE);
        texParam(TEXTURE_WRAP_T, CLAMP_TO_EDGE);
    }

    static void generateMipmap() { call(GEN_MIPMAP, TEXTURE_2D); }

    static void bindFramebuffer(int name) { call(BIND_FB, FRAMEBUFFER, name); }

    /** Point the bound framebuffer at a texture, and fail loudly if the driver will not have it. */
    static void attach(int texture) {
        call(FB_TEXTURE, FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, texture, 0);
        if ((int) call(FB_STATUS, FRAMEBUFFER) != FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("incomplete framebuffer");
    }

    static void bindVertexArray(int name) { call(BIND_VAO, name); }

    static void viewport(int w, int h) { call(VIEWPORT, 0, 0, w, h); }

    static void clear() { call(CLEAR, COLOR_BUFFER_BIT); }

    static void useProgram(int program) { call(USE_PROGRAM, program); }

    /** The full-screen triangle the passes are drawn with; its vertices come from gl_VertexID. */
    static void drawFullScreen() { call(DRAW_ARRAYS, TRIANGLES, 0, 3); }

    static void finish() { call(FINISH); }

    static void readPixels(int w, int h, MemorySegment into) {
        call(READ_PIXELS, 0, 0, w, h, BGRA, UNSIGNED_INT_8_8_8_8_REV, into);
    }

    static int program(String vertex, String fragment) {
        int p = (int) call(CREATE_PROGRAM);
        call(ATTACH, p, shader(VERTEX_SHADER, vertex));
        call(ATTACH, p, shader(FRAGMENT_SHADER, fragment));
        call(LINK, p);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ok = arena.allocate(I32);
            call(GET_PROGRAM, p, LINK_STATUS, ok);
            if (ok.get(I32, 0) == 0) throw new IllegalStateException("link: " + log(PROGRAM_LOG, p));
        }
        return p;
    }

    private static int shader(int kind, String source) {
        int s = (int) call(CREATE_SHADER, kind);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocateFrom(source), list = arena.allocate(PTR);
            list.set(PTR, 0, text);
            call(SHADER_SOURCE, s, 1, list, MemorySegment.NULL);
            call(COMPILE_SHADER, s);
            MemorySegment ok = arena.allocate(I32);
            call(GET_SHADER, s, COMPILE_STATUS, ok);
            if (ok.get(I32, 0) == 0) throw new IllegalStateException("shader: " + log(SHADER_LOG, s));
        }
        return s;
    }

    private static String log(MethodHandle get, int object) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocate(4096), len = arena.allocate(I32);
            call(get, object, 4096, len, text);
            return text.getString(0);
        }
    }

    static void uniform(int program, String name, int value) {
        call(UNIFORM_1I, location(program, name), value);
    }

    static void uniform(int program, String name, float value) {
        call(UNIFORM_1F, location(program, name), value);
    }

    private static int location(int program, String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) call(UNIFORM_LOC, program, arena.allocateFrom(name));
        }
    }

    static int error() { return (int) call(GET_ERROR); }

    static String version() { return string(VERSION); }

    static String device() { return string(RENDERER); }

    private static String string(int what) {
        MemorySegment s = (MemorySegment) call(GET_STRING, what);
        return s.equals(MemorySegment.NULL) ? "?" : s.reinterpret(Long.MAX_VALUE).getString(0);
    }
}

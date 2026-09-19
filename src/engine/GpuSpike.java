package engine;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;

/**
 * Would a GPU pay for itself here, and would reading the frame back undo it?
 *
 * The renderer's frame is dominated by texture filtering - eight texel reads and a blend for
 * every pixel, in Java - and that is the one thing a GPU has dedicated hardware for. The plan
 * this measures is the hybrid one: the CPU keeps casting one ray per column and works out the
 * intervals, and the GPU is handed those intervals and does nothing but fill vertical strips
 * and filter. Nothing about the column constraint changes.
 *
 * But the engine draws into a Swing window, and giving a Swing window a GL context on macOS
 * means CALayer and Objective-C. The cheap way round it is to render into an offscreen buffer
 * and read the pixels back into the int[] the window already blits - which only works if the
 * read costs less than the filtering it saves. That is the question, and this asks it without
 * touching the renderer, the window, or anything the golden frames can see:
 *
 *     ./build.sh && java --enable-native-access=ALL-UNNAMED -cp out engine.GpuSpike [WxH] [frames]
 *
 * Three phases are timed apart, because the verdict turns on which of them is slow:
 *
 *     upload   a frame's worth of per-column data into a texture, the CPU's half of a hybrid
 *     draw     a full-screen pass that samples a mipmapped texture, trilinear, the GPU's half
 *     read     glReadPixels into native memory and on into an int[], the price of staying in Swing
 *
 * It links against OpenGL.framework - deprecated on macOS and capped at 4.1, but a C ABI, so
 * SymbolLookup reaches it the same way {@link Keys} reaches the keyboard, and 4.1 has everything
 * the hybrid needs. Metal would cost every call an objc_msgSend, and buys nothing until Apple
 * actually removes GL.
 */
final class GpuSpike {
    private static final int GL_TEXTURE_2D = 0x0DE1, GL_FLOAT = 0x1406, GL_RGBA = 0x1908,
            GL_UNSIGNED_BYTE = 0x1401, GL_TRIANGLES = 0x0004, GL_COLOR_BUFFER_BIT = 0x4000,
            GL_TEXTURE_MAG_FILTER = 0x2800, GL_TEXTURE_MIN_FILTER = 0x2801,
            GL_TEXTURE_WRAP_S = 0x2802, GL_TEXTURE_WRAP_T = 0x2803, GL_REPEAT = 0x2901,
            GL_NEAREST = 0x2600, GL_LINEAR = 0x2601, GL_LINEAR_MIPMAP_LINEAR = 0x2703,
            GL_RGBA8 = 0x8058, GL_RGBA32F = 0x8814, GL_BGRA = 0x80E1,
            GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367, GL_TEXTURE0 = 0x84C0,
            GL_FRAGMENT_SHADER = 0x8B30, GL_VERTEX_SHADER = 0x8B31, GL_COMPILE_STATUS = 0x8B81,
            GL_LINK_STATUS = 0x8B82, GL_FRAMEBUFFER = 0x8D40, GL_FRAMEBUFFER_COMPLETE = 0x8CD5,
            GL_COLOR_ATTACHMENT0 = 0x8CE0, GL_RENDERER = 0x1F01, GL_VERSION = 0x1F02;

    /** CGL pixel format attributes: a core profile, accelerated, and no drawable at all. */
    private static final int CGL_PFA_ACCELERATED = 73, CGL_PFA_PROFILE = 99,
            CGL_PROFILE_4_1_CORE = 0x4100, CGL_PROFILE_3_2_CORE = 0x3200;

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    private static final AddressLayout PTR = ValueLayout.ADDRESS;

    private static final SymbolLookup GL = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/OpenGL.framework/OpenGL", Arena.global());
    private static final Linker LINKER = Linker.nativeLinker();

    /**
     * Every call below goes through {@code invokeWithArguments}, which boxes and adapts on each
     * call where {@code invokeExact} would not. That costs about a microsecond, against phases
     * measured in milliseconds, and it keeps this file a list of GL calls rather than a list of
     * hand-written signatures.
     */
    private static MethodHandle fn(String name, FunctionDescriptor sig) {
        return LINKER.downcallHandle(GL.find(name).orElseThrow(
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

    // CGL: a context with no window behind it.
    private static final MethodHandle CHOOSE_PF = fn("CGLChoosePixelFormat",
            FunctionDescriptor.of(I32, PTR, PTR, PTR));
    private static final MethodHandle CREATE_CTX = fn("CGLCreateContext",
            FunctionDescriptor.of(I32, PTR, PTR, PTR));
    private static final MethodHandle SET_CTX = fn("CGLSetCurrentContext",
            FunctionDescriptor.of(I32, PTR));

    // The handful of GL entry points a full-screen textured pass needs.
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
    private static final MethodHandle SHADER_SOURCE = fn("glShaderSource",
            FunctionDescriptor.ofVoid(I32, I32, PTR, PTR));
    private static final MethodHandle COMPILE_SHADER = fn("glCompileShader", FunctionDescriptor.ofVoid(I32));
    private static final MethodHandle GET_SHADER = fn("glGetShaderiv", FunctionDescriptor.ofVoid(I32, I32, PTR));
    private static final MethodHandle SHADER_LOG = fn("glGetShaderInfoLog",
            FunctionDescriptor.ofVoid(I32, I32, PTR, PTR));
    private static final MethodHandle CREATE_PROGRAM = fn("glCreateProgram", FunctionDescriptor.of(I32));
    private static final MethodHandle ATTACH = fn("glAttachShader", FunctionDescriptor.ofVoid(I32, I32));
    private static final MethodHandle LINK = fn("glLinkProgram", FunctionDescriptor.ofVoid(I32));
    private static final MethodHandle GET_PROGRAM = fn("glGetProgramiv", FunctionDescriptor.ofVoid(I32, I32, PTR));
    private static final MethodHandle USE_PROGRAM = fn("glUseProgram", FunctionDescriptor.ofVoid(I32));
    private static final MethodHandle UNIFORM_LOC = fn("glGetUniformLocation", FunctionDescriptor.of(I32, I32, PTR));
    private static final MethodHandle UNIFORM_1I = fn("glUniform1i", FunctionDescriptor.ofVoid(I32, I32));
    private static final MethodHandle VIEWPORT = fn("glViewport", FunctionDescriptor.ofVoid(I32, I32, I32, I32));
    private static final MethodHandle CLEAR = fn("glClear", FunctionDescriptor.ofVoid(I32));
    private static final MethodHandle DRAW_ARRAYS = fn("glDrawArrays", FunctionDescriptor.ofVoid(I32, I32, I32));
    private static final MethodHandle FINISH = fn("glFinish", FunctionDescriptor.ofVoid());
    private static final MethodHandle READ_PIXELS = fn("glReadPixels",
            FunctionDescriptor.ofVoid(I32, I32, I32, I32, I32, I32, PTR));
    private static final MethodHandle GET_STRING = fn("glGetString", FunctionDescriptor.of(PTR, I32));
    private static final MethodHandle GET_ERROR = fn("glGetError", FunctionDescriptor.of(I32));

    /** A full-screen triangle with no vertex buffer, its uv scaled so most of it is minified. */
    private static final String VERT = """
            #version 330 core
            out vec2 uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                uv = p * 12.0;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /** One trilinear sample per pixel: the work the CPU renderer spends its frame on. */
    private static final String FRAG = """
            #version 330 core
            in vec2 uv;
            uniform sampler2D atlas;
            uniform sampler2D columns;
            out vec4 frag;
            void main() {
                vec4 c = texture(columns, vec2(gl_FragCoord.x / 1280.0, 0.5));
                frag = texture(atlas, uv + c.xy * 0.001);
            }
            """;

    public static void main(String[] args) {
        int w = 1280, h = 720, frames = 300;
        for (String a : args) {
            if (a.contains("x")) {
                String[] p = a.split("x");
                w = Integer.parseInt(p[0]);
                h = Integer.parseInt(p[1]);
            } else {
                frames = Integer.parseInt(a);
            }
        }
        try (Arena arena = Arena.ofConfined()) {
            context(arena);
            System.out.printf("GL %s on %s%n", str(GET_STRING, GL_VERSION), str(GET_STRING, GL_RENDERER));
            run(arena, w, h, frames);
        }
    }

    /** A 4.1 core context, or a 3.2 one if the machine will not give 4.1. Neither has a window. */
    private static void context(Arena arena) {
        for (int profile : new int[] {CGL_PROFILE_4_1_CORE, CGL_PROFILE_3_2_CORE}) {
            MemorySegment attrs = arena.allocateFrom(I32,
                    CGL_PFA_PROFILE, profile, CGL_PFA_ACCELERATED, 0);
            MemorySegment pf = arena.allocate(PTR), n = arena.allocate(I32), ctx = arena.allocate(PTR);
            if ((int) call(CHOOSE_PF, attrs, pf, n) != 0 || pf.get(PTR, 0).equals(MemorySegment.NULL)) continue;
            if ((int) call(CREATE_CTX, pf.get(PTR, 0), MemorySegment.NULL, ctx) != 0) continue;
            if ((int) call(SET_CTX, ctx.get(PTR, 0)) == 0) return;
        }
        throw new IllegalStateException("no offscreen OpenGL context");
    }

    private static void run(Arena arena, int w, int h, int frames) {
        // The target: an RGBA8 texture on a framebuffer, standing in for the window's int[].
        int target = genName(arena, GEN_TEXTURES);
        call(BIND_TEXTURE, GL_TEXTURE_2D, target);
        call(TEX_IMAGE, GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, MemorySegment.NULL);
        call(TEX_PARAM, GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        call(TEX_PARAM, GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        int fb = genName(arena, GEN_FB);
        call(BIND_FB, GL_FRAMEBUFFER, fb);
        call(FB_TEXTURE, GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
        if ((int) call(FB_STATUS, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("incomplete framebuffer");

        // A 1024x1024 mipmapped atlas, the thing being filtered.
        int atlas = genName(arena, GEN_TEXTURES);
        call(ACTIVE_TEXTURE, GL_TEXTURE0);
        call(BIND_TEXTURE, GL_TEXTURE_2D, atlas);
        MemorySegment pixels = arena.allocate((long) 1024 * 1024 * 4);
        for (int y = 0; y < 1024; y++)
            for (int x = 0; x < 1024; x++)
                pixels.setAtIndex(I32, (long) y * 1024 + x, (x ^ y) * 0x00010101 | 0xFF000000);
        call(TEX_IMAGE, GL_TEXTURE_2D, 0, GL_RGBA8, 1024, 1024, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        call(GEN_MIPMAP, GL_TEXTURE_2D);
        call(TEX_PARAM, GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        call(TEX_PARAM, GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        call(TEX_PARAM, GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        call(TEX_PARAM, GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

        // The per-column data a hybrid would upload every frame: four floats a column.
        int columns = genName(arena, GEN_TEXTURES);
        call(ACTIVE_TEXTURE, GL_TEXTURE0 + 1);
        call(BIND_TEXTURE, GL_TEXTURE_2D, columns);
        MemorySegment spans = arena.allocate((long) w * 4 * Float.BYTES);
        for (int i = 0; i < w * 4; i++) spans.setAtIndex(F32, i, i * 0.001f);
        call(TEX_IMAGE, GL_TEXTURE_2D, 0, GL_RGBA32F, w, 1, 0, GL_RGBA, GL_FLOAT, spans);
        call(TEX_PARAM, GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        call(TEX_PARAM, GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        int program = program(arena);
        call(USE_PROGRAM, program);
        call(UNIFORM_1I, uniform(arena, program, "atlas"), 0);
        call(UNIFORM_1I, uniform(arena, program, "columns"), 1);
        call(BIND_VAO, genName(arena, GEN_VAO));
        call(VIEWPORT, 0, 0, w, h);

        MemorySegment back = arena.allocate((long) w * h * 4);
        int[] out = new int[w * h];
        double[] up = new double[frames], drew = new double[frames], read = new double[frames];
        for (int f = -30; f < frames; f++) {                       // the first thirty warm the driver
            long t0 = System.nanoTime();
            call(ACTIVE_TEXTURE, GL_TEXTURE0 + 1);
            call(BIND_TEXTURE, GL_TEXTURE_2D, columns);
            call(TEX_SUB_IMAGE, GL_TEXTURE_2D, 0, 0, 0, w, 1, GL_RGBA, GL_FLOAT, spans);
            long t1 = System.nanoTime();
            call(CLEAR, GL_COLOR_BUFFER_BIT);
            call(DRAW_ARRAYS, GL_TRIANGLES, 0, 3);
            call(FINISH);
            long t2 = System.nanoTime();
            call(READ_PIXELS, 0, 0, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, back);
            MemorySegment.copy(back, I32, 0, out, 0, w * h);
            long t3 = System.nanoTime();
            if (f >= 0) {
                up[f] = (t1 - t0) / 1e6;
                drew[f] = (t2 - t1) / 1e6;
                read[f] = (t3 - t2) / 1e6;
            }
        }
        if ((int) call(GET_ERROR) != 0) System.out.println("warning: GL reported an error");
        System.out.printf("SPIKE %dx%d  %d frames%n", w, h, frames);
        report("upload", up);
        report("draw", drew);
        report("read", read);
        double total = median(up) + median(drew) + median(read);
        System.out.printf("  %-8s %6.3f ms  (%.0f fps)   CPU renderer at 1280x720 pitch 0: 9.7 ms%n",
                "total", total, 1000 / total);
    }

    private static void report(String name, double[] ms) {
        double[] s = ms.clone();
        Arrays.sort(s);
        System.out.printf("  %-8s %6.3f ms   p99 %6.3f%n", name, s[s.length / 2], s[(int) (s.length * 0.99)]);
    }

    private static double median(double[] ms) {
        double[] s = ms.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private static int genName(Arena arena, MethodHandle gen) {
        MemorySegment n = arena.allocate(I32);
        call(gen, 1, n);
        return n.get(I32, 0);
    }

    private static int program(Arena arena) {
        int p = (int) call(CREATE_PROGRAM);
        call(ATTACH, p, shader(arena, GL_VERTEX_SHADER, VERT));
        call(ATTACH, p, shader(arena, GL_FRAGMENT_SHADER, FRAG));
        call(LINK, p);
        MemorySegment ok = arena.allocate(I32);
        call(GET_PROGRAM, p, GL_LINK_STATUS, ok);
        if (ok.get(I32, 0) == 0) throw new IllegalStateException("the shader program would not link");
        return p;
    }

    private static int shader(Arena arena, int kind, String source) {
        int s = (int) call(CREATE_SHADER, kind);
        MemorySegment text = arena.allocateFrom(source), list = arena.allocate(PTR);
        list.set(PTR, 0, text);
        call(SHADER_SOURCE, s, 1, list, MemorySegment.NULL);
        call(COMPILE_SHADER, s);
        MemorySegment ok = arena.allocate(I32);
        call(GET_SHADER, s, GL_COMPILE_STATUS, ok);
        if (ok.get(I32, 0) == 0) {
            MemorySegment log = arena.allocate(1024), len = arena.allocate(I32);
            call(SHADER_LOG, s, 1024, len, log);
            throw new IllegalStateException("shader: " + log.getString(0));
        }
        return s;
    }

    private static int uniform(Arena arena, int program, String name) {
        return (int) call(UNIFORM_LOC, program, arena.allocateFrom(name));
    }

    private static String str(MethodHandle h, int what) {
        MemorySegment s = (MemorySegment) call(h, what);
        return s.equals(MemorySegment.NULL) ? "?" : s.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private GpuSpike() {}
}

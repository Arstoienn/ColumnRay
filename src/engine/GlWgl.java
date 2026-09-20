package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Windows: the GL entry points are split in two, and a context needs a window to be born from.
 *
 * Both halves of that are older than this engine and neither is negotiable. {@code opengl32.dll}
 * exports OpenGL 1.1 and nothing after it - that export table was frozen in 1996 - so the
 * thirty-odd calls {@link Gl} makes that arrived later, every framebuffer and shader and vertex
 * array among them, are not symbols in a library at all. They are answers from
 * {@code wglGetProcAddress}, which is a question only a thread with a current context may ask.
 * {@link #library} therefore hands {@link Gl} a lookup rather than a library: exported name
 * first, driver's answer second. And because {@link Gl} resolves every entry point while its
 * class initialises, that lookup has to arrive with the context already made, which is why the
 * work happens in {@link #start} and both entry points call it.
 *
 * The other half is the window. CGL makes a context out of nothing; WGL cannot, because the
 * pixel format that decides what a context can do is a property of a device context, and a
 * device context comes from a window. So a one-pixel window is registered, created and never
 * shown, and its DC carries the format. Nothing is ever drawn into it - the engine renders into
 * a framebuffer and reads the picture back through {@link Gl#readPixels}, and the window on
 * screen stays the Swing window - so this one exists purely to be the thing the format is a
 * property of, and is never pumped, painted or swapped.
 *
 * Then the dance in the middle: {@code wglCreateContext} gives a compatibility context of
 * whatever version the driver feels like, and the way to ask for a core profile is
 * {@code wglCreateContextAttribsARB}, which is itself only reachable through
 * {@code wglGetProcAddress} and so only reachable from a context that already exists. The dummy
 * context is made to ask that one question and deleted once it is answered. A machine whose
 * answer is "no such function" has Microsoft's software renderer rather than a card - GL 1.1, no
 * shaders - and is told so in a sentence, rather than left to fail later on a shader compile.
 */
final class GlWgl implements GlPlatform {
    static final GlWgl INSTANCE = new GlWgl();

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfShort I16 = ValueLayout.JAVA_SHORT;
    private static final ValueLayout.OfByte I8 = ValueLayout.JAVA_BYTE;
    private static final java.lang.foreign.AddressLayout PTR = ValueLayout.ADDRESS;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup GDI = SymbolLookup.libraryLookup("gdi32.dll", Arena.global());
    private static final SymbolLookup USER = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
    private static final SymbolLookup OPENGL = SymbolLookup.libraryLookup("opengl32.dll", Arena.global());

    /** The last-error slot the Win32 calls below write into. A downcall has to ask for it: the
     *  JVM makes calls of its own between a native return and any {@code GetLastError} of ours,
     *  so the error such a call would report is whichever of those failed last, not ours. */
    private static final StructLayout STATE = Linker.Option.captureStateLayout();
    private static final VarHandle LAST_ERROR =
            STATE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
    private static final Linker.Option CAPTURE = Linker.Option.captureCallState("GetLastError");

    private static MethodHandle fn(SymbolLookup lib, String name, FunctionDescriptor sig, Linker.Option... opts) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(
                () -> new IllegalStateException("Windows has no " + name)), sig, opts);
    }

    private static final MethodHandle GET_MODULE = fn(KERNEL, "GetModuleHandleW", FunctionDescriptor.of(PTR, PTR));
    private static final MethodHandle REGISTER_CLASS = fn(USER, "RegisterClassW", FunctionDescriptor.of(I16, PTR), CAPTURE);
    private static final MethodHandle CREATE_WINDOW = fn(USER, "CreateWindowExW",
            FunctionDescriptor.of(PTR, I32, PTR, PTR, I32, I32, I32, I32, I32, PTR, PTR, PTR, PTR), CAPTURE);
    private static final MethodHandle GET_DC = fn(USER, "GetDC", FunctionDescriptor.of(PTR, PTR), CAPTURE);
    private static final MethodHandle ENUM_DEVICES = fn(USER, "EnumDisplayDevicesW",
            FunctionDescriptor.of(I32, PTR, I32, PTR, I32));
    private static final MethodHandle CHOOSE_PF = fn(GDI, "ChoosePixelFormat", FunctionDescriptor.of(I32, PTR, PTR), CAPTURE);
    private static final MethodHandle SET_PF = fn(GDI, "SetPixelFormat", FunctionDescriptor.of(I32, PTR, I32, PTR), CAPTURE);
    private static final MethodHandle DESCRIBE_PF = fn(GDI, "DescribePixelFormat",
            FunctionDescriptor.of(I32, PTR, I32, I32, PTR));
    private static final MethodHandle CREATE_CTX = fn(OPENGL, "wglCreateContext", FunctionDescriptor.of(PTR, PTR), CAPTURE);
    private static final MethodHandle MAKE_CURRENT = fn(OPENGL, "wglMakeCurrent", FunctionDescriptor.of(I32, PTR, PTR), CAPTURE);
    private static final MethodHandle DELETE_CTX = fn(OPENGL, "wglDeleteContext", FunctionDescriptor.of(I32, PTR));
    private static final MethodHandle CURRENT_CTX = fn(OPENGL, "wglGetCurrentContext", FunctionDescriptor.of(PTR));
    private static final MethodHandle GET_PROC = fn(OPENGL, "wglGetProcAddress", FunctionDescriptor.of(PTR, PTR));

    /** CS_OWNDC: the window keeps one device context for its whole life, so the pixel format set
     *  on it below is still the format the context is drawing through a thousand frames later. */
    private static final int CS_OWNDC = 0x0020;
    private static final int PFD_DOUBLEBUFFER = 0x00000001, PFD_DRAW_TO_WINDOW = 0x00000004,
            PFD_SUPPORT_OPENGL = 0x00000020, PFD_GENERIC_FORMAT = 0x00000040,
            PFD_GENERIC_ACCELERATED = 0x00004000;
    private static final int WGL_MAJOR = 0x2091, WGL_MINOR = 0x2092, WGL_PROFILE_MASK = 0x9126,
            WGL_CORE_PROFILE = 0x00000001;

    /** WNDCLASSW and PIXELFORMATDESCRIPTOR, by the offsets the 64-bit ABI puts their fields at.
     *  Spelled as offsets rather than as layouts because only a handful of their fields are ever
     *  set and every other one must be the zero an arena already gives. */
    private static final long WC_SIZE = 72, WC_STYLE = 0, WC_PROC = 8, WC_INSTANCE = 24, WC_NAME = 64;
    private static final long PFD_SIZE = 40, PFD_NSIZE = 0, PFD_VERSION = 2, PFD_FLAGS = 4,
            PFD_PIXELTYPE = 8, PFD_COLORBITS = 9, PFD_ALPHABITS = 16, PFD_DEPTHBITS = 23,
            PFD_STENCILBITS = 24;

    /** DISPLAY_DEVICEW, the same way: which adapter drives a screen. Only {@link #note} reads it. */
    private static final long DD_SIZE = 840, DD_CB = 0, DD_STRING = 68, DD_FLAGS = 324;
    private static final int DISPLAY_ATTACHED = 0x00000001;

    /** The class name outlives the call that registers it, so it is not an arena's to reclaim. */
    private static final MemorySegment CLASS_NAME =
            Arena.global().allocateFrom("ColumnRayGL", StandardCharsets.UTF_16LE);

    private static boolean started;
    private static RuntimeException failure;
    private static MemorySegment dc = MemorySegment.NULL, ctx = MemorySegment.NULL;
    private static String profile = "WGL";

    private GlWgl() {}

    /** Exported name first, driver's answer second. {@code glTexImage3D} and everything else
     *  younger than 1996 is only ever the second. */
    @Override public SymbolLookup library() {
        start();
        return name -> {
            Optional<MemorySegment> exported = OPENGL.find(name);
            return exported.isPresent() ? exported : proc(name);
        };
    }

    @Override public String name() { return profile; }

    @Override public void makeCurrent() {
        start();
        if (((MemorySegment) call(CURRENT_CTX)).address() == ctx.address()) return;
        // A WGL context is current on one thread at a time, and all of the engine's GL work is on
        // the thread that ran Main. A second thread arriving here is a bug worth naming.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(STATE);
            if ((int) call(MAKE_CURRENT, state, dc, ctx) == 0)
                throw new IllegalStateException("wglMakeCurrent failed on thread '"
                        + Thread.currentThread().getName() + "': " + why(state)
                        + " (the context belongs to the thread that made it)");
        }
    }

    private static synchronized void start() {
        if (started) {
            if (failure != null) throw failure;
            return;
        }
        started = true;
        try {
            open();
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        }
    }

    private static void open() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(STATE);
            MemorySegment instance = (MemorySegment) call(GET_MODULE, MemorySegment.NULL);

            MemorySegment cls = arena.allocate(WC_SIZE);
            cls.set(I32, WC_STYLE, CS_OWNDC);
            // DefWindowProcW is a perfectly good window procedure for a window that is never
            // shown and never pumped, and using it directly saves an upcall stub whose whole
            // body would have been a call to it.
            cls.set(PTR, WC_PROC, USER.find("DefWindowProcW").orElseThrow());
            cls.set(PTR, WC_INSTANCE, instance);
            cls.set(PTR, WC_NAME, CLASS_NAME);
            if ((short) call(REGISTER_CLASS, state, cls) == 0)
                throw new IllegalStateException("RegisterClassW failed: " + why(state));

            // One pixel, no WS_VISIBLE, no parent: something for a pixel format to belong to.
            // Its position is not a way to choose a card - see note().
            MemorySegment window = (MemorySegment) call(CREATE_WINDOW, state, 0, CLASS_NAME, CLASS_NAME,
                    0, 0, 0, 1, 1, MemorySegment.NULL, MemorySegment.NULL, instance, MemorySegment.NULL);
            if (window.address() == 0)
                throw new IllegalStateException("CreateWindowExW failed: " + why(state));

            dc = (MemorySegment) call(GET_DC, state, window);
            if (dc.address() == 0) throw new IllegalStateException("GetDC failed: " + why(state));

            MemorySegment pfd = arena.allocate(PFD_SIZE);
            pfd.set(I16, PFD_NSIZE, (short) PFD_SIZE);
            pfd.set(I16, PFD_VERSION, (short) 1);
            pfd.set(I32, PFD_FLAGS, PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER);
            pfd.set(I8, PFD_PIXELTYPE, (byte) 0);          // PFD_TYPE_RGBA
            pfd.set(I8, PFD_COLORBITS, (byte) 32);
            pfd.set(I8, PFD_ALPHABITS, (byte) 8);
            pfd.set(I8, PFD_DEPTHBITS, (byte) 24);
            pfd.set(I8, PFD_STENCILBITS, (byte) 8);
            int format = (int) call(CHOOSE_PF, state, dc, pfd);
            if (format == 0) throw new IllegalStateException("ChoosePixelFormat failed: " + why(state));
            if ((int) call(SET_PF, state, dc, format, pfd) == 0)
                throw new IllegalStateException("SetPixelFormat failed: " + why(state));

            // What ChoosePixelFormat actually picked. A format that is generic and not
            // accelerated is the software rasteriser, which will compile no shader.
            call(DESCRIBE_PF, dc, format, (int) PFD_SIZE, pfd);
            int flags = pfd.get(I32, PFD_FLAGS);
            if ((flags & PFD_GENERIC_FORMAT) != 0 && (flags & PFD_GENERIC_ACCELERATED) == 0)
                throw new IllegalStateException("the pixel format this machine offers is Microsoft's "
                        + "software renderer and not a card: install the graphics driver's OpenGL, "
                        + "or drop --gpu");

            MemorySegment dummy = (MemorySegment) call(CREATE_CTX, state, dc);
            if (dummy.address() == 0)
                throw new IllegalStateException("wglCreateContext failed: " + why(state));
            if ((int) call(MAKE_CURRENT, state, dc, dummy) == 0)
                throw new IllegalStateException("wglMakeCurrent failed: " + why(state));

            MemorySegment attribs = proc("wglCreateContextAttribsARB").orElse(MemorySegment.NULL);
            if (attribs.address() == 0) {
                call(MAKE_CURRENT, state, MemorySegment.NULL, MemorySegment.NULL);
                call(DELETE_CTX, dummy);
                throw new IllegalStateException("this machine's OpenGL has no "
                        + "wglCreateContextAttribsARB, so it can give no core profile and no "
                        + "shaders: it is GL 1.1, the version Windows ships when no driver has");
            }
            MethodHandle create = LINKER.downcallHandle(attribs, FunctionDescriptor.of(PTR, PTR, PTR, PTR));

            // 3.3 is what the shaders say and 4.6 is what a modern card gives; ask downwards and
            // keep the first that answers, the way GlCgl asks 4.1 before 3.2.
            for (int[] version : new int[][] {{4, 6}, {4, 1}, {3, 3}}) {
                MemorySegment wanted = arena.allocateFrom(I32,
                        WGL_MAJOR, version[0], WGL_MINOR, version[1],
                        WGL_PROFILE_MASK, WGL_CORE_PROFILE, 0);
                MemorySegment made = (MemorySegment) call(create, dc, MemorySegment.NULL, wanted);
                if (made.address() == 0) continue;
                // The context outlives this arena: the driver owns it, and the arena held only
                // the attribute list, for the length of the call.
                ctx = made;
                profile = "WGL " + version[0] + "." + version[1] + " core";
                break;
            }
            call(MAKE_CURRENT, state, MemorySegment.NULL, MemorySegment.NULL);
            call(DELETE_CTX, dummy);
            if (ctx.address() == 0)
                throw new IllegalStateException("no core OpenGL context: the driver refused 4.6, 4.1 and 3.3");
            if ((int) call(MAKE_CURRENT, state, dc, ctx) == 0)
                throw new IllegalStateException("wglMakeCurrent on the core context failed: " + why(state));
        }
    }

    /**
     * The other cards in the machine, when the one that answered is not the only one.
     *
     * This exists because the obvious fix is the wrong one and costs an afternoon to find out.
     * The rule used to be that {@code opengl32} loaded the driver of the screen the window was
     * on, so a hidden window placed on the discrete card's monitor would be drawn by the
     * discrete card. Since Windows 10 1803 it is not: the ICD follows the adapter the OS prefers
     * for this executable, and the window may sit anywhere it likes. Placing the window on the
     * 4070's screen was written, run, and measured making no difference at all - the renderer
     * string still said Intel - so what is left here is the measurement rather than the code.
     *
     * Which leaves one lever, and it is outside the process: the per-application preference in
     * Settings > System > Display > Graphics, read once when the JVM starts. A machine with two
     * cards therefore gets a sentence naming the one it did not use, because "Intel UHD 770" is
     * an answer that looks like success.
     */
    @Override public String note(String renderer) {
        StringBuilder others = new StringBuilder();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment device = arena.allocate(DD_SIZE);
            for (int i = 0; ; i++) {
                device.fill((byte) 0);
                device.set(I32, DD_CB, (int) DD_SIZE);
                if ((int) call(ENUM_DEVICES, MemorySegment.NULL, i, device, 0) == 0) break;
                if ((device.get(I32, DD_FLAGS) & DISPLAY_ATTACHED) == 0) continue;
                String adapter = device.getString(DD_STRING, StandardCharsets.UTF_16LE);
                // The renderer string and the adapter string are written by different people -
                // "NVIDIA GeForce RTX 4070" against "NVIDIA GeForce RTX 4070/PCIe/SSE2" - so the
                // match is on the first word, which is the vendor and is the part that differs.
                String vendor = adapter.split(" ")[0];
                if (renderer.contains(vendor) || others.indexOf(adapter) >= 0) continue;
                others.append(others.isEmpty() ? "" : ", ").append(adapter);
            }
        }
        if (others.isEmpty()) return null;
        return "this machine also has " + others + ". OpenGL takes the card Windows prefers for "
                + "java.exe, not the one the window is on: Settings > System > Display > Graphics";
    }

    /** A driver's answer, or nothing. Some of them say 1, 2, 3 or -1 for a function they do not
     *  have instead of the null the documentation promises, and a call through 1 is a crash. */
    private static Optional<MemorySegment> proc(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = (MemorySegment) call(GET_PROC, arena.allocateFrom(name));
            long address = p.address();
            if (address == 0 || address == 1 || address == 2 || address == 3 || address == -1L)
                return Optional.empty();
            return Optional.of(p);
        }
    }

    private static String why(MemorySegment state) {
        return "Win32 error " + (int) LAST_ERROR.get(state, 0L);
    }

    private static Object call(MethodHandle h, Object... args) {
        try {
            return h.invokeWithArguments(args);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            throw new IllegalStateException("WGL call failed", t);
        }
    }
}

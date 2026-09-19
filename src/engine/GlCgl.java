package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * macOS: the GL entry points live in OpenGL.framework, and CGL makes a context out of nothing.
 *
 * OpenGL is deprecated here and capped at 4.1, but the hybrid renderer asks for nothing newer
 * than a full-screen pass over a mipmapped texture. A context with no drawable is exactly what
 * the engine wants: it renders into a framebuffer and reads the picture back, so the window
 * stays the Swing window the engine already has and nothing here needs a CALayer.
 */
final class GlCgl implements GlPlatform {
    static final GlCgl INSTANCE = new GlCgl();

    /** CGL pixel format attributes: a core profile, accelerated, and no drawable at all. */
    private static final int PFA_ACCELERATED = 73, PFA_PROFILE = 99,
            PROFILE_4_1_CORE = 0x4100, PROFILE_3_2_CORE = 0x3200;

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final java.lang.foreign.AddressLayout PTR = ValueLayout.ADDRESS;

    private static final SymbolLookup LIB = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/OpenGL.framework/OpenGL", Arena.global());
    private static final Linker LINKER = Linker.nativeLinker();

    private static MethodHandle fn(String name, FunctionDescriptor sig) {
        return LINKER.downcallHandle(LIB.find(name).orElseThrow(
                () -> new IllegalStateException("OpenGL.framework has no " + name)), sig);
    }

    private static final MethodHandle CHOOSE_PF = fn("CGLChoosePixelFormat", FunctionDescriptor.of(I32, PTR, PTR, PTR));
    private static final MethodHandle CREATE_CTX = fn("CGLCreateContext", FunctionDescriptor.of(I32, PTR, PTR, PTR));
    private static final MethodHandle SET_CTX = fn("CGLSetCurrentContext", FunctionDescriptor.of(I32, PTR));

    private GlCgl() {}

    @Override public SymbolLookup library() { return LIB; }

    @Override public String name() { return "CGL"; }

    /** A 4.1 core context, or a 3.2 one if the machine will not give 4.1. */
    @Override public void makeCurrent() {
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

    private static int call(MethodHandle h, Object... args) {
        try {
            return (int) h.invokeWithArguments(args);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            throw new IllegalStateException("CGL call failed", t);
        }
    }
}

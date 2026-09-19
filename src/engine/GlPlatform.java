package engine;

import java.lang.foreign.SymbolLookup;

/**
 * Where the graphics card is on this machine.
 *
 * Everything {@link Gl} does above this line is the same on every operating system: the GL entry
 * points are C functions with the same names and the same signatures wherever they are found.
 * Two things are not, and they are the two this says: which library holds those functions, and
 * how to get a context with no window behind it, which is the one part of OpenGL that was never
 * standardised. On macOS that is CGL; on Windows it would be WGL, on Linux GLX or EGL.
 *
 * Only the macOS backend is written. The others are a shape waiting to be filled rather than
 * code nobody has run: a WGL backend needs a hidden window, a pixel format chosen through
 * {@code ChoosePixelFormat}, a dummy context to reach {@code wglCreateContextAttribsARB}, and
 * then a real core-profile context - four calls that cannot be tested from here, so writing them
 * would be guessing in public. What this file does instead is make the seam, so that adding one
 * is a new class and a line in {@link #find}, and make the failure on a platform without one a
 * sentence rather than a stack trace out of a missing framework.
 *
 * -Dgl.platform=none forces the missing case, which is how the sentence gets tested on a machine
 * that does have a backend.
 */
interface GlPlatform {
    /** The library holding the GL entry points. */
    SymbolLookup library();

    /** Make a context with no drawable current on this thread, or throw saying why not. */
    void makeCurrent();

    /** What to call this in a message. */
    String name();

    /**
     * Why this machine has no backend, or null when it has one. Deliberately answerable without
     * loading {@link Gl}, whose every field is a GL entry point and so cannot exist here at all.
     */
    static String missing() {
        if (find() != null) return null;
        return "no OpenGL backend for " + System.getProperty("os.name")
                + ": the CPU renderer runs anywhere, --gpu does not";
    }

    static GlPlatform find() {
        String forced = System.getProperty("gl.platform", "");
        if (forced.equals("none")) return null;
        if (System.getProperty("os.name", "").startsWith("Mac")) return GlCgl.INSTANCE;
        return null;
    }

    /** The backend, or an exception naming the platform that has none. */
    static GlPlatform get() {
        GlPlatform p = find();
        if (p == null) throw new UnsupportedOperationException(missing());
        return p;
    }
}

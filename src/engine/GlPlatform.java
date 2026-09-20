package engine;

import java.lang.foreign.SymbolLookup;

/**
 * Where the graphics card is on this machine.
 *
 * Everything {@link Gl} does above this line is the same on every operating system: the GL entry
 * points are C functions with the same names and the same signatures wherever they are found.
 * Two things are not, and they are the two this says: which library holds those functions, and
 * how to get a context with no window behind it, which is the one part of OpenGL that was never
 * standardised. On macOS that is CGL and on Windows WGL; on Linux it would be GLX or EGL.
 *
 * {@link GlCgl} and {@link GlWgl} are written and have been run. Linux has no backend, and the
 * seam is what this file is for: adding one is a new class and a line in {@link #find}, and a
 * platform without one fails as a sentence rather than as a stack trace out of a missing
 * library. The two that exist differ more than the interface suggests - CGL makes a context out
 * of nothing, while WGL needs a window, a pixel format and a context to ask how to make the
 * context it wanted - and {@link GlWgl} says why.
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
     * Anything worth saying about how this machine chose the card that answered, or null.
     *
     * One card is one card and there is nothing to say. Two is a trap: the renderer string names
     * one of them, the frame is drawn, everything looks like it worked, and the number at the
     * bottom of --bench is the wrong card's. {@code renderer} is what {@link Gl#device} said.
     */
    default String note(String renderer) { return null; }

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
        String os = System.getProperty("os.name", "");
        if (os.startsWith("Mac")) return GlCgl.INSTANCE;
        if (os.startsWith("Windows")) return GlWgl.INSTANCE;
        return null;
    }

    /** The backend, or an exception naming the platform that has none. */
    static GlPlatform get() {
        GlPlatform p = find();
        if (p == null) throw new UnsupportedOperationException(missing());
        return p;
    }
}

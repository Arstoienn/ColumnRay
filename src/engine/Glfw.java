package engine;

import static org.lwjgl.glfw.GLFW.*;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.MemoryUtil;

/**
 * Where the graphics card is, now that one library answers that everywhere.
 *
 * This is what {@link GpuNote} is the last piece of: there used to be a {@code GlPlatform} with a
 * backend under it for each operating system, because getting an OpenGL context with no window
 * behind it is the one part of OpenGL that was never standardised - CGL on macOS, WGL on Windows,
 * GLX or EGL on Linux, four hundred and fifty lines of them, and no backend for Linux at all.
 * GLFW does that on every platform it runs on, so the answer is one hidden window.
 *
 * The engine draws into {@link #root()}: a window that is never shown, whose only job is to own a
 * context. Every other window is created sharing it, so a texture the renderer writes is a texture
 * the window can read - which is what makes it possible, later, to stop reading the shaded frame
 * back to the CPU only to upload it again.
 *
 * <b>The first thread.</b> On macOS GLFW must own the process's first thread, which is what
 * {@code -XstartOnFirstThread} gives it and what an AWT window wants for itself. {@code run.sh} and
 * {@code test.sh} pass it there, and that is why AWT is only ever an offscreen rasteriser now.
 */
final class Glfw {
    private static boolean started;
    private static RuntimeException failure;
    private static long root = MemoryUtil.NULL;

    private Glfw() {}

    /**
     * Start GLFW, once, or say why it will not start.
     *
     * A machine with no display service - a CI runner, a container - fails here, and that is the
     * modern shape of "this platform has no backend": a sentence, and then the CPU renderer, which
     * runs anywhere. The failure is kept, so asking twice costs one message and not two attempts.
     */
    static synchronized void start() {
        if (started) {
            if (failure != null) throw failure;
            return;
        }
        started = true;
        // -Dglfw=none forces the missing case, which is how the sentence below gets tested on a
        // machine that does have a display. It is what -Dgl.platform=none was for before the
        // per-platform backends went, and the suite is meant to be run both ways.
        if ("none".equals(System.getProperty("glfw"))) {
            failure = new IllegalStateException("GLFW turned off by -Dglfw=none: no OpenGL for "
                    + "this run, and the CPU renderer runs anywhere");
            throw failure;
        }
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            failure = new IllegalStateException("GLFW will not start here: no OpenGL for this run, "
                    + "and the CPU renderer runs anywhere");
            throw failure;
        }
    }

    /**
     * The hidden window that owns the engine's context, made on the first ask.
     *
     * 3.3 core is the real requirement - every shader in the engine says {@code #version 330} - and
     * macOS gives 4.1 for asking. Forward-compatible, because macOS will not give a core profile
     * otherwise.
     */
    static synchronized long root() {
        start();
        if (root != MemoryUtil.NULL) return root;
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        root = glfwCreateWindow(1, 1, "ColumnRay", MemoryUtil.NULL, MemoryUtil.NULL);
        if (root == MemoryUtil.NULL)
            // GLFW says GLFW_API_UNAVAILABLE for this, which means the machine has an OpenGL of
            // some sort and not one that does 3.3 core: Microsoft's software renderer, a virtual
            // machine, a CI runner. Say what to do about it rather than what failed.
            throw new IllegalStateException("no OpenGL 3.3 here: this machine offers no core "
                    + "profile, which is a software renderer or a driver without one. Install the "
                    + "graphics driver's OpenGL; without it only --shot, --verify and --bench run");
        return root;
    }

    /** What a new window shares its textures with, or NULL when the engine has no context yet. */
    static synchronized long share() { return root; }

    /** Let go of the library. Only the last window standing calls this. */
    static synchronized void terminate() {
        if (!started || failure != null) return;
        if (root != MemoryUtil.NULL) {
            glfwDestroyWindow(root);
            root = MemoryUtil.NULL;
        }
        glfwTerminate();
        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) cb.free();
        started = false;
    }
}

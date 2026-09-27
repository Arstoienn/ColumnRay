package engine;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.IntBuffer;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

/**
 * The GLFW window: one library call for each of the things {@link SurfaceAwt} hand-built.
 *
 * The frame still arrives as a {@link Graphics2D}, because that is what {@code Game.overlay} is
 * made of and a screenshot draws the very same overlay with no window anywhere. So the painter
 * draws into an image held here, and the image goes up as a texture over a full-screen triangle
 * strip - the same shape {@code GpuWalls} uses, and the same BGRA/8888_REV upload, which is Java's
 * packed ARGB int read straight by the card with no repacking on the way.
 *
 * What this is for, measured against the AWT one it sits beside:
 *
 * - The pointer is held by {@code GLFW_CURSOR_DISABLED}, so there is no warp: no putting the
 *   pointer back at the middle after every event, no discarding the event the warp itself caused,
 *   and no {@link Pointer}. It also works off macOS, where {@code Pointer.available()} is false.
 * - {@code glfwGetKeyScancode(GLFW_KEY_W)} is 13 on macOS, which is exactly what {@link Keys}
 *   already calls the W position. The two tables agree on all thirty-three, so keys can move here
 *   without renumbering anything - that is the next step, and until it is taken this relies on
 *   {@code Keys} reading the machine directly, which only macOS does.
 * - Linux has no {@code GlPlatform} backend at all; GLFW has one.
 *
 * Two things it does not do yet. It does not open the ray view, which is a second AWT window and
 * needs a second GLFW one ({@code RayView} is null-safe when it was never opened, so the loop is
 * happy). And it takes the GL context whenever it shows a frame, so the engine asks for its own
 * back through {@link Gl#reclaim()}; when the card's work moves onto this context, that goes away.
 */
final class SurfaceGlfw implements Surface {
    private long window;
    private Events to;
    private volatile boolean mouseLook;

    /** The drawable, in pixels. Not the window, which is in points: a Retina window 960 wide has
     *  a 1920-wide framebuffer, and the overlay is drawn at the finer one so its text is sharp. */
    private volatile int fbW, fbH;
    /** The window, in points, which is what the cursor is reported in. */
    private volatile int winW, winH;

    private BufferedImage image;
    private int[] pixels;
    private IntBuffer upload;
    private int program, vao, texture;
    private double lastX, lastY;
    private boolean haveLast;

    private static final String VERT = """
            #version 330 core
            out vec2 uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                uv = vec2(p.x, 1.0 - p.y);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAG = """
            #version 330 core
            in vec2 uv;
            uniform sampler2D frame;
            out vec4 colour;
            void main() { colour = texture(frame, uv); }
            """;

    @Override
    public void open(String title, int wantW, int wantH, RayView rayView, Events to) {
        this.to = to;
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW would not start");

        // 3.3 core is the real requirement - GpuWalls' shaders say #version 330 - and macOS gives
        // 4.1 for asking. Forward-compatible, because macOS will not give a core profile otherwise.
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);

        // --window fitted onto the screen there actually is, the same arithmetic SurfaceAwt does.
        int[] mx = new int[1], my = new int[1], mw = new int[1], mh = new int[1];
        glfwGetMonitorWorkarea(glfwGetPrimaryMonitor(), mx, my, mw, mh);
        double fit = Math.min(1, Math.min((mw[0] - 16) / (double) wantW, (mh[0] - 48) / (double) wantH));
        int w = Math.max(160, (int) (wantW * fit)), h = Math.max(90, (int) (wantH * fit));

        window = glfwCreateWindow(w, h, title, MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            glfwTerminate();
            throw new IllegalStateException("GLFW would not open a window");
        }
        glfwSetWindowPos(window, mx[0] + 8, my[0] + 8);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        glfwSwapInterval(1);

        int[] fw = new int[1], fh = new int[1];
        glfwGetFramebufferSize(window, fw, fh);
        fbW = fw[0];
        fbH = fh[0];
        winW = w;
        winH = h;

        glfwSetFramebufferSizeCallback(window, (win, nw, nh) -> {
            fbW = Math.max(1, nw);
            fbH = Math.max(1, nh);
        });
        glfwSetWindowSizeCallback(window, (win, nw, nh) -> {
            winW = Math.max(1, nw);
            winH = Math.max(1, nh);
        });
        glfwSetWindowCloseCallback(window, win -> to.closed());
        glfwSetCursorPosCallback(window, (win, x, y) -> moved(x, y));
        glfwSetCursorEnterCallback(window, (win, entered) -> {
            if (!entered && !mouseLook) to.hover(-1, -1);
            haveLast = false;
        });

        buildGl();
        glfwShowWindow(window);
        glfwFocusWindow(window);
        // The engine's own context was made before this window existed - Main asks for the card
        // before anything opens - and creating this one took the thread. Hand it back.
        Gl.reclaim();
    }

    @Override public int width() { return fbW; }

    @Override public int height() { return fbH; }

    @Override
    public void present(Painter p) {
        int w = fbW, h = fbH;
        if (image == null || image.getWidth() != w || image.getHeight() != h) {
            image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            if (upload != null) MemoryUtil.memFree(upload);
            upload = MemoryUtil.memAllocInt(w * h);
        }
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        p.paint(g);
        g.dispose();

        glfwMakeContextCurrent(window);
        upload.clear();
        upload.put(pixels, 0, w * h).flip();
        glViewport(0, 0, w, h);
        glUseProgram(program);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        // Java's packed ARGB int, read straight by the card: BGRA with a reversed 8_8_8_8.
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, upload);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glfwSwapBuffers(window);
    }

    @Override
    public void pump() {
        glfwPollEvents();
        if (glfwWindowShouldClose(window)) to.closed();
    }

    @Override
    public boolean active() {
        return glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_TRUE;
    }

    @Override
    public void mouseLook(boolean on) {
        mouseLook = on;
        haveLast = false;
        glfwSetInputMode(window, GLFW_CURSOR, on ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
    }

    @Override
    public void close() {
        if (window == MemoryUtil.NULL) return;
        if (upload != null) {
            MemoryUtil.memFree(upload);
            upload = null;
        }
        Callbacks.glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        window = MemoryUtil.NULL;
        glfwTerminate();
        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) cb.free();
    }

    /**
     * The pointer moved, in window points.
     *
     * With the cursor disabled GLFW stops clamping it to the window and just keeps counting, so
     * the difference between two reports is how far the hand moved - no warp, and nothing to
     * discard. The deltas stay in points rather than framebuffer pixels so that the view turns as
     * far per inch of desk as it does under AWT, whose events are in points too.
     */
    private void moved(double x, double y) {
        if (mouseLook || dragging()) {
            if (haveLast && active()) {
                int dx = (int) Math.round(x - lastX), dy = (int) Math.round(y - lastY);
                if (dx != 0 || dy != 0) to.looked(dx, dy);
            }
            lastX = x;
            lastY = y;
            haveLast = true;
            return;
        }
        haveLast = false;
        // Hover is answered in the drawable's own pixels, because that is what the engine
        // letterboxed the picture into and what columnAt() undoes.
        double sx = winW == 0 ? 1 : fbW / (double) winW, sy = winH == 0 ? 1 : fbH / (double) winH;
        to.hover((int) (x * sx), (int) (y * sy));
    }

    private boolean dragging() {
        return glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
    }

    private void buildGl() {
        program = link();
        vao = glGenVertexArrays();
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        // Nearest, because the picture is already the size of the drawable: the engine letterboxes
        // it into the frame itself, so there is nothing here to resample.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "frame"), 0);
    }

    private static int link() {
        int v = shader(GL_VERTEX_SHADER, VERT), f = shader(GL_FRAGMENT_SHADER, FRAG);
        int p = glCreateProgram();
        glAttachShader(p, v);
        glAttachShader(p, f);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) != GL_TRUE)
            throw new IllegalStateException("the window's shader would not link: " + glGetProgramInfoLog(p));
        glDeleteShader(v);
        glDeleteShader(f);
        return p;
    }

    private static int shader(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) != GL_TRUE)
            throw new IllegalStateException("the window's shader would not compile: " + glGetShaderInfoLog(s));
        return s;
    }
}

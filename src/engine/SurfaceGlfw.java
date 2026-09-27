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
final class SurfaceGlfw implements Surface, Keys.Source {

    /**
     * Every key position the game uses, as {@link Keys} numbers it, paired with GLFW's token for
     * the same place on the board.
     *
     * Both columns are positions and neither is a letter, which is the only reason this table can
     * be written down at all: GLFW's tokens are named for what a US board prints but defined by
     * where the key sits, and {@link Keys}' numbers are macOS virtual key codes, which are the
     * same thing said differently. {@code check()} proves it rather than trusting it.
     */
    private static final int[][] POSITIONS = {
            {Keys.A, GLFW_KEY_A}, {Keys.S, GLFW_KEY_S}, {Keys.D, GLFW_KEY_D}, {Keys.F, GLFW_KEY_F},
            {Keys.H, GLFW_KEY_H}, {Keys.G, GLFW_KEY_G}, {Keys.C, GLFW_KEY_C}, {Keys.V, GLFW_KEY_V},
            {Keys.Q, GLFW_KEY_Q}, {Keys.W, GLFW_KEY_W}, {Keys.E, GLFW_KEY_E}, {Keys.R, GLFW_KEY_R},
            {Keys.EQUALS, GLFW_KEY_EQUAL}, {Keys.MINUS, GLFW_KEY_MINUS},
            {Keys.RIGHT_BRACKET, GLFW_KEY_RIGHT_BRACKET}, {Keys.LEFT_BRACKET, GLFW_KEY_LEFT_BRACKET},
            {Keys.P, GLFW_KEY_P}, {Keys.L, GLFW_KEY_L}, {Keys.COMMA, GLFW_KEY_COMMA},
            {Keys.N, GLFW_KEY_N}, {Keys.M, GLFW_KEY_M}, {Keys.PERIOD, GLFW_KEY_PERIOD},
            {Keys.SPACE, GLFW_KEY_SPACE}, {Keys.TAB, GLFW_KEY_TAB}, {Keys.ESCAPE, GLFW_KEY_ESCAPE},
            {Keys.SHIFT, GLFW_KEY_LEFT_SHIFT}, {Keys.CONTROL, GLFW_KEY_LEFT_CONTROL},
            {Keys.RIGHT_SHIFT, GLFW_KEY_RIGHT_SHIFT}, {Keys.RIGHT_CONTROL, GLFW_KEY_RIGHT_CONTROL},
            {Keys.LEFT, GLFW_KEY_LEFT}, {Keys.RIGHT, GLFW_KEY_RIGHT},
            {Keys.DOWN, GLFW_KEY_DOWN}, {Keys.UP, GLFW_KEY_UP},
    };

    /** Keys' position -> GLFW's token, filled from POSITIONS; -1 for a position nobody binds. */
    private static final int[] TOKEN = new int[128];
    static {
        java.util.Arrays.fill(TOKEN, -1);
        for (int[] pair : POSITIONS) if (pair[0] >= 0 && pair[0] < TOKEN.length) TOKEN[pair[0]] = pair[1];
    }

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
    public void open(String title, int wantW, int wantH, Events to) {
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
        check();
        Keys.source(this);
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

    /**
     * A second GLFW window, sharing this one's objects.
     *
     * Sharing means its textures and programs are the same objects, but a vertex array is not
     * shareable in GL 3.3, so it makes one of its own. Its context is current only while it is
     * painting; {@code shadeOnGpu} takes the engine's back before anything is shaded, which is
     * why no window here has to put anything back.
     */
    @Override
    public Panel panel(String title, int width) {
        int[] ww = new int[1], wh = new int[1];
        glfwGetWindowSize(window, ww, wh);
        int[] wx = new int[1], wy = new int[1];
        glfwGetWindowPos(window, wx, wy);

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);                // a debugging window: shut until asked for
        long win = glfwCreateWindow(width, wh[0], title, MemoryUtil.NULL, window);
        if (win == MemoryUtil.NULL) throw new IllegalStateException("GLFW would not open the ray view");
        glfwSetWindowPos(win, wx[0] + ww[0] + 6, wy[0]);
        glfwSetWindowCloseCallback(win, w -> {
            glfwSetWindowShouldClose(w, false);                  // hide rather than close, as the AWT one did
            glfwHideWindow(w);
        });

        glfwMakeContextCurrent(win);
        GL.createCapabilities();
        int prog = link(), array = glGenVertexArrays(), tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glUseProgram(prog);
        glUniform1i(glGetUniformLocation(prog, "frame"), 0);

        return new Panel() {
            private BufferedImage img;
            private int[] px;
            private IntBuffer buf;
            private boolean shown;

            @Override public int width() {
                int[] w = new int[1], h = new int[1];
                glfwGetFramebufferSize(win, w, h);
                return Math.max(1, w[0]);
            }

            @Override public int height() {
                int[] w = new int[1], h = new int[1];
                glfwGetFramebufferSize(win, w, h);
                return Math.max(1, h[0]);
            }

            @Override public boolean visible() { return shown && glfwGetWindowAttrib(win, GLFW_VISIBLE) == GLFW_TRUE; }

            @Override public void show(boolean on) {
                shown = on;
                if (on) glfwShowWindow(win); else glfwHideWindow(win);
            }

            @Override public void onWheel(java.util.function.DoubleConsumer wheel) {
                // GLFW counts up away from the hand and AWT counts down, so the sign is flipped
                // here and the ray view's own arithmetic is left alone.
                glfwSetScrollCallback(win, (w, dx, dy) -> wheel.accept(-dy));
            }

            @Override public void present(Painter p) {
                int w = width(), h = height();
                if (img == null || img.getWidth() != w || img.getHeight() != h) {
                    img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
                    if (buf != null) MemoryUtil.memFree(buf);
                    buf = MemoryUtil.memAllocInt(w * h);
                }
                Graphics2D g = img.createGraphics();
                p.paint(g);
                g.dispose();
                glfwMakeContextCurrent(win);
                buf.clear();
                buf.put(px, 0, w * h).flip();
                glViewport(0, 0, w, h);
                glUseProgram(prog);
                glBindVertexArray(array);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, tex);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buf);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                glfwSwapBuffers(win);
            }

            @Override public void close() {
                if (buf != null) {
                    MemoryUtil.memFree(buf);
                    buf = null;
                }
                Callbacks.glfwFreeCallbacks(win);
                glfwDestroyWindow(win);
            }
        };
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
        Keys.source(null);
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

    @Override
    public boolean down(int position) {
        int token = position >= 0 && position < TOKEN.length ? TOKEN[position] : -1;
        return token >= 0 && glfwGetKey(window, token) == GLFW_PRESS;
    }

    @Override
    public String label(int position) {
        int token = position >= 0 && position < TOKEN.length ? TOKEN[position] : -1;
        if (token < 0) return null;
        String name = glfwGetKeyName(token, 0);
        // Null for every key that prints nothing - Space, Shift, the arrows - and those are where
        // they are on every layout, so Keys' own names for them are right.
        return name == null || name.isEmpty() ? null : name.toUpperCase();
    }

    /**
     * Say so if GLFW and {@link Keys} disagree about where a key is.
     *
     * On macOS a GLFW token's scancode is the macOS virtual key code, which is exactly the number
     * Keys files the position under, so the whole table can be checked against the machine rather
     * than against a comment. Elsewhere the scancodes are a different numbering and there is
     * nothing to compare, so this asks nothing. A mismatch would move somebody's WASD one key over
     * and is worth a line on stderr rather than a puzzle.
     */
    private static void check() {
        if (!System.getProperty("os.name", "").startsWith("Mac")) return;
        StringBuilder wrong = new StringBuilder();
        for (int[] pair : POSITIONS) {
            int scancode = glfwGetKeyScancode(pair[1]);
            if (scancode != pair[0]) wrong.append(' ').append(pair[0]).append("!=").append(scancode);
        }
        if (wrong.length() > 0)
            System.err.println("keys: GLFW and Keys disagree about" + wrong
                    + " - the bindings will be in the wrong places");
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

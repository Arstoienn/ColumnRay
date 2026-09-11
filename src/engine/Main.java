package engine;

import engine.World.Region;
import engine.World.Shape;
import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Window, input, player physics and minimap. A second "ray view" window lives in RayView.
 * Usage: java -cp out engine.Main [map.json] [--shot out.png [x y angle pitch [column]]] [--bench]
 */
public final class Main {
    // What is rendered and what is shown are separate: 1280x720 rays by default, scaled up into a
    // window of its own size. Tying the ray count to the window made fullscreen slow for no detail
    // anyone asked for; the frame-time controller (see steer) moves the render size, not the window.
    static final int DEFAULT_W = 1280, DEFAULT_H = 720;
    static final int DEFAULT_WINDOW_W = 1920, DEFAULT_WINDOW_H = 1080;

    // Player dimensions (metres)
    static final double RADIUS = 0.3, EYE_STAND = 1.6, EYE_CROUCH = 1.0, HEAD_ABOVE_EYE = 0.15;
    static final double STEP = 0.35, GRAVITY = 18, JUMP_SPEED = 5.2, WALK = 3.2, RUN = 5.5;
    static final double MAX_PITCH = Math.toRadians(30);

    // The render size is a step on DynamicResolution.LADDER, a share of the window: the frame-time
    // controller moves along it, and , and . step along it by hand (which turns the controller off;
    // V turns it back on).

    /** Output resolution: the size of the image that reaches the window. Changes with the render
     *  scale, so it is not final; the window does not change, the picture in it just gets coarser
     *  or finer. */
    private int W, H;
    /** Supersampling factor. The engine renders at (W*SS) x (H*SS) and the result is box-filtered
     *  down to W x H, so SS is also the number of rays per output column. SS = 1 disables it. */
    private final int SS;
    /** Render resolution = the ray count. Equals W when SS is 1. */
    private int RW, RH;

    private final World world;
    private BufferedImage image;
    private int[] out;            // the W x H pixels the window sees
    private int[] hi;             // the RW x RH tilted view after the pitch warp; same array as `out` when SS = 1
    private float[] depth, hiDepth; // shot only: output depth and the same pitch warp before downsampling
    private int[] albedo, hiAlbedo; // shot only: unshaded map colours, following the depth samples
    private int[] src;            // what the renderer writes: the upright (y-sheared) view plus overscan
    private int srcW, srcH;
    private final Renderer renderer;
    private final RayView rayView;
    private final Renderer.Camera cam = new Renderer.Camera();
    private Minimap minimap;                                     // built on the first frame that shows it

    // Player state
    private double x, y, angle, pitch, feet, vz, eyeH = EYE_STAND, viewFeet;
    private boolean grounded;
    // G: fly. Gravity off, Space and crouch go up and down, and nothing is solid - the quickest way
    // to see whether a roof, a cliff or a stray block came out of the conversion right.
    private boolean flying;

    // Input (the mouse is written on the EDT and read by the main loop; the keys are read straight
    // from the machine by Keys, once a frame)
    private final Set<Integer> heldLastFrame = new HashSet<>();  // so a tap fires once, not every frame
    private boolean listening;                                   // is a window of ours in front?
    private double mouseDX, mouseDY;
    private volatile boolean showMap = true, fisheye = false;
    private volatile boolean shear = false;                      // P: the old y-shearing pitch, for comparison
    private volatile boolean baked = true;                       // L: baked lighting, or the old flat model
    private Lighting lighting;                                   // null with --flat
    private volatile double fovDeg = Renderer.DEFAULT_FOV;
    private volatile int hoverColumn = -1, hoverRow = -1;        // which pixel of the main view the mouse is over
    private int traceI = -1, traceJ = -1;                        // the output pixel whose ray the ray view traces

    // This frame's pitch warp, set by preparePitch()
    private boolean warpShear;
    private double warpSin, warpCos, warpTan, warpHz, warpCx;
    private int warpX0, warpX1, warpY1;
    private volatile int viewX, viewY, viewW, viewH;             // where the main view sits inside the window
    private final int baseW, baseH;                              // the resolution --size asked for
    private int winW = DEFAULT_WINDOW_W, winH = DEFAULT_WINDOW_H; // --window: the output, which the picture is scaled to
    private int scaleIx;                                         // where on DynamicResolution.LADDER we are now
    private int targetFps = 60;                                  // --fps: the controller's budget; 0 = off
    private volatile boolean autoRes = true;                     // V: is the controller steering?
    private DynamicResolution steer;
    private volatile int wantScale = -1;                         // set by a key, applied between frames
    private double startFeet = Double.NaN;                       // --feet: which storey to start on
    // --shots: stand at exactly the height asked for instead of on whatever the map has here.
    // Snapping to our own floor moved the eye up to 25 cm away from where the reference camera
    // stands, which tilts the whole frame out of line; unsnapped, a floor that came out at the
    // wrong height shows up as what it is - that floor being at the wrong distance.
    private double exactFeet = Double.NaN;
    private double fps;

    Main(World world, int w, int h, int ss) {
        this.world = world;
        this.W = w;
        this.H = h;
        this.baseW = w;
        this.baseH = h;
        this.SS = ss;
        this.RW = w * ss;
        this.RH = h * ss;
        this.viewW = w;
        this.viewH = h;
        this.image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        this.out = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        this.hi = ss == 1 ? out : new int[RW * RH];   // SS = 1: the pitch warp writes straight into the window image
        this.srcW = RW;
        this.srcH = RH;
        this.src = new int[RW * RH];                  // grows on demand, see preparePitch()
        renderer = new Renderer(world, RW, RH, src);
        rayView = new RayView(world, renderer);
        x = world.spawnX;
        y = world.spawnY;
        angle = world.spawnAngle;
        placeOnGround();
    }

    /** Start on whichever storey has a floor at (or just below) this height. Handy for looking at
     *  an upper floor without walking up to it: --feet 3.6 */
    void standOn(double z) {
        this.startFeet = z;
        placeOnGround();
    }

    private void placeOnGround() {
        Region r = world.regionAt(x, y);
        double from = Double.isNaN(startFeet) ? (r == null ? 0 : r.floor) : startFeet;
        feet = viewFeet = support(x, y, from)[0];
        vz = 0;
        grounded = true;
    }

    public static void main(String[] args) throws Exception {
        // Before AWT starts, and only when a window is going to open - see Keys. A headless run
        // has no HUD to name, and on a machine with no window service to ask, the question hangs.
        if (Arrays.stream(args).noneMatch(a ->
                a.equals("--shot") || a.equals("--shots") || a.equals("--bench"))) {
            Keys.readLabels();
        }
        String mapPath = "maps/school.json", shot = null, shots = null;
        double[] at = null;
        boolean bench = false, shear = false, flatLight = false;
        int w = DEFAULT_W, h = DEFAULT_H, ss = 1, winW = DEFAULT_WINDOW_W, winH = DEFAULT_WINDOW_H, targetFps = 60;
        double startFeet = Double.NaN;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--bench")) {
                bench = true;
            } else if (args[i].equals("--shear")) {
                shear = true;
            } else if (args[i].equals("--flat")) {
                flatLight = true;
            } else if (args[i].equals("--size")) {
                if (i + 1 >= args.length) { usage("--size needs a WxH value, e.g. 1280x720"); return; }
                String[] wh = args[++i].toLowerCase().split("x");
                if (wh.length != 2) { usage("--size wants WxH, e.g. 1280x720, not " + args[i]); return; }
                try {
                    w = Integer.parseInt(wh[0].trim());
                    h = Integer.parseInt(wh[1].trim());
                } catch (NumberFormatException e) {
                    usage("--size wants two whole numbers, e.g. 1280x720, not " + args[i]);
                    return;
                }
                if (w < 16 || h < 16 || w > 16384 || h > 16384) { usage("--size must be between 16x16 and 16384x16384"); return; }
            } else if (args[i].equals("--fps")) {
                if (i + 1 >= args.length) { usage("--fps needs a frame rate, e.g. 60 (0 keeps the render size fixed)"); return; }
                try {
                    targetFps = Integer.parseInt(args[++i].trim());
                } catch (NumberFormatException e) {
                    usage("--fps wants a whole number, e.g. 60, not " + args[i]);
                    return;
                }
                if (targetFps < 0 || targetFps > 1000) { usage("--fps must be between 0 and 1000"); return; }
            } else if (args[i].equals("--window")) {
                if (i + 1 >= args.length) { usage("--window needs a WxH value, e.g. 1920x1080"); return; }
                String[] wh = args[++i].toLowerCase().split("x");
                try {
                    winW = Integer.parseInt(wh[0].trim());
                    winH = Integer.parseInt(wh[1].trim());
                } catch (RuntimeException e) {
                    usage("--window wants two whole numbers, e.g. 1920x1080, not " + args[i]);
                    return;
                }
                if (winW < 160 || winH < 90 || winW > 16384 || winH > 16384) { usage("--window must be between 160x90 and 16384x16384"); return; }
            } else if (args[i].equals("--ss")) {
                if (i + 1 >= args.length) { usage("--ss needs a factor, e.g. 2"); return; }
                try {
                    ss = Integer.parseInt(args[++i].trim());
                } catch (NumberFormatException e) {
                    usage("--ss wants a whole number, e.g. 2, not " + args[i]);
                    return;
                }
                if (ss < 1 || ss > 8) { usage("--ss must be between 1 and 8"); return; }
            } else if (args[i].equals("--feet")) {
                if (i + 1 >= args.length) { usage("--feet needs a height in metres, e.g. 3.6"); return; }
                try {
                    startFeet = Double.parseDouble(args[++i].trim());
                } catch (NumberFormatException e) {
                    usage("--feet wants a number, e.g. 3.6, not " + args[i]);
                    return;
                }
            } else if (args[i].equals("--shots")) {
                if (i + 1 >= args.length) { usage("--shots needs a file of views"); return; }
                shots = args[++i];
            } else if (args[i].equals("--shot")) {
                if (i + 1 >= args.length) { usage("--shot needs an output file"); return; }
                shot = args[++i];
                // optionally followed by: x y angle pitch [column]
                double[] nums = new double[5];
                int n = 0;
                while (n < 5 && i + 1 < args.length && args[i + 1].matches("-?[0-9.]+")) nums[n++] = Double.parseDouble(args[++i]);
                if (n >= 4) at = Arrays.copyOf(nums, n);
            } else if (args[i].startsWith("--")) {
                usage("unknown option " + args[i]);
                return;
            } else {
                mapPath = args[i];
            }
        }
        if (shot != null || shots != null || bench) System.setProperty("java.awt.headless", "true");

        if ((long) w * ss > 16384 || (long) h * ss > 16384) {
            usage("--size times --ss must stay within 16384x16384 (that would be " + w * ss + "x" + h * ss + ")");
            return;
        }
        Main game = new Main(World.load(Path.of(mapPath)), w, h, ss);
        game.winW = winW;
        game.winH = winH;
        game.targetFps = targetFps;
        // The map may ask for a grade on the way out; -Dgrade.sat / -Dgrade.lift override it while
        // one is being found. See Renderer.grade.
        Map<String, Object> lg = game.world.lighting == null ? Map.of() : game.world.lighting;
        Object gr = lg.get("grade");
        Map<String, Object> g = gr instanceof Map ? World.obj(gr) : Map.of();
        Renderer.grade(Double.parseDouble(System.getProperty("grade.sat",
                        String.valueOf(World.num(g, "saturation", 1.0)))),
                Double.parseDouble(System.getProperty("grade.lift",
                        String.valueOf(World.num(g, "lift", 0.0)))));
        Renderer.fogOn = !Boolean.FALSE.equals(lg.get("fog"));
        game.shear = shear;
        if (!flatLight) {
            game.lighting = Lighting.bake(game.world);
            game.renderer.setLighting(game.lighting);
        }
        if (!Double.isNaN(startFeet)) game.standOn(startFeet);
        if (bench) game.bench();
        else if (shots != null) game.screenshots(Path.of(shots));
        else if (shot != null) game.screenshot(new File(shot), at);
        else game.run();
    }

    /**
     * Spin on the spot and time every frame (headless, no window): level, then tilted all the way.
     *
     * The JIT gets -Dbench.warmup untimed frames first (400): 120 were not enough, and the first
     * timed frames were still being compiled. Then each of -Dbench.frames frames (720, one full turn)
     * is timed on its own, and the median and 99th percentile are reported next to the mean - a
     * mean hides both a slow start and a stall. bench.sh runs this several times and says how far
     * apart the runs were.
     */
    private void bench() {
        int warmup = Integer.getInteger("bench.warmup", 400), frames = Integer.getInteger("bench.frames", 720);
        double saved = pitch;
        for (double p : new double[] {0, MAX_PITCH}) {          // level, and fully tilted (the most overscan)
            pitch = p;
            for (int i = 0; i < warmup; i++) { angle += 2 * Math.PI / frames; frame(); }
            long[] ns = new long[frames];
            for (int i = 0; i < frames; i++) {
                long t0 = System.nanoTime();
                angle += 2 * Math.PI / frames;
                frame();
                ns[i] = System.nanoTime() - t0;
            }
            long[] sorted = ns.clone();
            Arrays.sort(sorted);
            double median = sorted[frames / 2] / 1e6;
            double p99 = sorted[Math.min(frames - 1, (int) Math.ceil(frames * 0.99) - 1)] / 1e6;
            double mean = Arrays.stream(ns).average().orElse(0) / 1e6;
            System.out.printf("BENCH %dx%d rendered %dx%d ss %d pitch %.0f %s rays %d median %.3f p99 %.3f mean %.3f ms  (median %.0f fps)%n",
                    W, H, RW, RH, SS, Math.toDegrees(p), shear ? "shear" : "true",
                    renderer.drawnX1 - renderer.drawnX0, median, p99, mean, 1000 / median);
        }
        pitch = saved;
    }

    // ---- Main loop ----

    private void run() throws Exception {
        Canvas canvas = new Canvas();
        canvas.enableInputMethods(false);                        // an IME (Bopomofo, Pinyin) must not eat the keys
        SwingUtilities.invokeAndWait(() -> {
            // The window is the output: --window, fitted onto the screen, whatever is being rendered.
            // present() scales the picture up into it, so a bigger window costs no rays. The ray
            // view opens beside it, hidden until R.
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int rayW = (int) Math.min(640, screen.width * 0.36);
            double fit = Math.min(1, Math.min((screen.width - 16) / (double) winW, (screen.height - 48) / (double) winH));
            JFrame frame = new JFrame("ColumnRay - " + world.name);
            canvas.setPreferredSize(new Dimension((int) (winW * fit), (int) (winH * fit)));
            canvas.setIgnoreRepaint(true);
            canvas.setFocusTraversalKeysEnabled(false);
            frame.add(canvas);
            frame.pack();
            frame.setLocation(screen.x + 8, screen.y + 8);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setVisible(true);
            canvas.createBufferStrategy(2);
            installInput(canvas);
            rayView.open(frame, rayW);
            frame.toFront();
            // toFront() only orders our windows; on macOS it does not make us the active app, so a
            // game started from a shell that is not in front (an IDE, a script) opens behind it and
            // every key goes to whatever is. Ask the OS to bring the app itself forward.
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND))
                java.awt.Desktop.getDesktop().requestForeground(true);
            canvas.requestFocus();
        });

        scaleIx = DynamicResolution.nearest(W / (double) winW);
        autoRes = targetFps > 0;
        steer = new DynamicResolution(1000.0 / Math.max(1, targetFps), scaleIx);
        long last = System.nanoTime();
        while (true) {
            long now = System.nanoTime();
            double dt = Math.min(0.05, (now - last) / 1e9);
            last = now;
            update(dt);
            frame();
            present(canvas);
            rayView.present(view());
            // What this frame cost, before the loop sleeps: the controller asks for a step at most,
            // and frame() applies it between frames.
            if (autoRes) {
                int level = steer.frame((System.nanoTime() - now) / 1e6);
                if (level != scaleIx) wantScale = level;
            }
            fps = fps == 0 ? 1 / Math.max(dt, 1e-6) : fps * 0.95 + 0.05 / Math.max(dt, 1e-6);
            if (System.nanoTime() - now < 4_000_000) Thread.sleep(2);
        }
    }

    private void installInput(Canvas canvas) {
        canvas.addKeyListener(Keys.listener());
        MouseAdapter drag = new MouseAdapter() {
            int lx, ly;
            @Override public void mousePressed(MouseEvent e) { lx = e.getX(); ly = e.getY(); canvas.requestFocus(); }
            @Override public void mouseDragged(MouseEvent e) {
                synchronized (Main.this) { mouseDX += e.getX() - lx; mouseDY += e.getY() - ly; }
                lx = e.getX();
                ly = e.getY();
            }
            @Override public void mouseMoved(MouseEvent e) { hoverColumn = columnAt(e.getX()); hoverRow = rowAt(e.getY()); }
            @Override public void mouseExited(MouseEvent e) { hoverColumn = hoverRow = -1; }
        };
        canvas.addMouseListener(drag);
        canvas.addMouseMotionListener(drag);
    }

    /** Is the key in this position held? Only while a window of ours is in front - see {@link Keys}. */
    private boolean down(int key) { return listening && Keys.down(key); }

    /**
     * The key state comes from the whole machine, not from our windows, so ignore it unless one of
     * ours is the active window. AWT events came with that for free.
     */
    private static boolean inFront() {
        for (Window w : Window.getWindows()) if (w.isActive()) return true;
        return false;
    }

    private static final int[] TAPS = {Keys.ESCAPE, Keys.M, Keys.G, Keys.F, Keys.P, Keys.L, Keys.R,
            Keys.N, Keys.LEFT_BRACKET, Keys.MINUS, Keys.RIGHT_BRACKET, Keys.EQUALS, Keys.COMMA, Keys.PERIOD, Keys.V};

    /** The keys that do their work once, on the way down, rather than for as long as they are held. */
    private void taps() {
        for (int key : TAPS) {
            if (!down(key)) { heldLastFrame.remove(key); continue; }
            if (!heldLastFrame.add(key)) continue;               // still held from last frame
            switch (key) {
                case Keys.ESCAPE -> System.exit(0);
                case Keys.M -> showMap = !showMap;
                case Keys.G -> { flying = !flying; vz = 0; grounded = false; }
                case Keys.F -> fisheye = !fisheye;
                case Keys.P -> shear = !shear;
                case Keys.L -> baked = !baked;
                case Keys.R -> rayView.toggle();
                case Keys.N -> rayView.toggleFollow();
                case Keys.LEFT_BRACKET, Keys.MINUS -> fovDeg = Math.max(30, fovDeg - 5);
                case Keys.RIGHT_BRACKET, Keys.EQUALS -> fovDeg = Math.min(120, fovDeg + 5);
                case Keys.COMMA -> { autoRes = false; wantScale = Math.max(0, scaleIx - 1); }
                case Keys.PERIOD -> { autoRes = false; wantScale = Math.min(DynamicResolution.LADDER.length - 1, scaleIx + 1); }
                case Keys.V -> {
                    autoRes = targetFps > 0 && !autoRes;
                    if (autoRes) steer = new DynamicResolution(1000.0 / targetFps, scaleIx);
                }
                default -> { }
            }
        }
    }

    /** Window coordinate -> column of the main view; -1 when the point is outside it. */
    private int columnAt(int mx) {
        int c = (int) Math.floor((mx - viewX) * (double) RW / viewW);
        return c >= 0 && c < RW ? c : -1;
    }

    /** Window coordinate -> row of the main view; -1 when the point is outside it. */
    private int rowAt(int my) {
        int r = (int) Math.floor((my - viewY) * (double) RH / viewH);
        return r >= 0 && r < RH ? r : -1;
    }

    // ---- Player physics ----

    private void update(double dt) {
        listening = !Keys.physical() || inFront();
        taps();
        double fov = fovDeg;
        if (Math.abs(renderer.fov() - fov) > 1e-6) renderer.setFov(fov);
        int hc = hoverColumn, hr = hoverRow;
        traceI = hc >= 0 && hr >= 0 ? hc : -1;                   // the pixel the ray view traces; -1 = centre
        traceJ = hc >= 0 && hr >= 0 ? hr : -1;

        double mdx, mdy;
        synchronized (this) { mdx = mouseDX; mdy = mouseDY; mouseDX = mouseDY = 0; }
        double turn = (down(Keys.RIGHT) || down(Keys.E) ? 1 : 0) - (down(Keys.LEFT) || down(Keys.Q) ? 1 : 0);
        double look = (down(Keys.UP) ? 1 : 0) - (down(Keys.DOWN) ? 1 : 0);
        angle += turn * 2.2 * dt + mdx * 0.004;
        pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch + look * 1.2 * dt - mdy * 0.003));

        boolean crouch = down(Keys.C) || down(Keys.CONTROL) || down(Keys.RIGHT_CONTROL);
        boolean running = down(Keys.SHIFT) || down(Keys.RIGHT_SHIFT);
        double dirX = Math.cos(angle), dirY = Math.sin(angle);
        double f = (down(Keys.W) ? 1 : 0) - (down(Keys.S) ? 1 : 0);
        double s = (down(Keys.D) ? 1 : 0) - (down(Keys.A) ? 1 : 0);
        if (f != 0 || s != 0) {
            double speed = (running ? RUN : WALK) * (crouch ? 0.5 : 1) / Math.hypot(f, s);
            double dx = (dirX * f - dirY * s) * speed * dt, dy = (dirY * f + dirX * s) * speed * dt;
            if (flying) {
                x += dx;
                y += dy;
            } else {
                int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 0.1));   // substep so we cannot tunnel through thin walls
                for (int i = 0; i < n; i++) {
                    if (!blocked(x + dx / n, y, feet)) x += dx / n;
                    if (!blocked(x, y + dy / n, feet)) y += dy / n;
                }
            }
        }

        if (flying) {
            double up = (down(Keys.SPACE) ? 1 : 0) - (crouch ? 1 : 0);
            feet += up * (running ? RUN : WALK) * dt;
            vz = 0;
            grounded = false;
            eyeH += (EYE_STAND - eyeH) * Math.min(1, dt * 10);
            viewFeet = feet;
            return;
        }

        double[] sup = support(x, y, feet);
        double ground = sup[0], ceil = sup[1];
        if (grounded && feet > ground && feet - ground <= STEP) feet = ground;   // stick to the floor when stepping down
        if (grounded && down(Keys.SPACE)) vz = JUMP_SPEED;
        vz -= GRAVITY * dt;
        feet += vz * dt;
        if (feet <= ground) { feet = ground; vz = 0; grounded = true; } else grounded = false;
        if (feet + eyeH + HEAD_ABOVE_EYE > ceil) {
            feet = Math.max(ground, ceil - eyeH - HEAD_ABOVE_EYE);
            vz = Math.min(vz, 0);
        }

        double target = crouch ? EYE_CROUCH : EYE_STAND;
        if (!crouch && feet + EYE_STAND + HEAD_ABOVE_EYE > ceil) target = eyeH;  // something overhead: cannot stand back up
        eyeH += (target - eyeH) * Math.min(1, dt * 10);
        if (grounded && Math.abs(feet - viewFeet) < 1) viewFeet += (feet - viewFeet) * Math.min(1, dt * 14);
        else viewFeet = feet;
    }

    private double head(double feet) { return feet + eyeH + HEAD_ABOVE_EYE; }

    /** Blocked when the floor is more than one step up, the ceiling is below head height,
     *  or a solid shape is in the way. Storeys stack, so only regions that actually overlap the
     *  body between the feet and the top of the head are considered - the floor of the storey
     *  above is not an obstacle to someone walking about on the one below. */
    private boolean blocked(double px, double py, double feet) {
        if (world.regionAt(px, py) == null) return true;
        // Clearance has to be judged at the height we would end up at, not the one we are leaving:
        // stepping up onto a stair whose underside is a low void would otherwise be rejected by the
        // void's ceiling, even though our head ends up above it.
        double up = support(px, py, feet)[0];
        double stand = up > feet && up <= feet + STEP ? up : feet;
        for (Region r : world.regionsNear(px, py, RADIUS)) {
            if (!Geometry.discTouchesPoly(px, py, RADIUS, r.xs, r.ys)) continue;
            if (r.ceil <= stand || r.floor >= head(stand)) continue;    // wholly below or above us
            if (r.floor > stand + STEP || r.ceil < head(stand)) return true;
        }
        for (Shape s : world.shapesNear(px, py, RADIUS))
            if (s.bottomAt(px, py) < head(stand) && s.topAt(px, py) > stand + STEP
                    && Geometry.overlaps(px, py, RADIUS, s)) return true;
        return false;
    }

    /** { highest surface we can stand on, lowest thing above our head } */
    private double[] support(double px, double py, double feet) {
        double ground = Double.NEGATIVE_INFINITY;
        for (Region r : world.regionsNear(px, py, RADIUS)) {
            if (!Geometry.discTouchesPoly(px, py, RADIUS, r.xs, r.ys)) continue;
            if (r.floor <= feet + STEP) ground = Math.max(ground, r.floor);
        }
        for (Shape s : world.shapesNear(px, py, RADIUS)) {
            if (!Geometry.overlaps(px, py, RADIUS, s)) continue;
            double top = s.topAt(px, py);        // a tilted top is a different height under each foot
            if (top <= feet + STEP) ground = Math.max(ground, top);
        }
        if (ground == Double.NEGATIVE_INFINITY) ground = feet;

        // The ceiling is whatever is above the surface we would stand on, so the underside of a
        // stair we are climbing onto does not count as our own ceiling.
        double ceil = Double.POSITIVE_INFINITY;
        for (Region r : world.regionsNear(px, py, RADIUS)) {
            if (!Geometry.discTouchesPoly(px, py, RADIUS, r.xs, r.ys)) continue;
            if (r.ceil > ground) ceil = Math.min(ceil, r.ceil);
        }
        for (Shape s : world.shapesNear(px, py, RADIUS))
            if (Geometry.overlaps(px, py, RADIUS, s) && s.bottomAt(px, py) >= ground + STEP)
                ceil = Math.min(ceil, s.bottomAt(px, py));
        return new double[] {ground, ceil};
    }

    private Renderer.Camera camera() {
        cam.x = x;
        cam.y = y;
        cam.dirX = Math.cos(angle);
        cam.dirY = Math.sin(angle);
        cam.eye = viewFeet + eyeH;
        cam.fisheye = fisheye;                              // pitch and the render window: preparePitch()
        cam.baked = baked && lighting != null;
        return cam;
    }

    private RayView.View view() {
        return new RayView.View(x, y, angle, fisheye, Math.toDegrees(pitch), shear);
    }

    // ---- Rendering ----

    /**
     * Change the render resolution, which is the ray count: every buffer from the renderer's own
     * pixels to the image the window blits is sized from it, so they are all made again. The window
     * keeps its size and its HUD - only the picture inside it gets coarser or finer. Called from the
     * render loop, never from the key handler, because the loop is reading these arrays.
     */
    private void setScale(int ix) {
        scaleIx = Math.max(0, Math.min(DynamicResolution.LADDER.length - 1, ix));
        int w = Math.max(16, (int) Math.round(winW * DynamicResolution.LADDER[scaleIx]));
        int h = Math.max(16, (int) Math.round(w * (double) baseH / baseW));   // --size's shape, the window's scale
        if (w == W && h == H) return;
        W = w;
        H = h;
        RW = w * SS;
        RH = h * SS;
        image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        out = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        hi = SS == 1 ? out : new int[RW * RH];
        srcW = RW;
        srcH = RH;
        src = new int[RW * RH];
        renderer.setView(RW, RH);
        renderer.resize(srcW, srcH, src);
        renderer.setFov(fovDeg);
        traceI = Math.min(traceI, RW - 1);
        traceJ = Math.min(traceJ, RH - 1);
        hoverColumn = hoverRow = -1;
    }

    /** Render one frame, tilt it, and when supersampling box-filter it down to the output image. */
    private void frame() {
        int want = wantScale;
        if (want >= 0) {
            wantScale = -1;
            setScale(want);
        }
        Renderer.Camera c = camera();
        preparePitch(c);
        // Recording one column's ray costs allocations every frame; only the ray view and a
        // screenshot's -rays.png read it.
        renderer.traceColumn = rayView.visible() || c.captureDepth
                ? sourceColumn(traceI >= 0 ? traceI : RW / 2, traceJ >= 0 ? traceJ : RH / 2) : -1;
        renderer.render(c);
        if (c.captureDepth) {
            depth = new float[W * H];
            hiDepth = SS == 1 ? depth : new float[RW * RH];
            albedo = new int[W * H];
            hiAlbedo = SS == 1 ? albedo : new int[RW * RH];
        }
        warp();
        if (SS > 1) {
            downsample();
            if (c.captureDepth) downsampleDepth();
        }
    }

    /**
     * Looking up and down.
     *
     * A column renderer can only draw columns that stay vertical, and on its own that means
     * y-shearing: slide the horizon, keep every vertical edge vertical. That is not what a camera
     * does when it tilts - looking up, verticals converge towards the top of the screen, and without
     * that the top and bottom of the picture get stretched, which reads as a vertical fisheye.
     *
     * Both are pinhole cameras at the same eye point, one with an upright image plane and one with a
     * tilted one, so each is an exact projective warp of the other, and for a pure pitch that warp
     * is simple row by row. Measuring v upwards from the centre of the screen, v' upwards from the
     * horizon of the upright image, and p = pitch:
     *
     *   z = F cos p - v sin p      s = F / z      v' = F (F sin p + v cos p) / z
     *
     * Output row v shows upright row v', stretched horizontally about the centre by s. So the
     * renderer draws the upright view - taller than the screen, and wider for the rows where s > 1
     * (the top when looking up) - and warp() resamples it. At p = 0 this is an exact 1:1 copy.
     */
    private void preparePitch(Renderer.Camera c) {
        double h2 = RH / 2.0, w2 = RW / 2.0;
        warpShear = shear;
        warpSin = Math.sin(pitch);
        warpCos = Math.cos(pitch);
        warpTan = Math.tan(pitch);
        double sMax = Math.max(rowScale(h2), rowScale(-h2));   // the widest row is the top or the bottom one
        double vHi = rowSource(h2), vLo = rowSource(-h2);       // rowSource is monotonic in v
        int needW = 2 * (int) Math.ceil(w2 * sMax) + 4;
        int needH = (int) Math.ceil(vHi - vLo) + 4;
        if (needW > srcW || needH > srcH) {                     // grow only: an unused margin costs nothing
            srcW = Math.max(srcW, (int) (needW * 1.1));
            srcH = Math.max(srcH, (int) (needH * 1.1));
            src = new int[srcW * srcH];
            renderer.resize(srcW, srcH, src);
        }
        warpCx = renderer.centerX();
        warpHz = vHi + 2;                                       // the top of the screen lands on source row ~2
        warpX0 = Math.max(0, (int) Math.floor(warpCx - needW / 2.0));
        warpX1 = Math.min(srcW, warpX0 + needW);
        warpY1 = Math.min(srcH, needH);
        c.pitch = warpHz - srcH / 2.0;                          // the renderer puts the horizon at H/2 + pitch
        c.x0 = warpX0;
        c.x1 = warpX1;
        c.y0 = 0;
        c.y1 = warpY1;
    }

    /** Horizontal stretch s for output row v (measured up from the centre). */
    private double rowScale(double v) {
        if (warpShear) return 1;
        double F = renderer.focal();
        return F / Math.max(1e-3, F * warpCos - v * warpSin);
    }

    /** The height v' in the upright image that output row v shows. */
    private double rowSource(double v) {
        double F = renderer.focal();
        if (warpShear) return v + F * warpTan;
        return F * (F * warpSin + v * warpCos) / Math.max(1e-3, F * warpCos - v * warpSin);
    }

    /** Resample the upright render into the tilted output, one source row per output row. */
    private void warp() {
        double w2 = RW / 2.0, h2 = RH / 2.0;
        int[] s = src;
        int sw = srcW, xLo = warpX0, xHi = warpX1 - 1, yHi = warpY1 - 1;
        IntStream.range(0, RH).parallel().forEach(j -> {
            double v = h2 - (j + 0.5), k = rowScale(v);
            int ys = Math.max(0, Math.min(yHi, (int) Math.floor(warpHz - rowSource(v))));
            int srow = ys * sw, orow = j * RW;
            double sx = warpCx + (0.5 - w2) * k;               // source x of output column 0's centre
            if (cam.captureDepth) {
                for (int i = 0; i < RW; i++, sx += k) {
                    int p = srow + Math.max(xLo, Math.min(xHi, (int) Math.floor(sx)));
                    hi[orow + i] = s[p];
                    hiDepth[orow + i] = renderer.depth[p];
                    hiAlbedo[orow + i] = renderer.albedo[p];
                }
            } else {
                for (int i = 0; i < RW; i++, sx += k)
                    hi[orow + i] = s[srow + Math.max(xLo, Math.min(xHi, (int) Math.floor(sx)))];
            }
        });
    }

    /** The source (renderer) column that output pixel (i, j) comes from. */
    private int sourceColumn(int i, int j) {
        double k = rowScale(RH / 2.0 - (j + 0.5));
        return Math.max(warpX0, Math.min(warpX1 - 1, (int) Math.floor(warpCx + (i + 0.5 - RW / 2.0) * k)));
    }

    /** Where source column col shows up on output row j, in output pixels from the left edge. */
    private double outputX(int col, int j) {
        double k = rowScale(RH / 2.0 - (j + 0.5));
        return (col + 0.5 - warpCx) / k + RW / 2.0;
    }

    /** Average each SS x SS block of the render buffer into one output pixel. */
    private void downsample() {
        final int n = SS * SS, half = n / 2;
        IntStream.range(0, H).parallel().forEach(y -> {
            int row = y * W;
            for (int x = 0; x < W; x++) {
                int r = 0, g = 0, b = 0;
                for (int sy = 0; sy < SS; sy++) {
                    int base = (y * SS + sy) * RW + x * SS;
                    for (int sx = 0; sx < SS; sx++) {
                        int c = hi[base + sx];
                        r += (c >> 16) & 255;
                        g += (c >> 8) & 255;
                        b += c & 255;
                    }
                }
                out[row + x] = (((r + half) / n) << 16) | (((g + half) / n) << 8) | ((b + half) / n);
            }
        });
    }

    /** An antialiased pixel can show several surfaces. Keep the nearest hit in the same SS x SS
     *  block the colour averages, leaving zero only when the whole block is sky. Albedo keeps
     *  that hit's map colour too: averaging different surfaces would invent a colour. */
    private void downsampleDepth() {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float nearest = 0;
                int sample = (y * SS + SS / 2) * RW + x * SS + SS / 2;
                for (int sy = 0; sy < SS; sy++) {
                    int base = (y * SS + sy) * RW + x * SS;
                    for (int sx = 0; sx < SS; sx++) {
                        float d = hiDepth[base + sx];
                        if (d > 0 && (nearest == 0 || d < nearest)) {
                            nearest = d;
                            sample = base + sx;
                        }
                    }
                }
                depth[y * W + x] = nearest;
                albedo[y * W + x] = nearest > 0 ? hiAlbedo[sample] : out[y * W + x];
            }
        }
    }

    // ---- Output ----

    private void present(Canvas canvas) {
        BufferStrategy bs = canvas.getBufferStrategy();
        do {
            do {
                Graphics2D g = (Graphics2D) bs.getDrawGraphics();
                int cw = canvas.getWidth(), ch = canvas.getHeight();
                double sc = Math.min(cw / (double) W, ch / (double) H);
                int dw = (int) (W * sc), dh = (int) (H * sc);
                viewX = (cw - dw) / 2;
                viewY = (ch - dh) / 2;
                viewW = dw;
                viewH = dh;
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, cw, ch);
                drawFrame(g, viewX, viewY, dw, dh, rayView.visible());
                g.dispose();
            } while (bs.contentsRestored());
            bs.show();
        } while (bs.contentsLost());
        Toolkit.getDefaultToolkit().sync();
    }

    /** Every view in a file, one per line: "out.png x y feet heading". Baking the lightmaps for
     *  a map the size of Haven takes a minute and a half, and it is the same bake for every camera,
     *  so a set of comparison shots belongs in one run rather than one run each. */
    private void screenshots(Path list) throws Exception {
        for (String line : Files.readAllLines(list)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] f = t.split("\\s+");
            if (f.length < 5) { System.err.println("skipping: " + t); continue; }
            exactFeet = Double.parseDouble(f[3]);
            screenshot(new File(f[0]), new double[]{Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                                                    Double.parseDouble(f[4]), 0});
        }
    }

    private void screenshot(File out, double[] at) throws Exception {
        if (at != null) {
            x = at[0];
            y = at[1];
            angle = Math.toRadians(at[2]);
            pitch = Math.toRadians(at[3]);
            if (Double.isNaN(exactFeet)) {
                placeOnGround();
            } else {
                feet = viewFeet = exactFeet;
                vz = 0;
                grounded = true;
            }
        }
        // the column argument is an output column, so it means the same place whatever --ss is
        int col = at != null && at.length > 4 ? Math.max(0, Math.min(W - 1, (int) at[4])) : W / 2;
        traceI = col * SS + SS / 2;
        traceJ = -1;
        cam.captureDepth = true;
        try {
            frame();
        } finally {
            cam.captureDepth = false;
        }
        File plainOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-plain.png"));
        ImageIO.write(image, "png", plainOut);                  // the warped view, before any overlays or scaling
        System.out.println("wrote " + plainOut);
        File depthOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-depth.pfm"));
        writeDepth(depthOut);
        System.out.println("wrote " + depthOut);
        File albedoOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-albedo.png"));
        BufferedImage alb = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        alb.setRGB(0, 0, W, H, albedo, 0, W);
        ImageIO.write(alb, "png", albedoOut);
        System.out.println("wrote " + albedoOut);
        depth = hiDepth = renderer.depth = null;
        albedo = hiAlbedo = renderer.albedo = null;

        int scale = W < 1000 ? 2 : 1;          // upscale small renders so the HUD text stays readable
        BufferedImage img = new BufferedImage(W * scale, H * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        drawFrame(g, 0, 0, W * scale, H * scale, true);
        g.dispose();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);

        // Ray view of the same frame
        File raysOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-rays.png"));
        BufferedImage rays = new BufferedImage(640, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = rays.createGraphics();
        rayView.draw(rg, rays.getWidth(), rays.getHeight(), view());
        rg.dispose();
        ImageIO.write(rays, "png", raysOut);
        System.out.println("wrote " + raysOut);
    }

    /** Greyscale PFM: negative scale selects little endian; rows run from the bottom upwards. */
    private void writeDepth(File file) throws Exception {
        try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(file))) {
            stream.write(("Pf\n" + W + " " + H + "\n-1.0\n").getBytes(StandardCharsets.US_ASCII));
            ByteBuffer row = ByteBuffer.allocate(W * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = H - 1; y >= 0; y--) {
                row.clear();
                for (int x = 0; x < W; x++) row.putFloat(depth[y * W + x]);
                stream.write(row.array());
            }
        }
    }

    private void drawFrame(Graphics2D g, int ox, int oy, int dw, int dh, boolean markColumn) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, ox, oy, dw, dh, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int col = renderer.traceColumn;
        if (markColumn && col >= 0) {
            // The column shown in red in the ray view, mapped back through the pitch warp: one ray
            // is a vertical line in the world, so when the view tilts it leans like every other vertical.
            double sx = (double) dw / RW, sy = (double) dh / RH;
            g.setColor(new Color(255, 70, 50, 140));
            g.draw(new Line2D.Double(ox + outputX(col, 0) * sx, oy + 0.5 * sy,
                                     ox + outputX(col, RH - 1) * sx, oy + (RH - 0.5) * sy));
        }
        drawHud(g, ox + 12, oy + 20);
        if (showMap) drawMinimap(g, ox + dw - 12, oy + 12, (int) Math.max(160, Math.min(dw, dh) * 0.42));
    }

    /** The storey the player is actually standing on, rather than just the lowest one here. */
    private Region here() {
        Region best = null;
        for (Region r : world.regionsNear(x, y, RADIUS)) {
            if (!Geometry.discTouchesPoly(x, y, RADIUS, r.xs, r.ys)) continue;
            if (feet + 0.05 < r.floor || feet >= r.ceil) continue;
            if (best == null || r.floor > best.floor) best = r;
        }
        return best != null ? best : world.regionAt(x, y);
    }

    /** A key hint, spelled the way this player's keyboard labels the keys in those positions. */
    private static String key(int... positions) { return Keys.labels(positions); }

    private void drawHud(Graphics2D g, int left, int top) {
        Region r = here();
        String[] lines = {
            String.format("%s   %.0f fps   %dx%d%s   %,d rays   FOV %.0f deg   %s", world.name, fps, W, H,
                    SS > 1 ? " x" + SS + " AA" : "", RW, renderer.fov(),
                    fisheye ? "fisheye demo (straight-line distance, wrong)" : "perpendicular distance")
                    + (lighting == null ? "   flat lighting (--flat)" : baked ? "   baked lighting" : "   flat lighting (L)"),
            String.format("%s   (%.1f, %.1f)   feet %.2f m   pitch %+.0f deg %s", r == null ? "-" : r.name, x, y, feet,
                    Math.toDegrees(pitch), shear ? "y-shearing (old)" : "true perspective"),
            // The controls go by where a key sits, so name each one the way this keyboard labels it.
            key(Keys.W, Keys.A, Keys.S, Keys.D) + " move   drag mouse / arrows look   Space jump   "
                    + key(Keys.C) + " crouch   Shift run   " + key(Keys.G) + " fly",
            key(Keys.LEFT_BRACKET) + " " + key(Keys.RIGHT_BRACKET) + " FOV   " + key(Keys.COMMA) + " "
                    + key(Keys.PERIOD) + " rays   " + key(Keys.V) + (autoRes ? " auto res on   " : " auto res off   ")
                    + key(Keys.F) + " fisheye   " + key(Keys.P) + " pitch   "
                    + key(Keys.L) + " lighting   " + key(Keys.R) + " ray view   " + key(Keys.M)
                    + " minimap   Esc quit   hover to pick a column",
        };
        g.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
        for (int i = 0; i < lines.length; i++) {
            g.setColor(new Color(0, 0, 0, 160));
            g.drawString(lines[i], left + 1, top + i * 18 + 1);
            g.setColor(new Color(235, 235, 235));
            g.drawString(lines[i], left, top + i * 18);
        }
    }

    /** The minimap (see Minimap): one prebuilt image, plus the player on top of it. */
    private void drawMinimap(Graphics2D g, int right, int top, int size) {
        if (minimap == null) minimap = new Minimap(world, x, y, feet);
        double mw = minimap.image.getWidth() * Minimap.CELL, mh = minimap.image.getHeight() * Minimap.CELL;
        // The map may turn its minimap by quarter turns, the way Valorant shows each map the same
        // way round every time; a quarter turn swaps which side of the panel is the long one.
        int quarter = Math.floorMod((int) Math.round(world.minimapRotate / 90), 4);
        double pw = quarter % 2 == 0 ? mw : mh, ph = quarter % 2 == 0 ? mh : mw;
        double s = Math.min(size / pw, size / ph);
        double ox = right - pw * s, oy = top;
        AffineTransform saved = g.getTransform();
        g.setColor(new Color(255, 255, 255, 200));
        g.fill(new Rectangle2D.Double(ox - 6, oy - 6, pw * s + 12, ph * s + 12));
        g.translate(ox + pw * s / 2, oy + ph * s / 2);
        g.rotate(quarter * Math.PI / 2);                              // clockwise on screen
        g.scale(s, s);
        g.translate(-minimap.ix0 - mw / 2, -minimap.iy0 - mh / 2);
        g.setStroke(new BasicStroke((float) (1.5 / s)));

        Object smoothing = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform px = AffineTransform.getTranslateInstance(minimap.ix0, minimap.iy0);
        px.scale(Minimap.CELL, Minimap.CELL);
        g.drawImage(minimap.image, px, null);
        if (smoothing != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smoothing);

        double dx = Math.cos(angle), dy = Math.sin(angle), pl = renderer.planeHalfWidth(), len = 4;
        g.setColor(new Color(235, 150, 20, 220));
        g.draw(new Line2D.Double(x, y, x + (dx + dy * pl) * len, y + (dy - dx * pl) * len));
        g.draw(new Line2D.Double(x, y, x + (dx - dy * pl) * len, y + (dy + dx * pl) * len));
        g.setColor(new Color(255, 80, 60));
        g.fill(new Ellipse2D.Double(x - RADIUS, y - RADIUS, RADIUS * 2, RADIUS * 2));
        g.setTransform(saved);
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("usage: java -cp out engine.Main [map.json] [--size WxH] [--ss N] [--bench]");
        System.err.println("       java -cp out engine.Main [map.json] [--size WxH] [--ss N] --shot out.png [x y angle pitch [column]]");
        System.err.println("       java -cp out engine.Main [map.json] [--size WxH] --shots views.txt   (one \"out.png x y feet heading\" per line)");
        System.err.println("  --size  render resolution, the ray count (default " + DEFAULT_W + "x" + DEFAULT_H + ")");
        System.err.println("  --window  window size; the render is scaled up to it (default " + DEFAULT_WINDOW_W + "x" + DEFAULT_WINDOW_H + ", fitted to the screen)");
        System.err.println("  --feet  starting floor height in metres, to begin on an upper storey (e.g. 3.6)");
        System.err.println("  --shear look up / down the old way (y-shearing) instead of true perspective");
        System.err.println("  --flat  skip baking the lightmaps and use the old flat lighting");
        System.err.println("  --ss    supersampling factor 1-8: renders at size*N and averages down (default 1).");
        System.err.println("          Rays cast per frame = width * N.");
    }
}

package engine;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * The window, the frame loop and the buffers between the two.
 *
 * What is left here is what only the running game needs: open a window, read the controls once a
 * frame, render, warp, scale, show, and stop when asked. Everything it used to do as well now has
 * a file of its own - {@link Options} reads the command line, {@link Player} moves the player,
 * {@link Warp} tilts the picture, {@link Hud} draws over it, {@link Capture} runs the headless
 * modes, and the second "ray view" window is {@link RayView}.
 *
 * The buffers are the reason this is still one class. What the renderer draws into, what the warp
 * resamples into, and what the window blits are three different arrays with three different sizes,
 * all of which change together whenever the frame-time controller moves the render scale; they are
 * kept together so that nothing can be halfway through changing them. They are also why almost
 * everything here runs on one thread - see {@link Renderer}, which now enforces that rather than
 * asking for it.
 *
 * Usage: java -cp out engine.Main [map.json] [--shot out.png [x y angle pitch [column]]] [--bench]
 */
public final class Main {
    // What is rendered and what is shown are separate: 1280x720 rays by default, scaled up into a
    // window of its own size. Tying the ray count to the window made fullscreen slow for no detail
    // anyone asked for; the frame-time controller (see steer) moves the render size, not the window.
    static final int DEFAULT_W = 1280, DEFAULT_H = 720;
    static final int DEFAULT_WINDOW_W = 1920, DEFAULT_WINDOW_H = 1080;

    // The render size is a step on DynamicResolution.LADDER, a share of the window: the frame-time
    // controller moves along it, and , and . step along it by hand (which turns the controller off;
    // V turns it back on).

    /** Output resolution: the size of the image that reaches the window. Changes with the render
     *  scale, so it is not final; the window does not change, the picture in it just gets coarser
     *  or finer. */
    int W, H;
    /** Supersampling factor. The engine renders at (W*SS) x (H*SS) and the result is box-filtered
     *  down to W x H, so SS is also the number of rays per output column. SS = 1 disables it. */
    final int SS;
    /** Render resolution = the ray count. Equals W when SS is 1. */
    int RW, RH;

    private final World world;
    BufferedImage image;
    int[] out;                    // the W x H pixels the window sees
    private int[] hi;             // the RW x RH tilted view after the pitch warp; same array as `out` when SS = 1
    float[] depth, hiDepth; // shot only: output depth and the same pitch warp before downsampling
    int[] albedo, hiAlbedo;       // shot only: unshaded map colours, following the depth samples
    private int[] src;            // what the renderer writes: the upright (y-sheared) view plus overscan
    private int srcW, srcH;
    final Renderer renderer;
    final RayView rayView;
    final Renderer.Camera cam = new Renderer.Camera();
    private final Hud hud;

    final Player player;

    // Input (the mouse is written on the EDT and read by the main loop; the keys are read straight
    // from the machine by Keys, once a frame)
    private final Set<Integer> heldLastFrame = new HashSet<>();  // so a tap fires once, not every frame
    private boolean listening;                                   // is a window of ours in front?
    private double mouseDX, mouseDY;
    private volatile boolean showMap = true, fisheye = false;
    volatile boolean shear = false;                              // P: the old y-shearing pitch, for comparison
    private volatile boolean baked = true;                       // L: baked lighting, or the old flat model
    Lighting lighting;                                           // null with --flat
    private volatile double fovDeg = Renderer.DEFAULT_FOV;
    private volatile int hoverColumn = -1, hoverRow = -1;        // which pixel of the main view the mouse is over
    int traceI = -1, traceJ = -1;                                // the output pixel whose ray the ray view traces

    private final Warp warp = new Warp();                        // this frame's pitch warp; see Warp
    /** --gpu: the card shades the frame the CPU's columns worked out. Null on the CPU path, and
     *  rebuilt whenever the pitch warp grows the render buffer under it. */
    volatile boolean useGpu;
    private GpuWalls gpu;

    /** What the card's side of a frame cost, for --bench with -Dgpu.stats=true. */
    void gpuStats() {
        if (gpu != null) gpu.stats();
    }
    private GpuSpans spans;
    private GpuMasks masks;
    private GpuLights gpuLights;
    private GpuTextures gpuImages;
    private GpuMaterials gpuMaterials;
    private int[] gpuPixels;                                     // only when part of the frame is not ported
    private volatile int viewX, viewY, viewW, viewH;             // where the main view sits inside the window
    private final int baseW, baseH;                              // the resolution --size asked for
    private int winW = DEFAULT_WINDOW_W, winH = DEFAULT_WINDOW_H; // --window: the output, which the picture is scaled to
    private int scaleIx;                                         // where on DynamicResolution.LADDER we are now
    private int targetFps = 60;                                  // --fps: the controller's budget; 0 = off
    private volatile boolean autoRes = true;                     // V: is the controller steering?
    private DynamicResolution steer;
    private volatile int wantScale = -1;                         // set by a key, applied between frames
    /** False once something has asked the game to stop: Escape, the window's close button, or a
     *  signal. Written from the event thread and from a shutdown hook, read by the loop. */
    private volatile boolean running = true;
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
        player = new Player(world);
        hud = new Hud(world, renderer, player);
    }

    void standOn(double z) {
        player.standOn(z);
    }

    public static void main(String[] args) throws Exception {
        // Before AWT starts, and only when a window is going to open - see Keys. A headless run
        // has no HUD to name, and on a machine with no window service to ask, the question hangs.
        if (!Options.headless(args)) Keys.readLabels();
        Options o = Options.parse(args);
        if (o == null) return;
        if (Options.headless(args)) System.setProperty("java.awt.headless", "true");
        // Before AWT. A context asked for after the toolkit has started gets no accelerated
        // pixel format on macOS - the same ordering trap that once left the window taking no
        // keys, in the other direction. A platform with no backend says so in a sentence and
        // carries on with the CPU renderer, which runs anywhere; asking GlPlatform rather than
        // Gl is deliberate, because loading Gl is what fails.
        if (o.gpu) {
            String why = GlPlatform.missing();
            if (why != null) {
                System.err.println(why);
                o.gpu = false;
            } else {
                Gl.context();
            }
        }

        Main game = new Main(World.load(Path.of(o.map)), o.w, o.h, o.ss);
        game.winW = o.winW;
        game.winH = o.winH;
        game.targetFps = o.targetFps;
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
        game.shear = o.shear;
        game.useGpu = o.gpu;
        if (!o.flat) {
            game.lighting = Lighting.bake(game.world);
            game.renderer.setLighting(game.lighting);
        }
        if (!Double.isNaN(o.startFeet)) game.standOn(o.startFeet);
        Capture capture = new Capture(game);
        if (o.bench) capture.bench();
        else if (o.verify != null) capture.verify(Path.of(o.verify));
        else if (o.shots != null) capture.screenshots(Path.of(o.shots));
        else if (o.shot != null) capture.screenshot(new File(o.shot), o.at);
        else game.run();
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
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent e) { running = false; }
            });
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
        // Ctrl-C, or a shell closing, arrives here rather than stopping the JVM where it stands.
        // The hook waits for the loop to finish the frame it is on, so that a bake being written
        // at that moment is either replaced whole or not at all - which is what LightCache's
        // write-then-rename is for, and which only holds if the process lives long enough to
        // finish the rename.
        Thread loop = Thread.currentThread();
        Thread stopped = new Thread(() -> {
            running = false;
            try {
                loop.join(2000);
            } catch (InterruptedException giveUp) {
                Thread.currentThread().interrupt();
            }
        }, "columnray-stop");
        Runtime.getRuntime().addShutdownHook(stopped);

        long last = System.nanoTime();
        while (running) {
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
        try {
            Runtime.getRuntime().removeShutdownHook(stopped);
        } catch (IllegalStateException alreadyShuttingDown) {
            // The hook is what stopped us. There is nothing to remove and nothing to worry about.
        }
        SwingUtilities.invokeAndWait(() -> {
            rayView.close();
            for (Window w : Window.getWindows()) w.dispose();
        });
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
                case Keys.ESCAPE -> running = false;
                case Keys.M -> showMap = !showMap;
                case Keys.G -> { player.flying = !player.flying; player.vz = 0; player.grounded = false; }
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

    /** One frame: read the controls, hand them to the player, and tell the renderer what changed
     *  about the view. Nothing here knows how gravity works, and Player knows nothing about keys. */
    private void update(double dt) {
        listening = !Keys.physical() || inFront();
        taps();
        double fov = fovDeg;
        if (Math.abs(renderer.fov() - fov) > 1e-6) renderer.setFov(fov);
        int hc = hoverColumn, hr = hoverRow;
        traceI = hc >= 0 && hr >= 0 ? hc : -1;                   // the pixel the ray view traces; -1 = centre
        traceJ = hc >= 0 && hr >= 0 ? hr : -1;
        player.step(dt, controls());
    }

    /** What the keyboard and the mouse are asking for, as movement rather than as keys. */
    private Player.Move controls() {
        double mdx, mdy;
        synchronized (this) { mdx = mouseDX; mdy = mouseDY; mouseDX = mouseDY = 0; }
        return new Player.Move(
                (down(Keys.RIGHT) || down(Keys.E) ? 1 : 0) - (down(Keys.LEFT) || down(Keys.Q) ? 1 : 0),
                (down(Keys.UP) ? 1 : 0) - (down(Keys.DOWN) ? 1 : 0),
                (down(Keys.W) ? 1 : 0) - (down(Keys.S) ? 1 : 0),
                (down(Keys.D) ? 1 : 0) - (down(Keys.A) ? 1 : 0),
                mdx, mdy,
                down(Keys.C) || down(Keys.CONTROL) || down(Keys.RIGHT_CONTROL),
                down(Keys.SHIFT) || down(Keys.RIGHT_SHIFT),
                down(Keys.SPACE));
    }

    private Renderer.Camera camera() {
        cam.x = player.x;
        cam.y = player.y;
        cam.dirX = Math.cos(player.angle);
        cam.dirY = Math.sin(player.angle);
        cam.eye = player.eye();
        cam.fisheye = fisheye;                              // pitch and the render window: preparePitch()
        cam.baked = baked && lighting != null;
        return cam;
    }

    RayView.View view() {
        return new RayView.View(player.x, player.y, player.angle, fisheye, Math.toDegrees(player.pitch), shear);
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
    void frame() {
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
                ? warp.sourceColumn(traceI >= 0 ? traceI : RW / 2, traceJ >= 0 ? traceJ : RH / 2) : -1;
        if (spans != null) spans.reset();
        if (masks != null) masks.reset();
        renderer.render(c);
        if (gpu != null) shadeOnGpu(c);
        if (c.captureDepth) {
            depth = new float[W * H];
            hiDepth = SS == 1 ? depth : new float[RW * RH];
            albedo = new int[W * H];
            hiAlbedo = SS == 1 ? albedo : new int[RW * RH];
        }
        warp.apply(src, srcW, hi, renderer.depth, renderer.albedo, hiDepth, hiAlbedo);
        if (SS > 1) {
            downsample();
            if (c.captureDepth) downsampleDepth();
        }
    }

    /**
     * Put the card's picture into the render buffer.
     *
     * A surface whose shading is not ported yet emits no span, so the card would draw sky
     * through it. While that is still true the card's frame is merged rather than taken: every
     * pixel the CPU marked as one it painted itself keeps the CPU's colour, and the rest comes
     * from the card. Once every resource is on the card nothing is ever marked, and this is a
     * straight read into the render buffer with no merge and no second buffer.
     */
    private void shadeOnGpu(Renderer.Camera c) {
        double horizon = srcH / 2.0 + c.pitch;
        if (!spans.anySkipped()) {
            gpu.draw(spans, masks, src, c, horizon, renderer.focal(), RH);
            return;
        }
        if (gpuPixels == null || gpuPixels.length != src.length) gpuPixels = new int[src.length];
        gpu.draw(spans, masks, gpuPixels, c, horizon, renderer.focal(), RH);
        IntStream.range(0, srcH).parallel().forEach(y -> {
            int row = y * srcW;
            for (int x = 0; x < srcW; x++) if (!spans.skipped(x, y)) src[row + x] = gpuPixels[row + x];
        });
    }

    /** Work out this frame's warp, grow the render buffer if the tilt needs more overscan than it
     *  has, and tell the camera which part of that buffer to draw. See {@link Warp}. */
    private void preparePitch(Renderer.Camera c) {
        warp.plan(player.pitch, shear, RW, RH, renderer.focal());
        if (warp.needW() > srcW || warp.needH() > srcH) {        // grow only: an unused margin costs nothing
            srcW = Math.max(srcW, (int) (warp.needW() * 1.1));
            srcH = Math.max(srcH, (int) (warp.needH() * 1.1));
            src = new int[srcW * srcH];
            renderer.resize(srcW, srcH, src);
        }
        warp.place(renderer.centerX(), srcW, srcH, c);
        if (useGpu && (gpu == null || spans == null || spans.columns() != srcW)) {
            if (gpu != null) gpu.close();
            gpu = new GpuWalls(srcW, srcH, srcW);
            if (lighting != null) {
                if (gpuLights == null) gpuLights = new GpuLights(lighting);
                gpu.setLights(gpuLights);
            }
            if (gpuImages == null) {
                java.util.List<Materials.Texture> imgs = GpuTextures.of(world);
                if (!imgs.isEmpty()) {
                    gpuImages = new GpuTextures(imgs);
                    gpuMaterials = new GpuMaterials(world, gpuImages);
                }
            }
            if (gpuImages != null) {
                gpu.setImages(gpuImages, gpuMaterials);
                renderer.setMaterials(gpuMaterials);
            }
            spans = new GpuSpans(srcW, srcH);
            masks = new GpuMasks(srcW);
            gpuPixels = null;
            renderer.captureSpans(spans);
            renderer.captureMasks(masks);
            renderer.shadeUnderCard(false);
        }
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

    void drawFrame(Graphics2D g, int ox, int oy, int dw, int dh, boolean markColumn) {
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
            g.draw(new Line2D.Double(ox + warp.outputX(col, 0) * sx, oy + 0.5 * sy,
                                     ox + warp.outputX(col, RH - 1) * sx, oy + (RH - 0.5) * sy));
        }
        hud.draw(g, ox + 12, oy + 20, status());
        if (showMap) hud.drawMinimap(g, ox + dw - 12, oy + 12, (int) Math.max(160, Math.min(dw, dh) * 0.42));
    }

    private Hud.Status status() {
        return new Hud.Status(fps, W, H, SS, RW, fisheye,
                lighting == null ? "flat lighting (--flat)" : baked ? "baked lighting" : "flat lighting (L)",
                shear, autoRes);
    }


}

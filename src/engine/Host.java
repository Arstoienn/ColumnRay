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
import java.util.Map;
import java.util.stream.IntStream;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * The engine, from a game's point of view: a window, a frame loop and the buffers between the two.
 *
 * What is here is what every game needs and no game should have to write: open a window, read the
 * controls, ask the {@link Game} where to look, render that, tilt it, scale it, show it, and stop
 * when asked. What is deliberately not here is anything a game would want to decide - which key
 * walks forward, how fast, how tall the player is, what the overlay says. That lives in the
 * {@code game} package and arrives through {@link Game} and the public methods below.
 *
 * The buffers are the reason this is still one class. What the renderer draws into, what the warp
 * resamples into, and what the window blits are three different arrays with three different sizes,
 * all of which change together whenever the frame-time controller moves the render scale; they are
 * kept together so that nothing can be halfway through changing them. They are also why almost
 * everything here runs on one thread - see {@link Renderer}, which now enforces that rather than
 * asking for it.
 */
public final class Host {
    // What is rendered and what is shown are separate: 1280x720 rays by default, scaled up into a
    // window of its own size. Tying the ray count to the window made fullscreen slow for no detail
    // anyone asked for; the frame-time controller (see steer) moves the render size, not the window.
    public static final int DEFAULT_W = 1280, DEFAULT_H = 720;
    public static final int DEFAULT_WINDOW_W = 1920, DEFAULT_WINDOW_H = 1080;

    // The render size is a step on DynamicResolution.LADDER, a share of the window: the frame-time
    // controller moves along it, and the game may step along it by hand.

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
    /**
     * The most overscan the pitch warp may grow the upright image to, as a multiple of a level
     * frame - not as a count of pixels.
     *
     * A multiple, because the overscan a pitch needs is a fixed ratio of the frame at any render
     * size, so a budget in pixels would let the camera tilt further on a small window than on a
     * large one. The camera is a control and a control does not change its range when the
     * resolution does. As a multiple it stops at the same angle everywhere: about 55 degrees,
     * which is past what World.MAX_PITCH asks for and well short of the tangent running away - 68
     * degrees is 1,494 level frames, where the allocation itself used to fail.
     */
    private static final long OVERSCAN_LIMIT = 16;
    BufferedImage image;
    int[] out;                    // the W x H pixels the window sees
    private int[] hi;             // the RW x RH tilted view after the pitch warp; same array as `out` when SS = 1
    float[] depth, hiDepth; // shot only: output depth and the same pitch warp before downsampling
    int[] albedo, hiAlbedo;       // shot only: unshaded map colours, following the depth samples
    private int[] src;            // what the renderer writes: the upright (y-sheared) view plus overscan
    int srcW, srcH;
    final Renderer renderer;
    final RayView rayView;
    private final Renderer.Camera cam = new Renderer.Camera();
    private final Input input = new Input();

    Game game;                                                   // set by run(), or by a Capture
    /** The field of view the game asked for, kept so that a resize can set it again from the same
     *  number rather than from the renderer's round trip back out of the focal length. */
    private double fovDeg = Renderer.DEFAULT_FOV;
    /** The view the last frame was rendered from, for the ray view and for a screenshot. */
    private View last = new View();

    // Input (the mouse is written on the EDT and read by the main loop; the keys are read straight
    // from the machine by Keys, once a frame)
    private volatile int hoverColumn = -1, hoverRow = -1;        // which pixel of the main view the mouse is over
    int traceI = -1, traceJ = -1;                                // the output pixel whose ray the ray view traces

    private volatile boolean shear = false;                      // the old y-shearing pitch, for comparison
    Lighting lighting;                                           // null with --flat

    private final Warp warp = new Warp();                        // this frame's pitch warp; see Warp
    /** --gpu: the card shades the frame the CPU's columns worked out. Null on the CPU path, and
     *  rebuilt whenever the pitch warp grows the render buffer under it. */
    volatile boolean useGpu;

    /**
     * Turn the card on or off between frames.
     *
     * The two halves go together and must not be set apart. With the card on, the renderer is
     * told to leave the rows the card will draw uncoloured; a frame drawn without the card while
     * that is still true comes out full of holes. Nothing in the game toggles this - it is set
     * once from the command line - but {@code --gpu-verify} draws each view both ways, and the
     * pairing is what makes that safe.
     */
    void setUseGpu(boolean on) {
        useGpu = on;
        renderer.shadeUnderCard(!on);
    }

    /** The busiest column of the last frame: spans and masks. */
    String gpuMost() {
        return (spans == null ? 0 : spans.most()) + "/" + (masks == null ? 0 : masks.most());
    }

    /** How many pixels of the last frame the card was not given at all, because the surface's
     *  shading is not ported. Zero is what a finished card path looks like. */
    int gpuSkipped() {
        return spans == null ? 0 : spans.skipped();
    }

    /** How many spans and masks a frame had to hand back to the CPU for want of room. */
    int gpuDropped() {
        return (spans == null ? 0 : spans.dropped()) + (masks == null ? 0 : masks.dropped());
    }
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
    private final int winW, winH;                                // --window: the output, which the picture is scaled to
    private int scaleIx;                                         // where on DynamicResolution.LADDER we are now
    private final int targetFps;                                 // --fps: the controller's budget; 0 = off
    private volatile boolean autoRes = true;                     // is the controller steering?
    private DynamicResolution steer;
    private volatile int wantScale = -1;                         // set by a key, applied between frames
    /** False once something has asked the game to stop: the game itself, the window's close
     *  button, or a signal. Written from the event thread and from a shutdown hook, read by the
     *  loop. */
    private volatile boolean running = true;
    private double fps;

    /**
     * Everything the command line settled, applied in the order it has to be applied in: the card
     * is asked for before any window exists, and the bake happens before the first frame.
     */
    public Host(World world, Options o) {
        this.world = world;
        this.W = o.w;
        this.H = o.h;
        this.baseW = o.w;
        this.baseH = o.h;
        this.SS = o.ss;
        this.RW = o.w * o.ss;
        this.RH = o.h * o.ss;
        this.viewW = o.w;
        this.viewH = o.h;
        this.winW = o.winW;
        this.winH = o.winH;
        this.targetFps = o.targetFps;
        this.image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        this.out = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        this.hi = o.ss == 1 ? out : new int[RW * RH];   // SS = 1: the pitch warp writes straight into the window image
        this.srcW = RW;
        this.srcH = RH;
        this.src = new int[RW * RH];                    // grows on demand, see preparePitch()
        renderer = new Renderer(world, RW, RH, src);
        rayView = new RayView(world, renderer);
        // The map may ask for a grade on the way out; -Dgrade.sat / -Dgrade.lift override it while
        // one is being found. See Renderer.grade.
        Map<String, Object> lg = world.lighting == null ? Map.of() : world.lighting;
        Object gr = lg.get("grade");
        Map<String, Object> g = gr instanceof Map ? World.obj(gr) : Map.of();
        Renderer.grade(Double.parseDouble(System.getProperty("grade.sat",
                        String.valueOf(World.num(g, "saturation", 1.0)))),
                Double.parseDouble(System.getProperty("grade.lift",
                        String.valueOf(World.num(g, "lift", 0.0)))));
        Renderer.fogOn = !Boolean.FALSE.equals(lg.get("fog"));
        Renderer.hdr = o.hdr;
        Renderer.hdrBake = o.hdr;                       // the bake is decided once; the shading is not
        this.shear = o.shear;
        setUseGpu(o.gpu);
        if (!o.flat) {
            lighting = Lighting.bake(world);
            renderer.setLighting(lighting);
        }
    }

    /**
     * Is there a graphics card here to shade on, and say so if not.
     *
     * Asked before AWT starts: a context asked for after the toolkit has started gets no
     * accelerated pixel format on macOS - the same ordering trap that once left the window taking
     * no keys, in the other direction.
     *
     * Two different things can be missing and both end the same way: a sentence, and the CPU
     * renderer, which runs anywhere. There may be no backend for this operating system, which
     * GlPlatform can answer without loading Gl - deliberately, because loading Gl is what fails.
     * Or there may be a backend and no card for it to find: a Windows machine with no OpenGL
     * driver, a virtual machine, a CI runner that offers Microsoft's software renderer. That one
     * only shows up when the context is actually asked for, and it arrives as an
     * ExceptionInInitializerError out of Gl's own field initialisers, which is not a thing to let
     * out of main().
     */
    public static boolean graphicsCard() {
        String why = GlPlatform.missing();
        if (why == null) {
            try {
                Gl.context();
                // Which card, in one line, and on a machine with more than one, which cards it was
                // not. An integrated GPU draws the frame and reports success at a fraction of the
                // speed of the one beside it; see GlPlatform.note.
                System.err.println("gpu: " + Gl.device() + ", GL " + Gl.version());
                String note = GlPlatform.get().note(Gl.device());
                if (note != null) System.err.println("gpu: " + note);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                Throwable c = t.getCause() != null ? t.getCause() : t;
                why = c.getMessage() != null ? c.getMessage() : c.toString();
            }
        }
        if (why != null) System.err.println(why);      // both messages already say what happens next
        return why == null;
    }

    // ---- What a game may ask of the engine ----

    /** The map this engine was started on. */
    public World world() { return world; }

    /** Stop after this frame. */
    public void stop() { running = false; }

    /** The field of view, in degrees. Changing it is free; it only has to happen between frames,
     *  which is where {@link Game#update} runs. */
    public double fov() { return renderer.fov(); }

    public void setFov(double degrees) {
        fovDeg = degrees;
        if (Math.abs(renderer.fov() - degrees) > 1e-6) renderer.setFov(degrees);
    }

    /** Half the width of the camera plane at one metre: the shape of the view fan, for a minimap. */
    public double planeHalfWidth() { return renderer.planeHalfWidth(); }

    /** Is there a bake, or was this started with --flat? A game's "baked lighting" toggle has
     *  nothing to toggle when there is not. */
    public boolean hasLighting() { return lighting != null; }

    /**
     * Shade in light and roll the highlights off filmically, instead of multiplying the sRGB
     * numbers a texture is stored in. See {@code Renderer.hdr}, which is where it is explained;
     * this is only the switch, and it may be thrown between frames like the rest of them.
     */
    public boolean hdr() { return Renderer.hdr; }

    public void setHdr(boolean on) { Renderer.hdr = on; }

    /** The old y-shearing pitch instead of the true projective warp, for comparison. */
    public boolean shear() { return shear; }

    public void setShear(boolean on) { shear = on; }

    /**
     * How far up and down the camera may look right now, in radians.
     *
     * What the map asked for, or what the render buffer can hold, whichever is less. Worked out on
     * demand rather than once at startup because the render size and the field of view both change
     * under it - dynamic resolution moves one every few frames - and the overscan a pitch needs is
     * a function of both.
     */
    public double pitchLimit() {
        return Math.min(world.maxPitch,
                Warp.fits(shear, RW, RH, renderer.focal(), OVERSCAN_LIMIT * (long) RW * RH));
    }

    /** Move the render scale one step along the ladder, and stop the frame-time controller from
     *  steering it. */
    public void stepScale(int delta) {
        autoRes = false;
        wantScale = Math.max(0, Math.min(DynamicResolution.LADDER.length - 1, scaleIx + delta));
    }

    /** Hand the render scale back to the frame-time controller, or take it away again. Does
     *  nothing when --fps 0 asked for a fixed size. */
    public void toggleAutoRes() {
        autoRes = targetFps > 0 && !autoRes;
        if (autoRes) steer = new DynamicResolution(1000.0 / targetFps, scaleIx);
    }

    public boolean autoRes() { return autoRes; }

    /** The second window: a top-down view of where every column's ray goes. */
    public void toggleRayView() { rayView.toggle(); }

    /** Whether that view turns with the player or keeps the map the same way up. */
    public void toggleRayFollow() { rayView.toggleFollow(); }

    // ---- Main loop ----

    /** Open the window and run this game until something stops it. */
    public void run(Game g) throws Exception {
        this.game = g;
        Canvas canvas = new Canvas();
        canvas.enableInputMethods(false);                        // an IME (Bopomofo, Pinyin) must not eat the keys
        SwingUtilities.invokeAndWait(() -> {
            // The window is the output: --window, fitted onto the screen, whatever is being rendered.
            // present() scales the picture up into it, so a bigger window costs no rays. The ray
            // view opens beside it, hidden until the game asks for it.
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
            input.begin(!Keys.physical() || inFront());
            game.update(dt, input);
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
                input.dragged(e.getX() - lx, e.getY() - ly);
                lx = e.getX();
                ly = e.getY();
            }
            @Override public void mouseMoved(MouseEvent e) { hoverColumn = columnAt(e.getX()); hoverRow = rowAt(e.getY()); }
            @Override public void mouseExited(MouseEvent e) { hoverColumn = hoverRow = -1; }
        };
        canvas.addMouseListener(drag);
        canvas.addMouseMotionListener(drag);
    }

    /**
     * The key state comes from the whole machine, not from our windows, so ignore it unless one of
     * ours is the active window. AWT events came with that for free.
     */
    private static boolean inFront() {
        for (Window w : Window.getWindows()) if (w.isActive()) return true;
        return false;
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

    RayView.View view() {
        return new RayView.View(last.x, last.y, last.heading, last.fisheye, Math.toDegrees(last.pitch), shear);
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

    private void applyWantedScale() {
        int want = wantScale;
        if (want >= 0) {
            wantScale = -1;
            setScale(want);
        }
    }

    /** One frame of the running game: tell it how far it may tilt, then draw where it says. */
    void frame() {
        applyWantedScale();
        double limit = pitchLimit();
        game.pitchLimit(limit);
        int hc = hoverColumn, hr = hoverRow;
        traceI = hc >= 0 && hr >= 0 ? hc : -1;                   // the pixel the ray view traces; -1 = centre
        traceJ = hc >= 0 && hr >= 0 ? hr : -1;
        render(game.view(), limit);
    }

    /** Render one view, whoever it belongs to: a headless capture's, or a game's. */
    public void frame(View v) {
        applyWantedScale();
        render(v, pitchLimit());
    }

    /** Render one frame, tilt it, and when supersampling box-filter it down to the output image. */
    private void render(View v, double limit) {
        last = v;
        double pitch = Math.max(-limit, Math.min(limit, v.pitch));
        Renderer.Camera c = cam;
        c.x = v.x;
        c.y = v.y;
        c.dirX = Math.cos(v.heading);
        c.dirY = Math.sin(v.heading);
        c.eye = v.eye;
        c.fisheye = v.fisheye;                              // pitch and the render window: preparePitch()
        c.baked = v.baked && lighting != null;
        c.captureDepth = v.captureDepth;
        preparePitch(c, pitch);
        // Recording one column's ray costs allocations every frame; only the ray view and a
        // screenshot's -rays.png read it.
        renderer.traceColumn = rayView.visible() || c.captureDepth
                ? warp.sourceColumn(traceI >= 0 ? traceI : RW / 2, traceJ >= 0 ? traceJ : RH / 2) : -1;
        if (spans != null) spans.reset();
        if (masks != null) masks.reset();
        renderer.render(c);
        // Both: the card's resources are now kept in step even while it is switched off (see
        // preparePitch), so their existence no longer means it is the card drawing this frame.
        if (useGpu && gpu != null) shadeOnGpu(c);
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
    private void preparePitch(Renderer.Camera c, double pitch) {
        warp.plan(pitch, shear, RW, RH, renderer.focal());
        if (warp.needW() > srcW || warp.needH() > srcH) {        // grow only: an unused margin costs nothing
            srcW = Math.max(srcW, (int) (warp.needW() * 1.1));
            srcH = Math.max(srcH, (int) (warp.needH() * 1.1));
            src = new int[srcW * srcH];
            renderer.resize(srcW, srcH, src);
        }
        warp.place(renderer.centerX(), srcW, srcH, c);
        // Both dimensions: the overscan grows with pitch, and there is no rule that says the
        // width has to grow with the height. A height that changed on its own used to leave the
        // card drawing at the old size and GpuSpans' skip mask too short for the new one.
        // And once these exist they are kept in step whatever useGpu says, because the renderer
        // is still writing into them: the sink is attached for as long as the card's path exists,
        // so a buffer that grew while the card was switched off would be written past its end.
        if ((useGpu || spans != null) && (gpu == null || spans == null
                || spans.columns() != srcW || spans.rows() != srcH)) {
            try {
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
            } catch (RuntimeException | LinkageError cannot) {
                giveUpOnCard(cannot);
            }
        }
    }

    /**
     * Put the frame back on the CPU, whatever the card just refused to do.
     *
     * The card became the default on 2026-09-21, and that changes what a failure here means. It
     * used to be something you had asked for by name, so stopping and saying why was fair; now it
     * is what every run does, and a map whose lightmaps will not pack into one atlas - which
     * GpuLights throws about - would be a map that no longer runs at all. The CPU renderer is the
     * whole engine and always has been, so the answer is one sentence and that path.
     *
     * An OutOfMemoryError is not caught: that is the JVM in trouble rather than the card refusing,
     * and the buffers this was about to build are the reason it would be thrown.
     */
    private void giveUpOnCard(Throwable why) {
        String said = why.getMessage() != null ? why.getMessage() : why.toString();
        System.err.println("gpu: " + said);
        System.err.println("gpu: shading this map on the CPU instead");
        if (gpu != null) {
            gpu.close();
            gpu = null;
        }
        spans = null;
        masks = null;
        gpuPixels = null;
        renderer.captureSpans(null);
        renderer.captureMasks(null);
        setUseGpu(false);
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

    /** The finished picture, scaled into place, and whatever the game draws over it. */
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
        if (game != null)
            game.overlay(g, new Game.Overlay(ox, oy, dw, dh, fps, W, H, SS, RW, autoRes, shear));
    }
}

package engine;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Map;
import java.util.stream.IntStream;

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

    final World world;
    /**
     * The most overscan the pitch warp may grow the upright image to, as a multiple of a level
     * frame - not as a count of pixels.
     *
     * A multiple, because the overscan a pitch needs is a fixed ratio of the frame at any render
     * size, so a budget in pixels would let the camera tilt further on a small window than on a
     * large one. The camera is a control and a control does not change its range when the
     * resolution does. As a multiple it stops at the same angle everywhere: 55.6 degrees at the
     * 90-degree field of view, just past World.MAX_PITCH, which asks for 76.
     *
     * It was 16, which stopped the camera at 48.5, while every pixel of the upright image was
     * shaded and every row of every column filled. Neither is true any more (GpuWalls.shadeWarped,
     * Warp.columnRows), so a bigger image costs its rays and its memory rather than itself.
     */
    private static final long OVERSCAN_LIMIT = 80;

    /**
     * On the card there is a second ceiling, and it is not a multiple of anything: no texture may
     * be wider or taller than the card allows - 16384 on an M3 - and the upright frame, and the
     * span texture with a row a column and the warp's table under them, are textures. A request
     * past it is not refused; the call fails and every fetch reads zero. So the pitch stops where
     * they fit (pitchLimit), and the buffer grows no further than that (preparePitch). Zero until
     * the card is asked.
     */
    private int texMax;

    private int texMax() {
        if (texMax == 0) texMax = Gl.maxTextureSize();
        return texMax;
    }
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
    /** The window the picture goes to and the controls come from. Null until it is up, and null
     *  for good in a headless capture, which renders frames without ever opening one. */
    private Surface surface;
    /** Mouse look: the game asked for the pointer to be held at the middle of the window, so that
     *  moving the mouse turns the view without a button held. What the game asked for, which is not
     *  the same as what is happening - it only happens while a window of ours is in front. */
    private volatile boolean mouseLook;
    int traceI = -1, traceJ = -1;                                // the output pixel whose ray the ray view traces

    private volatile boolean shear = false;                      // the old y-shearing pitch, for comparison
    Lighting lighting;                                           // null with --flat

    private final Warp warp = new Warp();                        // this frame's pitch warp; see Warp
    private int[] rowLo, rowHi, rowNext;                         // the rows of each column it can read
    private static final boolean ROWS = !"false".equals(System.getProperty("warp.rows"));
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
    /**
     * The pitch warp on the card, and whether this frame went through it.
     *
     * Only a window asks for it (and {@code --gpu-verify}, which reads its result back to check
     * it): a capture writes depth and albedo and a golden digest is the double warp's, so every
     * headless path keeps {@link Warp#apply}. -Dwarp.cpu=true puts a window back on that path too,
     * which is how the two are compared.
     */
    private GpuWarp cardWarp;
    private boolean warpOnCard, readCardWarp, shownOnCard;
    private static final boolean WARP_CPU = Boolean.getBoolean("warp.cpu");

    /** Let frames be warped on the card; {@code readBack} brings each one into {@link #out}
     *  as well, which is only for checking it. */
    void warpOnCard(boolean on, boolean readBack) {
        warpOnCard = on && !WARP_CPU;
        readCardWarp = readBack;
    }

    /** Did the last frame stay on the card? Then {@link #out} is not what was shown. */
    boolean shownOnCard() { return shownOnCard; }

    /** Wait for the card to finish the last frame, so that a stopwatch around a frame on the card
     *  measures the frame and not the handing over of it. Nothing to wait for otherwise. */
    void settle() {
        if (useGpu && gpu != null) Gl.finish();
    }
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
        // Where --size sits on the ladder the frame-time controller moves along. Worked out here
        // rather than when the window opens, because stepScale() is public and a headless caller
        // may use it - and from a scaleIx left at 0 the first step goes to the bottom of the
        // ladder whatever size the run actually asked for. --gpu-verify does exactly that.
        this.scaleIx = DynamicResolution.nearest(o.w / (double) o.winW);
        renderer = new Renderer(world, RW, RH, src);
        // Before anything else asks: the pitch limit and the overscan the warp needs are both
        // worked out from the focal length, so a run at --fov 90 must not spend its first frame
        // deciding how far it may tilt at 67.
        if (!Double.isNaN(o.fov)) setFov(o.fov);
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
        // The bounce comes with the shading now, because a surface returns the light its colour
        // says it returns and not the sRGB number the colour is written as. It was a switch of its
        // own for as long as the maps were tuned against the old bounce; -Dhdr.bake=false is what
        // is left of that, and it is what the measurements in docs/ENGINEERING.md were taken with.
        Renderer.hdrBake = o.hdr && !"false".equals(System.getProperty("hdr.bake"));
        this.shear = o.shear;
        setUseGpu(o.gpu);
        if (!o.flat) {
            lighting = Lighting.bake(world);
            renderer.setLighting(lighting);
            // -Dflicker.levels=0,1,0.35: hold each flicker group at a level, so a capture - which
            // otherwise takes every light full on - can show a lamp that has dropped out.
            String held = System.getProperty("flicker.levels");
            if (held != null && lighting.flickerGroups() > 0) {
                double[] k = new double[lighting.flickerGroups()];
                String[] f = held.split(",");
                for (int i = 0; i < k.length; i++) k[i] = Double.parseDouble(f[Math.min(i, f.length - 1)].trim());
                lighting.flicker(k);
            }
        }
    }

    /**
     * Is there a graphics card here to shade on, and say so if not.
     *
     * Asked before AWT starts: a context asked for after the toolkit has started gets no
     * accelerated pixel format on macOS - the same ordering trap that once left the window taking
     * no keys, in the other direction.
     *
     * A missing card ends one way: a sentence, and the CPU renderer, which runs anywhere. It is a
     * machine with no display, a Windows machine with no OpenGL driver, a virtual machine, a CI
     * runner that offers no core profile - and it only shows up when the context is actually asked
     * for, arriving as an ExceptionInInitializerError out of Gl's own field initialisers, which is
     * not a thing to let out of main(). -Dglfw=none reproduces it on a machine that has a card.
     */
    public static boolean graphicsCard() {
        String why = null;
        try {
            Gl.context();
            // Which card, in one line, and on a machine with more than one, which cards it was
            // not. An integrated GPU draws the frame and reports success at a fraction of the
            // speed of the one beside it; see GpuNote.
            System.err.println("gpu: " + Gl.device() + ", GL " + Gl.version());
            String note = GpuNote.of(Gl.device());
            if (note != null) System.err.println("gpu: " + note);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            Throwable c = t.getCause() != null ? t.getCause() : t;
            why = c.getMessage() != null ? c.getMessage() : c.toString();
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
        double limit = Math.min(world.maxPitch,
                Warp.fits(shear, RW, RH, renderer.focal(), OVERSCAN_LIMIT * (long) RW * RH));
        // Only when there is a card: naming Gl on a machine without one is what -Dglfw=none catches.
        if (useGpu)
            limit = Math.min(limit, Warp.fitsWithin(shear, RW, RH, renderer.focal(), cardWidth(), cardHeight()));
        return limit;
    }

    /** The widest upright buffer the card can take: its span texture is a row a column with the
     *  warp's table of RH rows under them, and the buffer is kept an even number of columns wide,
     *  which may add one. */
    private int cardWidth() { return texMax() - RH - 2; }

    /** The tallest: the upright frame is a texture of its own. */
    private int cardHeight() { return texMax() - 2; }

    /** Move the render scale one step along the ladder, and stop the frame-time controller from
     *  steering it. */
    public void stepScale(int delta) {
        autoRes = false;
        wantScale = Math.max(0, Math.min(DynamicResolution.LADDER.length - 1, scaleIx + delta));
    }

    /** Where on the ladder the render size is now, and how many rungs there are: --gpu-verify
     *  walks them so that every comparison it makes is also a resize. */
    int scaleIndex() { return scaleIx; }

    static int scaleRungs() { return DynamicResolution.LADDER.length; }

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

    /**
     * Can this machine hold the pointer still, so that the mouse can look without a button held?
     *
     * It can. This used to be a real question: holding the pointer meant warping it back to the
     * middle of the window after every event, through a macOS call, so off macOS the answer was no
     * and a game had to leave dragging in its hints. The window does it now, with one call that
     * every platform has, so the question is kept for the games that ask it and the answer never
     * changes.
     */
    public static boolean canMouseLook() { return true; }

    /** Is mouse look on? */
    public boolean mouseLook() { return mouseLook; }

    /**
     * Turn mouse look on or off: hold the pointer still and hand the game how far it moved,
     * instead of asking for a button to be held down.
     *
     * The hover column the ray view traces goes back to the middle of the picture, because there
     * is no longer a pointer anywhere else to mean anything. May be asked for before the window
     * exists - a game's constructor is the natural place - and takes effect when it opens. How the
     * pointer is actually held still is the surface's business; see SurfaceGlfw.
     */
    public void setMouseLook(boolean on) {
        mouseLook = on;
        hoverColumn = hoverRow = -1;
        if (surface != null) surface.mouseLook(mouseLook);
    }

    /** What the window reports back: a hand that moved, a pointer over a pixel, a close button. */
    private final Surface.Events events = new Surface.Events() {
        @Override public void looked(int dx, int dy) { input.looked(dx, dy); }

        @Override public void hover(int x, int y) {
            hoverColumn = x < 0 ? -1 : columnAt(x);
            hoverRow = x < 0 ? -1 : rowAt(y);
        }

        @Override public void closed() { running = false; }
    };

    // ---- Main loop ----

    /** Open the window and run this game until something stops it. */
    public void run(Game g) throws Exception {
        this.game = g;
        surface = new SurfaceGlfw();
        surface.open("ColumnRay - " + world.name, winW, winH, events);
        warpOnCard(true, false);
        rayView.open(surface, Math.min(640, Math.max(240, surface.width() / 3)));
        if (mouseLook) surface.mouseLook(true);

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

        long last = System.nanoTime(), started = last;
        while (running) {
            long now = System.nanoTime();
            double dt = Math.min(0.05, (now - last) / 1e9);
            last = now;
            input.begin(!Keys.physical() || surface.active());
            game.update(dt, input);
            flicker((now - started) / 1e9);
            frame();
            present();
            surface.pump();
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
        surface.close();
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
        // A window with a context of its own - GLFW's, and its ray-view panel has a second - made
        // its own current to show the last frame, and none of the card's textures or framebuffers
        // are in it. Take the engine's back at the top of the frame rather than after each window:
        // the card is asked for more than just the shading pass (the span textures are built as
        // the buffers are sized), so there is no one later place that catches all of it.
        //
        // Only when there is a card. Naming Gl at all loads it, and its static initialiser asks
        // for a context - which on a machine with no display throws the sentence it was written
        // to throw. An unguarded call here once took every CPU frame on Linux down with it, which
        // is what CI is for; -Dglfw=none reproduces that here.
        if (useGpu) Gl.reclaim();
        applyWantedScale();
        double limit = pitchLimit();
        game.pitchLimit(limit);
        int hc = hoverColumn, hr = hoverRow;
        traceI = hc >= 0 && hr >= 0 ? hc : -1;                   // the pixel the ray view traces; -1 = centre
        traceJ = hc >= 0 && hr >= 0 ? hr : -1;
        render(game.view(), limit);
    }

    /** Render one view, whoever it belongs to: a headless capture's, or a game's. */
    /** Set the map's flickering lights for this moment: a level a group, which every lightmap
     *  sample reads, the CPU's and the card's alike. A capture never comes here, so it takes
     *  every light full on. */
    private void flicker(double seconds) {
        if (lighting == null || lighting.flickerGroups() == 0) return;
        lighting.flicker(lighting.flickerLevels(seconds));
    }

    public void frame(View v) {
        // A window with a context of its own - GLFW's, and its ray-view panel has a second - made
        // its own current to show the last frame, and none of the card's textures or framebuffers
        // are in it. Take the engine's back at the top of the frame rather than after each window:
        // the card is asked for more than just the shading pass (the span textures are built as
        // the buffers are sized), so there is no one later place that catches all of it.
        //
        // Only when there is a card. Naming Gl at all loads it, and its static initialiser asks
        // for a context - which on a machine with no display throws the sentence it was written
        // to throw. An unguarded call here once took every CPU frame on Linux down with it, which
        // is what CI is for; -Dglfw=none reproduces that here.
        if (useGpu) Gl.reclaim();
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
        // The whole frame on the card: shaded there, warped there, and shown from there, with
        // nothing read back. Only when the card was given every pixel - a frame with rows it was
        // not given is merged on the CPU (see shadeOnGpu) - and never for a capture, whose depth
        // and albedo the card does not have, or for supersampling, which averages on the CPU.
        shownOnCard = warpOnCard && useGpu && gpu != null && !c.captureDepth && SS == 1
                && !spans.anySkipped() && RW <= Warp.ROWS_MAX_WIDTH;
        if (shownOnCard && (cardWarp == null || cardWarp.width() != RW || cardWarp.height() != RH)) {
            try {
                if (cardWarp != null) cardWarp.close();
                cardWarp = null;
                cardWarp = new GpuWarp(RW, RH);
            } catch (RuntimeException | LinkageError cannot) {
                // Not the end of the card: the frame is shaded there and warped here, as before.
                System.err.println("gpu: the pitch warp stays on the CPU: " + cannot.getMessage());
                warpOnCard = false;
                shownOnCard = false;
            }
        }
        if (shownOnCard) {
            gpu.shadeWarped(spans, masks, c, srcH / 2.0 + c.pitch, renderer.focal(), RH, warp, cardWarp);
            cardWarp.submit();
            if (readCardWarp) cardWarp.read(hi);
            return;
        }
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
        if (world.puddles) {
            long budget = OVERSCAN_LIMIT * (long) RW * RH / warp.needW();
            warp.reachUp((int) Math.min(useGpu ? cardHeight() : Integer.MAX_VALUE, budget));
        }
        if (warp.needW() > srcW || warp.needH() > srcH) {        // grow only: an unused margin costs nothing
            // A tenth to spare, so a camera tilting a degree at a time does not reallocate every
            // frame - but never past what the card can hold, which pitchLimit already kept the
            // need itself inside.
            int wantW = (int) (warp.needW() * 1.1), wantH = (int) (warp.needH() * 1.1);
            if (useGpu) {
                wantW = Math.min(wantW, cardWidth());
                wantH = Math.min(wantH, cardHeight());
            }
            srcW = Math.max(srcW, Math.max(wantW, warp.needW()));
            // An even number of columns, always.
            //
            // The warp reads the window [cx - needW/2, cx + needW/2) out of the buffer, cx is the
            // renderer's centerX() and therefore srcW/2.0, and needW is even by construction. So
            // an odd buffer puts the window half a column off the centre the rays are measured
            // from, and every ray in the frame moves with it. Which is a picture that depends on
            // how tall an earlier frame was: look up until the overscan grows the buffer to an odd
            // width, look level again, and the frame is not the one you were looking at before.
            //
            // The height has no such problem and is left alone. The warp sets c.pitch to
            // hz - srcH/2.0 and the renderer puts the horizon at srcH/2.0 + c.pitch, so srcH
            // cancels exactly - which is what docs/GPU-REVIEW.md found when it went looking for
            // this in the wrong dimension.
            srcW += srcW & 1;
            srcH = Math.max(srcH, Math.max(wantH, warp.needH()));
            src = new int[srcW * srcH];
            renderer.resize(srcW, srcH, src);
        }
        warp.place(renderer.centerX(), srcW, srcH, c);
        // Render only what the warp can read: looking up 45 degrees, each column's range is about
        // three fifths of the buffer's height, and the ray stops once that much is filled.
        if (rowLo == null || rowLo.length < srcW) {
            rowLo = new int[srcW];
            rowHi = new int[srcW];
            rowNext = new int[srcW + 1];
        }
        warp.columnRows(rowLo, rowHi, rowNext, srcW);
        if (world.puddles) warp.mirrorRows(rowLo, rowHi, srcW);
        c.rowLo = ROWS ? rowLo : null;                          // -Dwarp.rows=false: every row, to compare
        c.rowHi = ROWS ? rowHi : null;
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
                gpu = new GpuWalls(srcW, srcH, srcW, RH);
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
                // As much room a column as the lists being replaced had grown to: every time the
                // overscan grows, which looking down at water it does every few degrees, lists
                // starting small would drop on the first frame, and a frame that drops is merged
                // on the CPU - where a puddle's walk up the column cannot see the rows the card
                // was not given, so the reflection came out combed and flickered with each growth.
                spans = new GpuSpans(srcW, srcH, spans == null ? 32 : spans.perColumn());
                masks = new GpuMasks(srcW, masks == null ? 16 : masks.perColumn());
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
        if (cardWarp != null) {
            cardWarp.close();
            cardWarp = null;
        }
        shownOnCard = false;
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

    /**
     * Show the frame: the picture, letterboxed into whatever size the window turned out to be,
     * and whatever the game draws over it.
     *
     * Where the view lands inside the window is worked out here rather than by the surface,
     * because it is the engine that knows the render size - and because columnAt/rowAt have to
     * undo exactly this arithmetic to turn a pointer back into a column.
     */
    private void present() {
        int cw = surface.width(), ch = surface.height();
        double sc = Math.min(cw / (double) W, ch / (double) H);
        int dw = (int) (W * sc), dh = (int) (H * sc);
        viewX = (cw - dw) / 2;
        viewY = (ch - dh) / 2;
        viewW = dw;
        viewH = dh;
        Surface.Painter overlay = g -> drawOverlay(g, viewX, viewY, dw, dh, rayView.visible());
        if (shownOnCard) surface.present(cardWarp.texture(), viewX, viewY, dw, dh, overlay);
        else surface.present(out, W, H, viewX, viewY, dw, dh, overlay);
    }

    /**
     * The finished picture, scaled into place, and whatever the game draws over it.
     *
     * Still one call for a screenshot, which composes a PNG and has no card to scale anything on.
     * A window does the two halves separately - see Surface.present - because the picture is the
     * expensive half to rasterise and the cheap half to hand to a card.
     */
    void drawFrame(Graphics2D g, int ox, int oy, int dw, int dh, boolean markColumn) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, ox, oy, dw, dh, null);
        drawOverlay(g, ox, oy, dw, dh, markColumn);
    }

    /** Everything drawn over the picture: the traced column, and whatever the game adds. */
    void drawOverlay(Graphics2D g, int ox, int oy, int dw, int dh, boolean markColumn) {
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

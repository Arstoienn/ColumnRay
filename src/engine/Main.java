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
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Window, input, player physics and minimap. A second "ray view" window lives in RayView.
 * Usage: java -cp out engine.Main [map.json] [--shot out.png [x y angle pitch [column]]] [--bench]
 */
public final class Main {
    static final int DEFAULT_W = 640, DEFAULT_H = 360;

    // Player dimensions (metres)
    static final double RADIUS = 0.3, EYE_STAND = 1.6, EYE_CROUCH = 1.0, HEAD_ABOVE_EYE = 0.15;
    static final double STEP = 0.35, GRAVITY = 18, JUMP_SPEED = 5.2, WALK = 3.2, RUN = 5.5;
    static final double MAX_PITCH = Math.toRadians(30);

    /** Output resolution: the size of the image that reaches the window. */
    private final int W, H;
    /** Supersampling factor. The engine renders at (W*SS) x (H*SS) and the result is box-filtered
     *  down to W x H, so SS is also the number of rays per output column. SS = 1 disables it. */
    private final int SS;
    /** Render resolution = the ray count. Equals W when SS is 1. */
    private final int RW, RH;

    private final World world;
    private final BufferedImage image;
    private final int[] out;      // the W x H pixels the window sees
    private final int[] hi;       // the RW x RH tilted view after the pitch warp; same array as `out` when SS = 1
    private int[] src;            // what the renderer writes: the upright (y-sheared) view plus overscan
    private int srcW, srcH;
    private final Renderer renderer;
    private final RayView rayView;
    private final Renderer.Camera cam = new Renderer.Camera();
    private final Shape[] mapOrder;

    // Player state
    private double x, y, angle, pitch, feet, vz, eyeH = EYE_STAND, viewFeet;
    private boolean grounded;

    // Input (written on the EDT, read by the main loop)
    private final Set<Integer> keys = ConcurrentHashMap.newKeySet();
    private double mouseDX, mouseDY;
    private volatile boolean showMap = true, fisheye = false;
    private volatile boolean shear = false;                      // P: the old y-shearing pitch, for comparison
    private volatile double fovDeg = Renderer.DEFAULT_FOV;
    private volatile int hoverColumn = -1, hoverRow = -1;        // which pixel of the main view the mouse is over
    private int traceI = -1, traceJ = -1;                        // the output pixel whose ray the ray view traces

    // This frame's pitch warp, set by preparePitch()
    private boolean warpShear;
    private double warpSin, warpCos, warpTan, warpHz, warpCx;
    private int warpX0, warpX1, warpY1;
    private volatile int viewX, viewY, viewW, viewH;             // where the main view sits inside the window
    private double startFeet = Double.NaN;                       // --feet: which storey to start on
    private double fps;

    Main(World world, int w, int h, int ss) {
        this.world = world;
        this.W = w;
        this.H = h;
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
        mapOrder = world.shapes.clone();
        Arrays.sort(mapOrder, Comparator.comparingDouble(s -> s.h));
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
        String mapPath = "maps/school.json", shot = null;
        double[] at = null;
        boolean bench = false, shear = false;
        int w = DEFAULT_W, h = DEFAULT_H, ss = 1;
        double startFeet = Double.NaN;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--bench")) {
                bench = true;
            } else if (args[i].equals("--shear")) {
                shear = true;
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
        if (shot != null || bench) System.setProperty("java.awt.headless", "true");

        if ((long) w * ss > 16384 || (long) h * ss > 16384) {
            usage("--size times --ss must stay within 16384x16384 (that would be " + w * ss + "x" + h * ss + ")");
            return;
        }
        Main game = new Main(World.load(Path.of(mapPath)), w, h, ss);
        game.shear = shear;
        if (!Double.isNaN(startFeet)) game.standOn(startFeet);
        if (bench) game.bench();
        else if (shot != null) game.screenshot(new File(shot), at);
        else game.run();
    }

    /** Spin on the spot and time each frame (headless, no window). */
    private void bench() {
        int frames = 720;
        double saved = pitch;
        for (double p : new double[] {0, MAX_PITCH}) {          // level, and fully tilted (the most overscan)
            pitch = p;
            for (int i = 0; i < 120; i++) { angle += 0.05; frame(); }                     // warm-up
            long t0 = System.nanoTime();
            for (int i = 0; i < frames; i++) { angle += 2 * Math.PI / frames; frame(); }
            double ms = (System.nanoTime() - t0) / 1e6 / frames;
            System.out.printf("%dx%d out, %dx%d rendered (ss %d), pitch %2.0f deg %s: %d rays, %.2f ms per frame (about %.0f fps)%n",
                    W, H, RW, RH, SS, Math.toDegrees(p), shear ? "shear" : "true",
                    renderer.drawnX1 - renderer.drawnX0, ms, 1000 / ms);
        }
        pitch = saved;
    }

    // ---- Main loop ----

    private void run() throws Exception {
        Canvas canvas = new Canvas();
        SwingUtilities.invokeAndWait(() -> {
            // Main view on the left, ray view on the right, both fitted onto the screen
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int rayW = (int) Math.min(640, screen.width * 0.36);
            int mainW = Math.max(W, Math.min(W * 2, screen.width - rayW - 30));
            JFrame frame = new JFrame("ColumnRay - " + world.name);
            canvas.setPreferredSize(new Dimension(mainW, mainW * H / W));
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
            canvas.requestFocus();
        });

        long last = System.nanoTime();
        while (true) {
            long now = System.nanoTime();
            double dt = Math.min(0.05, (now - last) / 1e9);
            last = now;
            update(dt);
            frame();
            present(canvas);
            rayView.present(view());
            fps = fps == 0 ? 1 / Math.max(dt, 1e-6) : fps * 0.95 + 0.05 / Math.max(dt, 1e-6);
            if (System.nanoTime() - now < 4_000_000) Thread.sleep(2);
        }
    }

    private void installInput(Canvas canvas) {
        canvas.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_ESCAPE) System.exit(0);
                if (!keys.add(code)) return;                     // ignore auto-repeat while a key is held
                switch (code) {
                    case KeyEvent.VK_M -> showMap = !showMap;
                    case KeyEvent.VK_F -> fisheye = !fisheye;
                    case KeyEvent.VK_P -> shear = !shear;
                    case KeyEvent.VK_R -> rayView.toggle();
                    case KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_MINUS -> fovDeg = Math.max(30, fovDeg - 5);
                    case KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_EQUALS -> fovDeg = Math.min(120, fovDeg + 5);
                    default -> { }
                }
            }
            @Override public void keyReleased(KeyEvent e) { keys.remove(e.getKeyCode()); }
        });
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

    private boolean down(int key) { return keys.contains(key); }

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
        double fov = fovDeg;
        if (Math.abs(renderer.fov() - fov) > 1e-6) renderer.setFov(fov);
        int hc = hoverColumn, hr = hoverRow;
        traceI = hc >= 0 && hr >= 0 ? hc : -1;                   // the pixel the ray view traces; -1 = centre
        traceJ = hc >= 0 && hr >= 0 ? hr : -1;

        double mdx, mdy;
        synchronized (this) { mdx = mouseDX; mdy = mouseDY; mouseDX = mouseDY = 0; }
        double turn = (down(KeyEvent.VK_RIGHT) || down(KeyEvent.VK_E) ? 1 : 0) - (down(KeyEvent.VK_LEFT) || down(KeyEvent.VK_Q) ? 1 : 0);
        double look = (down(KeyEvent.VK_UP) ? 1 : 0) - (down(KeyEvent.VK_DOWN) ? 1 : 0);
        angle += turn * 2.2 * dt + mdx * 0.004;
        pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch + look * 1.2 * dt - mdy * 0.003));

        boolean crouch = down(KeyEvent.VK_C) || down(KeyEvent.VK_CONTROL);
        double dirX = Math.cos(angle), dirY = Math.sin(angle);
        double f = (down(KeyEvent.VK_W) ? 1 : 0) - (down(KeyEvent.VK_S) ? 1 : 0);
        double s = (down(KeyEvent.VK_D) ? 1 : 0) - (down(KeyEvent.VK_A) ? 1 : 0);
        if (f != 0 || s != 0) {
            double speed = (down(KeyEvent.VK_SHIFT) ? RUN : WALK) * (crouch ? 0.5 : 1) / Math.hypot(f, s);
            double dx = (dirX * f - dirY * s) * speed * dt, dy = (dirY * f + dirX * s) * speed * dt;
            int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 0.1));   // substep so we cannot tunnel through thin walls
            for (int i = 0; i < n; i++) {
                if (!blocked(x + dx / n, y, feet)) x += dx / n;
                if (!blocked(x, y + dy / n, feet)) y += dy / n;
            }
        }

        double[] sup = support(x, y, feet);
        double ground = sup[0], ceil = sup[1];
        if (grounded && feet > ground && feet - ground <= STEP) feet = ground;   // stick to the floor when stepping down
        if (grounded && down(KeyEvent.VK_SPACE)) vz = JUMP_SPEED;
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
            if (s.z0 < head(stand) && s.h > stand + STEP && Geometry.overlaps(px, py, RADIUS, s)) return true;
        return false;
    }

    /** { highest surface we can stand on, lowest thing above our head } */
    private double[] support(double px, double py, double feet) {
        double ground = Double.NEGATIVE_INFINITY;
        for (Region r : world.regionsNear(px, py, RADIUS)) {
            if (!Geometry.discTouchesPoly(px, py, RADIUS, r.xs, r.ys)) continue;
            if (r.floor <= feet + STEP) ground = Math.max(ground, r.floor);
        }
        for (Shape s : world.shapesNear(px, py, RADIUS))
            if (Geometry.overlaps(px, py, RADIUS, s) && s.h <= feet + STEP) ground = Math.max(ground, s.h);
        if (ground == Double.NEGATIVE_INFINITY) ground = feet;

        // The ceiling is whatever is above the surface we would stand on, so the underside of a
        // stair we are climbing onto does not count as our own ceiling.
        double ceil = Double.POSITIVE_INFINITY;
        for (Region r : world.regionsNear(px, py, RADIUS)) {
            if (!Geometry.discTouchesPoly(px, py, RADIUS, r.xs, r.ys)) continue;
            if (r.ceil > ground) ceil = Math.min(ceil, r.ceil);
        }
        for (Shape s : world.shapesNear(px, py, RADIUS))
            if (Geometry.overlaps(px, py, RADIUS, s) && s.z0 >= ground + STEP) ceil = Math.min(ceil, s.z0);
        return new double[] {ground, ceil};
    }

    private Renderer.Camera camera() {
        cam.x = x;
        cam.y = y;
        cam.dirX = Math.cos(angle);
        cam.dirY = Math.sin(angle);
        cam.eye = viewFeet + eyeH;
        cam.fisheye = fisheye;                              // pitch and the render window: preparePitch()
        return cam;
    }

    private RayView.View view() {
        return new RayView.View(x, y, angle, fisheye, Math.toDegrees(pitch), shear);
    }

    // ---- Rendering ----

    /** Render one frame, tilt it, and when supersampling box-filter it down to the output image. */
    private void frame() {
        Renderer.Camera c = camera();
        preparePitch(c);
        renderer.traceColumn = sourceColumn(traceI >= 0 ? traceI : RW / 2, traceJ >= 0 ? traceJ : RH / 2);
        renderer.render(c);
        warp();
        if (SS > 1) downsample();
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
            for (int i = 0; i < RW; i++, sx += k)
                hi[orow + i] = s[srow + Math.max(xLo, Math.min(xHi, (int) Math.floor(sx)))];
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

    private void screenshot(File out, double[] at) throws Exception {
        if (at != null) {
            x = at[0];
            y = at[1];
            angle = Math.toRadians(at[2]);
            pitch = Math.toRadians(at[3]);
            placeOnGround();
        }
        // the column argument is an output column, so it means the same place whatever --ss is
        int col = at != null && at.length > 4 ? Math.max(0, Math.min(W - 1, (int) at[4])) : W / 2;
        traceI = col * SS + SS / 2;
        traceJ = -1;
        frame();
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
        if (showMap) drawMinimap(g, ox + dw - 12, oy + 12);
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

    private void drawHud(Graphics2D g, int left, int top) {
        Region r = here();
        String[] lines = {
            String.format("%s   %.0f fps   %dx%d%s   FOV %.0f deg   %s", world.name, fps, W, H,
                    SS > 1 ? " x" + SS + " AA" : "", renderer.fov(),
                    fisheye ? "fisheye demo (straight-line distance, wrong)" : "perpendicular distance"),
            String.format("%s   (%.1f, %.1f)   feet %.2f m   pitch %+.0f deg %s", r == null ? "-" : r.name, x, y, feet,
                    Math.toDegrees(pitch), shear ? "y-shearing (old)" : "true perspective"),
            "WASD move   drag mouse / arrows look   Space jump   C crouch   Shift run",
            "[ ] FOV   F fisheye demo   P pitch: true / shear   R ray view   M minimap   Esc quit   hover to pick a column",
        };
        g.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
        for (int i = 0; i < lines.length; i++) {
            g.setColor(new Color(0, 0, 0, 160));
            g.drawString(lines[i], left + 1, top + i * 18 + 1);
            g.setColor(new Color(235, 235, 235));
            g.drawString(lines[i], left, top + i * 18);
        }
    }

    private void drawMinimap(Graphics2D g, int right, int top) {
        double mw = world.maxX - world.minX, mh = world.maxY - world.minY;
        double s = Math.min(240 / mw, 220 / mh);
        double ox = right - mw * s, oy = top;
        AffineTransform saved = g.getTransform();
        g.setColor(new Color(0, 0, 0, 150));
        g.fill(new Rectangle2D.Double(ox - 6, oy - 6, mw * s + 12, mh * s + 12));
        g.translate(ox, oy);
        g.scale(s, s);
        g.translate(-world.minX, -world.minY);
        g.setStroke(new BasicStroke((float) (1.5 / s)));

        for (Region r : world.regions) {
            g.setColor(withAlpha(r.floorColor, r.sky ? 120 : 190));
            g.fill(path(r.xs, r.ys));
        }
        for (Shape sh : mapOrder) {
            Color c = withAlpha(sh.color, 230);
            g.setColor(c);
            boolean floating = sh.z0 > 0.3;
            switch (sh.kind) {
                case SEG -> g.draw(new Line2D.Double(sh.ax, sh.ay, sh.bx, sh.by));
                case CIRCLE -> {
                    Ellipse2D e = new Ellipse2D.Double(sh.cx - sh.r, sh.cy - sh.r, sh.r * 2, sh.r * 2);
                    if (floating) g.draw(e); else g.fill(e);
                }
                case POLY -> { if (floating) g.draw(path(sh.xs, sh.ys)); else g.fill(path(sh.xs, sh.ys)); }
            }
        }

        double dx = Math.cos(angle), dy = Math.sin(angle), pl = renderer.planeHalfWidth(), len = 4;
        g.setColor(new Color(255, 230, 120, 200));
        g.draw(new Line2D.Double(x, y, x + (dx + dy * pl) * len, y + (dy - dx * pl) * len));
        g.draw(new Line2D.Double(x, y, x + (dx - dy * pl) * len, y + (dy + dx * pl) * len));
        g.setColor(new Color(255, 80, 60));
        g.fill(new Ellipse2D.Double(x - RADIUS, y - RADIUS, RADIUS * 2, RADIUS * 2));
        g.setTransform(saved);
    }

    private static Path2D path(double[] xs, double[] ys) {
        Path2D p = new Path2D.Double();
        p.moveTo(xs[0], ys[0]);
        for (int i = 1; i < xs.length; i++) p.lineTo(xs[i], ys[i]);
        p.closePath();
        return p;
    }

    private static Color withAlpha(int rgb, int a) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, a);
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("usage: java -cp out engine.Main [map.json] [--size WxH] [--ss N] [--bench]");
        System.err.println("       java -cp out engine.Main [map.json] [--size WxH] [--ss N] --shot out.png [x y angle pitch [column]]");
        System.err.println("  --size  output resolution (default " + DEFAULT_W + "x" + DEFAULT_H + ")");
        System.err.println("  --feet  starting floor height in metres, to begin on an upper storey (e.g. 3.6)");
        System.err.println("  --shear look up / down the old way (y-shearing) instead of true perspective");
        System.err.println("  --ss    supersampling factor 1-8: renders at size*N and averages down (default 1).");
        System.err.println("          Rays cast per frame = width * N.");
    }
}

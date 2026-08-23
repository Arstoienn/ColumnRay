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
    private final int[] hi;       // the RW x RH pixels the renderer writes; same array as `out` when SS = 1
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
    private volatile double fovDeg = Renderer.DEFAULT_FOV;
    private volatile int hoverColumn = -1;                       // which column of the main view the mouse is over
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
        this.hi = ss == 1 ? out : new int[RW * RH];   // SS = 1: the renderer writes straight into the window image
        renderer = new Renderer(world, RW, RH, hi);
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
        boolean bench = false;
        int w = DEFAULT_W, h = DEFAULT_H, ss = 1;
        double startFeet = Double.NaN;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--bench")) {
                bench = true;
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
        if (!Double.isNaN(startFeet)) game.standOn(startFeet);
        if (bench) game.bench();
        else if (shot != null) game.screenshot(new File(shot), at);
        else game.run();
    }

    /** Spin on the spot and time each frame (headless, no window). */
    private void bench() {
        int frames = 720;
        for (int i = 0; i < 120; i++) { angle += 0.05; frame(); }                     // warm-up
        long t0 = System.nanoTime();
        for (int i = 0; i < frames; i++) { angle += 2 * Math.PI / frames; frame(); }
        double ms = (System.nanoTime() - t0) / 1e6 / frames;
        System.out.printf("%dx%d out, %dx%d rendered (ss %d, %d rays): %.2f ms per frame on average (about %.0f fps)%n",
                W, H, RW, RH, SS, RW, ms, 1000 / ms);
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
            rayView.present(new RayView.View(x, y, angle, fisheye));
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
            @Override public void mouseMoved(MouseEvent e) { hoverColumn = columnAt(e.getX()); }
            @Override public void mouseExited(MouseEvent e) { hoverColumn = -1; }
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

    // ---- Player physics ----

    private void update(double dt) {
        double fov = fovDeg;
        if (Math.abs(renderer.fov() - fov) > 1e-6) renderer.setFov(fov);
        int hover = hoverColumn;
        renderer.traceColumn = hover >= 0 ? hover : RW / 2;      // the column the ray view records in detail

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
        cam.pitch = renderer.focal() * Math.tan(pitch);     // y-shearing
        cam.fisheye = fisheye;
        return cam;
    }

    // ---- Rendering ----

    /** Render one frame and, when supersampling, box-filter it down to the output image. */
    private void frame() {
        renderer.render(camera());
        if (SS > 1) downsample();
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
        renderer.traceColumn = col * SS + SS / 2;
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
        rayView.draw(rg, rays.getWidth(), rays.getHeight(), new RayView.View(x, y, angle, fisheye));
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
        if (markColumn && col >= 0) {                        // the column shown in red in the ray view
            double cx = ox + (col + 0.5) * dw / RW;
            g.setColor(new Color(255, 70, 50, 140));
            g.draw(new Line2D.Double(cx, oy, cx, oy + dh));
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
            String.format("%s   (%.1f, %.1f)   feet %.2f m", r == null ? "-" : r.name, x, y, feet),
            "WASD move   drag mouse / arrows look   Space jump   C crouch   Shift run",
            "[ ] FOV   F fisheye demo   R ray view   M minimap   Esc quit   hover the view to pick a column",
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
        System.err.println("  --ss    supersampling factor 1-8: renders at size*N and averages down (default 1).");
        System.err.println("          Rays cast per frame = width * N.");
    }
}

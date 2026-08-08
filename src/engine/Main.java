package engine;

import engine.World.Region;
import engine.World.Shape;
import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
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
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Window, input, player physics and minimap.
 * Usage: java -cp out engine.Main [map.json] [--shot out.png [x y angle pitch]]
 */
public final class Main {
    static final int W = 640, H = 360;

    // Player dimensions (metres)
    static final double RADIUS = 0.3, EYE_STAND = 1.6, EYE_CROUCH = 1.0, HEAD_ABOVE_EYE = 0.15;
    static final double STEP = 0.35, GRAVITY = 18, JUMP_SPEED = 5.2, WALK = 3.2, RUN = 5.5;
    static final double MAX_PITCH = Math.toRadians(30);

    private final World world;
    private final BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    private final Renderer renderer;
    private final Renderer.Camera cam = new Renderer.Camera();
    private final Shape[] mapOrder;

    // Player state
    private double x, y, angle, pitch, feet, vz, eyeH = EYE_STAND, viewFeet;
    private boolean grounded;

    // Input (written on the EDT, read by the main loop)
    private final Set<Integer> keys = ConcurrentHashMap.newKeySet();
    private double mouseDX, mouseDY;
    private volatile boolean showMap = true;
    private double fps;

    Main(World world) {
        this.world = world;
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        renderer = new Renderer(world, W, H, pixels);
        mapOrder = world.shapes.clone();
        Arrays.sort(mapOrder, Comparator.comparingDouble(s -> s.h));
        x = world.spawnX;
        y = world.spawnY;
        angle = world.spawnAngle;
        placeOnGround();
    }

    private void placeOnGround() {
        Region r = world.regionAt(x, y);
        feet = viewFeet = support(x, y, r == null ? 0 : r.floor)[0];
        vz = 0;
        grounded = true;
    }

    public static void main(String[] args) throws Exception {
        String mapPath = "maps/school.json", shot = null;
        double[] at = null;
        boolean bench = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--bench")) {
                bench = true;
            } else if (args[i].equals("--shot")) {
                shot = args[++i];
                if (i + 4 < args.length) {
                    at = new double[4];
                    for (int k = 0; k < 4; k++) at[k] = Double.parseDouble(args[++i]);
                }
            } else {
                mapPath = args[i];
            }
        }
        if (shot != null || bench) System.setProperty("java.awt.headless", "true");

        Main game = new Main(World.load(Path.of(mapPath)));
        if (bench) game.bench();
        else if (shot != null) game.screenshot(new File(shot), at);
        else game.run();
    }

    /** Spin on the spot and time each frame (headless, no window). */
    private void bench() {
        int frames = 720;
        for (int i = 0; i < 120; i++) { angle += 0.05; renderer.render(camera()); }   // warm-up
        long t0 = System.nanoTime();
        for (int i = 0; i < frames; i++) { angle += 2 * Math.PI / frames; renderer.render(camera()); }
        double ms = (System.nanoTime() - t0) / 1e6 / frames;
        System.out.printf("%dx%d: %.2f ms per frame on average (about %.0f fps)%n", W, H, ms, 1000 / ms);
    }

    // ---- Main loop ----

    private void run() throws Exception {
        Canvas canvas = new Canvas();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("ColumnRay — " + world.name);
            canvas.setPreferredSize(new Dimension(W * 2, H * 2));
            canvas.setIgnoreRepaint(true);
            canvas.setFocusTraversalKeysEnabled(false);
            frame.add(canvas);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setVisible(true);
            canvas.createBufferStrategy(2);
            canvas.requestFocus();
            installInput(canvas);
        });

        long last = System.nanoTime();
        while (true) {
            long now = System.nanoTime();
            double dt = Math.min(0.05, (now - last) / 1e9);
            last = now;
            update(dt);
            renderer.render(camera());
            present(canvas);
            fps = fps == 0 ? 1 / Math.max(dt, 1e-6) : fps * 0.95 + 0.05 / Math.max(dt, 1e-6);
            if (System.nanoTime() - now < 4_000_000) Thread.sleep(2);
        }
    }

    private void installInput(Canvas canvas) {
        canvas.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) System.exit(0);
                if (e.getKeyCode() == KeyEvent.VK_M && keys.add(KeyEvent.VK_M)) showMap = !showMap;
                keys.add(e.getKeyCode());
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
        };
        canvas.addMouseListener(drag);
        canvas.addMouseMotionListener(drag);
    }

    private boolean down(int key) { return keys.contains(key); }

    // ---- Player physics ----

    private void update(double dt) {
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
     *  or a solid shape is in the way. */
    private boolean blocked(double px, double py, double feet) {
        if (world.regionAt(px, py) == null) return true;
        for (Region r : world.regionsNear(px, py, RADIUS)) {
            if (!Geometry.discTouchesPoly(px, py, RADIUS, r.xs, r.ys)) continue;
            if (r.floor > feet + STEP || r.ceil < head(feet)) return true;
        }
        for (Shape s : world.shapesNear(px, py, RADIUS))
            if (s.z0 < head(feet) && s.h > feet + STEP && Geometry.overlaps(px, py, RADIUS, s)) return true;
        return false;
    }

    /** { highest surface we can stand on, lowest thing above our head } */
    private double[] support(double px, double py, double feet) {
        double ground = Double.NEGATIVE_INFINITY, ceil = Double.POSITIVE_INFINITY;
        for (Region r : world.regionsNear(px, py, RADIUS)) {
            if (!Geometry.discTouchesPoly(px, py, RADIUS, r.xs, r.ys)) continue;
            if (r.floor <= feet + STEP) ground = Math.max(ground, r.floor);
            ceil = Math.min(ceil, r.ceil);
        }
        for (Shape s : world.shapesNear(px, py, RADIUS)) {
            if (!Geometry.overlaps(px, py, RADIUS, s)) continue;
            if (s.h <= feet + STEP) ground = Math.max(ground, s.h);
            else if (s.z0 >= feet + STEP) ceil = Math.min(ceil, s.z0);
        }
        return new double[] {ground == Double.NEGATIVE_INFINITY ? feet : ground, ceil};
    }

    private Renderer.Camera camera() {
        cam.x = x;
        cam.y = y;
        cam.dirX = Math.cos(angle);
        cam.dirY = Math.sin(angle);
        cam.eye = viewFeet + eyeH;
        cam.pitch = renderer.F * Math.tan(pitch);           // y-shearing
        return cam;
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
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, cw, ch);
                drawFrame(g, (cw - dw) / 2, (ch - dh) / 2, dw, dh);
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
        renderer.render(camera());
        BufferedImage img = new BufferedImage(W * 2, H * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        drawFrame(g, 0, 0, W * 2, H * 2);
        g.dispose();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }

    private void drawFrame(Graphics2D g, int ox, int oy, int dw, int dh) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, ox, oy, dw, dh, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        drawHud(g, ox + 12, oy + 20);
        if (showMap) drawMinimap(g, ox + dw - 12, oy + 12);
    }

    private void drawHud(Graphics2D g, int left, int top) {
        Region r = world.regionAt(x, y);
        String[] lines = {
            String.format("%s   %.0f fps   %d×%d", world.name, fps, W, H),
            String.format("%s   (%.1f, %.1f)   feet %.2f m", r == null ? "-" : r.name, x, y, feet),
            "WASD move   drag mouse / arrows look   Space jump   C crouch   Shift run   M minimap   Esc quit",
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

        double dx = Math.cos(angle), dy = Math.sin(angle), pl = Renderer.PL, len = 4;
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
}

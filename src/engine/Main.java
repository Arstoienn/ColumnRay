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
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * 視窗、輸入、玩家物理、小地圖。另外開一個「光線視圖」視窗，見 RayView。
 * 用法：java -cp out engine.Main [地圖.json] [--shot 輸出.png [x y 角度 仰角]] [--bench]
 */
public final class Main {
    static final int W = 640, H = 360;

    // 玩家尺寸（公尺）
    static final double RADIUS = 0.3, EYE_STAND = 1.6, EYE_CROUCH = 1.0, HEAD_ABOVE_EYE = 0.15;
    static final double STEP = 0.35, GRAVITY = 18, JUMP_SPEED = 5.2, WALK = 3.2, RUN = 5.5;
    static final double MAX_PITCH = Math.toRadians(30);

    private final World world;
    private final BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    private final Renderer renderer;
    private final RayView rayView;
    private final Renderer.Camera cam = new Renderer.Camera();
    private final Shape[] mapOrder;

    // 玩家
    private double x, y, angle, pitch, feet, vz, eyeH = EYE_STAND, viewFeet;
    private boolean grounded;

    // 輸入（EDT 寫、主迴圈讀）
    private final Set<Integer> keys = ConcurrentHashMap.newKeySet();
    private double mouseDX, mouseDY;
    private volatile boolean showMap = true, fisheye = false;
    private volatile double fovDeg = Renderer.DEFAULT_FOV;
    private volatile int hoverColumn = -1;                       // 滑鼠停在主畫面的哪一欄
    private volatile int viewX, viewY, viewW = W, viewH = H;     // 主畫面在視窗裡的位置
    private double fps;

    Main(World world) {
        this.world = world;
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        renderer = new Renderer(world, W, H, pixels);
        rayView = new RayView(world, renderer);
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
                // 後面可以接 x y 角度 仰角 [欄]
                double[] nums = new double[5];
                int n = 0;
                while (n < 5 && i + 1 < args.length && args[i + 1].matches("-?[0-9.]+")) nums[n++] = Double.parseDouble(args[++i]);
                if (n >= 4) at = Arrays.copyOf(nums, n);
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

    /** 原地轉一圈，量每張畫面的渲染時間（不開視窗）。 */
    private void bench() {
        int frames = 720;
        for (int i = 0; i < 120; i++) { angle += 0.05; renderer.render(camera()); }   // 暖機
        long t0 = System.nanoTime();
        for (int i = 0; i < frames; i++) { angle += 2 * Math.PI / frames; renderer.render(camera()); }
        double ms = (System.nanoTime() - t0) / 1e6 / frames;
        System.out.printf("%d×%d：平均 %.2f ms / 張（約 %.0f fps）%n", W, H, ms, 1000 / ms);
    }

    // ---- 主迴圈 ----

    private void run() throws Exception {
        Canvas canvas = new Canvas();
        SwingUtilities.invokeAndWait(() -> {
            // 主畫面在左、光線視圖在右，一起塞進螢幕
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int rayW = (int) Math.min(640, screen.width * 0.36);
            int mainW = Math.max(W, Math.min(W * 2, screen.width - rayW - 30));
            JFrame frame = new JFrame("ColumnRay — " + world.name);
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
            renderer.render(camera());
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
                if (!keys.add(code)) return;                     // 按住時的自動重複
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

    /** 視窗座標 → 主畫面的第幾欄；不在畫面上回傳 -1。 */
    private int columnAt(int mx) {
        int c = (int) Math.floor((mx - viewX) * (double) W / viewW);
        return c >= 0 && c < W ? c : -1;
    }

    // ---- 玩家物理 ----

    private void update(double dt) {
        double fov = fovDeg;
        if (Math.abs(renderer.fov() - fov) > 1e-6) renderer.setFov(fov);
        int hover = hoverColumn;
        renderer.traceColumn = hover >= 0 ? hover : W / 2;       // 光線視圖要詳細記錄的那一欄

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
            int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 0.1));   // 分段移動，避免穿過薄牆
            for (int i = 0; i < n; i++) {
                if (!blocked(x + dx / n, y, feet)) x += dx / n;
                if (!blocked(x, y + dy / n, feet)) y += dy / n;
            }
        }

        double[] sup = support(x, y, feet);
        double ground = sup[0], ceil = sup[1];
        if (grounded && feet > ground && feet - ground <= STEP) feet = ground;   // 下台階時貼地
        if (grounded && down(KeyEvent.VK_SPACE)) vz = JUMP_SPEED;
        vz -= GRAVITY * dt;
        feet += vz * dt;
        if (feet <= ground) { feet = ground; vz = 0; grounded = true; } else grounded = false;
        if (feet + eyeH + HEAD_ABOVE_EYE > ceil) {
            feet = Math.max(ground, ceil - eyeH - HEAD_ABOVE_EYE);
            vz = Math.min(vz, 0);
        }

        double target = crouch ? EYE_CROUCH : EYE_STAND;
        if (!crouch && feet + EYE_STAND + HEAD_ABOVE_EYE > ceil) target = eyeH;  // 頭上有東西，站不起來
        eyeH += (target - eyeH) * Math.min(1, dt * 10);
        if (grounded && Math.abs(feet - viewFeet) < 1) viewFeet += (feet - viewFeet) * Math.min(1, dt * 14);
        else viewFeet = feet;
    }

    private double head(double feet) { return feet + eyeH + HEAD_ABOVE_EYE; }

    /** 地板比腳高一個台階以上、天花板比頭低、或碰到擋路的形狀，就不能走。 */
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

    /** { 腳下能站的最高面, 頭上最低的東西 } */
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
        cam.pitch = renderer.focal() * Math.tan(pitch);     // y-shearing
        cam.fisheye = fisheye;
        return cam;
    }

    // ---- 輸出 ----

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
        renderer.traceColumn = at != null && at.length > 4 ? (int) at[4] : W / 2;
        renderer.render(camera());
        BufferedImage img = new BufferedImage(W * 2, H * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        drawFrame(g, 0, 0, W * 2, H * 2, true);
        g.dispose();
        ImageIO.write(img, "png", out);
        System.out.println("寫入 " + out);

        // 同一個畫面的光線視圖
        File raysOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-rays.png"));
        BufferedImage rays = new BufferedImage(640, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = rays.createGraphics();
        rayView.draw(rg, rays.getWidth(), rays.getHeight(), new RayView.View(x, y, angle, fisheye));
        rg.dispose();
        ImageIO.write(rays, "png", raysOut);
        System.out.println("寫入 " + raysOut);
    }

    private void drawFrame(Graphics2D g, int ox, int oy, int dw, int dh, boolean markColumn) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, ox, oy, dw, dh, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int col = renderer.traceColumn;
        if (markColumn && col >= 0) {                        // 光線視圖裡紅線對應的那一欄
            double cx = ox + (col + 0.5) * dw / W;
            g.setColor(new Color(255, 70, 50, 140));
            g.draw(new Line2D.Double(cx, oy, cx, oy + dh));
        }
        drawHud(g, ox + 12, oy + 20);
        if (showMap) drawMinimap(g, ox + dw - 12, oy + 12);
    }

    private void drawHud(Graphics2D g, int left, int top) {
        Region r = world.regionAt(x, y);
        String[] lines = {
            String.format("%s   %.0f fps   %d×%d   FOV %.0f°   %s", world.name, fps, W, H, renderer.fov(),
                    fisheye ? "魚眼對照（直線距離，錯的）" : "垂直距離"),
            String.format("%s   (%.1f, %.1f)   腳 %.2f m", r == null ? "—" : r.name, x, y, feet),
            "WASD 移動  拖曳滑鼠 / 方向鍵 看  Space 跳  C 蹲  Shift 跑",
            "[ ] 視野  F 魚眼對照  R 光線視圖  M 小地圖  Esc 離開　滑鼠停在畫面上 = 選那一欄",
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
}

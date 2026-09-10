package engine;

import engine.Renderer.EventKind;
import engine.Renderer.Trace;
import engine.Renderer.TraceEvent;
import engine.World.Grid;
import engine.World.Region;
import engine.World.Shape;
import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFrame;

/** Second window: a top-down view of where every column's ray goes and where it stops. */
final class RayView {
    record View(double x, double y, double angle, boolean fisheye, double pitchDeg, boolean shear) {}

    /** left: draw the text to the left of the point (right-aligned against it). */
    private record Label(double x, double y, String text, Color color, boolean left) {
        Label(double x, double y, String text, Color color) { this(x, y, text, color, false); }
    }

    private static final Color BG = new Color(0x16181c);
    private static final Color GRID = new Color(255, 255, 255, 16);
    private static final Color CELL = new Color(255, 210, 60, 55);
    private static final Color RAY = new Color(255, 225, 120, 45);
    private static final Color PICK = new Color(255, 70, 50);
    private static final Color CAMERA = new Color(90, 220, 255);
    private static final Color HIT = new Color(255, 150, 40);
    private static final Color HIDDEN = new Color(150, 150, 150);
    private static final Color CROSS = new Color(110, 160, 255);
    private static final Color TEXT = new Color(230, 230, 230);
    private static final Color PANEL = new Color(0, 0, 0, 170);
    private static final Font FONT = new Font(Font.DIALOG, Font.PLAIN, 12);
    private static final int LINE = 16;

    private final World world;
    private final Renderer renderer;
    private final java.awt.Shape[] outlines;
    private final Path2D gridPath;                   // every grid line as one path: one draw call
    private final Path2D[] regionPaths;              // region outlines, built once
    private final Path2D fan = new Path2D.Double();  // reused each frame for the whole ray fan

    /** The grid, the regions and the shape footprints never move, so they are drawn once into this
     *  image and blitted with the view transform. That replaces several hundred translucent
     *  antialiased shape operations per frame - by far the most expensive thing this window did. */
    private BufferedImage staticMap;
    private double mapX0, mapY0, mapPpm;
    private JFrame frame;
    private Canvas canvas;
    private volatile double zoom = 30;               // pixels per metre
    private volatile boolean followTurn = true;      // keep the player pointing up, so left/right match the main view

    RayView(World world, Renderer renderer) {
        this.world = world;
        this.renderer = renderer;
        Grid g = world.grid;
        gridPath = new Path2D.Double();
        double gx1 = g.x0 + g.nx * g.cell, gy1 = g.y0 + g.ny * g.cell;
        for (int i = 0; i <= g.nx; i++) {
            gridPath.moveTo(g.x0 + i * g.cell, g.y0);
            gridPath.lineTo(g.x0 + i * g.cell, gy1);
        }
        for (int j = 0; j <= g.ny; j++) {
            gridPath.moveTo(g.x0, g.y0 + j * g.cell);
            gridPath.lineTo(gx1, g.y0 + j * g.cell);
        }
        regionPaths = new Path2D[world.regions.length];
        for (int i = 0; i < regionPaths.length; i++) regionPaths[i] = path(world.regions[i].xs, world.regions[i].ys);
        outlines = new java.awt.Shape[world.shapes.length];
        for (int i = 0; i < outlines.length; i++) {
            Shape s = world.shapes[i];
            outlines[i] = switch (s.kind) {
                case SEG -> new Line2D.Double(s.ax, s.ay, s.bx, s.by);
                case CIRCLE -> new Ellipse2D.Double(s.cx - s.r, s.cy - s.r, s.r * 2, s.r * 2);
                case POLY -> path(s.xs, s.ys);
            };
        }
        buildStaticMap(zoom);
    }

    /** Rebuild the cached map if the zoom has moved far enough that it would visibly resample.
     *  Built at the display zoom so it blits 1:1 and hairlines survive; only zooming pays for it. */
    private void ensureStaticMap(double z) {
        Grid g = world.grid;
        double maxPpm = Math.sqrt(8e6 / Math.max(1, g.nx * g.cell * g.ny * g.cell));
        double ppm = Math.max(8, Math.min(z, maxPpm));
        if (staticMap != null && Math.abs(ppm - mapPpm) <= mapPpm * 0.15) return;
        buildStaticMap(ppm);
    }

    private void buildStaticMap(double ppm) {
        Grid g = world.grid;
        double maxPpm = Math.sqrt(8e6 / Math.max(1, g.nx * g.cell * g.ny * g.cell));
        mapPpm = Math.max(8, Math.min(ppm, maxPpm));
        mapX0 = g.x0;
        mapY0 = g.y0;
        int w = (int) Math.ceil(g.nx * g.cell * mapPpm);
        int h = (int) Math.ceil(g.ny * g.cell * mapPpm);
        staticMap = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D d = staticMap.createGraphics();
        d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        d.scale(mapPpm, mapPpm);
        d.translate(-mapX0, -mapY0);
        float px = (float) (1 / mapPpm);

        d.setStroke(new BasicStroke(px));
        d.setColor(GRID);
        d.draw(gridPath);
        for (int i = 0; i < regionPaths.length; i++) {
            Region r = world.regions[i];
            d.setColor(alpha(r.floorColor, r.sky ? 45 : 75));
            d.fill(regionPaths[i]);
            d.setColor(alpha(r.floorColor, 130));
            d.draw(regionPaths[i]);
        }
        for (int i = 0; i < outlines.length; i++) {
            Shape s = world.shapes[i];
            if (s.kind == World.Kind.SEG) {
                d.setStroke(new BasicStroke(px * 2.5f));
                d.setColor(alpha(s.color, 230));
                d.draw(outlines[i]);
                continue;
            }
            d.setStroke(new BasicStroke(px));
            d.setColor(alpha(s.color, s.z0 > 0.3 ? 35 : 120));   // things up in the air are fainter
            d.fill(outlines[i]);
            d.setColor(alpha(s.color, 230));
            d.draw(outlines[i]);
        }
        d.dispose();
    }

    /** Call on the EDT: opens to the right of the main window, at the same height. */
    void open(Window owner, int width) {
        frame = new JFrame("Ray view");
        canvas = new Canvas();
        int h = owner.getHeight() - owner.getInsets().top - owner.getInsets().bottom;
        canvas.setPreferredSize(new Dimension(width, h));
        canvas.setIgnoreRepaint(true);
        frame.add(canvas);
        frame.pack();
        frame.setLocation(owner.getX() + owner.getWidth() + 6, owner.getY());
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setVisible(true);
        canvas.createBufferStrategy(2);
        canvas.addMouseWheelListener(e ->
                zoom = Math.max(4, Math.min(240, zoom * Math.pow(1.15, -e.getPreciseWheelRotation()))));
        canvas.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { canvas.requestFocus(); }   // so N reaches the key listener
        });
        canvas.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_N) followTurn = !followTurn;
            }
        });
        canvas.requestFocus();
    }

    boolean visible() {
        JFrame f = frame;
        return f != null && f.isVisible();
    }

    void toggle() {
        if (frame != null) EventQueue.invokeLater(() -> frame.setVisible(!frame.isVisible()));
    }

    void present(View v) {
        if (!visible()) return;
        try {
            BufferStrategy bs = canvas.getBufferStrategy();
            do {
                do {
                    Graphics2D g = (Graphics2D) bs.getDrawGraphics();
                    draw(g, canvas.getWidth(), canvas.getHeight(), v);
                    g.dispose();
                } while (bs.contentsRestored());
                bs.show();
            } while (bs.contentsLost());
        } catch (IllegalStateException e) {
            // the window is being hidden or re-shown; just skip this frame
        }
    }

    void draw(Graphics2D g, int w, int h, View v) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        double z = zoom, pl = renderer.planeHalfWidth();
        double dx = Math.cos(v.angle()), dy = Math.sin(v.angle());
        double plX = -dy * pl, plY = dx * pl;
        Trace tr = renderer.trace;
        float px = (float) (1 / z);

        // World -> screen: the player sits low and centred, facing up, so left/right match the main view
        AffineTransform m = new AffineTransform();
        m.translate(w / 2.0, h * 0.62);
        m.scale(z, z);
        if (followTurn) m.rotate(-Math.PI / 2 - v.angle());
        m.translate(-v.x(), -v.y());
        AffineTransform screen = g.getTransform();
        g.transform(m);

        // The static world - grid, regions, shape footprints - as one prebuilt image
        Grid gr = world.grid;
        ensureStaticMap(z);
        // g already carries the view transform, so this only has to map image pixels to world metres
        AffineTransform mapAt = AffineTransform.getTranslateInstance(mapX0, mapY0);
        mapAt.scale(1 / mapPpm, 1 / mapPpm);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(staticMap, mapAt, null);

        // The cells the traced ray's DDA walked
        if (tr != null) {
            g.setColor(CELL);
            for (int[] c : tr.cells)
                g.fill(new Rectangle2D.Double(gr.x0 + c[0] * gr.cell, gr.y0 + c[1] * gr.cell, gr.cell, gr.cell));
        }

        // Every ray, drawn from the player to the point where its column became full. When pitched
        // this includes the overscan columns only the top (or bottom) rows of the screen use.
        int x0 = renderer.drawnX0, x1 = renderer.drawnX1;
        double cx = renderer.centerX(), F = renderer.focal();
        g.setStroke(new BasicStroke(px));
        g.setColor(RAY);
        fan.reset();
        // A couple of hundred lines is plenty to read the fan, and these are translucent antialiased
        // lines - the single most expensive thing this window draws.
        int stride = Math.max(1, (x1 - x0) / 160);
        for (int x = x0; x < x1; x += stride) {
            double off = (x + 0.5 - cx) / F, rx = dx - dy * off, ry = dy + dx * off, t = renderer.rayEnd[x];
            fan.moveTo(v.x(), v.y());
            fan.lineTo(v.x() + rx * t, v.y() + ry * t);
        }
        g.draw(fan);

        // Camera: direction vector and camera plane
        List<Label> labels = new ArrayList<>();
        g.setColor(CAMERA);
        g.setStroke(new BasicStroke(px * 2));
        g.draw(new Line2D.Double(v.x() + dx - plX, v.y() + dy - plY, v.x() + dx + plX, v.y() + dy + plY));
        g.draw(new Line2D.Double(v.x(), v.y(), v.x() + dx, v.y() + dy));
        labels.add(new Label(v.x() + dx + plX, v.y() + dy + plY, "camera plane", CAMERA));

        // The traced column
        if (tr != null) {
            double rx = tr.rx, ry = tr.ry;
            g.setColor(PICK);
            g.setStroke(new BasicStroke(px * 2.5f));
            g.draw(new Line2D.Double(v.x(), v.y(), v.x() + rx * tr.endT, v.y() + ry * tr.endT));

            // Perpendicular distance: the first thing actually drawn, projected onto the view direction
            TraceEvent first = null;
            for (TraceEvent e : tr.events)
                if (e.kind().numbered() && e.rows() > 0) { first = e; break; }
            if (first != null && first.t() > 0) {
                double fx = v.x() + dx * first.t(), fy = v.y() + dy * first.t();
                g.setColor(CAMERA);
                g.setStroke(new BasicStroke(px * 1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                        new float[] {px * 5, px * 4}, 0));
                g.draw(new Line2D.Double(first.x(), first.y(), fx, fy));
                g.draw(new Line2D.Double(v.x(), v.y(), fx, fy));
                double straight = first.t() * Math.hypot(rx, ry);
                if (straight - first.t() < 0.01) {
                    labels.add(new Label(fx, fy, String.format("t = %.2f (dead centre: perpendicular = straight-line)", first.t()), CAMERA, true));
                } else {
                    labels.add(new Label(fx, fy, String.format("t = %.2f perpendicular (used for projection)", first.t()), CAMERA, true));
                    labels.add(new Label(first.x(), first.y(), String.format("straight-line %.2f (using this gives fisheye)", straight), HIDDEN));
                }
            }

            int n = 0;
            double r = 4.5 / z;
            for (TraceEvent e : tr.events) {
                if (!e.kind().numbered()) continue;
                boolean portal = e.kind() == EventKind.PORTAL;
                n++;
                Color c = portal ? CROSS : e.rows() > 0 ? HIT : HIDDEN;
                g.setColor(c);
                if (portal) g.fill(new Rectangle2D.Double(e.x() - r, e.y() - r, 2 * r, 2 * r));
                else g.fill(new Ellipse2D.Double(e.x() - r, e.y() - r, 2 * r, 2 * r));
                labels.add(new Label(e.x(), e.y(), String.valueOf(n), c));
            }
        }

        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(v.x() - 0.3, v.y() - 0.3, 0.6, 0.6));
        g.setTransform(screen);

        // Labels: when several land on the same spot, stack them downwards
        g.setFont(FONT);
        FontMetrics fm = g.getFontMetrics();
        List<Rectangle2D> placed = new ArrayList<>();
        for (Label l : labels) {
            Point2D p = m.transform(new Point2D.Double(l.x(), l.y()), null);
            int tw = fm.stringWidth(l.text());
            double tx = l.left() ? p.getX() - 8 - tw : p.getX() + 6, ty = p.getY() - 6;
            Rectangle2D box = new Rectangle2D.Double(tx, ty - fm.getAscent(), tw, fm.getHeight());
            for (boolean moved = true; moved; ) {
                moved = false;
                for (Rectangle2D o : placed)
                    if (o.intersects(box)) { box.setRect(tx, o.getMaxY(), tw, fm.getHeight()); moved = true; }
            }
            placed.add(box);
            float bx = (float) box.getX(), by = (float) (box.getY() + fm.getAscent());
            g.setColor(Color.BLACK);
            g.drawString(l.text(), bx + 1, by + 1);
            g.setColor(l.color());
            g.drawString(l.text(), bx, by);
        }
        drawText(g, w, h, v, tr);
    }

    private void drawText(Graphics2D g, int w, int h, View v, Trace tr) {
        int x0 = renderer.drawnX0, x1 = renderer.drawnX1, W = Math.max(1, x1 - x0);
        double cells = 0, tests = 0;
        for (int x = x0; x < x1; x++) { cells += renderer.cellsVisited[x]; tests += renderer.shapesTested[x]; }

        List<String> head = new ArrayList<>();
        head.add("Ray view    wheel = zoom    N = follow turn / north up    (R in the main window toggles this)");
        head.add(String.format("Yellow: %d rays (%d across the view), every %d drawn, each to where its column filled",
                W, renderer.viewW, Math.max(1, W / 160)));
        head.add(String.format("Per ray: %.1f grid cells walked, %.1f shapes tested", cells / W, tests / W));
        head.add(String.format("FOV %.0f deg    %s", renderer.fov(),
                v.fisheye() ? "fisheye demo: projecting by straight-line distance (wrong)"
                            : "projecting by perpendicular distance (no fisheye)"));
        head.add(String.format("Pitch %+.0f deg    %s", v.pitchDeg(),
                v.shear() ? "y-shearing (old way: verticals stay vertical, top and bottom stretch)"
                          : "true perspective (verticals converge; extra rays cover the wider rows)"));
        panel(g, head, 8, 8, TEXT);

        if (tr == null) return;
        List<String> list = new ArrayList<>();
        double camX = (tr.column + 0.5 - renderer.centerX()) / renderer.focal() / renderer.planeHalfWidth();
        list.add(String.format("Red: column %d (camX %+.2f) stopped at t = %.2f, %s, after %d cells",
                tr.column, camX, tr.endT, tr.endReason, tr.cells.size()));
        list.add(String.format("     %d shapes intersected, %d rejected one by one, %d groups (%d shapes) rejected at once",
                tr.tested.size(), tr.skipped, tr.groupsSkipped, tr.groupMembersSkipped));
        int maxLines = Math.max(4, (int) (h * 0.34 / LINE));
        int n = 0;
        for (TraceEvent e : tr.events) {
            if (e.kind().numbered()) n++;
            if (list.size() >= maxLines) { list.add("   ... " + (tr.events.size() - list.size() + 1) + " more"); break; }
            String no = e.kind().numbered() ? String.format("%2d.", n) : "   .";
            String hidden = e.kind() == EventKind.SHAPE && e.rows() == 0 ? "  (hidden)" : "";
            list.add(String.format("%s t=%6.2f  %-8s %s  -> %d rows%s", no, e.t(), e.kind().text, e.label(), e.rows(), hidden));
        }
        panel(g, list, 8, h - 8 - list.size() * LINE - 8, TEXT);
    }

    private static void panel(Graphics2D g, List<String> lines, int x, int y, Color color) {
        g.setFont(FONT);
        FontMetrics fm = g.getFontMetrics();
        int width = 0;
        for (String s : lines) width = Math.max(width, fm.stringWidth(s));
        g.setColor(PANEL);
        g.fillRoundRect(x, y, width + 16, lines.size() * LINE + 8, 8, 8);
        g.setColor(color);
        for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), x + 8, y + 4 + fm.getAscent() + i * LINE);
    }

    private static Path2D path(double[] xs, double[] ys) {
        Path2D p = new Path2D.Double();
        p.moveTo(xs[0], ys[0]);
        for (int i = 1; i < xs.length; i++) p.lineTo(xs[i], ys[i]);
        p.closePath();
        return p;
    }

    private static Color alpha(int rgb, int a) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, a);
    }
}

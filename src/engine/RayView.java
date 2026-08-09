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
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFrame;

/** Second window: a top-down view of where every column's ray goes and where it stops. */
final class RayView {
    record View(double x, double y, double angle, boolean fisheye) {}

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
    private JFrame frame;
    private Canvas canvas;
    private volatile double zoom = 30;               // pixels per metre
    private volatile boolean followTurn = true;      // keep the player pointing up, so left/right match the main view

    RayView(World world, Renderer renderer) {
        this.world = world;
        this.renderer = renderer;
        outlines = new java.awt.Shape[world.shapes.length];
        for (int i = 0; i < outlines.length; i++) {
            Shape s = world.shapes[i];
            outlines[i] = switch (s.kind) {
                case SEG -> new Line2D.Double(s.ax, s.ay, s.bx, s.by);
                case CIRCLE -> new Ellipse2D.Double(s.cx - s.r, s.cy - s.r, s.r * 2, s.r * 2);
                case POLY -> path(s.xs, s.ys);
            };
        }
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

        // The acceleration grid, and the cells the traced ray's DDA walked
        Grid gr = world.grid;
        g.setStroke(new BasicStroke(px));
        g.setColor(GRID);
        double gx1 = gr.x0 + gr.nx * gr.cell, gy1 = gr.y0 + gr.ny * gr.cell;
        for (int i = 0; i <= gr.nx; i++) g.draw(new Line2D.Double(gr.x0 + i * gr.cell, gr.y0, gr.x0 + i * gr.cell, gy1));
        for (int j = 0; j <= gr.ny; j++) g.draw(new Line2D.Double(gr.x0, gr.y0 + j * gr.cell, gx1, gr.y0 + j * gr.cell));
        if (tr != null) {
            g.setColor(CELL);
            for (int[] c : tr.cells)
                g.fill(new Rectangle2D.Double(gr.x0 + c[0] * gr.cell, gr.y0 + c[1] * gr.cell, gr.cell, gr.cell));
        }

        // Regions and shapes
        for (Region r : world.regions) {
            Path2D p = path(r.xs, r.ys);
            g.setColor(alpha(r.floorColor, r.sky ? 45 : 75));
            g.fill(p);
            g.setColor(alpha(r.floorColor, 130));
            g.draw(p);
        }
        for (int i = 0; i < outlines.length; i++) {
            Shape s = world.shapes[i];
            if (s.kind == World.Kind.SEG) {
                g.setStroke(new BasicStroke(px * 2.5f));
                g.setColor(alpha(s.color, 230));
                g.draw(outlines[i]);
                continue;
            }
            g.setStroke(new BasicStroke(px));
            g.setColor(alpha(s.color, s.z0 > 0.3 ? 35 : 120));   // things up in the air (desktops, tree canopies) are fainter
            g.fill(outlines[i]);
            g.setColor(alpha(s.color, 230));
            g.draw(outlines[i]);
        }

        // Every ray, drawn from the player to the point where its column became full
        int W = renderer.W;
        g.setStroke(new BasicStroke(px));
        g.setColor(RAY);
        for (int x = 0; x < W; x += 2) {
            double camX = 2 * (x + 0.5) / W - 1, rx = dx + plX * camX, ry = dy + plY * camX, t = renderer.rayEnd[x];
            g.draw(new Line2D.Double(v.x(), v.y(), v.x() + rx * t, v.y() + ry * t));
        }

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
        int W = renderer.W;
        double cells = 0, tests = 0;
        for (int x = 0; x < W; x++) { cells += renderer.cellsVisited[x]; tests += renderer.shapesTested[x]; }

        List<String> head = new ArrayList<>();
        head.add("Ray view    wheel = zoom    N = follow turn / north up    (R in the main window toggles this)");
        head.add(String.format("Yellow: %d rays, each drawn until its column is full    avg %.1f cells walked, %.1f shapes tested per ray",
                W, cells / W, tests / W));
        head.add(String.format("FOV %.0f deg    %s", renderer.fov(),
                v.fisheye() ? "fisheye demo: projecting by straight-line distance (wrong)"
                            : "projecting by perpendicular distance (no fisheye)"));
        panel(g, head, 8, 8, TEXT);

        if (tr == null) return;
        List<String> list = new ArrayList<>();
        double camX = 2 * (tr.column + 0.5) / W - 1;
        list.add(String.format("Red: column %d (camX %+.2f) stopped at t = %.2f, %s, after %d cells",
                tr.column, camX, tr.endT, tr.endReason, tr.cells.size()));
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

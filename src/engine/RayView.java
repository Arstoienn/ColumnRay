package engine;

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

/** 第二個視窗：從上往下看，每一欄的光線怎麼走、在哪裡停下來。 */
final class RayView {
    record View(double x, double y, double angle, boolean fisheye) {}

    /** left：文字放在點的左邊（靠右對齊）。 */
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
    private volatile double zoom = 30;               // 像素 / 公尺
    private volatile boolean followTurn = true;      // 玩家永遠朝上（畫面左右 = 主畫面左右）

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

    /** 在 EDT 上呼叫：開在主視窗右邊，高度一樣。 */
    void open(Window owner, int width) {
        frame = new JFrame("光線視圖");
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
        canvas.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_N) followTurn = !followTurn;
            }
        });
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
            // 視窗正在關閉或重新顯示，下一張再畫
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

        // 世界 → 畫面：玩家在下方中間，朝上時畫面左右就是主畫面左右
        AffineTransform m = new AffineTransform();
        m.translate(w / 2.0, h * 0.62);
        m.scale(z, z);
        if (followTurn) m.rotate(-Math.PI / 2 - v.angle());
        m.translate(-v.x(), -v.y());
        AffineTransform screen = g.getTransform();
        g.transform(m);

        // 加速格子，和紅色光線 DDA 走過的格子
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

        // 區域和形狀
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
            g.setColor(alpha(s.color, s.z0 > 0.3 ? 35 : 120));   // 懸空的（桌面、樹冠）比較淡
            g.fill(outlines[i]);
            g.setColor(alpha(s.color, 230));
            g.draw(outlines[i]);
        }

        // 所有光線：從玩家畫到那一欄被填滿為止
        int W = renderer.W;
        g.setStroke(new BasicStroke(px));
        g.setColor(RAY);
        for (int x = 0; x < W; x += 2) {
            double camX = 2 * (x + 0.5) / W - 1, rx = dx + plX * camX, ry = dy + plY * camX, t = renderer.rayEnd[x];
            g.draw(new Line2D.Double(v.x(), v.y(), v.x() + rx * t, v.y() + ry * t));
        }

        // 相機：方向向量和相機平面
        List<Label> labels = new ArrayList<>();
        g.setColor(CAMERA);
        g.setStroke(new BasicStroke(px * 2));
        g.draw(new Line2D.Double(v.x() + dx - plX, v.y() + dy - plY, v.x() + dx + plX, v.y() + dy + plY));
        g.draw(new Line2D.Double(v.x(), v.y(), v.x() + dx, v.y() + dy));
        labels.add(new Label(v.x() + dx + plX, v.y() + dy + plY, "相機平面", CAMERA));

        // 選中的那一欄
        if (tr != null) {
            double rx = tr.rx, ry = tr.ry;
            g.setColor(PICK);
            g.setStroke(new BasicStroke(px * 2.5f));
            g.draw(new Line2D.Double(v.x(), v.y(), v.x() + rx * tr.endT, v.y() + ry * tr.endT));

            // 垂直距離：第一個真的畫出來的東西，投影到視線方向上
            TraceEvent first = null;
            for (TraceEvent e : tr.events)
                if ((e.kind().equals("物件") || e.kind().equals("跨區")) && e.rows() > 0) { first = e; break; }
            if (first != null && first.t() > 0) {
                double fx = v.x() + dx * first.t(), fy = v.y() + dy * first.t();
                g.setColor(CAMERA);
                g.setStroke(new BasicStroke(px * 1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                        new float[] {px * 5, px * 4}, 0));
                g.draw(new Line2D.Double(first.x(), first.y(), fx, fy));
                g.draw(new Line2D.Double(v.x(), v.y(), fx, fy));
                double straight = first.t() * Math.hypot(rx, ry);
                if (straight - first.t() < 0.01) {
                    labels.add(new Label(fx, fy, String.format("t = %.2f（正中間：垂直距離 = 直線距離）", first.t()), CAMERA, true));
                } else {
                    labels.add(new Label(fx, fy, String.format("t = %.2f 垂直距離（拿來投影）", first.t()), CAMERA, true));
                    labels.add(new Label(first.x(), first.y(), String.format("直線距離 %.2f（用它會魚眼）", straight), HIDDEN));
                }
            }

            int n = 0;
            double r = 4.5 / z;
            for (TraceEvent e : tr.events) {
                boolean cross = e.kind().equals("跨區");
                if (!cross && !e.kind().equals("物件")) continue;
                n++;
                Color c = cross ? CROSS : e.rows() > 0 ? HIT : HIDDEN;
                g.setColor(c);
                if (cross) g.fill(new Rectangle2D.Double(e.x() - r, e.y() - r, 2 * r, 2 * r));
                else g.fill(new Ellipse2D.Double(e.x() - r, e.y() - r, 2 * r, 2 * r));
                labels.add(new Label(e.x(), e.y(), String.valueOf(n), c));
            }
        }

        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(v.x() - 0.3, v.y() - 0.3, 0.6, 0.6));
        g.setTransform(screen);

        // 標籤：同一個位置有好幾個時往下排開
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
        head.add("光線視圖　滾輪縮放　N 切換跟著轉 / 北朝上　（主視窗按 R 開關）");
        head.add(String.format("黃線：%d 欄的光線，畫到那一欄填滿為止　平均每條走 %.1f 格、測 %.1f 個形狀",
                W, cells / W, tests / W));
        head.add(String.format("FOV %.0f°　%s", renderer.fov(),
                v.fisheye() ? "魚眼對照中：用直線距離投影（錯的）" : "用垂直距離投影（沒有魚眼）"));
        panel(g, head, 8, 8, TEXT);

        if (tr == null) return;
        List<String> list = new ArrayList<>();
        double camX = 2 * (tr.column + 0.5) / W - 1;
        list.add(String.format("紅線：第 %d 欄（camX %+.2f）停在 t = %.2f，%s，走過 %d 格",
                tr.column, camX, tr.endT, tr.endReason, tr.cells.size()));
        int maxLines = Math.max(4, (int) (h * 0.34 / LINE));
        int n = 0;
        for (TraceEvent e : tr.events) {
            boolean numbered = e.kind().equals("物件") || e.kind().equals("跨區");
            if (numbered) n++;
            if (list.size() >= maxLines) { list.add("　…還有 " + (tr.events.size() - list.size() + 1) + " 筆"); break; }
            String no = numbered ? String.format("%2d.", n) : "   ·";
            String hidden = e.kind().equals("物件") && e.rows() == 0 ? "（被擋住）" : "";
            list.add(String.format("%s t=%6.2f  %s  %s  → %d 行%s", no, e.t(), e.kind(), e.label(), e.rows(), hidden));
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

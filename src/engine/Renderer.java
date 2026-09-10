package engine;

import engine.Geometry.PolyHit;
import engine.Geometry.SegHit;
import engine.Geometry.Span;
import engine.World.Grid;
import engine.World.Region;
import engine.World.Shape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

/**
 * 每一欄一條光線。沿著光線用 DDA 走格子，把「物件」和「區域邊界」依距離由近到遠處理，
 * 每樣東西只畫在這一欄還空著的行（interval），填滿就停。
 */
final class Renderer {
    static final double DEFAULT_FOV = Math.toDegrees(2 * Math.atan(0.66));   // PL = 0.66，約 67°
    static final double NEAR = 1e-3;
    static final double MAX_DIST = 80;
    private static final double TIE = 1e-6;   // 牆剛好在區域邊界上時，讓牆先畫

    /** 地圖外：露天、沒有地板，只繼續畫還碰得到的物件。 */
    private static final Region OUTSIDE = new Region();
    static {
        OUTSIDE.name = "地圖外";
        OUTSIDE.sky = true;
        OUTSIDE.floor = Double.NaN;
        OUTSIDE.ceil = OUTSIDE.top = Double.POSITIVE_INFINITY;
        OUTSIDE.light = 1;
    }

    static final class Camera {
        double x, y;          // 位置
        double dirX, dirY;    // 單位方向
        double eye;           // 眼睛高度（世界 z）
        double pitch;         // 地平線位移（像素），抬頭為正
        boolean fisheye;      // 對照用的錯誤示範：用直線距離投影
    }

    /** 光線視圖用：選中那一欄沿路發生的事。(x, y) 是事件在地圖上的位置。 */
    record TraceEvent(String kind, double t, String label, int rows, double x, double y) {}

    static final class Trace {
        int column;
        double rx, ry, endT;
        String endReason = "";
        final List<int[]> cells = new ArrayList<>();
        final List<TraceEvent> events = new ArrayList<>();
    }

    final int W, H;
    private double pl;                       // 相機平面半寬 = tan(FOV/2)
    private double F;                        // 焦距（像素）
    private final int[] pixels;
    private final World world;
    private final ThreadLocal<Column> columns;

    // 光線視圖用的資料：每欄停下來的距離、走過幾格、測了幾個形狀
    final double[] rayEnd;
    final int[] cellsVisited, shapesTested;
    volatile int traceColumn = -1;           // 要詳細記錄的那一欄
    volatile Trace trace;                    // 最近一次的記錄

    Renderer(World world, int w, int h, int[] pixels) {
        this.world = world;
        this.W = w;
        this.H = h;
        this.pixels = pixels;
        this.rayEnd = new double[w];
        this.cellsVisited = new int[w];
        this.shapesTested = new int[w];
        this.columns = ThreadLocal.withInitial(() -> new Column());
        setFov(DEFAULT_FOV);
    }

    /** 水平視野（度）。垂直方向用同一個焦距，所以像素永遠是正方形，不會拉長。 */
    void setFov(double deg) {
        pl = Math.tan(Math.toRadians(deg) / 2);
        F = (W / 2.0) / pl;
    }

    double fov() { return Math.toDegrees(2 * Math.atan(pl)); }

    double planeHalfWidth() { return pl; }

    double focal() { return F; }

    void render(Camera cam) {
        int chunks = Math.min(W, Runtime.getRuntime().availableProcessors() * 4);
        IntStream.range(0, chunks).parallel().forEach(c -> {
            Column col = columns.get();
            for (int x = c * W / chunks, end = (c + 1) * W / chunks; x < end; x++) col.render(x, cam);
        });
    }

    private record Hit(Shape s, double t1, double t2, boolean inside, double nx, double ny, double u) {}

    private static final Comparator<Hit> BY_T1 = Comparator.comparingDouble(Hit::t1);

    /** 單欄的暫存；每個執行緒一份。 */
    private final class Column {
        private int[] o0 = new int[H + 2], o1 = new int[H + 2], n0 = new int[H + 2], n1 = new int[H + 2];
        private int open;                                   // 還空著的行區間數
        private final int[] stamp = new int[world.shapes.length];
        private int ray;
        private final ArrayList<Hit> pending = new ArrayList<>();

        private int x;
        private double px, py, rx, ry, eye, hz;
        private double dk;                                  // 深度倍率：正常是 1，魚眼對照時是 |r|
        private Region cur;                                 // 光線目前所在的區域
        private double tPrev;                               // 地板 / 天花板已經畫到的距離
        private double crossT, crossU;                      // 下一個區域邊界
        private int crossEdge, crossings;

        // 統計和記錄
        private Trace tr;
        private double endT, lastT;
        private String endReason;
        private int cells, tests;

        void render(int x, Camera cam) {
            this.x = x;
            px = cam.x;
            py = cam.y;
            eye = cam.eye;
            hz = H / 2.0 + cam.pitch;                       // 地平線（抬頭低頭只改這個）
            double camX = 2 * (x + 0.5) / W - 1;            // -1 左 … +1 右
            rx = cam.dirX - cam.dirY * pl * camX;           // plane = [-dir.y·PL, dir.x·PL]
            ry = cam.dirY + cam.dirX * pl * camX;           // 刻意不 normalize：t 就是垂直距離
            dk = cam.fisheye ? Math.hypot(rx, ry) : 1;      // 魚眼：把垂直距離換成直線距離
            open = 1;
            o0[0] = 0;
            o1[0] = H;
            if (++ray == Integer.MAX_VALUE) { Arrays.fill(stamp, 0); ray = 1; }
            pending.clear();
            crossings = 0;
            cells = tests = 0;
            endT = -1;
            lastT = 0;
            tr = x == traceColumn ? new Trace() : null;
            if (tr != null) { tr.column = x; tr.rx = rx; tr.ry = ry; }

            Region start = world.regionAt(px, py);
            cur = start == null ? OUTSIDE : start;
            tPrev = NEAR;
            exitOf(cur, NEAR);
            walkGrid();
            flush(Double.POSITIVE_INFINITY);
            surfaces(tPrev, MAX_DIST);
            if (endT < 0) {
                endT = Math.min(lastT, MAX_DIST);
                endReason = open == 0 ? "整欄填滿" : lastT >= MAX_DIST ? "到最大距離" : "走出地圖，剩下的行畫天空";
            }
            fillRest();

            rayEnd[x] = endT;
            cellsVisited[x] = cells;
            shapesTested[x] = tests;
            if (tr != null) {
                tr.endT = endT;
                tr.endReason = endReason;
                trace = tr;
            }
        }

        // ---- DDA：只測光線經過的格子 ----

        private void walkGrid() {
            Grid g = world.grid;
            double fx = (px - g.x0) / g.cell, fy = (py - g.y0) / g.cell;
            int cx = (int) Math.floor(fx), cy = (int) Math.floor(fy);
            int sx = rx > 0 ? 1 : -1, sy = ry > 0 ? 1 : -1;
            double dx = rx == 0 ? Double.POSITIVE_INFINITY : g.cell / Math.abs(rx);
            double dy = ry == 0 ? Double.POSITIVE_INFINITY : g.cell / Math.abs(ry);
            double tx = rx == 0 ? Double.POSITIVE_INFINITY : (rx > 0 ? cx + 1 - fx : fx - cx) * dx;
            double ty = ry == 0 ? Double.POSITIVE_INFINITY : (ry > 0 ? cy + 1 - fy : fy - cy) * dy;

            while (open > 0 && cx >= 0 && cy >= 0 && cx < g.nx && cy < g.ny) {
                cells++;
                if (tr != null) tr.cells.add(new int[] {cx, cy});
                for (int i : g.shapes[cy * g.nx + cx]) {
                    if (stamp[i] == ray) continue;
                    stamp[i] = ray;
                    tests++;
                    Hit h = intersect(world.shapes[i]);
                    if (h != null) pending.add(h);
                }
                double tout = Math.min(tx, ty);
                // 形狀若在更遠的格子才被發現，它的進入點一定在那格之後，所以到 tout 為止的順序已經確定
                flush(tout);
                lastT = tout;
                if (tout > MAX_DIST) return;
                if (tx < ty) { tx += dx; cx += sx; } else { ty += dy; cy += sy; }
            }
        }

        /** 依距離處理所有 t ≤ tout 的事件：物件，或跨進下一個區域。 */
        private void flush(double tout) {
            pending.sort(BY_T1);
            int k = 0;
            while (open > 0) {
                boolean hitFirst = k < pending.size() && pending.get(k).t1() <= crossT + TIE;   // 同距離時物件優先（牆在邊界上）
                double tn = hitFirst ? pending.get(k).t1() : crossT;
                if (tn > tout || tn == Double.POSITIVE_INFINITY) break;
                if (hitFirst) {
                    Hit h = pending.get(k++);
                    double t = Math.max(h.t1(), tPrev);
                    surfaces(tPrev, t);
                    tPrev = t;
                    drawHit(h);
                } else {
                    surfaces(tPrev, crossT);
                    tPrev = crossT;
                    cross();
                }
                if (open == 0 && endT < 0) {
                    endT = Math.max(tn, 0);
                    endReason = "整欄填滿";
                }
            }
            pending.subList(0, k).clear();
        }

        // ---- 交點 ----

        private Hit intersect(Shape s) {
            return switch (s.kind) {
                case SEG -> {
                    SegHit h = Geometry.raySeg(px, py, rx, ry, s.ax, s.ay, s.bx, s.by);
                    if (h == null || h.t() <= NEAR || h.t() > s.maxDist) yield null;
                    double nx = -h.ey() / s.len, ny = h.ex() / s.len;   // 邊向量轉 90°
                    if (nx * rx + ny * ry > 0) { nx = -nx; ny = -ny; }   // 朝向相機
                    yield new Hit(s, h.t(), h.t(), false, nx, ny, h.u() * s.len);
                }
                case CIRCLE -> {
                    Span sp = Geometry.rayCircle(px, py, rx, ry, s.cx, s.cy, s.r);
                    if (sp == null || sp.t2() <= NEAR || sp.t1() > s.maxDist) yield null;
                    double nx = (px + rx * sp.t1() - s.cx) / s.r;       // 交點減圓心
                    double ny = (py + ry * sp.t1() - s.cy) / s.r;
                    yield new Hit(s, sp.t1(), sp.t2(), sp.t1() <= NEAR, nx, ny, (Math.atan2(ny, nx) + Math.PI) * s.r);
                }
                case POLY -> {
                    PolyHit ph = Geometry.rayPoly(px, py, rx, ry, s.xs, s.ys);
                    if (ph == null || ph.t2() <= NEAR || ph.t1() > s.maxDist) yield null;
                    int i = ph.enterEdge(), j = (i + 1) % s.xs.length;
                    double ex = s.xs[j] - s.xs[i], ey = s.ys[j] - s.ys[i], len = Math.hypot(ex, ey);
                    double nx = -ey / len, ny = ex / len;
                    if (nx * rx + ny * ry > 0) { nx = -nx; ny = -ny; }
                    yield new Hit(s, ph.t1(), ph.t2(), ph.t1() <= NEAR, nx, ny, ph.enterU() * len);
                }
            };
        }

        private void exitOf(Region r, double tMin) {
            if (r == OUTSIDE) {                           // 目前不會從地圖外再走回來
                crossT = Double.POSITIVE_INFINITY;
                return;
            }
            PolyHit ph = Geometry.rayPoly(px, py, rx, ry, r.xs, r.ys);
            if (ph == null || ph.t2() <= tMin) {        // 擦邊：往前推一點再找
                crossT = tMin + 1e-3;
                crossEdge = -1;
            } else {
                crossT = ph.t2();
                crossEdge = ph.exitEdge();
                crossU = ph.exitU();
            }
        }

        // ---- 畫：物件、區域邊界、地板天花板 ----

        /** 側面、頂面、底面。天花板會裁切比它高的物件。 */
        private void drawHit(Hit h) {
            Shape s = h.s();
            double z0 = s.z0, top = Math.min(s.h, cur.ceil);
            if (top <= z0) {
                if (tr != null) note("物件", h.t1(), s.label + "（整個在天花板上面）", 0);
                return;
            }
            double t1 = h.t1(), yTop = rowZ(top, t1), yBot = rowZ(z0, t1);
            double light = cur.light;
            int side = 0, cap = 0, under = 0;
            if (!h.inside()) {
                double k = lambert(h.nx(), h.ny()) * fog(t1) * light;
                double u = h.u();
                side = paint(yTop, yBot, y -> shade(s.color, Materials.side(s.mat, u, eye - (y + 0.5 - hz) * t1 * dk / F) * k));
            }
            if (eye > top) cap = paint(rowZ(top, h.t2()), h.inside() ? H : yTop, flat(top, s.topMat, s.color, light));
            if (eye < z0) under = paint(h.inside() ? 0 : yBot, rowZ(z0, h.t2()), flat(z0, s.mat, s.color, 0.45 * light));
            if (tr != null) {
                String where = h.inside() ? "（站在範圍內）" : "";
                note("物件", t1, String.format("%s%s 側%d 頂%d 底%d", s.label, where, side, cap, under), side + cap + under);
            }
        }

        /** 跨過區域邊界：下一區天花板較低就畫門楣，地板較高就畫台階立面。 */
        private void cross() {
            double t = crossT;
            Region from = cur;
            Region found = world.regionAt(px + rx * (t + 1e-4), py + ry * (t + 1e-4));
            Region to = found == null || ++crossings > 256 ? OUTSIDE : found;
            int lintel = 0, riser = 0;
            if (crossEdge >= 0 && to != from && to != OUTSIDE) {
                int n = from.xs.length, i = crossEdge, j = (i + 1) % n;
                double ex = from.xs[j] - from.xs[i], ey = from.ys[j] - from.ys[i], len = Math.hypot(ex, ey);
                double nx = -ey / len, ny = ex / len;
                if (nx * rx + ny * ry > 0) { nx = -nx; ny = -ny; }
                double k = lambert(nx, ny) * fog(t) * to.light, u = crossU * len;
                IntUnaryOperator wall = y -> shade(to.wallColor, Materials.side(to.wallMat, u, eye - (y + 0.5 - hz) * t * dk / F) * k);
                double upper = from.sky ? to.top : from.ceil;
                if (to.ceil < upper) lintel = paint(rowZ(upper, t), rowZ(to.ceil, t), wall);             // 門楣
                if (to.floor > from.floor) riser = paint(rowZ(to.floor, t), rowZ(from.floor, t), wall); // 台階立面
            }
            if (tr != null && to != from) {
                String detail = (lintel > 0 ? " 門楣" + lintel : "") + (riser > 0 ? " 台階" + riser : "");
                note("跨區", t, from.name + " → " + to.name + detail, lintel + riser);
            }
            cur = to;
            exitOf(to, t);
        }

        /** 目前區域在距離 [ta, tb] 之間的地板和天花板。 */
        private void surfaces(double ta, double tb) {
            if (tb <= ta) return;
            Region r = cur;
            int f = 0, c = 0;
            if (eye > r.floor) f = paint(rowZ(r.floor, tb), rowZ(r.floor, ta), flat(r.floor, r.floorMat, r.floorColor, r.light));
            if (!r.sky && eye < r.ceil) c = paint(rowZ(r.ceil, ta), rowZ(r.ceil, tb), flat(r.ceil, r.ceilMat, r.ceilColor, r.light));
            if (tr != null) {
                String range = String.format("%s  t %.2f–%.2f", r.name, ta, Math.min(tb, MAX_DIST));
                if (f > 0) note("地板", ta, range, f);
                if (c > 0) note("天花板", ta, range, c);
            }
        }

        /** 水平面：從行反推距離，找到它在地圖上的哪裡。 */
        private IntUnaryOperator flat(double z, int mat, int color, double k0) {
            return y -> {
                double t = (eye - z) * F / ((y + 0.5 - hz) * dk);
                if (!(t > 0) || t > MAX_DIST) return shade(color, 0.3 * k0);
                return shade(color, Materials.flat(mat, px + rx * t, py + ry * t) * k0 * fog(t));
            };
        }

        /** 剩下的行：地平線以上是天空，以下是遠方的霧。 */
        private void fillRest() {
            for (int k = 0; k < open; k++)
                for (int y = o0[k]; y < o1[k]; y++) pixels[y * W + x] = y < hz ? sky(y) : 0x3a3c40;
            open = 0;
        }

        private int sky(int y) {
            double s = Math.max(0, Math.min(1, (hz - y) / (H * 0.9)));
            return rgb(205 - 125 * s, 222 - 87 * s, 238 - 28 * s);
        }

        private void note(String kind, double t, String label, int rows) {
            tr.events.add(new TraceEvent(kind, t, label, rows, px + rx * t, py + ry * t));
        }

        // ---- interval：只填還空著的行 ----

        /** 回傳實際填了幾行。 */
        private int paint(double a, double b, IntUnaryOperator colorOf) {
            int ia = clampRow(a), ib = clampRow(b);
            if (ib <= ia) return 0;
            int m = 0, filled = 0;
            for (int k = 0; k < open; k++) {
                int s0 = Math.max(o0[k], ia), s1 = Math.min(o1[k], ib);
                if (s1 <= s0) { n0[m] = o0[k]; n1[m++] = o1[k]; continue; }
                for (int y = s0; y < s1; y++) pixels[y * W + x] = colorOf.applyAsInt(y);
                filled += s1 - s0;
                if (s0 > o0[k]) { n0[m] = o0[k]; n1[m++] = s0; }   // 上方剩下的
                if (o1[k] > s1) { n0[m] = s1; n1[m++] = o1[k]; }   // 下方剩下的
            }
            int[] t0 = o0, t1 = o1;
            o0 = n0; o1 = n1; n0 = t0; n1 = t1;
            open = m;
            return filled;
        }

        private int clampRow(double v) {
            return (int) Math.round(Math.max(0, Math.min(H, v)));
        }

        /** 投影公式：整個引擎只有這一條。 */
        private double rowZ(double z, double t) {
            return hz - (z - eye) * F / (t * dk);
        }

        private double lambert(double nx, double ny) {
            return 0.3 + 0.7 * Math.max(0, nx * world.sunX + ny * world.sunY);
        }
    }

    private static double fog(double t) {
        return Math.max(0.3, 1 - t / 45);
    }

    private static int shade(int c, double k) {
        return rgb(((c >> 16) & 255) * k, ((c >> 8) & 255) * k, (c & 255) * k);
    }

    private static int rgb(double r, double g, double b) {
        return (Math.min(255, (int) r) << 16) | (Math.min(255, (int) g) << 8) | Math.min(255, (int) b);
    }
}

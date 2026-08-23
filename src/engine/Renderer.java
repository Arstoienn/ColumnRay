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
 * One ray per screen column. The ray walks the acceleration grid with a DDA and handles "shapes"
 * and "region boundaries" strictly nearest-first; each of them only paints into the rows of that
 * column that are still empty (the interval list), and the column stops once every row is filled.
 */
final class Renderer {
    static final double DEFAULT_FOV = Math.toDegrees(2 * Math.atan(0.66));   // PL = 0.66, about 67 degrees
    static final double NEAR = 1e-3;
    static final double MAX_DIST = 80;
    static final int MAX_STOREYS = 8;      // how many storeys can stack at one (x, y)
    private static final double TIE = 1e-6;   // when a wall sits exactly on a region boundary, draw the wall first

    static final class Camera {
        double x, y;          // position
        double dirX, dirY;    // unit direction
        double eye;           // eye height (world z)
        double pitch;         // horizon offset in pixels, positive when looking up
        boolean fisheye;      // deliberately wrong comparison mode: project by straight-line distance
    }

    /** What kind of thing the ray ran into. Only SHAPE and PORTAL events get a number in the ray view. */
    enum EventKind {
        SHAPE("shape"), PORTAL("portal"), FLOOR("floor"), CEILING("ceiling");

        final String text;

        EventKind(String text) { this.text = text; }

        boolean numbered() { return this == SHAPE || this == PORTAL; }
    }

    /** For the ray view: one thing that happened along the traced column. (x, y) is where it happened. */
    record TraceEvent(EventKind kind, double t, String label, int rows, double x, double y) {}

    static final class Trace {
        int column;
        double rx, ry, endT;
        String endReason = "";
        final List<int[]> cells = new ArrayList<>();
        final List<TraceEvent> events = new ArrayList<>();
    }

    final int W, H;
    private double pl;                       // half-width of the camera plane = tan(FOV/2)
    private double F;                        // focal length in pixels
    private final int[] pixels;
    private final World world;
    private final ThreadLocal<Column> columns;

    // Ray-view statistics: where each column's ray stopped, how many cells it walked, how many shapes it tested
    final double[] rayEnd;
    final int[] cellsVisited, shapesTested;
    volatile int traceColumn = -1;           // the column to record in detail
    volatile Trace trace;                    // the most recent recording

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

    /** Horizontal field of view in degrees. The vertical axis uses the same focal length,
     *  so pixels stay square and nothing is stretched. */
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

    /** Scratch state for a single column; one instance per thread. */
    private final class Column {
        private int[] o0 = new int[H + 2], o1 = new int[H + 2], n0 = new int[H + 2], n1 = new int[H + 2];
        private int open;                                   // number of row intervals still empty
        private final int[] stamp = new int[world.shapes.length];
        private int ray;
        private final ArrayList<Hit> pending = new ArrayList<>();

        private int x;
        private double px, py, rx, ry, eye, hz;
        private double dk;                                  // depth multiplier: 1 normally, |r| in fisheye mode

        // The stack of storeys the ray is currently inside, lowest floor first. Empty means off the
        // map. Everything *not* inside one of these [floor, ceil) ranges is solid: that is the floor
        // slab between two storeys, and its absence is a hole to see through.
        private final Region[] stack = new Region[MAX_STOREYS];
        private final Region[] prev = new Region[MAX_STOREYS];
        private final Region[] next = new Region[MAX_STOREYS];
        private int stackN;
        private final double[] openLo = new double[MAX_STOREYS + 1], openHi = new double[MAX_STOREYS + 1];

        private double tPrev;                               // how far the floor / ceiling has been drawn
        private double crossT, crossU;                      // the next region boundary
        private int crossEdge, crossings;

        // Statistics and recording
        private Trace tr;
        private double endT, lastT;
        private String endReason;
        private int cells, tests;

        void render(int x, Camera cam) {
            this.x = x;
            px = cam.x;
            py = cam.y;
            eye = cam.eye;
            hz = H / 2.0 + cam.pitch;                       // horizon row (looking up/down only moves this)
            double camX = 2 * (x + 0.5) / W - 1;            // -1 left ... +1 right
            rx = cam.dirX - cam.dirY * pl * camX;           // plane = [-dir.y*PL, dir.x*PL]
            ry = cam.dirY + cam.dirX * pl * camX;           // deliberately not normalised: t is the perpendicular distance
            dk = cam.fisheye ? Math.hypot(rx, ry) : 1;      // fisheye: turn perpendicular distance into straight-line distance
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

            stackN = world.regionsAt(px, py, stack);
            tPrev = NEAR;
            nextCross(NEAR);
            walkGrid();
            flush(Double.POSITIVE_INFINITY);
            surfaces(tPrev, MAX_DIST);
            if (endT < 0) {
                endT = Math.min(lastT, MAX_DIST);
                endReason = open == 0 ? "column full"
                        : lastT >= MAX_DIST ? "reached max distance"
                        : "left the map, remaining rows are sky";
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

        // ---- DDA: only test the cells the ray actually passes through ----

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
                // A shape first seen in a later cell must enter beyond that cell, so the ordering
                // of everything up to tout is already final.
                flush(tout);
                lastT = tout;
                if (tout > MAX_DIST) return;
                if (tx < ty) { tx += dx; cx += sx; } else { ty += dy; cy += sy; }
            }
        }

        /** Handle every event with t <= tout in distance order: a shape, or a crossing into the next region. */
        private void flush(double tout) {
            pending.sort(BY_T1);
            int k = 0;
            while (open > 0) {
                // on a tie the shape wins, so a wall sitting on a region boundary is drawn first
                boolean hitFirst = k < pending.size() && pending.get(k).t1() <= crossT + TIE;
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
                    endReason = "column full";
                }
            }
            pending.subList(0, k).clear();
        }

        // ---- Intersection ----

        private Hit intersect(Shape s) {
            return switch (s.kind) {
                case SEG -> {
                    SegHit h = Geometry.raySeg(px, py, rx, ry, s.ax, s.ay, s.bx, s.by);
                    if (h == null || h.t() <= NEAR || h.t() > s.maxDist) yield null;
                    double nx = -h.ey() / s.len, ny = h.ex() / s.len;    // edge vector rotated 90 degrees
                    if (nx * rx + ny * ry > 0) { nx = -nx; ny = -ny; }   // make it face the camera
                    yield new Hit(s, h.t(), h.t(), false, nx, ny, h.u() * s.len);
                }
                case CIRCLE -> {
                    Span sp = Geometry.rayCircle(px, py, rx, ry, s.cx, s.cy, s.r);
                    if (sp == null || sp.t2() <= NEAR || sp.t1() > s.maxDist) yield null;
                    double nx = (px + rx * sp.t1() - s.cx) / s.r;       // hit point minus centre
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

        /** The nearest point at which any storey in the stack ends. Once off the map, rays currently
         *  never come back in, so there is nothing further to cross. */
        private void nextCross(double tMin) {
            crossT = Double.POSITIVE_INFINITY;
            crossEdge = -1;
            crossU = 0;
            if (stackN == 0) return;
            for (int i = 0; i < stackN; i++) {
                Region r = stack[i];
                PolyHit ph = Geometry.rayPoly(px, py, rx, ry, r.xs, r.ys);
                if (ph == null || ph.t2() <= tMin) {      // grazing the corner: nudge forward and look again
                    if (tMin + 1e-3 < crossT) { crossT = tMin + 1e-3; crossEdge = -1; crossU = 0; }
                } else if (ph.t2() < crossT) {
                    crossT = ph.t2();
                    crossEdge = ph.exitEdge();
                    crossU = ph.exitU();
                }
            }
        }

        /** The storey whose [floor, ceil) contains z, else the highest one below it, else null. */
        private Region storeyAt(double z) {
            Region best = null;
            for (int i = 0; i < stackN; i++) {
                Region r = stack[i];
                if (z >= r.floor && z < r.ceil) return r;
                if (r.floor <= z && (best == null || r.floor > best.floor)) best = r;
            }
            return best != null ? best : stackN > 0 ? stack[0] : null;
        }

        /**
         * Open (walkable / see-through) z ranges of a stack, ascending, into openLo/openHi;
         * everything in between is solid. The last entry, when there is one, is the sky above the
         * topmost storey's `top` - only usable by a viewer who is themselves under open sky, which
         * is why the count is returned separately from {@link #openSpansNoSky}. Someone standing
         * indoors has their own ceiling in the way, so for them the far side stays solid all the
         * way up to it.
         */
        private int skySpans;

        private int openSpans(Region[] st, int n) {
            int m = 0;
            double highestTop = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                openLo[m] = st[i].floor;
                openHi[m++] = st[i].ceil;
                highestTop = Math.max(highestTop, Math.max(st[i].top, st[i].ceil));
            }
            skySpans = m;
            if (n > 0 && highestTop < Double.POSITIVE_INFINITY) {
                openLo[m] = highestTop;                   // out over the roof: sky
                openHi[m++] = Double.POSITIVE_INFINITY;
            }
            return m;
        }

        // ---- Drawing: shapes, region boundaries, floors and ceilings ----

        /** Side face, top face (when the eye is above it) and bottom face (when the eye is below it).
         *  A shape is clipped to the ceiling of the region the ray is in. */
        private void drawHit(Hit h) {
            Shape s = h.s();
            // Clip to the ceiling of the storey the EYE is in, not the one the shape stands in:
            // the point of the clip is that your own ceiling is opaque. A wall running the full
            // height of the building is therefore drawn up to 3.2 m from the ground floor and up to
            // the second floor's ceiling from up there, and the floor slab in between hides the rest.
            Region in = storeyAt(eye);
            double z0 = s.z0, top = Math.min(s.h, in == null ? Double.POSITIVE_INFINITY : in.ceil);
            if (top <= z0) {
                if (tr != null) note(EventKind.SHAPE, h.t1(), s.label + " (entirely above the ceiling)", 0);
                return;
            }
            double t1 = h.t1(), yTop = rowZ(top, t1), yBot = rowZ(z0, t1);
            double light = in == null ? 1 : in.light;
            int side = 0, cap = 0, under = 0;
            if (!h.inside()) {
                double k = lambert(h.nx(), h.ny()) * fog(t1) * light;
                double u = h.u();
                side = paint(yTop, yBot, y -> shade(s.color, Materials.side(s.mat, u, eye - (y + 0.5 - hz) * t1 * dk / F) * k));
            }
            if (eye > top) cap = paint(rowZ(top, h.t2()), h.inside() ? H : yTop, flat(top, s.topMat, s.color, light));
            if (eye < z0) under = paint(h.inside() ? 0 : yBot, rowZ(z0, h.t2()), flat(z0, s.mat, s.color, 0.45 * light));
            if (tr != null) {
                String where = h.inside() ? " (eye inside its footprint)" : "";
                note(EventKind.SHAPE, t1,
                        String.format("%s%s side %d top %d bottom %d", s.label, where, side, cap, under),
                        side + cap + under);
            }
        }

        /**
         * Cross a region boundary. Wall is visible wherever open space on the near side meets solid
         * on the far side: that one rule produces a lintel where the far ceiling is lower, a step
         * riser where the far floor is higher, and the face of a floor slab seen from the storey
         * below or above - which the old near/far pair of special cases could not express.
         */
        private void cross() {
            double t = crossT;
            int fromN = stackN;
            System.arraycopy(stack, 0, prev, 0, fromN);
            int toN = ++crossings > 256 ? 0
                    : world.regionsAt(px + rx * (t + 1e-4), py + ry * (t + 1e-4), next);

            int rows = 0;
            if (crossEdge >= 0 && toN > 0 && !same(prev, fromN, next, toN)) {
                Region edgeOf = prev[0];
                int n = edgeOf.xs.length, i = Math.min(crossEdge, n - 1), j = (i + 1) % n;
                double ex = edgeOf.xs[j] - edgeOf.xs[i], ey = edgeOf.ys[j] - edgeOf.ys[i];
                double len = Math.hypot(ex, ey);
                double nx = -ey / len, ny = ex / len;
                if (nx * rx + ny * ry > 0) { nx = -nx; ny = -ny; }
                double u = crossU * len;
                double lam = lambert(nx, ny) * fog(t);

                int farN = openSpans(next, toN), farNoSky = skySpans;
                double[] flo = openLo.clone(), fhi = openHi.clone();
                for (int a = 0; a < fromN; a++) {
                    double z = prev[a].floor, zEnd = prev[a].ceil;
                    int spans = prev[a].sky ? farN : farNoSky;   // only open sky sees sky
                    for (int b = 0; b < spans && z < zEnd; b++) {
                        if (fhi[b] <= z) continue;
                        if (flo[b] >= zEnd) break;
                        if (flo[b] > z) rows += wallBand(z, Math.min(flo[b], zEnd), t, u, lam, nearestFar(toN, z));
                        z = Math.max(z, fhi[b]);
                    }
                    if (z < zEnd) rows += wallBand(z, zEnd, t, u, lam, next[toN - 1]);
                }
            }

            if (tr != null && !same(prev, fromN, next, toN))
                note(EventKind.PORTAL, t, name(prev, fromN) + " -> " + name(next, toN)
                        + (rows > 0 ? " wall " + rows : ""), rows);

            stackN = toN;
            System.arraycopy(next, 0, stack, 0, toN);
            nextCross(t);
        }

        /** One solid band of the far side, seen through an opening on the near side. */
        private int wallBand(double zLo, double zHi, double t, double u, double lam, Region skin) {
            if (!(zHi > zLo) || skin == null) return 0;
            double k = lam * skin.light;
            IntUnaryOperator wall = y ->
                    shade(skin.wallColor, Materials.side(skin.wallMat, u, eye - (y + 0.5 - hz) * t * dk / F) * k);
            return paint(rowZ(zHi, t), rowZ(zLo, t), wall);
        }

        /** Which far storey's wall finish to use for a solid band starting at z. */
        private Region nearestFar(int toN, double z) {
            Region best = next[0];
            double bestD = Double.POSITIVE_INFINITY;
            for (int i = 0; i < toN; i++) {
                double d = Math.min(Math.abs(next[i].floor - z), Math.abs(next[i].ceil - z));
                if (d < bestD) { bestD = d; best = next[i]; }
            }
            return best;
        }

        private static boolean same(Region[] a, int an, Region[] b, int bn) {
            if (an != bn) return false;
            for (int i = 0; i < an; i++) if (a[i] != b[i]) return false;
            return true;
        }

        private static String name(Region[] st, int n) {
            if (n == 0) return "outside map";
            StringBuilder b = new StringBuilder(st[0].name);
            for (int i = 1; i < n; i++) b.append('+').append(st[i].name);
            return b.toString();
        }

        /**
         * The floors and ceilings of every storey in the stack, between distances ta and tb.
         *
         * Drawing the whole stack needs no depth sorting: for any given row, a higher floor is
         * always seen at a nearer distance than a lower one, and a lower ceiling nearer than a
         * higher one (t scales with |z - eye|). Since segments are handled near-to-far and paint()
         * only fills rows that are still empty, the surface you should see always claims the row
         * first. Going highest-floor-first and lowest-ceiling-first keeps that true within a single
         * segment too, where the two bands can overlap.
         */
        private void surfaces(double ta, double tb) {
            if (tb <= ta) return;
            for (int i = stackN - 1; i >= 0; i--) {
                Region r = stack[i];
                if (eye <= r.floor) continue;
                int f = paint(rowZ(r.floor, tb), rowZ(r.floor, ta), flat(r.floor, r.floorMat, r.floorColor, r.light));
                if (tr != null && f > 0)
                    note(EventKind.FLOOR, ta, String.format("%s  t %.2f-%.2f", r.name, ta, Math.min(tb, MAX_DIST)), f);
            }
            for (int i = 0; i < stackN; i++) {
                Region r = stack[i];
                if (r.sky || eye >= r.ceil) continue;
                int c = paint(rowZ(r.ceil, ta), rowZ(r.ceil, tb), flat(r.ceil, r.ceilMat, r.ceilColor, r.light));
                if (tr != null && c > 0)
                    note(EventKind.CEILING, ta, String.format("%s  t %.2f-%.2f", r.name, ta, Math.min(tb, MAX_DIST)), c);
            }
        }

        /** Horizontal surfaces: invert the projection to get the distance for a row,
         *  then look up where that lands on the map. */
        private IntUnaryOperator flat(double z, int mat, int color, double k0) {
            return y -> {
                double t = (eye - z) * F / ((y + 0.5 - hz) * dk);
                if (!(t > 0) || t > MAX_DIST) return shade(color, 0.3 * k0);
                return shade(color, Materials.flat(mat, px + rx * t, py + ry * t) * k0 * fog(t));
            };
        }

        /** Whatever rows are left: sky above the horizon, distant haze below it. */
        private void fillRest() {
            for (int k = 0; k < open; k++)
                for (int y = o0[k]; y < o1[k]; y++) pixels[y * W + x] = y < hz ? sky(y) : 0x3a3c40;
            open = 0;
        }

        private int sky(int y) {
            double s = Math.max(0, Math.min(1, (hz - y) / (H * 0.9)));
            return rgb(205 - 125 * s, 222 - 87 * s, 238 - 28 * s);
        }

        private void note(EventKind kind, double t, String label, int rows) {
            tr.events.add(new TraceEvent(kind, t, label, rows, px + rx * t, py + ry * t));
        }

        // ---- Interval list: only ever fill rows that are still empty ----

        /** Returns how many rows were actually filled. */
        private int paint(double a, double b, IntUnaryOperator colorOf) {
            int ia = clampRow(a), ib = clampRow(b);
            if (ib <= ia) return 0;
            int m = 0, filled = 0;
            for (int k = 0; k < open; k++) {
                int s0 = Math.max(o0[k], ia), s1 = Math.min(o1[k], ib);
                if (s1 <= s0) { n0[m] = o0[k]; n1[m++] = o1[k]; continue; }
                for (int y = s0; y < s1; y++) pixels[y * W + x] = colorOf.applyAsInt(y);
                filled += s1 - s0;
                if (s0 > o0[k]) { n0[m] = o0[k]; n1[m++] = s0; }   // leftover above
                if (o1[k] > s1) { n0[m] = s1; n1[m++] = o1[k]; }   // leftover below
            }
            int[] t0 = o0, t1 = o1;
            o0 = n0; o1 = n1; n0 = t0; n1 = t1;
            open = m;
            return filled;
        }

        private int clampRow(double v) {
            return (int) Math.round(Math.max(0, Math.min(H, v)));
        }

        /** The projection. This one line is the whole of it. */
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

package engine;

import engine.Geometry.PolyHit;
import engine.Geometry.SegHit;
import engine.Geometry.Span;
import engine.World.Grid;
import engine.World.Region;
import engine.World.Shape;
import java.util.ArrayList;
import java.util.Arrays;
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
        boolean captureDepth; // only screenshots need depth and albedo buffers
        boolean baked;        // shade from the baked lightmaps (Lighting) instead of the old flat model
        // Which part of the buffer to render this frame: columns [x0, x1), rows [y0, y1).
        int x0, y0, x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE;
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
        final List<Shape> tested = new ArrayList<>();     // every shape that got a real intersection test
        int skipped;                                      // shapes rejected one by one by skip()
        int groupsSkipped, groupMembersSkipped;           // whole groups rejected at once, and their size
    }

    /** The pixel buffer. It can be larger than the view: looking up or down needs a little
     *  overscan around it, which is then warped into the tilted view (see {@link Warp}). */
    int W, H;
    int viewW, viewH;                        // the image the camera actually shows
    private double F;                        // focal length in pixels
    private int[] pixels;
    float[] depth;                          // perpendicular metres, or null outside a screenshot
    int[] albedo;                           // map colours, with no shading; shot only, like depth
    private final World world;
    private ThreadLocal<Column> columns;

    // Ray-view statistics: where each column's ray stopped, how many cells it walked, how many shapes it tested
    double[] rayEnd;
    int[] cellsVisited, shapesTested;
    int drawnX0, drawnX1;                    // the columns the last frame actually rendered
    volatile int traceColumn = -1;           // the column to record in detail (buffer column)
    volatile Trace trace;                    // the most recent recording
    private volatile Lighting lighting;      // baked lightmaps, or null: then only the flat model exists

    /**
     * The thread that drives the renderer, learnt from the first call to {@link #render}, and
     * whether a frame is in flight.
     *
     * W, H, pixels, viewW, viewH and F are read by every column of every frame and are changed
     * together: a resize is three arrays and four numbers, and there is no order in which those
     * can be written that leaves a frame started halfway through it looking at a consistent view.
     * Making them volatile would not fix that - it would only make the torn read reliable - so the
     * rule is that they are changed between frames, from the thread that renders, and the rule is
     * enforced here rather than written in a comment and hoped for.
     *
     * Today every caller obeys it: Main changes the view from its own loop. The point is the day
     * somebody wires the field of view to a Swing slider, because the bug that makes is a frame
     * that is torn only when the listener fires mid-render - the hardest kind there is to
     * reproduce - and this turns it into an exception naming the thread that did it.
     */
    private Thread driver;
    private boolean rendering;

    /** Refuse a change to the view from the wrong thread, or from inside a frame. */
    private void betweenFrames(String what) {
        if (rendering)
            throw new IllegalStateException(what + " while a frame is being rendered; "
                    + "the view may only change between frames");
        Thread t = driver;
        if (t != null && t != Thread.currentThread())
            throw new IllegalStateException(what + " from thread '" + Thread.currentThread().getName()
                    + "', but this renderer is driven by '" + t.getName() + "'; "
                    + "post the change to that thread and let it apply it between frames");
    }

    /** When set, every wall interval the columns paint is also recorded here for the GPU path.
     *  Null - which it is unless something asks otherwise - costs one null test an interval. */
    private volatile GpuSpans spanSink;

    /** The same, for the masked surfaces blended over the finished column. */
    private volatile GpuMasks maskSink;

    void captureSpans(GpuSpans s) {
        betweenFrames("captureSpans");
        spanSink = s;
    }

    void captureMasks(GpuMasks m) {
        betweenFrames("captureMasks");
        maskSink = m;
    }

    /** Which record on the card each of a shape's faces reads, for the span to carry. Null when
     *  the world has no images, and then no surface has one. */
    private volatile GpuMaterials cardMats;

    void setMaterials(GpuMaterials m) {
        betweenFrames("setMaterials");
        cardMats = m;
    }

    /**
     * Whether the CPU still colours the rows the card is going to draw.
     *
     * The game wants the card's picture and nothing else, so leaving those rows alone is the
     * whole point of putting them on the card - a frame the card draws entirely is one the CPU
     * never shades a pixel of. {@code GpuCheck} wants both pictures to hold them against each
     * other, so it leaves this on. It is on by default, which is the answer that is merely slow
     * rather than wrong.
     */
    private volatile boolean shadeUnderCard = true;

    void shadeUnderCard(boolean b) {
        betweenFrames("shadeUnderCard");
        shadeUnderCard = b;
    }

    void setLighting(Lighting l) {
        betweenFrames("setLighting");
        lighting = l;
    }

    Renderer(World world, int w, int h, int[] pixels) {
        this.world = world;
        this.viewW = w;
        this.viewH = h;
        resize(w, h, pixels);
        setFov(DEFAULT_FOV);
    }

    /** Change the size of the view itself - the ray count. The field of view is an angle, so it
     *  stays what it was and only the focal length that realises it changes. Between frames, on the
     *  rendering thread; see betweenFrames. */
    void setView(int w, int h) {
        betweenFrames("setView");
        double deg = fov();
        viewW = w;
        viewH = h;
        setFov(deg);
    }

    /** Point the renderer at a (usually larger) buffer. Between frames, on the rendering thread. */
    void resize(int w, int h, int[] pixels) {
        betweenFrames("resize");
        this.W = w;
        this.H = h;
        this.pixels = pixels;
        this.rayEnd = new double[w];
        this.cellsVisited = new int[w];
        this.shapesTested = new int[w];
        this.columns = ThreadLocal.withInitial(() -> new Column());   // per-column scratch is sized by H
    }

    /** Horizontal field of view in degrees, across the view (not the overscan). The vertical axis
     *  uses the same focal length, so pixels stay square and nothing is stretched. */
    void setFov(double deg) {
        betweenFrames("setFov");
        F = (viewW / 2.0) / Math.tan(Math.toRadians(deg) / 2);
    }

    double fov() { return Math.toDegrees(2 * Math.atan(planeHalfWidth())); }

    /** Half-width of the camera plane at distance 1 = tan(FOV/2): the edge of the view. */
    double planeHalfWidth() { return (viewW / 2.0) / F; }

    double focal() { return F; }

    /** The buffer column the view direction goes through. Column x casts the ray
     *  dir + perp * (x + 0.5 - centerX()) / F. */
    double centerX() { return W / 2.0; }

    void render(Camera cam) {
        betweenFrames("render");
        driver = Thread.currentThread();
        rendering = true;
        try {
            renderFrame(cam);
        } finally {
            rendering = false;
        }
    }

    private void renderFrame(Camera cam) {
        if (cam.captureDepth) {
            if (depth == null || depth.length != W * H) depth = new float[W * H];
            else Arrays.fill(depth, 0);                       // sky and anything not hit stay zero
            if (albedo == null || albedo.length != W * H) albedo = new int[W * H];
        } else {
            depth = null;
            albedo = null;
        }
        int x0 = Math.max(0, cam.x0), x1 = Math.min(W, cam.x1), n = x1 - x0;
        drawnX0 = x0;
        drawnX1 = Math.max(x0, x1);
        if (n <= 0) return;
        ThreadLocal<Column> cols = columns;
        int chunks = Math.min(n, Runtime.getRuntime().availableProcessors() * 4);
        // Every chunk takes every chunks-th column rather than a block of neighbours. A column down
        // one of Haven's long lanes shades many times the rows of one facing a wall, and neighbouring
        // columns see the same thing: a block of them was one slow chunk, the frame waited for it,
        // and on the M3's efficiency cores it waited longer. Interleaved, every chunk gets a share of
        // everything. Columns are independent, so the picture is the same.
        IntStream.range(0, chunks).parallel().forEach(c -> {
            Column col = cols.get();
            for (int x = x0 + c; x < x1; x += chunks) col.render(x, cam);
        });
    }

    /**
     * Where a ray met a shape. face: which of the shape's side lightmaps the hit is on - the side
     * of a thin wall (0/1), the edge of a polygon, or 0 for a cylinder.
     *
     * Mutable and pooled, like Masked, Plane and Cand below, and for the same reason: this is the
     * hot path. Every shape a ray so much as passes through makes one of these, and on Haven a
     * frame is millions of them - all of it garbage a millisecond later, and none of it eligible
     * for the escape analysis that would have made a record free, because they go into a list and
     * outlive the call that made them. A column's hits are now a pooled array it keeps for the
     * life of the thread.
     */
    private static final class Hit {
        Shape s;
        double t1, t2, nx, ny, u;
        boolean inside;
        int face;
    }

    /**
     * Distance order, and on an exact tie the lower shape id, so that what is drawn does not
     * depend on the order the grid happens to hand the shapes over in.
     *
     * Double.compare rather than {@code >}, which is not the same relation: it is what the
     * Comparator this replaced was built from, and it is the one that has a defined answer for
     * -0.0 and for a NaN distance.
     */
    private static boolean after(Hit a, Hit b) {
        int c = Double.compare(a.t1, b.t1);
        return c > 0 || (c == 0 && a.s.id > b.s.id);
    }

    /** Scratch state for a single column; one instance per thread. */
    private final class Column {
        private int[] o0 = new int[H + 2], o1 = new int[H + 2], n0 = new int[H + 2], n1 = new int[H + 2];
        private int open;                                   // number of row intervals still empty
        private final int[] stamp = new int[world.shapes.length];
        private final int[] nodes = new int[128];           // the tree walk's stack; a tree is ~log2(n) deep
        private int ray;

        /**
         * The hits found but not yet drawn, nearest first once sorted; pend[0, pendN) are live and
         * pend[0, sortedN) are known to be in order. Slots past pendN keep their Hit objects, so a
         * column after the first allocates nothing here at all.
         *
         * The sort is an insertion sort from sortedN, not a general one. It is the right shape for
         * what is actually being sorted: what is left over from the last flush is already in order,
         * and a cell's new hits are appended to it, so the work is proportional to how far out of
         * place the new ones are rather than to the whole list. It also sorts the array in place,
         * where a comparator sort of an object array allocates a merge buffer of its own.
         */
        private Hit[] pend = new Hit[32];
        private Hit[] carry = new Hit[32];                  // the drawn ones, on their way back to the pool
        private int pendN, sortedN;

        /**
         * A masked surface waiting to be blended in - a crown of leaves, a clump of ferns. It
         * cannot be painted when the ray reaches it, because what shows through its holes is
         * whatever is behind it and that has not been drawn yet. So the rows it could cover are
         * recorded, clipped there and then to what was still open (anything nearer has already had
         * its say), and blended over the finished picture afterwards, far to near.
         */
        private static final class Masked {
            Shape s;
            Lighting.LightMap lm;
            double t, u, sq, f, w;
            int y0, y1;
            // A cut-out's top or bottom rather than a side: each row finds its own distance on the
            // plane (z, slope) and its colour from the plane's own colour function.
            boolean plane;
            double z, slope;
            IntUnaryOperator color;
            /** Given to the card, so the CPU's own blend of it is not counted as a pixel the
             *  card was never shown. */
            boolean gpu;
        }

        private final double[] A = new double[6];          // an alpha sample; T is the colour's

        private final ArrayList<Masked> masked = new ArrayList<>();
        private int maskedN;

        /**
         * The top or the bottom of a shape the ray has reached. It spans distances t1..t2, and
         * something standing on it - a crate on a platform - enters somewhere in between and is
         * nearer than the far part of it. Painted all at once when the ray got there, its far rows
         * were already taken by the time the crate came along, and the crate lost its lower half.
         * So it is painted a stretch at a time, with the floors, as the ray moves on (surfaces()).
         */
        private static final class Plane {
            double gk0;
            int gmat, grgb, glm, gtex;
            boolean ggpu;
            boolean top, inside;
            boolean masked;                    // a cut-out's: recorded and blended in, never takes a row
            Shape owner;
            double z, slope, t1, t2;          // z: its height at the eye, slope: its climb along the ray
            int base;
            IntUnaryOperator color;
            int ev, which;                     // ray view: its shape's event, and 1 top / 2 bottom
            int[] rows;                        // ray view: that shape's side, top and bottom rows
            String label;
        }

        private final ArrayList<Plane> planes = new ArrayList<>();
        private int planeN;                                 // planes[0, planeN) are still being painted

        /** One horizontal or tilted surface to paint between two distances: a floor, a ceiling or a
         *  stretch of a Plane. Rows [lo, hi]. */
        private static final class Cand {
            double lo, hi, z, slope;
            double gk0;                        // the GPU path: the plane's light, material, colour and
            int gmat, grgb, glm, gtex;         // lightmap and texture, which its IntUnaryOperator hides
            boolean ggpu;                      // false for a lightmapped or image-textured plane
            int ia, ib, base;
            IntUnaryOperator color;
            Plane plane;
            Region region;
            EventKind kind;
        }

        private final ArrayList<Cand> cands = new ArrayList<>();
        private int nc;

        private int x;
        private double px, py, rx, ry, eye, hz;
        private double dk;                                  // depth multiplier: 1 normally, |r| in fisheye mode
        private Lighting lit;                               // this frame's lightmaps
        private boolean baked;                              // ...and whether to use them
        private final float[] L = new float[3];             // one lightmap sample
        private final double[] T = new double[6];           // RGB texture factor + mip scratch, per thread
        private Shape lodShape;                              // mappedSample's mip levels, for this shape and footprint
        private double lodW = Double.NaN, lodImg, lodH, lodB;

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
        /** The GPU path: where wall intervals go, and the wall currently being painted. */
        private GpuSpans sink;
        private GpuMasks maskOut;
        private GpuMaterials mats;
        /**
         * Rows the CPU must colour even though the card is drawing them, because a surface the
         * card was not given will be blended over them and needs something underneath.
         *
         * A masked surface is recorded when the ray meets it and blended at the end, so by the
         * time whatever is behind it comes to be painted, this already says so. Rows nothing
         * unported covers are left to the card alone, and that is the whole saving: a frame the
         * card draws entirely is one the CPU never shades a pixel of.
         */
        private final boolean[] cpuUnder = new boolean[H];
        private boolean under = true;                     // shade what the card draws anyway
        private int spanKind;                   // 0 nothing, 1 a wall, 2 a plane
        private double spanU, spanW, spanSq, spanLight, spanZ, spanSlope, spanFog;
        private int spanMat, spanRgb, spanLm, spanTex;

        private Trace tr;
        private double endT, lastT;
        private String endReason;
        private int cells, tests;

        void render(int x, Camera cam) {
            this.x = x;
            sink = spanSink;
            maskOut = maskSink;
            mats = cardMats;
            under = shadeUnderCard;
            px = cam.x;
            py = cam.y;
            eye = cam.eye;
            hz = H / 2.0 + cam.pitch;                       // horizon row (the renderer itself only ever y-shears)
            double off = (x + 0.5 - W / 2.0) / F;           // where this column crosses the camera plane
            rx = cam.dirX - cam.dirY * off;                 // plane = [-dir.y, dir.x]
            ry = cam.dirY + cam.dirX * off;                 // deliberately not normalised: t is the perpendicular distance
            dk = cam.fisheye ? Math.hypot(rx, ry) : 1;      // fisheye: turn perpendicular distance into straight-line distance
            lit = lighting;
            baked = cam.baked && lit != null;
            int y0 = Math.max(0, cam.y0), y1 = Math.min(H, cam.y1);
            open = y1 > y0 ? 1 : 0;
            o0[0] = y0;
            o1[0] = y1;
            if (++ray == Integer.MAX_VALUE) { Arrays.fill(stamp, 0); ray = 1; }
            pendN = sortedN = 0;
            maskedN = 0;
            if (sink != null) Arrays.fill(cpuUnder, false);
            planeN = 0;
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
            blendMasked();

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
                for (World.Group gp : g.groups[cy * g.nx + cx]) {
                    // Down the group's tree: one test for everything under a node - a storey's
                    // furniture, or the triangles of a mesh the ray passes nowhere near. Members are
                    // not stamped by it, so the ones reaching into the next cell are tested there.
                    int sp = 0;
                    nodes[sp++] = 0;
                    while (sp > 0) {
                        int k = nodes[--sp], a = gp.start[k], b = gp.end[k], o = 7 * k;
                        if (b - a > 1 && hidden(gp.nb[o], gp.nb[o + 1], gp.nb[o + 2], gp.nb[o + 3],
                                gp.nb[o + 4], gp.nb[o + 5], gp.nb[o + 6])) {
                            if (tr != null) { tr.groupsSkipped++; tr.groupMembersSkipped += b - a; }
                            continue;
                        }
                        if (gp.right[k] >= 0) {
                            nodes[sp++] = gp.right[k];
                            nodes[sp++] = k + 1;
                            continue;
                        }
                        for (int m = a; m < b; m++) {
                            int i = gp.members[m];
                            if (stamp[i] == ray) continue;
                            stamp[i] = ray;
                            Shape sh = world.shapes[i];
                            if (skip(sh)) {
                                if (tr != null) tr.skipped++;
                                continue;
                            }
                            tests++;
                            if (tr != null) tr.tested.add(sh);
                            if (intersect(sh, slot())) pendN++;
                        }
                    }
                }
                double tout = Math.min(tx, ty);
                // A shape first seen in a later cell must enter beyond that cell, so the ordering
                // of everything up to tout is already final.
                flush(tout);
                lastT = tout;
                paintAhead(tout);
                if (tout > MAX_DIST) return;
                if (tx < ty) { tx += dx; cx += sx; } else { ty += dy; cy += sy; }
            }
        }

        /** Handle every event with t <= tout in distance order: a shape, or a crossing into the next region. */
        private void flush(double tout) {
            sortPending();
            int k = 0;
            while (open > 0) {
                // on a tie the shape wins, so a wall sitting on a region boundary is drawn first
                boolean hitFirst = k < pendN && pend[k].t1 <= crossT + TIE;
                double tn = hitFirst ? pend[k].t1 : crossT;
                if (tn > tout || tn == Double.POSITIVE_INFINITY) break;
                if (hitFirst) {
                    Hit h = pend[k++];
                    double t = Math.max(h.t1, tPrev);
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
            drop(k);
        }

        /** The Hit to fill in next, kept from the last time this slot was used. */
        private Hit slot() {
            if (pendN == pend.length) {
                pend = Arrays.copyOf(pend, pendN * 2);
                carry = new Hit[pend.length];
            }
            Hit h = pend[pendN];
            return h != null ? h : (pend[pendN] = new Hit());
        }

        private void sortPending() {
            for (int i = sortedN; i < pendN; i++) {
                Hit h = pend[i];
                int j = i - 1;
                while (j >= 0 && after(pend[j], h)) pend[j + 1] = pend[j--];
                pend[j + 1] = h;
            }
            sortedN = pendN;
        }

        /** The first k have been drawn. They go to the back rather than away: the objects are the
         *  pool, and the ones behind them are still in order. */
        private void drop(int k) {
            if (k == 0) return;
            System.arraycopy(pend, 0, carry, 0, k);
            System.arraycopy(pend, k, pend, 0, pendN - k);
            System.arraycopy(carry, 0, pend, pendN - k, k);
            pendN -= k;
            sortedN = pendN;
        }

        // ---- Intersection ----

        /**
         * Cheap reject, before the real intersection: a slab test against the shape's bounding box
         * gives the range of distances it could be hit at, and from that the band of rows it could
         * possibly occupy. If every row in that band is already painted, the shape cannot show and
         * is not worth intersecting.
         *
         * This is what stops the upper storeys paying for the furniture on the ones below: standing
         * on the second floor, the floor slab fills those rows almost immediately, so the desks and
         * chairs downstairs are rejected in a few flops each. It stays correct when you look down
         * through the courtyard, because there the rows really are still open.
         */
        private boolean skip(Shape s) {
            return hidden(s.minX, s.minY, s.maxX, s.maxY, s.zLow, s.hTop, s.maxDist);
        }

        /** The same test for any box in x, y, z: a shape's bounds, or a whole group's. */
        private boolean hidden(double minX, double minY, double maxX, double maxY, double z0, double h, double maxDist) {
            double t0 = NEAR, t1 = MAX_DIST;
            if (rx != 0) {
                double a = (minX - px) / rx, b = (maxX - px) / rx;
                if (a > b) { double q = a; a = b; b = q; }
                t0 = Math.max(t0, a);
                t1 = Math.min(t1, b);
            } else if (px < minX || px > maxX) {
                return true;
            }
            if (ry != 0) {
                double a = (minY - py) / ry, b = (maxY - py) / ry;
                if (a > b) { double q = a; a = b; b = q; }
                t0 = Math.max(t0, a);
                t1 = Math.min(t1, b);
            } else if (py < minY || py > maxY) {
                return true;
            }
            if (t1 < t0 || t0 > maxDist) return true;            // the ray misses it, or it is too far
            int top = clampRow(Math.min(rowZ(h, t0), rowZ(h, t1)));
            int bot = clampRow(Math.max(rowZ(z0, t0), rowZ(z0, t1)));
            if (bot <= top) return true;
            for (int k = 0; k < open; k++) if (o0[k] < bot && o1[k] > top) return false;
            return true;
        }

        /**
         * Draw the floors and ceilings up to tout now, rather than at the next event.
         *
         * flush() has just handled everything up to tout, so nothing can happen before it any more
         * (the pending hits are sorted; the next crossing and the next hit bound it too). skip() and
         * the group test can only reject what is already painted, and floors used to be painted
         * lazily - only when the next shape or boundary came along. Walking across an empty room
         * upstairs, the rows your own floor was about to cover were therefore still open, and the
         * furniture on the storey below was intersection-tested only to be hidden. The pixels are
         * the same either way: splitting one stretch of floor into several paints the same rows.
         */
        private void paintAhead(double tout) {
            if (open == 0) return;
            double upTo = Math.min(Math.min(tout, crossT), MAX_DIST);
            if (pendN > 0) upTo = Math.min(upTo, pend[0].t1);
            if (upTo <= tPrev) return;
            surfaces(tPrev, upTo);
            tPrev = upTo;
            if (open == 0 && endT < 0) {
                endT = upTo;
                endReason = "column full";
            }
        }

        /** Fill {@code into} with where the ray meets this shape, or return false and leave it be. */
        private boolean intersect(Shape s, Hit into) {
            switch (s.kind) {
                case SEG -> {
                    if (!(s.len > 1e-9)) return false;            // a wall with no length has no normal to draw by
                    SegHit h = Geometry.raySeg(px, py, rx, ry, s.ax, s.ay, s.bx, s.by);
                    if (h == null || h.t() <= NEAR || h.t() > s.maxDist) return false;
                    double nx = -h.ey() / s.len, ny = h.ex() / s.len;    // edge vector rotated 90 degrees
                    boolean back = nx * rx + ny * ry > 0;
                    if (back) { nx = -nx; ny = -ny; }                      // make it face the camera
                    return set(into, s, h.t(), h.t(), false, nx, ny, h.u() * s.len, back ? 1 : 0);
                }
                case CIRCLE -> {
                    Span sp = Geometry.rayCircle(px, py, rx, ry, s.cx, s.cy, s.r);
                    if (sp == null || sp.t2() <= NEAR || sp.t1() > s.maxDist) return false;
                    double nx = (px + rx * sp.t1() - s.cx) / s.r;       // hit point minus centre
                    double ny = (py + ry * sp.t1() - s.cy) / s.r;
                    return set(into, s, sp.t1(), sp.t2(), sp.t1() <= NEAR, nx, ny,
                            (Math.atan2(ny, nx) + Math.PI) * s.r, 0);
                }
                case POLY -> {
                    PolyHit ph = Geometry.rayPoly(px, py, rx, ry, s.xs, s.ys);
                    if (ph == null || ph.t2() <= NEAR || ph.t1() > s.maxDist) return false;
                    int i = ph.enterEdge(), j = (i + 1) % s.xs.length;
                    double ex = s.xs[j] - s.xs[i], ey = s.ys[j] - s.ys[i], len = Math.hypot(ex, ey);
                    double nx = -ey / len, ny = ex / len;
                    if (nx * rx + ny * ry > 0) { nx = -nx; ny = -ny; }
                    return set(into, s, ph.t1(), ph.t2(), ph.t1() <= NEAR, nx, ny, ph.enterU() * len, i);
                }
                default -> {
                    return false;
                }
            }
        }

        private boolean set(Hit h, Shape s, double t1, double t2, boolean inside,
                            double nx, double ny, double u, int face) {
            h.s = s;
            h.t1 = t1;
            h.t2 = t2;
            h.inside = inside;
            h.nx = nx;
            h.ny = ny;
            h.u = u;
            h.face = face;
            return true;
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
            Shape s = h.s;
            Region in = storeyAt(s.zLow);
            // Where this ray meets the shape, not the middle of it: a tilted top is a different
            // height at every column, and that is the whole point of it. The same goes for a
            // tilted bottom.
            double slope = s.topSlope(rx, ry), atEye = s.topAt(px, py);
            double bSlope = s.bottomSlope(rx, ry), bEye = s.bottomAt(px, py);
            double top = atEye + slope * h.t1;
            double z0 = bEye + bSlope * h.t1;
            if (s.hTop <= s.zLow) {
                if (tr != null) note(EventKind.SHAPE, h.t1, s.label + " (entirely above the ceiling)", 0);
                return;
            }
            double t1 = h.t1, yTop = rowZ(top, t1), yBot = rowZ(z0, t1);
            double light = in == null ? 1 : in.light;
            if (s.mask >= 0 || (s.amap != null && s.kind == World.Kind.SEG)) {   // leaves and the like: blended later
                record(s, h, t1, yTop, yBot, in);
                return;
            }
            int side = 0;
            if (!h.inside && s.amap == null) {             // a cut-out slab's edge is a few cm of nothing
                double u = h.u;
                Lighting.LightMap lm = baked ? lit.side(s, h.face) : null;
                double sq = square(h.nx, h.ny);
                if (sink != null && (lm == null || lm.gpuIndex >= 0)
                        && ((s.img == null && s.tex == null) || recSide(s) >= 0)) {
                    double c = t1 * dk / F;
                    spanKind = 1;
                    spanU = u;
                    spanW = c;
                    spanSq = sq;
                    spanLight = lambert(h.nx, h.ny) * fog(t1) * light;
                    spanFog = fog(t1);
                    spanLm = lm == null ? -1 : lm.gpuIndex;
                    spanTex = s.img != null || s.tex != null ? recSide(s) : -1;
                    spanMat = s.mat;
                    spanRgb = s.color;
                }
                if (lm != null) {
                    double f = fog(t1);
                    side = paint(yTop, yBot, t1, 0, s.albedoColor, y -> {
                        double z = eye - (y + 0.5 - hz) * t1 * dk / F;
                        lm.sample(u, z, L);
                        return sideColor(s, u, z, t1, sq, f, L);
                    });
                } else {
                    double k = lambert(h.nx, h.ny) * fog(t1) * light;
                    side = paint(yTop, yBot, t1, 0, s.albedoColor, y -> sideColor(s,
                            u, eye - (y + 0.5 - hz) * t1 * dk / F, t1, sq, k, null));
                }
                spanKind = 0;
            }
            int ev = -1;
            int[] rows = null;
            String label = null;
            if (tr != null) {
                label = s.label + (h.inside ? " (eye inside its footprint)" : "");
                rows = new int[] {side, 0, 0};
                ev = tr.events.size();
                note(EventKind.SHAPE, t1, String.format("%s side %d top 0 bottom 0", label, side), side);
            }
            // A plane is seen from above exactly when the eye is above it where the eye stands - it
            // is a plane, so every point of it the ray meets is then met from above.
            if (eye > atEye) {
                Lighting.LightMap lm = baked ? lit.top(s) : null;
                addPlane(s, h, true, atEye, slope, ev, rows, label,
                        flat(atEye, slope, s.topMat, s.color, light, lm, s.topTex, s.topTs, s),
                        s.topMat, light, (s.topTex == null && s.img == null) || recTop(s) >= 0, lm,
                        s.topTex != null || s.img != null ? recTop(s) : -1);
            }
            if (eye < bEye) {
                Lighting.LightMap lm = baked ? lit.bottom(s) : null;
                addPlane(s, h, false, bEye, bSlope, ev, rows, label,
                        flat(bEye, bSlope, s.mat, s.color, 0.45 * light, lm, s.tex, s.ts, s),
                        s.mat, 0.45 * light, (s.tex == null && s.img == null) || recBottom(s) >= 0, lm,
                        s.tex != null || s.img != null ? recBottom(s) : -1);
            }
        }

        private void addPlane(Shape s, Hit h, boolean top, double z, double slope, int ev, int[] rows, String label,
                              IntUnaryOperator color, int mat, double k0, boolean gpu, Lighting.LightMap lm, int tex) {
            if (planeN == planes.size()) planes.add(new Plane());
            Plane p = planes.get(planeN++);
            p.top = top;
            p.inside = h.inside;
            p.masked = s.amap != null;
            p.owner = s;
            p.z = z;
            p.slope = slope;
            p.t1 = Math.max(h.t1, NEAR);
            p.t2 = Math.min(h.t2, MAX_DIST);
            p.base = s.albedoColor;
            p.color = color;
            p.gmat = mat;
            p.grgb = s.color;
            p.gk0 = k0;
            p.glm = lm == null ? -1 : lm.gpuIndex;
            p.gtex = tex;
            p.ggpu = gpu && (lm == null || lm.gpuIndex >= 0);
            p.ev = ev;
            p.which = top ? 1 : 2;
            p.rows = rows;
            p.label = label;
            // Found after the floors had already been drawn past where it starts (a shape culled in
            // an earlier cell, tested again in this one): catch up on that stretch now.
            if (tPrev > p.t1) {
                nc = 0;
                addPiece(p, p.t1, tPrev);
                paintCands(p.t1, tPrev);
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
                int farN = openSpans(next, toN), farNoSky = skySpans;
                double[] flo = openLo.clone(), fhi = openHi.clone();
                for (int a = 0; a < fromN; a++) {
                    Region near = prev[a];
                    // The wall stands on this storey's own boundary, so its normal, the position
                    // along it and its lightmap come from the edge the ray leaves this storey by.
                    // A storey that carries on past t is open on both sides: no wall of its own here.
                    PolyHit ph = Geometry.rayPoly(px, py, rx, ry, near.xs, near.ys);
                    if (ph == null || Math.abs(ph.t2() - t) > 1e-7 * (1 + t)) continue;
                    int e = ph.exitEdge(), e2 = (e + 1) % near.xs.length;
                    double ex = near.xs[e2] - near.xs[e], ey = near.ys[e2] - near.ys[e], len = Math.hypot(ex, ey);
                    double nx = -ey / len, ny = ex / len;
                    if (nx * rx + ny * ry > 0) { nx = -nx; ny = -ny; }
                    double u = ph.exitU() * len, lam = lambert(nx, ny) * fog(t), sq = square(nx, ny);
                    Lighting.LightMap em = baked ? lit.edge(near, e) : null;

                    double z = near.floor, zEnd = near.ceil;
                    int spans = near.sky ? farN : farNoSky;      // only open sky sees sky
                    for (int b = 0; b < spans && z < zEnd; b++) {
                        if (fhi[b] <= z) continue;
                        if (flo[b] >= zEnd) break;
                        if (flo[b] > z) rows += wallBand(z, Math.min(flo[b], zEnd), t, u, lam, sq, nearestFar(toN, z), em);
                        z = Math.max(z, fhi[b]);
                    }
                    if (z < zEnd) rows += wallBand(z, zEnd, t, u, lam, sq, next[toN - 1], em);
                }
            }

            if (tr != null && !same(prev, fromN, next, toN))
                note(EventKind.PORTAL, t, name(prev, fromN) + " -> " + name(next, toN)
                        + (rows > 0 ? " wall " + rows : ""), rows);

            stackN = toN;
            System.arraycopy(next, 0, stack, 0, toN);
            nextCross(t);
        }

        /** How wide one pixel is, in metres, on a surface square to the eye at distance t. */
        private double pixelSize(double t) { return t * dk / F; }

        /** How much a surface with this normal is turned away from the ray: 1 face on, towards 0
         *  edge on. What one pixel covers along such a surface is its square-on size over this. */
        private double square(double nx, double ny) {
            return Math.max(0.02, Math.abs(rx * nx + ry * ny) / Math.hypot(rx, ry));
        }

        /**
         * Anisotropic filtering.
         *
         * One pixel of a surface seen edge-on covers a long thin strip of it - a corridor wall, a
         * floor running to the horizon - and the two sides of that strip can differ by a hundred
         * times. Filtering both to the length of the long side, which is all a single texel size can
         * say, is what smears a distant floor into mush; so sample along the strip instead, as many
         * times as it is long, each sample filtered to the width of the narrow side. That is what a
         * graphics card's 16x anisotropic filtering does, and why a corridor in a modern game stays
         * sharp into the distance. Past ANISO samples the rest goes back to being blurred away.
         */
        private static final int ANISO = 8;

        /** A vertical face: the strip runs along the surface, and the whole column shares its u. */
        private double sideTex(int mat, double u, double z, double t, double square) {
            double narrow = pixelSize(t), along = narrow / square;
            int n = (int) Math.min(ANISO, Math.ceil(along / narrow));
            if (n <= 1) return Materials.sideFaded(mat, narrow) ? Materials.sideMean(mat) : Materials.side(mat, u, z, narrow);
            double step = along / n, w = Math.max(narrow, step), sum = 0;
            if (Materials.sideFaded(mat, w)) {
                // Every detail has faded at this width: each sample would be this same number. Summed
                // and divided the same way, so the result is the same to the last bit.
                double c = Materials.sideMean(mat);
                for (int i = 0; i < n; i++) sum += c;
                return sum / n;
            }
            for (int i = 0; i < n; i++) sum += Materials.side(mat, u + (i + 0.5 - n / 2.0) * step, z, w);
            return sum / n;
        }

        /** A floor or a ceiling: the strip runs away from the eye, so walk it in distance. The rows
         *  near the horizon are the long ones - the strip is F / (y - horizon) times its own width. */
        private double flatTex(int mat, double t, int y) {
            double narrow = pixelSize(t), d = Math.abs(y + 0.5 - hz);
            double along = d < 1e-6 ? Double.MAX_VALUE : t * dk / d;
            int n = (int) Math.min(ANISO, Math.ceil(along / narrow));
            double w = Math.max(narrow, along / n);
            if (Materials.flatFaded(mat, w)) {                  // as sideTex: one number, summed the same way
                double c = Materials.flatMean(mat);
                if (n <= 1) return c;
                double sum = 0;
                for (int i = 0; i < n; i++) sum += c;
                return sum / n;
            }
            if (n <= 1) return Materials.flat(mat, px + rx * t, py + ry * t, w);
            double step = t / (d * n), sum = 0;                   // one sample's worth of distance
            for (int i = 0; i < n; i++) {
                double tt = t + (i + 0.5 - n / 2.0) * step;
                sum += Materials.flat(mat, px + rx * tt, py + ry * tt, w);
            }
            return sum / n;
        }

        private int sideColor(Shape s, double u, double z, double t, double square, double k, float[] light) {
            if (s.img != null) {
                sideImg(s, u, z, t, square);
                if (albedo != null) textured(s.color);
                return shade(s.color, T, k, light);
            }
            if (s.tex == null) {
                double factor = sideTex(s.mat, u, z, t, square) * k;
                return light == null ? shade(s.color, factor) : shadeL(s.color, factor, light);
            }
            sideTex(s.tex, s.ts, u, z, t, square);
            if (albedo != null) textured(s.color);
            return shade(s.color, T, k, light);
        }

        // The albedo pass wants what a surface is - its texture included - with no light on it.
        // Only the image-textured paths set this; everything else keeps its flat map colour there,
        // as it always did, so a map without textures writes exactly the albedo it wrote before.
        private int texAlbedo;
        private boolean texAlbedoSet;

        private void textured(int c) {
            texAlbedo = raw(((c >> 16) & 255) * T[0], ((c >> 8) & 255) * T[1], (c & 255) * T[2]);
            texAlbedoSet = true;
        }

        /**
         * The side of a shape carrying its mesh's coordinates. A wall is mapped by (distance along
         * it, height), so the strip of samples runs along u exactly as sideTex's does. The edge of a
         * slab cut from a mesh is a few centimetres tall and mapped by where it is on the plan, so it
         * takes one sample there.
         */
        private void sideImg(Shape s, double u, double z, double t, double square) {
            double narrow = pixelSize(t), along = narrow / square;
            if (s.kind != World.Kind.SEG) {
                mappedSample(s,px + rx * t, py + ry * t, along, T);
                return;
            }
            int n = (int) Math.min(ANISO, Math.ceil(along / narrow));
            if (n <= 1) {
                mappedSample(s,u, z, narrow, T);
                return;
            }
            double step = along / n, w = Math.max(narrow, step), r = 0, g = 0, b = 0;
            for (int i = 0; i < n; i++) {
                mappedSample(s,u + (i + 0.5 - n / 2.0) * step, z, w, T);
                r += T[0]; g += T[1]; b += T[2];
            }
            T[0] = r / n; T[1] = g / n; T[2] = b / n;
        }

        /** One sample of a shape's own texture at (p, q), into out: its image, or for a blend
         *  material its two layers mixed by the vertex alpha and height there (Materials.blend). */
        private void mappedSample(Shape s, double p, double q, double w, double[] out) {
            // The mip level depends only on the shape and the footprint, and a strip of
            // anisotropic samples repeats both: two logarithms per texture, once per strip.
            if (s != lodShape || w != lodW) {
                lodShape = s;
                lodW = w;
                lodImg = Materials.mappedLod(s.img, s.uv, w);
                if (s.imgB != null) {
                    lodH = Materials.mappedLod(s.hmap, s.uv, w);
                    lodB = Materials.mappedLod(s.imgB, s.uv, w);
                }
            }
            Materials.mappedAt(s.img, s.uv, p, q, lodImg, out);
            if (s.imgB != null) {
                double r = out[0], g = out[1], b = out[2];
                double h = Materials.mappedAt0(s.hmap, s.uv, p, q, lodH);
                double k = Materials.blend(h, s.va[0] * p + s.va[1] * q + s.va[2], s.vb, s.inv);
                if (k <= 0) {
                    out[0] = r; out[1] = g; out[2] = b;
                } else {
                    Materials.mappedAt(s.imgB, s.uv, p, q, lodB, out);
                    out[0] = r + (out[0] - r) * k;
                    out[1] = g + (out[1] - g) * k;
                    out[2] = b + (out[2] - b) * k;
                }
            }
            if (s.vc != null) {
                // The vertex colour multiplies the albedo in linear light; the image is sRGB.
                for (int c = 0; c < 3; c++) {
                    double v = Math.max(0, s.vc[3 * c] * p + s.vc[3 * c + 1] * q + s.vc[3 * c + 2]);
                    out[c] = Math.pow(Math.pow(out[c], 2.2) * v, 1 / 2.2);
                }
            }
        }

        /** flatTex for a shape carrying its mesh's coordinates: the same strip of samples along the
         *  ray, each placed in the image by the shape's uv instead of by world position over ts. */
        private void flatImg(Shape s, double t, int y) {
            double narrow = pixelSize(t), d = Math.abs(y + 0.5 - hz);
            double along = d < 1e-6 ? Double.MAX_VALUE : t * dk / d;
            int n = (int) Math.min(ANISO, Math.ceil(along / narrow));
            double w = Math.max(narrow, along / n);
            if (n <= 1) {
                mappedSample(s,px + rx * t, py + ry * t, w, T);
                return;
            }
            double step = t / (d * n), r = 0, g = 0, b = 0;
            for (int i = 0; i < n; i++) {
                double tt = t + (i + 0.5 - n / 2.0) * step;
                mappedSample(s,px + rx * tt, py + ry * tt, w, T);
                r += T[0]; g += T[1]; b += T[2];
            }
            T[0] = r / n; T[1] = g / n; T[2] = b / n;
        }

        /** Image samples follow exactly the scalar path's strip, footprint and sample count.
         *  Average RGB factors before lighting and tone mapping, without per-pixel allocations. */
        private void sideTex(Materials.Texture tex, double ts, double u, double z, double t, double square) {
            double narrow = pixelSize(t), along = narrow / square;
            int n = (int) Math.min(ANISO, Math.ceil(along / narrow));
            if (n <= 1) {
                Materials.side(tex, ts, u, z, narrow, T);
                return;
            }
            double step = along / n, w = Math.max(narrow, step), r = 0, g = 0, b = 0;
            for (int i = 0; i < n; i++) {
                Materials.side(tex, ts, u + (i + 0.5 - n / 2.0) * step, z, w, T);
                r += T[0]; g += T[1]; b += T[2];
            }
            T[0] = r / n; T[1] = g / n; T[2] = b / n;
        }

        private void flatTex(Materials.Texture tex, double ts, double t, int y) {
            double narrow = pixelSize(t), d = Math.abs(y + 0.5 - hz);
            double along = d < 1e-6 ? Double.MAX_VALUE : t * dk / d;
            int n = (int) Math.min(ANISO, Math.ceil(along / narrow));
            double w = Math.max(narrow, along / n);
            if (n <= 1) {
                Materials.flat(tex, ts, px + rx * t, py + ry * t, w, T);
                return;
            }
            double step = t / (d * n), r = 0, g = 0, b = 0;
            for (int i = 0; i < n; i++) {
                double tt = t + (i + 0.5 - n / 2.0) * step;
                Materials.flat(tex, ts, px + rx * tt, py + ry * tt, w, T);
                r += T[0]; g += T[1]; b += T[2];
            }
            T[0] = r / n; T[1] = g / n; T[2] = b / n;
        }

        /** One solid band of the far side, seen through an opening on the near side. */
        private int wallBand(double zLo, double zHi, double t, double u, double lam, double sq,
                             Region skin, Lighting.LightMap em) {
            if (!(zHi > zLo) || skin == null) return 0;
            IntUnaryOperator wall;
            if (em != null) {
                double f = fog(t);
                wall = y -> {
                    double z = eye - (y + 0.5 - hz) * t * dk / F;
                    em.sample(u, z, L);
                    return shadeL(skin.wallColor, sideTex(skin.wallMat, u, z, t, sq) * f, L);
                };
            } else {
                double k = lam * skin.light;
                wall = y -> shade(skin.wallColor,
                        sideTex(skin.wallMat, u, eye - (y + 0.5 - hz) * t * dk / F, t, sq) * k);
            }
            if (sink != null && (em == null || em.gpuIndex >= 0)) {
                double c = t * dk / F;
                spanKind = 1;
                spanU = u;
                spanW = c;                              // pixelSize(t): how wide a pixel is here
                spanSq = sq;
                spanLight = lam * skin.light;           // the flat model's scalar; fog alone when lit
                spanFog = fog(t);
                spanLm = em == null ? -1 : em.gpuIndex;
                spanTex = -1;
                spanMat = skin.wallMat;
                spanRgb = skin.wallColor;
            }
            int rows = paint(rowZ(zHi, t), rowZ(zLo, t), t, 0, skin.wallColor, wall);
            spanKind = 0;
            return rows;
        }

        /**
         * Has the CPU any reason to work out this pixel of a masked surface?
         *
         * None, when the card was given the surface, nothing unported is blended over the same
         * row, and no screenshot wants the albedo and the depth. That covers every tree in
         * school, and it is worth more than the spans are: a mask costs two noise lookups and a
         * filtered texture sample a row, and the CPU was paying for all of them twice.
         */
        private boolean cpuWants(Masked m, int y) {
            return !(sink != null && !under && m.gpu && !cpuUnder[y] && depth == null);
        }

        private int recSide(Shape s) { return mats == null ? -1 : mats.side(s); }

        private int recTop(Shape s) { return mats == null ? -1 : mats.top(s); }

        private int recBottom(Shape s) { return mats == null ? -1 : mats.bottom(s); }

        private int recAlpha(Shape s) { return mats == null ? -1 : mats.alpha(s); }

        /** Note the rows a masked surface could cover, clipped to the ones still open. */
        private void record(Shape s, Hit h, double t, double yTop, double yBot, Region in) {
            int ia = clampRow(yTop), ib = clampRow(yBot);
            if (ib <= ia) return;
            double span = Math.max(s.len, s.h - s.z0);
            int rows = 0;
            for (int k = 0; k < open; k++) {
                int y0 = Math.max(o0[k], ia), y1 = Math.min(o1[k], ib);
                if (y1 <= y0) continue;
                if (maskedN == masked.size()) masked.add(new Masked());
                Masked m = masked.get(maskedN++);
                m.s = s;
                m.t = t;
                m.u = h.u;
                m.sq = square(h.nx, h.ny);
                m.f = fog(t) * (in == null ? 1 : in.light);
                m.w = pixelSize(t) / span;                    // a pixel, in the mask's own units
                m.lm = baked ? lit.side(s, h.face) : null;
                m.y0 = y0;
                m.y1 = y1;
                m.plane = false;
                boolean cut = s.amap != null;             // a slab sawn out of a mesh, not a tree
                m.gpu = maskOut != null
                        && (cut ? recAlpha(s) >= 0 : s.mask >= 0)
                        && ((s.img == null && s.tex == null) || recSide(s) >= 0)
                        && (m.lm == null || m.lm.gpuIndex >= 0)
                        && maskOut.addSide(x, y0, y1, cut ? -1 : s.mask, h.u, t, m.w, m.sq, m.f,
                                m.lm == null ? -1 : m.lm.gpuIndex, s.color, s.mat,
                                1 / s.len, 1 / (s.h - s.z0), s.z0,
                                s.img != null || s.tex != null ? recSide(s) : -1, recAlpha(s));
                if (sink != null && !m.gpu)
                    for (int y = y0; y < y1; y++) cpuUnder[y] = true;
                rows += y1 - y0;
            }
            if (tr != null) note(EventKind.SHAPE, t, s.label + " (masked, " + rows + " rows blended)", 0);
        }

        /**
         * Blend the masked surfaces over the finished picture, furthest first - they were recorded
         * as the ray met them, so that is this list backwards. Each row asks the mask how much of
         * the surface is really there and mixes that much of it in, which is what lets you see the
         * sky through a tree.
         */
        private void blendMasked() {
            for (int i = maskedN - 1; i >= 0; i--) {
                Masked m = masked.get(i);
                Shape s = m.s;
                if (m.plane) {
                    blendPlane(m);
                    continue;
                }
                if (s.amap != null) {
                    blendCutSide(m);
                    continue;
                }
                double invU = 1 / s.len, invV = 1 / (s.h - s.z0);
                for (int y = m.y0; y < m.y1; y++) {
                    if (!cpuWants(m, y)) continue;
                    double z = eye - (y + 0.5 - hz) * m.t * dk / F;
                    double a = Materials.mask(s.mask, m.u * invU, (z - s.z0) * invV, m.w);
                    if (a <= 0.004) continue;
                    int c;
                    if (m.lm != null) {
                        m.lm.sample(m.u, z, L);
                        c = sideColor(s, m.u, z, m.t, m.sq, m.f, L);
                    } else {
                        c = sideColor(s, m.u, z, m.t, m.sq, m.f, null);
                    }
                    if (sink != null && !m.gpu) sink.skip(x, y);
                    int p = y * W + x;
                    pixels[p] = a >= 0.996 ? c : mix(pixels[p], c, a);
                    // A blended pixel has several surfaces; keep the nearest one that contributes.
                    if (depth != null) {
                        depth[p] = (float) m.t;
                        albedo[p] = s.albedoColor;              // the same nearest surface, without blending its colour
                    }
                }
            }
        }

        /** Note the rows of cut-out plane candidate i that are open and where it is nearer than every
         *  solid candidate, as masked entries. */
        private void recordPlane(int i) {
            Cand c = cands.get(i);
            for (int k = 0; k < open; k++) {
                int y0 = Math.max(o0[k], c.ia), y1 = Math.min(o1[k], c.ib), run = -1;
                for (int y = y0; y <= y1; y++) {
                    boolean win = y < y1 && nearest(i, y);
                    if (win && run < 0) {
                        run = y;
                    } else if (!win && run >= 0) {
                        if (maskedN == masked.size()) masked.add(new Masked());
                        Masked m = masked.get(maskedN++);
                        m.s = c.plane.owner;
                        m.plane = true;
                        m.z = c.z;
                        m.slope = c.slope;
                        m.color = c.color;
                        m.y0 = run;
                        m.y1 = y;
                        m.gpu = maskOut != null && c.ggpu && recAlpha(m.s) >= 0
                                && maskOut.addPlane(x, run, y, c.z, c.slope, c.gk0, c.glm,
                                        c.grgb, c.gmat, c.gtex, recAlpha(m.s));
                        if (sink != null && !m.gpu)
                            for (int yy = run; yy < y; yy++) cpuUnder[yy] = true;
                        run = -1;
                    }
                }
            }
        }

        /** How much of a cut-out is there at (p, q) in its uv: its alpha image, 0 to 1. */
        private double alphaAt(Shape s, double p, double q, double w) {
            Materials.mapped(s.amap, s.uv, p, q, w, A);
            return A[0];
        }

        /** Write one blended pixel of a cut-out, and its albedo and depth for a screenshot.
         *  gpu says the card was given this surface too, so the pixel is not marked as one the
         *  CPU kept - it is one the two are expected to agree on. */
        private void blendPixel(int y, int c, double a, double t, Shape s, boolean gpu) {
            if (sink != null && !gpu) sink.skip(x, y);
            int p = y * W + x;
            pixels[p] = a >= 0.996 ? c : mix(pixels[p], c, a);
            if (depth != null) {
                int alb = texAlbedoSet ? texAlbedo : s.albedoColor;
                albedo[p] = a >= 0.996 ? alb : mix(albedo[p], alb, a);
                if (a >= 0.5) depth[p] = (float) t;
            }
        }

        /** A cut-out's top or bottom over the finished column: per row its distance on the plane,
         *  its alpha there, and the plane's own colour. */
        private void blendPlane(Masked m) {
            Shape s = m.s;
            double sF = m.slope * F;
            for (int y = m.y0; y < m.y1; y++) {
                if (!cpuWants(m, y)) continue;
                double t = (eye - m.z) * F / ((y + 0.5 - hz) * dk + sF);
                if (!(t > 0) || t > MAX_DIST) continue;
                double a = alphaAt(s, px + rx * t, py + ry * t, pixelSize(t));
                if (a <= 0.004) continue;
                texAlbedoSet = false;
                blendPixel(y, m.color.applyAsInt(y), a, t, s, m.gpu);
            }
        }

        /** A cut-out wall over the finished column: its alpha at (distance along it, height). */
        private void blendCutSide(Masked m) {
            Shape s = m.s;
            for (int y = m.y0; y < m.y1; y++) {
                if (!cpuWants(m, y)) continue;
                double z = eye - (y + 0.5 - hz) * m.t * dk / F;
                double a = alphaAt(s, m.u, z, pixelSize(m.t) / m.sq);
                if (a <= 0.004) continue;
                texAlbedoSet = false;
                int c;
                if (m.lm != null) {
                    m.lm.sample(m.u, z, L);
                    c = sideColor(s, m.u, z, m.t, m.sq, m.f, L);
                } else {
                    c = sideColor(s, m.u, z, m.t, m.sq, m.f, null);
                }
                blendPixel(y, c, a, m.t, s, m.gpu);
            }
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
         * Every floor and ceiling in the stack, and every shape top and bottom the ray is over,
         * between distances ta and tb.
         *
         * Segments are handled near-to-far and paint() only fills rows that are still empty, so
         * anything that starts beyond tb - a shape's side, a region boundary - cannot be hidden by
         * what is painted here. Within the segment several of these surfaces can want the same row
         * (two storeys' floors, a platform and the top of the crate on it); that row goes to the one
         * the ray through it meets first.
         */
        private void surfaces(double ta, double tb) {
            if (tb <= ta) return;
            nc = 0;
            for (int i = stackN - 1; i >= 0; i--) {
                Region r = stack[i];
                if (eye <= r.floor) continue;
                Lighting.LightMap lm = baked ? lit.floor(r) : null;
                describe(cand(rowZ(r.floor, tb), rowZ(r.floor, ta), r.floor, 0, r.floorColor,
                        flat(r.floor, r.floorMat, r.floorColor, r.light, lm), null, r, EventKind.FLOOR),
                        r.floorMat, r.floorColor, r.light, true, lm, -1);
            }
            for (int i = 0; i < stackN; i++) {
                Region r = stack[i];
                if (r.sky || eye >= r.ceil) continue;
                Lighting.LightMap lm = baked ? lit.ceil(r) : null;
                describe(cand(rowZ(r.ceil, ta), rowZ(r.ceil, tb), r.ceil, 0, r.ceilColor,
                        flat(r.ceil, r.ceilMat, r.ceilColor, r.light, lm), null, r, EventKind.CEILING),
                        r.ceilMat, r.ceilColor, r.light, true, lm, -1);
            }
            for (int i = 0; i < planeN; i++) addPiece(planes.get(i), ta, tb);
            paintCands(ta, tb);
            for (int i = 0; i < planeN; ) {                      // retire the planes painted to their far end
                Plane p = planes.get(i);
                if (p.t2 <= tb) {
                    planes.set(i, planes.get(--planeN));
                    planes.set(planeN, p);
                } else {
                    i++;
                }
            }
        }

        /** The stretch ta..tb of a plane, as a candidate. Its row is monotonic in distance, so the
         *  rows of a stretch lie between the rows of its two ends. */
        private void addPiece(Plane p, double ta, double tb) {
            double a = Math.max(ta, p.t1), b = Math.min(tb, p.t2);
            if (!(b > a)) return;
            double ra = rowZ(p.z + p.slope * a, a), rb = rowZ(p.z + p.slope * b, b);
            double lo = Math.min(ra, rb), hi = Math.max(ra, rb);
            if (p.inside && a <= p.t1) {                         // standing over it: it runs off the screen
                if (p.top) hi = H;
                else lo = 0;
            }
            Cand c = cand(lo, hi, p.z, p.slope, p.base, p.color, p, null, EventKind.SHAPE);
            c.gmat = p.gmat;
            c.grgb = p.grgb;
            c.gk0 = p.gk0;
            c.glm = p.glm;
            c.gtex = p.gtex;
            c.ggpu = p.ggpu;
        }

        /** What the candidate's colour operator is made of, for the GPU path to rebuild in a
         *  shader. Only the procedural, unlit path is on the card; the rest says so with ggpu. */
        private void describe(Cand c, int mat, int rgb, double k0, boolean gpu, Lighting.LightMap lm, int tex) {
            c.gmat = mat;
            c.grgb = rgb;
            c.gk0 = k0;
            c.gtex = tex;
            c.glm = lm == null ? -1 : lm.gpuIndex;
            c.ggpu = gpu && c.glm >= (lm == null ? -1 : 0);
        }

        private Cand cand(double lo, double hi, double z, double slope, int base, IntUnaryOperator color,
                          Plane plane, Region region, EventKind kind) {
            if (nc == cands.size()) cands.add(new Cand());
            Cand c = cands.get(nc++);
            c.lo = lo;
            c.hi = hi;
            c.z = z;
            c.slope = slope;
            c.base = base;
            c.color = color;
            c.plane = plane;
            c.region = region;
            c.kind = kind;
            c.ggpu = false;
            return c;
        }

        private void paintCands(double ta, double tb) {
            for (int i = 0; i < nc; i++) {
                Cand c = cands.get(i);
                c.ia = clampRow(c.lo);
                c.ib = clampRow(c.hi);
            }
            // A cut-out's plane first: the rows where it is nearer than every solid surface in this
            // stretch are noted, not taken, so what is behind still paints them and the cut-out is
            // blended over that afterwards. Rows already closed were closed by something nearer.
            for (int i = 0; i < nc; i++) {
                Cand c = cands.get(i);
                if (c.plane != null && c.plane.masked && c.ib > c.ia) recordPlane(i);
            }
            for (int i = 0; i < nc; i++) {
                Cand c = cands.get(i);
                if (c.ib <= c.ia || (c.plane != null && c.plane.masked)) continue;
                boolean alone = true;
                for (int j = 0; j < nc && alone; j++) {
                    Cand d = cands.get(j);
                    if (j != i && d.ia < c.ib && d.ib > c.ia && !(d.plane != null && d.plane.masked)) alone = false;
                }
                int rows = 0;
                if (sink != null && c.ggpu) {
                    spanKind = 2;
                    spanZ = c.z;
                    spanSlope = c.slope;
                    spanLight = c.gk0;
                    spanLm = c.glm;
                    spanTex = c.gtex;
                    spanMat = c.gmat;
                    spanRgb = c.grgb;
                }
                if (alone) {
                    rows = paint(c.ia, c.ib, 0, c.z, c.slope, c.base, c.color);
                } else {
                    int run = -1;
                    for (int y = c.ia; y <= c.ib; y++) {
                        boolean win = y < c.ib && nearest(i, y);
                        if (win && run < 0) {
                            run = y;
                        } else if (!win && run >= 0) {
                            rows += paint(run, y, 0, c.z, c.slope, c.base, c.color);
                            run = -1;
                        }
                    }
                }
                spanKind = 0;
                if (tr != null && rows > 0) {
                    if (c.plane != null) notePlane(c.plane, rows);
                    else noteSurface(c.kind, c.region, ta, tb, rows);
                }
            }
        }

        /** Whether candidate i is the first thing the ray through row y meets, of those wanting it. */
        private boolean nearest(int i, int y) {
            double di = depthAt(cands.get(i), y);
            for (int j = 0; j < nc; j++) {
                if (j == i) continue;
                Cand d = cands.get(j);
                if (y < d.ia || y >= d.ib || (d.plane != null && d.plane.masked)) continue;   // a cut-out hides nothing
                double dj = depthAt(d, y);
                if (dj < di || (dj == di && j < i)) return false;
            }
            return true;
        }

        /** Where the ray through row y meets a candidate's plane - the formula flat() uses. */
        private double depthAt(Cand c, int y) {
            double d = (eye - c.z) * F / ((y + 0.5 - hz) * dk + c.slope * F);
            return d > 0 && Double.isFinite(d) ? d : Double.POSITIVE_INFINITY;
        }

        private void notePlane(Plane p, int rows) {
            p.rows[p.which] += rows;
            TraceEvent e = tr.events.get(p.ev);
            tr.events.set(p.ev, new TraceEvent(EventKind.SHAPE, e.t(),
                    String.format("%s side %d top %d bottom %d", p.label, p.rows[0], p.rows[1], p.rows[2]),
                    p.rows[0] + p.rows[1] + p.rows[2], e.x(), e.y()));
        }

        /** Floors are now painted a cell at a time, so merge each run of the same floor (or
         *  ceiling) back into one line of the ray view's list instead of one per grid cell. */
        private void noteSurface(EventKind kind, Region r, double ta, double tb, int rows) {
            String prefix = r.name + "  t ";
            for (int k = tr.events.size() - 1; k >= 0; k--) {
                TraceEvent e = tr.events.get(k);
                if (e.kind().numbered()) break;                   // a shape or portal in between: start a new line
                if (e.kind() == kind && e.label().startsWith(prefix)) {
                    tr.events.set(k, new TraceEvent(kind, e.t(),
                            String.format("%s%.2f-%.2f", prefix, e.t(), Math.min(tb, MAX_DIST)), e.rows() + rows, e.x(), e.y()));
                    return;
                }
            }
            note(kind, ta, String.format("%s%.2f-%.2f", prefix, ta, Math.min(tb, MAX_DIST)), rows);
        }

        /** Horizontal surfaces: invert the projection to get the distance for a row,
         *  then look up where that lands on the map. */
        private IntUnaryOperator flat(double z, int mat, int color, double k0, Lighting.LightMap lm) {
            return flat(z, 0, mat, color, k0, lm, null, 1, null);
        }

        /**
         * A plane. z is its height where the eye stands and slope how fast it climbs along this
         * ray, so a floor or a flat top passes slope 0 and nothing about it changes.
         *
         * The row a plane fills is where the ray's height meets the plane's. The ray is at
         * eye - C*t with C = (row - horizon) * dk / F; the plane is at z + slope*t; so
         * t = (eye - z) / (C + slope), which is the old t = (eye - z) / C with one term added.
         */
        private IntUnaryOperator flat(double z, double slope, int mat, int color, double k0,
                                      Lighting.LightMap lm, Materials.Texture tex, double ts, Shape owner) {
            boolean mapped = owner != null && owner.img != null;        // the mesh's own texture wins
            double sF = slope * F;
            if (lm == null) {
                return y -> {
                    double t = (eye - z) * F / ((y + 0.5 - hz) * dk + sF);
                    if (!(t > 0) || t > MAX_DIST) return shade(color, 0.3 * k0);
                    if (mapped) {
                        flatImg(owner, t, y);
                        if (albedo != null) textured(color);
                        return shade(color, T, k0 * fog(t), null);
                    }
                    if (tex != null) {
                        flatTex(tex, ts, t, y);
                        if (albedo != null) textured(color);
                        return shade(color, T, k0 * fog(t), null);
                    }
                    return shade(color, flatTex(mat, t, y) * k0 * fog(t));
                };
            }
            return y -> {
                double t = (eye - z) * F / ((y + 0.5 - hz) * dk + sF);
                if (!(t > 0) || t > MAX_DIST) return shade(color, 0.3 * k0);
                double wx = px + rx * t, wy = py + ry * t, f = fog(t);
                if (Materials.emissive(mat, wx, wy)) return shade(EMISSIVE, f);   // a light panel is its own light
                lm.sample(wx, wy, L);
                if (mapped) {
                    flatImg(owner, t, y);
                    if (albedo != null) textured(color);
                    return shade(color, T, f, L);
                }
                if (tex != null) {
                    flatTex(tex, ts, t, y);
                    if (albedo != null) textured(color);
                    return shade(color, T, f, L);
                }
                return shadeL(color, flatTex(mat, t, y) * f, L);
            };
        }

        /** Whatever rows are left: sky above the horizon, distant haze below it. The card draws
         *  exactly these rows itself (no span reaches them), so they are the CPU's only when a
         *  screenshot wants them or something unported will be blended over them. */
        private void fillRest() {
            if (sink == null || under || albedo != null)
                for (int k = 0; k < open; k++)
                    for (int y = o0[k]; y < o1[k]; y++) pixels[y * W + x] = y < hz ? sky(y) : 0x3a3c40;
            else
                for (int k = 0; k < open; k++)
                    for (int y = o0[k]; y < o1[k]; y++)
                        if (cpuUnder[y]) pixels[y * W + x] = y < hz ? sky(y) : 0x3a3c40;
            if (albedo != null)
                for (int k = 0; k < open; k++)
                    for (int y = o0[k]; y < o1[k]; y++) albedo[y * W + x] = pixels[y * W + x];
            open = 0;
        }

        private int sky(int y) {
            double s = Math.max(0, Math.min(1, (hz - y) / (viewH * 0.9)));
            return rgb(205 - 125 * s, 222 - 87 * s, 238 - 28 * s);
        }

        private void note(EventKind kind, double t, String label, int rows) {
            tr.events.add(new TraceEvent(kind, t, label, rows, px + rx * t, py + ry * t));
        }

        // ---- Interval list: only ever fill rows that are still empty ----

        /** Returns how many rows were actually filled. Depth is constant t on a side; t = 0
         *  means a horizontal face at z, whose distance comes from the row instead. */
        private int paint(double a, double b, double t, double z, int baseColor, IntUnaryOperator colorOf) {
            return paint(a, b, t, z, 0, baseColor, colorOf);
        }

        /** slope: for a plane (t = 0), how fast it climbs along this ray, z being its height at the
         *  eye - a tilted top or bottom. Its depth then comes from the same formula flat() uses to
         *  find it; the horizontal one put every tilted face at the wrong distance. */
        private int paint(double a, double b, double t, double z, double slope, int baseColor, IntUnaryOperator colorOf) {
            int ia = clampRow(a), ib = clampRow(b);
            if (ib <= ia) return 0;
            int m = 0, filled = 0;
            boolean note = sink != null;                      // the GPU path is watching what is drawn
            for (int k = 0; k < open; k++) {
                int s0 = Math.max(o0[k], ia), s1 = Math.min(o1[k], ib);
                if (s1 <= s0) { n0[m] = o0[k]; n1[m++] = o1[k]; continue; }
                // Record first: whether the card has these rows is what decides whether the CPU
                // has to colour them at all. A screenshot still does, for its albedo and depth.
                boolean onCard = false;
                if (note) {
                    if (spanKind == 1) onCard = sink.add(x, s0, s1, spanU, spanLight, spanW, spanSq,
                            spanMat, spanRgb, spanFog, spanLm, spanTex);
                    else if (spanKind == 2) onCard = sink.addPlane(x, s0, s1, spanZ, spanSlope,
                            spanLight, spanMat, spanRgb, spanLm, spanTex);
                    else for (int y = s0; y < s1; y++) sink.skip(x, y);
                    // Rows the card has not got are the CPU's, and so is anything blended on top
                    // of them: the blend needs something underneath, and this is where it says so.
                    if (!onCard) for (int y = s0; y < s1; y++) cpuUnder[y] = true;
                }
                if (depth == null) {
                    for (int y = s0; y < s1; y++)
                        if (under || !onCard || cpuUnder[y]) pixels[y * W + x] = colorOf.applyAsInt(y);
                } else {
                    for (int y = s0; y < s1; y++) {
                        texAlbedoSet = false;
                        pixels[y * W + x] = colorOf.applyAsInt(y);
                        albedo[y * W + x] = texAlbedoSet ? texAlbedo : baseColor;
                    }
                    if (t > 0) {
                        for (int y = s0; y < s1; y++) depth[y * W + x] = (float) t;
                    } else {
                        for (int y = s0; y < s1; y++) {
                            double d = (eye - z) * F / ((y + 0.5 - hz) * dk + slope * F);
                            depth[y * W + x] = d > 0 && Double.isFinite(d) ? (float) d : 0;
                        }
                    }
                }
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

    /** Off when the map's lighting says "fog": false. The fade to 30% at 45 m stood in for light
     *  falling off before there was a bake; with real lightmaps it only darkens whatever is far away,
     *  lit or not. */
    static boolean fogOn = true;

    private static double fog(double t) {
        return fogOn ? Math.max(0.3, 1 - t / 45) : 1;
    }

    /** src over dst, by a. */
    private static int mix(int dst, int src, double a) {
        int k = (int) (a * 256), j = 256 - k;
        return ((((dst >> 16 & 255) * j + (src >> 16 & 255) * k) >> 8) << 16)
                | ((((dst >> 8 & 255) * j + (src >> 8 & 255) * k) >> 8) << 8)
                | (((dst & 255) * j + (src & 255) * k) >> 8);
    }

    private static int shade(int c, double k) {
        return rgb(((c >> 16) & 255) * k, ((c >> 8) & 255) * k, (c & 255) * k);
    }

    /** Colour c times k, times a coloured light level from a lightmap. */
    private static int shadeL(int c, double k, float[] L) {
        return rgb(((c >> 16) & 255) * k * L[0], ((c >> 8) & 255) * k * L[1], (c & 255) * k * L[2]);
    }

    /** Image texture factors replace only the procedural scalar; lighting and grading are shared. */
    private static int shade(int c, double[] texture, double k, float[] light) {
        double r = ((c >> 16) & 255) * (texture[0] * k);
        double g = ((c >> 8) & 255) * (texture[1] * k);
        double b = (c & 255) * (texture[2] * k);
        return light == null ? rgb(r, g, b) : rgb(r * light[0], g * light[1], b * light[2]);
    }

    private static final int EMISSIVE = 0xfff4e6;          // what a lit ceiling panel looks like

    /**
     * Tone mapping. Bounced light pushes lit surfaces well past full brightness, and cutting them
     * off at 255 turns a sunlit wall into a flat white shape. The curve leaves everything below
     * KNEE exactly as it was - the direct-lit look this engine was tuned against - and rolls the
     * rest off towards 255, so the extra light shows as detail instead of a hole. One byte of
     * table lookup per channel, so it costs nothing per pixel.
     */
    private static final int KNEE = 200, TONE_MAX = 2048;
    private static final int[] TONE = new int[TONE_MAX];

    static {
        for (int v = 0; v < TONE_MAX; v++)
            TONE[v] = v <= KNEE ? v
                    : (int) Math.round(KNEE + (255.0 - KNEE) * (1 - Math.exp(-(v - KNEE) / (255.0 - KNEE))));
    }

    private static int tone(double v) {
        int i = (int) v;
        return TONE[i < 0 ? 0 : Math.min(i, TONE_MAX - 1)];
    }

    /**
     * The grade: saturation, and a lift off black. Valorant's own picture is not what its textures
     * are - measured over ten of Riot's screenshots of Haven it runs at 0.31 saturation with barely
     * a pixel under 25, while the .blend's own albedo is 0.19 and this engine's is 0.20. The extra
     * chroma is colour grading in their renderer, not anything a conversion can fetch out of the
     * file, and the lifted shadows are theirs too. So it is done here, at the end, where they do it.
     */
    static double satBoost = 1.0, lift = 0.0;

    static void grade(double saturation, double blackLift) {
        satBoost = saturation;
        lift = blackLift;
    }

    /** An albedo pixel: the colour as it is, clamped - no grade and no tone curve. */
    private static int raw(double r, double g, double b) {
        return (int) Math.max(0, Math.min(255, r)) << 16 | (int) Math.max(0, Math.min(255, g)) << 8
                | (int) Math.max(0, Math.min(255, b));
    }

    private static int rgb(double r, double g, double b) {
        if (satBoost != 1.0) {
            double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            r = y + (r - y) * satBoost;
            g = y + (g - y) * satBoost;
            b = y + (b - y) * satBoost;
        }
        if (lift != 0) {
            double k = 1 - lift, c = lift * 255;
            r = c + r * k;
            g = c + g * k;
            b = c + b * k;
        }
        return (tone(r) << 16) | (tone(g) << 8) | tone(b);
    }
}

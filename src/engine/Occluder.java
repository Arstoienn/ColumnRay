package engine;

import engine.Geometry.PolyHit;
import engine.Geometry.SegHit;
import engine.Geometry.Span;
import engine.World.Grid;
import engine.World.Kind;
import engine.World.Region;
import engine.World.Shape;
import java.util.Arrays;

/**
 * Line of sight in the 2.5D world: is the straight 3D segment between two points free of floors,
 * ceilings, walls and furniture?
 *
 * It is the renderer's own question asked from somewhere else. The segment is projected onto the
 * map and walked as a 2D ray - region boundaries for the storeys, grid cells for the shapes - and
 * every thing it crosses is checked against the height the segment has at that point. Lighting uses
 * it from each lit point towards each light. One instance per thread.
 */
final class Occluder {
    private final World w;
    private final int[] stamp;
    private final int[] nodes = new int[128];                // tree walk stack, as in Renderer
    private int mark;
    private final Region[] stack = new Region[Renderer.MAX_STOREYS];
    long rays;                               // how many segments this instance has been asked about
    private final double[] exitT = new double[Renderer.MAX_STOREYS];
    private final int[] exitE = new int[Renderer.MAX_STOREYS];

    Occluder(World w) {
        this.w = w;
        this.stamp = new int[w.shapes.length];
    }

    /** Is (x, y, z) in open air: inside a storey, and not inside a slab, a wall or furniture? */
    boolean open(double x, double y, double z) {
        int n = w.regionsAt(x, y, stack);
        if (n > 0 && !inSpan(stack, n, z, z)) return false;
        Grid g = w.grid;
        int cx = (int) Math.floor((x - g.x0) / g.cell), cy = (int) Math.floor((y - g.y0) / g.cell);
        if (cx < 0 || cy < 0 || cx >= g.nx || cy >= g.ny) return true;
        // Down the tree: a node whose box does not hold the point holds no shape that does. Every
        // lightmap sample asks this, and a cell of mesh triangles had it testing hundreds each time.
        for (World.Group gp : g.groups[cy * g.nx + cx]) {
            int sp = 0;
            nodes[sp++] = 0;
            while (sp > 0) {
                int k = nodes[--sp], o = 7 * k;
                double[] nb = gp.nb;
                // Padded: a tilted shape's bottomAt / topAt can round a hair past its lowest and
                // highest corner, and the node must never be the stricter of the two tests.
                if (x < nb[o] - PAD || y < nb[o + 1] - PAD || x > nb[o + 2] + PAD || y > nb[o + 3] + PAD
                        || z <= nb[o + 4] - PAD || z >= nb[o + 5] + PAD)
                    continue;
                if (gp.right[k] >= 0) {
                    nodes[sp++] = gp.right[k];
                    nodes[sp++] = k + 1;
                    continue;
                }
                for (int m = gp.start[k]; m < gp.end[k]; m++) {
                    Shape s = w.shapes[gp.members[m]];
                    if (z > s.bottomAt(x, y) && z < s.topAt(x, y) && contains(s, x, y)) return false;
                }
            }
        }
        return true;
    }

    /** Is the segment from a to b clear? Storeys first - indoors a ceiling usually settles it
     *  straight away - then the shapes along the way. */
    boolean clear(double ax, double ay, double az, double bx, double by, double bz) {
        rays++;
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        return storeysClear(ax, ay, az, dx, dy, dz) && shapesClear(ax, ay, az, dx, dy, dz);
    }

    /**
     * Walk the region stack along the segment. Between two boundaries the stack does not change,
     * and the segment's height moves in a straight line, so it stays in open air exactly when both
     * ends of that stretch are in the same open span - leaving it means passing through a slab.
     * Off the map is open air.
     */
    private boolean storeysClear(double ax, double ay, double az, double dx, double dy, double dz) {
        double len = Math.hypot(dx, dy);
        int n = w.regionsAt(ax, ay, stack);
        double s = 0;
        for (int guard = 0; guard < 256 && n > 0; guard++) {
            double exit = 1;
            if (len > 1e-9) {
                for (int i = 0; i < n; i++) {
                    PolyHit ph = Geometry.rayPoly(ax, ay, dx, dy, stack[i].xs, stack[i].ys);
                    exit = Math.min(exit, ph == null || ph.t2() <= s ? s + 1e-4 / len : ph.t2());
                }
            }
            if (!inSpan(stack, n, az + dz * s, az + dz * Math.min(exit, 1))) return false;
            if (exit >= 1) return true;
            s = exit;
            double on = s + 1e-5 / len;
            n = w.regionsAt(ax + dx * on, ay + dy * on, stack);
        }
        return true;
    }

    /** The shapes in every grid cell the segment's shadow on the map passes through. */
    private boolean shapesClear(double ax, double ay, double az, double dx, double dy, double dz) {
        if (++mark == Integer.MAX_VALUE) { Arrays.fill(stamp, 0); mark = 1; }
        Grid g = w.grid;
        double fx = (ax - g.x0) / g.cell, fy = (ay - g.y0) / g.cell;
        int cx = (int) Math.floor(fx), cy = (int) Math.floor(fy);
        if (Math.abs(dx) < 1e-12 && Math.abs(dy) < 1e-12) {                // straight up or down
            if (cx < 0 || cy < 0 || cx >= g.nx || cy >= g.ny) return true;
            double lo = Math.min(az, az + dz), hi = Math.max(az, az + dz);
            for (int i : g.shapes[cy * g.nx + cx]) {
                Shape s = w.shapes[i];
                if (hi > s.bottomAt(ax, ay) && lo < s.topAt(ax, ay) && contains(s, ax, ay)) return false;
            }
            return true;
        }
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.POSITIVE_INFINITY : g.cell / Math.abs(dx);
        double tdy = dy == 0 ? Double.POSITIVE_INFINITY : g.cell / Math.abs(dy);
        double tx = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? cx + 1 - fx : fx - cx) * tdx;
        double ty = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? cy + 1 - fy : fy - cy) * tdy;
        double enter = 0;
        while (cx >= 0 && cy >= 0 && cx < g.nx && cy < g.ny) {
            // The heights the segment has inside this cell. A shape entirely above or below them
            // cannot block it here - and if it reaches into another cell where the heights do
            // overlap, it is tested there, which is why it is only stamped once really tested.
            // Haven's rafters, pipes and braces are thousands of small slabs overhead, and without
            // this every shadow ray along the ground paid for all of them.
            double exit = Math.min(1, Math.min(tx, ty));
            double zLo = Math.min(az + dz * enter, az + dz * exit) - 1e-6;
            double zHi = Math.max(az + dz * enter, az + dz * exit) + 1e-6;
            // The renderer's tree: a node the segment's heights or its shadow miss holds nothing to test.
            for (World.Group gp : g.groups[cy * g.nx + cx]) {
                int sp = 0;
                nodes[sp++] = 0;
                while (sp > 0) {
                    int k = nodes[--sp], o = 7 * k;
                    if (gp.nb[o + 5] < zLo || gp.nb[o + 4] > zHi || enters(gp.nb, o, ax, ay, dx, dy) > 1) continue;
                    if (gp.right[k] >= 0) {
                        nodes[sp++] = gp.right[k];
                        nodes[sp++] = k + 1;
                        continue;
                    }
                    for (int m = gp.start[k]; m < gp.end[k]; m++) {
                        int i = gp.members[m];
                        if (stamp[i] == mark) continue;
                        Shape s = w.shapes[i];
                        if (s.hTop < zLo || s.zLow > zHi) continue;
                        stamp[i] = mark;
                        if (blocks(s, ax, ay, az, dx, dy, dz)) return false;
                    }
                }
            }
            if (Math.min(tx, ty) >= 1) return true;                            // the segment ends in this cell
            enter = exit;
            if (tx < ty) { tx += tdx; cx += sx; } else { ty += tdy; cy += sy; }
        }
        return true;
    }

    private static final double PAD = 1e-9;

    /** Where the segment's shadow enters a tree node's plan, 0 to 1, or infinity when it misses.
     *  Padded a hair, so a footprint the segment only grazes is never rejected by its node. */
    private static double enters(double[] nb, int o, double ax, double ay, double dx, double dy) {
        double t0 = 0, t1 = 1;
        if (dx != 0) {
            double a = (nb[o] - PAD - ax) / dx, b = (nb[o + 2] + PAD - ax) / dx;
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
        } else if (ax < nb[o] - PAD || ax > nb[o + 2] + PAD) {
            return Double.POSITIVE_INFINITY;
        }
        if (dy != 0) {
            double a = (nb[o + 1] - PAD - ay) / dy, b = (nb[o + 3] + PAD - ay) / dy;
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
        } else if (ay < nb[o + 1] - PAD || ay > nb[o + 3] + PAD) {
            return Double.POSITIVE_INFINITY;
        }
        return t0 <= t1 ? t0 : Double.POSITIVE_INFINITY;
    }

    /** Does the segment pass through the shape: cross its footprint at a height inside [z0, h]? */
    private static boolean blocks(Shape s, double ax, double ay, double az, double dx, double dy, double dz) {
        double s1, s2;
        switch (s.kind) {
            case SEG -> {
                SegHit h = Geometry.raySeg(ax, ay, dx, dy, s.ax, s.ay, s.bx, s.by);
                if (h == null || h.t() < 0 || h.t() > 1) return false;
                if (hole(s, h.u(), az + dz * h.t())) return false;
                s1 = s2 = h.t();
            }
            case CIRCLE -> {
                Span sp = Geometry.rayCircle(ax, ay, dx, dy, s.cx, s.cy, s.r);
                if (sp == null) return false;
                s1 = Math.max(0, sp.t1());
                s2 = Math.min(1, sp.t2());
            }
            default -> {
                PolyHit ph = Geometry.rayPoly(ax, ay, dx, dy, s.xs, s.ys);
                if (ph == null) return false;
                s1 = Math.max(0, ph.t1());
                s2 = Math.min(1, ph.t2());
            }
        }
        if (s2 < s1) return false;
        double za = az + dz * s1, zb = az + dz * s2;
        if (s.hx == 0 && s.hy == 0 && s.zx == 0 && s.zy == 0)
            return Math.max(za, zb) > s.z0 + 1e-6 && Math.min(za, zb) < s.h - 1e-6;
        // Tilted: the segment, the top and the bottom are all straight lines over [s1, s2], so it
        // is inside the slab somewhere exactly when "above the bottom" and "below the top" overlap.
        double b0 = s.bottomAt(ax, ay), bs = s.bottomSlope(dx, dy);
        double t0 = s.topAt(ax, ay), ts = s.topSlope(dx, dy);
        double lo = s1, hi = s2;
        for (int k = 0; k < 2; k++) {
            // above the bottom: (az - b0 - eps) + (dz - bs) s > 0; below the top: (t0 - az - eps) + (ts - dz) s > 0
            double c0 = k == 0 ? az - b0 - 1e-6 : t0 - az - 1e-6, c1 = k == 0 ? dz - bs : ts - dz;
            if (Math.abs(c1) < 1e-12) {
                if (c0 <= 0) return false;
            } else if (c1 > 0) {
                lo = Math.max(lo, -c0 / c1);
            } else {
                hi = Math.min(hi, -c0 / c1);
            }
        }
        return s1 == s2 ? lo <= hi : lo < hi;
    }

    /**
     * Did the ray go through one of the gaps? A crown of leaves stops a ray where there is a leaf
     * and lets it past where there is not, which is the whole of dappled light: the lighting bake
     * fires eight rays from spread-out spots in every texel, so a patch of ground ends up as bright
     * as the fraction of them that found their way through.
     */
    private static boolean hole(Shape s, double u, double z) {
        return s.mask >= 0 && Materials.mask(s.mask, u, (z - s.z0) / (s.h - s.z0), 0) <= 0.5;
    }

    private static boolean contains(Shape s, double x, double y) {
        return switch (s.kind) {
            case SEG -> false;                                                 // a thin wall has no inside
            case CIRCLE -> Math.hypot(x - s.cx, y - s.cy) < s.r;
            case POLY -> Geometry.pointInPoly(x, y, s.xs, s.ys);
        };
    }

    // ---- Nearest hit ----

    /** What a ray ran into, and where along it. {@code what} says which lightmap the surface is. */
    static final class Hit {
        static final int SKY = 0, SOLID = 1, FLOOR = 2, CEIL = 3, EDGE = 4, SIDE = 5, TOP = 6, BOTTOM = 7;
        int what;
        int id;                  // region id for FLOOR / CEIL / EDGE, shape id for SIDE / TOP / BOTTOM
        int face;                // which edge of the region, or which face of the shape
        double t, x, y, z;       // t is 0..1 along the segment
    }

    /**
     * The nearest surface the segment from a to b runs into, or {@link Hit#SKY} if it gets all the
     * way. The same walk as {@link #clear}, only it keeps the closest crossing instead of stopping
     * at the first one and says what was hit; lighting gathers bounced light through it. A hit on
     * something with no lightmap of its own - the top of the roof - comes back as {@link Hit#SOLID}.
     */
    boolean hit(double ax, double ay, double az, double bx, double by, double bz, Hit out) {
        rays++;
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        out.what = Hit.SKY;
        out.t = 1;
        storeysHit(ax, ay, az, dx, dy, dz, out);
        shapesHit(ax, ay, az, dx, dy, dz, out);
        out.x = ax + dx * out.t;
        out.y = ay + dy * out.t;
        out.z = az + dz * out.t;
        return out.what != Hit.SKY;
    }

    private static void keep(Hit out, double t, int what, int id, int face) {
        if (t > out.t || t < 0) return;
        // An exact tie between two shapes goes to the lower id, so the answer does not depend on the
        // order the shapes are visited in.
        if (t == out.t && !(what >= Hit.SIDE && out.what >= Hit.SIDE && id < out.id)) return;
        out.t = t;
        out.what = what;
        out.id = id;
        out.face = face;
    }

    /** Slabs and the walls on region boundaries: the storey walk of {@link #storeysClear}, stopping
     *  at the floor or ceiling the segment leaves through, or at the wall it runs into. */
    private void storeysHit(double ax, double ay, double az, double dx, double dy, double dz, Hit out) {
        double len = Math.hypot(dx, dy), inv = 1 / Math.max(len, 1e-9);
        int n = w.regionsAt(ax, ay, stack);
        double s = 0;
        for (int guard = 0; guard < 256 && n > 0 && s < out.t; guard++) {
            double exit = 1;
            for (int i = 0; i < n; i++) {
                PolyHit ph = len > 1e-9 ? Geometry.rayPoly(ax, ay, dx, dy, stack[i].xs, stack[i].ys) : null;
                exitT[i] = ph == null || ph.t2() <= s ? (len > 1e-9 ? s + 1e-4 * inv : 1) : ph.t2();
                exitE[i] = ph == null ? -1 : ph.exitEdge();
                exit = Math.min(exit, exitT[i]);
            }
            double zs = az + dz * s, end = Math.min(exit, 1);

            int in = -1;
            double roof = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                Region r = stack[i];
                if (in < 0 && zs >= r.floor - 1e-9 && zs <= r.ceil + 1e-9) in = i;
                roof = Math.max(roof, Math.max(r.top, r.ceil));
            }
            // The stack does not change until `exit`, so within this stretch the only way out is
            // through the floor or the ceiling of the span we are in - or the roof, from above.
            double plane = in >= 0 ? (dz > 0 ? stack[in].ceil : stack[in].floor) : (dz < 0 ? roof : Double.NaN);
            if (dz != 0 && !Double.isNaN(plane) && !Double.isInfinite(plane)) {
                double tp = (plane - az) / dz;
                if (tp > s && tp <= end) {
                    if (in < 0) keep(out, tp, Hit.SOLID, -1, 0);           // the top of the roof: no lightmap
                    else keep(out, tp, dz > 0 ? Hit.CEIL : Hit.FLOOR, stack[in].id, 0);
                    return;
                }
            }
            if (exit >= 1) return;

            int rid = in >= 0 ? stack[in].id : -1, e = in >= 0 ? exitE[in] : -1;
            boolean own = in >= 0 && exitT[in] <= exit + 1e-9;             // the span we are in is the one ending
            double zx = az + dz * exit;
            s = exit;
            n = w.regionsAt(ax + dx * (exit + 1e-5 * inv), ay + dy * (exit + 1e-5 * inv), stack);
            if (n == 0) return;                                            // off the map: open air
            if (!inSpan(stack, n, zx, zx)) {
                keep(out, exit, own && e >= 0 ? Hit.EDGE : Hit.SOLID, rid, Math.max(e, 0));
                return;
            }
        }
    }

    /** The same cell walk as {@link #shapesClear}, keeping the nearest shape the segment enters. */
    private void shapesHit(double ax, double ay, double az, double dx, double dy, double dz, Hit out) {
        if (++mark == Integer.MAX_VALUE) { Arrays.fill(stamp, 0); mark = 1; }
        Grid g = w.grid;
        double fx = (ax - g.x0) / g.cell, fy = (ay - g.y0) / g.cell;
        int cx = (int) Math.floor(fx), cy = (int) Math.floor(fy);
        if (Math.abs(dx) < 1e-12 && Math.abs(dy) < 1e-12) {                // straight up or down
            if (cx < 0 || cy < 0 || cx >= g.nx || cy >= g.ny || dz == 0) return;
            for (int i : g.shapes[cy * g.nx + cx]) {
                Shape s = w.shapes[i];
                if (s.kind == Kind.SEG || !contains(s, ax, ay)) continue;
                double bz = s.bottomAt(ax, ay), tz = s.topAt(ax, ay);
                if (dz > 0 ? az < bz : az > tz)
                    keep(out, ((dz > 0 ? bz : tz) - az) / dz, dz > 0 ? Hit.BOTTOM : Hit.TOP, s.id, 0);
            }
            return;
        }
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.POSITIVE_INFINITY : g.cell / Math.abs(dx);
        double tdy = dy == 0 ? Double.POSITIVE_INFINITY : g.cell / Math.abs(dy);
        double tx = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? cx + 1 - fx : fx - cx) * tdx;
        double ty = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? cy + 1 - fy : fy - cy) * tdy;
        double enter = 0;
        while (cx >= 0 && cy >= 0 && cx < g.nx && cy < g.ny && enter <= out.t) {
            double exit = Math.min(1, Math.min(tx, ty));                       // the same height reject as shapesClear
            double zLo = Math.min(az + dz * enter, az + dz * exit) - 1e-6;
            double zHi = Math.max(az + dz * enter, az + dz * exit) + 1e-6;
            for (World.Group gp : g.groups[cy * g.nx + cx]) {
                int sp = 0;
                nodes[sp++] = 0;
                while (sp > 0) {
                    int k = nodes[--sp], o = 7 * k;
                    // Nothing under a node can be hit before the segment reaches its plan, so one
                    // strictly beyond the nearest hit so far is done with.
                    if (gp.nb[o + 5] < zLo || gp.nb[o + 4] > zHi || enters(gp.nb, o, ax, ay, dx, dy) > out.t) continue;
                    if (gp.right[k] >= 0) {
                        nodes[sp++] = gp.right[k];
                        nodes[sp++] = k + 1;
                        continue;
                    }
                    for (int m = gp.start[k]; m < gp.end[k]; m++) {
                        int i = gp.members[m];
                        if (stamp[i] == mark) continue;
                        Shape s = w.shapes[i];
                        if (s.hTop < zLo || s.zLow > zHi) continue;
                        stamp[i] = mark;
                        shapeHit(s, ax, ay, az, dx, dy, dz, out);
                    }
                }
            }
            if (Math.min(tx, ty) >= 1) return;
            enter = Math.min(tx, ty);
            if (tx < ty) { tx += tdx; cx += sx; } else { ty += tdy; cy += sy; }
        }
    }

    /** Where the segment enters one shape: through a side if it crosses the footprint at a height
     *  the shape occupies, otherwise through the top or the bottom if it crosses that plane inside
     *  the footprint. A segment that starts inside the footprint can only enter through a plane. */
    private static void shapeHit(Shape s, double ax, double ay, double az, double dx, double dy, double dz, Hit out) {
        double t1, t2;
        int face = 0;
        boolean inside;
        switch (s.kind) {
            case SEG -> {
                if (!(s.len > 1e-9)) return;                              // no length: nothing to hit
                SegHit h = Geometry.raySeg(ax, ay, dx, dy, s.ax, s.ay, s.bx, s.by);
                if (h == null || h.t() < 0 || h.t() > 1) return;
                if (hole(s, h.u(), az + dz * h.t())) return;
                t1 = t2 = h.t();
                inside = false;
                face = dx * -h.ey() + dy * h.ex() < 0 ? 0 : 1;             // face 0's normal is (-ey, ex)
            }
            case CIRCLE -> {
                Span sp = Geometry.rayCircle(ax, ay, dx, dy, s.cx, s.cy, s.r);
                if (sp == null) return;
                inside = sp.t1() < 0;
                t1 = Math.max(0, sp.t1());
                t2 = Math.min(1, sp.t2());
            }
            default -> {
                PolyHit ph = Geometry.rayPoly(ax, ay, dx, dy, s.xs, s.ys);
                if (ph == null) return;
                inside = ph.t1() < 0;
                t1 = Math.max(0, ph.t1());
                t2 = Math.min(1, ph.t2());
                face = ph.enterEdge();
            }
        }
        if (t2 < t1) return;
        double za = az + dz * t1;
        // Top and bottom along the segment: plane(t) = plane at a + slope * t. Flat shapes have
        // no slope, and then this is exactly the flat test it always was.
        double b0 = s.bottomAt(ax, ay), bs = s.bottomSlope(dx, dy);
        double h0 = s.topAt(ax, ay), hs = s.topSlope(dx, dy);
        double zb = b0 + bs * t1;
        if (!inside && za > zb + 1e-6 && za < h0 + hs * t1 - 1e-6) {
            keep(out, t1, Hit.SIDE, s.id, face);
            return;
        }
        if (s.kind == Kind.SEG) return;                                    // a thin wall has no top
        boolean below = za <= zb;
        double rate = dz - (below ? bs : hs);
        if (rate == 0) return;
        double tp = ((below ? b0 : h0) - az) / rate;
        if (tp >= t1 && tp <= t2) keep(out, tp, below ? Hit.BOTTOM : Hit.TOP, s.id, 0);
    }

    /** Is the height range z1..z2 inside one open span of the stack: a storey's [floor, ceil), or
     *  the sky above the highest roof? Anything else is a slab. */
    static boolean inSpan(Region[] st, int n, double z1, double z2) {
        double lo = Math.min(z1, z2), hi = Math.max(z1, z2), roof = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            Region r = st[i];
            if (lo >= r.floor - 1e-9 && hi <= r.ceil + 1e-9) return true;
            roof = Math.max(roof, Math.max(r.top, r.ceil));
        }
        return roof < Double.POSITIVE_INFINITY && lo >= roof - 1e-9;
    }
}

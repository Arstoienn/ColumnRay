package engine;

import engine.World.Region;
import engine.World.Shape;

/**
 * What the map lets a body of a given size do: what it can stand on, what is over its head, and
 * what is in its way.
 *
 * This is the engine's half of walking about. It knows the map - regions, shapes, tilted tops and
 * bottoms, the acceleration grid - and nothing else: not gravity, not a walking speed, not a jump,
 * not a camera. A game supplies the size of its body and decides what to do with the answers, so
 * a player, a crate and a patrolling guard all ask the same three questions of the same map.
 *
 * Storeys stack, so nothing here asks "what is the floor here" without also asking which storey is
 * meant: {@link #support} answers with the surface a body at this height could stand on and the
 * first thing above its head, and both of those depend on where its feet already are.
 *
 * One instance is one caller's: it keeps a two-number scratch for {@link #nearest} rather than
 * allocating per shape, so a second thread wants a second {@code Body}.
 */
public final class Body {
    private final World world;
    private final double[] near = new double[2];

    public Body(World world) {
        this.world = world;
    }

    /**
     * The height of the ground under (x, y), for a body that is not standing anywhere yet.
     *
     * The region's floor, which is where a storey begins, and 0 off the edge of the map. What a
     * body ends up standing on is {@link #support}, which may be a shape on top of that floor.
     */
    public double floorAt(double x, double y) {
        Region r = world.regionAt(x, y);
        return r == null ? 0 : r.floor;
    }

    /**
     * Blocked when the floor is more than one step up, the ceiling is below head height, or a solid
     * shape is in the way. {@code height} is how far the top of the body is above its feet.
     *
     * Storeys stack, so only regions that actually overlap the body between the feet and the top of
     * the head are considered - the floor of the storey above is not an obstacle to someone walking
     * about on the one below.
     */
    public boolean blocked(double x, double y, double feet, double radius, double height, double step) {
        if (world.regionAt(x, y) == null) return true;
        // Clearance has to be judged at the height we would end up at, not the one we are leaving:
        // stepping up onto a stair whose underside is a low void would otherwise be rejected by the
        // void's ceiling, even though our head ends up above it.
        double up = support(x, y, feet, radius, step)[0];
        double stand = up > feet && up <= feet + step ? up : feet;
        double head = stand + height;
        for (Region r : world.regionsNear(x, y, radius)) {
            if (!Geometry.discTouchesPoly(x, y, radius, r.xs, r.ys)) continue;
            if (r.ceil <= stand || r.floor >= head) continue;    // wholly below or above us
            if (r.floor > stand + step || r.ceil < head) return true;
        }
        for (Shape s : world.shapesNear(x, y, radius))
            if (Geometry.overlaps(x, y, radius, s)
                    && bottomNear(s, x, y) < head && topNear(s, x, y) > stand + step) return true;
        return false;
    }

    /** { the highest surface a body here could stand on, the lowest thing above it } */
    public double[] support(double x, double y, double feet, double radius, double step) {
        double ground = Double.NEGATIVE_INFINITY;
        for (Region r : world.regionsNear(x, y, radius)) {
            if (!Geometry.discTouchesPoly(x, y, radius, r.xs, r.ys)) continue;
            if (r.floor <= feet + step) ground = Math.max(ground, r.floor);
        }
        for (Shape s : world.shapesNear(x, y, radius)) {
            if (!Geometry.overlaps(x, y, radius, s)) continue;
            double top = topNear(s, x, y);       // a tilted top: its height where it meets the body
            if (top <= feet + step) ground = Math.max(ground, top);
        }
        if (ground == Double.NEGATIVE_INFINITY) ground = feet;

        // The ceiling is whatever is above the surface we would stand on, so the underside of a
        // stair we are climbing onto does not count as our own ceiling.
        double ceil = Double.POSITIVE_INFINITY;
        for (Region r : world.regionsNear(x, y, radius)) {
            if (!Geometry.discTouchesPoly(x, y, radius, r.xs, r.ys)) continue;
            if (r.ceil > ground) ceil = Math.min(ceil, r.ceil);
        }
        for (Shape s : world.shapesNear(x, y, radius))
            if (Geometry.overlaps(x, y, radius, s) && bottomNear(s, x, y) >= ground + step)
                ceil = Math.min(ceil, bottomNear(s, x, y));
        return new double[] {ground, ceil};
    }

    /** The name of the storey a body at this height is actually standing in, rather than just the
     *  lowest one here; null when it is off the map altogether. */
    public String regionName(double x, double y, double feet, double radius) {
        Region best = null;
        for (Region r : world.regionsNear(x, y, radius)) {
            if (!Geometry.discTouchesPoly(x, y, radius, r.xs, r.ys)) continue;
            if (feet + 0.05 < r.floor || feet >= r.ceil) continue;
            if (best == null || r.floor > best.floor) best = r;
        }
        if (best == null) best = world.regionAt(x, y);
        return best == null ? null : best.name;
    }

    /**
     * A tilted top or bottom is a plane, and read past the shape's own edge it keeps going. The body
     * is a disc, so it touches shapes whose footprint does not contain its centre - and for the
     * near-vertical triangles of a converted mesh (a roof's fascia climbs 60 m per metre) the plane
     * read 20 cm beyond the sliver was metres from any real part of it: a roof 10 m up stood in
     * front of the player as a wall at chest height, and on Haven twelve of sixteen directions were
     * blocked from the first step. So read the height where the shape is nearest the body.
     */
    private double topNear(Shape s, double px, double py) {
        if (s.hx == 0 && s.hy == 0) return s.h;
        nearest(s, px, py);
        return s.topAt(near[0], near[1]);
    }

    private double bottomNear(Shape s, double px, double py) {
        if (s.zx == 0 && s.zy == 0) return s.z0;
        nearest(s, px, py);
        return s.bottomAt(near[0], near[1]);
    }

    /** The point of the shape's footprint nearest (px, py), into {@link #near}. */
    private void nearest(Shape s, double px, double py) {
        double nx = px, ny = py;
        switch (s.kind) {
            case SEG -> {
                double ex = s.bx - s.ax, ey = s.by - s.ay, len2 = ex * ex + ey * ey;
                double u = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - s.ax) * ex + (py - s.ay) * ey) / len2));
                nx = s.ax + ex * u;
                ny = s.ay + ey * u;
            }
            case CIRCLE -> {
                double d = Math.hypot(px - s.cx, py - s.cy);
                if (d > s.r) { nx = s.cx + (px - s.cx) * s.r / d; ny = s.cy + (py - s.cy) * s.r / d; }
            }
            case POLY -> {
                if (!Geometry.pointInPoly(px, py, s.xs, s.ys)) {
                    double best = Double.POSITIVE_INFINITY;
                    for (int i = 0, n = s.xs.length; i < n; i++) {
                        int j = (i + 1) % n;
                        double ax = s.xs[i], ay = s.ys[i], ex = s.xs[j] - ax, ey = s.ys[j] - ay, len2 = ex * ex + ey * ey;
                        double u = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * ex + (py - ay) * ey) / len2));
                        double qx = ax + ex * u, qy = ay + ey * u, d = (px - qx) * (px - qx) + (py - qy) * (py - qy);
                        if (d < best) { best = d; nx = qx; ny = qy; }
                    }
                }
            }
        }
        near[0] = nx;
        near[1] = ny;
    }
}

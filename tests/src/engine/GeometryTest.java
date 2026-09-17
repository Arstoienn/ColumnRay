package engine;

/** Ray against segment and convex polygon: the two primitives every cast in the engine ends in. */
final class GeometryTest {
    private GeometryTest() {}

    private static final double TOL = 1e-12;

    static void run() {
        Check.group("Geometry");

        // A ray east from the origin, crossing a wall that runs north-south through x = 3.
        Geometry.SegHit h = Geometry.raySeg(0, 0, 1, 0, 3, -1, 3, 1);
        Check.that(h != null, "a wall straight ahead is hit");
        if (h != null) {
            Check.eq(h.t(), 3.0, TOL, "t is the distance along the ray, in ray lengths");
            Check.eq(h.u(), 0.5, TOL, "u is the position along the wall");
            Check.eq(h.ex(), 0.0, TOL, "the edge vector's x");
            Check.eq(h.ey(), 2.0, TOL, "the edge vector's y");
        }

        // r need not be a unit vector: t is in lengths of r, which is what the DDA relies on.
        Geometry.SegHit half = Geometry.raySeg(0, 0, 0.5, 0, 3, -1, 3, 1);
        Check.eq(half == null ? -1 : half.t(), 6.0, TOL, "t scales with the ray vector's length");

        Check.that(Geometry.raySeg(0, 0, 1, 0, 3, 1, 3, 2) == null, "a wall the ray passes beside is missed");
        Check.that(Geometry.raySeg(0, 0, 1, 0, 3, 0, 5, 0) == null, "a wall the ray runs along is missed");

        // Behind the ray is reported, with a negative t: the callers decide, not this.
        Geometry.SegHit back = Geometry.raySeg(0, 0, 1, 0, -3, -1, -3, 1);
        Check.eq(back == null ? 0 : back.t(), -3.0, TOL, "a wall behind the origin comes back with a negative t");

        // The ends count as hits, which is what keeps two walls meeting at a corner watertight.
        Check.that(Geometry.raySeg(0, 0, 1, 0, 3, 0, 3, 2) != null, "a wall whose end is exactly on the ray");

        double[] xs = {2, 4, 4, 2}, ys = {-1, -1, 1, 1};
        Geometry.PolyHit p = Geometry.rayPoly(0, 0, 1, 0, xs, ys);
        Check.that(p != null, "a box straight ahead is hit");
        if (p != null) {
            Check.eq(p.t1(), 2.0, TOL, "entry t");
            Check.eq(p.t2(), 4.0, TOL, "exit t");
            Check.that(p.t2() > p.t1(), "exit is beyond entry");
        }

        Geometry.PolyHit inside = Geometry.rayPoly(3, 0, 1, 0, xs, ys);
        Check.that(inside != null && inside.t1() < 0, "starting inside gives a negative entry t");

        Check.that(Geometry.rayPoly(0, 5, 1, 0, xs, ys) == null, "a box the ray passes above is missed");

        Geometry.Span c = Geometry.rayCircle(0, 0, 1, 0, 5, 0, 1);
        Check.that(c != null, "a circle straight ahead is hit");
        if (c != null) {
            Check.eq(c.t1(), 4.0, TOL, "circle entry");
            Check.eq(c.t2(), 6.0, TOL, "circle exit");
        }
        Check.that(Geometry.rayCircle(0, 0, 1, 0, 5, 1, 1) == null,
                "a circle the ray only grazes is not a hit: a tangent is not a surface to draw");

        Check.that(Geometry.pointInPoly(3, 0, xs, ys), "a point inside");
        Check.that(Geometry.pointInPoly(2, 0, xs, ys), "a point exactly on an edge counts as inside");
        Check.that(!Geometry.pointInPoly(1.9, 0, xs, ys), "a point outside");
        Check.that(Geometry.isConvex(xs, ys), "a box is convex");
        Check.that(!Geometry.isConvex(new double[] {0, 2, 1, 2, 0}, new double[] {0, 0, 1, 2, 2}),
                "an arrowhead is not");

        Check.eq(Geometry.distPointSeg(0, 0, 1, -1, 1, 1), 1.0, TOL, "distance to a segment beside the point");
        Check.eq(Geometry.distPointSeg(0, 0, 1, 1, 2, 2), Math.sqrt(2), TOL,
                "distance past a segment's end is measured to the end");
    }
}

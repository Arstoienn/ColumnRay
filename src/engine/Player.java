package engine;

import engine.World.Region;
import engine.World.Shape;

/**
 * Where the player is, and what the map lets them do about it.
 *
 * A disc of {@link #RADIUS} standing on whatever the map has under it, with an eye a little below
 * the top of their head. Everything here is about the map rather than about the picture: the
 * renderer is not mentioned once, and neither is a window, which is the point of it being a file
 * of its own. What the keyboard and the mouse did this frame arrives as a {@link Move}, so the
 * physics can be driven from a test, a replay or a script as easily as from a key.
 *
 * Storeys stack, so nothing here asks "what is the floor here" without also asking which storey
 * is meant: {@link #support} answers with the surface we could stand on and the first thing above
 * our head, and both of those depend on where our feet already are.
 */
final class Player {
    /** Player dimensions, in metres. */
    static final double RADIUS = 0.3, EYE_STAND = 1.6, EYE_CROUCH = 1.0, HEAD_ABOVE_EYE = 0.15;
    static final double STEP = 0.35, GRAVITY = 18, JUMP_SPEED = 5.2, WALK = 3.2, RUN = 5.5;
    static final double MAX_PITCH = Math.toRadians(30);

    /** What the controls asked for this frame, with no mention of which key any of it came from. */
    record Move(double turn, double look, double forward, double strafe,
                double mouseDX, double mouseDY, boolean crouch, boolean run, boolean jump) {
        static final Move NONE = new Move(0, 0, 0, 0, 0, 0, false, false, false);
    }

    private final World world;

    double x, y, angle, pitch, feet, vz, eyeH = EYE_STAND;
    /** The height the camera is actually at: feet, but easing over a step rather than jumping up
     *  it, which is what makes a staircase feel like one instead of a lift. */
    double viewFeet;
    boolean grounded;
    /** G: fly. Gravity off, Space and crouch go up and down, and nothing is solid - the quickest
     *  way to see whether a roof, a cliff or a stray block came out of the conversion right. */
    boolean flying;

    private double startFeet = Double.NaN;                       // --feet: which storey to start on

    Player(World world) {
        this.world = world;
        x = world.spawnX;
        y = world.spawnY;
        angle = world.spawnAngle;
        placeOnGround();
    }

    /** Start on whichever storey has a floor at (or just below) this height. Handy for looking at
     *  an upper floor without walking up to it: --feet 3.6 */
    void standOn(double z) {
        this.startFeet = z;
        placeOnGround();
    }

    void placeOnGround() {
        Region r = world.regionAt(x, y);
        double from = Double.isNaN(startFeet) ? (r == null ? 0 : r.floor) : startFeet;
        feet = viewFeet = support(x, y, from)[0];
        vz = 0;
        grounded = true;
    }


    /** Stand at exactly this height rather than on whatever ground is here. --shots and --verify
     *  compare against a reference camera, and snapping to our own floor moved the eye up to 25 cm
     *  away from where that camera stands, which tilts the whole frame out of line; unsnapped, a
     *  floor that came out at the wrong height shows up as what it is. */
    void standAt(double z) {
        feet = viewFeet = z;
        vz = 0;
        grounded = true;
    }

    /** Put the player at a view, for a screenshot or a comparison. */
    void look(double px, double py, double headingDeg, double pitchDeg) {
        x = px;
        y = py;
        angle = Math.toRadians(headingDeg);
        pitch = Math.toRadians(pitchDeg);
    }

    /** The eye, which is what the camera and the lighting both want. */
    double eye() { return viewFeet + eyeH; }

    /** One frame of movement: turn, look, walk, fall, and ease the camera over a step. */
    void step(double dt, Move m) {
        angle += m.turn() * 2.2 * dt + m.mouseDX() * 0.004;
        pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch + m.look() * 1.2 * dt - m.mouseDY() * 0.003));

        boolean crouch = m.crouch();
        boolean running = m.run();
        double dirX = Math.cos(angle), dirY = Math.sin(angle);
        double f = m.forward();
        double s = m.strafe();
        if (f != 0 || s != 0) {
            double speed = (running ? RUN : WALK) * (crouch ? 0.5 : 1) / Math.hypot(f, s);
            double dx = (dirX * f - dirY * s) * speed * dt, dy = (dirY * f + dirX * s) * speed * dt;
            if (flying) {
                x += dx;
                y += dy;
            } else {
                int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 0.1));   // substep so we cannot tunnel through thin walls
                for (int i = 0; i < n; i++) {
                    if (!blocked(x + dx / n, y, feet)) x += dx / n;
                    if (!blocked(x, y + dy / n, feet)) y += dy / n;
                }
            }
        }

        if (flying) {
            double up = (m.jump() ? 1 : 0) - (crouch ? 1 : 0);
            feet += up * (running ? RUN : WALK) * dt;
            vz = 0;
            grounded = false;
            eyeH += (EYE_STAND - eyeH) * Math.min(1, dt * 10);
            viewFeet = feet;
            return;
        }

        double[] sup = support(x, y, feet);
        double ground = sup[0], ceil = sup[1];
        if (grounded && feet > ground && feet - ground <= STEP) feet = ground;   // stick to the floor when stepping down
        if (grounded && m.jump()) vz = JUMP_SPEED;
        vz -= GRAVITY * dt;
        feet += vz * dt;
        if (feet <= ground) { feet = ground; vz = 0; grounded = true; } else grounded = false;
        if (feet + eyeH + HEAD_ABOVE_EYE > ceil) {
            feet = Math.max(ground, ceil - eyeH - HEAD_ABOVE_EYE);
            vz = Math.min(vz, 0);
        }

        double target = crouch ? EYE_CROUCH : EYE_STAND;
        if (!crouch && feet + EYE_STAND + HEAD_ABOVE_EYE > ceil) target = eyeH;  // something overhead: cannot stand back up
        eyeH += (target - eyeH) * Math.min(1, dt * 10);
        if (grounded && Math.abs(feet - viewFeet) < 1) viewFeet += (feet - viewFeet) * Math.min(1, dt * 14);
        else viewFeet = feet;
    }


    private double head(double feet) { return feet + eyeH + HEAD_ABOVE_EYE; }

    /** Blocked when the floor is more than one step up, the ceiling is below head height,
     *  or a solid shape is in the way. Storeys stack, so only regions that actually overlap the
     *  body between the feet and the top of the head are considered - the floor of the storey
     *  above is not an obstacle to someone walking about on the one below. */
    private boolean blocked(double px, double py, double feet) {
        if (world.regionAt(px, py) == null) return true;
        // Clearance has to be judged at the height we would end up at, not the one we are leaving:
        // stepping up onto a stair whose underside is a low void would otherwise be rejected by the
        // void's ceiling, even though our head ends up above it.
        double up = support(px, py, feet)[0];
        double stand = up > feet && up <= feet + STEP ? up : feet;
        for (Region r : world.regionsNear(px, py, RADIUS)) {
            if (!Geometry.discTouchesPoly(px, py, RADIUS, r.xs, r.ys)) continue;
            if (r.ceil <= stand || r.floor >= head(stand)) continue;    // wholly below or above us
            if (r.floor > stand + STEP || r.ceil < head(stand)) return true;
        }
        for (Shape s : world.shapesNear(px, py, RADIUS))
            if (Geometry.overlaps(px, py, RADIUS, s)
                    && bottomNear(s, px, py) < head(stand) && topNear(s, px, py) > stand + STEP) return true;
        return false;
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

    private final double[] near = new double[2];                 // update() is the only caller: one thread

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

    /** { highest surface we can stand on, lowest thing above our head } */
    private double[] support(double px, double py, double feet) {
        double ground = Double.NEGATIVE_INFINITY;
        for (Region r : world.regionsNear(px, py, RADIUS)) {
            if (!Geometry.discTouchesPoly(px, py, RADIUS, r.xs, r.ys)) continue;
            if (r.floor <= feet + STEP) ground = Math.max(ground, r.floor);
        }
        for (Shape s : world.shapesNear(px, py, RADIUS)) {
            if (!Geometry.overlaps(px, py, RADIUS, s)) continue;
            double top = topNear(s, px, py);     // a tilted top: its height where it meets the body
            if (top <= feet + STEP) ground = Math.max(ground, top);
        }
        if (ground == Double.NEGATIVE_INFINITY) ground = feet;

        // The ceiling is whatever is above the surface we would stand on, so the underside of a
        // stair we are climbing onto does not count as our own ceiling.
        double ceil = Double.POSITIVE_INFINITY;
        for (Region r : world.regionsNear(px, py, RADIUS)) {
            if (!Geometry.discTouchesPoly(px, py, RADIUS, r.xs, r.ys)) continue;
            if (r.ceil > ground) ceil = Math.min(ceil, r.ceil);
        }
        for (Shape s : world.shapesNear(px, py, RADIUS))
            if (Geometry.overlaps(px, py, RADIUS, s) && bottomNear(s, px, py) >= ground + STEP)
                ceil = Math.min(ceil, bottomNear(s, px, py));
        return new double[] {ground, ceil};
    }


    /** The storey the player is actually standing on, rather than just the lowest one here. */
    Region here() {
        Region best = null;
        for (Region r : world.regionsNear(x, y, RADIUS)) {
            if (!Geometry.discTouchesPoly(x, y, RADIUS, r.xs, r.ys)) continue;
            if (feet + 0.05 < r.floor || feet >= r.ceil) continue;
            if (best == null || r.floor > best.floor) best = r;
        }
        return best != null ? best : world.regionAt(x, y);
    }
}

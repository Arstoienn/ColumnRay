package game;

import engine.Body;
import engine.World;

/**
 * Where the player is, and what the map lets them do about it.
 *
 * A disc of {@link #RADIUS} standing on whatever the map has under it, with an eye a little below
 * the top of their head. This is the game's half of walking about: how fast a walk is, how hard a
 * jump is, how quickly the camera eases over a step, how low a crouch goes. The map's half - what
 * can be stood on, what is overhead, what is in the way - is {@link Body}, which is the engine's,
 * and which knows nothing about any of these numbers.
 *
 * Nothing here mentions the renderer, a window or a key. What the controls did this frame arrives
 * as a {@link Move}, so the physics can be driven from a test, a replay or a script as easily as
 * from a keyboard.
 */
public final class Player {
    /** Player dimensions, in metres. */
    public static final double RADIUS = 0.3, EYE_STAND = 1.6, EYE_CROUCH = 1.0, HEAD_ABOVE_EYE = 0.15;
    public static final double STEP = 0.35, GRAVITY = 18, JUMP_SPEED = 5.2, WALK = 3.2, RUN = 5.5;

    /** What the controls asked for this frame, with no mention of which key any of it came from. */
    public record Move(double turn, double look, double forward, double strafe,
                       double mouseDX, double mouseDY, boolean crouch, boolean run, boolean jump) {
        public static final Move NONE = new Move(0, 0, 0, 0, 0, 0, false, false, false);
    }

    private final Body body;

    public double x, y, angle, pitch, feet, vz, eyeH = EYE_STAND;
    /** The height the camera is actually at: feet, but easing over a step rather than jumping up
     *  it, which is what makes a staircase feel like one instead of a lift. */
    public double viewFeet;
    public boolean grounded;
    /** Fly: gravity off, jump and crouch go up and down, and nothing is solid - the quickest way to
     *  see whether a roof, a cliff or a stray block came out of the conversion right. */
    public boolean flying;

    private double startFeet = Double.NaN;                       // --feet: which storey to start on
    /**
     * How far up and down this player may look: what the map asked for, unless the overscan that
     * would need is more than the render buffer may grow to, in which case the engine lowers it.
     * Both ends of that matter - the map knows what it can afford to draw, the machine knows what
     * it can afford to allocate, and the smaller of the two is the one you get. See
     * {@code Host.pitchLimit()}, which is where this number comes from every frame.
     */
    public double pitchLimit;

    public Player(World world) {
        this.body = new Body(world);
        pitchLimit = world.maxPitch;
        x = world.spawnX;
        y = world.spawnY;
        angle = world.spawnAngle;
        placeOnGround();
    }

    /** Start on whichever storey has a floor at (or just below) this height. Handy for looking at
     *  an upper floor without walking up to it: --feet 3.6 */
    public void standOn(double z) {
        this.startFeet = z;
        placeOnGround();
    }

    public void placeOnGround() {
        double from = Double.isNaN(startFeet) ? body.floorAt(x, y) : startFeet;
        feet = viewFeet = body.support(x, y, from, RADIUS, STEP)[0];
        vz = 0;
        grounded = true;
    }

    /** Stand at exactly this height rather than on whatever ground is here. --shots and --verify
     *  compare against a reference camera, and snapping to our own floor moved the eye up to 25 cm
     *  away from where that camera stands, which tilts the whole frame out of line; unsnapped, a
     *  floor that came out at the wrong height shows up as what it is. */
    public void standAt(double z) {
        feet = viewFeet = z;
        vz = 0;
        grounded = true;
    }

    /** Put the player at a view, for a screenshot or a comparison. */
    public void look(double px, double py, double headingDeg, double pitchDeg) {
        x = px;
        y = py;
        angle = Math.toRadians(headingDeg);
        pitch = Math.toRadians(pitchDeg);
    }

    /** The eye, which is what the camera and the lighting both want. */
    public double eye() { return viewFeet + eyeH; }

    /** The name of the storey the player is standing in, for the overlay; null off the map. */
    public String where() { return body.regionName(x, y, feet, RADIUS); }

    /** One frame of movement: turn, look, walk, fall, and ease the camera over a step. */
    public void step(double dt, Move m) {
        angle += m.turn() * 2.2 * dt + m.mouseDX() * 0.004;
        pitch = Math.max(-pitchLimit, Math.min(pitchLimit, pitch + m.look() * 1.2 * dt - m.mouseDY() * 0.003));

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
                    if (!blocked(x + dx / n, y)) x += dx / n;
                    if (!blocked(x, y + dy / n)) y += dy / n;
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

        double[] sup = body.support(x, y, feet, RADIUS, STEP);
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

    /** Is this body, at the height it is now, able to stand at (px, py)? */
    private boolean blocked(double px, double py) {
        return body.blocked(px, py, feet, RADIUS, eyeH + HEAD_ABOVE_EYE, STEP);
    }
}

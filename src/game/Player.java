package game;

import engine.Body;
import engine.World;

/**
 * Where the player is, and what the map lets them do about it.
 *
 * A disc of {@link #RADIUS} standing on whatever the map has under it, with an eye a little below
 * the top of their head. This is the game's half of walking about: how fast a walk is, how quickly
 * it gets there, how hard a jump is, how the camera eases over a step and settles after a drop, how
 * low a crouch goes. The map's half - what can be stood on, what is overhead, what is in the way -
 * is {@link Body}, which is the engine's, and which knows nothing about any of these numbers.
 *
 * Nothing here mentions the renderer, a window or a key. What the controls did this frame arrives
 * as a {@link Move}, so the physics can be driven from a test, a replay or a script as easily as
 * from a keyboard - which is what {@code PlayerTest} does, and the reason every one of the numbers
 * below can be argued about against a measurement instead of a memory of how it felt.
 *
 * <h2>Why the walk has a velocity in it</h2>
 *
 * It used to move the player by {@code speed * dt} in whatever direction the keys pointed, which
 * makes the top speed instant in both directions: a step is at full pace on the frame the key goes
 * down and stopped on the frame it comes up, and a jump could be steered as if the air were a
 * floor. So there is a velocity now, accelerated towards what the keys ask for - fast on the ground
 * ({@link #GROUND_ACCEL}: full walking pace in about 70 ms, so nothing feels sluggish), slowly in
 * the air ({@link #AIR_ACCEL}, which leaves a jump its momentum), and not at all in the air when
 * the keys ask for nothing, because letting go mid-jump must not stop you dead.
 */
public final class Player {
    /** Player dimensions, in metres. */
    public static final double RADIUS = 0.3, EYE_STAND = 1.6, EYE_CROUCH = 1.0, HEAD_ABOVE_EYE = 0.15;
    public static final double STEP = 0.35, GRAVITY = 18, JUMP_SPEED = 5.2, WALK = 3.2, RUN = 5.5;

    /** How quickly the walk reaches the pace the keys asked for, and loses it again, in m/s². */
    public static final double GROUND_ACCEL = 45, STOP_ACCEL = 35, AIR_ACCEL = 9;

    /**
     * Jumping, given that a person is not a state machine.
     *
     * {@link #COYOTE}: a jump asked for within this long of walking off an edge still jumps, which
     * is the difference between a leap and an unexplained fall. {@link #JUMP_BUFFER}: a jump asked
     * for this long before landing is remembered and jumps on landing, rather than being eaten
     * because the ground was two frames away. Both are shorter than a person can perceive and both
     * are the whole of why jumping in a game feels like jumping.
     */
    public static final double COYOTE = 0.10, JUMP_BUFFER = 0.15;

    /**
     * What the camera does that the body does not: bob with the stride, and dip on landing.
     *
     * Neither moves the player - they are an offset on {@link #eye()} - and both are small enough
     * to be felt rather than seen ({@link #BOB} is 2 cm at a run, half that at a walk). They are
     * here because speed in a first-person view is read off the picture and nothing else: with a
     * perfectly still camera, walking and standing on a moving floor look the same.
     */
    public static final double BOB = 0.022, STRIDE = 0.95, DIP_MAX = 0.11, DIP_STIFF = 180, DIP_DAMP = 22;

    /** What the controls asked for this frame, with no mention of which key any of it came from. */
    public record Move(double turn, double look, double forward, double strafe,
                       double mouseDX, double mouseDY, boolean crouch, boolean run, boolean jump) {
        public static final Move NONE = new Move(0, 0, 0, 0, 0, 0, false, false, false);
    }

    private final Body body;

    public double x, y, angle, pitch, feet, vz, eyeH = EYE_STAND;
    /** How fast the body is moving across the ground, in m/s. Accelerated towards what the keys
     *  ask for rather than set from it; see the class comment. */
    public double vx, vy;
    /** The height the camera is actually at: feet, but easing over a step rather than jumping up
     *  it, which is what makes a staircase feel like one instead of a lift. */
    public double viewFeet;
    public boolean grounded;
    /** Fly: gravity off, jump and crouch go up and down, and nothing is solid - the quickest way to
     *  see whether a roof, a cliff or a stray block came out of the conversion right. A game that
     *  is being played rather than inspected leaves this alone. */
    public boolean flying;
    /** How much of the camera's bob and landing dip to use, 0 to leave the camera perfectly still.
     *  A screenshot never moves and so never bobs, but a tool that walks the player about and
     *  compares frames wants to be able to say so. */
    public double cameraMotion = 1;

    private double startFeet = Double.NaN;                       // --feet: which storey to start on
    private double bobPhase;                                     // where in the stride the camera is
    private double bob;                                          // this frame's bob, in metres
    private double dip, dipV;                                    // the landing dip and its spring
    private double coyote, jumpAsked;                            // see COYOTE and JUMP_BUFFER
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
        settle();
    }

    /** Stand at exactly this height rather than on whatever ground is here. --shots and --verify
     *  compare against a reference camera, and snapping to our own floor moved the eye up to 25 cm
     *  away from where that camera stands, which tilts the whole frame out of line; unsnapped, a
     *  floor that came out at the wrong height shows up as what it is. */
    public void standAt(double z) {
        feet = viewFeet = z;
        settle();
    }

    /** Standing still, with nothing left over from however we got here: a camera placed for a
     *  screenshot must not be halfway through a stride or springing back from a landing. */
    private void settle() {
        vz = vx = vy = 0;
        grounded = true;
        bobPhase = bob = dip = dipV = 0;
        coyote = jumpAsked = 0;
    }

    /** Put the player at a view, for a screenshot or a comparison. */
    public void look(double px, double py, double headingDeg, double pitchDeg) {
        x = px;
        y = py;
        angle = Math.toRadians(headingDeg);
        pitch = Math.toRadians(pitchDeg);
    }

    /** The eye, which is what the camera and the lighting both want. The stride and the landing
     *  ride on top of it; both are zero when standing still. */
    public double eye() { return viewFeet + eyeH + bob - dip; }

    /** How fast the body is moving across the ground, in m/s. */
    public double speed() { return Math.hypot(vx, vy); }

    /** The name of the storey the player is standing in, for the overlay; null off the map. */
    public String where() { return body.regionName(x, y, feet, RADIUS); }

    /** One frame of movement: turn, look, walk, fall, and settle the camera afterwards. */
    public void step(double dt, Move m) {
        angle += m.turn() * 2.2 * dt + m.mouseDX() * LOOK;
        pitch = Math.max(-pitchLimit, Math.min(pitchLimit, pitch + m.look() * 1.2 * dt - m.mouseDY() * LOOK));

        boolean crouch = m.crouch();
        double top = (m.run() ? RUN : WALK) * (crouch ? 0.5 : 1);
        double dirX = Math.cos(angle), dirY = Math.sin(angle);
        double f = m.forward(), s = m.strafe();
        double wishX = 0, wishY = 0;
        if (f != 0 || s != 0) {
            double norm = top / Math.hypot(f, s);                 // diagonals are not faster
            wishX = (dirX * f - dirY * s) * norm;
            wishY = (dirY * f + dirX * s) * norm;
        }

        if (flying) {
            flyStep(dt, m, crouch, top, wishX, wishY);
            return;
        }

        accelerate(dt, wishX, wishY);
        walk(dt);
        double ceil = fall(dt, m.jump());
        camera(dt, crouch, ceil);
    }

    /** Fly: what the keys ask for, at once and in a straight line, through anything in the way.
     *  It is an inspection tool and it is better for being exact rather than weighty. */
    private void flyStep(double dt, Move m, boolean crouch, double top, double wishX, double wishY) {
        vx = wishX;
        vy = wishY;
        x += vx * dt;
        y += vy * dt;
        feet += ((m.jump() ? 1 : 0) - (crouch ? 1 : 0)) * top * dt;
        vz = 0;
        grounded = false;
        eyeH += (EYE_STAND - eyeH) * Math.min(1, dt * 10);
        bob = dip = dipV = 0;
        viewFeet = feet;
    }

    /**
     * Move the velocity towards what the keys asked for.
     *
     * Straight at it rather than along each axis, so that turning while running curves the path
     * instead of stopping the old direction before starting the new one. In the air with nothing
     * asked for, nothing happens at all: a jump keeps what it left the ground with.
     */
    private void accelerate(double dt, double wishX, double wishY) {
        boolean asking = wishX != 0 || wishY != 0;
        if (!grounded && !asking) return;
        double rate = grounded ? (asking ? GROUND_ACCEL : STOP_ACCEL) : AIR_ACCEL;
        double dvx = wishX - vx, dvy = wishY - vy, dv = Math.hypot(dvx, dvy), step = rate * dt;
        if (dv <= step || dv == 0) {
            vx = wishX;
            vy = wishY;
        } else {
            vx += dvx / dv * step;
            vy += dvy / dv * step;
        }
    }

    /** Carry the velocity across the floor, one axis at a time so that a wall is slid along rather
     *  than stopped at, and in short enough steps that a thin wall cannot be tunnelled through. */
    private void walk(double dt) {
        double dx = vx * dt, dy = vy * dt;
        if (dx == 0 && dy == 0) return;
        int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 0.1));
        for (int i = 0; i < n; i++) {
            if (!blocked(x + dx / n, y)) x += dx / n;
            else vx = 0;                                          // walking into a wall is not speed kept up against it
            if (!blocked(x, y + dy / n)) y += dy / n;
            else vy = 0;
        }
    }

    /**
     * Gravity, the ground, whatever is overhead, and the jump - including the two allowances that
     * make asking for one at the wrong moment work anyway; see COYOTE and JUMP_BUFFER.
     *
     * Returns the ceiling it found, because the camera needs the same answer and asking the map
     * twice for it is a grid query the frame does not have to pay for.
     */
    private double fall(double dt, boolean jump) {
        double[] sup = body.support(x, y, feet, RADIUS, STEP);
        double ground = sup[0], ceil = sup[1];
        if (grounded && feet > ground && feet - ground <= STEP) feet = ground;   // stick to the floor when stepping down

        coyote = grounded ? COYOTE : Math.max(0, coyote - dt);
        jumpAsked = jump ? JUMP_BUFFER : Math.max(0, jumpAsked - dt);
        if (jumpAsked > 0 && coyote > 0) {
            vz = JUMP_SPEED;
            jumpAsked = coyote = 0;
            grounded = false;
        }

        vz -= GRAVITY * dt;
        feet += vz * dt;
        if (feet <= ground) {
            if (!grounded) land(-vz);
            feet = ground;
            vz = 0;
            grounded = true;
        } else {
            grounded = false;
        }
        if (feet + eyeH + HEAD_ABOVE_EYE > ceil) {
            feet = Math.max(ground, ceil - eyeH - HEAD_ABOVE_EYE);
            vz = Math.min(vz, 0);
        }
        return ceil;
    }

    /** Landing: push the camera down by what the drop was worth, for the spring to bring back. */
    private void land(double fallSpeed) {
        if (fallSpeed <= 2) return;                               // stepping off a kerb is not a landing
        dip = Math.min(DIP_MAX, dip + fallSpeed * 0.012 * cameraMotion);
        dipV = 0;
    }

    /**
     * Everything the camera does that the body does not: crouch, ease over a step, bob with the
     * stride, and spring back from a landing.
     *
     * The bob's phase advances with the distance walked rather than with time, so it keeps step
     * with the feet whatever the pace, and stops where it is when the walking does instead of
     * carrying on nodding on the spot.
     */
    private void camera(double dt, boolean crouch, double ceil) {
        double target = crouch ? EYE_CROUCH : EYE_STAND;
        if (!crouch && feet + EYE_STAND + HEAD_ABOVE_EYE > ceil) target = eyeH;  // something overhead: cannot stand back up
        eyeH += (target - eyeH) * Math.min(1, dt * 10);
        if (grounded && Math.abs(feet - viewFeet) < 1) viewFeet += (feet - viewFeet) * Math.min(1, dt * 14);
        else viewFeet = feet;

        double pace = speed();
        if (grounded && pace > 0.1) {
            bobPhase += pace * dt * Math.PI / STRIDE;             // one rise and fall per footfall
            bob = Math.sin(bobPhase) * BOB * Math.min(1, pace / RUN) * cameraMotion;
        } else {
            bobPhase = 0;
            bob += (0 - bob) * Math.min(1, dt * 8);               // and back to level, rather than stopping mid-nod
        }

        dipV += (-dip * DIP_STIFF - dipV * DIP_DAMP) * dt;        // a spring, near enough critically damped
        dip += dipV * dt;
        if (Math.abs(dip) < 1e-4 && Math.abs(dipV) < 1e-3) dip = dipV = 0;
    }

    /** Is this body, at the height it is now, able to stand at (px, py)? */
    private boolean blocked(double px, double py) {
        return body.blocked(px, py, feet, RADIUS, eyeH + HEAD_ABOVE_EYE, STEP);
    }

    /**
     * Radians per pixel of mouse movement, the same in both directions.
     *
     * It was not: sideways was 0.004 and up and down 0.003, which is a mouse that draws an ellipse
     * when the hand draws a circle. One number, between the two.
     */
    private static final double LOOK = 0.0035;
}

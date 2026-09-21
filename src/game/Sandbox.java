package game;

import engine.Game;
import engine.Host;
import engine.Input;
import engine.Keys;
import engine.View;

/**
 * The game ColumnRay ships with: walk about a map and look at it.
 *
 * There is no score and nothing to shoot. What there is instead is everything a first-person game
 * needs before any of that - a body that walks, falls, crouches, climbs stairs and cannot walk
 * through walls, a camera on it, a set of bindings, and an overlay - and it is all here rather
 * than in the engine, which is the point. A game of one's own starts by writing another one of
 * these, not by editing the frame loop.
 *
 * What it asks of the engine is small enough to list: where the map is, how far it may look up,
 * and a few switches that belong to the renderer rather than to the player (the field of view, the
 * old y-shearing pitch, the render scale, the ray view). Everything else - which key does what,
 * how fast a run is, what the overlay says - is decided here.
 */
public final class Sandbox implements Game {
    private final Host host;
    private final Player player;
    private final Hud hud;
    private final View view = new View();

    private boolean showMap = true;
    private boolean fisheye = false;
    private boolean baked = true;                                // the flat model instead, for comparison
    private double fovDeg;

    public Sandbox(Host host, double startFeet) {
        this.host = host;
        this.player = new Player(host.world());
        this.hud = new Hud(host.world(), host, player);
        this.fovDeg = host.fov();
        if (!Double.isNaN(startFeet)) player.standOn(startFeet);
    }

    @Override public void update(double dt, Input in) {
        taps(in);
        host.setFov(fovDeg);
        player.step(dt, controls(in));
    }

    /**
     * The keys that do their work once, on the way down, rather than for as long as they are held.
     *
     * Every one of them is asked about every frame, whether or not an earlier one fired: it is the
     * asking that moves the edge on, so a key that goes unasked for a frame is a key that fires
     * again the next time it is looked at.
     */
    private void taps(Input in) {
        if (in.tapped(Keys.ESCAPE)) host.stop();
        if (in.tapped(Keys.M)) showMap = !showMap;
        if (in.tapped(Keys.G)) {
            player.flying = !player.flying;
            player.vz = 0;
            player.grounded = false;
        }
        if (in.tapped(Keys.F)) fisheye = !fisheye;
        if (in.tapped(Keys.P)) host.setShear(!host.shear());
        if (in.tapped(Keys.L)) baked = !baked;
        if (in.tapped(Keys.H)) host.setHdr(!host.hdr());
        if (in.tapped(Keys.R)) host.toggleRayView();
        if (in.tapped(Keys.N)) host.toggleRayFollow();
        if (in.tapped(Keys.LEFT_BRACKET)) fovDeg = Math.max(30, fovDeg - 5);
        if (in.tapped(Keys.MINUS)) fovDeg = Math.max(30, fovDeg - 5);
        if (in.tapped(Keys.RIGHT_BRACKET)) fovDeg = Math.min(120, fovDeg + 5);
        if (in.tapped(Keys.EQUALS)) fovDeg = Math.min(120, fovDeg + 5);
        if (in.tapped(Keys.COMMA)) host.stepScale(-1);
        if (in.tapped(Keys.PERIOD)) host.stepScale(1);
        if (in.tapped(Keys.V)) host.toggleAutoRes();
    }

    /** What the keyboard and the mouse are asking for, as movement rather than as keys. */
    private Player.Move controls(Input in) {
        return new Player.Move(
                (in.down(Keys.RIGHT) || in.down(Keys.E) ? 1 : 0) - (in.down(Keys.LEFT) || in.down(Keys.Q) ? 1 : 0),
                (in.down(Keys.UP) ? 1 : 0) - (in.down(Keys.DOWN) ? 1 : 0),
                (in.down(Keys.W) ? 1 : 0) - (in.down(Keys.S) ? 1 : 0),
                (in.down(Keys.D) ? 1 : 0) - (in.down(Keys.A) ? 1 : 0),
                in.mouseDX(), in.mouseDY(),
                in.down(Keys.C) || in.down(Keys.CONTROL) || in.down(Keys.RIGHT_CONTROL),
                in.down(Keys.SHIFT) || in.down(Keys.RIGHT_SHIFT),
                in.down(Keys.SPACE));
    }

    @Override public void pitchLimit(double radians) {
        player.pitchLimit = radians;
        player.pitch = Math.max(-radians, Math.min(radians, player.pitch));
    }

    @Override public View view() {
        view.x = player.x;
        view.y = player.y;
        view.heading = player.angle;
        view.eye = player.eye();
        view.pitch = player.pitch;
        view.fisheye = fisheye;
        view.baked = baked;
        return view;
    }

    @Override public void place(double x, double y, double headingDeg, double pitchDeg, double feet) {
        player.look(x, y, headingDeg, pitchDeg);
        if (Double.isNaN(feet)) player.placeOnGround();
        else player.standAt(feet);
    }

    @Override public void overlay(java.awt.Graphics2D g, Overlay o) {
        Hud.Status st = new Hud.Status(o.fps(), o.imageW(), o.imageH(), o.ss(), o.rays(), fisheye,
                (!host.hasLighting() ? "flat lighting (--flat)" : baked ? "baked lighting" : "flat lighting (L)")
                        + (host.hdr() ? ", filmic" : ", sRGB"),
                o.shear(), o.autoRes());
        hud.draw(g, o.x() + 12, o.y() + 20, st);
        if (showMap)
            hud.drawMinimap(g, o.x() + o.width() - 12, o.y() + 12,
                    (int) Math.max(160, Math.min(o.width(), o.height()) * 0.42));
    }

    /** The player, for a test or a tool that wants to drive this game rather than play it. */
    public Player player() { return player; }
}

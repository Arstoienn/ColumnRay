package game;

import engine.Host;
import engine.Input;
import engine.Keys;
import engine.View;

/**
 * The game ColumnRay is developed with: walk about a map and look at what the renderer is doing.
 *
 * There is no score and nothing to shoot. What there is instead is everything a first-person game
 * needs before any of that - a body that walks, falls, crouches, climbs stairs and cannot walk
 * through walls, a camera on it, a set of bindings, and an overlay - and it is all here rather
 * than in the engine, which is the point. A game of one's own starts by writing another one of
 * these, not by editing the frame loop; {@link Play} is that second one, and the walking both of
 * them share is in {@link Walker}.
 *
 * What it asks of the engine is small enough to list: where the map is, how far it may look up,
 * and a few switches that belong to the renderer rather than to the player (the field of view, the
 * old y-shearing pitch, the render scale, the ray view). Everything else - which key does what,
 * how fast a run is, what the overlay says - is decided here.
 */
public final class Sandbox extends Walker {
    private final Hud hud;

    private boolean showMap = true;
    private boolean fisheye = false;
    private boolean baked = true;                                // the flat model instead, for comparison
    private double fovDeg;

    public Sandbox(Host host, double startFeet) {
        super(host, startFeet);
        this.hud = new Hud(host.world(), host, player);
        this.fovDeg = host.fov();
        // The mouse looks about without a button held, like anything else in first person. Tab hands
        // the pointer back, because this game has a use for one: hovering a column picks the ray the
        // ray view traces.
        host.setMouseLook(true);
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
        if (in.tapped(Keys.TAB)) host.setMouseLook(!host.mouseLook());
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

    @Override public View view() {
        View v = super.view();
        v.fisheye = fisheye;
        v.baked = baked;
        return v;
    }

    @Override public void overlay(java.awt.Graphics2D g, Overlay o) {
        Hud.Status st = new Hud.Status(o.fps(), o.imageW(), o.imageH(), o.ss(), o.rays(), fisheye,
                (!host.hasLighting() ? "flat lighting (--flat)" : baked ? "baked lighting" : "flat lighting (L)")
                        + (host.hdr() ? ", filmic" : ", sRGB"),
                o.shear(), o.autoRes(), host.mouseLook());
        hud.draw(g, o.x() + 12, o.y() + 20, st);
        if (showMap)
            hud.drawMinimap(g, o.x() + o.width() - 12, o.y() + 12,
                    (int) Math.max(160, Math.min(o.width(), o.height()) * 0.42));
    }
}

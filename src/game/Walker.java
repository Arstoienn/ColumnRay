package game;

import engine.Game;
import engine.Host;
import engine.Input;
import engine.Keys;
import engine.View;

/**
 * The half of a game that is the same whether anyone is being shown the engine or playing in it:
 * a body, a camera on it, and the keys that move it.
 *
 * ColumnRay ships two games - {@link Sandbox}, which puts every switch the renderer has on the
 * screen, and {@link Play}, which puts nothing there at all - and the difference between them is
 * entirely what they do with the frame after the player has moved. Walking is not a difference, so
 * it is here: which key walks forward, how the mouse turns, what the engine is handed as a view,
 * and where a headless capture's camera goes.
 *
 * It is deliberately not in the engine. The engine is not allowed to know that W walks forward
 * (see {@link Game}); what this is instead is the first thing a third game would extend, and the
 * bindings below are the answer to "what does a first-person game usually do", not a rule.
 */
abstract class Walker implements Game {
    protected final Host host;
    protected final Player player;
    private final View view = new View();

    protected Walker(Host host, double startFeet) {
        this.host = host;
        this.player = new Player(host.world());
        if (!Double.isNaN(startFeet)) player.standOn(startFeet);
    }

    /**
     * What the keyboard and the mouse are asking for, as movement rather than as keys.
     *
     * Positions, not letters (see {@link Keys}): WASD is a shape on the keyboard and stays where it
     * is on Colemak or Bopomofo. The arrows and Q/E are here because a trackpad is a poor way to
     * turn round, and both Shift and both Control keys because which one is under a hand depends on
     * which hand.
     */
    protected Player.Move controls(Input in) {
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

    /** Where the engine is to look from: the player's eye. A subclass that has switches of its own
     *  to set on the view fills them in on top of this. */
    @Override public View view() {
        view.x = player.x;
        view.y = player.y;
        view.heading = player.angle;
        view.eye = player.eye();
        view.pitch = player.pitch;
        return view;
    }

    @Override public void place(double x, double y, double headingDeg, double pitchDeg, double feet) {
        player.look(x, y, headingDeg, pitchDeg);
        if (Double.isNaN(feet)) player.placeOnGround();
        else player.standAt(feet);
    }

    /** The player, for a test or a tool that wants to drive this game rather than play it. */
    public Player player() { return player; }
}

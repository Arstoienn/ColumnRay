package game;

import engine.Host;
import engine.Input;
import engine.Keys;
import engine.View;

/**
 * The game with nothing on the screen but the game: {@code --play}.
 *
 * {@link Sandbox} is an instrument. Every switch the renderer has is on a key, four lines of text
 * say what it is doing, and there is a fly mode for looking at a map from outside it - all of which
 * is exactly right when the thing being examined is the engine, and all of which is in the way when
 * what is being shown is the world. So this is the same walking with none of that: no overlay, no
 * minimap, no ray view, no toggles, no flying. The mouse looks about without a button held and the
 * pointer is out of sight, the way a first-person game has worked for thirty years.
 *
 * It is not a cut-down Sandbox with the drawing commented out - it is a second {@code Game}, which
 * is what the engine/game split was for. The only keys it reads are the ones that move a person:
 * WASD, the mouse, Space, Shift, crouch, and Escape to leave.
 */
public final class Play extends Walker {
    public Play(Host host, double startFeet) {
        super(host, startFeet);
        // Asked for before the window exists, which Host allows: it takes the pointer when it opens.
        host.setMouseLook(true);
    }

    @Override public void update(double dt, Input in) {
        if (in.tapped(Keys.ESCAPE)) host.stop();
        player.step(dt, controls(in));
    }

    /** The baked lighting, and the tone curve if the run asked for it. Nothing here toggles either:
     *  a picture being shown to somebody is shown the best way the engine has. */
    @Override public View view() {
        View v = super.view();
        v.baked = true;
        v.fisheye = false;
        return v;
    }

    // No overlay. That is the point of this class, so it is worth saying out loud rather than
    // leaving the reader to notice that Game's default does nothing.
}

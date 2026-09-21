package engine;

import java.awt.Graphics2D;

/**
 * What a game is, as far as the engine is concerned.
 *
 * The engine owns the window, the frame loop, the buffers, the renderer and the map. It does not
 * own the player, the controls or the overlay, and it must not: the whole of ColumnRay's game
 * lives in the {@code game} package and reaches the engine through this interface and
 * {@link Host}, so that a second game is a second implementation rather than a fork of the loop.
 *
 * The contract for one frame is:
 *
 * <ol>
 *   <li>{@link #update} - move whatever moves, given how long the last frame took and what the
 *       controls are doing.</li>
 *   <li>{@link #pitchLimit} - the engine says how far this frame may tilt, which depends on the
 *       map and on how much overscan the render buffer can hold at the size it is now.</li>
 *   <li>{@link #view} - where to look from.</li>
 *   <li>{@link #overlay} - draw over the finished picture.</li>
 * </ol>
 *
 * {@link #place} is the odd one out: it is not part of a running frame at all, it is how the
 * headless modes (--shot, --shots, --verify) ask a game to stand at a camera out of a views file.
 * A game that never gets screenshotted can leave it alone.
 */
public interface Game {
    /** One frame of whatever this game does. {@code dt} is in seconds and already capped. */
    void update(double dt, Input in);

    /** Where to look from this frame. Returning the same object every frame is expected. */
    View view();

    /**
     * How far up or down the engine can let this frame tilt, in radians.
     *
     * Told rather than asked, because it moves: the map sets a ceiling, and under it the pitch
     * warp's overscan has to fit in the render buffer, which changes size whenever the frame-time
     * controller does. A game that lets the player look up should clamp its own pitch to this, or
     * the engine will clamp the view and the game's idea of where it is looking will drift away
     * from the picture.
     */
    default void pitchLimit(double radians) { }

    /**
     * Stand at a camera from a views file, for a headless capture.
     *
     * {@code feet} is the height the camera's feet are at, or {@code NaN} for "stand on whatever
     * ground is here". Heading and pitch are in degrees, which is what the files are written in.
     * The engine renders whatever {@link #view} says afterwards.
     */
    default void place(double x, double y, double headingDeg, double pitchDeg, double feet) { }

    /** Draw over the finished frame: a HUD, a map, a crosshair. Called on the loop's thread with
     *  the window's own graphics, after the picture has been scaled into place. */
    default void overlay(Graphics2D g, Overlay o) { }

    /**
     * Where the picture ended up in the window, and what the engine spent getting it there.
     *
     * The rectangle is in window pixels: a HUD lays itself out against this rather than against
     * the window, because the picture is letterboxed inside a window of a different shape.
     * {@code imageW} and {@code imageH} are the picture's own resolution before it was scaled into
     * that rectangle, and {@code rays} is how many columns were cast to make it.
     */
    record Overlay(int x, int y, int width, int height,
                   double fps, int imageW, int imageH, int ss, int rays,
                   boolean autoRes, boolean shear) { }
}

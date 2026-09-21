package engine;

/**
 * Where to look from: the one thing a game must tell the engine every frame.
 *
 * It is deliberately small, and deliberately in the units a game thinks in - metres and radians -
 * rather than the ones the renderer works in. The renderer's own camera carries the horizon as an
 * offset in pixels, which is a number only the pitch warp can work out (see {@link Warp}), and a
 * game that had to fill that in would be doing the engine's arithmetic for it.
 *
 * Mutable and reused: a game is expected to keep one of these and fill it in each frame, the way
 * {@link Renderer.Camera} is reused inside the engine, so that walking about allocates nothing.
 */
public final class View {
    /** Where the eye is, in metres. {@link #eye} is its height above the map's zero, not above the
     *  floor - the engine does not know how tall anybody is. */
    public double x, y, eye;
    /** Which way it faces, in radians, measured the way {@code Math.atan2(dy, dx)} does. */
    public double heading;
    /** How far up (positive) or down it tilts, in radians. The engine clamps this to what the map
     *  and the render buffer allow; see {@link Host#pitchLimit()}. */
    public double pitch;
    /** Project by straight-line distance instead of perpendicular distance: the classic fisheye,
     *  kept as a comparison mode rather than as something a game would want. */
    public boolean fisheye;
    /** Shade from the baked lightmaps rather than the flat model. Ignored when there is no bake
     *  (--flat), so a game may leave it on. */
    public boolean baked = true;
    /** Fill in the depth and albedo buffers as well as the picture. Only a screenshot wants them;
     *  they cost a frame's worth of writes. */
    public boolean captureDepth;

    /** A copy, for anything that wants to move a view about without disturbing the game's own. */
    public View copy() {
        View v = new View();
        v.x = x;
        v.y = y;
        v.eye = eye;
        v.heading = heading;
        v.pitch = pitch;
        v.fisheye = fisheye;
        v.baked = baked;
        v.captureDepth = captureDepth;
        return v;
    }
}

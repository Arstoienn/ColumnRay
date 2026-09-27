package engine;

import java.awt.Graphics2D;

/**
 * The window under a running game: where the finished picture goes, and where the controls come from.
 *
 * {@link Host} owns the buffers, the frame loop and the arithmetic that decides what a frame looks
 * like. None of that depends on which toolkit put a window on the screen, and until this interface
 * existed the two were the same class - thirty-one AWT references sitting among the pitch warp and
 * the resolution ladder. What is behind this line is a window, a pointer and a set of held keys;
 * what is in front of it is the engine.
 *
 * The seam is drawn where it is because of {@link Host#drawFrame}: a screenshot draws the very same
 * overlay into an image with no window anywhere (see {@code Capture}), so what to paint belongs to
 * the engine and only where to paint it belongs here. A surface is handed a {@link Painter} and
 * gives it a {@link Graphics2D} over its drawable; it never decides what goes in one.
 *
 * {@link SurfaceAwt} is the implementation this engine has always had, moved rather than rewritten.
 * A GLFW one is the reason the interface exists: it answers {@link #pump()} with real work, it can
 * hold the pointer without {@link Pointer}'s warp, and it reaches Linux, which has no backend today.
 */
interface Surface extends AutoCloseable {

    /** What a surface tells the engine, as it happens rather than when it is asked. */
    interface Events {
        /** The hand moved this far, in drawable pixels: a drag, or mouse look with no button held. */
        void looked(int dx, int dy);

        /** The pointer is over this point of the drawable, or (-1, -1) when it is over none of it. */
        void hover(int x, int y);

        /** The window was asked to close. */
        void closed();
    }

    /** Paints one frame into a drawable whose origin is its own top-left corner. */
    interface Painter {
        void paint(Graphics2D g);
    }

    /**
     * Put a window on the screen and start reporting to {@code to}.
     *
     * {@code winW} by {@code winH} is what the command line asked for, not a promise: a surface
     * fits that onto the screen it actually has, and {@link #width()} is the answer.
     */
    void open(String title, int winW, int winH, RayView rayView, Events to);

    /** The drawable's width in pixels, which is the coordinate space {@link Events#hover} reports in. */
    int width();

    /** The drawable's height in pixels. */
    int height();

    /** Paint one frame and show it. */
    void present(Painter p);

    /**
     * Let the window system do its work, once a frame.
     *
     * AWT has a thread of its own for this and needs nothing; GLFW does not, and its events arrive
     * only while somebody is asking for them. The call is here so that the loop in {@link Host} is
     * already the shape both of them need.
     */
    void pump();

    /**
     * Is one of our windows the one being used?
     *
     * The key state is read from the whole machine rather than from an event queue, so it means
     * nothing while another application is in front - and a window that is not being used must not
     * be holding the pointer either. AWT events came with this for free and the engine still needs
     * to ask it out loud.
     */
    boolean active();

    /** Hold the pointer and report movement as {@link Events#looked}, or give it back. */
    void mouseLook(boolean on);

    @Override
    void close();
}

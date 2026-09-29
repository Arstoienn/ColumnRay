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
 * {@link SurfaceGlfw} is the only implementation. The AWT window the engine had from the start sat
 * behind this line too until 2026-09-28: off macOS it could not read the keyboard, and on macOS it
 * could not have the card once GLFW owned the context.
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
     * A second window beside the main one, which is what the ray view is.
     *
     * It is a debugging window, shut until somebody asks for it - redrawing it every frame costs
     * about five milliseconds - so it opens hidden and {@link #show} is how it arrives. Like the
     * main drawable it is painted through a {@link Painter}: {@code RayView.draw} was already
     * toolkit-free, and this is the only part of that class that was not.
     */
    interface Panel {
        int width();

        int height();

        void present(Painter p);

        boolean visible();

        void show(boolean on);

        /** The wheel turned this far, positive away from the hand. The ray view zooms with it. */
        void onWheel(java.util.function.DoubleConsumer to);

        void close();
    }

    /**
     * Put a window on the screen and start reporting to {@code to}.
     *
     * {@code winW} by {@code winH} is what the command line asked for, not a promise: a surface
     * fits that onto the screen it actually has, and {@link #width()} is the answer.
     */
    void open(String title, int winW, int winH, Events to);

    /** The drawable's width in pixels, which is the coordinate space {@link Events#hover} reports in. */
    int width();

    /** The drawable's height in pixels. */
    int height();

    /**
     * Show one frame: the picture, and then whatever the game draws over it.
     *
     * The two are handed over separately because they cost very differently. The picture is
     * {@code pw} by {@code ph} pixels of packed ARGB that want to appear in the rectangle
     * {@code ox, oy, dw, dh} - and on a surface with a graphics card under it, scaling it is free
     * there and dear anywhere else. Rasterising it into the drawable instead cost 9.77 ms a frame
     * against AWT's 1.21 when this was one painter, on a Retina drawable four times the size of
     * the picture. The overlay is small, changes every frame, and is a {@link Painter} because
     * {@code Game.overlay} is a Graphics2D and a screenshot draws the same thing with no window.
     */
    void present(int[] picture, int pw, int ph, int ox, int oy, int dw, int dh, Painter overlay);

    /**
     * The same, for a picture that is already on the card: {@code texture} is a name in the
     * engine's GL context, which every window shares, so there is nothing to upload. This is what
     * the frame arrives as once the pitch warp runs there too (see {@link GpuWarp}).
     */
    void present(int texture, int ox, int oy, int dw, int dh, Painter overlay);

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

    /**
     * Open a second window beside the main one, hidden, {@code width} wide and as tall as the
     * main window is.
     */
    Panel panel(String title, int width);

    @Override
    void close();
}

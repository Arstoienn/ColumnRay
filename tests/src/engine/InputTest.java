package engine;

/**
 * The controls, as far as they can be asked about without a keyboard.
 *
 * What a key does is the game's business now, so what is left to check here is the plumbing under
 * it: that nothing reads as held while another application is in front, and that a frame's mouse
 * movement is handed over once and then gone. Pressing a key cannot be faked - {@link Keys} reads
 * the machine's own key state rather than anything this process could set - so the edge that makes
 * a tap fire once is only checked in the direction that does not need one.
 */
final class InputTest {
    private InputTest() {}

    static void run() {
        Check.group("Input");

        Input in = new Input();
        in.begin(false);                                        // no window of ours is in front
        Check.that(!in.down(Keys.W), "nothing is held while another application has the keyboard");
        Check.that(!in.tapped(Keys.SPACE), "and nothing taps either");

        in.begin(true);
        Check.that(!in.tapped(Keys.SPACE), "a key that is not held does not tap");
        Check.that(!in.tapped(Keys.SPACE), "and asking twice does not make one");

        // The mouse accumulates on the event thread and is taken by the frame that reads it.
        in.dragged(3, -4);
        in.dragged(1, 2);
        in.begin(true);
        Check.eq(in.mouseDX(), 4, 1e-12, "a frame gets everything the mouse did before it");
        Check.eq(in.mouseDY(), -2, 1e-12, "in both directions");
        Check.eq(in.mouseDX(), 4, 1e-12, "and reading it twice in one frame reads the same thing");
        in.begin(true);
        Check.eq(in.mouseDX(), 0, 1e-12, "the next frame does not get it again");
        Check.eq(in.mouseDY(), 0, 1e-12, "in either direction");
    }
}

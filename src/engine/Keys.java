package engine;

import java.awt.event.KeyEvent;
import java.util.Map;

/**
 * Which keys are held, by where they sit rather than by what is printed on them.
 *
 * WASD is a shape on the keyboard, not four letters. A toolkit that names a key by the character it
 * produces puts the shape somewhere else on every layout that is not US QWERTY: on Colemak the key
 * in the S position produces R and the one in the D position produces S, so a game that listened
 * for letters would answer the wrong way round, and on Bopomofo it would hear nothing it knew.
 *
 * So a key here is a position, and the numbers below are macOS virtual key codes because that is
 * what the engine read from the machine when it asked macOS directly. It no longer asks anybody:
 * the window answers, through {@link Source}, and GLFW's key tokens are the same positions -
 * {@code glfwGetKeyScancode(GLFW_KEY_W)} is 13, which is what {@link #W} has always been, and all
 * thirty-three of them agree. {@code SurfaceGlfw.check()} says so out loud if they ever stop.
 *
 * This class used to be three hundred lines of FFM calls for the two questions a window could not
 * answer: {@code CGEventSourceKeyState} for whether a key was down, and {@code UCKeyTranslate}
 * behind Text Services for what a key cap says - the latter asked once before any window existed,
 * because asking it later hung the process, and preceded by {@code TransformProcessType} because
 * the first call into Text Services checked the process in as background-only, which is how the
 * game once ended up taking no keys at all. GLFW answers both, on every platform, and answers the
 * second one **live**: switch to Bopomofo mid-run and the HUD follows, which the old answer,
 * read once at start-up, could not do.
 */
public final class Keys {
    /**
     * The key positions the game uses, as macOS virtual key codes, named by what a US QWERTY board
     * prints on them. These are positions, not letters: {@link #S} is the key below and right of
     * {@link #W} on every keyboard, whether it is labelled S, R or ㄙ.
     */
    public static final int A = 0, S = 1, D = 2, F = 3, H = 4, G = 5, C = 8, V = 9, Q = 12, W = 13, E = 14, R = 15,
            EQUALS = 24, MINUS = 27, RIGHT_BRACKET = 30, LEFT_BRACKET = 33, P = 35, L = 37,
            COMMA = 43, N = 45, M = 46, PERIOD = 47, SPACE = 49, TAB = 48, ESCAPE = 53, SHIFT = 56,
            CONTROL = 59, RIGHT_SHIFT = 60, RIGHT_CONTROL = 62,
            LEFT = 123, RIGHT = 124, DOWN = 125, UP = 126;

    /**
     * A window that can answer about key positions itself.
     *
     * Installed by the window while it is open, so this class never learns which toolkit it is.
     * See {@code SurfaceGlfw}, which holds the one table that pairs a position with GLFW's token
     * for the same place on the board.
     */
    public interface Source {
        /** Is the key in this position held down right now? */
        boolean down(int position);

        /** What this keyboard prints on that position, or null if the window cannot say. */
        String label(int position);
    }

    private static volatile Source source;

    /** Let a window answer the keyboard for as long as it is open; null gives it back. */
    public static void source(Source s) { source = s; }

    /** Is anything answering? False before a window is open, and in a headless run, where the
     *  question never comes up because nothing is being played. */
    public static boolean physical() { return source != null; }

    /** Is the key in this position held down right now? */
    public static boolean down(int key) {
        Source s = source;
        return s != null && s.down(key);
    }

    /**
     * How this keyboard labels the key in this position, e.g. "R" for {@link #S} on Colemak.
     *
     * The window is asked first and answers from the layout in force at this moment. It answers
     * nothing for a position that prints nothing - Space, Shift, the arrows - and those are where
     * they are on every layout, so the name below is right for them.
     */
    public static String label(int key) {
        Source s = source;
        if (s != null) {
            String named = s.label(key);
            if (named != null) return named;
        }
        return KeyEvent.getKeyText(VK.getOrDefault(key, KeyEvent.VK_UNDEFINED));
    }

    /** Every position spelled out, for a hint like "WASD". */
    public static String labels(int... keys) {
        StringBuilder out = new StringBuilder();
        for (int k : keys) out.append(label(k));
        return out.toString();
    }

    /**
     * A name for each position, for the keys no layout prints anything on.
     *
     * AWT's key codes are used only as an index into its own table of names, which is where
     * "Space", "Shift" and "Left" come from and is all that is wanted here. Nothing reads a key
     * through AWT any more.
     */
    private static final Map<Integer, Integer> VK = Map.ofEntries(
            Map.entry(A, KeyEvent.VK_A), Map.entry(S, KeyEvent.VK_S), Map.entry(D, KeyEvent.VK_D),
            Map.entry(F, KeyEvent.VK_F), Map.entry(G, KeyEvent.VK_G), Map.entry(H, KeyEvent.VK_H),
            Map.entry(C, KeyEvent.VK_C), Map.entry(V, KeyEvent.VK_V),
            Map.entry(Q, KeyEvent.VK_Q), Map.entry(W, KeyEvent.VK_W), Map.entry(E, KeyEvent.VK_E),
            Map.entry(R, KeyEvent.VK_R), Map.entry(P, KeyEvent.VK_P), Map.entry(L, KeyEvent.VK_L),
            Map.entry(N, KeyEvent.VK_N), Map.entry(M, KeyEvent.VK_M),
            Map.entry(EQUALS, KeyEvent.VK_EQUALS), Map.entry(MINUS, KeyEvent.VK_MINUS),
            Map.entry(LEFT_BRACKET, KeyEvent.VK_OPEN_BRACKET), Map.entry(RIGHT_BRACKET, KeyEvent.VK_CLOSE_BRACKET),
            Map.entry(COMMA, KeyEvent.VK_COMMA), Map.entry(PERIOD, KeyEvent.VK_PERIOD),
            Map.entry(SPACE, KeyEvent.VK_SPACE), Map.entry(TAB, KeyEvent.VK_TAB),
            Map.entry(ESCAPE, KeyEvent.VK_ESCAPE),
            Map.entry(SHIFT, KeyEvent.VK_SHIFT), Map.entry(RIGHT_SHIFT, KeyEvent.VK_SHIFT),
            Map.entry(CONTROL, KeyEvent.VK_CONTROL), Map.entry(RIGHT_CONTROL, KeyEvent.VK_CONTROL),
            Map.entry(LEFT, KeyEvent.VK_LEFT), Map.entry(RIGHT, KeyEvent.VK_RIGHT),
            Map.entry(DOWN, KeyEvent.VK_DOWN), Map.entry(UP, KeyEvent.VK_UP));

    private Keys() { }
}

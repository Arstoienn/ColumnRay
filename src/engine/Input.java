package engine;

import java.util.HashSet;
import java.util.Set;

/**
 * What the keyboard and the mouse did this frame, with no opinion about what any of it means.
 *
 * The engine reads the keys and collects the mouse; which key walks forward and which one opens
 * the map is the game's business, and nothing here knows. That is the whole point of the split:
 * a game rebinds its controls by reading different positions out of {@link Keys}, not by editing
 * the engine.
 *
 * Keys are positions rather than letters (see {@link Keys}), so {@code down(Keys.W)} means the key
 * where W sits on a US keyboard whatever this machine's layout prints on it.
 */
public final class Input {
    private final Set<Integer> heldLastFrame = new HashSet<>();  // so a tap fires once, not every frame
    private boolean listening;
    private double pendingDX, pendingDY;   // written on the event thread as the mouse is dragged
    private double dx, dy;                 // this frame's share of it

    Input() { }

    /** Take this frame's mouse movement, and say whether a window of ours is in front: the key
     *  state comes from the whole machine, not from our windows, so it is ignored when it is not. */
    synchronized void begin(boolean listening) {
        this.listening = listening;
        dx = pendingDX;
        dy = pendingDY;
        pendingDX = pendingDY = 0;
    }

    /** The mouse was dragged this far, in window pixels. Called on the event thread. */
    synchronized void dragged(double ddx, double ddy) {
        pendingDX += ddx;
        pendingDY += ddy;
    }

    /** Is the key in this position held down? */
    public boolean down(int key) {
        return listening && Keys.down(key);
    }

    /**
     * Did the key in this position go down since the last frame?
     *
     * For the things that happen once rather than for as long as a key is held - a toggle, a door,
     * a weapon. Ask at most once a frame per key: the answer is what moves the edge on.
     */
    public boolean tapped(int key) {
        if (!down(key)) {
            heldLastFrame.remove(key);
            return false;
        }
        return heldLastFrame.add(key);                            // false: still held from last frame
    }

    /** How far the mouse was dragged this frame, in window pixels. */
    public synchronized double mouseDX() { return dx; }

    public synchronized double mouseDY() { return dy; }
}

package engine;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * The AWT window: a {@code JFrame} with a {@code Canvas} in it, drawn through a buffer strategy.
 *
 * This is what {@link Host} did itself until the {@link Surface} seam was cut, moved rather than
 * rewritten - the mouse-look warp, the foreground request and the buffer-strategy retry loop are
 * the same code, and the behaviour is meant to be identical to the line.
 *
 * Two things here are macOS working around macOS. {@code Desktop.requestForeground} is because
 * {@code toFront()} orders our own windows but does not make us the active application, so a game
 * started from a shell that is not in front opens behind it and every key goes elsewhere. And mouse
 * look is a warp: the pointer is put back at the middle of the canvas after every event it reports,
 * so the next event's distance from the middle is how far the hand moved. Both are the kind of
 * thing GLFW does in one call, which is the point of the interface.
 */
final class SurfaceAwt implements Surface {
    private JFrame frame;
    private Canvas canvas;
    private RayView rayView;
    private Events to;
    private volatile boolean mouseLook;
    private static Cursor blank;

    @Override
    public void open(String title, int winW, int winH, RayView rayView, Events to) {
        this.rayView = rayView;
        this.to = to;
        Canvas c = new Canvas();
        c.enableInputMethods(false);                             // an IME (Bopomofo, Pinyin) must not eat the keys
        this.canvas = c;
        try {
            SwingUtilities.invokeAndWait(() -> {
                // The window is the output: --window, fitted onto the screen, whatever is being
                // rendered. present() scales the picture up into it, so a bigger window costs no
                // rays. The ray view opens beside it, hidden until the game asks for it.
                Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                int rayW = (int) Math.min(640, screen.width * 0.36);
                double fit = Math.min(1, Math.min((screen.width - 16) / (double) winW,
                        (screen.height - 48) / (double) winH));
                JFrame f = new JFrame(title);
                c.setPreferredSize(new Dimension((int) (winW * fit), (int) (winH * fit)));
                c.setIgnoreRepaint(true);
                c.setFocusTraversalKeysEnabled(false);
                f.add(c);
                f.pack();
                f.setLocation(screen.x + 8, screen.y + 8);
                f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                f.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent e) { to.closed(); }
                });
                f.setVisible(true);
                c.createBufferStrategy(2);
                installInput(c);
                rayView.open(f, rayW);
                f.toFront();
                // toFront() only orders our windows; on macOS it does not make us the active app,
                // so a game started from a shell that is not in front (an IDE, a script) opens
                // behind it and every key goes to whatever is. Ask the OS to bring the app forward.
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND))
                    Desktop.getDesktop().requestForeground(true);
                c.requestFocus();
                this.frame = f;
            });
        } catch (Exception cannotOpen) {
            throw new IllegalStateException("could not open the window", cannotOpen);
        }
    }

    @Override public int width() { return canvas.getWidth(); }

    @Override public int height() { return canvas.getHeight(); }

    @Override
    public void present(Painter p) {
        BufferStrategy bs = canvas.getBufferStrategy();
        do {
            do {
                Graphics2D g = (Graphics2D) bs.getDrawGraphics();
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
                p.paint(g);
                g.dispose();
            } while (bs.contentsRestored());
            bs.show();
        } while (bs.contentsLost());
        Toolkit.getDefaultToolkit().sync();
    }

    /** AWT pumps its own events on a thread of its own; there is nothing to do here. */
    @Override public void pump() {}

    @Override
    public boolean active() {
        for (Window w : Window.getWindows()) if (w.isActive()) return true;
        return false;
    }

    @Override
    public void mouseLook(boolean on) {
        mouseLook = on;
        Canvas c = canvas;
        if (c != null) SwingUtilities.invokeLater(() -> {
            c.setCursor(on ? blankCursor() : Cursor.getDefaultCursor());
            if (on) recentre();
        });
    }

    @Override
    public void close() {
        try {
            SwingUtilities.invokeAndWait(() -> {
                rayView.close();
                for (Window w : Window.getWindows()) w.dispose();
            });
        } catch (Exception alreadyGone) {
            // Shutting down. A window that will not dispose cannot stop the process from ending.
        }
    }

    private void installInput(Canvas c) {
        c.addKeyListener(Keys.listener());
        MouseAdapter drag = new MouseAdapter() {
            int lx, ly;
            @Override public void mousePressed(MouseEvent e) {
                lx = e.getX();
                ly = e.getY();
                c.requestFocus();
                if (mouseLook) recentre();                       // a click is also how a game gets the pointer back
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (mouseLook) { looked(e); return; }
                to.looked(e.getX() - lx, e.getY() - ly);
                lx = e.getX();
                ly = e.getY();
            }
            @Override public void mouseMoved(MouseEvent e) {
                if (mouseLook) { looked(e); return; }
                to.hover(e.getX(), e.getY());
            }
            @Override public void mouseExited(MouseEvent e) { if (!mouseLook) to.hover(-1, -1); }
        };
        c.addMouseListener(drag);
        c.addMouseMotionListener(drag);
        // Let go of the pointer the moment another application wants it, and take it again when the
        // game comes back: a window that is not in front holding the pointer at its own middle is
        // a window nobody can get away from.
        c.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                if (mouseLook) { c.setCursor(blankCursor()); recentre(); }
            }
            @Override public void focusLost(FocusEvent e) {
                c.setCursor(Cursor.getDefaultCursor());
            }
        });
        if (mouseLook) {
            c.setCursor(blankCursor());
            recentre();
        }
    }

    /**
     * One mouse event while the pointer is being held still: how far the hand moved is how far the
     * event landed from the middle of the window, and then the pointer goes back to the middle.
     *
     * An event exactly at the middle is the warp's own arrival coming back round, and counts for
     * nothing - without that the view would drift, since the pointer is put back on every event
     * and each of those reports itself.
     */
    private void looked(MouseEvent e) {
        // Not while another application is in front: a window that is not being used holding the
        // pointer at its own middle is a window nobody can get the mouse away from. The keys are
        // ignored under the same condition, in the loop.
        if (!active()) return;
        int dx = e.getX() - canvas.getWidth() / 2, dy = e.getY() - canvas.getHeight() / 2;
        if (dx == 0 && dy == 0) return;
        to.looked(dx, dy);
        recentre();
    }

    /** Put the pointer back at the middle of the canvas, which is where mouse look measures from. */
    private void recentre() {
        Canvas c = canvas;
        if (c == null || !c.isShowing()) return;
        Point at = c.getLocationOnScreen();
        // The middle to the pixel, and the same arithmetic the deltas are measured with: warping
        // half a pixel off would make the event that comes back read as a flick of one, every time.
        Pointer.moveTo(at.x + c.getWidth() / 2, at.y + c.getHeight() / 2);
    }

    /** A cursor with nothing in it: the pointer is still there, it just must not be seen. */
    private static Cursor blankCursor() {
        if (blank == null) {
            BufferedImage dot = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            blank = Toolkit.getDefaultToolkit().createCustomCursor(dot, new Point(0, 0), "blank");
        }
        return blank;
    }
}

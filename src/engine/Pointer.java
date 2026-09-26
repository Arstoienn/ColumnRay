package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.NoSuchElementException;

/**
 * Putting the mouse pointer back where it was, which is how a mouse looks about instead of pointing.
 *
 * A window is told where the pointer is, never how far the hand moved, so a game that wants the
 * second has to arrange for the first to be the same every time: hold the pointer at the middle of
 * the window, and the next event's distance from the middle is the movement. That is what this is
 * for, and it is the whole of the native part - {@link Host} does the arithmetic.
 *
 * Why not {@link java.awt.Robot}, which can also move the pointer: on macOS it posts a synthetic
 * event, and posting events needs Accessibility permission, which the game does not have and
 * should not ask for. {@code CGWarpMouseCursorPosition} moves the pointer itself and needs no
 * permission at all - the same framework {@link Keys} already reads the key state from.
 *
 * {@code CGAssociateMouseAndMouseCursorPosition(true)} after the warp is not a leftover: macOS
 * damps the movement that arrives in the few milliseconds after a warp, which reads as the view
 * sticking every time the pointer is put back, and re-associating clears it.
 *
 * Off macOS, or on a JVM that will not let us call out, {@link #available} is false and the game
 * falls back to dragging the mouse with a button held - which is what it did before.
 */
final class Pointer {
    private static final MethodHandle WARP, ASSOCIATE;
    /** CGPoint: two CGFloats, passed by value. */
    private static final GroupLayout POINT = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("x"), ValueLayout.JAVA_DOUBLE.withName("y"));

    static {
        MethodHandle warp = null, associate = null;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup app = SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices", Arena.global());
            warp = linker.downcallHandle(app.find("CGWarpMouseCursorPosition").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, POINT));
            associate = linker.downcallHandle(app.find("CGAssociateMouseAndMouseCursorPosition").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN));
        } catch (IllegalCallerException | UnsupportedOperationException | IllegalArgumentException
                 | NoSuchElementException | LinkageError notMacOrNotAllowed) {
            // The same five as in Keys, and for the same reasons. A game that cannot hold the
            // pointer still runs; it looks about by dragging.
            warp = null;
        }
        WARP = warp;
        ASSOCIATE = associate;
    }

    private Pointer() { }

    /** Can the pointer be held in place on this machine? */
    static boolean available() { return WARP != null; }

    /** Put the pointer at this point on the screen, in the screen coordinates AWT reports. */
    static void moveTo(double screenX, double screenY) {
        if (WARP == null) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment point = arena.allocate(POINT);
            point.set(ValueLayout.JAVA_DOUBLE, 0, screenX);
            point.set(ValueLayout.JAVA_DOUBLE, 8, screenY);
            int err = (int) WARP.invokeExact(point);
            if (err == 0) {
                int ignored = (int) ASSOCIATE.invokeExact(true);
            }
        } catch (Throwable notMoved) {
            if (notMoved instanceof VirtualMachineError e) throw e;
            // The pointer stayed where it was: the view stops turning, the game carries on.
        }
    }
}

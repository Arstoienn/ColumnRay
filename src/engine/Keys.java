package engine;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which keys are held, by where they sit rather than by what is printed on them.
 *
 * WASD is a shape on the keyboard, not four letters, and AWT will not say which key was pressed -
 * on macOS it works out the key code for a letter key from the character the key produces under the
 * current layout, so on Colemak the key in the S position arrives as R and the one in the D
 * position as S. The game would answer the wrong way round.
 *
 * So do not go through AWT at all. macOS gives every key position a fixed number - the scan code the
 * hardware sends, 13 for the key a US board prints W on, whatever any layout later makes of it - and
 * {@code CGEventSourceKeyState} answers whether the key at a given number is down right now. The
 * game asks that once a frame for the keys it cares about. Nothing has to know what layout is
 * selected, so nothing has to keep asking: switch to Bopomofo mid-jump and the same keys keep
 * working, because the question was never about letters.
 *
 * {@link #label} is the one place the layout still matters, and it goes the other way: given a
 * position, {@code UCKeyTranslate} says what this keyboard prints on it, so the HUD can name the
 * keys as they are actually labelled. That question is asked once, from {@link #readLabels} before
 * the windows go up, and the answers are kept - see there for why it cannot be asked later.
 *
 * Off macOS, or on a JVM that will not let us call out, {@link #listener} keeps a set of held keys
 * from AWT events instead, read as a plain US QWERTY board - which is what everything did before.
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

    /** kCGEventSourceStateCombinedSessionState: real keys plus anything Robot is typing for us. */
    private static final int SESSION_STATE = 0;

    private static final MethodHandle KEY_STATE, INPUT_SOURCE, SOURCE_PROPERTY, DATA_BYTES,
            KEYBOARD_TYPE, TRANSLATE, RELEASE, TRANSFORM;
    /** The CFStringRef naming the property that holds a layout's key table. */
    private static final MemorySegment LAYOUT_DATA;

    /** Held keys off the AWT events, for when the machine will not tell us directly. */
    private static final Set<Integer> held = ConcurrentHashMap.newKeySet();

    /** What this keyboard prints on each position, filled in once by {@link #readLabels}. */
    private static final Map<Integer, String> labels = new HashMap<>();

    static {
        MethodHandle keyState = null, inputSource = null, sourceProperty = null, dataBytes = null,
                keyboardType = null, translate = null, release = null, transform = null;
        MemorySegment layoutData = null;
        Linker linker = Linker.nativeLinker();
        ValueLayout.OfInt i32 = ValueLayout.JAVA_INT;
        ValueLayout.OfShort i16 = ValueLayout.JAVA_SHORT;
        try {
            SymbolLookup app = SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices", Arena.global());
            keyState = linker.downcallHandle(app.find("CGEventSourceKeyState").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, i32, i16));
            transform = linker.downcallHandle(app.find("TransformProcessType").orElseThrow(),
                    FunctionDescriptor.of(i32, ValueLayout.ADDRESS, i32));
        } catch (IllegalCallerException | UnsupportedOperationException | IllegalArgumentException
                 | NoSuchElementException | LinkageError notMacOrNotAllowed) {
            // another OS, a JVM with native access shut off, a framework that moved: fall back to AWT.
            // Each of those is one of these five; anything else is this code being wrong, and a
            // renderer that will not start says so far more usefully than keys that do nothing.
            keyState = null;
        }
        try {
            SymbolLookup carbon = SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/Carbon.framework/Carbon", Arena.global());
            SymbolLookup cf = SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Arena.global());
            inputSource = linker.downcallHandle(carbon.find("TISCopyCurrentKeyboardLayoutInputSource").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            sourceProperty = linker.downcallHandle(carbon.find("TISGetInputSourceProperty").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            dataBytes = linker.downcallHandle(cf.find("CFDataGetBytePtr").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            release = linker.downcallHandle(cf.find("CFRelease").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            keyboardType = linker.downcallHandle(carbon.find("LMGetKbdType").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_BYTE));
            translate = linker.downcallHandle(carbon.find("UCKeyTranslate").orElseThrow(),
                    FunctionDescriptor.of(i32, ValueLayout.ADDRESS, i16, i16, i32, i32, i32,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            layoutData = carbon.find("kTISPropertyUnicodeKeyLayoutData").orElseThrow()
                    .reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0);
        } catch (IllegalCallerException | UnsupportedOperationException | IllegalArgumentException
                 | NoSuchElementException | LinkageError noLayoutToRead) {
            // the HUD falls back to naming the keys as a US board would
            translate = null;
        }
        KEY_STATE = keyState;
        INPUT_SOURCE = inputSource;
        SOURCE_PROPERTY = sourceProperty;
        DATA_BYTES = dataBytes;
        KEYBOARD_TYPE = keyboardType;
        TRANSLATE = translate;
        RELEASE = release;
        TRANSFORM = transform;
        LAYOUT_DATA = layoutData;
    }

    private Keys() { }

    /** Are we reading key positions from the machine, rather than guessing from AWT key codes? */
    public static boolean physical() { return KEY_STATE != null; }

    /**
     * Set once a key event arrives while the machine says no key at all is held. macOS answers the
     * key-state question only for an app with Input Monitoring permission, and it does not refuse -
     * it says "not held", for every key, forever. Started from an app without that permission (the
     * game launched from a desktop app rather than Terminal) the mouse turned the view and
     * WASD did nothing. An AWT key-pressed event arrives while its key is physically down, so if
     * nothing reads as down at that moment the answer is dead, and the AWT keys take over.
     *
     * Not both at once: AWT names a key by the layout, so on Colemak the S position arrives as R,
     * and adding those in would toggle the ray view on every step backwards.
     */
    private static volatile boolean nativeDead;

    /** Is the key in this position held down right now? */
    public static boolean down(int key) {
        if (KEY_STATE == null || nativeDead) return held.contains(key);
        return nativeDown(key);
    }

    /**
     * A native call through a {@link MethodHandle} is declared to throw {@code Throwable}, so the
     * three calls below cannot be caught any more narrowly than that however much one would like
     * to. What can still be done is to put back the ones that were never about the keyboard: an
     * OutOfMemoryError or a StackOverflowError is the JVM in trouble, and swallowing it here turns
     * a machine running out of memory into a key that mysteriously stopped working.
     */
    private static void rethrowIfNotNative(Throwable t) {
        if (t instanceof VirtualMachineError e) throw e;
    }

    private static boolean nativeDown(int key) {
        try {
            return (boolean) KEY_STATE.invokeExact(SESSION_STATE, (short) key);
        } catch (Throwable notAnswering) {
            rethrowIfNotNative(notAnswering);
            return false;
        }
    }

    /** Feeds the AWT set, and notices when the machine's own key state is not answering. */
    static KeyAdapter listener() {
        return new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                held.add(position(e));
                if (KEY_STATE != null && !nativeDead) {
                    boolean any = false;
                    for (int k : VK.keySet()) any |= nativeDown(k);
                    if (!any) {
                        nativeDead = true;
                        System.err.println("keys: macOS reports no key held (no Input Monitoring permission?) - using AWT key events");
                    }
                }
            }
            @Override public void keyReleased(KeyEvent e) { held.remove(position(e)); }
        };
    }

    /**
     * Read the key caps, once, before any window exists - see {@link #label}.
     *
     * Reading them means asking Text Services, which belongs to the process's first thread: ask
     * from the game loop once the window is up and the process is trapped on the spot, no stack
     * trace, no exception to catch. So ask before AWT starts, and keep the answers. Nothing is lost
     * by that - what a key does is a position and never changes; only its name comes from the
     * layout, and a HUD hint that still says R after a switch to Bopomofo is no great harm.
     */
    public static void readLabels() {
        foreground();
        for (int key : VK.keySet()) {
            char c = printed(key);
            labels.put(key, c != 0 ? String.valueOf(Character.toUpperCase(c))
                    : KeyEvent.getKeyText(VK.get(key)));
            if (c != 0) {
                PRINTING.add(key);
                int vk = KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(c));
                if (vk != KeyEvent.VK_UNDEFINED) LAYOUT.putIfAbsent(vk, key);
            }
        }
    }

    /**
     * Make this process an ordinary app with a Dock icon and a menu bar, before Text Services is asked
     * anything. The first call into it checks the process in with the system, and a process that has
     * no window yet checks in as background-only - a type AWT does not undo when the window opens. A
     * background-only app is never the active one: clicking its window does nothing and every key goes
     * to whichever app was in front before. Declaring the type first is what AWT would have done.
     */
    private static void foreground() {
        if (TRANSFORM == null) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment current = arena.allocate(8);           // ProcessSerialNumber kCurrentProcess = {0, 2}
            current.set(ValueLayout.JAVA_INT, 0, 0);
            current.set(ValueLayout.JAVA_INT, 4, 2);
            int status = (int) TRANSFORM.invokeExact(current, 1); // kProcessTransformToForegroundApplication
            if (status != 0) System.err.println("keys: TransformProcessType returned " + status);
        } catch (Throwable notTransformed) {
            rethrowIfNotNative(notTransformed);
            // not fatal: the game still runs, the window may just need clicking
        }
    }

    /** How this keyboard labels the key in this position, e.g. "R" for {@link #S} on Colemak. */
    public static String label(int key) {
        String read = labels.get(key);
        return read != null ? read : KeyEvent.getKeyText(VK.getOrDefault(key, KeyEvent.VK_UNDEFINED));
    }

    /** Every position spelled out, for a hint like "WASD". */
    public static String labels(int... keys) {
        StringBuilder out = new StringBuilder();
        for (int k : keys) out.append(label(k));
        return out.toString();
    }

    /**
     * The character the current layout prints on a position, or 0 if it prints nothing we can show.
     * kUCKeyActionDisplay with no modifiers is what the key cap says.
     */
    private static char printed(int key) {
        if (TRANSLATE == null) return 0;
        MemorySegment source = MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            source = (MemorySegment) INPUT_SOURCE.invokeExact();
            if (source.equals(MemorySegment.NULL)) return 0;
            MemorySegment data = (MemorySegment) SOURCE_PROPERTY.invokeExact(source, LAYOUT_DATA);
            if (data.equals(MemorySegment.NULL)) return 0;
            MemorySegment table = (MemorySegment) DATA_BYTES.invokeExact(data);
            MemorySegment dead = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment length = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment out = arena.allocate(ValueLayout.JAVA_CHAR, 4);
            byte type = (byte) KEYBOARD_TYPE.invokeExact();
            int err = (int) TRANSLATE.invokeExact(table, (short) key, (short) 3 /* kUCKeyActionDisplay */,
                    0, type & 0xff, 1 /* kUCKeyTranslateNoDeadKeysBit */, dead, 4L, length, out);
            if (err != 0 || length.get(ValueLayout.JAVA_LONG, 0) != 1) return 0;
            char c = out.getAtIndex(ValueLayout.JAVA_CHAR, 0);
            return c > ' ' && c < 0x7f ? c : 0;                  // space, arrows, dead keys: use the name instead
        } catch (Throwable noLabelToRead) {
            rethrowIfNotNative(noLabelToRead);
            return 0;
        } finally {
            if (!source.equals(MemorySegment.NULL)) {
                try {
                    RELEASE.invokeExact(source);                 // TISCopy... hands over a reference
                } catch (Throwable notReleased) {
                    rethrowIfNotNative(notReleased);
                    // nothing to do about a leaked input source
                }
            }
        }
    }

    /** The AWT key code each position carries on a US QWERTY board. */
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

    /** The same the other way round, so a fallback AWT event can be filed under a position. */
    private static final Map<Integer, Integer> POSITION = new HashMap<>();

    static {
        VK.forEach((position, vk) -> POSITION.putIfAbsent(vk, position));
    }

    /**
     * The AWT fallback, filed under positions by the layout that was read. AWT on macOS names a letter
     * key by what the layout prints on it and carries no position at all (its rawCode is only filled
     * in on X11), so on Colemak the S position arrives as VK_R. Turned round through the key caps
     * readLabels() read, VK_R is the position that prints R - S, which is where the game wants it.
     * A character no tracked position prints is not one of ours: on Colemak VK_G comes from the T
     * position, and filing it under the QWERTY G would toggle something from a key that looks unrelated.
     * Keys that print nothing - Space, Shift, the arrows - are where they are on every layout.
     */
    private static final Map<Integer, Integer> LAYOUT = new HashMap<>();
    private static final Set<Integer> PRINTING = new java.util.HashSet<>();

    private static int position(KeyEvent e) {
        int code = e.getKeyCode();
        Integer byLayout = LAYOUT.get(code);
        if (byLayout != null) return byLayout;
        Integer qwerty = POSITION.get(code);
        if (qwerty == null) return -1;
        return LAYOUT.isEmpty() || !PRINTING.contains(qwerty) ? qwerty : -1;
    }
}

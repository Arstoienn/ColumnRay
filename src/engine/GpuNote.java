package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

/**
 * Whether this machine has a graphics card the frame did not go to.
 *
 * One card is one card and there is nothing to say. Two is a trap: the renderer string names one of
 * them, the frame is drawn, everything looks like it worked, and the number at the bottom of
 * {@code --bench} is the wrong card's. A desktop with an Intel chip and a card beside it is the
 * ordinary case, and Windows decides which one a process gets from a setting nobody looks at.
 *
 * This is all that survives of {@code GlWgl}, which used to make the context here as well. GLFW
 * makes the context now, on every platform, and it has nothing to say about adapters - so the one
 * piece worth keeping is the warning, and only Windows can answer it. Everywhere else, and on any
 * machine that will not let the call out, the answer is silence rather than a wrong reassurance.
 */
final class GpuNote {
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;

    /** DISPLAY_DEVICEW: the size of the struct, and where the name and the flags sit in it. */
    private static final long DD_SIZE = 840, DD_CB = 0, DD_STRING = 68, DD_FLAGS = 324;
    private static final int DISPLAY_ATTACHED = 0x00000001;

    private static final MethodHandle ENUM_DEVICES = find();

    private GpuNote() {}

    private static MethodHandle find() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
        try {
            SymbolLookup user = SymbolLookup.libraryLookup("user32.dll", Arena.global());
            return Linker.nativeLinker().downcallHandle(user.find("EnumDisplayDevicesW").orElseThrow(),
                    FunctionDescriptor.of(I32, ValueLayout.ADDRESS, I32, ValueLayout.ADDRESS, I32));
        } catch (IllegalCallerException | UnsupportedOperationException | IllegalArgumentException
                 | NoSuchElementException | LinkageError cannotAsk) {
            return null;
        }
    }

    /**
     * What is worth saying about the card that answered, or null.
     *
     * {@code renderer} is what {@link Gl#device()} said. The renderer string and the adapter string
     * are written by different people - "NVIDIA GeForce RTX 4070" against
     * "NVIDIA GeForce RTX 4070/PCIe/SSE2" - so the match is on the first word, which is the vendor
     * and is the part that differs between two cards in one machine.
     */
    static String of(String renderer) {
        if (ENUM_DEVICES == null) return null;
        StringBuilder others = new StringBuilder();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment device = arena.allocate(DD_SIZE);
            for (int i = 0; ; i++) {
                device.fill((byte) 0);
                device.set(I32, DD_CB, (int) DD_SIZE);
                if ((int) ENUM_DEVICES.invokeExact(MemorySegment.NULL, i, device, 0) == 0) break;
                if ((device.get(I32, DD_FLAGS) & DISPLAY_ATTACHED) == 0) continue;
                String adapter = device.getString(DD_STRING, StandardCharsets.UTF_16LE);
                String vendor = adapter.split(" ")[0];
                if (renderer.contains(vendor) || others.indexOf(adapter) >= 0) continue;
                others.append(others.isEmpty() ? "" : ", ").append(adapter);
            }
        } catch (Throwable notAnswering) {
            if (notAnswering instanceof VirtualMachineError e) throw e;
            return null;                                         // a warning is not worth a crash
        }
        if (others.isEmpty()) return null;
        return "this machine also has " + others + ". OpenGL takes the card Windows prefers for "
                + "java.exe, not the one the window is on: Settings > System > Display > Graphics";
    }
}

package engine;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * The whole test framework.
 *
 * The engine takes no dependency on anything outside the JDK, and its tests are held to the same
 * rule: a test is a static method, a failure is a line on stdout, and the exit status is what a
 * CI job reads. Nothing here is clever enough to need testing itself.
 *
 * Tests live in package {@code engine} but under {@code tests/src}, so they can reach the
 * package-private classes they are checking without any of them being opened up for the purpose,
 * and so that {@code build.sh} never compiles them into the engine's own {@code out/}.
 */
final class Check {
    private Check() {}

    static int checks, failed;
    private static String group = "";

    static void group(String name) {
        group = name;
    }

    static void that(boolean ok, String what) {
        checks++;
        if (ok) return;
        failed++;
        System.out.println("FAIL  " + group + ": " + what);
    }

    static void eq(int got, int want, String what) {
        that(got == want, what + " (wanted " + want + ", got " + got + ")");
    }

    static void eq(Object got, Object want, String what) {
        that(want == null ? got == null : want.equals(got),
                what + " (wanted " + want + ", got " + got + ")");
    }

    static void eq(double got, double want, double tol, String what) {
        that(Math.abs(got - want) <= tol, what + " (wanted " + want + " +/- " + tol + ", got " + got + ")");
    }

    static void eq(int[] got, int[] want, String what) {
        that(Arrays.equals(got, want),
                what + " (wanted " + Arrays.toString(want) + ", got " + Arrays.toString(got) + ")");
    }

    /** The call must fail, and its message must name the problem. */
    static void rejects(Supplier<?> call, String messagePart, String what) {
        checks++;
        try {
            Object v = call.get();
            failed++;
            System.out.println("FAIL  " + group + ": " + what + " (wanted a rejection, got " + v + ")");
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains(messagePart)) return;
            failed++;
            System.out.println("FAIL  " + group + ": " + what
                    + " (rejected, but for '" + m + "', which does not mention '" + messagePart + "')");
        }
    }
}

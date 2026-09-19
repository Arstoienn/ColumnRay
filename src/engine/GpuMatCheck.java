package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Does {@link GlMaterials} agree with {@link Materials}?
 *
 * The GPU path cannot be golden-tested: its arithmetic is float where the CPU's is double, so
 * the two will never hash the same. What can be done is to measure the disagreement and insist
 * it stays small, which is what this does - the same check in spirit as scoring the Haven
 * conversion against Blender, with the CPU renderer as the reference instead.
 *
 * It renders side(m, u, v, w) into a float target over a grid of surface coordinates, at several
 * filter widths, reads it back and compares every sample against the Java function. A shading
 * factor lands on a colour channel, so an error of 1/255 is the most that could ever show in a
 * picture; the tolerance below is a tenth of that.
 *
 *     ./build.sh && java --enable-native-access=ALL-UNNAMED -cp out engine.GpuMatCheck
 */
final class GpuMatCheck {
    private static final int N = 256;                 // samples across u and across v
    private static final double SPAN_U = 4, SPAN_V = 3;        // metres of wall covered by the grid
    private static final double[] WIDTHS = {0.0005, 0.004, 0.02, 0.09, 0.4};
    private static final double TOLERANCE = 1 / 2550.0;

    private static final String VERT = """
            #version 330 core
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 330 core
            uniform int mat;
            uniform float w, spanU, spanV, n;
            out vec4 frag;
            %s
            void main() {
                float u = gl_FragCoord.x / n * spanU;
                float v = gl_FragCoord.y / n * spanV;
                frag = vec4(side(mat, u, v, w), 0.0, 0.0, 1.0);
            }
            """.formatted(GlMaterials.SIDE);

    public static void main(String[] args) {
        Gl.context();
        System.out.printf("GL %s on %s%n", Gl.version(), Gl.device());
        try (Arena arena = Arena.ofConfined()) {
            int target = Gl.texture();
            Gl.bindTexture(target);
            Gl.texImage(Gl.RGBA32F, N, N, Gl.RGBA, Gl.FLOAT, MemorySegment.NULL);
            Gl.texUnfiltered();
            Gl.bindFramebuffer(Gl.framebuffer());
            Gl.attach(target);
            Gl.bindVertexArray(Gl.vertexArray());
            Gl.viewport(N, N);

            int program = Gl.program(VERT, FRAG);
            Gl.useProgram(program);
            Gl.uniform(program, "spanU", (float) SPAN_U);
            Gl.uniform(program, "spanV", (float) SPAN_V);
            Gl.uniform(program, "n", (float) N);

            MemorySegment back = arena.allocate((long) N * N * 4 * Float.BYTES);
            boolean bad = false;
            System.out.printf("%-12s %10s %10s   %s%n", "material", "max err", "mean err", "worst at");
            for (int m = 0; m < 13; m++) {
                double worst = 0, sum = 0;
                String where = "";
                long samples = 0;
                for (double w : WIDTHS) {
                    Gl.uniform(program, "mat", m);
                    Gl.uniform(program, "w", (float) w);
                    Gl.clear();
                    Gl.drawFullScreen();
                    Gl.finish();
                    Gl.readFloats(N, N, back);
                    for (int j = 0; j < N; j++) {
                        double v = (j + 0.5) / N * SPAN_V;
                        for (int i = 0; i < N; i++) {
                            double u = (i + 0.5) / N * SPAN_U;
                            float got = back.getAtIndex(ValueLayout.JAVA_FLOAT, ((long) j * N + i) * 4);
                            double want = Materials.side(m, u, v, w);
                            double err = Math.abs(got - want);
                            sum += err;
                            samples++;
                            if (err > worst) {
                                worst = err;
                                where = "u %.3f v %.3f w %.4f  gpu %.5f cpu %.5f".formatted(u, v, w, got, want);
                            }
                        }
                    }
                }
                bad |= worst > TOLERANCE;
                System.out.printf("%-12s %10.2e %10.2e   %s%n",
                        name(m) + (worst > TOLERANCE ? " FAIL" : ""), worst, sum / samples, where);
            }
            System.out.println(bad
                    ? "at least one material is further out than " + TOLERANCE
                    : "every material agrees with the CPU to within " + TOLERANCE);
        }
    }

    private static String name(int m) {
        return switch (m) {
            case 0 -> "concrete"; case 1 -> "plaster"; case 2 -> "brick"; case 3 -> "wood";
            case 4 -> "stone"; case 5 -> "metal"; case 6 -> "tile"; case 7 -> "water";
            case 8 -> "grass"; case 9 -> "leaf"; case 10 -> "board"; case 11 -> "panel";
            default -> "terracotta";
        };
    }

    private GpuMatCheck() {}
}

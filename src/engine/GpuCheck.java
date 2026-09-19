package engine;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * How far apart are the wall pixels the CPU draws and the ones the card draws?
 *
 * The golden frames cannot answer this. They hash the CPU renderer's own arrays, and a card
 * works in float where the renderer works in double, so the two will never hash the same and
 * insisting that they do would only mean never using the card. What can be asked instead is how
 * large the disagreement is, and whether it is small enough that no one could see it - the same
 * question {@code tools/score.sh} asks of the Haven conversion against Blender, with the CPU
 * renderer as the reference this time.
 *
 * For each camera it renders the frame on the CPU with the spans recorded, draws those same
 * spans on the card, and compares the two at every pixel a span covers. A channel is one byte,
 * so a difference of 1 is the smallest that exists and anything under about 2 is invisible.
 *
 *     ./build.sh && java --enable-native-access=ALL-UNNAMED -cp out engine.GpuCheck [map.json] [WxH]
 */
final class GpuCheck {
    /** name, x, y, heading, feet - the school cameras, at the heights they stand at. */
    private static final String[][] VIEWS = {
        {"classroom", "13", "4", "90", "0"},
        {"corridor", "16", "9.8", "0", "0"},
        {"courtyard", "16", "21.6", "-100", "0"},
        {"pool", "16", "17", "90", "0"},
        {"landing", "29.5", "14", "180", "1.8"},
    };

    public static void main(String[] args) throws Exception {
        Path map = Path.of(args.length > 0 && args[0].endsWith(".json") ? args[0] : "maps/school.json");
        int w = 1280, h = 720;
        for (String a : args) {
            if (a.contains("x") && !a.endsWith(".json")) {
                w = Integer.parseInt(a.split("x")[0]);
                h = Integer.parseInt(a.split("x")[1]);
            }
        }
        World world = World.load(map);
        int[] cpu = new int[w * h], gpu = new int[w * h];
        Renderer renderer = new Renderer(world, w, h, cpu);
        GpuSpans spans = new GpuSpans(w, h);
        renderer.captureSpans(spans);

        try (Arena arena = Arena.ofConfined()) {
            GpuWalls walls = new GpuWalls(arena, w, h, w);
            System.out.printf("GL %s on %s%n", Gl.version(), Gl.device());
            System.out.printf("%s at %dx%d, flat shading, walls only%n%n", map, w, h);
            System.out.printf("%-12s %8s %8s %8s %8s %8s %8s %8s%n",
                    "view", "pixels", "worst", "mean", "over 2", "masked", "cpu ms", "gpu ms");
            int worstAll = 0;
            for (String[] v : VIEWS) {
                spans.reset();
                Arrays.fill(cpu, 0);
                Renderer.Camera cam = new Renderer.Camera();
                cam.x = Double.parseDouble(v[1]);
                cam.y = Double.parseDouble(v[2]);
                double heading = Math.toRadians(Double.parseDouble(v[3]));
                cam.dirX = Math.cos(heading);
                cam.dirY = Math.sin(heading);
                cam.eye = Double.parseDouble(v[4]) + Player.EYE_STAND;
                renderer.render(cam);
                walls.draw(spans, gpu);

                // How long each side takes, once both are warm. The CPU figure is a whole frame -
                // rays, grid, floors, ceilings and walls - and the GPU figure is the wall pass
                // alone, so this is not a like-for-like race; it is the ceiling the wall half of
                // the frame could fall to.
                double cpuMs = Double.MAX_VALUE, gpuMs = Double.MAX_VALUE;
                for (int r = 0; r < 12; r++) {
                    spans.reset();
                    long a0 = System.nanoTime();
                    renderer.render(cam);
                    long a1 = System.nanoTime();
                    walls.draw(spans, gpu);
                    long a2 = System.nanoTime();
                    cpuMs = Math.min(cpuMs, (a1 - a0) / 1e6);
                    gpuMs = Math.min(gpuMs, (a2 - a1) / 1e6);
                }

                long n = 0, sum = 0, over = 0, skipped = 0;
                int worst = 0;
                String worstAt = "";
                for (int x = 0; x < w; x++) {
                    for (int s = 0; s < spans.count()[x]; s++) {
                        int at = (x * GpuSpans.MAX_PER_COLUMN + s) * GpuSpans.FLOATS;
                        int y0 = (int) spans.data()[at + 1], y1 = (int) spans.data()[at + 2];
                        for (int y = y0; y < y1; y++) {
                            if (spans.wasBlended(x, y)) { skipped++; continue; }
                            int a = cpu[y * w + x], b = gpu[y * w + x];
                            int d = Math.max(Math.abs((a >> 16 & 255) - (b >> 16 & 255)),
                                    Math.max(Math.abs((a >> 8 & 255) - (b >> 8 & 255)),
                                            Math.abs((a & 255) - (b & 255))));
                            n++;
                            sum += d;
                            if (d > worst) {
                                worst = d;
                                worstAt = ("    worst at column %d row %d: cpu %06x gpu %06x%n"
                                        + "      mat %.0f  u %.4f  z %.4f  w %.6f  sq %.4f  light %.4f  rgb %06x")
                                        .formatted(x, y, a & 0xffffff, b & 0xffffff,
                                                spans.data()[at + 3], spans.data()[at + 4],
                                                spans.data()[at + 5] + y * spans.data()[at + 6],
                                                spans.data()[at + 8], spans.data()[at + 9],
                                                spans.data()[at + 7], (int) spans.data()[at + 10]);
                            }
                            if (d > 2) over++;
                        }
                    }
                }
                worstAll = Math.max(worstAll, worst);
                System.out.printf("%-12s %8d %8d %8.3f %7.3f%% %8d %8.2f %8.2f%n",
                        v[0], n, worst, n == 0 ? 0 : (double) sum / n,
                        n == 0 ? 0 : 100.0 * over / n, skipped, cpuMs, gpuMs);
                if (worst > 2) System.out.println(worstAt);
                if (spans.dropped() > 0) System.out.printf("  %d spans dropped%n", spans.dropped());
            }
            System.out.printf("%nworst channel difference anywhere: %d of 255%n", worstAll);
        }
    }

    private GpuCheck() {}
}

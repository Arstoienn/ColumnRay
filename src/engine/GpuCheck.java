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
    /** A scene with nothing but walls in it, for when the point is that the card drew the frame
     *  and not that it drew part of one: if the GPU pass were doing nothing, these would be black. */
    private static final String[][] WALLS_ONLY = {
        {"shaft-n", "5", "5", "-90", "25"},
        {"shaft-e", "5", "5", "0", "25"},
        {"shaft-corner", "2", "2", "45", "25"},
    };

    /** The Haven cameras of tests/views/haven.txt, where the images are. */
    private static final String[][] HAVEN = {
        {"spot4", "95.2", "122.8", "180", "3.00"},
        {"spot6", "71.8", "49.2", "330", "3.00"},
        {"spot7", "69.2", "124.2", "255", "1.00"},
        {"spot8", "65.8", "84.8", "180", "1.00"},
        {"spot9", "34.8", "120.2", "270", "1.50"},
        {"spot10", "34.8", "80.8", "90", "2.00"},
    };

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
        boolean lit = !Boolean.getBoolean("gpucheck.flat");
        Lighting lighting = lit ? Lighting.bake(world) : null;
        int[] cpu = new int[w * h], gpu = new int[w * h];
        Renderer renderer = new Renderer(world, w, h, cpu);
        if (lighting != null) renderer.setLighting(lighting);
        GpuSpans spans = new GpuSpans(w, h);
        GpuMasks masks = new GpuMasks(w);
        renderer.captureSpans(spans);
        renderer.captureMasks(masks);

        try (Arena arena = Arena.ofConfined()) {
            GpuWalls walls = new GpuWalls(arena, w, h, w);
            if (lighting != null) walls.setLights(new GpuLights(lighting));
            java.util.List<Materials.Texture> imgs = GpuTextures.of(world);
            if (!imgs.isEmpty()) {
                GpuTextures textures = new GpuTextures(imgs);
                GpuMaterials materials = new GpuMaterials(world, textures);
                walls.setImages(textures, materials);
                System.out.printf("%d images in %d array textures, %d of them left to the CPU%n",
                        imgs.size(), textures.banks(), textures.leftToCpu());
                System.out.printf("%d shapes in %d material records%n",
                        world.shapes.length, materials.count());
            }
            System.out.printf("GL %s on %s%n", Gl.version(), Gl.device());
            System.out.printf("%s at %dx%d, %s%n%n", map, w, h, lit ? "baked lighting" : "flat shading");
            System.out.printf("%-12s %8s %8s %8s %8s %8s %8s %8s%n",
                    "view", "pixels", "worst", "mean", "over 2", "masked", "cpu ms", "gpu ms");
            int worstAll = 0;
            String name = map.getFileName().toString();
            String[][] views = name.contains("walls") ? WALLS_ONLY : name.contains("haven") ? HAVEN : VIEWS;
            for (String[] v : views) {
                spans.reset();
                masks.reset();
                Arrays.fill(cpu, 0);
                Renderer.Camera cam = new Renderer.Camera();
                cam.x = Double.parseDouble(v[1]);
                cam.y = Double.parseDouble(v[2]);
                double heading = Math.toRadians(Double.parseDouble(v[3]));
                cam.dirX = Math.cos(heading);
                cam.dirY = Math.sin(heading);
                cam.eye = Double.parseDouble(v[4]) + Player.EYE_STAND;
                cam.baked = lighting != null;
                renderer.render(cam);
                walls.draw(spans, masks, gpu, cam, h / 2.0 + cam.pitch, renderer.focal(), renderer.viewH);

                // How long each side takes, once both are warm. The CPU figure is a whole frame -
                // rays, grid, floors, ceilings and walls - and the GPU figure is the wall pass
                // alone, so this is not a like-for-like race; it is the ceiling the wall half of
                // the frame could fall to.
                double cpuMs = Double.MAX_VALUE, gpuMs = Double.MAX_VALUE;
                for (int r = 0; r < 12; r++) {
                    spans.reset();
                    masks.reset();
                    long a0 = System.nanoTime();
                    renderer.render(cam);
                    long a1 = System.nanoTime();
                    walls.draw(spans, masks, gpu, cam, h / 2.0 + cam.pitch, renderer.focal(), renderer.viewH);
                    long a2 = System.nanoTime();
                    cpuMs = Math.min(cpuMs, (a1 - a0) / 1e6);
                    gpuMs = Math.min(gpuMs, (a2 - a1) / 1e6);
                }

                if (System.getProperty("gpucheck.png") != null) {
                    png(cpu, w, h, System.getProperty("gpucheck.png") + "/" + v[0] + "-cpu.png");
                    png(gpu, w, h, System.getProperty("gpucheck.png") + "/" + v[0] + "-gpu.png");
                }
                long n = 0, sum = 0, over = 0, skipped = 0;
                int worst = 0;
                String worstAt = "";
                for (int x = 0; x < w; x++) {
                    for (int y = 0; y < h; y++) {
                        if (spans.skipped(x, y)) { skipped++; continue; }
                        int a = cpu[y * w + x], b = gpu[y * w + x];
                        int d = Math.max(Math.abs((a >> 16 & 255) - (b >> 16 & 255)),
                                Math.max(Math.abs((a >> 8 & 255) - (b >> 8 & 255)),
                                        Math.abs((a & 255) - (b & 255))));
                        n++;
                        sum += d;
                        if (d > 2) over++;
                        if (d > worst) {
                            worst = d;
                            worstAt = describe(spans, x, y, a, b, cam, h);
                        }
                    }
                }
                worstAll = Math.max(worstAll, worst);
                System.out.printf("%-12s %8d %8d %8.3f %7.3f%% %8d %8.2f %8.2f%n",
                        v[0], n, worst, n == 0 ? 0 : (double) sum / n,
                        n == 0 ? 0 : 100.0 * over / n, skipped, cpuMs, gpuMs);
                if (worst > 2) System.out.println(worstAt);
                if (spans.dropped() > 0) System.out.printf("  %d spans dropped%n", spans.dropped());
                if (masks.dropped() > 0) System.out.printf("  %d masks dropped%n", masks.dropped());
            }
            System.out.printf("%nworst channel difference anywhere: %d of 255%n", worstAll);
        }
    }

    /** What the renderer said about the pixel the two sides disagree on most: which span covers
     *  it and what that span is made of, or that none does and the two skies differ. */
    private static String describe(GpuSpans spans, int x, int y, int a, int b,
                                   Renderer.Camera cam, int h) {
        String head = "    worst at column %d row %d: cpu %06x gpu %06x%n"
                .formatted(x, y, a & 0xffffff, b & 0xffffff);
        float[] sp = spans.data();
        for (int i = 0; i < spans.count()[x]; i++) {
            int at = (x * GpuSpans.MAX_PER_COLUMN + i) * GpuSpans.FLOATS;
            if (y < (int) sp[at + 1] || y >= (int) sp[at + 2]) continue;
            return head + (sp[at + 11] == 0
                    ? "      wall  mat %.0f  u %.4f  z %.4f  w %.6f  sq %.4f  light %.4f  rgb %06x  rec %.0f"
                            .formatted(sp[at + 3], sp[at + 4],
                                    cam.eye - (y + 0.5 - (h / 2.0 + cam.pitch)) * sp[at + 8],
                                    sp[at + 8], sp[at + 9], sp[at + 7], (int) sp[at + 10], sp[at + 12])
                    : "      plane mat %.0f  z %.4f  slope %.4f  light %.4f  rgb %06x  rec %.0f"
                            .formatted(sp[at + 3], sp[at + 4], sp[at + 5], sp[at + 7],
                                    (int) sp[at + 10], sp[at + 12]));
        }
        return head + "      sky (no span covers this row)";
    }

    /** -Dgpucheck.png=DIR writes both pictures, for when the numbers want looking at. */
    private static void png(int[] pixels, int w, int h, String path) throws java.io.IOException {
        java.awt.image.BufferedImage im =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        im.setRGB(0, 0, w, h, pixels, 0, w);
        javax.imageio.ImageIO.write(im, "png", new java.io.File(path));
    }

    private GpuCheck() {}
}

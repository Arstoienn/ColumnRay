package engine;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * The headless ways of running the engine: --bench, --shot, --shots and --verify.
 *
 * None of them opens a window, none of them reads a key, and all of them work by putting the
 * player somewhere, rendering one frame and writing down what came out. That is a different job
 * from running a game, which is why it is a different file: Main is the window and the loop, and
 * has no business knowing about PNG encoders, PFM headers or how long a frame took.
 *
 * It drives a Main rather than replacing one. The buffers, the renderer and the player are the
 * same ones the interactive loop uses, and deliberately so - a benchmark or a golden frame that
 * went through a second, simpler path would be measuring and comparing something the game does
 * not actually do.
 */
final class Capture {
    private final Main g;

    /** --shots and --verify stand at exactly the height the view asks for rather than on whatever
     *  ground is there. Snapping to our own floor moved the eye up to 25 cm away from where the
     *  reference camera stands, which tilts the whole frame out of line; unsnapped, a floor that
     *  came out at the wrong height shows up as what it is - that floor being at the wrong
     *  distance. NaN means "stand on the ground", which is what --shot on its own does. */
    private double exactFeet = Double.NaN;

    Capture(Main game) {
        this.g = game;
    }

    /** Stand at a view from a file or the command line. */
    private void place(double px, double py, double headingDeg, double pitchDeg) {
        g.player.look(px, py, headingDeg, pitchDeg);
        if (Double.isNaN(exactFeet)) g.player.placeOnGround();
        else g.player.standAt(exactFeet);
    }

    /**
     * Spin on the spot and time every frame (headless, no window): level, then tilted all the way.
     *
     * The JIT gets -Dbench.warmup untimed frames first (400): 120 were not enough, and the first
     * timed frames were still being compiled. Then each of -Dbench.frames frames (720, one full turn)
     * is timed on its own, and the median and 99th percentile are reported next to the mean - a
     * mean hides both a slow start and a stall. bench.sh runs this several times and says how far
     * apart the runs were.
     */
    void bench() {
        int warmup = Integer.getInteger("bench.warmup", 400), frames = Integer.getInteger("bench.frames", 720);
        double saved = g.player.pitch;
        for (double p : new double[] {0, Player.MAX_PITCH}) {   // level, and fully tilted (the most overscan)
            g.player.pitch = p;
            for (int i = 0; i < warmup; i++) { g.player.angle += 2 * Math.PI / frames; g.frame(); }
            long[] ns = new long[frames];
            for (int i = 0; i < frames; i++) {
                long t0 = System.nanoTime();
                g.player.angle += 2 * Math.PI / frames;
                g.frame();
                ns[i] = System.nanoTime() - t0;
            }
            long[] sorted = ns.clone();
            Arrays.sort(sorted);
            double median = sorted[frames / 2] / 1e6;
            double p99 = sorted[Math.min(frames - 1, (int) Math.ceil(frames * 0.99) - 1)] / 1e6;
            double mean = Arrays.stream(ns).average().orElse(0) / 1e6;
            System.out.printf("BENCH %dx%d rendered %dx%d ss %d pitch %.0f %s rays %d median %.3f p99 %.3f mean %.3f ms  (median %.0f fps)%n",
                    g.W, g.H, g.RW, g.RH, g.SS, Math.toDegrees(p), g.shear ? "shear" : "true",
                    g.renderer.drawnX1 - g.renderer.drawnX0, median, p99, mean, 1000 / median);
        }
        g.player.pitch = saved;
    }

    /**
     * Render each view and print what it came out as, instead of writing any files.
     *
     * The rule this project runs on is that an optimisation is proved not to change the output
     * rather than assumed not to, and until now that was a habit rather than a mechanism: you
     * rendered the shots by hand before and after and hoped you remembered to. This is the same
     * comparison with the pictures taken out of it - the renderer's own pixels, depth and albedo
     * arrays, and the lightmap, each as a digest that a script can diff against a golden file.
     *
     * One view per line: {@code name x y heading pitch feet}, where feet may be {@code -} to stand
     * on whatever ground is there. The HUD is deliberately not included: it draws text, and the
     * glyphs a machine has are not the engine's output.
     */
    void verify(Path list) throws Exception {
        System.out.println("# columnray verify 1");
        for (String line : Files.readAllLines(list)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] f = t.split("\\s+");
            if (f.length < 5) { System.err.println("skipping: " + t); continue; }
            exactFeet = f.length > 5 && !f[5].equals("-") ? Double.parseDouble(f[5]) : Double.NaN;
            place(Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                    Double.parseDouble(f[3]), Double.parseDouble(f[4]));
            g.traceI = g.W / 2 * g.SS + g.SS / 2;
            g.traceJ = -1;
            g.cam.captureDepth = true;
            try {
                g.frame();
            } finally {
                g.cam.captureDepth = false;
            }
            System.out.printf("view %s %dx%d plain=%s albedo=%s depth=%s%n", f[0], g.W, g.H,
                    Hash.of().add(g.out, g.W * g.H).hex(),
                    Hash.of().add(g.albedo, g.W * g.H).hex(),
                    Hash.of().add(g.depth, g.W * g.H).hex());
            g.depth = g.hiDepth = g.renderer.depth = null;
            g.albedo = g.hiAlbedo = g.renderer.albedo = null;
        }
        if (g.lighting != null) System.out.printf("lightmap %s rays=%d%n", g.lighting.hash(), g.lighting.rays);
        else System.out.println("lightmap - rays=0   # --flat");
    }

    /** Every view in a file, one per line: "out.png x y feet heading". Baking the lightmaps for
     *  a map the size of Haven takes a minute and a half, and it is the same bake for every camera,
     *  so a set of comparison shots belongs in one run rather than one run each. */
    void screenshots(Path list) throws Exception {
        for (String line : Files.readAllLines(list)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] f = t.split("\\s+");
            if (f.length < 5) { System.err.println("skipping: " + t); continue; }
            exactFeet = Double.parseDouble(f[3]);
            screenshot(new File(f[0]), new double[]{Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                                                    Double.parseDouble(f[4]), 0});
        }
    }

    void screenshot(File out, double[] at) throws Exception {
        if (at != null) place(at[0], at[1], at[2], at[3]);
        // the column argument is an output column, so it means the same place whatever --ss is
        int col = at != null && at.length > 4 ? Math.max(0, Math.min(g.W - 1, (int) at[4])) : g.W / 2;
        g.traceI = col * g.SS + g.SS / 2;
        g.traceJ = -1;
        g.cam.captureDepth = true;
        try {
            g.frame();
        } finally {
            g.cam.captureDepth = false;
        }
        File plainOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-plain.png"));
        ImageIO.write(g.image, "png", plainOut);                  // the warped view, before any overlays or scaling
        System.out.println("wrote " + plainOut);
        File depthOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-depth.pfm"));
        writeDepth(depthOut);
        System.out.println("wrote " + depthOut);
        File albedoOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-albedo.png"));
        BufferedImage alb = new BufferedImage(g.W, g.H, BufferedImage.TYPE_INT_RGB);
        alb.setRGB(0, 0, g.W, g.H, g.albedo, 0, g.W);
        ImageIO.write(alb, "png", albedoOut);
        System.out.println("wrote " + albedoOut);
        g.depth = g.hiDepth = g.renderer.depth = null;
        g.albedo = g.hiAlbedo = g.renderer.albedo = null;

        int scale = g.W < 1000 ? 2 : 1;          // upscale small renders so the HUD text stays readable
        BufferedImage img = new BufferedImage(g.W * scale, g.H * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D gfx = img.createGraphics();
        g.drawFrame(gfx, 0, 0, g.W * scale, g.H * scale, true);
        gfx.dispose();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);

        // Ray view of the same frame
        File raysOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-rays.png"));
        BufferedImage rays = new BufferedImage(640, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = rays.createGraphics();
        g.rayView.draw(rg, rays.getWidth(), rays.getHeight(), g.view());
        rg.dispose();
        ImageIO.write(rays, "png", raysOut);
        System.out.println("wrote " + raysOut);
    }

    /** Greyscale PFM: negative scale selects little endian; rows run from the bottom upwards. */
    private void writeDepth(File file) throws Exception {
        try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(file))) {
            stream.write(("Pf\n" + g.W + " " + g.H + "\n-1.0\n").getBytes(StandardCharsets.US_ASCII));
            ByteBuffer row = ByteBuffer.allocate(g.W * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = g.H - 1; y >= 0; y--) {
                row.clear();
                for (int x = 0; x < g.W; x++) row.putFloat(g.depth[y * g.W + x]);
                stream.write(row.array());
            }
        }
    }
}

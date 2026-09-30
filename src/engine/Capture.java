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
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * The headless ways of running the engine: --bench, --shot, --shots and --verify.
 *
 * None of them opens a window, none of them reads a key, and all of them work by putting the game
 * somewhere, rendering one frame and writing down what came out. That is a different job from
 * running a game, which is why it is a different file: {@link Host} is the window and the loop,
 * and has no business knowing about PNG encoders, PFM headers or how long a frame took.
 *
 * It drives a Host and a Game rather than replacing either. The buffers, the renderer and the
 * player are the same ones the interactive loop uses, and deliberately so - a benchmark or a
 * golden frame that went through a second, simpler path would be measuring and comparing
 * something the game does not actually do. The one thing it asks of the game is
 * {@link Game#place}: a views file says where to stand, and only the game knows how tall it is
 * and what counts as the ground.
 */
public final class Capture {
    private final Host h;
    private final Game game;

    /** --shots and --verify stand at exactly the height the view asks for rather than on whatever
     *  ground is there. Snapping to our own floor moved the eye up to 25 cm away from where the
     *  reference camera stands, which tilts the whole frame out of line; unsnapped, a floor that
     *  came out at the wrong height shows up as what it is - that floor being at the wrong
     *  distance. NaN means "stand on the ground", which is what --shot on its own does. */
    private double exactFeet = Double.NaN;

    /**
     * What a views file's {@code feet} column means for a camera that has no game to ask: the eye
     * stands this far above it. Only {@link GpuCheck} needs it - everything here goes through
     * {@link Game#place}, which knows how tall the game's own player is.
     */
    static final double EYE = 1.6;

    public Capture(Host host, Game game) {
        this.h = host;
        this.game = game;
        host.game = game;                   // so a screenshot gets the game's overlay on it
    }

    /** Stand at a view from a file or the command line. */
    private void place(double px, double py, double headingDeg, double pitchDeg) {
        game.place(px, py, headingDeg, pitchDeg, exactFeet);
    }

    /**
     * Spin on the spot and time every frame (headless, no window): level, then tilted all the way.
     *
     * The JIT gets -Dbench.warmup untimed frames first (400): 120 were not enough, and the first
     * timed frames were still being compiled. Then each of -Dbench.frames frames (720, one full turn)
     * is timed on its own, and the median, the 95th and 99th percentiles and the worst frame are
     * reported next to the mean - a mean hides both a slow start and a stall, and on a map the size
     * of Haven the tail is a garbage collection rather than anything the renderer did, which only
     * the far end of the distribution shows. bench.sh runs this several times and says how far
     * apart the runs were.
     */
    public void bench() {
        int warmup = Integer.getInteger("bench.warmup", 400), frames = Integer.getInteger("bench.frames", 720);
        View v = game.view().copy();              // the game's own camera, ours to spin
        // The frame a window gets: warped on the card and never read back. A frame that stays on
        // the card is only handed to the driver by the time h.frame returns, so each timed one is
        // waited for (settle) - otherwise this would time the handing over and not the drawing.
        h.warpOnCard(true, false);
        // -Dbench.settle=false leaves the card to run behind, as a window does: the CPU starts the
        // next frame's rays while the card is still shading this one. Then the mean over the turn is
        // the throughput, and a single frame's time means little - it is when the driver chose to
        // make the CPU wait, not what that frame cost.
        boolean settle = !"false".equals(System.getProperty("bench.settle"));
        for (double p : new double[] {0, h.pitchLimit()}) {   // level, and fully tilted (the most overscan)
            v.pitch = p;
            // The warmup frames are the same turn as the timed ones and nobody is holding a stop
            // watch over them, so the ray statistics are gathered here: summing a column array
            // inside the timed loop would be measuring this instead of the renderer. What comes
            // out does not move with the weather, which the milliseconds very much do - a chip
            // that has been busy all afternoon reports a different number for the same work - so
            // for "did that change make the rays do less", these are the figures to compare.
            long cells = 0, tests = 0, hits = 0, columns = 0;
            for (int i = 0; i < warmup; i++) {
                v.heading += 2 * Math.PI / frames;
                h.frame(v);
                for (int x = h.renderer.drawnX0; x < h.renderer.drawnX1; x++) {
                    cells += h.renderer.cellsVisited[x];
                    tests += h.renderer.shapesTested[x];
                    hits += h.renderer.shapesHit[x];
                }
                columns += h.renderer.drawnX1 - h.renderer.drawnX0;
            }
            System.out.printf("WORK pitch %.0f rays %d cells/ray %.1f tested/ray %.1f hit/ray %.1f (%.0f%% of tests)  (%d warmup frames)%n",
                    Math.toDegrees(p), columns / Math.max(1, warmup), cells / (double) Math.max(1, columns),
                    tests / (double) Math.max(1, columns), hits / (double) Math.max(1, columns),
                    100.0 * hits / Math.max(1, tests), warmup);
            long[] ns = new long[frames];
            for (int i = 0; i < frames; i++) {
                long t0 = System.nanoTime();
                v.heading += 2 * Math.PI / frames;
                h.frame(v);
                if (settle) h.settle();
                ns[i] = System.nanoTime() - t0;
            }
            long[] sorted = ns.clone();
            Arrays.sort(sorted);
            double median = sorted[frames / 2] / 1e6;
            double p95 = sorted[Math.min(frames - 1, (int) Math.ceil(frames * 0.95) - 1)] / 1e6;
            double p99 = sorted[Math.min(frames - 1, (int) Math.ceil(frames * 0.99) - 1)] / 1e6;
            double worst = sorted[frames - 1] / 1e6;
            double mean = Arrays.stream(ns).average().orElse(0) / 1e6;
            System.out.printf("BENCH %dx%d rendered %dx%d ss %d pitch %.0f %s%s rays %d median %.3f p95 %.3f p99 %.3f max %.3f mean %.3f ms  (median %.0f fps)%n",
                    h.W, h.H, h.RW, h.RH, h.SS, Math.toDegrees(v.pitch), h.shear() ? "shear" : "true",
                    h.shownOnCard() ? " warp card" : h.useGpu ? " warp cpu" : "",
                    h.renderer.drawnX1 - h.renderer.drawnX0, median, p95, p99, worst, mean, 1000 / median);
            h.gpuStats();
            dump(ns, v.pitch);
        }
    }

    /**
     * -Dbench.dump=NAME writes every frame's time to NAME-pitchN.txt, one millisecond figure a
     * line, in the order the turn rendered them.
     *
     * A percentile says how bad the bad frames are and nothing about what they are. Two very
     * different things look the same in one: a collector stopping the world for 80 ms, and a
     * heading that simply has more of the map down it than the one before. The first is a spike
     * with cheap frames either side, the second a broad hill that comes round once a turn, and
     * only the frames in order tell them apart.
     */
    private void dump(long[] ns, double pitch) {
        String name = System.getProperty("bench.dump");
        if (name == null) return;
        StringBuilder b = new StringBuilder();
        for (long n : ns) b.append(String.format("%.3f%n", n / 1e6));
        try {
            Files.writeString(Path.of(String.format("%s-pitch%.0f.txt", name, Math.toDegrees(pitch))), b);
        } catch (Exception e) {
            System.err.println("bench.dump: " + e);
        }
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
    public void verify(Path list) throws Exception {
        System.out.println("# columnray verify 1");
        for (String line : Files.readAllLines(list)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] f = t.split("\\s+");
            if (f.length < 5) { System.err.println("skipping: " + t); continue; }
            exactFeet = f.length > 5 && !f[5].equals("-") ? Double.parseDouble(f[5]) : Double.NaN;
            place(Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                    Double.parseDouble(f[3]), Double.parseDouble(f[4]));
            h.traceI = h.W / 2 * h.SS + h.SS / 2;
            h.traceJ = -1;
            View v = game.view();
            v.captureDepth = true;
            try {
                h.frame(v);
            } finally {
                v.captureDepth = false;
            }
            System.out.printf("view %s %dx%d plain=%s albedo=%s depth=%s%n", f[0], h.W, h.H,
                    Hash.of().add(h.out, h.W * h.H).hex(),
                    Hash.of().add(h.albedo, h.W * h.H).hex(),
                    Hash.of().add(h.depth, h.W * h.H).hex());
            h.depth = h.hiDepth = h.renderer.depth = null;
            h.albedo = h.hiAlbedo = h.renderer.albedo = null;
        }
        if (h.lighting != null) System.out.printf("lightmap %s rays=%d%n", h.lighting.hash(), h.lighting.rays);
        else System.out.println("lightmap - rays=0   # --flat");
    }

    /**
     * The card against the CPU, through the whole of the real frame loop.
     *
     * {@code GpuCheck} compares the two at a fixed size with the camera level, which leaves out
     * everything between the renderer and the window: the pitch warp, the overscan buffer growing
     * and the card's resources being rebuilt around it. That is not a small gap - it is exactly
     * where the worst bug of this branch lived, a resize that watched the width and not the
     * height - so this runs the same views through {@link Host#frame(View)} twice, once on each
     * path, and compares the finished output.
     *
     * The pitches climb, because the overscan only ever grows: each step asks for a taller buffer
     * than the last and so exercises the rebuild, and the heights it lands on are whatever the
     * warp asks for rather than round numbers.
     *
     * The render size moves too, one rung of {@link DynamicResolution#LADDER} per comparison. That
     * is the other half of the same rebuild and it costs nothing extra: the frames are being
     * rendered anyway, and rendering each of them at a different size means every comparison here
     * is also a resize, with the card's textures, its span mask and the overscan buffer all made
     * again underneath it. It matters that the sizes are the ladder's own and not round numbers -
     * with {@code --window} at the render size the rungs land on 213, 267 and 293 columns wide as
     * well as 320, so an odd render width is covered, which is a case nothing else here reaches
     * and which the buffer's own width being rounded up to even is there to survive.
     *
     * One trap is worth naming. With the card on, the renderer is told to leave the rows the card
     * will draw uncoloured; turning the card off for the reference frame without undoing that
     * would compare against a picture with holes in it. Hence the pairing below, and hence
     * {@code Host.useGpu} being a thing nothing else toggles at runtime.
     */
    public void gpuVerify(Path list) throws Exception {
        if (!h.useGpu) {
            System.err.println("--gpu-verify needs --gpu: it is the card that is being checked");
            return;
        }
        // The file's own pitch column is replaced by this sweep, so a views file that names the
        // same camera at several tilts checks it several times over. That is a little wasted work
        // and no wrong answer.
        double[] pitches = {0, 8, 17, 25, Math.toDegrees(h.pitchLimit())};
        // The frame a window gets, warped on the card, read back here so it can be held to the
        // CPU's. -Dwarp.cpu=true checks the older path instead, which is still what a frame falls
        // back to when the card was not given all of it.
        h.warpOnCard(true, true);
        int onCard = 0, frames = 0;
        int worstAll = 0, rung = h.scaleIndex();
        java.util.Set<String> sizes = new java.util.LinkedHashSet<>();
        long failed = 0, pixels = 0, drops = 0, notGiven = 0;
        System.out.printf("%-12s %6s %10s %10s %8s %8s %8s %8s %8s %12s%n",
                "view", "pitch", "render", "buffer", "worst", "mean", "over 8", "cpu px", "dropped", "per column");
        for (String line : Files.readAllLines(list)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] f = t.split("\s+");
            if (f.length < 5) { System.err.println("skipping: " + t); continue; }
            exactFeet = f.length > 5 && !f[5].equals("-") ? Double.parseDouble(f[5]) : Double.NaN;
            for (double pitch : pitches) {
                // One rung along before anything is rendered, so the pair below is compared at one
                // size and the resize happens between comparisons rather than inside one. It wraps
                // rather than climbing, because a step down tears the card's resources out and
                // builds them smaller, which a ladder that only ever went up would never ask for.
                rung = (rung + 1) % Host.scaleRungs();
                h.stepScale(rung - h.scaleIndex());
                // Frames thrown away until the lists stop growing: they start small and double
                // when a column runs out, so the first frames at a new tilt legitimately hand rows
                // back to the CPU. What is being checked is the steady state, not the climb to it,
                // and a tilt that never stops dropping is a real answer rather than a slow start.
                for (int warm = 0; warm < 16; warm++) {
                    place(Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                            Double.parseDouble(f[3]), pitch);
                    h.setUseGpu(false);
                    h.frame(game.view());
                    place(Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                            Double.parseDouble(f[3]), pitch);
                    h.setUseGpu(true);
                    h.frame(game.view());
                    if (warm > 0 && h.gpuDropped() == 0) break;
                }
                place(Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                        Double.parseDouble(f[3]), pitch);
                h.setUseGpu(false);
                h.frame(game.view());
                int[] cpu = Arrays.copyOf(h.out, h.W * h.H);

                place(Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                        Double.parseDouble(f[3]), pitch);
                h.setUseGpu(true);
                h.frame(game.view());
                int skipped = h.gpuSkipped();
                frames++;
                if (h.shownOnCard()) onCard++;

                int worst = 0, over = 0;
                long sum = 0;
                for (int i = 0; i < h.W * h.H; i++) {
                    int a = cpu[i], b = h.out[i];
                    int d = Math.max(Math.abs((a >> 16 & 255) - (b >> 16 & 255)),
                            Math.max(Math.abs((a >> 8 & 255) - (b >> 8 & 255)),
                                    Math.abs((a & 255) - (b & 255))));
                    sum += d;
                    if (d > 8) over++;
                    worst = Math.max(worst, d);
                }
                worstAll = Math.max(worstAll, worst);
                failed += over;
                pixels += (long) h.W * h.H;
                drops += h.gpuDropped();
                notGiven += skipped;
                sizes.add(h.W + "x" + h.H);
                System.out.printf("%-12s %6.0f %10s %10s %8d %8.3f %8d %8d %8d %12s%n", f[0], pitch,
                        h.W + "x" + h.H, h.srcW + "x" + h.srcH, worst, (double) sum / (h.W * h.H),
                        over, skipped, h.gpuDropped(), h.gpuMost());
            }
        }
        // What this is allowed to find, and what it is not.
        //
        // The two pictures are never identical - float against double - and a few pixels a frame
        // land exactly on the hard edge of a procedural material, a plank line or a brick course,
        // where the two fall on opposite sides and disagree by tens of levels. That is a handful
        // of pixels in a million and it is what the worst column reports. What this is here to
        // catch is structural: a row the card did not draw, a buffer left at the wrong size, a
        // frame where the merge kept the wrong half. Those are not two pixels, they are percents
        // of the frame, so the threshold is on how many pixels disagree and not on how much.
        double bad = 100.0 * failed / Math.max(1, pixels);
        System.out.printf("%nworst %d of 255 anywhere; %d of %d pixels over 8 (%.4f%%); "
                        + "%d pixels the card was not given; %d dropped%n",
                worstAll, failed, pixels, bad, notGiven, drops);
        System.out.printf("%d of %d frames warped on the card%n", onCard, frames);
        System.out.printf("%d render sizes, %d of them an odd number of columns wide: %s%n",
                sizes.size(), sizes.stream().filter(z -> Integer.parseInt(z.split("x")[0]) % 2 == 1).count(),
                String.join(" ", sizes));
        // Dropping is not failing. A column with no room for another entry hands its rows back to
        // the CPU, and this very run is the proof that the picture survives it: the frames that
        // dropped sixty thousand entries between them still agreed to within four levels. So a
        // drop is said out loud, because it costs coverage and speed, and the verdict is about
        // the picture alone.
        if (drops > 0)
            System.out.printf("note  %d entries went back to the CPU for want of room in a column%n", drops);
        if (bad > 0.01) {
            System.out.println("FAIL  the card and the CPU disagree over more of the frame than rounding explains");
            System.exit(1);
        }
        System.out.println("ok    the card's frame and the CPU's agree everywhere but the odd edge");
    }

    /**
     * Every view in a file, one per line: "out.png x y feet heading [pitch]".
     *
     * Baking the lightmaps for a map the size of Haven takes a minute and a half, and it is the
     * same bake for every camera, so a set of comparison shots belongs in one run rather than one
     * run each.
     *
     * The pitch is optional and used to not exist: this forced 0 and {@code --shot} took a pitch
     * but had no exact standing height, so the three README pictures that want both were rendered
     * from a build patched by hand and unpatched afterwards, every time the lighting moved. That
     * is three lines to avoid, {@code --verify} has taken a pitch all along, and a hand-patched
     * build is not a thing to keep in a document.
     */
    public void screenshots(Path list) throws Exception {
        dumpMaps();
        for (String line : Files.readAllLines(list)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] f = t.split("\\s+");
            if (f.length < 5) { System.err.println("skipping: " + t); continue; }
            exactFeet = Double.parseDouble(f[3]);
            double pitch = f.length > 5 && !f[5].equals("-") ? Double.parseDouble(f[5]) : 0;
            screenshot(new File(f[0]), new double[]{Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                                                    Double.parseDouble(f[4]), pitch});
        }
    }

    public void screenshot(File out, double[] at) throws Exception {
        if (at != null) place(at[0], at[1], at[2], at[3]);
        // the column argument is an output column, so it means the same place whatever --ss is
        int col = at != null && at.length > 4 ? Math.max(0, Math.min(h.W - 1, (int) at[4])) : h.W / 2;
        h.traceI = col * h.SS + h.SS / 2;
        h.traceJ = -1;
        View v = game.view();
        v.captureDepth = true;
        try {
            h.frame(v);
        } finally {
            v.captureDepth = false;
        }
        File plainOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-plain.png"));
        ImageIO.write(h.image, "png", plainOut);                  // the warped view, before any overlays or scaling
        System.out.println("wrote " + plainOut);
        File depthOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-depth.pfm"));
        writeDepth(depthOut);
        System.out.println("wrote " + depthOut);
        File albedoOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-albedo.png"));
        BufferedImage alb = new BufferedImage(h.W, h.H, BufferedImage.TYPE_INT_RGB);
        alb.setRGB(0, 0, h.W, h.H, h.albedo, 0, h.W);
        ImageIO.write(alb, "png", albedoOut);
        System.out.println("wrote " + albedoOut);
        h.depth = h.hiDepth = h.renderer.depth = null;
        h.albedo = h.hiAlbedo = h.renderer.albedo = null;

        int scale = h.W < 1000 ? 2 : 1;          // upscale small renders so the HUD text stays readable
        BufferedImage img = new BufferedImage(h.W * scale, h.H * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D gfx = img.createGraphics();
        h.drawFrame(gfx, 0, 0, h.W * scale, h.H * scale, true);
        gfx.dispose();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);

        // Ray view of the same frame
        File raysOut = new File(out.getPath().replaceFirst("(\\.png)?$", "-rays.png"));
        BufferedImage rays = new BufferedImage(640, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = rays.createGraphics();
        h.rayView.draw(rg, rays.getWidth(), rays.getHeight(), h.view());
        rg.dispose();
        ImageIO.write(rays, "png", raysOut);
        System.out.println("wrote " + raysOut);
    }

    /** Greyscale PFM: negative scale selects little endian; rows run from the bottom upwards. */
    private void writeDepth(File file) throws Exception {
        try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(file))) {
            stream.write(("Pf\n" + h.W + " " + h.H + "\n-1.0\n").getBytes(StandardCharsets.US_ASCII));
            ByteBuffer row = ByteBuffer.allocate(h.W * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = h.H - 1; y >= 0; y--) {
                row.clear();
                for (int x = 0; x < h.W; x++) row.putFloat(h.depth[y * h.W + x]);
                stream.write(row.array());
            }
        }
    }

    /**
     * -Dlight.dump=top:ID[,side:ID:FACE,floor:ID,...]: write one lightmap as a PNG, one pixel a
     * texel, beside the shots. A debugging aid for the question "is this blocky patch the shape of
     * the lightmap or the shape of something in the world", which no screenshot can answer: at a
     * grazing angle a 0.5 m texel is a hundred pixels across and every guess about its size is a
     * guess. It lives here, and not in {@link Lighting}, on purpose - {@code Capture} is in
     * {@code LightCache.BAKES_NOT}, so asking for a dump reads the cached bake instead of
     * spending ten minutes making a fresh one that is supposed to be identical.
     */
    private void dumpMaps() throws Exception {
        String spec = System.getProperty("light.dump");
        if (spec == null || h.lighting == null) return;
        for (String one : spec.split(",")) {
            String[] f = one.trim().split(":");
            if (f[0].equals("big")) { biggest(Integer.parseInt(f[1])); continue; }
            if (f[0].equals("tiny")) { tiny(Integer.parseInt(f[1])); continue; }
            if (f[0].equals("black")) { black(); continue; }
            if (f[0].equals("down")) { down(); continue; }
            if (f[0].equals("info")) { info(Integer.parseInt(f[1])); continue; }
            if (f[0].equals("probe")) { probe(Double.parseDouble(f[1]), Double.parseDouble(f[2])); continue; }
            if (f[0].equals("sun")) {
                sunShadow(Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                        Double.parseDouble(f[4]), Double.parseDouble(f[5]));
                continue;
            }
            if (f[0].equals("id")) { byId(Integer.parseInt(f[1])); continue; }
            // at:X:Y, not at:X,Y - the spec itself is a comma-separated list.
            if (f[0].equals("at")) { under(Double.parseDouble(f[1]), Double.parseDouble(f[2])); continue; }
            int id = Integer.parseInt(f[1]);
            Lighting.LightMap m = switch (f[0]) {
                case "top" -> h.lighting.top(shape(id));
                case "bottom" -> h.lighting.bottom(shape(id));
                case "side" -> h.lighting.side(shape(id), f.length > 2 ? Integer.parseInt(f[2]) : 0);
                case "floor" -> h.lighting.floor(h.world.regions[id]);
                case "ceil" -> h.lighting.ceil(h.world.regions[id]);
                default -> throw new IllegalArgumentException("light.dump: " + one);
            };
            if (m == null) { System.out.println("light.dump " + one + ": no map"); continue; }
            write(one.trim().replace(':', '-'), m);
        }
    }

    /** The n maps of this bake with the most texels: the ground and the big walls, which are the
     *  ones a blocky patch in a picture is likely to be standing on. */
    private void biggest(int n) throws Exception {
        List<Lighting.LightMap> all = new java.util.ArrayList<>(h.lighting.maps());
        all.sort((a, b) -> Long.compare((long) b.w * b.h, (long) a.w * a.h));
        for (int i = 0; i < Math.min(n, all.size()); i++) write("big" + i, all.get(i));
    }

    /** -Dlight.dump=id:N finds the map that -Ddebug.who=map wrote as N - its identity hash, masked
     *  to the 21 bits the albedo buffer has room for - and says what it is and what points at it. */
    private void byId(int id) {
        for (Lighting.LightMap m : h.lighting.maps()) {
            if ((System.identityHashCode(m) & 0x1FFFFF) != id) continue;
            int tops = 0, bottoms = 0, floors = 0;
            double z = Double.NaN;
            World.Shape one = null;
            for (World.Shape sh : h.world.shapes) {
                if (h.lighting.top(sh) == m) { tops++; z = sh.h; one = sh; }
                if (h.lighting.bottom(sh) == m) { bottoms++; z = sh.z0; one = sh; }
            }
            for (World.Region r : h.world.regions) if (h.lighting.floor(r) == m) { floors++; z = r.floor; }
            System.out.printf("map %d: %d x %d texels of %.2f m at u %.2f v %.2f; %d tops, %d bottoms, %d floors; z ~ %.4f%n",
                    id, m.w, m.h, m.step, m.u0, m.v0, tops, bottoms, floors, z);
            if (one != null)
                System.out.printf("   one of them: shape %d mat %d topMat %d slope %g,%g box %.2f,%.2f..%.2f,%.2f%n",
                        one.id, one.mat, one.topMat, one.hx, one.hy, one.minX, one.minY, one.maxX, one.maxY);
            return;
        }
        System.out.println("light.dump id:" + id + ": no map with that identity");
    }

    /** -Dlight.dump=info:ID says what a shape is, for when a debug.who id has to be looked up. */
    private void info(int id) {
        World.Shape s = shape(id);
        System.out.printf("shape %d: %s mat %d topMat %d colour %06x  z %.4f..%.4f  slope top %g,%g bottom %g,%g"
                + "  box %.2f,%.2f..%.2f,%.2f  img %s  mask %d  top map %s%n",
                id, s.kind, s.mat, s.topMat, s.color & 0xFFFFFF, s.z0, s.h, s.hx, s.hy, s.zx, s.zy,
                s.minX, s.minY, s.maxX, s.maxY, s.img != null, s.mask,
                h.lighting.top(s) == null ? "none"
                        : String.format("%dx%d at u %.2f v %.2f step %.2f (#%08x)", h.lighting.top(s).w,
                                h.lighting.top(s).h, h.lighting.top(s).u0, h.lighting.top(s).v0,
                                h.lighting.top(s).step, System.identityHashCode(h.lighting.top(s))));
    }

    /** -Dlight.dump=down paints every downward-facing map - a shape's underside, a region's
     *  ceiling. One of those has no business being visible from above, so a pixel it paints is a
     *  pixel where the depth test picked the underside of something paper-thin. */
    private void down() {
        java.util.Set<Lighting.LightMap> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (World.Shape sh : h.world.shapes)
            if (h.lighting.bottom(sh) != null) seen.add(h.lighting.bottom(sh));
        for (World.Region r : h.world.regions)
            if (h.lighting.ceil(r) != null) seen.add(h.lighting.ceil(r));
        for (Lighting.LightMap m : seen)
            for (int k = 0; k < m.rgb.length; k += 3) { m.rgb[k] = 8; m.rgb[k + 1] = 0; m.rgb[k + 2] = 8; }
        System.out.printf("light.dump down: painted %,d downward-facing maps%n", seen.size());
    }

    /**
     * -Dlight.dump=black paints every lightmap that is still all zeroes: one whose every texel
     * fell inside something solid, so the direct pass skipped all of them and dilate, finding no
     * valid neighbour anywhere, gave up and left the map as it was allocated. Such a map draws
     * black. A surface can be perfectly visible and still be one of these - what the bake tested
     * was where it sampled, not the face.
     */
    private void black() {
        long painted = 0, all = 0;
        for (Lighting.LightMap m : h.lighting.maps()) {
            all++;
            boolean zero = true;
            for (int i = 0; i < m.rgb.length && zero; i++) zero = m.rgb[i] == 0;
            if (!zero) continue;
            painted++;
            for (int k = 0; k < m.rgb.length; k += 3) { m.rgb[k] = 8; m.rgb[k + 1] = 0; m.rgb[k + 2] = 8; }
        }
        System.out.printf("light.dump black: %,d of %,d maps are all zeroes (%.2f%%)%n",
                painted, all, 100.0 * painted / all);
    }

    /**
     * -Dlight.dump=tiny:N paints every lightmap no bigger than N texels either way, so a picture
     * says how much of itself is lit by a map too small to hold a gradient. A lightmap is at least
     * 2x2 because bilinear wants four corners, and at Haven's half-metre texel that is a metre
     * across: a converted slab smaller than that gets a map whose texel centres are off the slab
     * entirely, and answers with the light somewhere else. This runs after the cache is read and
     * before the first frame, which is why it costs four seconds and not ten minutes.
     */
    private void tiny(int n) {
        long painted = 0, all = 0;
        for (Lighting.LightMap m : h.lighting.maps()) {
            all++;
            if (m.w > n || m.h > n) continue;
            painted++;
            for (int k = 0; k < m.rgb.length; k += 3) { m.rgb[k] = 8; m.rgb[k + 1] = 0; m.rgb[k + 2] = 8; }
        }
        System.out.printf("light.dump tiny:%d: painted %,d of %,d maps (%.1f%%)%n",
                n, painted, all, 100.0 * painted / all);
    }

    /**
     * -Dlight.dump=sun:X0:Y0:X1:Y1:Z - the sun's own shadow on the plane at height Z over that
     * rectangle, one pixel every five centimetres, one hard ray to the centre of the sun each: what
     * the geometry says before any lightmap has had a texel to quantise it into. White is lit.
     * Written as sun.png beside the shots, with the lightmap's half-metre lattice drawn over it.
     */
    private void sunShadow(double x0, double y0, double x1, double y1, double z) throws Exception {
        Map<String, Object> spec = h.world.lighting != null ? h.world.lighting : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> sun = spec.get("sun") instanceof Map ? (Map<String, Object>) spec.get("sun") : Map.of();
        double el = Math.toRadians(World.num(sun, "elevation", 40));
        double sx = h.world.sunX * Math.cos(el), sy = h.world.sunY * Math.cos(el), sz = Math.sin(el);
        double step = 0.05, far = 200;
        int w = (int) Math.round((x1 - x0) / step), hh = (int) Math.round((y1 - y0) / step);
        Occluder oc = new Occluder(h.world);
        BufferedImage img = new BufferedImage(w, hh, BufferedImage.TYPE_INT_RGB);
        int lit = 0;
        for (int j = 0; j < hh; j++)
            for (int i = 0; i < w; i++) {
                double x = x0 + (i + 0.5) * step, y = y1 - (j + 0.5) * step;
                boolean open = oc.open(x, y, z) && oc.clear(x, y, z, x + sx * far, y + sy * far, z + sz * far);
                if (open) lit++;
                int c = open ? 0xE0E0E0 : 0x303030;
                double fu = (x - Math.floor(x / 0.5) * 0.5), fv = (y - Math.floor(y / 0.5) * 0.5);
                if (fu < step || fv < step) c = open ? 0xC08080 : 0x803030;     // the texel lattice
                img.setRGB(i, j, c);
            }
        File out = new File(dumpDir(), "sun.png");
        ImageIO.write(img, "png", out);
        System.out.printf("light.dump sun: %dx%d samples at z %.2f, %.0f%% lit, wrote %s%n",
                w, hh, z, 100.0 * lit / (w * hh), out);
    }

    private static File dumpDir() {
        String d = System.getProperty("light.dump.dir");
        return new File(d != null ? d : ".");
    }

    /**
     * -Dlight.dump=probe:X:Y: the stack of solid at one point of the map, top to bottom - every
     * shape whose outline actually contains (x, y), not merely whose map's box does, with where its
     * top and bottom are *at that point*, and what its top map says there, texel by texel. From
     * above, the first line is what a camera sees; the rest is what the bake may have had to reason
     * about underneath it.
     */
    private void probe(double x, double y) {
        record Layer(double top, double bottom, World.Shape s) {}
        java.util.List<Layer> at = new java.util.ArrayList<>();
        for (World.Shape s : h.world.shapes) {
            if (x < s.minX || x > s.maxX || y < s.minY || y > s.maxY) continue;
            boolean in = switch (s.kind) {
                case POLY -> Geometry.pointInPoly(x, y, s.xs, s.ys);
                case CIRCLE -> (x - s.cx) * (x - s.cx) + (y - s.cy) * (y - s.cy) <= s.r * s.r;
                default -> false;
            };
            if (in) at.add(new Layer(s.topAt(x, y), s.bottomAt(x, y), s));
        }
        at.sort((a, b) -> Double.compare(b.top(), a.top()));
        System.out.printf("light.dump probe %.3f,%.3f: %d shapes contain it%n", x, y, at.size());
        float[] c = new float[3];
        for (Layer l : at) {
            World.Shape s = l.s();
            Lighting.LightMap m = h.lighting.top(s);
            String light = "no top map";
            if (m != null) {
                m.sample(x, y, c);
                double fu = (x - m.u0) / m.step, fv = (y - m.v0) / m.step;
                int i = (int) Math.floor(fu), j = (int) Math.floor(fv);
                StringBuilder t = new StringBuilder();
                for (int dj = 0; dj <= 1; dj++)
                    for (int di = 0; di <= 1; di++) {
                        int ii = Math.max(0, Math.min(m.w - 1, i + di)), jj = Math.max(0, Math.min(m.h - 1, j + dj));
                        t.append(String.format(" (%.1f,%.1f)=%.2f", m.u(ii), m.v(jj), m.rgb[(jj * m.w + ii) * 3]));
                    }
                light = String.format("light %.3f from a %dx%d map;%s", c[0], m.w, m.h, t);
            }
            System.out.printf("   top %8.4f  bottom %8.4f  shape %-7d mat %-2d slope %8.4f,%8.4f %s %s%n",
                    l.top(), l.bottom(), s.id, s.topMat, s.hx, s.hy,
                    s.amap != null ? "CUT-OUT" : s.mask >= 0 ? "masked " : "solid  ", light);
        }
    }

    /** Every horizontal map whose grid covers the world point (x, y), smallest first: a horizontal
     *  map's u and v are the world's x and y, so standing somewhere and asking what is under your
     *  feet is one box test. The ground of a converted map is many planes at many heights, and the
     *  one a picture shows is rarely the biggest. */
    private void under(double x, double y) throws Exception {
        record Found(double z, String what, Lighting.LightMap m) {}
        java.util.List<Found> hit = new java.util.ArrayList<>();
        for (World.Shape s : h.world.shapes) {
            for (int k = 0; k < 2; k++) {
                Lighting.LightMap m = k == 0 ? h.lighting.top(s) : h.lighting.bottom(s);
                if (m == null || !covers(m, x, y)) continue;
                hit.add(new Found(k == 0 ? s.h : s.z0,
                        String.format("%s%d mat %d slope %.2g,%.2g", k == 0 ? "top " : "bottom ", s.id,
                                k == 0 ? s.topMat : s.mat,
                                k == 0 ? s.hx : s.zx, k == 0 ? s.hy : s.zy), m));
            }
        }
        for (World.Region r : h.world.regions) {
            if (h.lighting.floor(r) != null && covers(h.lighting.floor(r), x, y))
                hit.add(new Found(r.floor, "floor of region " + r.id, h.lighting.floor(r)));
            if (h.lighting.ceil(r) != null && covers(h.lighting.ceil(r), x, y))
                hit.add(new Found(r.ceil, "ceiling of region " + r.id, h.lighting.ceil(r)));
        }
        hit.sort((a, b) -> Double.compare(a.z(), b.z()));
        // Hundreds of converted triangles share one merged map, so the interesting count is how
        // many distinct maps there are, not how many faces point at them.
        java.util.Map<Lighting.LightMap, int[]> seen = new java.util.IdentityHashMap<>();
        java.util.List<Found> firsts = new java.util.ArrayList<>();
        for (Found f : hit) {
            int[] c = seen.get(f.m());
            if (c == null) { seen.put(f.m(), new int[]{1}); firsts.add(f); } else c[0]++;
        }
        System.out.printf("light.dump at %.2f,%.2f: %d horizontal faces over this point, on %d distinct maps%n",
                x, y, hit.size(), firsts.size());
        int n = 0;
        for (Found f : firsts) {
            float[] c = new float[3];
            f.m().sample(x, y, c);
            System.out.printf("   z %8.3f  %-38s %4d x %-4d texels, %4d faces  light here %.3f %.3f %.3f%n",
                    f.z(), f.what(), f.m().w, f.m().h, seen.get(f.m())[0], c[0], c[1], c[2]);
            // A small map, texel by texel with the world point each one stands on: two maps snapped
            // to the same lattice sample the same places, so this says whether they disagree about
            // the light or merely about where they measured it.
            Lighting.LightMap m = f.m();
            if (m.w * m.h <= 12) {
                StringBuilder b = new StringBuilder("        ");
                for (int j = 0; j < m.h; j++)
                    for (int i = 0; i < m.w; i++)
                        b.append(String.format("(%.2f,%.2f)=%.3f ", m.u(i), m.v(j), m.rgb[(j * m.w + i) * 3]));
                System.out.println(b);
            }
            if (n < 12) write("at" + n++, f.m());
        }
    }

    private static boolean covers(Lighting.LightMap m, double x, double y) {
        return x >= m.u0 && x <= m.u0 + (m.w - 1) * m.step && y >= m.v0 && y <= m.v0 + (m.h - 1) * m.step;
    }

    private void write(String name, Lighting.LightMap m) throws Exception {
        {
            System.out.printf("light.dump %s: %d x %d texels of %.3f m, u %.2f..%.2f, v %.2f..%.2f%s%n",
                    name, m.w, m.h, m.step, m.u0, m.u0 + (m.w - 1) * m.step,
                    m.v0, m.v0 + (m.h - 1) * m.step, m.base != null ? " (a view onto a shared map)" : "");
            float[] rgb = m.rgb;
            float hi = 0;
            for (float v : rgb) hi = Math.max(hi, v);
            if (hi <= 0) hi = 1;
            int scale = Math.max(1, Math.min(16, 512 / Math.max(m.w, m.h)));
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(m.w * scale, m.h * scale, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int j = 0; j < m.h; j++)
                for (int i = 0; i < m.w; i++) {
                    int k = (j * m.w + i) * 3;
                    int r = lvl(rgb[k] / hi), g = lvl(rgb[k + 1] / hi), b = lvl(rgb[k + 2] / hi);
                    int c = (r << 16) | (g << 8) | b;
                    for (int dy = 0; dy < scale; dy++)
                        for (int dx = 0; dx < scale; dx++) img.setRGB(i * scale + dx, j * scale + dy, c);
                }
            File out = new File(dumpDir(), "lightmap-" + name + ".png");
            javax.imageio.ImageIO.write(img, "png", out);
            System.out.println("wrote " + out + "  (peak " + hi + ", " + scale + " px a texel)");
        }
    }

    private World.Shape shape(int id) {
        for (World.Shape s : h.world.shapes) if (s.id == id) return s;
        throw new IllegalArgumentException("no shape " + id);
    }

    private static int lvl(double v) {
        return (int) Math.round(255 * Math.pow(Math.max(0, Math.min(1, v)), 1 / 2.2));
    }
}

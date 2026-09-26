package engine;

import java.util.Arrays;

/**
 * The command line, read once into one object.
 *
 * It lived in main() next to the game's own start-up, which meant a hundred and thirty lines of
 * string handling in front of the two that load the map and open the window, and no way to check
 * any of it without starting a JVM and looking at what came out. It is a small pure function -
 * arguments in, settings out - and it is now written as one.
 *
 * Every option is validated here rather than where it is used, and a bad one prints what was
 * wanted and stops. A render size that quietly clamped itself would be worse than one that
 * refuses: the picture would come out, and be the wrong size, and nothing would say why.
 */
public final class Options {
    public String map = "maps/school.json";
    public String shot, shots, verify, gpuVerify;
    /** --shot's optional "x y heading pitch [column]". */
    public double[] at;
    public boolean bench, shear, flat;
    /** --play: the game with nothing on the screen but the game - no overlay, no minimap, no
     *  toggles, no flying. See {@code game.Play}. */
    public boolean play;
    /** --hdr: shade in light and tone map at the end, instead of multiplying sRGB numbers. */
    public boolean hdr;
    /**
     * Shade the frame on the graphics card. On by default since the card was finished: it draws
     * the same picture three to four times faster, and the CPU path stays as the thing that says
     * what the picture should have been. --cpu asks for that path instead.
     */
    public boolean gpu = true;
    /** Did the command line say --gpu out loud? Only that puts the card back for the runs
     *  below that would otherwise take the CPU path. */
    private boolean gpuAsked;
    public int w = Host.DEFAULT_W, h = Host.DEFAULT_H, ss = 1;
    public int winW = Host.DEFAULT_WINDOW_W, winH = Host.DEFAULT_WINDOW_H;
    public int targetFps = 60;
    public double startFeet = Double.NaN;
    /** --fov: the field of view in degrees, or NaN for the renderer's own. It is the one camera
     *  setting that belongs on the command line rather than to a game: a screenshot and the window
     *  it was framed in have to agree about it, and only one of the two has a key to press. */
    public double fov = Double.NaN;

    private Options() {}

    /** Does this command line open a window? A headless run must not ask about the keyboard. */
    public static boolean headless(String[] args) {
        return Arrays.stream(args).anyMatch(a ->
                a.equals("--shot") || a.equals("--shots") || a.equals("--bench")
                        || a.equals("--verify") || a.equals("--gpu-verify"));
    }

    /** The settings this command line asks for, or null when it has already been refused and
     *  explained. */
    public static Options parse(String[] args) {
        Options o = new Options();
        return o.read(args) ? o : null;
    }

    private boolean read(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--bench")) {
                bench = true;
            } else if (args[i].equals("--shear")) {
                shear = true;
            } else if (args[i].equals("--shade")) {
                // One switch rather than two, because it is one choice. Which way it goes by
                // default depends on the mode - the card for a window, the CPU for anything that
                // writes depth and albedo - so both directions have to be sayable.
                if (i + 1 >= args.length) { usage("--shade needs card or cpu"); return false; }
                String who = args[++i];
                if (who.equals("card")) { gpu = true; gpuAsked = true; }
                else if (who.equals("cpu")) gpu = false;
                else { usage("--shade wants card or cpu, not " + who); return false; }
            } else if (args[i].equals("--gpu")) {                // the old spelling of --shade card
                gpu = true;
                gpuAsked = true;
            } else if (args[i].equals("--cpu")) {                // and of --shade cpu
                gpu = false;
            } else if (args[i].equals("--play")) {
                play = true;
            } else if (args[i].equals("--hdr")) {
                hdr = true;
            } else if (args[i].equals("--unlit") || args[i].equals("--flat")) {
                // --flat was the old name, and it reads like a camera that does not tilt rather
                // than a world with no lightmaps in it. Kept, because the scripts say it.
                flat = true;
            } else if (args[i].equals("--size")) {
                if (i + 1 >= args.length) { usage("--size needs a WxH value, e.g. 1280x720"); return false; }
                String[] wh = args[++i].toLowerCase().split("x");
                if (wh.length != 2) { usage("--size wants WxH, e.g. 1280x720, not " + args[i]); return false; }
                try {
                    w = Integer.parseInt(wh[0].trim());
                    h = Integer.parseInt(wh[1].trim());
                } catch (NumberFormatException e) {
                    usage("--size wants two whole numbers, e.g. 1280x720, not " + args[i]);
                    return false;
                }
                if (w < 16 || h < 16 || w > 16384 || h > 16384) { usage("--size must be between 16x16 and 16384x16384"); return false; }
            } else if (args[i].equals("--fps")) {
                if (i + 1 >= args.length) { usage("--fps needs a frame rate, e.g. 60 (0 keeps the render size fixed)"); return false; }
                try {
                    targetFps = Integer.parseInt(args[++i].trim());
                } catch (NumberFormatException e) {
                    usage("--fps wants a whole number, e.g. 60, not " + args[i]);
                    return false;
                }
                if (targetFps < 0 || targetFps > 1000) { usage("--fps must be between 0 and 1000"); return false; }
            } else if (args[i].equals("--window")) {
                if (i + 1 >= args.length) { usage("--window needs a WxH value, e.g. 1920x1080"); return false; }
                String[] wh = args[++i].toLowerCase().split("x");
                try {
                    winW = Integer.parseInt(wh[0].trim());
                    winH = Integer.parseInt(wh[1].trim());
                } catch (RuntimeException e) {
                    usage("--window wants two whole numbers, e.g. 1920x1080, not " + args[i]);
                    return false;
                }
                if (winW < 160 || winH < 90 || winW > 16384 || winH > 16384) { usage("--window must be between 160x90 and 16384x16384"); return false; }
            } else if (args[i].equals("--ss")) {
                if (i + 1 >= args.length) { usage("--ss needs a factor, e.g. 2"); return false; }
                try {
                    ss = Integer.parseInt(args[++i].trim());
                } catch (NumberFormatException e) {
                    usage("--ss wants a whole number, e.g. 2, not " + args[i]);
                    return false;
                }
                if (ss < 1 || ss > 8) { usage("--ss must be between 1 and 8"); return false; }
            } else if (args[i].equals("--fov")) {
                if (i + 1 >= args.length) { usage("--fov needs an angle in degrees, e.g. 90"); return false; }
                try {
                    fov = Double.parseDouble(args[++i].trim());
                } catch (NumberFormatException e) {
                    usage("--fov wants a number of degrees, e.g. 90, not " + args[i]);
                    return false;
                }
                // The same range the game's own [ and ] keys keep to: under 30 the picture is a
                // telescope, over 120 the projection stretches the edges past any use.
                if (fov < 30 || fov > 120) { usage("--fov must be between 30 and 120 degrees"); return false; }
            } else if (args[i].equals("--feet")) {
                if (i + 1 >= args.length) { usage("--feet needs a height in metres, e.g. 3.6"); return false; }
                try {
                    startFeet = Double.parseDouble(args[++i].trim());
                } catch (NumberFormatException e) {
                    usage("--feet wants a number, e.g. 3.6, not " + args[i]);
                    return false;
                }
            } else if (args[i].equals("--shots")) {
                if (i + 1 >= args.length) { usage("--shots needs a file of views"); return false; }
                shots = args[++i];
            } else if (args[i].equals("--verify")) {
                if (i + 1 >= args.length) { usage("--verify needs a file of views"); return false; }
                verify = args[++i];
            } else if (args[i].equals("--gpu-verify")) {
                if (i + 1 >= args.length) { usage("--gpu-verify needs a file of views"); return false; }
                gpuVerify = args[++i];
                gpu = true;
                gpuAsked = true;
            } else if (args[i].equals("--shot")) {
                if (i + 1 >= args.length) { usage("--shot needs an output file"); return false; }
                shot = args[++i];
                // optionally followed by: x y angle pitch [column]
                double[] nums = new double[5];
                int n = 0;
                while (n < 5 && i + 1 < args.length && args[i + 1].matches("-?[0-9.]+")) nums[n++] = Double.parseDouble(args[++i]);
                if (n >= 4) at = Arrays.copyOf(nums, n);
            } else if (args[i].startsWith("--")) {
                usage("unknown option " + args[i]);
                return false;
            } else {
                map = args[i];
            }
        }
        // Two kinds of run take the CPU path unless the command line asks for the card by name.
        //
        // --verify is the golden test's instrument, and a digest of the card's pixels is not one:
        // it works in float where the renderer works in double, so the two agree to about a level
        // in 255 and neither is reproducible from the other. The comparison that does belong to
        // the card is --gpu-verify, which compares pictures rather than hashing one.
        //
        // --shot and --shots have a duller reason: they write depth and albedo as well as a
        // picture, and those come from the CPU walking every row itself (see Renderer, where a
        // frame with a depth buffer shades on the CPU whether or not the card was given the
        // surface). So a screenshot pays for the CPU's shading regardless, and handing the
        // colours to the card afterwards buys nothing but a different rounding - and loses the
        // byte-for-byte comparison that every change to the renderer is checked with.
        if ((verify != null || shot != null || shots != null) && !gpuAsked) gpu = false;
        if ((long) w * ss > 16384 || (long) h * ss > 16384) {
            usage("--size times --ss must stay within 16384x16384 (that would be " + w * ss + "x" + h * ss + ")");
            return false;
        }
        return true;
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("usage: java -cp out game.Main [map.json] [--size WxH] [--ss N] [--bench]");
        System.err.println("       java -cp out game.Main [map.json] [--size WxH] [--ss N] --shot out.png [x y angle pitch [column]]");
        System.err.println("       java -cp out game.Main [map.json] [--size WxH] --shots views.txt   (one \"out.png x y feet heading [pitch]\" per line)");
        System.err.println("  --size  render resolution, the ray count (default " + Host.DEFAULT_W + "x" + Host.DEFAULT_H + ")");
        System.err.println("  --window  window size; the render is scaled up to it (default " + Host.DEFAULT_WINDOW_W + "x" + Host.DEFAULT_WINDOW_H + ", fitted to the screen)");
        System.err.println("  --feet  starting floor height in metres, to begin on an upper storey (e.g. 3.6)");
        System.err.println("  --fov   field of view in degrees, 30-120 (default " + Math.round(Renderer.DEFAULT_FOV) + ")");
        System.err.println("  --fps   frame-time target the render size is driven to; 0 keeps it fixed (default 60)");
        System.err.println("  --shear look up / down the old way (y-shearing) instead of true perspective");
        System.err.println("  --shade card|cpu   where the frame is shaded: the card by default and faster,");
        System.err.println("          the CPU by default for --verify, --shot and --shots, which write");
        System.err.println("          depth and albedo. --gpu and --cpu are the old spellings of the two");
        System.err.println("  --play  no overlay, no minimap, no toggles, no flying: just the world");
        System.err.println("  --hdr   shade in light and roll the highlights off filmically (H toggles it)");
        System.err.println("  --unlit skip baking the lightmaps and use the old flat lighting");
        System.err.println("          (--flat was its old name, and the scripts still say it)");
        System.err.println("  --ss    supersampling factor 1-8: renders at size*N and averages down (default 1).");
        System.err.println("          Rays cast per frame = width * N.");
        System.err.println("  --verify views.txt   print a digest of each view instead of writing files (see test.sh)");
        System.err.println("  --gpu-verify views.txt   draw each view both ways, at several pitches, and compare");
    }
}

package engine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/** The command line. Now that it is a function from arguments to settings, it can be asked what it
 *  makes of an argument without starting a game to find out. */
final class OptionsTest {
    private OptionsTest() {}

    /** Parse, with the usage text swallowed; null means it was refused. */
    private static Options parse(String... args) {
        PrintStream err = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            return Options.parse(args);
        } finally {
            System.setErr(err);
        }
    }

    private static boolean refuses(String... args) {
        return parse(args) == null;
    }

    static void run() {
        Check.group("Options");

        Options d = parse();
        Check.eq(d.map, "maps/school.json", "the default map");
        Check.eq(d.w + "x" + d.h, Host.DEFAULT_W + "x" + Host.DEFAULT_H, "the default render size");
        Check.eq(d.ss, 1, "no supersampling by default");
        Check.eq(d.targetFps, 60, "dynamic resolution aims at 60 by default");
        Check.that(Double.isNaN(d.startFeet), "no starting height until --feet says one");

        Options o = parse("maps/haven/haven.json", "--size", "800x600", "--ss", "2", "--window", "1600x900",
                "--fps", "0", "--feet", "3.6", "--flat", "--shear");
        Check.eq(o.map, "maps/haven/haven.json", "a map named without a flag");
        Check.eq(o.w, 800, "--size width");
        Check.eq(o.h, 600, "--size height");
        Check.eq(o.ss, 2, "--ss");
        Check.eq(o.winW, 1600, "--window width");
        Check.eq(o.targetFps, 0, "--fps 0 turns dynamic resolution off");
        Check.eq(o.startFeet, 3.6, 1e-12, "--feet");
        Check.that(o.flat && o.shear, "--flat and --shear");
        Check.eq(parse("--size", "1280X720").w, 1280, "--size takes a capital X too");

        Options shot = parse("--shot", "a.png", "16", "21.6", "-100", "5");
        Check.eq(shot.shot, "a.png", "--shot's file");
        Check.eq(shot.at == null ? -1 : shot.at.length, 4, "and the view after it");
        Check.eq(shot.at[2], -100.0, 1e-12, "a negative heading is a number, not a flag");
        Check.that(parse("--shot", "a.png").at == null, "--shot on its own uses wherever the player is");
        Check.that(Options.headless(new String[] {"--shot", "a.png"}), "--shot opens no window");
        Check.that(Options.headless(new String[] {"--verify", "v.txt"}), "nor does --verify");
        Check.that(!Options.headless(new String[] {"--size", "800x600"}), "an ordinary run does");

        Check.that(refuses("--size"), "--size with nothing after it");
        Check.that(refuses("--size", "1280"), "--size without a height");
        Check.that(refuses("--size", "12x12"), "a render size below the minimum");
        Check.that(refuses("--size", "99999x99999"), "and one above the maximum");
        Check.that(refuses("--size", "99999x1", "--ss", "1"), "a width over the limit on its own");
        Check.that(refuses("--size", "8192x4096", "--ss", "8"), "--size times --ss over the limit");
        Check.that(refuses("--ss", "0"), "--ss below one");
        Check.that(refuses("--ss", "9"), "--ss above eight");
        Check.that(refuses("--fps", "-1"), "a negative frame rate");
        Check.that(refuses("--feet", "high"), "--feet that is not a number");
        Check.that(refuses("--shots"), "--shots with no file");
        Check.that(refuses("--verify"), "--verify with no file");
        Check.that(refuses("--wat"), "an option nobody has heard of");
    }
}

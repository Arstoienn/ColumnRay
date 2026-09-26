package game;

import engine.Capture;
import engine.Game;
import engine.Host;
import engine.Keys;
import engine.Options;
import engine.World;
import java.io.File;
import java.nio.file.Path;

/**
 * Where a run starts: read the command line, load the map, build the engine, and either play or
 * capture.
 *
 * The engine has no {@code main} of its own, deliberately. It is a library that a game drives, and
 * the order things happen in at start-up - ask the keyboard what its keys are called, ask for a
 * graphics context, open a window - is the game's to get right, because it is the game that knows
 * whether a window is going to open at all.
 *
 * Usage: java -cp out game.Main [map.json] [--shot out.png [x y angle pitch [column]]] [--bench]
 */
public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        // Before AWT starts, and only when a window is going to open - see Keys. A headless run
        // has no HUD to name, and on a machine with no window service to ask, the question hangs.
        if (!Options.headless(args)) Keys.readLabels();
        Options o = Options.parse(args);
        if (o == null) return;
        if (Options.headless(args)) System.setProperty("java.awt.headless", "true");
        // Also before AWT, and for the same sort of reason: a graphics context asked for after the
        // toolkit has started gets no accelerated pixel format on macOS. Host.graphicsCard() says
        // why not, when there is no card; the CPU renderer runs anywhere.
        if (o.gpu) o.gpu = Host.graphicsCard();

        Host host = new Host(World.load(Path.of(o.map)), o);
        // Two games on one engine: the sandbox, which shows what the renderer is doing, and --play,
        // which shows the world and nothing else. Both are a Game and neither is the engine's
        // business, so this line is the only place that chooses.
        Game game = o.play ? new Play(host, o.startFeet) : new Sandbox(host, o.startFeet);
        Capture capture = new Capture(host, game);
        if (o.bench) capture.bench();
        else if (o.verify != null) capture.verify(Path.of(o.verify));
        else if (o.gpuVerify != null) capture.gpuVerify(Path.of(o.gpuVerify));
        else if (o.shots != null) capture.screenshots(Path.of(o.shots));
        else if (o.shot != null) capture.screenshot(new File(o.shot), o.at);
        else host.run(game);
    }
}

package game;

import engine.Check;
import engine.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Walking, falling, stepping and looking.
 *
 * None of this could be asked a question before: it was sixty lines in the middle of the window
 * loop that read the keyboard itself, so the only way to find out what happened when you walked
 * into a wall was to walk into one.
 */
public final class PlayerTest {
    private PlayerTest() {}

    /**
     * A room 8 m square at floor 0, with a low platform one step up along its east side, a taller
     * block beside it that is too high to climb, and a wall at x = 4 with a doorway in it.
     */
    private static final String MAP = """
            { "name": "gym", "cell": 2.0, "sun": [1, 0],
              "spawn": { "pos": [2, 4], "angle": 0 },
              "regions": [
                { "name": "room",     "poly": [[0,0],[8,0],[8,8],[0,8]], "floor": 0,   "ceil": 3 },
                { "name": "platform", "poly": [[6,0],[8,0],[8,8],[6,8]], "floor": 0.3, "ceil": 3 }
              ],
              "shapes": [
                { "type": "wall", "a": [4,0], "b": [4,3.5], "z0": 0, "h": 3 },
                { "type": "wall", "a": [4,4.5], "b": [4,8], "z0": 0, "h": 3 },
                { "type": "box",  "x": 1, "y": 6, "w": 1, "d": 1, "z0": 0, "h": 1.2 }
              ] }
            """;

    private static Player.Move walk(double forward, double strafe) {
        return new Player.Move(0, 0, forward, strafe, 0, 0, false, false, false);
    }

    /** Hold a move for a second of game time, in sixtieths. */
    private static void hold(Player p, Player.Move m) {
        for (int i = 0; i < 60; i++) p.step(1 / 60.0, m);
    }

    public static void run() throws IOException {
        Check.group("Player");

        Path file = Files.createTempFile("columnray-player", ".json");
        World w;
        try {
            Files.writeString(file, MAP);
            w = World.load(file);
        } finally {
            Files.deleteIfExists(file);
        }

        Player p = new Player(w);
        Check.eq(p.x, 2.0, 1e-9, "the player starts at the map's spawn");
        Check.eq(p.feet, 0.0, 1e-9, "standing on the floor");
        Check.that(p.grounded, "and knows it");
        Check.eq(p.eye(), Player.EYE_STAND, 1e-9, "eye height at rest");

        // Walking east from (2, 4) reaches the doorway in the wall at x = 4 and goes through it.
        hold(p, walk(1, 0));
        Check.that(p.x > 4, "walking through a doorway, got x = " + p.x);

        // Walking east from (2, 2) meets the wall instead and stops just short of it.
        Player blocked = new Player(w);
        blocked.y = 2;
        hold(blocked, walk(1, 0));
        Check.that(blocked.x < 4 - Player.RADIUS + 0.05 && blocked.x > 3,
                "a wall stops the player just short of it, got x = " + blocked.x);
        hold(blocked, walk(1, 0));
        Check.that(blocked.x < 4, "and holding the key does not push through it");

        // The same wall is nothing at all when flying.
        Player flier = new Player(w);
        flier.y = 2;
        flier.flying = true;
        hold(flier, walk(1, 0));
        Check.that(flier.x > 4, "flying goes through walls, which is what it is for");

        // A 0.3 m platform is under STEP, so it is walked onto rather than into.
        Player stepper = new Player(w);
        stepper.y = 4;
        hold(stepper, walk(1, 0));
        hold(stepper, walk(1, 0));
        Check.eq(stepper.feet, 0.3, 1e-6, "stepping up onto a low platform");
        Check.that(stepper.viewFeet < 0.3,
                "and the camera is still on its way up - a step that teleported the eye reads as a lift");

        // A 1.2 m block is not.
        Player stopped = new Player(w);
        stopped.x = 0.5;
        stopped.y = 6.5;
        hold(stopped, walk(1, 0));
        Check.eq(stopped.feet, 0.0, 1e-9, "a block above stepping height is not climbed");
        Check.that(stopped.x < 1, "it is walked into, got x = " + stopped.x);

        // Gravity: dropped from above, land on the floor and stay there.
        Player falling = new Player(w);
        falling.feet = 2.5;
        falling.grounded = false;
        hold(falling, Player.Move.NONE);
        Check.eq(falling.feet, 0.0, 1e-9, "what goes up");
        Check.that(falling.grounded, "lands");
        Check.eq(falling.vz, 0.0, 1e-9, "and stops falling");

        // Looking up and down stops at the pitch the warp is built for.
        Player looker = new Player(w);
        for (int i = 0; i < 600; i++) looker.step(1 / 60.0, new Player.Move(0, 1, 0, 0, 0, 0, false, false, false));
        Check.eq(looker.pitch, World.MAX_PITCH, 1e-9, "looking up stops at MAX_PITCH");
        for (int i = 0; i < 1200; i++) looker.step(1 / 60.0, new Player.Move(0, -1, 0, 0, 0, 0, false, false, false));
        Check.eq(looker.pitch, -World.MAX_PITCH, 1e-9, "and looking down at -MAX_PITCH");

        // Crouching lowers the eye; standing up again raises it.
        Player crouch = new Player(w);
        hold(crouch, new Player.Move(0, 0, 0, 0, 0, 0, true, false, false));
        Check.that(crouch.eye() < Player.EYE_CROUCH + 0.05, "crouching, eye at " + crouch.eye());
        hold(crouch, Player.Move.NONE);
        Check.that(crouch.eye() > Player.EYE_STAND - 0.05, "and standing back up");

        // --feet: start on whichever storey has a floor at that height.
        Player upstairs = new Player(w);
        upstairs.x = 7;
        upstairs.standOn(0.3);
        Check.eq(upstairs.feet, 0.3, 1e-9, "--feet starts on the platform");
        Check.eq(upstairs.where(), "platform",
                "and the HUD names the storey they are actually on");

        // --shots and --verify stand exactly where they are told, floor or no floor.
        Player exact = new Player(w);
        exact.standAt(1.75);
        Check.eq(exact.feet, 1.75, 1e-12, "standAt does not snap to the ground under it");
        Check.eq(exact.viewFeet, 1.75, 1e-12, "and the camera is there straight away, not easing towards it");
    }
}

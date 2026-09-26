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
     * block beside it that is too high to climb, a wall at x = 4 with a doorway in it, and a ledge
     * in the west corner high enough that walking off it is a fall rather than a step down.
     */
    private static final String MAP = """
            { "name": "gym", "cell": 2.0, "sun": [1, 0],
              "spawn": { "pos": [2, 4], "angle": 0 },
              "regions": [
                { "name": "room",     "poly": [[0,0],[8,0],[8,8],[0,8]], "floor": 0,   "ceil": 4 },
                { "name": "platform", "poly": [[6,0],[8,0],[8,8],[6,8]], "floor": 0.3, "ceil": 4 },
                { "name": "ledge",    "poly": [[0,0],[1.5,0],[1.5,1.5],[0,1.5]], "floor": 1.2, "ceil": 4 }
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

    private static final Player.Move JUMP = new Player.Move(0, 0, 0, 0, 0, 0, false, false, true);

    /** Hold a move for this many sixtieths of a second. */
    private static void hold(Player p, Player.Move m, int frames) {
        for (int i = 0; i < frames; i++) p.step(1 / 60.0, m);
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

        // The walk has a velocity in it: a step takes a moment to reach its pace, and a moment to
        // lose it. Both are short - under a tenth of a second - which is what separates weight from
        // sluggishness, so both ends are checked rather than just the first.
        Player accel = new Player(w);
        accel.y = 4;
        accel.step(1 / 60.0, walk(1, 0));
        Check.that(accel.speed() < Player.WALK * 0.9,
                "one frame of walking is not full pace yet, got " + accel.speed() + " m/s");
        hold(accel, walk(1, 0), 12);
        Check.eq(accel.speed(), Player.WALK, 1e-9, "a fifth of a second in, it is");
        hold(accel, Player.Move.NONE, 12);
        Check.eq(accel.speed(), 0, 1e-12, "and letting go stops it as quickly");

        // Running is faster than walking, and a diagonal is not faster than either.
        Player fast = new Player(w);
        fast.y = 4;
        hold(fast, new Player.Move(0, 0, 1, 0, 0, 0, false, true, false), 30);
        Check.eq(fast.speed(), Player.RUN, 1e-9, "Shift runs");
        Player diagonal = new Player(w);
        diagonal.y = 4;
        hold(diagonal, walk(1, 1), 30);
        Check.eq(diagonal.speed(), Player.WALK, 1e-9, "and a diagonal is the same pace, not root two of it");

        // A jump keeps what it left the ground with: letting go of the keys in mid-air used to stop
        // the player dead in the air, which no thrown object does.
        Player leap = new Player(w);
        leap.y = 4;
        hold(leap, walk(1, 0), 30);
        double pace = leap.speed();
        leap.step(1 / 60.0, new Player.Move(0, 0, 1, 0, 0, 0, false, false, true));
        Check.that(!leap.grounded, "jumping leaves the ground");
        hold(leap, Player.Move.NONE, 6);
        Check.that(leap.speed() > pace * 0.9,
                "and carries its momentum with nothing held, got " + leap.speed() + " of " + pace);

        // Coyote time: walking off the ledge and asking for a jump a frame later still jumps.
        Player edge = new Player(w);
        edge.x = 0.75;
        edge.y = 0.75;
        edge.standOn(1.2);
        Check.eq(edge.feet, 1.2, 1e-9, "standing on the ledge to walk off it");
        int frames = 0;
        while (edge.grounded && frames++ < 120) edge.step(1 / 60.0, walk(1, 0));
        Check.that(!edge.grounded, "walked off the edge after " + frames + " frames");
        edge.step(1 / 60.0, JUMP);
        Check.that(edge.vz > 4, "a jump just after the edge still jumps, got vz = " + edge.vz);

        // And it is an allowance, not a second jump: wait in the air and there is nothing to jump with.
        Player late = new Player(w);
        late.feet = 2.5;
        late.grounded = false;
        hold(late, Player.Move.NONE, 20);
        double descending = late.vz;
        late.step(1 / 60.0, JUMP);
        Check.that(late.vz < descending, "a jump in mid-air is not a jump, got vz = " + late.vz);

        // Jump buffering: asked for just before landing, it fires on landing rather than being eaten.
        Player early = new Player(w);
        early.feet = 0.5;
        early.grounded = false;
        hold(early, Player.Move.NONE, 10);
        Check.that(!early.grounded, "still in the air when the jump is asked for");
        early.step(1 / 60.0, JUMP);
        hold(early, Player.Move.NONE, 4);
        Check.that(early.vz > 4, "the buffered jump fired on landing, got vz = " + early.vz);

        // The camera dips on landing and springs back, and never moves the body.
        Player landing = new Player(w);
        landing.y = 4;
        landing.feet = 3;
        landing.grounded = false;
        while (!landing.grounded) landing.step(1 / 60.0, Player.Move.NONE);
        landing.step(1 / 60.0, Player.Move.NONE);
        Check.that(landing.eye() < landing.viewFeet + Player.EYE_STAND - 0.02,
                "landing dips the camera, by " + (landing.viewFeet + Player.EYE_STAND - landing.eye()) + " m");
        Check.eq(landing.feet, 0, 1e-9, "without moving the feet");
        hold(landing, Player.Move.NONE, 60);
        Check.eq(landing.eye(), Player.EYE_STAND, 1e-6, "and springs back to level within a second");

        // The stride bobs the camera, by centimetres, and stops where it started.
        Player bob = new Player(w);
        bob.y = 4;
        double highest = 0, lowest = 0;
        for (int i = 0; i < 60; i++) {
            bob.step(1 / 60.0, walk(1, 0));
            double off = bob.eye() - bob.viewFeet - Player.EYE_STAND;
            highest = Math.max(highest, off);
            lowest = Math.min(lowest, off);
        }
        Check.that(highest > 0.004 && highest < Player.BOB,
                "walking bobs the camera up by " + highest + " m");
        Check.that(lowest < -0.004, "and down by " + (-lowest) + " m");
        hold(bob, Player.Move.NONE, 60);
        Check.eq(bob.eye() - bob.viewFeet, Player.EYE_STAND, 1e-6,
                "and standing still brings it back to level");

        // A camera that must not move says so, and then nothing the stride or a landing does reaches it.
        Player still = new Player(w);
        still.y = 4;
        still.cameraMotion = 0;
        hold(still, walk(1, 0), 60);
        Check.eq(still.eye(), still.viewFeet + Player.EYE_STAND, 1e-12,
                "cameraMotion = 0 walks without bobbing");

        // And a camera placed for a screenshot is level whatever it was doing a moment ago.
        Player placed = new Player(w);
        placed.y = 4;
        hold(placed, walk(1, 0), 20);
        placed.standAt(1.75);
        Check.eq(placed.eye(), 1.75 + Player.EYE_STAND, 1e-12,
                "standAt leaves no stride or landing in the camera");
        Check.eq(placed.speed(), 0, 1e-12, "and no speed in the body");
    }
}

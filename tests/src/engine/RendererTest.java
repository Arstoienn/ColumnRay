package engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The renderer's view may only be changed between frames, from the thread that renders. That used
 * to be a sentence in a doc comment; these are the tests that it is now a rule.
 */
final class RendererTest {
    private RendererTest() {}

    private static final String MAP = """
            { "name": "one room", "cell": 2.0, "sun": [1, 0],
              "spawn": { "pos": [2, 2], "angle": 0 },
              "regions": [ { "name": "room", "poly": [[0,0],[8,0],[8,8],[0,8]], "floor": 0, "ceil": 3 } ] }
            """;

    static void run() throws Exception {
        Check.group("Renderer");

        Path file = Files.createTempFile("columnray-renderer", ".json");
        World w;
        try {
            Files.writeString(file, MAP);
            w = World.load(file);
        } finally {
            Files.deleteIfExists(file);
        }

        int rw = 64, rh = 32;
        Renderer r = new Renderer(w, rw, rh, new int[rw * rh]);

        // Before anything has rendered, nobody owns the renderer and any thread may set it up.
        onThread(() -> r.setFov(75));
        Check.eq(r.fov(), 75.0, 1e-9, "the view can be built from whichever thread is doing the setting up");

        Renderer.Camera cam = new Renderer.Camera();
        cam.x = 2;
        cam.y = 2;
        r.render(cam);

        // From now on it belongs to the thread that rendered it.
        Check.that(r.fov() == 75.0, "rendering does not change the field of view");
        r.setFov(60);
        Check.eq(r.fov(), 60.0, 1e-9, "the rendering thread may still change it, between frames");

        Check.that(refused(() -> r.setFov(90)), "setFov from another thread is refused");
        Check.that(refused(() -> r.setView(32, 16)), "setView from another thread is refused");
        Check.that(refused(() -> r.resize(32, 16, new int[32 * 16])), "resize from another thread is refused");
        Check.that(refused(() -> r.setLighting(null)), "setLighting from another thread is refused");
        Check.that(refused(() -> r.render(cam)), "and so is rendering it from two threads");
        Check.eq(r.fov(), 60.0, 1e-9, "a refused call changes nothing");

        // The message has to name the thread, or it is no better than the comment it replaced.
        String why = why(() -> r.setFov(90));
        Check.that(why.contains("setFov") && why.contains(Thread.currentThread().getName()),
                "the refusal names the call and the thread that owns the renderer, got: " + why);
    }

    private static void onThread(Runnable body) throws InterruptedException {
        Thread t = new Thread(body, "columnray-test-worker");
        t.start();
        t.join();
    }

    private static boolean refused(Runnable body) throws InterruptedException {
        return why(body) != null;
    }

    /** Run on another thread and hand back why it was refused, or null if it was not. */
    private static String why(Runnable body) throws InterruptedException {
        AtomicReference<String> failure = new AtomicReference<>();
        onThread(() -> {
            try {
                body.run();
            } catch (IllegalStateException e) {
                failure.set(e.getMessage());
            }
        });
        return failure.get();
    }
}

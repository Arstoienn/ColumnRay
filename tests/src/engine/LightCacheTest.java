package engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The lightmap cache's key.
 *
 * A wrong key here is the worst kind of bug this project can have: it does not crash, it does not
 * look wrong in a diff, it just hands you a bake from before the change you are trying to measure.
 */
final class LightCacheTest {
    private LightCacheTest() {}

    static void run() throws IOException {
        Check.group("LightCache");

        // Every class in the engine is either part of the bake or deliberately not. This is the
        // test that makes the lists safe to keep by hand: add a class, and this fails on the next
        // run rather than the cache going quietly stale months later.
        List<String> unclassified = new ArrayList<>();
        Path src = Path.of("src/engine");
        Check.that(Files.isDirectory(src), "the engine's sources are where this test expects them");
        if (Files.isDirectory(src)) {
            try (Stream<Path> files = Files.list(src)) {
                for (Path f : files.toList()) {
                    String java = f.getFileName().toString();
                    if (!java.endsWith(".java")) continue;
                    String cls = java.substring(0, java.length() - ".java".length()) + ".class";
                    if (LightCache.bakes(cls) == null) unclassified.add(java);
                }
            }
        }
        Check.that(unclassified.isEmpty(),
                "every engine class is listed in LightCache.BAKES or BAKES_NOT; these are not: " + unclassified);

        Check.eq(LightCache.bakes("Lighting.class"), Boolean.TRUE, "the bake itself counts");
        Check.eq(LightCache.bakes("Lighting$Job.class"), Boolean.TRUE, "and so do its nested classes");
        Check.eq(LightCache.bakes("Occluder$Hit.class"), Boolean.TRUE, "a nested class follows its outer one");
        Check.eq(LightCache.bakes("Renderer.class"), Boolean.FALSE, "drawing a frame cannot move a texel");
        Check.eq(LightCache.bakes("Renderer$Camera.class"), Boolean.FALSE, "nor can its nested classes");
        Check.eq(LightCache.bakes("engine.properties"), Boolean.FALSE, "a file that is not a class is not a class");
        Check.eq(LightCache.bakes("Imaginary.class"), null,
                "a class nobody has classified is reported, not assumed harmless");

        // The key follows the map's own bytes: edit the map, and the old bake is a different file.
        Path a = Files.createTempFile("columnray-key", ".json");
        Path b = Files.createTempFile("columnray-key", ".json");
        try {
            Files.writeString(a, MAP);
            Files.writeString(b, MAP.replace("\"ceil\": 3", "\"ceil\": 4"));
            World wa = World.load(a), wb = World.load(b);
            byte[] ka = LightCache.key(wa), kb = LightCache.key(wb), ka2 = LightCache.key(wa);
            Check.that(ka != null, "a key can be worked out when the engine runs from class files");
            if (ka != null && kb != null) {
                Check.that(!java.security.MessageDigest.isEqual(ka, kb), "a changed map is a changed key");
                Check.that(java.security.MessageDigest.isEqual(ka, ka2), "the same map is the same key");
                Check.that(!LightCache.file(wa, ka).equals(LightCache.file(wb, kb)),
                        "and so a different file, rather than one being patched over the other");
                Check.that(LightCache.file(wa, ka).getFileName().toString().endsWith(".bin"),
                        "the cache file is named after the map");
            }
        } finally {
            Files.deleteIfExists(a);
            Files.deleteIfExists(b);
        }
    }

    private static final String MAP = """
            { "name": "key", "cell": 2.0, "sun": [1, 0],
              "spawn": { "pos": [1, 1], "angle": 0 },
              "regions": [ { "name": "room", "poly": [[0,0],[4,0],[4,4],[0,4]], "floor": 0, "ceil": 3 } ] }
            """;
}

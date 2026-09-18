package engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The bake's settings come out of the map file, and every one of them sizes an array or a loop:
 * the texel size divides every surface in the world, and the sample counts are the inner loops of
 * a bake that already takes a quarter of an hour on Haven. A map asking for a texel of zero is not
 * asking for a bake - the bake it starts does not finish - so it is refused at the top of the
 * constructor, before any surface is laid out.
 */
final class LightingTest {
    private LightingTest() {}

    private static final String MAP = """
            { "name": "lit", "cell": 2.0, "sun": [1, 0],
              "spawn": { "pos": [1, 1], "angle": 0 },
              "lighting": { "texel": 0.2 },
              "regions": [
                { "name": "room", "poly": [[0,0],[8,0],[8,4],[0,4]], "floor": 0, "ceil": 3 }
              ] }
            """;

    static void run() {
        Check.group("Lighting settings");

        Check.rejects(() -> bake(MAP.replace("\"texel\": 0.2", "\"texel\": 0")),
                "texel must be a positive size", "a texel size of zero metres");
        Check.rejects(() -> bake(MAP.replace("\"texel\": 0.2", "\"texel\": 1e-9")),
                "more than", "a texel so small the lightmap cannot be allocated");
        Check.rejects(() -> bake(MAP.replace("\"texel\": 0.2",
                        "\"texel\": 0.2, \"indirect\": { \"samples\": 0 }")),
                "samples must be a whole number", "a bounce pass with no samples in it");
        Check.rejects(() -> bake(MAP.replace("\"texel\": 0.2",
                        "\"texel\": 0.2, \"shadowSamples\": -1")),
                "shadowSamples must be a whole number", "a negative number of shadow samples");
    }

    private static Lighting bake(String json) {
        Path file = null;
        try {
            file = Files.createTempFile("columnray-lit", ".json");
            Files.writeString(file, json);
            return Lighting.bake(World.load(file));
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        }
    }
}

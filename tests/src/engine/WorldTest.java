package engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loading a map, and the one thing about the acceleration grid that output depends on.
 *
 * The grid is an optimisation, and an optimisation in this engine is supposed to be invisible; the
 * cell padding is the exception that proves it, because without it the picture really does change.
 */
final class WorldTest {
    private WorldTest() {}

    /** Two rooms either side of y = 4, and a wall lying exactly on the cell line at x = 4. */
    private static final String MAP = """
            // a map small enough to reason about by hand
            { "name": "grid", "cell": 2.0, "sun": [1, 0],
              "spawn": { "pos": [1, 1], "angle": 0 },
              "regions": [
                { "name": "north", "poly": [[0,0],[8,0],[8,4],[0,4]], "floor": 0, "ceil": 3 },
                { "name": "south", "poly": [[0,4],[8,4],[8,8],[0,8]], "floor": 0, "ceil": 3 },
                { "name": "upstairs", "poly": [[0,0],[8,0],[8,4],[0,4]], "floor": 3, "ceil": 6 }
              ],
              "shapes": [
                { "type": "wall", "a": [4,0], "b": [4,4], "z0": 0, "h": 3 },
                { "type": "circle", "c": [1,6], "r": 0.5, "z0": 0, "h": 1 }
              ] }
            """;

    static void run() throws IOException {
        Check.group("World");

        Path file = Files.createTempFile("columnray-grid", ".json");
        World w;
        try {
            Files.writeString(file, MAP);
            w = World.load(file);
        } finally {
            Files.deleteIfExists(file);
        }

        Check.eq(w.name, "grid", "the map's name");
        Check.eq(w.regions.length, 3, "every region is loaded");
        Check.eq(w.shapes.length, 2, "every shape is loaded");
        Check.eq(w.sources.size(), 1, "the file the map was read from is remembered, for the light cache key");
        for (int i = 0; i < w.shapes.length; i++) Check.eq(w.shapes[i].id, i, "shape " + i + " knows its id");

        Check.eq(w.regionAt(2, 2).name, "north", "a point in the north room");
        Check.eq(w.regionAt(2, 6).name, "south", "a point in the south room");
        Check.that(w.regionAt(-5, -5) == null, "a point off the map is in no region");

        World.Region[] stack = new World.Region[8];
        Check.eq(w.regionsAt(2, 2, stack), 2, "two storeys stand at this spot");
        Check.that(stack[0].floor < stack[1].floor, "the stack is sorted from the ground up");
        Check.eq(w.regionsAt(2, 6, stack), 1, "one storey over the south room");
        Check.eq(w.regionsAt(-5, -5, stack), 0, "none off the map");

        // The invariant: a wall flush against a cell line belongs to both cells. Without the pad,
        // the ray meets a region boundary at exactly the wall's distance with the wall not yet in
        // its pending list, and lights and clips it with the room on the far side.
        //
        // The wall runs from (4, 0) to (4, 4) on a 2 m grid, so it spans four cells vertically and
        // lies exactly on a cell line horizontally. Padded, that is eight cells; unpadded it would
        // be three, all on the same side of the line.
        Check.eq(cellsHolding(w, 0), 8,
                "a wall lying exactly on the cell line at x = 4 is registered in the cells either side of it");
        Check.that(holds(w.grid.shapes[cell(w, 3.5, 1)], 0) && holds(w.grid.shapes[cell(w, 4.5, 1)], 0),
                "and specifically in both of the two that meet at it");
        Check.eq(cellsHolding(w, 1), 2, "a shape that straddles one cell line is in the two cells it touches");

        // Regions are padded the same way, for the same reason.
        int east = cell(w, 4.5, 2), west = cell(w, 3.5, 2);
        Check.that(holds(w.grid.regions[east], 0) && holds(w.grid.regions[west], 0),
                "a region is in every cell its bounds touch");

        // Groups only bundle what the cell already holds; they must lose nothing.
        for (int c = 0; c < w.grid.shapes.length; c++) {
            int inGroups = 0;
            for (World.Group g : w.grid.groups[c]) inGroups += g.members.length;
            Check.that(inGroups == w.grid.shapes[c].length,
                    "cell " + c + " groups all of its shapes and no others");
        }
    }

    private static int cell(World w, double x, double y) {
        int cx = (int) Math.floor((x - w.grid.x0) / w.grid.cell);
        int cy = (int) Math.floor((y - w.grid.y0) / w.grid.cell);
        return cy * w.grid.nx + cx;
    }

    private static int cellsHolding(World w, int shape) {
        int n = 0;
        for (int[] ids : w.grid.shapes) if (holds(ids, shape)) n++;
        return n;
    }

    private static boolean holds(int[] ids, int id) {
        for (int i : ids) if (i == id) return true;
        return false;
    }
}

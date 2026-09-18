package engine;

import engine.World.Region;
import engine.World.Shape;
import java.awt.image.BufferedImage;
import java.util.function.IntConsumer;

/**
 * The minimap the way Valorant draws one: not the shapes, but where you can go. Grey is floor you
 * can walk to, green is a bomb site, a light box with a white edge is a crate or a pillar standing
 * in the way, and everything else - walls, buildings you cannot enter, the world outside - is left
 * empty.
 *
 * Worked out once, as an image, from the same shapes the player collides with:
 *
 *   1. every CELL x CELL square gets the surfaces a player could crouch on: a region's floor or a
 *      shape's top, with nothing solid in the 1.15 m above it;
 *   2. a flood from where the player stands walks to the neighbouring square's highest surface
 *      that is at most a step up, or any distance down - no jumping, so a crate stays a crate;
 *   3. an island of unreachable squares that is small and surrounded by floor is an obstacle.
 *
 * Drawing it is then one image per frame. The old minimap filled every shape every frame, which
 * for Haven's 38,000 shapes was most of a second on screen.
 */
final class Minimap {
    static final double CELL = 0.25;                     // metres per pixel of the image
    /** A wall claims every square whose centre is this close to it: more than half a square's
     *  diagonal, so a slanted wall leaves no corner-to-corner gap for the flood to leak through. */
    private static final double WALL = CELL * 0.75;
    private static final double CLIMB = 0.45;            // a step and a bit, not a jump
    private static final double CLEAR = Player.EYE_CROUCH + Player.HEAD_ABOVE_EYE;
    private static final double OBSTACLE = 25;           // m2: smaller than this is a crate, bigger a building

    private static final int FLOOR = 0xff777777, SITE = 0xff969a73, OBST = 0xff8b8b8b, EDGE = 0xffdadada;
    /** -Dminimap.debug=true: also paint, in blue, standable ground the flood never reached. */
    private static final boolean DEBUG = Boolean.getBoolean("minimap.debug");

    final BufferedImage image;                           // cropped to what is drawn, plus a margin
    final double ix0, iy0;                               // the world corner of the image's pixel (0, 0)
    final double x0, y0;                                 // the world corner of the whole grid
    final int nx, ny;                                    // the whole grid, in squares
    /** With -Dminimap.debug=true only: every square's surfaces (debugFirst[c] .. debugFirst[c + 1]
     *  index debugZ) and which of them the flood reached. Null otherwise - nobody pays for them. */
    int[] debugFirst;
    float[] debugZ;
    boolean[] debugGot;

    Minimap(World world, double startX, double startY, double startFeet) {
        x0 = world.minX;
        y0 = world.minY;
        nx = Math.max(1, (int) Math.ceil((world.maxX - x0) / CELL));
        ny = Math.max(1, (int) Math.ceil((world.maxY - y0) / CELL));
        int n = nx * ny;
        Shape[] shapes = world.shapes;

        // 1. The shapes over each square, as one flat array (two passes: count, then fill).
        int[] start = new int[n + 1];
        for (Shape s : shapes) covered(s, c -> start[c + 1]++);
        for (int c = 0; c < n; c++) start[c + 1] += start[c];
        int[] ids = new int[start[n]], fill = start.clone();
        for (Shape s : shapes) covered(s, c -> ids[fill[c]++] = s.id);

        // 2. Standable surfaces per square, lowest first, and what each one is the top of.
        int[] first = new int[n + 1];
        float[] sz = new float[n * 2];
        int[] src = new int[n * 2], cellOf = new int[n * 2];
        int m = 0;
        Region[] stack = new Region[16];
        for (int j = 0; j < ny; j++) {
            double cy = y0 + (j + 0.5) * CELL;
            for (int i = 0; i < nx; i++) {
                int c = j * nx + i;
                first[c] = m;
                int k = world.regionsAt(x0 + (i + 0.5) * CELL, cy, stack);
                if (k == 0) continue;
                int from = start[c], to = start[c + 1];
                for (int q = -k; q < to - from; q++) {
                    double z = q < 0 ? stack[q + k].floor : shapes[ids[from + q]].h;
                    int who = q < 0 ? -1 : ids[from + q];
                    if (q < 0 && !stack[q + k].walkable) continue;       // the bottom of the world, not ground
                    if (!standable(z, stack, k, shapes, ids, from, to)) continue;
                    int dup = -1;
                    for (int v = first[c]; v < m; v++) if (Math.abs(sz[v] - z) < 0.05) dup = v;
                    if (dup >= 0) {
                        if (who >= 0 && shapes[who].site != null) src[dup] = who;
                        continue;
                    }
                    if (m == sz.length) {
                        sz = java.util.Arrays.copyOf(sz, m * 2);
                        src = java.util.Arrays.copyOf(src, m * 2);
                        cellOf = java.util.Arrays.copyOf(cellOf, m * 2);
                    }
                    int v = m++;
                    while (v > first[c] && sz[v - 1] > z) { sz[v] = sz[v - 1]; src[v] = src[v - 1]; v--; }
                    sz[v] = (float) z;
                    src[v] = who;
                }
                for (int v = first[c]; v < m; v++) cellOf[v] = c;
            }
        }
        first[n] = m;

        // 3. Flood from the player.
        boolean[] got = new boolean[m];
        int[] queue = new int[Math.max(1, m)];
        int head = 0, tail = 0;
        int seed = seed(startX, startY, startFeet, first, sz);
        if (seed >= 0) { got[seed] = true; queue[tail++] = seed; }
        int[] di = {1, -1, 0, 0}, dj = {0, 0, 1, -1};
        while (head < tail) {
            int u = queue[head++], c = cellOf[u], i = c % nx, j = c / nx;
            double top = sz[u] + CLIMB;
            for (int d = 0; d < 4; d++) {
                int i2 = i + di[d], j2 = j + dj[d];
                if (i2 < 0 || j2 < 0 || i2 >= nx || j2 >= ny) continue;
                int c2 = j2 * nx + i2, best = -1;
                for (int v = first[c2]; v < first[c2 + 1] && sz[v] <= top; v++) best = v;   // where you land
                if (best >= 0 && !got[best]) { got[best] = true; queue[tail++] = best; }
            }
        }

        // What each square shows: its lowest reachable surface.
        int[] show = new int[n];
        for (int c = 0; c < n; c++) {
            show[c] = -1;
            for (int v = first[c]; v < first[c + 1]; v++) if (got[v]) { show[c] = v; break; }
        }

        // 4. Islands of no-go: small, and not part of the mass that runs off the edge of the map.
        boolean[] obstacle = new boolean[n];
        int[] comp = new int[n], stackC = new int[n];
        int label = 0;
        for (int c0 = 0; c0 < n; c0++) {
            if (show[c0] >= 0 || comp[c0] != 0) continue;
            label++;
            int sp = 0, count = 0, lo = 0;
            boolean edge = false;
            stackC[sp++] = c0;
            comp[c0] = label;
            int[] members = new int[64];
            while (sp > 0) {
                int c = stackC[--sp], i = c % nx, j = c / nx;
                if (count == members.length) members = java.util.Arrays.copyOf(members, count * 2);
                members[count++] = c;
                if (i == 0 || j == 0 || i == nx - 1 || j == ny - 1) edge = true;
                for (int d = 0; d < 4; d++) {
                    int i2 = i + di[d], j2 = j + dj[d];
                    if (i2 < 0 || j2 < 0 || i2 >= nx || j2 >= ny) continue;
                    int c2 = j2 * nx + i2;
                    if (show[c2] < 0 && comp[c2] == 0) { comp[c2] = label; stackC[sp++] = c2; }
                }
            }
            if (!edge && count * CELL * CELL <= OBSTACLE)
                for (int q = lo; q < count; q++) obstacle[members[q]] = true;
        }

        // 5. Paint.
        int[] px = new int[n];
        for (int c = 0; c < n; c++) {
            if (show[c] >= 0) {
                int who = src[show[c]];
                px[c] = who >= 0 && shapes[who].site != null ? SITE : FLOOR;
            } else if (obstacle[c]) {
                int i = c % nx, j = c / nx;
                boolean rim = false;
                for (int d = 0; d < 4; d++) {
                    int i2 = i + di[d], j2 = j + dj[d];
                    if (i2 >= 0 && j2 >= 0 && i2 < nx && j2 < ny && show[j2 * nx + i2] >= 0) rim = true;
                }
                px[c] = rim ? EDGE : OBST;
            } else if (DEBUG && first[c] < first[c + 1]) {
                // Somewhere to stand that the flood never reached: blue, brighter the higher it is.
                // Right beside reached floor, say why it stopped: red is a jump up (0.45-1.1 m),
                // yellow a ledge (1.1-2.5 m).
                int zc = Math.max(0, Math.min(255, (int) (sz[first[c]] * 18)));
                px[c] = 0xff000000 | (zc << 8) | 0xd0;
                int i = c % nx, j = c / nx;
                double rise = Double.POSITIVE_INFINITY;
                for (int d = 0; d < 4; d++) {
                    int i2 = i + di[d], j2 = j + dj[d];
                    if (i2 < 0 || j2 < 0 || i2 >= nx || j2 >= ny || show[j2 * nx + i2] < 0) continue;
                    double from = sz[show[j2 * nx + i2]];
                    for (int v = first[c]; v < first[c + 1]; v++)
                        if (sz[v] > from) rise = Math.min(rise, sz[v] - from);
                }
                if (rise <= 1.1) px[c] = 0xffff2a2a;
                else if (rise <= 2.5) px[c] = 0xffffd21f;
            }
        }
        if (DEBUG && seed >= 0) {
            int c = cellOf[seed], i = c % nx, j = c / nx;
            for (int b = -3; b <= 3; b++)
                for (int a2 = -3; a2 <= 3; a2++)
                    if (i + a2 >= 0 && j + b >= 0 && i + a2 < nx && j + b < ny) px[(j + b) * nx + i + a2] = 0xffff2020;
        }
        // Crop to what is drawn: the map's bounds include the scenery round it, and a panel that is a
        // third empty margin shows the part that matters at two thirds of the size.
        int i0 = nx, i1 = -1, j0 = ny, j1 = -1;
        for (int c = 0; c < n; c++) {
            if (px[c] == 0) continue;
            int i = c % nx, j = c / nx;
            i0 = Math.min(i0, i); i1 = Math.max(i1, i);
            j0 = Math.min(j0, j); j1 = Math.max(j1, j);
        }
        BufferedImage full = new BufferedImage(nx, ny, BufferedImage.TYPE_INT_ARGB);
        full.setRGB(0, 0, nx, ny, px, 0, nx);
        if (i1 < 0) {
            image = full;
            ix0 = x0;
            iy0 = y0;
        } else {
            int pad = (int) Math.round(2 / CELL);
            i0 = Math.max(0, i0 - pad); j0 = Math.max(0, j0 - pad);
            i1 = Math.min(nx - 1, i1 + pad); j1 = Math.min(ny - 1, j1 + pad);
            image = full.getSubimage(i0, j0, i1 - i0 + 1, j1 - j0 + 1);
            ix0 = x0 + i0 * CELL;
            iy0 = y0 + j0 * CELL;
        }
        if (DEBUG) {
            debugFirst = first;
            debugZ = sz;
            debugGot = got;
        }
    }

    /** Room to crouch on z: inside a storey's air, and no shape's body in the way. */
    private static boolean standable(double z, Region[] stack, int k, Shape[] shapes, int[] ids, int from, int to) {
        boolean air = false;
        for (int q = 0; q < k; q++) if (stack[q].floor <= z + 1e-6 && z + CLEAR <= stack[q].ceil) air = true;
        if (!air) return false;
        for (int q = from; q < to; q++) {
            Shape s = shapes[ids[q]];
            if (s.zLow < z + CLEAR && s.h > z + 0.05) return false;
        }
        return true;
    }

    /** The surface the player stands on, or the nearest one to it if the spot itself has none. */
    private int seed(double x, double y, double feet, int[] first, float[] sz) {
        int ci = (int) Math.floor((x - x0) / CELL), cj = (int) Math.floor((y - y0) / CELL);
        for (int r = 0; r <= 8; r++)
            for (int j = cj - r; j <= cj + r; j++)
                for (int i = ci - r; i <= ci + r; i++) {
                    if (i < 0 || j < 0 || i >= nx || j >= ny) continue;
                    int c = j * nx + i, best = -1;
                    for (int v = first[c]; v < first[c + 1]; v++) if (best < 0 || sz[v] <= feet + CLIMB) best = v;
                    if (best >= 0) return best;
                }
        return -1;
    }

    /** Every square whose centre the shape covers - walls and slivers widened by WALL, so a
     *  20 cm partition still stops the flood. */
    private void covered(Shape s, IntConsumer fn) {
        int i0 = Math.max(0, (int) Math.floor((s.minX - WALL - x0) / CELL - 0.5));
        int i1 = Math.min(nx - 1, (int) Math.ceil((s.maxX + WALL - x0) / CELL - 0.5));
        int j0 = Math.max(0, (int) Math.floor((s.minY - WALL - y0) / CELL - 0.5));
        int j1 = Math.min(ny - 1, (int) Math.ceil((s.maxY + WALL - y0) / CELL - 0.5));
        for (int j = j0; j <= j1; j++) {
            double cy = y0 + (j + 0.5) * CELL;
            for (int i = i0; i <= i1; i++) {
                double cx = x0 + (i + 0.5) * CELL;
                boolean in = switch (s.kind) {
                    case SEG -> Geometry.distPointSeg(cx, cy, s.ax, s.ay, s.bx, s.by) <= WALL;
                    case CIRCLE -> Math.hypot(cx - s.cx, cy - s.cy) <= s.r + WALL;
                    case POLY -> Geometry.discTouchesPoly(cx, cy, WALL, s.xs, s.ys);
                };
                if (in) fn.accept(j * nx + i);
            }
        }
    }
}

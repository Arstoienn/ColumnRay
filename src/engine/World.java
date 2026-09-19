package engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/**
 * Map data: a list of regions (floor / ceiling) plus a list of shapes.
 * The grid is a pure acceleration structure - each cell stores the shapes and regions whose
 * bounding boxes touch it, and it is rebuilt automatically on load.
 */
final class World {
    /**
     * What a map file is allowed to ask for.
     *
     * A map is data, and the engine loads whichever one it is pointed at - a map someone sent you,
     * a converter's output, a file that was truncated halfway. Every number below is far above any
     * real map (Haven: 3.8 million surfaces, a 448 x 448 grid, 408 images) and far below the point
     * where the request stops being a map and becomes a way to exhaust the machine: "count":
     * [1e9, 1e9] on an array of one box asks for 10^18 shapes, and without a ceiling the loader
     * spends the rest of the afternoon finding that out. Refusing says which key was at fault.
     */
    private static final long MAX_JSON_BYTES = 512L << 20;
    private static final int MAX_SHAPES = 20_000_000;
    private static final int MAX_FILES = 65_536;          // chunks, images: one map's worth
    private static final int MAX_POLY_POINTS = 100_000;
    private static final long MAX_GRID_CELLS = 64_000_000L;
    private static final int MAX_REPEAT = 1_000_000;      // an array's count, a canopy's cards

    enum Kind { SEG, CIRCLE, POLY }

    /** A convex polygon region. ceil = +infinity means open to the sky. */
    static final class Region {
        String name;
        double[] xs, ys;
        double floor, ceil;
        double top;          // top of the wall above the opening, seen from an open-air region
        boolean sky;
        /** false: the floor is only the bottom of the world, and the ground is made of shapes (an
         *  imported map). The minimap does not count it as somewhere to stand. */
        boolean walkable = true;
        int floorMat, ceilMat, wallMat;
        int floorColor, ceilColor, wallColor;
        double light;        // ambient brightness (indoor regions are dimmer)
        int id;              // index into World.regions
        double minX, minY, maxX, maxY;
    }

    static final class Shape {
        /** Where GpuMaterials put this face's texture record, or -1 when the card cannot draw it. */
        int gpuSide = -1, gpuTop = -1, gpuBottom = -1, gpuAlpha = -1;
        Kind kind;
        double ax, ay, bx, by, len;   // segment wall
        double cx, cy, r;             // cylinder
        double[] xs, ys;              // convex polygon
        double z0, h;                 // bottom and top height
        // An optional tilted top: top(x, y) = h + hx * (x - midX) + hy * (y - midY), so h stays the
        // height at the middle and a shape with no tilt is exactly what it always was. Roofs, eaves
        // and rocky ground are not flat, and read as flat they come out as staircases of steps.
        double hx, hy;
        double hTop;                  // the highest corner: what the grid and the culling must use
        // The bottom can tilt the same way: bottom(x, y) = z0 + zx * (x - midX) + zy * (y - midY).
        // With both planes free a shape is a slab of any slope - a diagonal brace, the underside of
        // an arch, one triangle of a mesh - and it is still a column of solid between two heights
        // wherever a ray meets it, which is all the renderer ever asks of anything.
        double zx, zy;
        double zLow;                  // the lowest corner of the bottom
        int mat, topMat, color;
        Materials.Texture tex, topTex; // shared atlas tiles; null keeps the procedural material
        // Or the mesh's own texture: a whole image, placed by uv = [a, b, c, d, e, f] with
        // u = a p + b q + c, v = d p + e q + f. (p, q) is the world (x, y) for a shape with a plan,
        // and for a wall the distance along it from a and the height. Null when not mapped.
        Materials.Texture img;
        double[] uv;
        // A blend material's second layer and the height that sharpens the edge between the two,
        // mixed by the mesh's vertex alpha: va = [g, h, i], alpha = g p + h q + i. See Materials.blend.
        Materials.Texture imgB, hmap;
        double[] va;
        // The mesh's vertex colour for a material that multiplies by it, linear, as three planes:
        // channel c = vc[3c] p + vc[3c + 1] q + vc[3c + 2]. Null when the material ignores it.
        double[] vc;
        // A cut-out's alpha (leaves, netting, rocks painted on the ground), placed by the same uv:
        // the shape is drawn through it rather than as a solid. Null for a solid shape.
        Materials.Texture amap;
        double vb;
        boolean inv;
        double ts = 1, topTs = 1;      // metres per tile; the bottom uses tex/ts, like the sides
        int albedoColor;              // the map colour, before a canopy varies its cards
        int mask = -1;                // a masked surface: leaves, ferns, grass. -1 = solid
        double maxDist;               // not drawn beyond this distance
        String label;                 // shown in the ray view, e.g. "box/wood"
        String site;                  // a bomb site's floor ("A", "B", ...): green on the minimap
        int id;                       // index into World.shapes
        double minX, minY, maxX, maxY;

        /** The top at a point. Flat shapes - everything but a roof or a slope - return h. */
        double topAt(double x, double y) {
            return hx == 0 && hy == 0 ? h
                    : h + hx * (x - (minX + maxX) / 2) + hy * (y - (minY + maxY) / 2);
        }

        /** How fast the top climbs along a ray going (rx, ry): top(t) = topAt(px, py) + slope * t. */
        double topSlope(double rx, double ry) { return hx * rx + hy * ry; }

        /** The bottom at a point, and how fast it climbs along a ray: the top's two, for z0. */
        double bottomAt(double x, double y) {
            return zx == 0 && zy == 0 ? z0
                    : z0 + zx * (x - (minX + maxX) / 2) + zy * (y - (minY + maxY) / 2);
        }

        double bottomSlope(double rx, double ry) { return zx * rx + zy * ry; }
    }

    static final class Grid {
        double x0, y0, cell;
        int nx, ny;
        int[][] shapes, regions;
        Group[][] groups;             // the same shapes as `shapes`, bundled per storey; see buildGroups
    }

    /**
     * The shapes of one cell that stand in the same region - in practice, the furniture of one room
     * on one storey - with the union of their bounding boxes and height ranges. The renderer tests
     * the union first: when every row it could reach is already painted (you are upstairs and your
     * own floor is in the way), none of the members can show, and the whole storey's worth of
     * furniture in that cell is rejected with one test instead of one per shape.
     */
    static final class Group {
        final int[] members;                  // in tree order: every node's members are one run of it
        final double minX, minY, maxX, maxY, z0, h, maxDist;

        /**
         * A bounding volume hierarchy over the members.
         *
         * One union for the whole group rejects a storey's furniture at once, but it cannot reject
         * part of it: in a cell of an imported mesh there are hundreds of triangles, a ray through
         * the cell passes a few of them, and every one of the rest still got its own test - two
         * fifths of Haven's frame time, measured, once one square of it was its triangles. So the
         * members are split in half along the widest spread of their middles, and again, down to
         * LEAF; a test on a node rejects everything under it. Node k holds members[start[k],
         * end[k]); a leaf has right[k] = -1, otherwise its children are k + 1 and right[k]. Its
         * bounds are nb[7k..7k+6]: minX, minY, maxX, maxY, lowest bottom, highest top, maxDist.
         * A node's bounds contain every member's, so "the node cannot show" still implies "no
         * member can", and what is drawn does not change.
         */
        static final int LEAF = 4;
        final int[] start, end, right;
        final double[] nb;

        Group(int[] members, Shape[] shapes) {
            int n = members.length;
            this.members = members.clone();
            int cap = Math.max(1, 2 * n - 1);           // halves are never empty: under 2n nodes
            int[] st = new int[cap], en = new int[cap], rt = new int[cap];
            double[] b = new double[7 * cap];
            int used = build(this.members, 0, n, shapes, st, en, rt, b, 0);
            start = Arrays.copyOf(st, used);
            end = Arrays.copyOf(en, used);
            right = Arrays.copyOf(rt, used);
            nb = Arrays.copyOf(b, 7 * used);
            minX = nb[0]; minY = nb[1]; maxX = nb[2]; maxY = nb[3]; z0 = nb[4]; h = nb[5]; maxDist = nb[6];
        }

        /** Node k over a[lo, hi); returns the next free node index. */
        private static int build(int[] a, int lo, int hi, Shape[] shapes, int[] st, int[] en, int[] rt,
                                 double[] b, int k) {
            double x0 = Double.POSITIVE_INFINITY, y0 = x0, zLo = x0;
            double x1 = Double.NEGATIVE_INFINITY, y1 = x1, zHi = x1, far = x1;
            double[] cLo = {x0, x0, x0}, cHi = {x1, x1, x1};
            for (int m = lo; m < hi; m++) {
                Shape s = shapes[a[m]];
                x0 = Math.min(x0, s.minX); y0 = Math.min(y0, s.minY);
                x1 = Math.max(x1, s.maxX); y1 = Math.max(y1, s.maxY);
                zLo = Math.min(zLo, s.zLow); zHi = Math.max(zHi, s.hTop);
                far = Math.max(far, s.maxDist);
                for (int axis = 0; axis < 3; axis++) {
                    double c = middle(s, axis);
                    cLo[axis] = Math.min(cLo[axis], c);
                    cHi[axis] = Math.max(cHi[axis], c);
                }
            }
            int o = 7 * k;
            b[o] = x0; b[o + 1] = y0; b[o + 2] = x1; b[o + 3] = y1; b[o + 4] = zLo; b[o + 5] = zHi; b[o + 6] = far;
            st[k] = lo;
            en[k] = hi;
            if (hi - lo <= LEAF) {
                rt[k] = -1;
                return k + 1;
            }
            int axis = 0;
            for (int i = 1; i < 3; i++) if (cHi[i] - cLo[i] > cHi[axis] - cLo[axis]) axis = i;
            int mid = (lo + hi) >>> 1;
            select(a, lo, hi, mid, shapes, axis);
            int next = build(a, lo, mid, shapes, st, en, rt, b, k + 1);
            rt[k] = next;
            return build(a, mid, hi, shapes, st, en, rt, b, next);
        }

        /** Twice the middle of a shape's bounds along x, y or z. */
        private static double middle(Shape s, int axis) {
            return axis == 0 ? s.minX + s.maxX : axis == 1 ? s.minY + s.maxY : s.zLow + s.hTop;
        }

        /** Quickselect: afterwards a[nth] is where a sort by middle would put it, with nothing larger
         *  before it and nothing smaller after it. */
        private static void select(int[] a, int lo, int hi, int nth, Shape[] shapes, int axis) {
            while (hi - lo > 1) {
                double pivot = middle(shapes[a[(lo + hi) >>> 1]], axis);
                int i = lo, j = hi - 1;
                while (i <= j) {
                    while (middle(shapes[a[i]], axis) < pivot) i++;
                    while (middle(shapes[a[j]], axis) > pivot) j--;
                    if (i <= j) {
                        int t = a[i]; a[i] = a[j]; a[j] = t;
                        i++;
                        j--;
                    }
                }
                if (nth <= j) hi = j + 1;
                else if (nth >= i) lo = i;
                else return;
            }
        }
    }

    final String name;
    final Region[] regions;
    final Shape[] shapes;
    final Grid grid;
    final double sunX, sunY;
    final double spawnX, spawnY, spawnAngle;
    final double minX, minY, maxX, maxY;
    final Map<String, Object> lighting;   // the map's "lighting" block, or null; read by Lighting
    final List<Path> sources = new ArrayList<>();   // every file the map was read from, for LightCache's key
    double minimapRotate;                 // "minimap": {"rotate": degrees clockwise, in quarter turns}

    private World(String name, Region[] regions, Shape[] shapes, double cell,
                  double sunX, double sunY, double spawnX, double spawnY, double spawnAngle,
                  Map<String, Object> lighting) {
        this.name = name;
        this.regions = regions;
        this.shapes = shapes;
        this.lighting = lighting;
        if (regions.length == 0) throw new IllegalArgumentException("a map needs at least one region");
        if (!Double.isFinite(cell) || cell <= 0)
            throw new IllegalArgumentException("cell must be a finite positive size in metres");
        for (int i = 0; i < regions.length; i++) regions[i].id = i;
        for (int i = 0; i < shapes.length; i++) shapes[i].id = i;
        double len = Math.hypot(sunX, sunY);
        // Every shading term divides by this. A sun of [0, 0] would make the whole map NaN, which
        // draws as a black frame with nothing to say why.
        if (!Double.isFinite(len) || len == 0)
            throw new IllegalArgumentException("sun must be a finite direction that is not [0, 0]");
        this.sunX = sunX / len;
        this.sunY = sunY / len;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnAngle = spawnAngle;

        double x0 = Double.POSITIVE_INFINITY, y0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1;
        for (Region r : regions) {
            x0 = Math.min(x0, r.minX); y0 = Math.min(y0, r.minY);
            x1 = Math.max(x1, r.maxX); y1 = Math.max(y1, r.maxY);
        }
        for (Shape s : shapes) {
            x0 = Math.min(x0, s.minX); y0 = Math.min(y0, s.minY);
            x1 = Math.max(x1, s.maxX); y1 = Math.max(y1, s.maxY);
        }
        minX = x0; minY = y0; maxX = x1; maxY = y1;
        grid = buildGrid(cell);
        buildGroups(grid);
    }

    // ---- Queries ----

    /** The lowest region containing the point, or null if the point is off the map.
     *  Storeys stack, so use {@link #regionsAt} whenever more than the ground one matters. */
    Region regionAt(double x, double y) {
        int cx = (int) Math.floor((x - grid.x0) / grid.cell);
        int cy = (int) Math.floor((y - grid.y0) / grid.cell);
        if (cx < 0 || cy < 0 || cx >= grid.nx || cy >= grid.ny) return null;
        Region best = null;
        for (int i : grid.regions[cy * grid.nx + cx]) {
            Region r = regions[i];
            if (Geometry.pointInPoly(x, y, r.xs, r.ys) && (best == null || r.floor < best.floor)) best = r;
        }
        return best;
    }

    /**
     * Every region containing the point, written into {@code out} sorted by floor height, lowest
     * first; returns how many. This is the vertical stack of storeys at that spot: one entry over
     * open ground, two where a floor slab has another storey on top of it, and none off the map.
     * Anything not inside one of the returned [floor, ceil) ranges is solid - that is what makes a
     * slab a slab, and what makes a missing entry (a stairwell, the courtyard) a hole to fall or
     * see through.
     */
    int regionsAt(double x, double y, Region[] out) {
        int cx = (int) Math.floor((x - grid.x0) / grid.cell);
        int cy = (int) Math.floor((y - grid.y0) / grid.cell);
        if (cx < 0 || cy < 0 || cx >= grid.nx || cy >= grid.ny) return 0;
        int n = 0;
        for (int i : grid.regions[cy * grid.nx + cx]) {
            Region r = regions[i];
            if (!Geometry.pointInPoly(x, y, r.xs, r.ys)) continue;
            if (n == out.length) continue;                       // more storeys than we have room for
            int k = n++;
            while (k > 0 && out[k - 1].floor > r.floor) { out[k] = out[k - 1]; k--; }
            out[k] = r;
        }
        return n;
    }

    List<Region> regionsNear(double x, double y, double rad) {
        Set<Integer> ids = new LinkedHashSet<>();
        forCells(x - rad, y - rad, x + rad, y + rad, c -> { for (int i : grid.regions[c]) ids.add(i); });
        List<Region> out = new ArrayList<>(ids.size());
        for (int i : ids) out.add(regions[i]);
        return out;
    }

    List<Shape> shapesNear(double x, double y, double rad) {
        Set<Integer> ids = new LinkedHashSet<>();
        forCells(x - rad, y - rad, x + rad, y + rad, c -> { for (int i : grid.shapes[c]) ids.add(i); });
        List<Shape> out = new ArrayList<>(ids.size());
        for (int i : ids) out.add(shapes[i]);
        return out;
    }

    private void forCells(double x0, double y0, double x1, double y1, IntConsumer fn) {
        forCells(grid, x0, y0, x1, y1, fn);
    }

    private static void forCells(Grid g, double x0, double y0, double x1, double y1, IntConsumer fn) {
        int ix0 = Math.max(0, (int) Math.floor((x0 - g.x0) / g.cell));
        int iy0 = Math.max(0, (int) Math.floor((y0 - g.y0) / g.cell));
        int ix1 = Math.min(g.nx - 1, (int) Math.floor((x1 - g.x0) / g.cell));
        int iy1 = Math.min(g.ny - 1, (int) Math.floor((y1 - g.y0) / g.cell));
        for (int cy = iy0; cy <= iy1; cy++)
            for (int cx = ix0; cx <= ix1; cx++) fn.accept(cy * g.nx + cx);
    }

    /** Bounds are grown by this before being registered into cells, so that anything lying exactly
     *  on a cell line lands in both neighbouring cells. Without it a wall flush against a cell
     *  boundary is not yet in the ray's pending list when the DDA flushes the cell in front of it,
     *  and a region-boundary event at the same distance can be processed first - which would light
     *  and clip the wall using the region on the far side. */
    private static final double CELL_PAD = 1e-9;

    private Grid buildGrid(double cell) {
        Grid g = new Grid();
        g.cell = cell;
        g.x0 = minX - cell;
        g.y0 = minY - cell;
        // In double, and checked: a map a kilometre across with a 1 mm cell is 10^12 cells, and
        // nx * ny in int wraps to something small or negative long before the allocation fails.
        double wide = Math.ceil((maxX - g.x0) / cell) + 1, tall = Math.ceil((maxY - g.y0) / cell) + 1;
        if (!(wide >= 1) || !(tall >= 1) || wide * tall > MAX_GRID_CELLS)
            throw new IllegalArgumentException("the map needs a grid of " + wide + " x " + tall
                    + " cells, which is more than " + MAX_GRID_CELLS + ": raise \"cell\"");
        g.nx = (int) wide;
        g.ny = (int) tall;
        int cells = g.nx * g.ny;
        g.shapes = new int[cells][];
        g.regions = new int[cells][];

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Integer>[] sh = new List[cells], rg = new List[cells];
        for (int c = 0; c < sh.length; c++) { sh[c] = new ArrayList<>(); rg[c] = new ArrayList<>(); }
        for (int i = 0; i < shapes.length; i++) {
            final int id = i;
            Shape s = shapes[i];
            forCells(g, s.minX - CELL_PAD, s.minY - CELL_PAD, s.maxX + CELL_PAD, s.maxY + CELL_PAD, c -> sh[c].add(id));
        }
        for (int i = 0; i < regions.length; i++) {
            final int id = i;
            Region r = regions[i];
            forCells(g, r.minX - CELL_PAD, r.minY - CELL_PAD, r.maxX + CELL_PAD, r.maxY + CELL_PAD, c -> rg[c].add(id));
        }
        for (int c = 0; c < sh.length; c++) {
            g.shapes[c] = sh[c].stream().mapToInt(Integer::intValue).toArray();
            g.regions[c] = rg[c].stream().mapToInt(Integer::intValue).toArray();
        }
        return g;
    }

    /**
     * Bundle each cell's shapes by the region they stand in: the highest region at the shape's
     * centre whose [floor, ceil) contains the shape's base. Shapes that rise above that region's
     * ceiling - outer walls running the full height of the building - belong to no single storey
     * and are left as groups of one. Grouping only affects speed: a group's bounds contain every
     * member's, so "the group cannot show" implies "no member can".
     */
    private void buildGroups(Grid g) {
        Map<Region, Integer> index = new java.util.IdentityHashMap<>();
        for (int i = 0; i < regions.length; i++) index.put(regions[i], i);
        int[] key = new int[shapes.length];
        Region[] stack = new Region[16];
        for (int i = 0; i < shapes.length; i++) {
            Shape s = shapes[i];
            int n = regionsAt((s.minX + s.maxX) / 2, (s.minY + s.maxY) / 2, stack);
            Region home = null;
            for (int k = 0; k < n; k++)
                if (stack[k].floor <= s.zLow + 1e-6 && s.zLow < stack[k].ceil && (home == null || stack[k].floor > home.floor))
                    home = stack[k];
            key[i] = home != null && s.hTop <= home.ceil + 1e-6 ? index.get(home) : -1;
        }
        g.groups = new Group[g.shapes.length][];
        for (int c = 0; c < g.shapes.length; c++) {
            Map<Integer, List<Integer>> byKey = new java.util.LinkedHashMap<>();
            List<Group> out = new ArrayList<>();
            for (int i : g.shapes[c]) {
                if (key[i] < 0) out.add(new Group(new int[] {i}, shapes));
                else byKey.computeIfAbsent(key[i], k -> new ArrayList<>()).add(i);
            }
            for (List<Integer> m : byKey.values())
                out.add(new Group(m.stream().mapToInt(Integer::intValue).toArray(), shapes));
            g.groups[c] = out.toArray(Group[]::new);
        }
    }

    // ---- JSON loading ----

    static World load(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        Map<String, Object> root = readJson(path);
        Assets textures = new Assets(parseTextures(root, path), parseImages(root, path));
        List<Region> regions = new ArrayList<>();
        for (Object o : list(root.get("regions"))) regions.add(parseRegion(obj(o)));
        List<Shape> shapes = new ArrayList<>();
        if (root.get("shapes") != null)
            for (Object o : list(root.get("shapes"))) parseShape(obj(o), 0, 0, shapes, textures);
        // A big map keeps its shapes in pieces beside it: "chunks": ["haven/0_0.json", ...], each
        // {"shapes": [...]}, paths relative to this file. One 33 MB file had to be rewritten whole
        // for any change anywhere, and a diff of it said nothing.
        List<Path> sources = new ArrayList<>(List.of(path));
        if (root.get("chunks") != null) {
            List<Object> names = list(root.get("chunks"));
            if (names.size() > MAX_FILES)
                throw new IllegalArgumentException("a map may name at most " + MAX_FILES + " chunks");
            for (Object c : names) {
                Path file = beside(path, c, "chunk");
                sources.add(file);
                Map<String, Object> chunk = readJson(file);
                for (Object o : list(chunk.get("shapes"))) parseShape(obj(o), 0, 0, shapes, textures);
            }
        }

        double[] sun = root.containsKey("sun") ? pt(root.get("sun")) : new double[] {0.5, 0.8};
        Map<String, Object> spawn = obj(root.get("spawn"));
        double[] sp = pt(spawn.get("pos"));
        World world = new World(str(root, "name", path.getFileName().toString()),
                regions.toArray(Region[]::new), shapes.toArray(Shape[]::new), num(root, "cell", 1.0),
                sun[0], sun[1], sp[0], sp[1], Math.toRadians(num(spawn, "angle", 0)),
                root.get("lighting") instanceof Map ? obj(root.get("lighting")) : null);
        if (root.get("minimap") instanceof Map) world.minimapRotate = num(obj(root.get("minimap")), "rotate", 0);
        world.sources.addAll(sources);
        return world;
    }

    private static Materials.Texture[] parseTextures(Map<String, Object> root, Path map) throws IOException {
        if (!root.containsKey("textures")) return null;
        if (!(root.get("textures") instanceof Map))
            throw new IllegalArgumentException("textures must be an object");
        Map<String, Object> m = obj(root.get("textures"));
        int size = textureInt(m, "size", 1), cols = textureInt(m, "cols", 1), count = textureInt(m, "count", 1);
        if (size > Materials.MAX_SIDE || cols > Materials.MAX_SIDE || count > MAX_FILES)
            throw new IllegalArgumentException("textures.size/cols must be at most " + Materials.MAX_SIDE
                    + " and count at most " + MAX_FILES);
        if (!(m.get("file") instanceof String file) || file.isBlank())
            throw new IllegalArgumentException("textures.file must name the atlas PNG beside the map");
        if (!(m.get("mean") instanceof List<?> means) || means.size() != count)
            throw new IllegalArgumentException("textures.mean must have exactly count colours");
        int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            if (!(means.get(i) instanceof String color) || !color.matches("#[0-9a-fA-F]{6}"))
                throw new IllegalArgumentException("textures.mean[" + i + "] must be #rrggbb");
            colors[i] = Integer.parseInt(color.substring(1), 16);
        }
        return Materials.loadAtlas(beside(map, file, "texture atlas"), size, cols, count, colors);
    }

    /** The atlas's tiles and the map's whole images, whichever of the two it has. */
    private record Assets(Materials.Texture[] tiles, Materials.Texture[] images) {}

    /** "images": ["haven-img/0.png", ...], paths relative to the map: whole textures at their own
     *  size, picked by a shape's "img" and placed by its "uv". */
    private static Materials.Texture[] parseImages(Map<String, Object> root, Path map) throws IOException {
        if (root.get("images") == null) return null;
        List<Object> files = list(root.get("images"));
        if (files.size() > MAX_FILES)
            throw new IllegalArgumentException("a map may name at most " + MAX_FILES + " images");
        // Resolve every path on this thread: a refusal must name the file that caused it, and the
        // check is the one thing here that must not be racing anything.
        Path[] paths = new Path[files.size()];
        for (int i = 0; i < paths.length; i++) paths[i] = beside(map, files.get(i), "image");
        Materials.Texture[] out = new Materials.Texture[files.size()];
        // Two images failing at once used to overwrite the same slot from both threads; which
        // exception came out was then whichever write landed last, or neither.
        AtomicReference<IOException> failed = new AtomicReference<>();
        java.util.stream.IntStream.range(0, out.length).parallel().forEach(i -> {
            try {
                out[i] = Materials.loadImage(paths[i]);
            } catch (IOException e) {
                failed.compareAndSet(null, e);
            }
        });
        if (failed.get() != null) throw failed.get();
        return out;
    }

    private static Materials.Texture image(Assets a, Map<String, Object> m, String key) {
        int k = textureInt(m, key, 0);
        if (a.images() == null || k >= a.images().length)
            throw new IllegalArgumentException("shape " + key + " is outside the map's images: " + k);
        return a.images()[k];
    }

    private static int textureInt(Map<String, Object> m, String key, int min) {
        if (!(m.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < min || n.doubleValue() > Integer.MAX_VALUE
                || n.doubleValue() != Math.rint(n.doubleValue()))
            throw new IllegalArgumentException("textures/shape " + key + " must be an integer >= " + min);
        return n.intValue();
    }

    private static Materials.Texture texture(Map<String, Object> m, String key, Materials.Texture[] tiles) {
        int index = textureInt(m, key, 0);
        if (tiles == null) throw new IllegalArgumentException("shape " + key + " needs a map textures block");
        if (index >= tiles.length) throw new IllegalArgumentException("shape " + key + " is outside textures.count: " + index);
        return tiles[index];
    }

    private static double textureScale(Map<String, Object> m, String key, double fallback) {
        if (!m.containsKey(key)) return fallback;
        if (!(m.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() <= 0)
            throw new IllegalArgumentException("shape " + key + " must be finite and positive (metres per tile)");
        return n.doubleValue();
    }

    private static Region parseRegion(Map<String, Object> m) {
        Region r = new Region();
        r.name = str(m, "name", "");
        double[][] p = poly(m.get("poly"), 0, 0);
        r.xs = p[0];
        r.ys = p[1];
        if (!Geometry.isConvex(r.xs, r.ys)) throw new IllegalArgumentException("region polygon is not convex: " + r.name);
        r.floor = num(m, "floor", 0);
        Object c = m.get("ceil");
        r.sky = c == null;
        r.ceil = r.sky ? Double.POSITIVE_INFINITY : finite(c, "region ceil");
        r.top = num(m, "top", r.ceil);
        r.walkable = !(m.get("walkable") instanceof Boolean b) || b;
        r.floorMat = Materials.id(str(m, "floorMat", "concrete"));
        r.ceilMat = Materials.id(str(m, "ceilMat", "concrete"));
        r.wallMat = Materials.id(str(m, "wallMat", "plaster"));
        r.floorColor = color(m, "floorColor", "#a0a0a0");
        r.ceilColor = color(m, "ceilColor", "#d0d0d0");
        r.wallColor = color(m, "wallColor", "#d8d0c0");
        r.light = num(m, "light", r.sky ? 1.0 : 0.8);
        bounds(r.xs, r.ys, b -> { r.minX = b[0]; r.minY = b[1]; r.maxX = b[2]; r.maxY = b[3]; });
        return r;
    }

    /**
     * A tree crown, scattered.
     *
     * One card of leaves is a piece of cardboard however good its outline is: the whole of it is
     * lit the same, so there is no inside to the tree. A crown is really hundreds of small sprays
     * of leaves at every angle, the outer ones bright and the ones behind them in their shade - so
     * make it out of a dozen or two small cards spread through the volume instead, each with its
     * own place, size, angle and shade of green. Every one of them gets its own lightmap, which is
     * what puts a lit side, a shaded side and a dark middle into the tree for nothing.
     *
     * The cards are placed by rule from a seed, so a map says "a crown here, this big" and never
     * has to list them - the same thing a forest will do with its trees.
     */
    private static void canopy(Map<String, Object> m, double ox, double oy, List<Shape> out,
                               Assets textures) {
        double[] c = pt(m.get("c"));
        double cx = c[0] + ox, cy = c[1] + oy;
        double r = num(m, "r", 1.8), z0 = num(m, "z0", 1.8), z1 = num(m, "h", 4.8);
        int cards = whole(num(m, "cards", 16), "canopy cards", 0, MAX_REPEAT);
        int seed = whole(num(m, "seed", 1), "canopy seed", Integer.MIN_VALUE, Integer.MAX_VALUE);
        room(out, cards, "canopy cards");
        int color = color(m, "color", "#4d7d36");
        double mid = (z0 + z1) / 2, halfZ = (z1 - z0) / 2;
        for (int i = 0; i < cards; i++) {
            // Somewhere inside the crown, as a squashed ball: pick a height first, then how far
            // out the crown still reaches at that height.
            double up = 2 * rnd(seed, i, 1) - 1;                  // -1 bottom, +1 top
            double reach = Math.sqrt(Math.max(0, 1 - up * up));   // the ball's width up there
            double a = 2 * Math.PI * rnd(seed, i, 2);
            double d = r * reach * 0.72 * Math.sqrt(rnd(seed, i, 3));
            double x = cx + d * Math.cos(a), y = cy + d * Math.sin(a);
            double zc = mid + up * halfZ * 0.78;

            double half = r * (0.34 + 0.30 * rnd(seed, i, 4)) * (0.55 + 0.45 * reach);
            double tall = halfZ * (0.42 + 0.30 * rnd(seed, i, 5)) * (0.55 + 0.45 * reach);
            double dir = Math.PI * rnd(seed, i, 6);
            double ex = half * Math.cos(dir), ey = half * Math.sin(dir);

            Map<String, Object> card = new LinkedHashMap<>(m);
            card.put("type", "wall");
            card.put("a", List.of(x - ex - ox, y - ey - oy));
            card.put("b", List.of(x + ex - ox, y + ey - oy));
            card.put("z0", Math.max(0, zc - tall));
            card.put("h", zc + tall);
            card.put("mask", str(m, "mask", "canopy"));
            card.put("color", shadeOf(color, 0.82 + 0.30 * rnd(seed, i, 7)));
            parseShape(card, ox, oy, out, textures);
            out.get(out.size() - 1).albedoColor = color;
        }
    }

    /** A repeatable number in [0, 1) - the same crown every time the map is loaded. */
    private static double rnd(int seed, int i, int salt) {
        int h = seed * 374761393 ^ i * 668265263 ^ salt * 2147483647;
        h ^= h >>> 13;
        h *= 1274126177;
        h ^= h >>> 16;
        return (h & 0xffffff) / (double) 0x1000000;
    }

    private static String shadeOf(int c, double k) {
        int r = (int) Math.min(255, ((c >> 16) & 255) * k);
        int g = (int) Math.min(255, ((c >> 8) & 255) * k);
        int b = (int) Math.min(255, (c & 255) * k);
        return String.format("#%02x%02x%02x", r, g, b);
    }

    /** type: wall / circle / box / poly / ngon / array (tiles its items into a grid of copies)
     *  / canopy (scatters masked cards through the volume of a tree crown). */
    private static void parseShape(Map<String, Object> m, double ox, double oy, List<Shape> out,
                                   Assets textures) {
        String type = str(m, "type", "box");
        if (type.equals("canopy")) {
            canopy(m, ox, oy, out, textures);
            return;
        }
        if (type.equals("array")) {
            double[] o = pt(m.get("origin")), n = pt(m.get("count")), st = pt(m.get("step"));
            int nx = whole(n[0], "array count x", 0, MAX_REPEAT);
            int ny = whole(n[1], "array count y", 0, MAX_REPEAT);
            List<Object> items = list(m.get("items"));
            room(out, (long) nx * ny * items.size(), "array copies");
            for (int j = 0; j < ny; j++)
                for (int i = 0; i < nx; i++)
                    for (Object item : items)
                        parseShape(obj(item), ox + o[0] + i * st[0], oy + o[1] + j * st[1], out, textures);
            return;
        }
        room(out, 1, "shapes");

        Shape s = new Shape();
        s.z0 = num(m, "z0", 0);
        s.h = num(m, "h", 1);
        s.hx = num(m, "hx", 0);
        s.hy = num(m, "hy", 0);
        s.zx = num(m, "z0x", 0);
        s.zy = num(m, "z0y", 0);
        String mat = str(m, "mat", "concrete");
        s.mat = Materials.id(mat);
        s.topMat = Materials.id(str(m, "topMat", mat));
        // A shape without tex is unchanged, even in a textured map. Top overrides are independent:
        // topTex alone inherits ts, topTs alone changes the scale of the inherited tile.
        if (m.containsKey("tex")) {
            s.tex = texture(m, "tex", textures.tiles());
            s.ts = textureScale(m, "ts", 1);
            s.topTex = m.containsKey("topTex") ? texture(m, "topTex", textures.tiles()) : s.tex;
            s.topTs = textureScale(m, "topTs", s.ts);
        }
        if (m.containsKey("img")) {
            if (!(m.get("uv") instanceof List<?> uv) || uv.size() != 6)
                throw new IllegalArgumentException("a shape with img needs uv: six numbers");
            s.img = image(textures, m, "img");
            s.uv = new double[6];
            for (int i = 0; i < 6; i++) s.uv[i] = finite(uv.get(i), "uv[" + i + "]");
            if (m.containsKey("imgB")) {
                if (!(m.get("va") instanceof List<?> va) || va.size() != 3)
                    throw new IllegalArgumentException("a shape with imgB needs va: three numbers");
                s.imgB = image(textures, m, "imgB");
                s.hmap = image(textures, m, "hmap");
                s.va = new double[3];
                for (int i = 0; i < 3; i++) s.va[i] = finite(va.get(i), "va[" + i + "]");
                s.vb = num(m, "vb", 0);
                s.inv = num(m, "inv", 0) != 0;
            }
            if (m.containsKey("amap")) s.amap = image(textures, m, "amap");
            if (m.containsKey("vc")) {
                if (!(m.get("vc") instanceof List<?> vc) || vc.size() != 9)
                    throw new IllegalArgumentException("shape vc must be nine numbers");
                s.vc = new double[9];
                for (int i = 0; i < 9; i++) s.vc[i] = finite(vc.get(i), "vc[" + i + "]");
            }
        }
        s.color = color(m, "color", "#c8c8c8");
        s.albedoColor = s.color;
        s.maxDist = num(m, "maxDist", Double.POSITIVE_INFINITY);
        if (m.get("mask") != null) s.mask = Materials.maskId(str(m, "mask", ""));
        s.site = str(m, "site", null);
        s.label = switch (type) {
            case "wall" -> "wall";
            case "circle" -> "cylinder";
            case "box" -> "box";
            case "ngon" -> "ngon";
            default -> "poly";
        } + "/" + mat;

        switch (type) {
            case "wall" -> {
                s.kind = Kind.SEG;
                double[] a = pt(m.get("a")), b = pt(m.get("b"));
                s.ax = a[0] + ox; s.ay = a[1] + oy;
                s.bx = b[0] + ox; s.by = b[1] + oy;
                s.len = Math.hypot(s.bx - s.ax, s.by - s.ay);
                s.minX = Math.min(s.ax, s.bx); s.maxX = Math.max(s.ax, s.bx);
                s.minY = Math.min(s.ay, s.by); s.maxY = Math.max(s.ay, s.by);
            }
            case "circle" -> {
                s.kind = Kind.CIRCLE;
                double[] c = pt(m.get("c"));
                s.cx = c[0] + ox; s.cy = c[1] + oy;
                s.r = num(m, "r", 0.5);
                s.minX = s.cx - s.r; s.maxX = s.cx + s.r;
                s.minY = s.cy - s.r; s.maxY = s.cy + s.r;
            }
            case "box", "poly", "ngon" -> {
                s.kind = Kind.POLY;
                double[][] p;
                if (type.equals("box")) {
                    double x = num(m, "x", 0) + ox, y = num(m, "y", 0) + oy, w = num(m, "w", 1), d = num(m, "d", 1);
                    p = new double[][] {{x, x + w, x + w, x}, {y, y, y + d, y + d}};
                } else if (type.equals("ngon")) {
                    double[] c = pt(m.get("c"));
                    double r = num(m, "r", 0.5), rot = Math.toRadians(num(m, "rot", 0));
                    int n = whole(num(m, "n", 6), "ngon n", 3, MAX_POLY_POINTS);
                    p = new double[2][n];
                    for (int i = 0; i < n; i++) {
                        double a = rot + i * 2 * Math.PI / n;
                        p[0][i] = c[0] + ox + r * Math.cos(a);
                        p[1][i] = c[1] + oy + r * Math.sin(a);
                    }
                } else {
                    p = poly(m.get("pts"), ox, oy);
                }
                s.xs = p[0];
                s.ys = p[1];
                if (!Geometry.isConvex(s.xs, s.ys)) throw new IllegalArgumentException("shape polygon is not convex: " + m);
                bounds(s.xs, s.ys, b -> { s.minX = b[0]; s.minY = b[1]; s.maxX = b[2]; s.maxY = b[3]; });
            }
            default -> throw new IllegalArgumentException("unknown shape type: " + type);
        }
        s.hTop = s.h + Math.abs(s.hx) * (s.maxX - s.minX) / 2 + Math.abs(s.hy) * (s.maxY - s.minY) / 2;
        s.zLow = s.z0 - Math.abs(s.zx) * (s.maxX - s.minX) / 2 - Math.abs(s.zy) * (s.maxY - s.minY) / 2;
        out.add(s);
    }

    // ---- JSON helpers ----

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return (List<Object>) o; }

    /** A missing key takes the default; a key that is there must be a number the arithmetic can
     *  use. A NaN width spreads to the shape's bounds, from there to the map's, and from there to
     *  the whole grid, which then covers nothing and draws an empty world with nothing said. */
    static double num(Map<String, Object> m, String k, double def) {
        if (!m.containsKey(k)) return def;
        return finite(m.get(k), k);
    }

    static String str(Map<String, Object> m, String k, String def) {
        return m.get(k) instanceof String s ? s : def;
    }

    static int color(Map<String, Object> m, String k, String def) {
        String value = str(m, k, def);
        if (value == null || !value.matches("#[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException(k + " must be a colour like #rrggbb, not " + value);
        return Integer.parseInt(value.substring(1), 16);
    }

    static double[] pt(Object o) {
        List<Object> l = list(o);
        if (l == null || l.size() != 2) throw new IllegalArgumentException("a point must be two numbers");
        return new double[] {finite(l.get(0), "point x"), finite(l.get(1), "point y")};
    }

    private static double[][] poly(Object o, double ox, double oy) {
        List<Object> l = list(o);
        if (l == null || l.size() < 3 || l.size() > MAX_POLY_POINTS)
            throw new IllegalArgumentException("a polygon needs 3 to " + MAX_POLY_POINTS + " points");
        double[][] p = new double[2][l.size()];
        for (int i = 0; i < l.size(); i++) {
            double[] q = pt(l.get(i));
            p[0][i] = q[0] + ox;
            p[1][i] = q[1] + oy;
        }
        return p;
    }

    /** A number a map gave us, which the engine is about to compute with. */
    private static double finite(Object value, String what) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
            throw new IllegalArgumentException(what + " must be a finite number, not " + value);
        return n.doubleValue();
    }

    /** A count, an index, a number of sides: whole, and within what the engine can build. */
    private static int whole(double value, String what, int min, int max) {
        if (value != Math.rint(value) || value < min || value > max)
            throw new IllegalArgumentException(what + " must be a whole number from " + min + " to " + max
                    + ", not " + value);
        return (int) value;
    }

    /** Is there room for this many more shapes? Asked before the loop that would make them, not
     *  after, so an expansion that cannot fit costs a message rather than the whole heap. */
    private static void room(List<Shape> out, long more, String what) {
        if (more > MAX_SHAPES - out.size())
            throw new IllegalArgumentException(what + " would take the map past " + MAX_SHAPES + " shapes");
    }

    private static Map<String, Object> readJson(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_JSON_BYTES)
            throw new IOException(file + " is " + size + " bytes, more than the " + MAX_JSON_BYTES + " a map may be");
        return obj(Json.parse(Files.readString(file)));
    }

    /**
     * A file the map names, resolved against the map's own directory and required to stay inside it.
     *
     * Every path in a map file is relative to the map - "haven/0_0.json", "haven-img/12.png" - and
     * a map is a thing you are handed: a converter's output, a level someone sent you. Without this
     * check "../../../../etc/passwd" is a path the loader will happily read and hand to a PNG
     * decoder, and "images" is a list of any length. Keeping a map to its own directory costs
     * nothing that a real map does.
     */
    private static Path beside(Path map, Object name, String what) {
        if (!(name instanceof String file) || file.isBlank())
            throw new IllegalArgumentException("a " + what + " path must be a non-empty string, not " + name);
        Path dir = map.toAbsolutePath().normalize().getParent();
        Path resolved = dir.resolve(file).normalize();
        if (!resolved.startsWith(dir))
            throw new IllegalArgumentException(what + " \"" + file + "\" is outside the map's own directory");
        return resolved;
    }

    private interface BoundsSink { void accept(double[] b); }

    private static void bounds(double[] xs, double[] ys, BoundsSink sink) {
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1;
        for (int i = 0; i < xs.length; i++) {
            x0 = Math.min(x0, xs[i]); x1 = Math.max(x1, xs[i]);
            y0 = Math.min(y0, ys[i]); y1 = Math.max(y1, ys[i]);
        }
        sink.accept(new double[] {x0, y0, x1, y1});
    }
}

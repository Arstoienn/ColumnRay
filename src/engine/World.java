package engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Map data: a list of regions (floor / ceiling) plus a list of shapes.
 * The grid is pure acceleration: each cell stores the shapes and regions touching it, built on load.
 */
final class World {
    enum Kind { SEG, CIRCLE, POLY }

    /** A convex polygon region. ceil = +infinity means open to the sky. */
    static final class Region {
        String name;
        double[] xs, ys;
        double floor, ceil;
        double top;          // top of the wall above the opening, seen from an open-air region
        boolean sky;
        int floorMat, ceilMat, wallMat;
        int floorColor, ceilColor, wallColor;
        double light;        // ambient brightness (indoor regions are dimmer)
        double minX, minY, maxX, maxY;
    }

    static final class Shape {
        Kind kind;
        double ax, ay, bx, by, len;   // segment wall
        double cx, cy, r;             // cylinder
        double[] xs, ys;              // convex polygon
        double z0, h;                 // bottom and top height
        int mat, topMat, color;
        double maxDist;               // not drawn beyond this distance
        double minX, minY, maxX, maxY;
    }

    static final class Grid {
        double x0, y0, cell;
        int nx, ny;
        int[][] shapes, regions;
    }

    final String name;
    final Region[] regions;
    final Shape[] shapes;
    final Grid grid;
    final double sunX, sunY;
    final double spawnX, spawnY, spawnAngle;
    final double minX, minY, maxX, maxY;

    private World(String name, Region[] regions, Shape[] shapes, double cell,
                  double sunX, double sunY, double spawnX, double spawnY, double spawnAngle) {
        this.name = name;
        this.regions = regions;
        this.shapes = shapes;
        double len = Math.hypot(sunX, sunY);
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
    }

    // ---- Queries ----

    Region regionAt(double x, double y) {
        int cx = (int) Math.floor((x - grid.x0) / grid.cell);
        int cy = (int) Math.floor((y - grid.y0) / grid.cell);
        if (cx < 0 || cy < 0 || cx >= grid.nx || cy >= grid.ny) return null;
        for (int i : grid.regions[cy * grid.nx + cx]) {
            Region r = regions[i];
            if (Geometry.pointInPoly(x, y, r.xs, r.ys)) return r;
        }
        return null;
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

    private Grid buildGrid(double cell) {
        Grid g = new Grid();
        g.cell = cell;
        g.x0 = minX - cell;
        g.y0 = minY - cell;
        g.nx = (int) Math.ceil((maxX - g.x0) / cell) + 1;
        g.ny = (int) Math.ceil((maxY - g.y0) / cell) + 1;
        g.shapes = new int[g.nx * g.ny][];
        g.regions = new int[g.nx * g.ny][];

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Integer>[] sh = new List[g.nx * g.ny], rg = new List[g.nx * g.ny];
        for (int c = 0; c < sh.length; c++) { sh[c] = new ArrayList<>(); rg[c] = new ArrayList<>(); }
        for (int i = 0; i < shapes.length; i++) {
            final int id = i;
            Shape s = shapes[i];
            forCells(g, s.minX, s.minY, s.maxX, s.maxY, c -> sh[c].add(id));
        }
        for (int i = 0; i < regions.length; i++) {
            final int id = i;
            Region r = regions[i];
            forCells(g, r.minX, r.minY, r.maxX, r.maxY, c -> rg[c].add(id));
        }
        for (int c = 0; c < sh.length; c++) {
            g.shapes[c] = sh[c].stream().mapToInt(Integer::intValue).toArray();
            g.regions[c] = rg[c].stream().mapToInt(Integer::intValue).toArray();
        }
        return g;
    }

    // ---- JSON loading ----

    static World load(Path path) throws IOException {
        Map<String, Object> root = obj(Json.parse(Files.readString(path)));
        List<Region> regions = new ArrayList<>();
        for (Object o : list(root.get("regions"))) regions.add(parseRegion(obj(o)));
        List<Shape> shapes = new ArrayList<>();
        for (Object o : list(root.get("shapes"))) parseShape(obj(o), 0, 0, shapes);

        double[] sun = root.containsKey("sun") ? pt(root.get("sun")) : new double[] {0.5, 0.8};
        Map<String, Object> spawn = obj(root.get("spawn"));
        double[] sp = pt(spawn.get("pos"));
        return new World(str(root, "name", path.getFileName().toString()),
                regions.toArray(Region[]::new), shapes.toArray(Shape[]::new), num(root, "cell", 1.0),
                sun[0], sun[1], sp[0], sp[1], Math.toRadians(num(spawn, "angle", 0)));
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
        r.ceil = r.sky ? Double.POSITIVE_INFINITY : ((Number) c).doubleValue();
        r.top = num(m, "top", r.ceil);
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

    /** type: wall / circle / box / poly / ngon / array (array tiles its items into a grid of copies). */
    private static void parseShape(Map<String, Object> m, double ox, double oy, List<Shape> out) {
        String type = str(m, "type", "box");
        if (type.equals("array")) {
            double[] o = pt(m.get("origin")), n = pt(m.get("count")), st = pt(m.get("step"));
            for (int j = 0; j < (int) n[1]; j++)
                for (int i = 0; i < (int) n[0]; i++)
                    for (Object item : list(m.get("items")))
                        parseShape(obj(item), ox + o[0] + i * st[0], oy + o[1] + j * st[1], out);
            return;
        }

        Shape s = new Shape();
        s.z0 = num(m, "z0", 0);
        s.h = num(m, "h", 1);
        String mat = str(m, "mat", "concrete");
        s.mat = Materials.id(mat);
        s.topMat = Materials.id(str(m, "topMat", mat));
        s.color = color(m, "color", "#c8c8c8");
        s.maxDist = num(m, "maxDist", Double.POSITIVE_INFINITY);

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
                    int n = (int) num(m, "n", 6);
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
        out.add(s);
    }

    // ---- JSON helpers ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) { return (List<Object>) o; }

    private static double num(Map<String, Object> m, String k, double def) {
        return m.get(k) instanceof Number n ? n.doubleValue() : def;
    }

    private static String str(Map<String, Object> m, String k, String def) {
        return m.get(k) instanceof String s ? s : def;
    }

    private static int color(Map<String, Object> m, String k, String def) {
        return Integer.parseInt(str(m, k, def).substring(1), 16);
    }

    private static double[] pt(Object o) {
        List<Object> l = list(o);
        return new double[] {((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue()};
    }

    private static double[][] poly(Object o, double ox, double oy) {
        List<Object> l = list(o);
        double[][] p = new double[2][l.size()];
        for (int i = 0; i < l.size(); i++) {
            double[] q = pt(l.get(i));
            p[0][i] = q[0] + ox;
            p[1][i] = q[1] + oy;
        }
        return p;
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

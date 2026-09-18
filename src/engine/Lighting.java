package engine;

import engine.World.Kind;
import engine.World.Region;
import engine.World.Shape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Baked static lighting: lightmaps.
 *
 * Every surface the renderer can draw gets a grid of RGB light levels over its own flat
 * coordinates - world (x, y) for floors, ceilings and the tops and bottoms of shapes, (distance
 * along the face, height) for anything vertical. A world of vertical walls and horizontal slabs
 * unfolds like that on its own; there is no UV unwrapping to do.
 *
 * Each texel adds up what reaches it: the sun, the part of the sky it can see, and every light in
 * range. "Can it see it" is one {@link Occluder#clear} call - the renderer's 2D ray plus height
 * test, cast from the texel towards the light. All of that happens once, at load; at run time a
 * pixel reads its surface's lightmap and multiplies.
 *
 * Light reaches a texel two ways. Straight from a source - the sun, a lamp - which is one
 * {@link Occluder#clear} call each. Or off another surface: the same hemisphere of rays that
 * measures how much sky a texel can see also reports, through {@link Occluder#hit}, which surface
 * each blocked ray landed on, and that surface's own light times its colour comes back as bounced
 * light. Repeating that pass feeds each bounce the result of the one before, which is where soft
 * fill light, colour bleeding and a sunlit courtyard brightening the corridor behind it come from.
 *
 * Light levels are relative: 1.0 shows a texture at its own colour, and they may go above 1.
 */
final class Lighting {
    /** Texels are lifted this far off their surface before any test, so no surface shadows itself. */
    private static final double LIFT = 0.03;
    private static final double SUN_REACH = 60;

    /** One surface's light levels on a regular grid, sampled bilinearly. */
    static final class LightMap {
        final double u0, v0, step;
        final int w, h;
        final float[] rgb;                       // w * h texels, three floats each
        final float[] albedo = new float[3];     // the surface's own colour: what it bounces back
        int mat;                                 // its material, for the ceiling panels that glow

        LightMap(double u0, double v0, double u1, double v1, double step) {
            this.u0 = u0;
            this.v0 = v0;
            this.step = step;
            this.w = Math.max(2, (int) Math.ceil((u1 - u0) / step) + 1);
            this.h = Math.max(2, (int) Math.ceil((v1 - v0) / step) + 1);
            this.rgb = new float[w * h * 3];
        }

        double u(int i) { return u0 + i * step; }

        double v(int j) { return v0 + j * step; }

        /** Bilinear, clamped at the edges. */
        void sample(double u, double v, float[] out) {
            double fu = Math.max(0, Math.min(w - 1.0001, (u - u0) / step));
            double fv = Math.max(0, Math.min(h - 1.0001, (v - v0) / step));
            int i = (int) fu, j = (int) fv;
            float a = (float) (fu - i), b = (float) (fv - j);
            int k00 = (j * w + i) * 3, k10 = k00 + 3, k01 = k00 + w * 3, k11 = k01 + 3;
            for (int c = 0; c < 3; c++) {
                float top = rgb[k00 + c] + (rgb[k10 + c] - rgb[k00 + c]) * a;
                float bot = rgb[k01 + c] + (rgb[k11 + c] - rgb[k01 + c]) * a;
                out[c] = top + (bot - top) * b;
            }
        }
    }

    /** Where a surface's point (u, v) is in the world, and which way the surface faces there. */
    private interface Place { void at(double u, double v, double[] p, double[] n); }

    /** One surface's bake: the light that arrives straight from a source, and the light that
     *  arrives off other surfaces. They are kept apart so a bounce pass can be redone from the
     *  previous pass's totals without the direct half being counted again. */
    private record Job(LightMap map, Place place, boolean[] ok, float[] direct, float[] bounced) {}

    private record Light(double x, double y, double z, float r, float g, float b, double range) {}

    final LightMap[] floor, ceil, top, bottom;
    final LightMap[][] side, edge;               // per shape: one per face; per region: one per edge

    LightMap floor(Region r) { return floor[r.id]; }

    LightMap ceil(Region r) { return ceil[r.id]; }

    LightMap top(Shape s) { return top[s.id]; }

    LightMap bottom(Shape s) { return bottom[s.id]; }

    /** Face 0/1 are the two sides of a thin wall, the edge index for a polygon, 0 for a cylinder. */
    LightMap side(Shape s, int face) {
        LightMap[] f = side[s.id];
        return f != null && face >= 0 && face < f.length ? f[face] : null;
    }

    /** The wall on a region's edge e, as seen from inside the region: lintels, risers, slab edges. */
    LightMap edge(Region r, int e) {
        LightMap[] f = edge[r.id];
        return f != null && e >= 0 && e < f.length ? f[e] : null;
    }

    private final World world;
    private final float[] ambient, sunColor, skyColor, panelGlow;
    private final double sunX, sunY, sunZ, soft;
    private final List<Light> lights = new ArrayList<>();
    private final double[][] hemi;                   // cosine-weighted directions around +z
    private final int samples, bounces, shadow, blurs;
    long rays;                                       // shadow and gather rays cast by the bake
    private List<LightMap> allMaps = List.of();      // every map in bake order, for the cache and hash()
    private java.nio.file.Path fromCache;            // set when the texels were read back rather than baked
    private final double reach, reflect, sunSoft, lampSize;

    static Lighting bake(World w) {
        long t0 = System.nanoTime();
        Lighting l = new Lighting(w);
        int texels = 0, maps = 0;
        for (LightMap[] ms : new LightMap[][] {l.floor, l.ceil, l.top, l.bottom})
            for (LightMap m : ms) if (m != null) { texels += m.w * m.h; maps++; }
        for (LightMap[][] ms : new LightMap[][][] {l.side, l.edge})
            for (LightMap[] f : ms) if (f != null) for (LightMap m : f) if (m != null) { texels += m.w * m.h; maps++; }
        double s = (System.nanoTime() - t0) / 1e9;
        if (Boolean.getBoolean("light.stats")) {
            double sum = 0, mx = 0; long n = 0;
            java.util.List<LightMap[]> all = new ArrayList<>(java.util.List.of(l.floor, l.ceil, l.top, l.bottom));
            for (LightMap[][] ms : new LightMap[][][] {l.side, l.edge})
                for (LightMap[] f : ms) if (f != null) all.add(f);
            for (LightMap[] ms : all)
                for (LightMap m : ms) if (m != null)
                    for (float v : m.rgb) { sum += v; mx = Math.max(mx, v); n++; }
            System.out.printf("lightmap: mean %.3f, max %.3f over %,d channels%n", sum / n, mx, n);
        }
        if (l.fromCache != null)
            System.out.printf("lighting: %,d texels on %,d surfaces, %d lights, %,d rays when baked, read from %s in %.1f s%n",
                    texels, maps, l.lights.size(), l.rays, l.fromCache, s);
        else
            System.out.printf("lighting: %,d texels on %,d surfaces, %d lights, %,d rays, baked in %.1f s (%,.0f rays/s)%n",
                    texels, maps, l.lights.size(), l.rays, s, l.rays / s);
        return l;
    }

    /**
     * Every texel of every lightmap, in the order the bake lays its surfaces out, with the shape
     * of each map so that two bakes which merely divide the same texels differently do not agree.
     *
     * This is the check that caught the unpadded point-in-node test and the {@code bottomAt}
     * rounding error: both moved 480 rays and not one pixel, so only the texels showed it.
     */
    String hash() {
        Hash h = Hash.of();
        for (LightMap m : allMaps) {
            h.add(m.w).add(m.h).add(m.rgb, m.rgb.length);
            h.add(m.albedo, m.albedo.length).add(m.mat);
        }
        return h.hex();
    }

    private Lighting(World w) {
        world = w;
        Map<String, Object> spec = w.lighting != null ? w.lighting : Map.of();
        Map<String, Object> sun = sub(spec, "sun"), sky = sub(spec, "sky"), panels = sub(spec, "panelLights");
        Map<String, Object> ind = sub(spec, "indirect");
        // A coarser bake, for when the map is being changed over and over and only the shape of
        // the light matters: -Dlight.texel=1 quarters the texels and the time with them.
        double texel = Double.parseDouble(System.getProperty("light.texel",
                String.valueOf(World.num(spec, "texel", 0.2))));
        samples = (int) World.num(ind, "samples", 32);
        bounces = Math.max(1, (int) World.num(ind, "bounces", 2));   // pass 1 is the sky's as well
        reach = World.num(ind, "reach", 40);
        blurs = Math.max(0, (int) World.num(ind, "smooth", 2));
        reflect = World.num(ind, "reflectance", 0.6);

        double el = Math.toRadians(World.num(sun, "elevation", 40));
        sunX = w.sunX * Math.cos(el);
        sunY = w.sunY * Math.cos(el);
        sunZ = Math.sin(el);
        sunColor = rgb(World.color(sun, "color", "#fff2de"), World.num(sun, "intensity", 1.0));
        skyColor = rgb(World.color(sky, "color", "#a9c6ee"), World.num(sky, "intensity", 0.5));
        ambient = rgb(World.color(spec, "ambient", "#121418"), 1);
        soft = World.num(panels, "soft", 2.0);
        shadow = Math.max(1, (int) World.num(spec, "shadowSamples", 8));
        sunSoft = Math.tan(Math.toRadians(World.num(sun, "softness", 2.0)));
        lampSize = World.num(panels, "size", Materials.PANEL_CELL);
        // The panels are point lights already; as a surface they only glow enough to keep the
        // ceiling around them from reading as the darkest thing in a lit room.
        panelGlow = rgb(World.color(panels, "color", "#fff1de"),
                World.num(panels, "glow", 0.5 * World.num(panels, "intensity", 0.7)));

        hemi = new double[samples][3];
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < samples; i++) {
            double u = (i + 0.5) / samples, r = Math.sqrt(u), a = i * golden;
            hemi[i][0] = r * Math.cos(a);
            hemi[i][1] = r * Math.sin(a);
            hemi[i][2] = Math.sqrt(1 - u);
        }

        addPanelLights(w, panels);
        if (spec.get("lights") != null) {
            for (Object o : World.list(spec.get("lights"))) {
                Map<String, Object> m = World.obj(o);
                double[] p = World.pt(m.get("pos"));
                float[] c = rgb(World.color(m, "color", "#fff1de"), World.num(m, "intensity", 1.0));
                lights.add(new Light(p[0], p[1], World.num(m, "z", 2.5), c[0], c[1], c[2], World.num(m, "range", 7)));
            }
        }
        indexLights();

        int nr = w.regions.length, ns = w.shapes.length;
        floor = new LightMap[nr];
        ceil = new LightMap[nr];
        edge = new LightMap[nr][];
        top = new LightMap[ns];
        bottom = new LightMap[ns];
        side = new LightMap[ns][];
        List<Job> jobs = new ArrayList<>();

        double roof = Double.NEGATIVE_INFINITY;                  // the top of the building
        for (Region r : w.regions) {
            if (r.top < Double.POSITIVE_INFINITY) roof = Math.max(roof, r.top);
            if (r.ceil < Double.POSITIVE_INFINITY) roof = Math.max(roof, r.ceil);
        }
        for (Region r : w.regions) {
            floor[r.id] = add(jobs, new LightMap(r.minX, r.minY, r.maxX, r.maxY, texel), flat(r.floor, 1),
                    r.floorColor, r.floorMat);
            if (!r.sky) ceil[r.id] = add(jobs, new LightMap(r.minX, r.minY, r.maxX, r.maxY, texel),
                    flat(r.ceil, -1), r.ceilColor, r.ceilMat);
            // Walls seen across this region's boundary can only show between its own floor and
            // ceiling - or, under open sky, up to the roofline.
            double zTop = r.sky ? roof : r.ceil;
            edge[r.id] = new LightMap[r.xs.length];
            if (zTop > r.floor) {
                for (int e = 0; e < r.xs.length; e++) {
                    Face f = face(r.xs, r.ys, e, false);
                    edge[r.id][e] = add(jobs, new LightMap(0, r.floor, f.len, zTop, texel), f,
                            r.wallColor, r.wallMat);
                }
            }
        }
        for (Shape s : w.shapes) {
            switch (s.kind) {
                case SEG -> {
                    double[] xs = {s.ax, s.bx}, ys = {s.ay, s.by};
                    Face f0 = face(xs, ys, 0, true), f1 = f0.flipped();
                    side[s.id] = new LightMap[] {
                        add(jobs, new LightMap(0, s.zLow, s.len, s.hTop, texel), f0, s.color, s.mat),
                        add(jobs, new LightMap(0, s.zLow, s.len, s.hTop, texel), f1, s.color, s.mat)};
                }
                case CIRCLE -> {
                    final double cx = s.cx, cy = s.cy, rad = s.r;
                    side[s.id] = new LightMap[] {add(jobs, new LightMap(0, s.zLow, 2 * Math.PI * rad, s.hTop, texel),
                            (Place) (u, v, p, n) -> {
                                double a = u / rad - Math.PI;         // the renderer's u = (atan2 + pi) * r
                                set(p, cx + rad * Math.cos(a), cy + rad * Math.sin(a), v);
                                set(n, Math.cos(a), Math.sin(a), 0);
                            }, s.color, s.mat)};
                }
                case POLY -> {
                    side[s.id] = new LightMap[s.xs.length];
                    for (int e = 0; e < s.xs.length; e++) {
                        Face f = face(s.xs, s.ys, e, true);
                        side[s.id][e] = add(jobs, new LightMap(0, s.zLow, f.len, s.hTop, texel), f, s.color, s.mat);
                    }
                }
            }
            if (s.kind != Kind.SEG) {
                top[s.id] = add(jobs, new LightMap(s.minX, s.minY, s.maxX, s.maxY, texel), topFace(s),
                        s.color, s.topMat);
                if (s.zLow > 0.01) bottom[s.id] = add(jobs, new LightMap(s.minX, s.minY, s.maxX, s.maxY, texel),
                        bottomFace(s), s.color, s.mat);
            }
        }

        // One task per texel row across every surface, spread over all cores. The direct pass
        // stands alone; every gather pass after it reads the totals the pass before wrote, so each
        // one adds a bounce. The sky arrives with the first of them.
        // Baked before with the same files, settings and code: read the texels back instead.
        byte[] key = LightCache.key(w);
        java.nio.file.Path cacheFile = key == null ? null : LightCache.file(w, key);
        List<LightMap> maps = jobs.stream().map(Job::map).toList();
        allMaps = maps;
        if (cacheFile != null) {
            long[] cached = new long[1];
            if (LightCache.load(cacheFile, key, maps, cached)) {
                rays = cached[0];
                fromCache = cacheFile;
                return;
            }
            for (LightMap m : maps) Arrays.fill(m.rgb, 0);         // a file that did not fit may have written some
        }

        int[] start = new int[jobs.size() + 1];
        for (int k = 0; k < jobs.size(); k++) start[k + 1] = start[k] + jobs.get(k).map.h;
        List<Occluder> all = Collections.synchronizedList(new ArrayList<>());
        ThreadLocal<Occluder> occ = ThreadLocal.withInitial(() -> {
            Occluder o = new Occluder(w);
            all.add(o);
            return o;
        });
        int rows = start[jobs.size()];
        // Say what is happening. On Haven this is minutes of a silent black screen otherwise, and
        // the first person to run it waited, decided it had hung, and killed it.
        long t = System.nanoTime();
        System.out.printf("baking light: %,d surfaces, %,d texel rows, %d lights, %d bounces%n",
                jobs.size(), rows, lights.size(), bounces);
        IntStream.range(0, rows).parallel().forEach(row -> {
            int k = owner(start, row);
            directRow(jobs.get(k), row - start[k], occ.get());
        });
        jobs.parallelStream().forEach(Lighting::total);
        t = step("  sun, sky and lamps", t);
        for (int pass = 0; pass < bounces; pass++) {
            IntStream.range(0, rows).parallel().forEach(row -> {
                int k = owner(start, row);
                gatherRow(jobs.get(k), row - start[k], occ.get());
            });
            jobs.parallelStream().forEach(j -> {
                for (int b = 0; b < blurs; b++) smooth(j);
                total(j);
            });
            t = step("  bounce " + (pass + 1) + " of " + bounces, t);
        }
        jobs.parallelStream().forEach(j -> dilate(j.map, j.ok));
        for (Occluder o : all) rays += o.rays;
        if (cacheFile != null) LightCache.save(cacheFile, key, maps, rays);
    }

    /** Print how long a pass took, and hand back the clock for the next one. */
    private static long step(String what, long since) {
        long now = System.nanoTime();
        System.out.printf("%-22s %5.1f s%n", what, (now - since) / 1e9);
        return now;
    }

    // ---- Light sources ----

    /** The lit cells of every panel ceiling become point lights just below it - the same cells
     *  Materials draws bright, so what glows is what lights the room. */
    private void addPanelLights(World w, Map<String, Object> spec) {
        if (Boolean.FALSE.equals(spec.get("enabled"))) return;
        float[] c = rgb(World.color(spec, "color", "#fff1de"), World.num(spec, "intensity", 0.7));
        double drop = World.num(spec, "drop", 0.3), range = World.num(spec, "range", 7);
        double cell = Materials.PANEL_CELL;
        double stepX = cell * Materials.PANEL_EVERY_X, stepY = cell * Materials.PANEL_EVERY_Y, off = cell * 1.5;
        for (Region r : w.regions) {
            if (r.sky || r.ceilMat != Materials.PANEL) continue;
            for (double x = Math.floor((r.minX - off) / stepX) * stepX + off; x <= r.maxX; x += stepX)
                for (double y = Math.floor((r.minY - off) / stepY) * stepY + off; y <= r.maxY; y += stepY)
                    if (insideBy(r, x, y, cell / 2)) lights.add(new Light(x, y, r.ceil - drop, c[0], c[1], c[2], range));
        }
    }

    /**
     * Which lights can reach anywhere in each LIGHT_CELL square. Haven has 189 lamps and each of
     * them reaches 7 to 9 m, yet every sample of every texel went through all of them before
     * finding out they were too far - a quarter of the bake, measured. A light is listed in every
     * cell its range, plus the half-width of the spot it is aimed at, reaches into; one that is not
     * listed cannot be within range of any point in the cell, so the answer is exactly what it was.
     */
    private static final double LIGHT_CELL = 4;
    private static final int[] NO_LIGHTS = {};
    private int[][] lightCells = new int[0][];
    private double lightX0, lightY0;
    private int lightNx, lightNy;

    private void indexLights() {
        if (lights.isEmpty()) return;
        double pad = 0.5 * lampSize;
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1;
        for (Light l : lights) {
            x0 = Math.min(x0, l.x() - l.range() - pad); y0 = Math.min(y0, l.y() - l.range() - pad);
            x1 = Math.max(x1, l.x() + l.range() + pad); y1 = Math.max(y1, l.y() + l.range() + pad);
        }
        lightX0 = x0;
        lightY0 = y0;
        lightNx = (int) Math.ceil((x1 - x0) / LIGHT_CELL) + 1;
        lightNy = (int) Math.ceil((y1 - y0) / LIGHT_CELL) + 1;
        List<List<Integer>> cells = new ArrayList<>();
        for (int c = 0; c < lightNx * lightNy; c++) cells.add(new ArrayList<>());
        for (int i = 0; i < lights.size(); i++) {
            Light l = lights.get(i);
            double reachOf = l.range() + pad;
            int ix0 = (int) Math.floor((l.x() - reachOf - x0) / LIGHT_CELL), ix1 = (int) Math.floor((l.x() + reachOf - x0) / LIGHT_CELL);
            int iy0 = (int) Math.floor((l.y() - reachOf - y0) / LIGHT_CELL), iy1 = (int) Math.floor((l.y() + reachOf - y0) / LIGHT_CELL);
            for (int cy = Math.max(0, iy0); cy <= Math.min(lightNy - 1, iy1); cy++)
                for (int cx = Math.max(0, ix0); cx <= Math.min(lightNx - 1, ix1); cx++)
                    cells.get(cy * lightNx + cx).add(i);                     // ascending: the order is kept
        }
        lightCells = new int[cells.size()][];
        for (int c = 0; c < cells.size(); c++) lightCells[c] = cells.get(c).stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean insideBy(Region r, double x, double y, double margin) {
        if (!Geometry.pointInPoly(x, y, r.xs, r.ys)) return false;
        for (int i = 0, n = r.xs.length; i < n; i++) {
            int j = (i + 1) % n;
            if (Geometry.distPointSeg(x, y, r.xs[i], r.ys[i], r.xs[j], r.ys[j]) < margin) return false;
        }
        return true;
    }

    // ---- Baking ----

    /** Which job a global row index belongs to. */
    private static int owner(int[] start, int row) {
        int k = Arrays.binarySearch(start, row);
        return k < 0 ? -k - 2 : k;
    }

    private static void total(Job job) {
        for (int i = 0; i < job.map.rgb.length; i++) job.map.rgb[i] = job.direct[i] + job.bounced[i];
    }

    /**
     * Direct light, one texel at a time. A shadow ray answers yes or no, so one ray per texel puts
     * every shadow edge on a texel boundary and the edge comes out as a staircase of 20 cm steps.
     * Sampling the texel at several spread-out spots instead - and treating the sun and the ceiling
     * panels as the discs they really are rather than as points - lands the texel somewhere between
     * lit and dark, which is what the bilinear filter needs to draw a smooth edge. It is also what
     * really happens: an edge is soft, and softer the further the shadow falls from what casts it.
     */
    private void directRow(Job job, int j, Occluder oc) {
        LightMap m = job.map;
        double[] p = new double[3], n = new double[3];
        float[] c = new float[3];
        for (int i = 0; i < m.w; i++) {
            job.place.at(m.u(i), m.v(j), p, n);
            double x = p[0] + n[0] * LIFT, y = p[1] + n[1] * LIFT, z = p[2] + n[2] * LIFT;
            if (!oc.open(x, y, z)) continue;                 // inside a wall or a slab: filled in by dilate()
            int k = j * m.w + i;
            job.ok[k] = true;
            float r = 0, g = 0, b = 0;
            int taken = 0;
            for (int t = 0; t < shadow; t++) {
                double sx = x, sy = y, sz = z;
                if (t > 0) {                                 // spot 0 is the texel's own centre
                    job.place.at(m.u(i) + (rnd(k, t, 1) - 0.5) * m.step,
                            m.v(j) + (rnd(k, t, 2) - 0.5) * m.step, p, n);
                    sx = p[0] + n[0] * LIFT;
                    sy = p[1] + n[1] * LIFT;
                    sz = p[2] + n[2] * LIFT;
                    if (!oc.open(sx, sy, sz)) continue;      // that spot is inside something: skip it
                }
                direct(oc, sx, sy, sz, n, k * 31 + t, c);
                r += c[0];
                g += c[1];
                b += c[2];
                taken++;
            }
            job.direct[k * 3] = r / taken;
            job.direct[k * 3 + 1] = g / taken;
            job.direct[k * 3 + 2] = b / taken;
        }
    }

    /** A repeatable number in [0, 1) for sample t of texel k: the bake must not change run to run. */
    private static double rnd(int k, int t, int salt) {
        int h = k * 73856093 ^ t * 19349663 ^ salt * 83492791;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        return (h & 0xffffff) / (double) 0x1000000;
    }

    /** The sky and bounce pass. Every texel fires the same hemisphere of rays; each one either
     *  gets away to the sky or lands on a surface and brings its light back. */
    private void gatherRow(Job job, int j, Occluder oc) {
        LightMap m = job.map;
        double[] p = new double[3], n = new double[3];
        float[] c = new float[3];
        Occluder.Hit h = new Occluder.Hit();
        for (int i = 0; i < m.w; i++) {
            int k = j * m.w + i;
            if (!job.ok[k]) continue;
            // Fired from a different spot in each texel, as well as in different directions, so
            // that what is left of the noise has no grid in it for the eye to lock on to.
            job.place.at(m.u(i) + (rnd(k, 0, 3) - 0.5) * m.step, m.v(j) + (rnd(k, 0, 4) - 0.5) * m.step, p, n);
            double x = p[0] + n[0] * LIFT, y = p[1] + n[1] * LIFT, z = p[2] + n[2] * LIFT;
            if (!oc.open(x, y, z)) {                         // that spot is inside something
                job.place.at(m.u(i), m.v(j), p, n);
                x = p[0] + n[0] * LIFT;
                y = p[1] + n[1] * LIFT;
                z = p[2] + n[2] * LIFT;
            }
            gather(oc, h, x, y, z, n, turn(i, j), c);
            job.bounced[k * 3] = c[0];
            job.bounced[k * 3 + 1] = c[1];
            job.bounced[k * 3 + 2] = c[2];
        }
    }

    /** A texel's own turn of the sample pattern, so neighbours do not all miss the same window. */
    private static double turn(int i, int j) {
        int s = i * 73856093 ^ j * 19349663;
        s ^= s >>> 13;
        return (s * 2654435761L & 0xffffff) * (2 * Math.PI / 0x1000000);
    }

    /** Everything that reaches the point (x, y, z) on a surface facing n straight from a source.
     *  {@code seed} picks this sample's spot on the sun and on each lamp; averaging several of them
     *  is what makes an edge soft. */
    private void direct(Occluder oc, double x, double y, double z, double[] n, int seed, float[] out) {
        float r = ambient[0], g = ambient[1], b = ambient[2];

        // The sun is a disc about half a degree wide, not a point, so aim at a spot on it.
        double ja = 2 * Math.PI * rnd(seed, 0, 5), jr = sunSoft * Math.sqrt(rnd(seed, 0, 6));
        double px = -sunY, py = sunX, pl = Math.hypot(px, py);   // any two directions across the sun
        px /= pl;
        py /= pl;
        double qx = sunY * 0 - sunZ * py, qy = sunZ * px - sunX * 0, qz = sunX * py - sunY * px;
        double jx = px * jr * Math.cos(ja) + qx * jr * Math.sin(ja);
        double jy = py * jr * Math.cos(ja) + qy * jr * Math.sin(ja);
        double jz = qz * jr * Math.sin(ja);
        double dx = sunX + jx, dy = sunY + jy, dz = sunZ + jz;

        double ndl = n[0] * dx + n[1] * dy + n[2] * dz;
        if (ndl > 0 && oc.clear(x, y, z, x + dx * SUN_REACH, y + dy * SUN_REACH, z + dz * SUN_REACH)) {
            r += sunColor[0] * ndl;
            g += sunColor[1] * ndl;
            b += sunColor[2] * ndl;
        }

        int cx = (int) Math.floor((x - lightX0) / LIGHT_CELL), cy = (int) Math.floor((y - lightY0) / LIGHT_CELL);
        int[] near = cx >= 0 && cy >= 0 && cx < lightNx && cy < lightNy ? lightCells[cy * lightNx + cx] : NO_LIGHTS;
        for (int i : near) {
            Light l = lights.get(i);
            int li = i + 1;                                  // its place in the whole list: the same spot as ever
            // A ceiling panel is a square of light, so aim at a spot on it, not at its middle.
            double a = 2 * Math.PI * rnd(seed, li, 7), rr = 0.5 * lampSize * Math.sqrt(rnd(seed, li, 8));
            double tx = l.x + rr * Math.cos(a), ty = l.y + rr * Math.sin(a);
            double lx = tx - x, ly = ty - y, lz = l.z - z, d2 = lx * lx + ly * ly + lz * lz;
            if (d2 > l.range * l.range) continue;
            double d = Math.sqrt(d2), cos = (n[0] * lx + n[1] * ly + n[2] * lz) / d;
            if (cos <= 0) continue;
            double q = d / l.range, window = 1 - q * q * q * q;      // fades smoothly to 0 at the range
            double att = cos * window * window / (1 + d2 / (soft * soft));
            if (att < 1e-3 || !oc.clear(x, y, z, tx, ty, l.z)) continue;
            r += (float) (l.r * att);
            g += (float) (l.g * att);
            b += (float) (l.b * att);
        }
        out[0] = r;
        out[1] = g;
        out[2] = b;
    }

    /**
     * Sky and bounced light at one point. {@code samples} rays spread over the cosine-weighted
     * hemisphere around n, the whole pattern turned by {@code phi} so that neighbouring texels do
     * not sample the same directions and their noise averages out under {@link #smooth}. A ray that
     * gets away above the horizon sees sky; one that lands on a surface brings back that surface's
     * light times its colour. Cosine weighting is already in the directions, so the average over
     * the samples is the answer.
     */
    private void gather(Occluder oc, Occluder.Hit h, double x, double y, double z, double[] n,
                        double phi, float[] out) {
        double tx, ty, tz;
        if (Math.abs(n[2]) < 0.9) { tx = -n[1]; ty = n[0]; tz = 0; } else { tx = 1; ty = 0; tz = 0; }
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl; ty /= tl; tz /= tl;
        double bx = n[1] * tz - n[2] * ty, by = n[2] * tx - n[0] * tz, bz = n[0] * ty - n[1] * tx;
        double cs = Math.cos(phi), sn = Math.sin(phi);
        double ux = tx * cs + bx * sn, uy = ty * cs + by * sn, uz = tz * cs + bz * sn;
        double vx = bx * cs - tx * sn, vy = by * cs - ty * sn, vz = bz * cs - tz * sn;

        float r = 0, g = 0, b = 0;
        float[] c = new float[3];
        for (double[] d : hemi) {
            double dx = ux * d[0] + vx * d[1] + n[0] * d[2];
            double dy = uy * d[0] + vy * d[1] + n[1] * d[2];
            double dz = uz * d[0] + vz * d[1] + n[2] * d[2];
            if (!oc.hit(x, y, z, x + dx * reach, y + dy * reach, z + dz * reach, h)) {
                if (dz > 0.02) {                             // below the horizon there is no sky
                    r += skyColor[0];
                    g += skyColor[1];
                    b += skyColor[2];
                }
            } else if (h.what != Occluder.Hit.SOLID) {
                radiance(h, c);
                r += c[0];
                g += c[1];
                b += c[2];
            }
        }
        out[0] = r / samples;
        out[1] = g / samples;
        out[2] = b / samples;
    }

    /** What the surface a ray landed on sends back: the light on it times its own colour, or, for
     *  a lit ceiling panel, the panel's glow - what the renderer draws bright really does light
     *  the room. Somewhere with no lightmap of its own sends back nothing. */
    private void radiance(Occluder.Hit h, float[] out) {
        LightMap m;
        double u, v;
        switch (h.what) {
            case Occluder.Hit.FLOOR -> { m = floor[h.id]; u = h.x; v = h.y; }
            case Occluder.Hit.CEIL -> { m = ceil[h.id]; u = h.x; v = h.y; }
            case Occluder.Hit.TOP -> { m = top[h.id]; u = h.x; v = h.y; }
            case Occluder.Hit.BOTTOM -> { m = bottom[h.id]; u = h.x; v = h.y; }
            case Occluder.Hit.EDGE -> {
                Region r = world.regions[h.id];
                m = edge(r, h.face);
                u = along(r.xs, r.ys, h.face, h.x, h.y);
                v = h.z;
            }
            default -> {
                Shape s = world.shapes[h.id];
                m = side(s, h.face);
                u = s.kind == Kind.CIRCLE ? (Math.atan2(h.y - s.cy, h.x - s.cx) + Math.PI) * s.r
                        : s.kind == Kind.SEG ? along(new double[] {s.ax, s.bx}, new double[] {s.ay, s.by}, 0, h.x, h.y)
                        : along(s.xs, s.ys, h.face, h.x, h.y);
                v = h.z;
            }
        }
        if (m == null) {
            out[0] = out[1] = out[2] = 0;
            return;
        }
        if (Materials.emissive(m.mat, h.x, h.y)) {
            System.arraycopy(panelGlow, 0, out, 0, 3);
            return;
        }
        m.sample(u, v, out);
        out[0] *= m.albedo[0];
        out[1] *= m.albedo[1];
        out[2] *= m.albedo[2];
    }

    /** How far along edge e of a polygon the point (x, y) lies - the lightmap's u for that face. */
    private static double along(double[] xs, double[] ys, int e, double x, double y) {
        int j = (e + 1) % xs.length;
        double ex = xs[j] - xs[e], ey = ys[j] - ys[e], len = Math.hypot(ex, ey);
        return ((x - xs[e]) * ex + (y - ys[e]) * ey) / len;
    }

    /** Bounced light is smooth by nature and sampled by only a few rays, so a couple of passes of
     *  a 3x3 average over the valid texels buy back most of what the sampling costs in noise. */
    private static void smooth(Job job) {
        LightMap m = job.map;
        float[] in = job.bounced.clone();
        for (int j = 0; j < m.h; j++) {
            for (int i = 0; i < m.w; i++) {
                int k = j * m.w + i;
                if (!job.ok[k]) continue;
                float r = 0, g = 0, b = 0;
                int cnt = 0;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        int ii = i + di, jj = j + dj;
                        if (ii < 0 || jj < 0 || ii >= m.w || jj >= m.h || !job.ok[jj * m.w + ii]) continue;
                        int o = (jj * m.w + ii) * 3;
                        r += in[o];
                        g += in[o + 1];
                        b += in[o + 2];
                        cnt++;
                    }
                }
                job.bounced[k * 3] = r / cnt;
                job.bounced[k * 3 + 1] = g / cnt;
                job.bounced[k * 3 + 2] = b / cnt;
            }
        }
    }

    /** Texels whose sample point fell inside something solid take the average of their valid
     *  neighbours, so bilinear filtering never drags black in from inside a wall. */
    private static void dilate(LightMap m, boolean[] ok) {
        boolean any = false;
        for (boolean b : ok) any |= b;
        if (!any) return;                                    // entirely inside something: never seen
        for (int pass = 0; pass < 64; pass++) {
            boolean[] next = ok.clone();
            boolean missing = false;
            for (int j = 0; j < m.h; j++) {
                for (int i = 0; i < m.w; i++) {
                    int k = j * m.w + i;
                    if (ok[k]) continue;
                    float r = 0, g = 0, b = 0;
                    int cnt = 0;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            int ii = i + di, jj = j + dj;
                            if (ii < 0 || jj < 0 || ii >= m.w || jj >= m.h || !ok[jj * m.w + ii]) continue;
                            int o = (jj * m.w + ii) * 3;
                            r += m.rgb[o]; g += m.rgb[o + 1]; b += m.rgb[o + 2];
                            cnt++;
                        }
                    }
                    if (cnt == 0) { missing = true; continue; }
                    m.rgb[k * 3] = r / cnt;
                    m.rgb[k * 3 + 1] = g / cnt;
                    m.rgb[k * 3 + 2] = b / cnt;
                    next[k] = true;
                }
            }
            System.arraycopy(next, 0, ok, 0, ok.length);
            if (!missing) return;
        }
    }

    // ---- Surface geometry ----

    private static Place flat(double z, double nz) {
        return (u, v, p, n) -> {
            set(p, u, v, z);
            set(n, 0, 0, nz);
        };
    }

    /** A shape's top when it is tilted: the texel sits on the plane, and faces along its normal.
     *  Lit as though it were flat, a roof gets the sky as if it pointed straight up and the sun as
     *  if it never turned away from it - the one thing a sloped roof is supposed to show. */
    private static Place topFace(Shape s) {
        if (s.hx == 0 && s.hy == 0) return flat(s.h, 1);
        double len = Math.sqrt(s.hx * s.hx + s.hy * s.hy + 1);
        return (u, v, p, n) -> {
            set(p, u, v, s.topAt(u, v));
            set(n, -s.hx / len, -s.hy / len, 1 / len);
        };
    }

    /** The underside, the same way: on the bottom plane, facing down and away from it. */
    private static Place bottomFace(Shape s) {
        if (s.zx == 0 && s.zy == 0) return flat(s.z0, -1);
        double len = Math.sqrt(s.zx * s.zx + s.zy * s.zy + 1);
        return (u, v, p, n) -> {
            set(p, u, v, s.bottomAt(u, v));
            set(n, s.zx / len, s.zy / len, -1 / len);
        };
    }

    /** A vertical face along polygon edge e: u runs from vertex e to e + 1, v is the height.
     *  The normal points out of the polygon for shapes and into it for regions. */
    private record Face(double ax, double ay, double ex, double ey, double len, double nx, double ny) implements Place {
        public void at(double u, double v, double[] p, double[] n) {
            set(p, ax + ex * u / len, ay + ey * u / len, v);
            set(n, nx, ny, 0);
        }

        Face flipped() { return new Face(ax, ay, ex, ey, len, -nx, -ny); }
    }

    private static Face face(double[] xs, double[] ys, int e, boolean outward) {
        int j = (e + 1) % xs.length;
        double ex = xs[j] - xs[e], ey = ys[j] - ys[e], len = Math.hypot(ex, ey);
        double nx = -ey / len, ny = ex / len;                 // for a thin wall this is face 0
        if (xs.length > 2) {
            double cx = 0, cy = 0;
            for (int i = 0; i < xs.length; i++) { cx += xs[i]; cy += ys[i]; }
            cx /= xs.length;
            cy /= xs.length;
            double mx = xs[e] + ex / 2 - cx, my = ys[e] + ey / 2 - cy;
            if ((nx * mx + ny * my > 0) != outward) { nx = -nx; ny = -ny; }
        }
        return new Face(xs[e], ys[e], ex, ey, len, nx, ny);
    }

    /** Register a surface to be baked, with the colour and material it bounces light as. */
    private LightMap add(List<Job> jobs, LightMap m, Place place, int color, int mat) {
        float[] a = rgb(color, reflect);
        System.arraycopy(a, 0, m.albedo, 0, 3);
        m.mat = mat;
        jobs.add(new Job(m, place, new boolean[m.w * m.h], new float[m.w * m.h * 3], new float[m.w * m.h * 3]));
        return m;
    }

    private static void set(double[] a, double x, double y, double z) {
        a[0] = x;
        a[1] = y;
        a[2] = z;
    }

    private static float[] rgb(int c, double k) {
        return new float[] {(float) (((c >> 16) & 255) / 255.0 * k), (float) (((c >> 8) & 255) / 255.0 * k),
                (float) ((c & 255) / 255.0 * k)};
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String k) {
        return m.get(k) instanceof Map ? (Map<String, Object>) m.get(k) : Map.of();
    }
}

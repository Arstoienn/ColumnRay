package engine;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Surface multipliers applied to the shape's base colour: a procedural brightness, or an image's
 * RGB divided by its own mean. Image filtering stays here; the renderer supplies metres and the
 * footprint of each of its anisotropic samples, just as it does for procedural detail.
 */
final class Materials {
    private Materials() {}

    static final int CONCRETE = 0, PLASTER = 1, BRICK = 2, WOOD = 3, STONE = 4, METAL = 5, TILE = 6,
            WATER = 7, GRASS = 8, LEAF = 9, BOARD = 10, PANEL = 11, TERRACOTTA = 12;

    private static final String[] NAMES = {
        "concrete", "plaster", "brick", "wood", "stone", "metal", "tile",
        "water", "grass", "leaf", "board", "panel", "terracotta"
    };

    /** Load once per world. Each tile owns its mipmaps, so filtering cannot read a neighbour. */
    static Texture[] loadAtlas(Path file, int size, int cols, int count, int[] means) throws IOException {
        if (size <= 0 || cols <= 0 || count <= 0 || means.length != count)
            throw new IllegalArgumentException("textures: size, cols and count must be positive; mean needs count entries");
        BufferedImage atlas = ImageIO.read(file.toFile());
        if (atlas == null) throw new IOException("cannot decode texture atlas: " + file);
        long rows = (count + (long) cols - 1) / cols;
        if (atlas.getWidth() != (long) cols * size || atlas.getHeight() < rows * size
                || atlas.getHeight() % size != 0)
            throw new IOException("texture atlas dimensions do not match size/cols/count: " + file);
        Texture[] tiles = new Texture[count];
        for (int i = 0; i < count; i++) {
            int[] pixels = atlas.getRGB(i % cols * size, i / cols * size, size, size, null, 0, size);
            tiles[i] = new Texture(size, size, pixels, means[i]);
        }
        return tiles;
    }

    /**
     * One whole texture at its own size, for a shape that carries its mesh's texture coordinates.
     * Loaded with a white mean, so a sample is the texel itself (over 255) and the shape's colour is
     * a plain multiplier - not the atlas's "texel over its own average" that keeps a measured
     * average in place. Rows run with v, as in the atlas: row 0 is v = 0.
     */
    static Texture loadImage(Path file) throws IOException {
        BufferedImage im = ImageIO.read(file.toFile());
        if (im == null) throw new IOException("cannot decode image: " + file);
        int w = im.getWidth(), h = im.getHeight();
        return new Texture(w, h, im.getRGB(0, 0, w, h, null, 0, w), 0xffffff);
    }

    /** Immutable after loading; sample output belongs to the renderer's thread, never the tile. */
    static final class Texture {
        private final Level[] levels;
        private final double[] mean = new double[3];

        private Texture(int w, int h, int[] pixels, int color) {
            levels = new Level[32 - Integer.numberOfLeadingZeros(Math.max(w, h))];
            Level first = levels[0] = new Level(w, h);
            for (int c = 0; c < 3; c++) {
                mean[c] = (color >> (16 - 8 * c)) & 255;
                for (int i = 0; i < pixels.length; i++)
                    first.rgb[3 * i + c] = (pixels[i] >> (16 - 8 * c)) & 255;
            }
            // Work in the PNG's sRGB values, the same space as the exporter's tile means and the
            // renderer's base colours. Alpha does not change the engine's existing shape masks.
            for (int i = 1; i < levels.length; i++) levels[i] = levels[i - 1].half();
        }

        /** Wrapped bilinear samples, blended between mip levels. out has six scratch components;
         *  the first three receive the RGB multipliers. w is in repetitions of the tile. */
        private void sample(double u, double v, double w, double[] out) {
            sampleAt(u, v, lod(w), out);
        }

        /** The mip level, fractional, that a footprint of w repetitions of the tile samples. A strip
         *  of anisotropic samples shares one footprint, so it can ask once and sampleAt each. */
        double lod(double w) {
            double footprint = Math.max(1, w * Math.max(levels[0].sx, levels[0].sy));
            return Math.min(levels.length - 1, Math.log(footprint) / Math.log(2));
        }

        /** sampleAt's first component alone, for a texture only ever read as one channel (a blend
         *  material's height): the same arithmetic on that channel, a third of the fetches. */
        double sampleAt0(double u, double v, double lod) {
            u = frac(u);
            v = frac(v);
            int lo = (int) lod, hi = Math.min(lo + 1, levels.length - 1);
            double r = levels[lo].sample0(u, v);
            if (hi != lo) r += (levels[hi].sample0(u, v) - r) * (lod - lo);
            return mean[0] == 0 ? 1 : Math.max(0, Math.min(4, r / mean[0]));
        }

        void sampleAt(double u, double v, double lod, double[] out) {
            u = frac(u);
            v = frac(v);                              // PNG rows increase with the second coordinate
            int lo = (int) lod, hi = Math.min(lo + 1, levels.length - 1);
            levels[lo].sample(u, v, out, 0);
            if (hi != lo) {
                levels[hi].sample(u, v, out, 3);
                for (int c = 0; c < 3; c++) out[c] += (out[c + 3] - out[c]) * (lod - lo);
            }
            for (int c = 0; c < 3; c++)
                out[c] = mean[c] == 0 ? 1 : Math.max(0, Math.min(4, out[c] / mean[c]));
        }
    }

    private static final class Level {
        final int sx, sy;                     // width and height; the atlas's tiles are square
        final float[] rgb;

        Level(int sx, int sy) {
            this.sx = sx;
            this.sy = sy;
            rgb = new float[Math.multiplyExact(Math.multiplyExact(sx, sy), 3)];
        }

        Level half() {
            Level next = new Level(Math.max(1, sx / 2), Math.max(1, sy / 2));
            // Area averaging also keeps every source pixel when a side is odd. A power-of-two
            // tile is the usual 2x2 box filter; neither case quantizes the intermediate means.
            double scaleX = (double) sx / next.sx, scaleY = (double) sy / next.sy;
            for (int y = 0; y < next.sy; y++) {
                double y0 = y * scaleY, y1 = Math.min(sy, (y + 1) * scaleY);
                for (int x = 0; x < next.sx; x++) {
                    double x0 = x * scaleX, x1 = Math.min(sx, (x + 1) * scaleX);
                    for (int c = 0; c < 3; c++) {
                        double sum = 0;
                        for (int py = (int) y0; py < Math.ceil(y1); py++)
                            for (int px = (int) x0; px < Math.ceil(x1); px++) {
                                double area = (Math.min(px + 1, x1) - Math.max(px, x0))
                                        * (Math.min(py + 1, y1) - Math.max(py, y0));
                                sum += rgb[3 * (py * sx + px) + c] * area;
                            }
                        next.rgb[3 * (y * next.sx + x) + c] = (float) (sum / (scaleX * scaleY));
                    }
                }
            }
            return next;
        }

        double sample0(double u, double v) {
            double x = u * sx - 0.5, y = v * sy - 0.5;
            int ix = fl(x), iy = fl(y);
            double fx = x - ix, fy = y - iy;
            int x0 = Math.floorMod(ix, sx), y0 = Math.floorMod(iy, sy);
            int x1 = (x0 + 1) % sx, y1 = (y0 + 1) % sy;
            int a = 3 * (y0 * sx + x0), b = 3 * (y0 * sx + x1);
            int c = 3 * (y1 * sx + x0), d = 3 * (y1 * sx + x1);
            double top = rgb[a] + (rgb[b] - rgb[a]) * fx;
            double bottom = rgb[c] + (rgb[d] - rgb[c]) * fx;
            return top + (bottom - top) * fy;
        }

        void sample(double u, double v, double[] out, int offset) {
            double x = u * sx - 0.5, y = v * sy - 0.5;
            int ix = fl(x), iy = fl(y);
            double fx = x - ix, fy = y - iy;
            int x0 = Math.floorMod(ix, sx), y0 = Math.floorMod(iy, sy);
            int x1 = (x0 + 1) % sx, y1 = (y0 + 1) % sy;
            int a = 3 * (y0 * sx + x0), b = 3 * (y0 * sx + x1);
            int c = 3 * (y1 * sx + x0), d = 3 * (y1 * sx + x1);
            for (int k = 0; k < 3; k++) {
                double top = rgb[a + k] + (rgb[b + k] - rgb[a + k]) * fx;
                double bottom = rgb[c + k] + (rgb[d + k] - rgb[c + k]) * fx;
                out[offset + k] = top + (bottom - top) * fy;
            }
        }
    }

    /** A mesh's own coordinates: u = a p + b q + c, v = d p + e q + f. w is metres, and becomes
     *  repetitions of the image by how much of it one metre covers (the root of the map's area scale). */
    static void mapped(Texture tex, double[] uv, double p, double q, double w, double[] out) {
        mappedAt(tex, uv, p, q, mappedLod(tex, uv, w), out);
    }

    /** The mip level mapped() would pick for footprint w, to hand to mappedAt(). */
    static double mappedLod(Texture tex, double[] uv, double w) {
        double scale = Math.sqrt(Math.abs(uv[0] * uv[4] - uv[1] * uv[3]));
        return tex.lod(w * scale);
    }

    static void mappedAt(Texture tex, double[] uv, double p, double q, double lod, double[] out) {
        tex.sampleAt(uv[0] * p + uv[1] * q + uv[2], uv[3] * p + uv[4] * q + uv[5], lod, out);
    }

    /** mappedAt's first component alone. */
    static double mappedAt0(Texture tex, double[] uv, double p, double q, double lod) {
        return tex.sampleAt0(uv[0] * p + uv[1] * q + uv[2], uv[3] * p + uv[4] * q + uv[5], lod);
    }

    /**
     * How much of a blend material's second layer shows, 0 to 1 - VALORANT_Blend's own sums, so a
     * path of tiles gives way to grass where Riot painted it to (the map converter derives them).
     * va is the mesh's vertex alpha there, h the height kept in layer A's DF alpha, vb the
     * material's Vertex Blend and invert its Invert Alpha. The steep ramp (vb is 5 to 17) turns a
     * soft painted alpha into a crisp edge that follows the height of the tiles.
     */
    static double blend(double h, double va, double vb, boolean invert) {
        double a = Math.pow(Math.max(0, Math.min(1, va)), 2.2);
        if (vb < 0) a = 1 - a;
        double hp = invert ? 1 - h : h, dodge;
        if (a == 0) {
            dodge = 0;
        } else {
            double room = 1 - hp;                              // Blender's colour dodge
            dodge = room <= 0 ? 1 : Math.min(1, a / room);
        }
        double m = -Math.abs(vb) / 2;
        return Math.max(0, Math.min(1, m + dodge * (1 - m)));
    }

    /** Image counterparts use the very same metres and pixel footprint as the procedural path. */
    static void side(Texture tex, double ts, double u, double v, double w, double[] out) {
        tex.sample(u / ts, v / ts, w / ts, out);
    }

    static void flat(Texture tex, double ts, double x, double y, double w, double[] out) {
        tex.sample(x / ts, y / ts, w / ts, out);
    }

    // ---- Masks ----

    /**
     * A masked surface is a flat quad that is mostly holes: a crown of leaves, a clump of ferns, a
     * tuft of grass. Nothing in nature is a prism, and this is how a raycaster has drawn trees since
     * Doom - not as geometry but as a shape cut out of a flat surface, so what you see through the
     * gaps is whatever is behind it. {@link #mask} says how much of the surface is really there at
     * a point, 0 for a hole and 1 for solid; the renderer blends by it and the shadow rays are
     * stopped by it, which is where dappled light on the ground comes from.
     *
     * The coordinates are across the quad, 0 to 1 both ways, so one mask fits any size of tree.
     */
    static final int CANOPY = 0, FERN = 1, TUFT = 2;

    private static final String[] MASK_NAMES = {"canopy", "fern", "tuft"};

    static int maskId(String name) {
        for (int i = 0; i < MASK_NAMES.length; i++) if (MASK_NAMES[i].equals(name)) return i;
        throw new IllegalArgumentException("unknown mask: " + name);
    }

    /**
     * How much of a masked surface is really there at (s, t), both 0 to 1 across the quad. w is how
     * wide one pixel is in those same units, so that a distant crown loses its gaps before it loses
     * its outline - fading the silhouette too would turn a far-off tree back into a rectangle.
     */
    static double mask(int kind, double s, double t, double w) {
        switch (kind) {
            case CANOPY -> {
                // An outline that is roughly round, pushed in and out by two sizes of clump, and
                // then eaten into by holes - the sky you can see through a tree.
                double dx = (s - 0.5) * 2.1, dy = (t - 0.54) * 2.15;
                double r = Math.sqrt(dx * dx + dy * dy);
                double lobes = 0.40 * (noise(s, t, 2.7, w) - 0.5) + 0.26 * (noise(s + 5, t + 2, 7.0, w) - 0.5);
                double a = step(0, 0.09, 1 + lobes - r);
                double holes = noise(s + 31, t + 17, 15.0, w) + 0.30 * (1 - r);
                return a * step(0.30, 0.52, holes);
            }
            case FERN -> {
                double dx = (s - 0.5) * 2.3, dy = (t - 0.35) * 1.9;     // wide, low, sitting on the ground
                double r = Math.sqrt(dx * dx + dy * dy);
                double fronds = 0.45 * (noise(s + 7, t + 13, 5.0, w) - 0.5);
                double a = step(0, 0.14, 1 + fronds - r);
                return a * step(0.34, 0.56, noise(s + 3, t + 23, 11.0, w) + 0.22 * (1 - r));
            }
            case TUFT -> {
                // Blades: dense at the bottom, thinning to nothing at the tips.
                double blades = noise(s + 19, t * 0.25 + 41, 26.0, w);
                return step(0.34, 0.5, blades + 0.55 * (1 - t) - 0.22);
            }
            default -> { return 1; }
        }
    }

    /** Smoothstep. */
    private static double step(double a, double b, double x) {
        double k = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return k * k * (3 - 2 * k);
    }

    static int id(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        throw new IllegalArgumentException("unknown material: " + name);
    }

    /**
     * Vertical faces: u runs along the surface (metres), v is the height z, w is how wide one pixel
     * is on this surface (metres) - see {@link #keep}.
     */
    static double side(int m, double u, double v, double w) {
        switch (m) {
            case CONCRETE -> {
                double grain = 0.88 + 0.08 * noise(u, v, 2, w) + 0.04 * noise(u, v, 7, w);
                return lines(frac(u / 1.2) < 0.02 || frac(v / 0.6) < 0.03, 0.8, grain, 0.05, 0.018, w);
            }
            case PLASTER -> {
                if (v < 0.12) return 0.6;                                   // skirting board
                return 0.93 + 0.05 * noise(u, v, 4, w);
            }
            case BRICK -> {
                int row = fl(v * 4);
                double bu = u * 2 + (row & 1) * 0.5;
                double brick = 0.85 + 0.15 * cell(hash(fl(bu), row), 0.25, w);
                return lines(frac(bu) < 0.05 || frac(v * 4) < 0.1, 0.6, brick, 0.145, 0.025, w);
            }
            case WOOD -> {
                int plank = fl(v * 6);
                double grain = 0.8 + 0.2 * cell(noise(u * 1.5 + plank * 0.37, plank + 0.5), 0.67, w);
                return lines(frac(v * 6) < 0.08, 0.55, grain, 0.08, 0.013, w);
            }
            case STONE -> {
                int row = fl(v * 2.5);
                double bu = u * 1.5 + row * 0.5;
                double block = 0.8 + 0.2 * cell(hash(fl(bu), row), 0.4, w);
                return lines(frac(bu) < 0.04 || frac(v * 2.5) < 0.06, 0.65, block, 0.098, 0.024, w);
            }
            case METAL -> {
                double ribs = 0.8 + 0.15 * fade(Math.abs(Math.sin(u * 9)), 0.64, w, 0.35);
                return lines(frac(v * 8) < 0.06 && v > 1.4 && v < 1.8, 0.5, ribs, 0.06, 0.0075, w);
            }
            case LEAF -> { return 0.55 + 0.3 * noise(u, v, 6, w) + 0.15 * noise(u, v, 19, w); }
            case BOARD -> { return 0.9 + 0.1 * noise(u, v, 12, w); }
            case TERRACOTTA -> {
                double clay = 0.9 + 0.1 * cell(hash(fl(u * 5), fl(v * 4)), 0.2, w);
                return lines(frac(v * 4) < 0.08, 0.7, clay, 0.08, 0.02, w);
            }
            case TILE -> { return lines(frac(u * 3) < 0.05 || frac(v * 3) < 0.05, 0.7, 0.95, 0.098, 0.017, w); }
            default -> { return 1; }
        }
    }

    /** Horizontal faces (floors, ceilings, box tops and bottoms): indexed by world coordinates. */
    static double flat(int m, double x, double y, double w) {
        switch (m) {
            case TILE -> {
                double face = fade(((fl(x * 2) + fl(y * 2)) & 1) == 0 ? 1.0 : 0.86, 0.93, w, 0.5);
                return lines(frac(x * 2) < 0.05 || frac(y * 2) < 0.05, 0.6, face, 0.098, 0.025, w);
            }
            case WOOD -> {
                int row = fl(y * 6);
                double grain = 0.8 + 0.2 * cell(noise(x * 0.7 + row * 0.37, row + 0.5), 1.4, w);
                return lines(frac(y * 6) < 0.07, 0.55, grain, 0.07, 0.012, w);
            }
            case PANEL -> {
                double face = emissive(m, x, y) ? 1.35 : 0.95;               // light panel
                return lines(frac(x / PANEL_CELL) < 0.04 || frac(y / PANEL_CELL) < 0.04, 0.7, face, 0.078, 0.024, w);
            }
            case STONE -> {
                double block = 0.8 + 0.2 * cell(hash(fl(x / 0.8), fl(y / 0.8)), 0.8, w);
                return lines(frac(x / 0.8) < 0.04 || frac(y / 0.8) < 0.04, 0.65, block, 0.078, 0.032, w);
            }
            case GRASS -> { return 0.62 + 0.28 * noise(x, y, 4, w) + 0.14 * noise(x, y, 15, w); }
            case WATER -> { return 0.85 + 0.15 * fade(Math.sin(x * 3.1 + Math.sin(y * 2.3) * 2), 0, w, 1.0); }
            case LEAF -> { return 0.5 + 0.34 * noise(x, y, 5, w) + 0.16 * noise(x, y, 17, w); }
            case CONCRETE -> { return 0.85 + 0.07 * noise(x, y, 2, w) + 0.04 * noise(x, y, 7, w); }
            default -> { return side(m, x, y, w); }
        }
    }

    /**
     * The largest detail of each material, on a side and on a flat, in metres. keep() fades a
     * detail out completely once a pixel is as wide as it, so past the largest one the material is
     * the same number wherever it is sampled - and the renderer can stop sampling it eight times over
     * to average eight copies of that number. NaN where something about the material depends on
     * where it is and never fades: plaster's skirting board and the lit ceiling panels. 0 for the
     * ones that have no detail at all.
     *
     * These have to match the feature sizes written in side() and flat(); a larger value only costs
     * speed, a smaller one would change the picture, and the --shot hashes would say so.
     */
    private static final double[] SIDE_DETAIL = new double[NAMES.length], FLAT_DETAIL = new double[NAMES.length];
    private static final double[] SIDE_MEAN = new double[NAMES.length], FLAT_MEAN = new double[NAMES.length];

    static {
        SIDE_DETAIL[CONCRETE] = 1 / 2.0;        // noise(.., 2, w)
        SIDE_DETAIL[PLASTER] = Double.NaN;      // the skirting board is below 0.12 m, whatever the width
        SIDE_DETAIL[BRICK] = 0.25;              // cell(.., 0.25, w)
        SIDE_DETAIL[WOOD] = 0.67;               // cell(.., 0.67, w)
        SIDE_DETAIL[STONE] = 0.4;               // cell(.., 0.4, w)
        SIDE_DETAIL[METAL] = 0.35;              // fade(.., 0.64, w, 0.35)
        SIDE_DETAIL[TILE] = 0.017;              // lines(.., 0.017, w)
        SIDE_DETAIL[LEAF] = 1 / 6.0;            // noise(.., 6, w)
        SIDE_DETAIL[BOARD] = 1 / 12.0;          // noise(.., 12, w)
        SIDE_DETAIL[TERRACOTTA] = 0.2;          // cell(.., 0.2, w)
        // WATER, GRASS and PANEL have no side detail: side() returns 1.

        FLAT_DETAIL[TILE] = 0.5;                // fade(.., 0.93, w, 0.5)
        FLAT_DETAIL[WOOD] = 1.4;                // cell(.., 1.4, w)
        FLAT_DETAIL[PANEL] = Double.NaN;        // which cells are lit
        FLAT_DETAIL[STONE] = 0.8;               // cell(.., 0.8, w)
        FLAT_DETAIL[GRASS] = 1 / 4.0;           // noise(.., 4, w)
        FLAT_DETAIL[WATER] = 1.0;               // fade(.., 0, w, 1.0)
        FLAT_DETAIL[LEAF] = 1 / 5.0;            // noise(.., 5, w)
        FLAT_DETAIL[CONCRETE] = 1 / 2.0;        // noise(.., 2, w)
        for (int m : new int[] {PLASTER, BRICK, METAL, BOARD, TERRACOTTA}) FLAT_DETAIL[m] = SIDE_DETAIL[m];  // flat() falls back to side()

        for (int m = 0; m < NAMES.length; m++) {
            SIDE_MEAN[m] = Double.isNaN(SIDE_DETAIL[m]) ? Double.NaN : side(m, 0, 0.5, 1e6);
            FLAT_MEAN[m] = Double.isNaN(FLAT_DETAIL[m]) ? Double.NaN : flat(m, 0, 0, 1e6);
        }
    }

    /** Has every detail of material m's side faded at a pixel this wide? The same test keep() makes. */
    static boolean sideFaded(int m, double w) {
        double f = SIDE_DETAIL[m];
        return f == 0 || 2 * w / f >= 2;
    }

    static boolean flatFaded(int m, double w) {
        double f = FLAT_DETAIL[m];
        return f == 0 || 2 * w / f >= 2;
    }

    static double sideMean(int m) { return SIDE_MEAN[m]; }

    static double flatMean(int m) { return FLAT_MEAN[m]; }

    /** Ceiling panels are a grid of PANEL_CELL squares; one cell in every PANEL_EVERY_X by
     *  PANEL_EVERY_Y block is a light. Lighting turns the same cells into light sources. */
    static final double PANEL_CELL = 0.6;
    static final int PANEL_EVERY_X = 4, PANEL_EVERY_Y = 3;

    /** Does this point of a surface give off light of its own (a lit ceiling panel)? */
    static boolean emissive(int m, double x, double y) {
        if (m != PANEL || frac(x / PANEL_CELL) < 0.04 || frac(y / PANEL_CELL) < 0.04) return false;
        return Math.floorMod(fl(x / PANEL_CELL), PANEL_EVERY_X) == 1 && Math.floorMod(fl(y / PANEL_CELL), PANEL_EVERY_Y) == 1;
    }

    static double frac(double v) { return v - Math.floor(v); }

    static int fl(double v) { return (int) Math.floor(v); }

    /**
     * How much of a detail this size survives a pixel this wide.
     *
     * A detail needs about two pixels across it to show at all. Past that it is averaging itself
     * out, and a point sample that draws it anyway just picks one side of it at random - which is
     * why a distant brick wall crawls and a distant tiled floor turns into moire. Drawing the
     * average instead is what a mipmap does when it picks a level; there is no pyramid to pick from
     * here, because the texture is a function rather than an image, so work it out from the numbers.
     *
     * w comes from the renderer: the width of one pixel on that surface, which grows with distance
     * and, on a floor, with how near the horizon the row is. It also shrinks when the ray count goes
     * up, so casting more rays really does buy back distant detail instead of just anti-aliasing it.
     */
    private static double keep(double w, double feature) {
        double k = 2 * w / feature;
        return k <= 1 ? 1 : k >= 2 ? 0 : 2 - k;
    }

    /** A detail faded into the mean it averages out to, once a pixel is wider than it is. */
    private static double fade(double detail, double mean, double w, double feature) {
        double k = keep(w, feature);
        return k >= 1 ? detail : mean + (detail - mean) * k;
    }

    /** Value noise of a given number of cells per metre, flattening out when the cells get small. */
    private static double noise(double u, double v, double cells, double w) {
        return fade(noise(u * cells, v * cells), 0.5, w, 1 / cells);
    }

    /** One cell's own shade - a brick, a plank, a paving stone - fading to the average of them. */
    private static double cell(double h, double size, double w) {
        return fade(h, 0.5, w, size);
    }

    /**
     * Lines drawn over a surface: mortar, grout, panel joints, the gaps between planks. `dark` is
     * the line, `base` the surface between them, `cover` how much of the area the lines take and
     * `width` the thinnest of them. Thin lines are the first thing to go: they are the detail that
     * aliases worst, and a wall whose mortar has averaged in still looks like a wall.
     */
    private static double lines(boolean in, double dark, double base, double cover, double width, double w) {
        return fade(in ? dark : base, cover * dark + (1 - cover) * base, w, width);
    }

    /**
     * Smooth value noise in [0, 1]: the same hash, but read off the four corners of the cell and
     * interpolated across it instead of held flat. A flat cell makes a surface a grid of squares,
     * which is what plaster, concrete, grass and leaves looked like; brick, stone and planks keep
     * the flat version on purpose, because there each cell really is one object.
     */
    static double noise(double u, double v) {
        int i = fl(u), j = fl(v);
        double fu = ease(u - i), fv = ease(v - j);
        double a = hash(i, j) + (hash(i + 1, j) - hash(i, j)) * fu;
        double b = hash(i, j + 1) + (hash(i + 1, j + 1) - hash(i, j + 1)) * fu;
        return a + (b - a) * fv;
    }

    private static double ease(double t) { return t * t * (3 - 2 * t); }

    static double hash(int i, int j) {
        int h = i * 73856093 ^ j * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 1023) / 1023.0;
    }
}

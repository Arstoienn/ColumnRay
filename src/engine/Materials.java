package engine;

/**
 * Procedural textures: each returns a brightness multiplier (roughly 0.5-1.35) that is applied
 * to the shape's base colour. Swapping in image textures and normal maps only touches this file.
 */
final class Materials {
    private Materials() {}

    static final int CONCRETE = 0, PLASTER = 1, BRICK = 2, WOOD = 3, STONE = 4, METAL = 5, TILE = 6,
            WATER = 7, GRASS = 8, LEAF = 9, BOARD = 10, PANEL = 11, TERRACOTTA = 12;

    private static final String[] NAMES = {
        "concrete", "plaster", "brick", "wood", "stone", "metal", "tile",
        "water", "grass", "leaf", "board", "panel", "terracotta"
    };

    static int id(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        throw new IllegalArgumentException("unknown material: " + name);
    }

    /** Vertical faces: u runs along the surface (metres), v is the height z. */
    static double side(int m, double u, double v) {
        switch (m) {
            case CONCRETE -> {
                if (frac(u / 1.2) < 0.02 || frac(v / 0.6) < 0.03) return 0.8;
                return 0.88 + 0.1 * hash(fl(u * 3), fl(v * 3));
            }
            case PLASTER -> {
                if (v < 0.12) return 0.6;                                   // skirting board
                return 0.93 + 0.05 * hash(fl(u * 6), fl(v * 6));
            }
            case BRICK -> {
                int row = fl(v * 4);
                double bu = u * 2 + (row & 1) * 0.5;
                if (frac(bu) < 0.05 || frac(v * 4) < 0.1) return 0.6;
                return 0.85 + 0.15 * hash(fl(bu), row);
            }
            case WOOD -> {
                int plank = fl(v * 6);
                if (frac(v * 6) < 0.08) return 0.55;
                return 0.8 + 0.2 * hash(plank, fl(u * 1.5 + plank * 0.37));
            }
            case STONE -> {
                int row = fl(v * 2.5);
                double bu = u * 1.5 + row * 0.5;
                if (frac(bu) < 0.04 || frac(v * 2.5) < 0.06) return 0.65;
                return 0.8 + 0.2 * hash(fl(bu), row);
            }
            case METAL -> {
                if (frac(v * 8) < 0.06 && v > 1.4 && v < 1.8) return 0.5;   // vent slots
                return 0.8 + 0.15 * Math.abs(Math.sin(u * 9));
            }
            case LEAF -> { return 0.55 + 0.45 * hash(fl(u * 9), fl(v * 9)); }
            case BOARD -> { return 0.9 + 0.1 * hash(fl(u * 20), fl(v * 20)); }
            case TERRACOTTA -> {
                if (frac(v * 4) < 0.08) return 0.7;
                return 0.9 + 0.1 * hash(fl(u * 5), fl(v * 4));
            }
            case TILE -> {
                if (frac(u * 3) < 0.05 || frac(v * 3) < 0.05) return 0.7;
                return 0.95;
            }
            default -> { return 1; }
        }
    }

    /** Horizontal faces (floors, ceilings, box tops and bottoms): indexed by world coordinates. */
    static double flat(int m, double x, double y) {
        switch (m) {
            case TILE -> {
                if (frac(x * 2) < 0.05 || frac(y * 2) < 0.05) return 0.6;
                return ((fl(x * 2) + fl(y * 2)) & 1) == 0 ? 1.0 : 0.86;
            }
            case WOOD -> {
                int row = fl(y * 6);
                if (frac(y * 6) < 0.07) return 0.55;
                return 0.8 + 0.2 * hash(row, fl(x * 0.7 + row * 0.37));
            }
            case PANEL -> {
                int ix = fl(x / 0.6), iy = fl(y / 0.6);
                if (frac(x / 0.6) < 0.04 || frac(y / 0.6) < 0.04) return 0.7;
                if (Math.floorMod(ix, 4) == 1 && Math.floorMod(iy, 3) == 1) return 1.35;  // light panel
                return 0.95;
            }
            case STONE -> {
                if (frac(x / 0.8) < 0.04 || frac(y / 0.8) < 0.04) return 0.65;
                return 0.8 + 0.2 * hash(fl(x / 0.8), fl(y / 0.8));
            }
            case GRASS -> { return 0.65 + 0.35 * hash(fl(x * 10), fl(y * 10)); }
            case WATER -> { return 0.85 + 0.15 * Math.sin(x * 3.1 + Math.sin(y * 2.3) * 2); }
            case LEAF -> { return 0.5 + 0.5 * hash(fl(x * 8), fl(y * 8)); }
            case CONCRETE -> { return 0.85 + 0.1 * hash(fl(x * 3), fl(y * 3)); }
            default -> { return side(m, x, y); }
        }
    }

    static double frac(double v) { return v - Math.floor(v); }

    static int fl(double v) { return (int) Math.floor(v); }

    static double hash(int i, int j) {
        int h = i * 73856093 ^ j * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 1023) / 1023.0;
    }
}

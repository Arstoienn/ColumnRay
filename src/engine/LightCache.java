package engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Baked lightmaps kept on disk, so a map is baked once rather than on every launch.
 *
 * Haven's bake is a quarter of an hour, and it came out the same every time: the bake is a pure
 * function of the map's files, the -Dlight.* settings and the code that does the baking. So the
 * cache is keyed by a hash of exactly those - every file the map was read from, the texel size, and
 * the compiled bytes of each class the bake runs through - and anything that could change a texel
 * changes the key. A key that does not match is simply a different file; nothing is ever patched.
 *
 * The file holds every surface's texels in the order the bake lays its surfaces out, each with its
 * width and height, and the ray count so the log still says what the light cost. A file whose
 * layout does not line up with the world is not trusted: the bake runs as if there were none.
 *
 * -Dlight.cache=false turns it off; -Dlight.cache.dir picks the folder (default .lightcache).
 */
final class LightCache {
    private static final int MAGIC = 0x52434c43;                 // "RCLC"
    private static final int VERSION = 1;
    /** The classes whose code decides a texel: the bake, its rays, the geometry, parsing, masks. */
    private static final String[] BAKE_CLASSES = {"Lighting", "Occluder", "World", "Geometry", "Materials", "Json"};

    private LightCache() {}

    static boolean enabled() {
        return !"false".equals(System.getProperty("light.cache"));
    }

    /** The key for this world's bake, or null when it cannot be worked out (then nothing is cached). */
    static byte[] key(World w) {
        if (!enabled() || w.sources.isEmpty()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(("lightcache " + VERSION + "\n").getBytes());
            md.update(("light.texel=" + System.getProperty("light.texel", "") + "\n").getBytes());
            for (Path p : w.sources) md.update(Files.readAllBytes(p));
            Path dir = classDirectory();
            if (dir == null) return null;
            List<Path> classes;
            try (Stream<Path> files = Files.list(dir)) {
                classes = files.filter(f -> {
                    String n = f.getFileName().toString();
                    if (!n.endsWith(".class")) return false;
                    for (String c : BAKE_CLASSES)
                        if (n.equals(c + ".class") || n.startsWith(c + "$")) return true;
                    return false;
                }).sorted().toList();
            }
            if (classes.isEmpty()) return null;
            for (Path c : classes) {
                md.update(c.getFileName().toString().getBytes());
                md.update(Files.readAllBytes(c));
            }
            return md.digest();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** Where the engine's own .class files are, or null when they are not loose files (a jar). */
    private static Path classDirectory() {
        try {
            Path root = Path.of(LightCache.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = root.resolve("engine");
            return Files.isDirectory(dir) ? dir : null;
        } catch (URISyntaxException | SecurityException | NullPointerException | IllegalArgumentException e) {
            return null;
        }
    }

    static Path file(World w, byte[] key) {
        String dir = System.getProperty("light.cache.dir", ".lightcache");
        String stem = w.sources.get(0).getFileName().toString().replaceAll("\\.json$", "").replaceAll("[^A-Za-z0-9_-]", "_");
        return Path.of(dir, stem + "-" + HexFormat.of().formatHex(key, 0, 8) + ".bin");
    }

    /** Fill every map's texels from the cache. False, and nothing usable written, if it is absent or does not fit. */
    static boolean load(Path file, byte[] key, List<Lighting.LightMap> maps, long[] rays) {
        if (!Files.isRegularFile(file)) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new InflaterInputStream(Files.newInputStream(file)), 1 << 20))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return false;
            byte[] k = new byte[key.length];
            in.readFully(k);
            if (!MessageDigest.isEqual(k, key)) return false;
            long r = in.readLong();
            if (in.readInt() != maps.size()) return false;
            byte[] buf = new byte[0];
            for (Lighting.LightMap m : maps) {
                if (in.readInt() != m.w || in.readInt() != m.h) return false;
                int n = m.rgb.length * Float.BYTES;
                if (buf.length < n) buf = new byte[n];
                in.readFully(buf, 0, n);
                ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(m.rgb);
            }
            rays[0] = r;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Write the finished bake. A failure only costs the next launch a bake; it is reported, not thrown. */
    static void save(Path file, byte[] key, List<Lighting.LightMap> maps, long rays) {
        Path tmp = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "bake", ".tmp");
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new DeflaterOutputStream(Files.newOutputStream(tmp), deflater, 1 << 20), 1 << 20))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.write(key);
                out.writeLong(rays);
                out.writeInt(maps.size());
                byte[] buf = new byte[0];
                for (Lighting.LightMap m : maps) {
                    out.writeInt(m.w);
                    out.writeInt(m.h);
                    int n = m.rgb.length * Float.BYTES;
                    if (buf.length < n) buf = new byte[n];
                    ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(m.rgb);
                    out.write(buf, 0, n);
                }
            } finally {
                deflater.end();
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.out.println("light cache: could not write " + file + " (" + e.getMessage() + ")");
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }
}

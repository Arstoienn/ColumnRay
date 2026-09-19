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
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
 * Which classes those are is decided by two lists, BAKES and BAKES_NOT, and every class in the
 * engine must be in one of them: a class in neither turns the cache off and says so. Getting that
 * backwards is how a cache like this goes quietly wrong, and it fails in the direction that costs
 * a bake rather than the one that hands back light from before the change you are measuring.
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
    private static final Set<String> BAKES = Set.of(
            "Lighting", "Occluder", "World", "Geometry", "Materials", "Json");

    /**
     * The classes that deliberately do not, each one a decision that a change to it cannot move a
     * texel: they run after the bake, or only draw, or only read the keyboard.
     *
     * Listing these is the point. A hand-kept list of what matters fails silently - split a piece
     * of the bake into a class of its own, forget to add it, and every cached file stays valid
     * while the light it holds is wrong, with nothing said. A list of what does not matter fails
     * loudly instead: a class in neither list is one nobody has classified, so the cache turns
     * itself off and says which class to classify. The cost of forgetting is then a bake, which is
     * merely slow, rather than wrong light, which is invisible.
     */
    private static final Set<String> BAKES_NOT = Set.of(
            "Main", "Options", "Renderer", "Warp", "Player", "Hud", "Capture",
            "Minimap", "RayView", "DynamicResolution", "Keys", "LightCache", "Hash", "GpuSpike", "Gl", "GlMaterials", "GpuMatCheck", "GpuSpans", "GpuWalls", "GpuCheck");

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
            SortedMap<String, byte[]> classes = bakeClasses();
            if (classes == null || classes.isEmpty()) return null;
            for (Map.Entry<String, byte[]> c : classes.entrySet()) {
                md.update(c.getKey().getBytes(StandardCharsets.UTF_8));
                md.update(c.getValue());
            }
            return md.digest();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * The compiled bytes of every class the bake runs through, by name, or null when they cannot
     * all be accounted for - which turns the cache off rather than keying it on a guess.
     *
     * Both ways the engine is run are handled: loose class files under {@code out/}, and entries
     * of a jar. The jar case used to return null and take the cache with it; nothing depended on
     * it because nothing packages the engine yet, which is exactly how a distribution ends up
     * shipping with a feature quietly missing.
     */
    private static SortedMap<String, byte[]> bakeClasses() throws IOException {
        Path root = codeSource();
        if (root == null) return off("the engine's own location cannot be read");
        SortedMap<String, byte[]> out = new TreeMap<>();
        if (Files.isDirectory(root)) {
            Path dir = root.resolve("engine");
            if (!Files.isDirectory(dir)) return off("no engine/ directory under " + root);
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    String name = f.getFileName().toString();
                    Boolean bakes = bakes(name);
                    if (bakes == null) return unclassified(name);
                    if (bakes) out.put(name, Files.readAllBytes(f));
                }
            }
        } else if (Files.isRegularFile(root)) {
            try (JarFile jar = new JarFile(root.toFile())) {
                for (JarEntry e : java.util.Collections.list(jar.entries())) {
                    String n = e.getName();
                    if (!n.startsWith("engine/") || n.indexOf('/', "engine/".length()) >= 0) continue;
                    String name = n.substring("engine/".length());
                    Boolean bakes = bakes(name);
                    if (bakes == null) return unclassified(name);
                    if (bakes) try (var in = jar.getInputStream(e)) { out.put(name, in.readAllBytes()); }
                }
            }
        } else {
            return off(root + " is neither a directory nor a jar");
        }
        return out;
    }

    /** True when this class file's code can move a texel, false when it is known not to, and null
     *  when nobody has said - a new class that the lists have not caught up with. */
    static Boolean bakes(String classFile) {
        if (!classFile.endsWith(".class")) return Boolean.FALSE;
        String name = classFile.substring(0, classFile.length() - ".class".length());
        int nested = name.indexOf('$');                          // Lighting$Job belongs to Lighting
        if (nested >= 0) name = name.substring(0, nested);
        if (BAKES.contains(name)) return Boolean.TRUE;
        if (BAKES_NOT.contains(name)) return Boolean.FALSE;
        return null;
    }

    private static SortedMap<String, byte[]> unclassified(String classFile) {
        return off(classFile + " is in neither LightCache.BAKES nor LightCache.BAKES_NOT."
                + " Say which it is: a class the bake runs through belongs in BAKES, or every cached"
                + " bake from before it existed stays valid with the wrong light in it");
    }

    private static SortedMap<String, byte[]> off(String why) {
        System.out.println("light cache: off, because " + why);
        return null;
    }

    /** Where the engine was loaded from: a directory of class files, or a jar. */
    private static Path codeSource() {
        try {
            return Path.of(LightCache.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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

    /** Temporary files from a write that was interrupted, left behind for over a day. */
    private static void sweep(Path dir) {
        long old = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String n = f.getFileName().toString();
                if (n.startsWith("bake") && n.endsWith(".tmp")
                        && Files.getLastModifiedTime(f).toMillis() < old) Files.deleteIfExists(f);
            }
        } catch (IOException tidyingIsOptional) {
            // Nothing here is worth failing a bake over; the files are a few megabytes at worst.
        }
    }

    /** Write the finished bake. A failure only costs the next launch a bake; it is reported, not thrown. */
    static void save(Path file, byte[] key, List<Lighting.LightMap> maps, long rays) {
        Path tmp = null;
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            sweep(dir);
            tmp = Files.createTempFile(dir, "bake", ".tmp");
            // A write that never finishes - Ctrl-C, a full disk, the power - leaves the real file
            // untouched, because it is only ever replaced by a whole one. What it does leave is
            // this temporary file, so it goes at exit too, and sweep() clears up after the times
            // that was not reached either.
            tmp.toFile().deleteOnExit();
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

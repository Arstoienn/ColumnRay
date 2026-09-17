package engine;

/** The digest the golden test is built on. If two different frames could hash the same, the
 *  golden test would pass through exactly the change it exists to catch. */
final class HashTest {
    private HashTest() {}

    static void run() {
        Check.group("Hash");

        Check.eq(Hash.of().add(new int[] {1, 2, 3}, 3).hex(), Hash.of().add(new int[] {1, 2, 3}, 3).hex(),
                "the same pixels hash the same");
        Check.that(!Hash.of().add(new int[] {1, 2, 3}, 3).hex().equals(Hash.of().add(new int[] {1, 3, 2}, 3).hex()),
                "the order of the pixels is part of the picture");
        Check.that(!Hash.of().add(new int[] {1, 2, 3}, 3).hex().equals(Hash.of().add(new int[] {1, 2, 3}, 2).hex()),
                "only the first n are read");

        Check.that(!Hash.of().add(new float[] {0.0f}, 1).hex().equals(Hash.of().add(new float[] {-0.0f}, 1).hex()),
                "raw bits: -0.0 is not 0.0");
        Check.that(!Hash.of().add(new float[] {1f, 1f}, 2).hex().equals(Hash.of().add(new float[] {1f, 1.0000001f}, 2).hex()),
                "a depth that changed in the last place still shows");

        // A digest whose fields ran together would let a 2x3 lightmap and a 3x2 one agree.
        Check.that(!Hash.of().add(2).add(3).hex().equals(Hash.of().add(3).add(2).hex()),
                "the shape of a map is part of its digest");

        Check.eq(Hash.of().add(new int[0], 0).hex().length(), 16, "sixteen hex characters");
    }
}

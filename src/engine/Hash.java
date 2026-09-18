package engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable digests of what a frame and a bake came out as, so "nothing changed" can be checked
 * rather than believed.
 *
 * The engine's own arrays are hashed, not the PNG and PFM files written from them: an image
 * encoder is free to pick a different filter or compression level between JDK releases without
 * a single pixel changing, and a golden test that fails for that reason teaches people to ignore
 * it. Floats go in by their raw bits, so a texel that changed in the last place still shows.
 *
 * Only the first eight bytes are printed. A frame is compared against a value in the repository,
 * not against an attacker, and sixteen hex characters fit on a line next to the view's name.
 */
final class Hash {
    private final MessageDigest md;
    private byte[] buf = new byte[0];

    private Hash() {
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JDK", e);
        }
    }

    static Hash of() {
        return new Hash();
    }

    Hash add(int v) {
        md.update(new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v});
        return this;
    }

    Hash add(long v) {
        return add((int) (v >>> 32)).add((int) v);
    }

    Hash add(String s) {
        md.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return this;
    }

    /** {@code n} pixels, or any other ints whose order is part of what is being compared. */
    Hash add(int[] a, int n) {
        ByteBuffer b = buffer(n * Integer.BYTES);
        b.asIntBuffer().put(a, 0, n);
        md.update(buf, 0, n * Integer.BYTES);
        return this;
    }

    /** {@code n} floats by their raw bits: -0.0 and 0.0 are different, and every NaN is itself. */
    Hash add(float[] a, int n) {
        ByteBuffer b = buffer(n * Float.BYTES);
        for (int i = 0; i < n; i++) b.putInt(i * Float.BYTES, Float.floatToRawIntBits(a[i]));
        md.update(buf, 0, n * Float.BYTES);
        return this;
    }

    private ByteBuffer buffer(int n) {
        if (buf.length < n) buf = new byte[n];
        return ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** The first eight bytes of the digest, as sixteen hex characters. */
    String hex() {
        return HexFormat.of().formatHex(md.digest(), 0, 8);
    }
}

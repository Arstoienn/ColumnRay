package engine;

/**
 * A table of fixed-size records, laid out so that a card will actually allocate it.
 *
 * Both the lightmaps and the image materials hand the shader one record per surface and let the
 * span carry an index. The obvious layout - one record to a row, a texture as many texels wide
 * as a record and as many rows tall as there are records - works on school, where there are
 * hundreds of them, and fails on Haven, where there are hundreds of thousands: every dimension
 * of a texture is capped (16384 on an M3), and a taller one is simply refused. Nothing says so
 * at the time. {@code glTexImage2D} sets an error nobody reads, the texture stays unallocated,
 * every {@code texelFetch} returns zero, and zero is a plausible-looking record - a zero mean
 * makes every image multiplier 1, a zero extent makes every light 0 - so the frame comes back
 * evenly shaded and wrong, which is much harder to recognise than a crash.
 *
 * So records run across the row and wrap: {@link #perRow} of them to a row, {@link #rows} rows.
 * Both sides then stay under the cap until there are more records than the square of it, and
 * {@link Gl#check} turns the next mistake of this kind into an exception. The shader is built
 * per world, so the row length is written into it rather than sent as a uniform.
 */
final class GpuTable {
    private final int texels, perRow, width, rows;

    GpuTable(int texels, int records) {
        this.texels = texels;
        perRow = Math.max(1, Gl.maxTextureSize() / texels);
        int n = Math.max(1, records);
        rows = (n + perRow - 1) / perRow;
        width = Math.min(n, perRow) * texels;
    }

    int width() { return width; }

    int rows() { return rows; }

    /** Where record i's first texel starts in a width x rows buffer, counted in floats. */
    long at(int i) {
        return ((long) (i / perRow) * width + (long) (i % perRow) * texels) * 4;
    }

    /** A GLSL function giving the position of a record's first texel, named after the table. */
    String glsl(String name) {
        return """
                /** Where record rec begins: %d of them to a row, %d texels each. */
                ivec2 %s(int rec) {
                    int row = rec / %d;
                    return ivec2((rec - row * %d) * %d, row);
                }
                """.formatted(perRow, texels, name, perRow, perRow, texels);
    }
}

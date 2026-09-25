package engine;

/**
 * What an sRGB number stands for as light.
 *
 * A class of its own rather than a corner of {@link Renderer}, because the bake reads it too:
 * {@code Lighting.emit} turns a map's colours into light through {@link #linOf} before bouncing
 * them ({@code --hdr}). That makes it one of the classes a cached bake has to be keyed on, and
 * {@code LightCache.BAKES} lists it. While it lived in {@code Renderer} it could not be: the
 * renderer is on the list of classes that deliberately do not key the cache - it runs after the
 * bake - so a change to this table would have left every cached file valid and the light in it
 * wrong, with nothing said. Keep the encode direction, the tone map and the grade in
 * {@code Renderer}; the bake does not use them, and they would cost a re-bake for nothing.
 */
final class Srgb {
    private Srgb() {}

    /** sRGB byte to light, with one spare entry so a fractional index can interpolate. */
    private static final double[] TO_LIGHT = new double[257];

    static {
        for (int i = 0; i <= 256; i++) {
            double v = Math.min(1.0, i / 255.0);
            TO_LIGHT[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
    }

    /** How much light an sRGB number stands for. Takes fractions, and numbers past 255: an image's
     *  factor can brighten a colour past white before anything has been tone mapped. */
    static double linOf(double v) {
        if (v <= 0) return 0;
        if (v >= 255) {
            double s = v / 255;
            return Math.pow((s + 0.055) / 1.055, 2.4);
        }
        int i = (int) v;
        return TO_LIGHT[i] + (TO_LIGHT[i + 1] - TO_LIGHT[i]) * (v - i);
    }
}

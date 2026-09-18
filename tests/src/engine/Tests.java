package engine;

/** Every unit test, run in one JVM. {@code ./test.sh} is how this is meant to be started. */
public final class Tests {
    private Tests() {}

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        OptionsTest.run();
        JsonTest.run();
        GeometryTest.run();
        DynamicResolutionTest.run();
        HashTest.run();
        WorldTest.run();
        PlayerTest.run();
        WarpTest.run();
        RendererTest.run();
        LightCacheTest.run();
        System.out.printf("%d checks, %d failed, %.1f s%n",
                Check.checks, Check.failed, (System.nanoTime() - t0) / 1e9);
        if (Check.failed > 0) System.exit(1);
    }
}

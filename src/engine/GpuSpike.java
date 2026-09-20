package engine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Would a GPU pay for itself here, and would reading the frame back undo it?
 *
 * The renderer's frame is dominated by texture filtering - eight texel reads and a blend for
 * every pixel, in Java - and that is the one thing a GPU has dedicated hardware for. The plan
 * this measures is the hybrid one: the CPU keeps casting one ray per column and works out the
 * intervals, and the GPU is handed those intervals and does nothing but fill vertical strips
 * and filter. Nothing about the column constraint changes.
 *
 * But the engine draws into a Swing window, and giving a Swing window a GL context on macOS
 * means CALayer and Objective-C. The cheap way round it is to render offscreen and read the
 * pixels back into the int[] the window already blits - which only works if the read costs
 * less than the filtering it saves. That is what this asks, without touching the renderer,
 * the window, or anything the golden frames can see:
 *
 *     ./build.sh && java --enable-native-access=ALL-UNNAMED -cp out engine.GpuSpike [WxH] [frames]
 *
 * Three phases are timed apart, because the verdict turns on which of them is slow:
 *
 *     upload   a frame's worth of per-column data into a texture, the CPU's half of a hybrid
 *     draw     a full-screen pass that samples a mipmapped texture, trilinear, the GPU's half
 *     read     glReadPixels into native memory and on into an int[], the price of staying in Swing
 *
 * The answer, on an M3: 1.3 ms at 720p and 2.5 at 1080p, against CPU frames of 9.7 and 23.7.
 * The read is the largest of the three and scales with the pixel count, about 0.85 ms a
 * megapixel, so it is what will eventually make a windowed context worth its Objective-C.
 */
final class GpuSpike {
    /** A full-screen triangle with no vertex buffer, its uv scaled so most of it is minified. */
    private static final String VERT = """
            #version 330 core
            out vec2 uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                uv = p * 12.0;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /** One trilinear sample per pixel, and one read of the per-column data, per pixel. */
    private static final String FRAG = """
            #version 330 core
            in vec2 uv;
            uniform sampler2D atlas;
            uniform sampler2D columns;
            uniform float width;
            out vec4 frag;
            void main() {
                vec4 c = texture(columns, vec2(gl_FragCoord.x / width, 0.5));
                frag = texture(atlas, uv + c.xy * 0.001);
            }
            """;

    public static void main(String[] args) {
        int w = 1280, h = 720, frames = 300;
        for (String a : args) {
            if (a.contains("x")) {
                String[] p = a.split("x");
                w = Integer.parseInt(p[0]);
                h = Integer.parseInt(p[1]);
            } else {
                frames = Integer.parseInt(a);
            }
        }
        Gl.context();
        System.out.printf("GL %s on %s%n", Gl.version(), Gl.device());
        try (Arena arena = Arena.ofConfined()) {
            run(arena, w, h, frames);
        }
    }

    private static void run(Arena arena, int w, int h, int frames) {
        // The target: an RGBA8 texture on a framebuffer, standing in for the window's int[].
        int target = Gl.texture();
        Gl.bindTexture(target);
        Gl.texImage(Gl.RGBA8, w, h, Gl.RGBA, Gl.UNSIGNED_BYTE, MemorySegment.NULL);
        Gl.texUnfiltered();
        Gl.bindFramebuffer(Gl.framebuffer());
        Gl.attach(target);

        // A 1024x1024 mipmapped atlas, the thing being filtered.
        int atlas = Gl.texture();
        Gl.activeTexture(0);
        Gl.bindTexture(atlas);
        MemorySegment pixels = arena.allocate((long) 1024 * 1024 * 4);
        for (int y = 0; y < 1024; y++)
            for (int x = 0; x < 1024; x++)
                pixels.setAtIndex(ValueLayout.JAVA_INT, (long) y * 1024 + x, (x ^ y) * 0x00010101 | 0xFF000000);
        Gl.texImage(Gl.RGBA8, 1024, 1024, Gl.RGBA, Gl.UNSIGNED_BYTE, pixels);
        Gl.generateMipmap();
        Gl.texParam(Gl.TEXTURE_MIN_FILTER, Gl.LINEAR_MIPMAP_LINEAR);
        Gl.texParam(Gl.TEXTURE_MAG_FILTER, Gl.LINEAR);
        Gl.texParam(Gl.TEXTURE_WRAP_S, Gl.REPEAT);
        Gl.texParam(Gl.TEXTURE_WRAP_T, Gl.REPEAT);

        // The per-column data a hybrid would upload every frame: four floats a column.
        int columns = Gl.texture();
        Gl.activeTexture(1);
        Gl.bindTexture(columns);
        MemorySegment spans = arena.allocate((long) w * 4 * Float.BYTES);
        for (int i = 0; i < w * 4; i++) spans.setAtIndex(ValueLayout.JAVA_FLOAT, i, i * 0.001f);
        Gl.texImage(Gl.RGBA32F, w, 1, Gl.RGBA, Gl.FLOAT, spans);
        Gl.texUnfiltered();

        int program = Gl.program(VERT, FRAG);
        Gl.useProgram(program);
        Gl.uniform(program, "atlas", 0);
        Gl.uniform(program, "columns", 1);
        Gl.uniform(program, "width", (float) w);
        Gl.bindVertexArray(Gl.vertexArray());
        Gl.viewport(w, h);

        MemorySegment back = arena.allocate((long) w * h * 4);
        int[] out = new int[w * h];
        double[] up = new double[frames], drew = new double[frames], read = new double[frames];
        for (int f = -30; f < frames; f++) {                       // the first thirty warm the driver
            long t0 = System.nanoTime();
            Gl.activeTexture(1);
            Gl.bindTexture(columns);
            Gl.texSubImage(w, 1, Gl.RGBA, Gl.FLOAT, spans);
            long t1 = System.nanoTime();
            Gl.clear();
            Gl.drawFullScreen();
            Gl.finish();
            long t2 = System.nanoTime();
            Gl.readPixels(w, h, back);
            MemorySegment.copy(back, ValueLayout.JAVA_INT, 0, out, 0, w * h);
            long t3 = System.nanoTime();
            if (f >= 0) {
                up[f] = (t1 - t0) / 1e6;
                drew[f] = (t2 - t1) / 1e6;
                read[f] = (t3 - t2) / 1e6;
            }
        }
        if (Gl.error() != 0) System.out.println("warning: GL reported an error");
        System.out.printf("SPIKE %dx%d  %d frames%n", w, h, frames);
        report("upload", up);
        report("draw", drew);
        report("read", read);
        double total = median(up) + median(drew) + median(read);
        System.out.printf("  %-8s %6.3f ms  (%.0f fps)%n", "total", total, 1000 / total);
    }

    private static void report(String name, double[] ms) {
        double[] s = ms.clone();
        Arrays.sort(s);
        System.out.printf("  %-8s %6.3f ms   p99 %6.3f%n", name, s[s.length / 2], s[(int) (s.length * 0.99)]);
    }

    private static double median(double[] ms) {
        double[] s = ms.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private GpuSpike() {}
}

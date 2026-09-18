package engine;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * The one thing about loading a texture that is not arithmetic: how big a picture the engine will
 * agree to unpack.
 *
 * A PNG's size on disk says nothing about the raster it becomes. The dimensions are in the header,
 * so they are read first and the file is refused by name, rather than the decoder asking the heap
 * for the whole thing from inside a parallel stream and taking the run down with it.
 */
final class MaterialsTest {
    private MaterialsTest() {}

    static void run() throws IOException {
        Check.group("Materials");

        Path small = png(4, 4);
        try {
            Check.that(load(small) != null, "an ordinary texture still loads");
        } finally {
            Files.deleteIfExists(small);
        }

        Path wide = png(Materials.MAX_SIDE + 1, 1);
        try {
            Check.rejects(() -> load(wide), "more than the engine will decode",
                    "an image wider than the engine will decode");
        } finally {
            Files.deleteIfExists(wide);
        }

        Path notAnImage = Files.createTempFile("columnray-image", ".png");
        try {
            Files.writeString(notAnImage, "this is not a PNG");
            Check.rejects(() -> load(notAnImage), "cannot decode", "a file that is not an image at all");
        } finally {
            Files.deleteIfExists(notAnImage);
        }
    }

    private static Path png(int w, int h) throws IOException {
        Path file = Files.createTempFile("columnray-image", ".png");
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    /** loadImage throws IOException, which Check.rejects cannot see; the message is what is being
     *  checked, so it is what is carried over. */
    private static Materials.Texture load(Path file) {
        try {
            return Materials.loadImage(file);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}

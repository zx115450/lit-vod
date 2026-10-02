package com.example.vod.worker.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailEncoderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldScaleLongEdgeToMax() throws Exception {
        Path source = tempDir.resolve("wide.png");
        BufferedImage image = new BufferedImage(960, 200, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 960, 200);
        graphics.dispose();
        ImageIO.write(image, "png", source.toFile());

        Path cover = tempDir.resolve("cover.jpg");
        ThumbnailEncoder.writeCoverJpeg(source, cover);

        BufferedImage out = ImageIO.read(cover.toFile());
        assertEquals(ThumbnailEncoder.MAX_EDGE, out.getWidth());
        assertEquals(100, out.getHeight());
        assertTrue(cover.toFile().length() > 0);
    }

    @Test
    void shouldRejectNonImage() throws Exception {
        Path source = tempDir.resolve("note.txt");
        java.nio.file.Files.writeString(source, "not an image");
        Path cover = tempDir.resolve("cover.jpg");
        assertThrows(Exception.class, () -> ThumbnailEncoder.writeCoverJpeg(source, cover));
    }
}

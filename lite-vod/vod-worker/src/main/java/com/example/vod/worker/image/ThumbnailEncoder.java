package com.example.vod.worker.image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * 把原图压成 JPEG 封面，长边不超过 {@link #MAX_EDGE}。
 * 无法解码时抛 {@link IOException}，由调用方保留原图。
 */
public final class ThumbnailEncoder {

    public static final int MAX_EDGE = 480;
    static final float JPEG_QUALITY = 0.82f;

    private ThumbnailEncoder() {
    }

    public static void writeCoverJpeg(Path source, Path target) throws IOException {
        BufferedImage src = ImageIO.read(source.toFile());
        if (src == null) {
            throw new IOException("unsupported or corrupt image: " + source.getFileName());
        }
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW <= 0 || srcH <= 0) {
            throw new IOException("invalid image size: " + srcW + "x" + srcH);
        }
        double scale = Math.min(1.0d, (double) MAX_EDGE / Math.max(srcW, srcH));
        int width = Math.max(1, (int) Math.round(srcW * scale));
        int height = Math.max(1, (int) Math.round(srcH * scale));

        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(src, 0, 0, width, height, null);
        } finally {
            //释放资源
            graphics.dispose();
        }

        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("no JPEG writer available");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            if (output == null) {
                throw new IOException("cannot open image output: " + target);
            }
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}

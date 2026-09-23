package com.bluffgame.web;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Draws the image chat apps show next to a shared link. Rendered once, then served from memory. */
@RestController
public class PreviewImageController {

    static final int WIDTH = 1200;
    static final int HEIGHT = 630;

    private volatile byte[] png;

    @GetMapping(value = "/og-image.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image() throws IOException {
        byte[] bytes = png;
        if (bytes == null) {
            bytes = render();
            png = bytes;
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(bytes);
    }

    static byte[] render() throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            g.setPaint(new GradientPaint(0, 0, new Color(0x1c2740), 0, HEIGHT, new Color(0x0f1420)));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            Ellipse2D felt = new Ellipse2D.Double(50, 70, 1100, 490);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(600, 315), 600,
                    new float[] {0f, 0.55f, 1f},
                    new Color[] {new Color(0x2c8f4e), new Color(0x1f6b3a), new Color(0x154a28)}));
            g.fill(felt);
            g.setStroke(new BasicStroke(18f));
            g.setColor(new Color(0x5b3a1e));
            g.draw(felt);

            drawCard(g, 150, 190, -16, "A", new Color(0xd1232a));
            drawCard(g, 250, 170, -4, "K", new Color(0x1b1b1b));
            drawCard(g, 350, 185, 9, "?", new Color(0x7a2fd6));

            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 150));
            g.drawString("BLUFF", 560, 330);
            g.setColor(new Color(0xffd77a));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 46));
            g.drawString("By Dhrushaj Achar", 566, 398);
            g.setColor(new Color(0xd8e0f0));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 30));
            g.drawString("Lie about your cards. Call out your friends.", 568, 452);
            g.drawString("Empty your hand first.", 568, 492);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void drawCard(Graphics2D g, int x, int y, double degrees, String rank, Color color) {
        AffineTransform saved = g.getTransform();
        g.rotate(Math.toRadians(degrees), x + 75, y + 105);
        g.setColor(new Color(0, 0, 0, 100));
        g.fill(new RoundRectangle2D.Double(x + 8, y + 10, 150, 210, 18, 18));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(x, y, 150, 210, 18, 18));
        g.setColor(color);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        g.drawString(rank, x + 14, y + 48);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 96));
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(rank, x + 75 - metrics.stringWidth(rank) / 2, y + 140);
        g.setTransform(saved);
    }
}

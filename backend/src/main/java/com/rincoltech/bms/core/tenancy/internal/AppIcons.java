package com.rincoltech.bms.core.tenancy.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * The installed app's icons drawn from the tenant logo (ADR-064, issue #446): a square of the theme
 * colour with the logo centred inside it, PNG, with Java2D alone. The plain icons and the iPhone's
 * keep the logo inside 80 percent of the side; the maskable ones keep it inside the safe zone, the
 * circle of 40 percent radius that every launcher mask leaves visible. A rendered icon is kept by
 * its key (the logo's checksum, the colour and the variant), so a logo or colour change is a new key.
 */
final class AppIcons {

    /** The icons the manifest and the page name; the file name is also the static BMS icon's under {@code /icons/}. */
    enum Variant {
        ICON_192("icon-192.png", 192, false),
        ICON_512("icon-512.png", 512, false),
        MASKABLE_192("icon-maskable-192.png", 192, true),
        MASKABLE_512("icon-maskable-512.png", 512, true),
        APPLE_TOUCH("apple-touch-icon.png", 180, false);

        final String file;
        final int size;
        final boolean maskable;

        Variant(String file, int size, boolean maskable) {
            this.file = file;
            this.size = size;
            this.maskable = maskable;
        }

        static Optional<Variant> of(String file) {
            return Arrays.stream(values()).filter(v -> v.file.equals(file)).findFirst();
        }
    }

    /** Share of the side the logo may fill on a plain icon. */
    static final double PLAIN_BOX = 0.8;
    /** Radius of the maskable safe zone as a share of the side (the W3C manifest's 80 percent circle). */
    static final double SAFE_RADIUS = 0.4;

    private AppIcons() {}

    /** The icon from the logo's bytes (PNG or JPEG, as stored) on {@code background} ({@code #RRGGBB}). */
    static byte[] render(byte[] logo, String background, Variant variant) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(logo));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (source == null) {
            throw new IllegalArgumentException("the logo is not a readable image");
        }
        int size = variant.size;
        double w = source.getWidth();
        double h = source.getHeight();
        // Plain: the longer side fills 80 percent. Maskable: the logo's diagonal is the safe circle's diameter.
        double scale =
                variant.maskable ? (2 * SAFE_RADIUS * size) / Math.hypot(w, h) : (PLAIN_BOX * size) / Math.max(w, h);
        int dw = Math.max(1, (int) Math.round(w * scale));
        int dh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = icon.createGraphics();
        try {
            g.setColor(Color.decode(background));
            g.fillRect(0, 0, size, size);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(shrink(source, dw, dh), (size - dw) / 2, (size - dh) / 2, dw, dh, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(icon, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Halves the image until one bicubic step is left, so a large logo does not alias when it becomes small. */
    private static BufferedImage shrink(BufferedImage image, int width, int height) {
        BufferedImage current = image;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            BufferedImage half =
                    new BufferedImage(current.getWidth() / 2, current.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = half.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(current, 0, 0, half.getWidth(), half.getHeight(), null);
            } finally {
                g.dispose();
            }
            current = half;
        }
        return current;
    }

    /** At most {@value #CACHE_ENTRIES} rendered icons (each well under 100 KB), least recently used out. */
    static final int CACHE_ENTRIES = 64;

    private static final Map<String, byte[]> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > CACHE_ENTRIES;
                }
            });

    /** The icon under {@code key}, rendered once by {@code render}; an empty render is not kept. */
    static Optional<byte[]> cached(String key, Supplier<Optional<byte[]>> render) {
        byte[] hit = CACHE.get(key);
        if (hit != null) {
            return Optional.of(hit);
        }
        Optional<byte[]> made = render.get();
        made.ifPresent(b -> CACHE.put(key, b));
        return made;
    }
}

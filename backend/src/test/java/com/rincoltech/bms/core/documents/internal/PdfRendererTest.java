package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

/**
 * ADR-034: the renderer loads only {@code data:} URIs (the tenant logo). A value that points anywhere
 * else, a URL on a machine of the attacker's choosing or a file on this one, is never fetched.
 */
class PdfRendererTest {

    private final PdfRenderer renderer = new PdfRenderer();

    static String dataUri() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static int images(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDResources res = d.getPage(0).getResources();
            int n = 0;
            for (COSName name : res.getXObjectNames()) {
                if (res.getXObject(name) instanceof PDImageXObject) {
                    n++;
                }
            }
            return n;
        }
    }

    @Test
    void aDataUriImageIsDrawn() throws Exception {
        byte[] pdf = renderer.render("probe", Map.of("src", dataUri()));
        assertThat(images(pdf)).as("the logo is on the page").isPositive();
    }

    @Test
    void aUrlIsNeverFetched() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread listener = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        Socket s = server.accept();
                        connections.incrementAndGet();
                        s.close();
                    }
                } catch (java.io.IOException closed) {
                    // the server is closed at the end of the test
                }
            });
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/logo.png";
            byte[] pdf = renderer.render("probe", Map.of("src", url));
            Thread.sleep(300);
            assertThat(connections.get()).as("connections made by the renderer").isZero();
            assertThat(images(pdf)).as("nothing was drawn").isZero();
            listener.interrupt();
        }
    }

    @Test
    void aFileIsNeverRead() throws Exception {
        java.nio.file.Path secret = java.nio.file.Files.createTempFile("probe", ".png");
        try {
            java.nio.file.Files.write(
                    secret,
                    Base64.getDecoder().decode(dataUri().substring(dataUri().indexOf(',') + 1)));
            byte[] pdf = renderer.render("probe", Map.of("src", secret.toUri().toString()));
            assertThat(images(pdf)).as("a file: URI draws nothing").isZero();
        } finally {
            java.nio.file.Files.deleteIfExists(secret);
        }
    }
}

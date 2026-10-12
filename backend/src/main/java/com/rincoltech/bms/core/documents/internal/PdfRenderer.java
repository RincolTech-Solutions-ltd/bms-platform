package com.rincoltech.bms.core.documents.internal;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * ADR-034: Thymeleaf in XML mode (strict XHTML, every value escaped) laid out by OpenHTMLtoPDF on
 * PDFBox, with the self-hosted Open Sans embedded in three weights. Templates live under
 * {@code templates/documents/} and share {@code layout.html} for the header and footer. The renderer
 * loads nothing from outside the document: an access rule, checked before any URI is resolved, allows
 * only {@code data:} URIs (the tenant logo), so no template value can reach a file or a URL.
 */
@Component
class PdfRenderer {

    private static final String FONTS = "/fonts/open-sans/OpenSans-";

    private final TemplateEngine engine;

    PdfRenderer() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/documents/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    String xhtml(String template, Map<String, Object> model) {
        return engine.process(template, new Context(null, model));
    }

    byte[] render(String template, Map<String, Object> model) {
        String html = xhtml(template, model);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withProducer("BMS Platform");
            builder.useExternalResourceAccessControl(
                    (uri, type) -> uri != null && uri.regionMatches(true, 0, "data:", 0, 5),
                    ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
            builder.withHtmlContent(html, "/");
            font(builder, "Regular", 400);
            font(builder, "SemiBold", 600);
            font(builder, "Bold", 700);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void font(PdfRendererBuilder builder, String file, int weight) {
        builder.useFont(
                () -> PdfRenderer.class.getResourceAsStream(FONTS + file + ".ttf"),
                "Open Sans",
                weight,
                FontStyle.NORMAL,
                true);
    }
}

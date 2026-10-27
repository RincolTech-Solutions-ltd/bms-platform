package com.rincoltech.bms.core.help.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.help.SetupDrafts.Upload;
import com.rincoltech.bms.kernel.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * What a file the owner chose becomes before it is sent (issue #626, ADR-102): the kind read from the bytes, a photo drawn
 * again as a JPEG no larger than it needs, a PDF's text or, for a scan, a picture of its first page, the first sheet of a
 * workbook as rows of cells, a CSV as UTF-8, and plain words for anything else. Every file is made here.
 */
class ListFilesTest {

    static Upload upload(String name, byte[] bytes) {
        return new Upload(name, "application/octet-stream", bytes);
    }

    @Test
    void aCsvIsReadAsText() {
        ListFiles.Read read =
                ListFiles.read(upload("list.csv", "﻿name,price\nTest Sugar,5500\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(read.text()).isEqualTo("name,price\nTest Sugar,5500");
        assertThat(read.pictureDataUrl()).isNull();
        assertThat(read.source()).isEqualTo("file");
    }

    @Test
    void aPhotoIsDrawnAgainAsASmallerJpegWhateverItsName() throws IOException {
        BufferedImage wide = new BufferedImage(3000, 1000, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(wide, "png", png);
        ListFiles.Read read = ListFiles.read(upload("list.csv", png.toByteArray()));
        assertThat(read.source()).isEqualTo("photo");
        assertThat(read.text()).isNull();
        assertThat(read.pictureDataUrl()).startsWith("data:image/jpeg;base64,");
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(read.pictureDataUrl().substring("data:image/jpeg;base64,".length()))));
        assertThat(sent.getWidth()).isEqualTo(ListFiles.MAX_SIDE);
        assertThat(sent.getHeight()).isEqualTo(667);
    }

    @Test
    void aWorkbookGivesTheCellsOfItsFirstSheet() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            part(zip, "[Content_Types].xml", "<Types/>");
            part(zip, "xl/sharedStrings.xml", """
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                    <si><t>Item</t></si><si><t>Price</t></si><si><r><t>Test </t></r><r><t>Sugar</t></r></si></sst>""");
            part(
                    zip,
                    "xl/worksheets/sheet2.xml",
                    "<worksheet><sheetData><row r=\"1\"><c r=\"A1\"><v>9</v></c></row>" + "</sheetData></worksheet>");
            part(zip, "xl/worksheets/sheet1.xml", """
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>\
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>\
                    <row r="3"><c r="A3" t="s"><v>2</v></c><c r="C3"><v>5500</v></c></row>\
                    <row r="4"><c r="B4" t="inlineStr"><is><t>Test Salt</t></is></c></row>\
                    </sheetData></worksheet>""");
        }
        assertThat(ListFiles.read(upload("stock.xlsx", bytes.toByteArray())).text())
                .isEqualTo("Item\tPrice\nTest Sugar\t\t5500\n\tTest Salt");
    }

    static void part(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    void aPdfGivesItsTextAndAScanAPictureOfItsFirstPage() throws IOException {
        ByteArrayOutputStream typed = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream text = new PDPageContentStream(doc, page)) {
                text.beginText();
                text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                text.newLineAtOffset(72, 700);
                text.showText("Test Sugar 5500");
                text.endText();
            }
            doc.save(typed);
        }
        ListFiles.Read read = ListFiles.read(upload("list.pdf", typed.toByteArray()));
        assertThat(read.text()).isEqualTo("Test Sugar 5500");
        assertThat(read.pictureDataUrl()).isNull();

        ByteArrayOutputStream scan = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(scan);
        }
        ListFiles.Read scanned = ListFiles.read(upload("scan.pdf", scan.toByteArray()));
        assertThat(scanned.text()).isNull();
        assertThat(scanned.pictureDataUrl()).startsWith("data:image/jpeg;base64,");
        assertThat(scanned.source()).isEqualTo("file");
    }

    /** A PNG whose header declares a picture of the given size and holds no pixels: what a crafted file looks like. */
    static byte[] declaredPng(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        java.nio.ByteBuffer ihdr = java.nio.ByteBuffer.allocate(17);
        ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height);
        ihdr.put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(ihdr.array());
        out.write(java.nio.ByteBuffer.allocate(4).putInt(13).array());
        out.write(ihdr.array());
        out.write(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        return out.toByteArray();
    }

    /** The size is read from the header before anything is decoded, so a declared 40000 by 40000 picture is refused. */
    @Test
    void aPhotoDeclaringAHugeSizeIsRefusedBeforeItIsDecoded() throws IOException {
        assertThatThrownBy(() -> ListFiles.read(upload("big.png", declaredPng(40_000, 40_000))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("too big to read");
    }

    /** A page declaring 200 inches a side is drawn no larger than the longest side that is sent. */
    @Test
    void aScannedPageOfAnySizeIsDrawnSmall() throws IOException {
        ByteArrayOutputStream scan = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(14_400, 14_400)));
            doc.save(scan);
        }
        String url = ListFiles.read(upload("huge.pdf", scan.toByteArray())).pictureDataUrl();
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(url.substring("data:image/jpeg;base64,".length()))));
        assertThat(Math.max(sent.getWidth(), sent.getHeight())).isLessThanOrEqualTo(ListFiles.MAX_SIDE);
    }

    @Test
    void anythingElseIsRefusedInPlainWords() {
        assertThatThrownBy(() ->
                        ListFiles.read(upload("old.xls", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0})))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("old Excel file");
        assertThatThrownBy(() -> ListFiles.read(upload("x.bin", new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 1})))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be read");
        assertThatThrownBy(() -> ListFiles.read(upload("empty.csv", new byte[0])))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() ->
                        ListFiles.read(upload("photo.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1})))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("photo cannot be read");
    }
}

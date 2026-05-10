package com.voyu.agent.service.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class PdfExportRenderer {

    private static final Path DEFAULT_OUTPUT_DIR = Path.of("data", "exports");
    private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
    private static final float MARGIN = 54f;
    private static final float BODY_FONT_SIZE = 11f;
    private static final float TITLE_FONT_SIZE = 18f;
    private static final float SECTION_FONT_SIZE = 13f;
    private static final float LINE_GAP = 4f;

    public Path render(PdfExportRequest request) throws IOException {
        return render(request, DEFAULT_OUTPUT_DIR, null);
    }

    public Path render(PdfExportRequest request, Path outputDirectory) throws IOException {
        return render(request, outputDirectory, null);
    }

    public Path render(PdfExportRequest request, Path outputDirectory, Path fontPath) throws IOException {
        Objects.requireNonNull(request, "request must not be null");
        Path safeOutputDirectory = outputDirectory == null ? DEFAULT_OUTPUT_DIR : outputDirectory;
        Files.createDirectories(safeOutputDirectory);

        String fileName = safeFileName(request.sessionId()) + ".pdf";
        Path pdfPath = safeOutputDirectory.resolve(fileName);

        try (PDDocument document = new PDDocument()) {
            FontSet fonts = loadFonts(document, fontPath);
            try (PdfWriter writer = new PdfWriter(document, fonts)) {
                writer.writeTitle(request.title());
                writer.writeLabelValue("Session ID", request.sessionId());

                writer.writeSection("User Request");
                writer.writeParagraph(request.userRequest());

                writer.writeSection("Final Answer");
                writer.writeParagraph(request.finalAnswer());

                writer.writeSection("Messages");
                if (request.messages().isEmpty()) {
                    writer.writeParagraph("No messages.");
                } else {
                    for (Message message : request.messages()) {
                        writer.writeLabelValue(safeText(message.role(), "MESSAGE"), message.content());
                    }
                }
            }
            document.save(pdfPath.toFile());
        }

        return pdfPath;
    }

    private FontSet loadFonts(PDDocument document, Path fontPath) throws IOException {
        if (fontPath != null && Files.exists(fontPath)) {
            try (InputStream input = Files.newInputStream(fontPath)) {
                PDFont customFont = PDType0Font.load(document, input, true);
                return new FontSet(customFont, customFont, false);
            }
        }
        return new FontSet(PDType1Font.HELVETICA, PDType1Font.HELVETICA_BOLD, true);
    }

    private static String safeFileName(String value) {
        String sanitized = safeText(value, "session").replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "session" : sanitized;
    }

    private static String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    public record PdfExportRequest(String title,
                                   String sessionId,
                                   String userRequest,
                                   String finalAnswer,
                                   List<Message> messages) {

        public PdfExportRequest {
            title = safeText(title, "Voyu Conversation Export");
            sessionId = safeText(sessionId, "session");
            userRequest = safeText(userRequest, "No user request.");
            finalAnswer = safeText(finalAnswer, "No final answer.");
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    public record Message(String role, String content) {

        public Message {
            role = safeText(role, "MESSAGE");
            content = safeText(content, "");
        }
    }

    private record FontSet(PDFont regular, PDFont bold, boolean standardEncodingOnly) {
    }

    private static final class PdfWriter implements AutoCloseable {

        private final PDDocument document;
        private final FontSet fonts;
        private PDPageContentStream contentStream;
        private float cursorY;

        private PdfWriter(PDDocument document, FontSet fonts) throws IOException {
            this.document = document;
            this.fonts = fonts;
            addPage();
        }

        private void writeTitle(String text) throws IOException {
            writeWrapped(text, fonts.bold(), TITLE_FONT_SIZE, 10f);
        }

        private void writeSection(String text) throws IOException {
            addVerticalSpace(8f);
            writeWrapped(text, fonts.bold(), SECTION_FONT_SIZE, 6f);
        }

        private void writeLabelValue(String label, String value) throws IOException {
            writeWrapped(label + ": " + value, fonts.regular(), BODY_FONT_SIZE, 4f);
        }

        private void writeParagraph(String text) throws IOException {
            for (String paragraph : splitParagraphs(text)) {
                if (paragraph.isBlank()) {
                    addVerticalSpace(BODY_FONT_SIZE);
                    continue;
                }
                writeWrapped(paragraph, fonts.regular(), BODY_FONT_SIZE, 6f);
            }
        }

        private void writeWrapped(String text, PDFont font, float fontSize, float gapAfter) throws IOException {
            String prepared = prepareText(text);
            float maxWidth = PAGE_SIZE.getWidth() - (MARGIN * 2);
            for (String line : wrap(prepared, font, fontSize, maxWidth)) {
                drawLine(line, font, fontSize);
            }
            addVerticalSpace(gapAfter);
        }

        private void drawLine(String line, PDFont font, float fontSize) throws IOException {
            ensureSpace(fontSize + LINE_GAP);
            contentStream.beginText();
            contentStream.setFont(font, fontSize);
            contentStream.newLineAtOffset(MARGIN, cursorY);
            contentStream.showText(line);
            contentStream.endText();
            cursorY -= fontSize + LINE_GAP;
        }

        private void addVerticalSpace(float points) throws IOException {
            ensureSpace(points);
            cursorY -= points;
        }

        private void ensureSpace(float points) throws IOException {
            if (cursorY - points < MARGIN) {
                addPage();
            }
        }

        private void addPage() throws IOException {
            if (contentStream != null) {
                contentStream.close();
            }
            PDPage page = new PDPage(PAGE_SIZE);
            document.addPage(page);
            contentStream = new PDPageContentStream(document, page);
            cursorY = PAGE_SIZE.getHeight() - MARGIN;
        }

        private List<String> splitParagraphs(String text) {
            return List.of(safeText(text, "").split("\\R", -1));
        }

        private List<String> wrap(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
            String normalized = text.replaceAll("\\s+", " ").trim();
            if (normalized.isBlank()) {
                return List.of("");
            }

            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (int index = 0; index < normalized.length(); index++) {
                char ch = normalized.charAt(index);
                String candidate = current.toString() + ch;
                if (!current.isEmpty() && width(candidate, font, fontSize) > maxWidth) {
                    lines.add(current.toString().stripTrailing());
                    current = new StringBuilder();
                    if (ch != ' ') {
                        current.append(ch);
                    }
                    continue;
                }
                current.append(ch);
            }
            if (!current.isEmpty()) {
                lines.add(current.toString().stripTrailing());
            }
            return lines;
        }

        private float width(String text, PDFont font, float fontSize) throws IOException {
            return font.getStringWidth(text) / 1000f * fontSize;
        }

        private String prepareText(String text) {
            String value = safeText(text, "");
            if (!fonts.standardEncodingOnly()) {
                return value;
            }

            StringBuilder sanitized = new StringBuilder(value.length());
            for (int index = 0; index < value.length(); index++) {
                char ch = value.charAt(index);
                if (ch == '\n' || ch == '\r' || ch == '\t') {
                    sanitized.append(ch);
                } else if (ch >= 32 && ch <= 126) {
                    sanitized.append(ch);
                } else {
                    sanitized.append('?');
                }
            }
            return sanitized.toString();
        }

        @Override
        public void close() throws IOException {
            if (contentStream != null) {
                contentStream.close();
                contentStream = null;
            }
        }
    }
}

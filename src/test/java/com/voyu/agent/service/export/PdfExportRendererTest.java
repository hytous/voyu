package com.voyu.agent.service.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyu.agent.service.export.PdfExportRenderer.Message;
import com.voyu.agent.service.export.PdfExportRenderer.PdfExportRequest;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfExportRendererTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rendersPdfFromSampleSessionJson() throws Exception {
        Path outputDirectory = Path.of("target", "pdf-export-test");
        Files.createDirectories(outputDirectory);
        System.setProperty("pdfbox.fontcache", outputDirectory.resolve("font-cache").toString());

        SampleSession sample = readSampleSession();
        PdfExportRequest request = new PdfExportRequest(
                sample.title(),
                sample.sessionId(),
                sample.userRequest(),
                sample.finalAnswer(),
                sample.messages().stream()
                        .map(message -> new Message(message.role(), message.content()))
                        .toList());

        Path outputPath = new PdfExportRenderer().render(request, outputDirectory);

        assertTrue(Files.exists(outputPath));
        assertTrue(Files.size(outputPath) > 0);
    }

    private SampleSession readSampleSession() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/pdf-export/sample-session.json")) {
            assertNotNull(input, "sample-session.json should be available on the test classpath");
            return objectMapper.readValue(input, SampleSession.class);
        }
    }

    private record SampleSession(String title,
                                 String sessionId,
                                 String userRequest,
                                 String finalAnswer,
                                 List<SampleMessage> messages) {

        SampleSession {
            messages = messages == null ? List.of() : messages;
        }
    }

    private record SampleMessage(String role, String content) {
    }
}

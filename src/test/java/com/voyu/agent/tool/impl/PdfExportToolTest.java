package com.voyu.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyu.agent.service.export.PdfExportRenderer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfExportToolTest {

    @Test
    void exportsPdfFromToolInput() throws Exception {
        Path outputDirectory = Path.of("target", "pdf-export-tool-test");
        Files.createDirectories(outputDirectory);
        System.setProperty("pdfbox.fontcache", outputDirectory.resolve("font-cache").toString());

        PdfExportTool tool = new PdfExportTool(new PdfExportRenderer(), new ObjectMapper());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sessionId", "tool-test-session-001");
        input.put("title", "Tokyo export tool test");
        input.put("userRequest", "Plan a short Tokyo trip focused on anime, food, and bookstores.");
        input.put("finalAnswer", "Use Akihabara, Nakano, Kichijoji, and Asakusa as the route anchors.");
        input.put("messages", List.of(
                Map.of("role", "USER", "content", "I want a Tokyo anime and food route."),
                Map.of("role", "ASSISTANT", "content", "Here is a compact route with relaxed pacing.")));
        input.put("outputDir", outputDirectory.toString());

        Map<String, Object> result = tool.execute(input);

        assertEquals(Boolean.TRUE, result.get("success"));
        Object rawFilePath = result.get("filePath");
        assertNotNull(rawFilePath);
        String filePath = String.valueOf(rawFilePath);
        assertTrue(!filePath.isBlank());

        Path pdfPath = Path.of(filePath);
        assertTrue(Files.exists(pdfPath));
        assertTrue(Files.size(pdfPath) > 0);
    }
}

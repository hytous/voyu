package com.voyu.agent.controller;

import com.voyu.agent.model.history.ConversationMessageRecord;
import com.voyu.agent.model.history.TravelConversationDocument;
import com.voyu.agent.service.agent.PlanFileService;
import com.voyu.agent.service.export.PdfExportRenderer;
import com.voyu.agent.service.export.PdfExportRenderer.Message;
import com.voyu.agent.service.export.PdfExportRenderer.PdfExportRequest;
import com.voyu.agent.service.history.ConversationHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationHistoryControllerPdfExportTest {

    private static final Path OUTPUT_DIRECTORY = Path.of("target", "pdf-export-controller-test");

    private StubConversationHistoryService conversationHistoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(OUTPUT_DIRECTORY);
        System.setProperty("pdfbox.fontcache", OUTPUT_DIRECTORY.resolve("font-cache").toString());

        conversationHistoryService = new StubConversationHistoryService();
        PlanFileService planFileService = new PlanFileService(OUTPUT_DIRECTORY.resolve("plans").toString());
        ConversationHistoryController controller = new ConversationHistoryController(
                conversationHistoryService,
                planFileService,
                new PdfExportRenderer(),
                OUTPUT_DIRECTORY.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void exportsPdfForExistingSession() throws Exception {
        String sessionId = "controller-session-001";
        Path pdfPath = OUTPUT_DIRECTORY.resolve(sessionId + ".pdf");
        Files.deleteIfExists(pdfPath);
        conversationHistoryService.putSession(sampleDocument(sessionId));

        mockMvc.perform(post("/api/travel-agent/sessions/{sessionId}/export/pdf", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.fileName").value(sessionId + ".pdf"))
                .andExpect(jsonPath("$.downloadUrl").value("/api/travel-agent/sessions/" + sessionId + "/export/pdf/download"));

        assertTrue(Files.exists(pdfPath));
        assertTrue(Files.size(pdfPath) > 0);
    }

    @Test
    void returnsNotFoundWhenExportSessionDoesNotExist() throws Exception {
        String sessionId = "missing-controller-session";

        mockMvc.perform(post("/api/travel-agent/sessions/{sessionId}/export/pdf", sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.errorMessage").exists());
    }

    @Test
    void downloadsExistingPdf() throws Exception {
        String sessionId = "download-session-001";
        Path pdfPath = OUTPUT_DIRECTORY.resolve(sessionId + ".pdf");
        Files.deleteIfExists(pdfPath);
        new PdfExportRenderer().render(new PdfExportRequest(
                "Download test",
                sessionId,
                "Plan a Kyoto walking day.",
                "Start near Gion, visit temples, and end by the river.",
                List.of(new Message("USER", "Plan a calm Kyoto walking day."))),
                OUTPUT_DIRECTORY);

        mockMvc.perform(get("/api/travel-agent/sessions/{sessionId}/export/pdf/download", sessionId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(sessionId + ".pdf")));
    }

    @Test
    void returnsNotFoundWhenPdfDoesNotExist() throws Exception {
        String sessionId = "download-missing-session";
        Files.deleteIfExists(OUTPUT_DIRECTORY.resolve(sessionId + ".pdf"));

        mockMvc.perform(get("/api/travel-agent/sessions/{sessionId}/export/pdf/download", sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.errorMessage").exists());
    }

    private TravelConversationDocument sampleDocument(String sessionId) {
        Instant now = Instant.parse("2026-05-10T00:00:00Z");
        TravelConversationDocument document = new TravelConversationDocument();
        document.setSessionId(sessionId);
        document.setTitle("Tokyo food and bookstore plan");
        document.setRequestSnapshot(Map.of("message", "Plan a Tokyo trip with ramen, bookstores, and museums."));
        document.setFinalAnswer("Use Ueno, Jimbocho, Kichijoji, and Shinjuku as the route anchors.");
        document.setMessages(List.of(
                new ConversationMessageRecord(
                        "m1",
                        null,
                        "USER",
                        "Plan a Tokyo trip with ramen, bookstores, and museums.",
                        "USER_INPUT",
                        "COMPLETED",
                        1L,
                        now,
                        now,
                        Map.of("message", "Plan a Tokyo trip with ramen, bookstores, and museums.")),
                new ConversationMessageRecord(
                        "m2",
                        "m1",
                        "ASSISTANT",
                        "Use Ueno, Jimbocho, Kichijoji, and Shinjuku as the route anchors.",
                        "FINAL_ANSWER",
                        "COMPLETED",
                        2L,
                        now,
                        now,
                        null)));
        return document;
    }

    private static final class StubConversationHistoryService extends ConversationHistoryService {

        private final Map<String, TravelConversationDocument> sessions = new HashMap<>();

        private StubConversationHistoryService() {
            super(null, null, null, Runnable::run, "test-history");
        }

        private void putSession(TravelConversationDocument document) {
            sessions.put(document.getSessionId(), document);
        }

        @Override
        public Optional<TravelConversationDocument> findChatSession(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }
    }
}

package com.voyu.agent.controller;

import com.voyu.agent.model.history.ConversationSessionSummary;
import com.voyu.agent.model.history.ConversationMessageRecord;
import com.voyu.agent.model.history.TravelConversationDocument;
import com.voyu.agent.service.agent.PlanFileService;
import com.voyu.agent.service.export.PdfExportRenderer;
import com.voyu.agent.service.export.PdfExportRenderer.Message;
import com.voyu.agent.service.export.PdfExportRenderer.PdfExportRequest;
import com.voyu.agent.service.history.ConversationHistoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/travel-agent/sessions")
public class ConversationHistoryController {

    private static final String DEFAULT_EXPORT_TITLE = "\u65c5\u6e38\u89c4\u5212\u4f1a\u8bdd\u5bfc\u51fa";

    private final ConversationHistoryService conversationHistoryService;
    private final PlanFileService planFileService;
    private final PdfExportRenderer pdfExportRenderer;
    private final Path exportDirectory;

    public ConversationHistoryController(ConversationHistoryService conversationHistoryService,
                                         PlanFileService planFileService,
                                         PdfExportRenderer pdfExportRenderer,
                                         @Value("${voyu.agent.export-dir:data/exports}") String exportDir) {
        this.conversationHistoryService = conversationHistoryService;
        this.planFileService = planFileService;
        this.pdfExportRenderer = pdfExportRenderer;
        this.exportDirectory = Path.of(exportDir);
    }

    @GetMapping
    public List<ConversationSessionSummary> listSessions(@RequestParam(required = false) String userId,
                                                          @RequestParam(defaultValue = "20") int limit) {
        return conversationHistoryService.listSessions(userId, limit);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<TravelConversationDocument> getSession(@PathVariable String sessionId) {
        return conversationHistoryService.findSession(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{sessionId}/chat")
    public ResponseEntity<TravelConversationDocument> getChatSession(@PathVariable String sessionId) {
        return conversationHistoryService.findChatSession(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/export/pdf")
    public ResponseEntity<Map<String, Object>> exportPdf(@PathVariable String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return errorResponse(HttpStatus.BAD_REQUEST, sessionId, "PDF export failed.", "sessionId must not be blank");
        }

        Optional<TravelConversationDocument> session = conversationHistoryService.findChatSession(sessionId);
        if (session.isEmpty()) {
            return errorResponse(HttpStatus.NOT_FOUND, sessionId, "PDF export failed.", "Conversation session not found");
        }

        try {
            Path outputDirectory = exportBaseDirectory();
            PdfExportRequest request = toPdfExportRequest(sessionId, session.get());
            Path pdfPath = pdfExportRenderer.render(request, outputDirectory).toAbsolutePath().normalize();
            assertUnderExportDirectory(outputDirectory, pdfPath);

            String fileName = pdfPath.getFileName().toString();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("sessionId", sessionId);
            response.put("fileName", fileName);
            response.put("downloadUrl", downloadUrl(sessionId));
            response.put("summary", "PDF export generated.");
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, sessionId, "PDF export failed.", ex.getMessage());
        }
    }

    @GetMapping("/{sessionId}/export/pdf/download")
    public ResponseEntity<?> downloadPdf(@PathVariable String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return errorResponse(HttpStatus.BAD_REQUEST, sessionId, "PDF download failed.", "sessionId must not be blank");
        }

        try {
            Path outputDirectory = exportBaseDirectory().toAbsolutePath().normalize();
            Path pdfPath = outputDirectory.resolve(safeFileName(sessionId) + ".pdf").normalize();
            assertUnderExportDirectory(outputDirectory, pdfPath);

            if (!Files.exists(pdfPath) || !Files.isRegularFile(pdfPath)) {
                return errorResponse(HttpStatus.NOT_FOUND, sessionId, "PDF download failed.", "Exported PDF not found");
            }

            Resource resource = new FileSystemResource(pdfPath);
            String fileName = pdfPath.getFileName().toString();
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(Files.size(pdfPath))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .body(resource);
        } catch (Exception ex) {
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, sessionId, "PDF download failed.", ex.getMessage());
        }
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        boolean deleted = conversationHistoryService.deleteSession(sessionId);
        // 同步清理关联的 plan 文件，避免孤立文件
        planFileService.deletePlanFile(sessionId);
        return deleted
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    private PdfExportRequest toPdfExportRequest(String sessionId, TravelConversationDocument document) {
        return new PdfExportRequest(
                firstText(document.getTitle(), DEFAULT_EXPORT_TITLE),
                sessionId,
                firstText(requestMessage(document.getLastRequestSnapshot()),
                        requestMessage(document.getRequestSnapshot()),
                        latestMessageByRole(document.getMessages(), "USER"),
                        "No user request."),
                firstText(document.getFinalAnswer(),
                        latestMessageByRole(document.getMessages(), "ASSISTANT"),
                        document.getErrorMessage(),
                        "No final answer."),
                toPdfMessages(document.getMessages()));
    }

    private List<Message> toPdfMessages(List<ConversationMessageRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .filter(record -> record != null)
                .map(record -> new Message(
                        firstText(record.getRole(), "MESSAGE"),
                        firstText(record.getContent(), "")))
                .toList();
    }

    private String requestMessage(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return "";
        }
        Object message = snapshot.get("message");
        return message == null ? "" : String.valueOf(message);
    }

    private String latestMessageByRole(List<ConversationMessageRecord> records, String role) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        for (int index = records.size() - 1; index >= 0; index--) {
            ConversationMessageRecord record = records.get(index);
            if (record != null && role.equalsIgnoreCase(record.getRole()) && StringUtils.hasText(record.getContent())) {
                return record.getContent();
            }
        }
        return "";
    }

    private Path exportBaseDirectory() throws IOException {
        Path outputDirectory = exportDirectory.toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        return outputDirectory;
    }

    private void assertUnderExportDirectory(Path outputDirectory, Path pdfPath) {
        if (!pdfPath.startsWith(outputDirectory)) {
            throw new IllegalArgumentException("Resolved PDF path is outside the export directory");
        }
    }

    private String downloadUrl(String sessionId) {
        return "/api/travel-agent/sessions/"
                + UriUtils.encodePathSegment(sessionId, StandardCharsets.UTF_8)
                + "/export/pdf/download";
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status,
                                                              String sessionId,
                                                              String summary,
                                                              String errorMessage) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("sessionId", sessionId);
        response.put("summary", summary);
        response.put("errorMessage", errorMessage);
        return ResponseEntity.status(status).body(response);
    }

    private String safeFileName(String value) {
        String sanitized = firstText(value, "session").replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "session" : sanitized;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }
}

package com.voyu.agent.tool.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyu.agent.service.export.PdfExportRenderer;
import com.voyu.agent.service.export.PdfExportRenderer.Message;
import com.voyu.agent.service.export.PdfExportRenderer.PdfExportRequest;
import com.voyu.agent.tool.ToolCapabilityType;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PdfExportTool implements TravelTool {

    private static final String TOOL_NAME = "pdf.export";
    private static final String DESCRIPTION = "将已生成的旅游规划会话内容整理为 PDF 文件，并保存到本地导出目录。";
    private static final ToolTemplate TEMPLATE = ToolTemplate.local(
            TOOL_NAME,
            "PDF 导出",
            DESCRIPTION,
            ToolCapabilityType.GENERIC_WRITE,
            false,
            false,
            ToolTemplate.jsonObjectSchema("sessionId", "title", "userRequest", "finalAnswer", "messages", "outputDir"));

    private final PdfExportRenderer renderer;
    private final ObjectMapper objectMapper;

    public PdfExportTool(PdfExportRenderer renderer, ObjectMapper objectMapper) {
        this.renderer = renderer;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolTemplate template() {
        return TEMPLATE;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        String sessionId = textValue(safeInput.get("sessionId"), "session");
        try {
            PdfExportRequest request = new PdfExportRequest(
                    textValue(safeInput.get("title"), "Voyu Conversation Export"),
                    sessionId,
                    textValue(safeInput.get("userRequest"), "No user request."),
                    textValue(safeInput.get("finalAnswer"), "No final answer."),
                    parseMessages(safeInput.get("messages")));
            Path outputDirectory = outputDirectory(safeInput.get("outputDir"));
            Path filePath = outputDirectory == null
                    ? renderer.render(request)
                    : renderer.render(request, outputDirectory);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("sessionId", sessionId);
            result.put("filePath", filePath.toAbsolutePath().normalize().toString());
            result.put("summary", "PDF 已导出到 " + filePath.toString());
            return result;
        } catch (Exception ex) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("sessionId", sessionId);
            result.put("filePath", "");
            result.put("summary", "PDF 导出失败。");
            result.put("errorMessage", ex.getMessage());
            result.put("error", ex.getMessage());
            return result;
        }
    }

    private Path outputDirectory(Object rawOutputDir) {
        String outputDir = textValue(rawOutputDir, "");
        return StringUtils.hasText(outputDir) ? Path.of(outputDir) : null;
    }

    private List<Message> parseMessages(Object rawMessages) {
        if (rawMessages == null) {
            return List.of();
        }
        if (rawMessages instanceof List<?> list) {
            return parseMessageList(list);
        }
        if (rawMessages instanceof Map<?, ?> map) {
            Object nested = map.get("messages");
            if (nested instanceof List<?> list) {
                return parseMessageList(list);
            }
            return List.of(messageFromMap(map));
        }
        if (rawMessages instanceof String text) {
            return parseMessageString(text);
        }
        return List.of(new Message("MESSAGE", String.valueOf(rawMessages)));
    }

    private List<Message> parseMessageString(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return List.of();
        }
        String trimmed = rawText.trim();
        if (!(trimmed.startsWith("[") || trimmed.startsWith("{"))) {
            return List.of(new Message("MESSAGE", trimmed));
        }

        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root.isArray()) {
                List<Map<String, Object>> values = objectMapper.convertValue(
                        root,
                        new TypeReference<List<Map<String, Object>>>() {
                        });
                return parseMessageList(values);
            }
            if (root.isObject() && root.get("messages") != null && root.get("messages").isArray()) {
                List<Map<String, Object>> values = objectMapper.convertValue(
                        root.get("messages"),
                        new TypeReference<List<Map<String, Object>>>() {
                        });
                return parseMessageList(values);
            }
            if (root.isObject()) {
                Map<String, Object> value = objectMapper.convertValue(
                        root,
                        new TypeReference<Map<String, Object>>() {
                        });
                return List.of(messageFromMap(value));
            }
        } catch (Exception ignored) {
            return List.of(new Message("MESSAGE", trimmed));
        }
        return List.of();
    }

    private List<Message> parseMessageList(List<?> rawList) {
        List<Message> messages = new ArrayList<>();
        for (Object item : rawList) {
            Message message = parseMessageItem(item);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    private Message parseMessageItem(Object item) {
        if (item == null) {
            return null;
        }
        if (item instanceof Message message) {
            return message;
        }
        if (item instanceof Map<?, ?> map) {
            return messageFromMap(map);
        }
        if (item instanceof String text) {
            if (!StringUtils.hasText(text)) {
                return null;
            }
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) {
                List<Message> parsed = parseMessageString(trimmed);
                return parsed.isEmpty() ? null : parsed.get(0);
            }
            return new Message("MESSAGE", trimmed);
        }
        return new Message("MESSAGE", String.valueOf(item));
    }

    private Message messageFromMap(Map<?, ?> map) {
        return new Message(
                textValue(firstPresent(map, "role", "type", "sender"), "MESSAGE"),
                textValue(firstPresent(map, "content", "text", "message"), ""));
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private String textValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : fallback;
    }
}

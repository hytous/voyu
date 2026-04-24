package com.voyu.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyu.agent.config.McpToolProperties;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class McpToolGateway {

    private static final Field MCP_TOOL_FIELD = resolveMcpToolField();

    private final ObjectMapper objectMapper;
    private final ObjectProvider<SyncMcpToolCallbackProvider> callbackProvider;
    private final McpToolProperties properties;

    public McpToolGateway(ObjectMapper objectMapper,
                          ObjectProvider<SyncMcpToolCallbackProvider> callbackProvider,
                          McpToolProperties properties) {
        this.objectMapper = objectMapper;
        this.callbackProvider = callbackProvider;
        this.properties = properties;
    }

    public List<ToolTemplate> listRemoteTools() {
        List<ToolTemplate> discovered = callbacks().stream()
                .filter(callback -> properties.isToolAllowed(canonicalName(callback.getToolDefinition().name())))
                .map(this::toToolTemplate)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                ToolTemplate::name,
                                template -> template,
                                (left, right) -> left,
                                LinkedHashMap::new),
                        map -> map.values().stream()
                                .sorted(Comparator.comparing(ToolTemplate::name, String.CASE_INSENSITIVE_ORDER))
                                .toList()));

        if (!discovered.isEmpty()) {
            return discovered;
        }
        return properties.discoveredTools();
    }

    public Optional<ToolTemplate> findRemoteTool(String name) {
        return listRemoteTools().stream()
                .filter(template -> template.matches(name))
                .findFirst();
    }

    public boolean hasTool(String name) {
        return findCallback(name).isPresent();
    }

    public TravelTool toTravelTool(String name) {
        ToolCallback callback = findCallback(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown MCP tool: " + name));
        ToolTemplate template = toToolTemplate(callback);
        return new TravelTool() {
            @Override
            public ToolTemplate template() {
                return template;
            }

            @Override
            public Map<String, Object> execute(Map<String, Object> input) {
                try {
                    String rawResult = callback.call(objectMapper.writeValueAsString(input == null ? Map.of() : input));
                    return normalizeCallbackResult(rawResult);
                } catch (Exception ex) {
                    return Map.of(
                            "error", String.valueOf(ex.getMessage()),
                            "toolName", template.name());
                }
            }
        };
    }

    private Optional<ToolCallback> findCallback(String name) {
        return callbacks().stream()
                .filter(callback -> isSameToolName(name, callback.getToolDefinition().name())
                        || toToolTemplate(callback).matches(name))
                .findFirst();
    }

    private List<ToolCallback> callbacks() {
        SyncMcpToolCallbackProvider provider = callbackProvider.getIfAvailable();
        if (provider == null) {
            return List.of();
        }
        return Arrays.asList(provider.getToolCallbacks());
    }

    private ToolTemplate toToolTemplate(ToolCallback callback) {
        ToolDefinition definition = callback.getToolDefinition();
        String canonicalName = canonicalName(definition.name());
        String description = StringUtils.hasText(definition.description()) ? definition.description() : canonicalName;
        String inputSchema = definition.inputSchema();
        String outputSchema = "";
        Boolean readOnlyHint = null;
        Boolean destructiveHint = null;
        Boolean idempotentHint = null;
        Boolean openWorldHint = null;
        Boolean returnDirect = callback.getToolMetadata() == null ? null : callback.getToolMetadata().returnDirect();
        String displayName = canonicalName;
        Map<String, Object> protocolMetadata = new LinkedHashMap<>();
        List<String> aliases = new ArrayList<>();

        if (callback instanceof SyncMcpToolCallback syncCallback) {
            aliases.add(syncCallback.getOriginalToolName());
            aliases.add(definition.name());
            protocolMetadata.put("originalToolName", syncCallback.getOriginalToolName());

            McpSchema.Tool rawTool = extractProtocolTool(syncCallback);
            if (rawTool != null) {
                if (StringUtils.hasText(rawTool.title())) {
                    displayName = rawTool.title();
                    protocolMetadata.put("title", rawTool.title());
                }
                if (StringUtils.hasText(rawTool.description())) {
                    description = rawTool.description();
                }
                if (rawTool.outputSchema() != null && !rawTool.outputSchema().isEmpty()) {
                    outputSchema = writeJson(rawTool.outputSchema());
                }
                if (rawTool.meta() != null && !rawTool.meta().isEmpty()) {
                    protocolMetadata.put("meta", rawTool.meta());
                }
                McpSchema.ToolAnnotations annotations = rawTool.annotations();
                if (annotations != null) {
                    readOnlyHint = annotations.readOnlyHint();
                    destructiveHint = annotations.destructiveHint();
                    idempotentHint = annotations.idempotentHint();
                    openWorldHint = annotations.openWorldHint();
                    if (annotations.returnDirect() != null) {
                        returnDirect = annotations.returnDirect();
                    }
                }
            }
        }

        ToolCapabilityType capabilityType = ToolCapabilityType.infer(canonicalName, readOnlyHint);
        boolean parallelizable = Boolean.TRUE.equals(readOnlyHint);
        return new ToolTemplate(
                canonicalName,
                displayName,
                description,
                capabilityType,
                ToolSourceType.MCP,
                properties.getProvider(),
                true,
                readOnlyHint,
                destructiveHint,
                idempotentHint,
                openWorldHint,
                returnDirect,
                parallelizable,
                inputSchema,
                outputSchema,
                protocolMetadata,
                aliases);
    }

    private Map<String, Object> normalizeCallbackResult(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = unwrapNode(objectMapper.readTree(rawResult));
            if (node.isObject()) {
                return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
                });
            }
            if (node.isArray()) {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("items", objectMapper.convertValue(node, new TypeReference<List<Object>>() {
                }));
                return wrapper;
            }
            if (node.isTextual()) {
                return Map.of("text", node.asText());
            }
            if (node.isNumber()) {
                return Map.of("value", node.numberValue());
            }
            if (node.isBoolean()) {
                return Map.of("value", node.booleanValue());
            }
        } catch (Exception ignored) {
        }
        return Map.of("raw", rawResult);
    }

    private JsonNode unwrapNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            JsonNode structuredContent = node.get("structuredContent");
            if (structuredContent != null && !structuredContent.isNull()) {
                return unwrapNode(structuredContent);
            }
            JsonNode content = node.get("content");
            JsonNode parsedContent = extractEmbeddedContent(content);
            if (parsedContent != null) {
                return unwrapNode(parsedContent);
            }
            JsonNode text = node.get("text");
            JsonNode parsedText = parseEmbeddedJson(text);
            if (parsedText != null) {
                return unwrapNode(parsedText);
            }
            return node;
        }
        if (node.isArray()) {
            JsonNode parsedContent = extractEmbeddedContent(node);
            if (parsedContent != null) {
                return unwrapNode(parsedContent);
            }
        }
        return node;
    }

    private JsonNode extractEmbeddedContent(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        if (node.size() != 1) {
            return null;
        }
        JsonNode first = node.get(0);
        if (first == null || first.isNull()) {
            return null;
        }
        if (first.isObject()) {
            JsonNode structuredContent = first.get("structuredContent");
            if (structuredContent != null && !structuredContent.isNull()) {
                return structuredContent;
            }
            JsonNode text = first.get("text");
            JsonNode parsedText = parseEmbeddedJson(text);
            if (parsedText != null) {
                return parsedText;
            }
        }
        if (first.isTextual()) {
            return parseEmbeddedJson(first);
        }
        return null;
    }

    private JsonNode parseEmbeddedJson(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private McpSchema.Tool extractProtocolTool(SyncMcpToolCallback callback) {
        if (MCP_TOOL_FIELD == null) {
            return null;
        }
        try {
            return (McpSchema.Tool) MCP_TOOL_FIELD.get(callback);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String canonicalName(String candidate) {
        return configuredToolNames().stream()
                .filter(name -> isSameToolName(candidate, name))
                .findFirst()
                .orElse(candidate);
    }

    private boolean isSameToolName(String left, String right) {
        return Objects.equals(left, right)
                || normalizeToolName(left).equals(normalizeToolName(right));
    }

    private String normalizeToolName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private List<String> configuredToolNames() {
        if (properties.getTools() == null) {
            return List.of();
        }
        return properties.getTools().stream()
                .filter(Objects::nonNull)
                .filter(McpToolProperties.RemoteTool::isEnabled)
                .map(McpToolProperties.RemoteTool::getName)
                .filter(Objects::nonNull)
                .toList();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private static Field resolveMcpToolField() {
        try {
            Field field = SyncMcpToolCallback.class.getDeclaredField("tool");
            field.setAccessible(true);
            return field;
        } catch (Exception ignored) {
            return null;
        }
    }
}

package com.voyu.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record ToolTemplate(String name,
                           String displayName,
                           String description,
                           ToolCapabilityType capabilityType,
                           ToolSourceType sourceType,
                           String provider,
                           boolean invocable,
                           Boolean readOnlyHint,
                           Boolean destructiveHint,
                           Boolean idempotentHint,
                           Boolean openWorldHint,
                           Boolean returnDirect,
                           boolean parallelizable,
                           String inputSchema,
                           String outputSchema,
                           Map<String, Object> protocolMetadata,
                           List<String> aliases) {

    public ToolTemplate {
        name = textOrDefault(name, "");
        displayName = textOrDefault(displayName, name);
        description = textOrDefault(description, "");
        capabilityType = capabilityType == null ? ToolCapabilityType.GENERIC_WRITE : capabilityType;
        sourceType = sourceType == null ? ToolSourceType.LOCAL : sourceType;
        provider = textOrDefault(provider, sourceType.code());
        inputSchema = textOrDefault(inputSchema, jsonObjectSchema());
        outputSchema = textOrDefault(outputSchema, "");
        protocolMetadata = protocolMetadata == null ? Map.of() : new LinkedHashMap<>(protocolMetadata);
        aliases = normalizeAliases(aliases, name);
        parallelizable = parallelizable && invocable;
    }

    public static ToolTemplate local(String name,
                                     String displayName,
                                     String description,
                                     ToolCapabilityType capabilityType,
                                     boolean readOnlyHint,
                                     boolean parallelizable,
                                     String inputSchema) {
        return new ToolTemplate(
                name,
                displayName,
                description,
                capabilityType,
                ToolSourceType.LOCAL,
                ToolSourceType.LOCAL.code(),
                true,
                readOnlyHint,
                !readOnlyHint,
                readOnlyHint,
                false,
                false,
                parallelizable,
                inputSchema,
                "",
                Map.of(),
                List.of());
    }

    public static String jsonObjectSchema(String... stringFields) {
        if (stringFields == null || stringFields.length == 0) {
            return "{\"type\":\"object\"}";
        }
        String properties = java.util.Arrays.stream(stringFields)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(field -> !field.isBlank())
                .distinct()
                .map(field -> "\"" + escape(field) + "\":{\"type\":\"string\"}")
                .collect(Collectors.joining(","));
        if (properties.isBlank()) {
            return "{\"type\":\"object\"}";
        }
        return "{\"type\":\"object\",\"properties\":{" + properties + "}}";
    }

    public boolean matches(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalizedCandidate = normalize(candidate);
        if (normalize(name).equals(normalizedCandidate)) {
            return true;
        }
        return aliases.stream().map(ToolTemplate::normalize).anyMatch(normalizedCandidate::equals);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("displayName", displayName);
        data.put("description", description);
        data.put("capabilityType", capabilityType.code());
        data.put("sourceType", sourceType.code());
        data.put("provider", provider);
        data.put("invocable", invocable);
        data.put("readOnlyHint", readOnlyHint);
        data.put("destructiveHint", destructiveHint);
        data.put("idempotentHint", idempotentHint);
        data.put("openWorldHint", openWorldHint);
        data.put("returnDirect", returnDirect);
        data.put("parallelizable", parallelizable);
        data.put("inputSchema", inputSchema);
        data.put("outputSchema", outputSchema);
        data.put("aliases", aliases);
        data.put("protocolMetadata", protocolMetadata);
        return data;
    }

    private static List<String> normalizeAliases(List<String> aliases, String name) {
        Set<String> unique = new LinkedHashSet<>();
        if (aliases != null) {
            aliases.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(alias -> !alias.isBlank())
                    .filter(alias -> !alias.equals(name))
                    .forEach(unique::add);
        }
        return new ArrayList<>(unique);
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

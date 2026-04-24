package com.voyu.agent.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class SpringAiLlmFacade implements LlmFacade {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmFacade.class);

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public SpringAiLlmFacade(ObjectMapper objectMapper,
                             @Value("${voyu.llm.api-key:${MOONSHOT_API_KEY:${VOYU_LLM_API_KEY:}}}") String apiKey,
                             @Value("${voyu.llm.base-url:https://api.moonshot.ai/v1}") String baseUrl,
                             @Value("${voyu.llm.model:kimi-k2.5}") String model) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    ),
                    "thinking", Map.of("type", "disabled")
            );

            HttpURLConnection connection = (HttpURLConnection) URI.create(normalizeEndpoint(baseUrl)).toURL().openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(90000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setDoOutput(true);

            byte[] payload = objectMapper.writeValueAsString(requestBody).getBytes(StandardCharsets.UTF_8);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(payload);
            }

            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = "";
            if (stream != null) {
                try (InputStream inputStream = stream) {
                    responseBody = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            connection.disconnect();

            if (statusCode < 200 || statusCode >= 300) {
                log.warn("Moonshot API call failed with status {} and body {}", statusCode, truncate(responseBody));
                return null;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText();
            }
            if (content.isArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonNode node : content) {
                    JsonNode text = node.path("text");
                    if (text.isTextual()) {
                        builder.append(text.asText());
                    }
                }
                return builder.toString();
            }
            return null;
        } catch (Exception ex) {
            log.warn("Moonshot API call failed: {}", ex.getMessage());
            return null;
        }
    }

    private String normalizeEndpoint(String configuredBaseUrl) {
        String normalized = configuredBaseUrl == null ? "" : configuredBaseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}

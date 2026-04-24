package com.voyu.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Primary
@Service
public class SiliconFlowEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowEmbeddingService.class);

    private final ObjectMapper objectMapper;
    private final DeterministicEmbeddingService fallback;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int dimension;
    private final boolean enabled;

    public SiliconFlowEmbeddingService(ObjectMapper objectMapper,
                                       DeterministicEmbeddingService fallback,
                                       @Value("${voyu.rag.siliconflow.api-key:}") String apiKey,
                                       @Value("${voyu.rag.siliconflow.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
                                       @Value("${voyu.rag.siliconflow.embedding-model:Qwen/Qwen3-Embedding-8B}") String model,
                                       @Value("${voyu.rag.embedding-dim:64}") int dimension,
                                       @Value("${voyu.rag.siliconflow.enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.fallback = fallback;
        this.apiKey = apiKey;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.model = model;
        this.dimension = dimension;
        this.enabled = enabled;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<Float> embed(String text) {
        List<List<Float>> result = embedRemote(List.of(text));
        return result.isEmpty() ? fallback.embed(text) : result.getFirst();
    }

    @Override
    public List<List<Float>> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<List<Float>> result = new ArrayList<>(texts.size());
        int batchSize = 32;
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(start + batchSize, texts.size()));
            List<List<Float>> remote = embedRemote(batch);
            if (remote.size() != batch.size()) {
                return fallback.embedAll(texts);
            }
            result.addAll(remote);
        }
        return result;
    }

    private List<List<Float>> embedRemote(List<String> texts) {
        if (!enabled || !StringUtils.hasText(apiKey) || texts.isEmpty()) {
            return List.of();
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("input", texts);
            payload.put("dimensions", dimension);
            payload.put("encoding_format", "float");

            JsonNode root = post("/embeddings", payload);
            List<List<Float>> embeddings = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                List<Float> vector = new ArrayList<>();
                for (JsonNode value : item.path("embedding")) {
                    vector.add((float) value.asDouble());
                }
                embeddings.add(vector);
            }
            return embeddings;
        } catch (Exception ex) {
            log.warn("SiliconFlow embedding failed, using fallback: {}", ex.getMessage());
            return List.of();
        }
    }

    private JsonNode post(String path, Map<String, Object> payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
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
            throw new IllegalStateException("HTTP " + statusCode + ": " + truncate(responseBody));
        }
        return objectMapper.readTree(responseBody);
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}

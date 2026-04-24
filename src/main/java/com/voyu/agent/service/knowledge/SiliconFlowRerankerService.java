package com.voyu.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyu.agent.model.knowledge.KnowledgeHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

@Service
public class SiliconFlowRerankerService {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowRerankerService.class);

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String instruction;
    private final boolean enabled;

    public SiliconFlowRerankerService(ObjectMapper objectMapper,
                                      @Value("${voyu.rag.siliconflow.api-key:}") String apiKey,
                                      @Value("${voyu.rag.siliconflow.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
                                      @Value("${voyu.rag.siliconflow.reranker-model:Qwen/Qwen3-Reranker-8B}") String model,
                                      @Value("${voyu.rag.siliconflow.reranker-instruction:请优先保留与用户目的地、玩法偏好和预算约束最相关的旅行知识。}") String instruction,
                                      @Value("${voyu.rag.siliconflow.rerank-enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.model = model;
        this.instruction = instruction;
        this.enabled = enabled;
    }

    public List<KnowledgeHit> rerank(String query, List<KnowledgeHit> candidates, int topK) {
        if (!enabled || !StringUtils.hasText(apiKey) || candidates.isEmpty()) {
            return candidates.stream().limit(topK).toList();
        }
        try {
            List<String> documents = candidates.stream()
                    .map(hit -> hit.getChunk().getTitle() + "\n" + hit.getChunk().getContent())
                    .toList();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("query", query);
            payload.put("documents", documents);
            payload.put("top_n", Math.min(topK, documents.size()));
            payload.put("return_documents", true);
            payload.put("instruction", instruction);

            JsonNode root = post("/rerank", payload);
            List<KnowledgeHit> reranked = new ArrayList<>();
            for (JsonNode result : root.path("results")) {
                int index = result.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size()) {
                    continue;
                }
                KnowledgeHit original = candidates.get(index);
                reranked.add(new KnowledgeHit(
                        original.getChunk(),
                        result.path("relevance_score").asDouble(original.getScore()),
                        mergeSources(original.getSource(), "siliconflow-rerank")
                ));
            }
            return reranked.isEmpty() ? candidates.stream().limit(topK).toList() : reranked;
        } catch (Exception ex) {
            log.warn("SiliconFlow rerank failed, using fused order: {}", ex.getMessage());
            return candidates.stream().limit(topK).toList();
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

    private String mergeSources(String current, String next) {
        if (current == null || current.isBlank()) {
            return next;
        }
        if (current.contains(next)) {
            return current;
        }
        return current + "+" + next;
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

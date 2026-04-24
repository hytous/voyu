package com.voyu.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyu.agent.model.knowledge.KnowledgeChunk;
import com.voyu.agent.model.knowledge.KnowledgeHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MilvusKnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusKnowledgeStore.class);

    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final String baseUrl;
    private final String collectionName;
    private final boolean enabled;

    public MilvusKnowledgeStore(ObjectMapper objectMapper,
                                EmbeddingService embeddingService,
                                @Value("${voyu.middleware.milvus.host:localhost}") String host,
                                @Value("${voyu.middleware.milvus.grpc-port:19530}") int port,
                                @Value("${voyu.rag.milvus-collection:voyu_travel_knowledge}") String collectionName,
                                @Value("${voyu.rag.milvus-enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.embeddingService = embeddingService;
        this.collectionName = collectionName;
        this.enabled = enabled;
        this.baseUrl = "http://" + host + ":" + port;
    }

    public void sync(List<KnowledgeChunk> chunks) {
        if (!enabled || chunks.isEmpty()) {
            return;
        }
        try {
            recreateCollection();
            ensureCollection();
            upsert(chunks);
            loadCollection();
        } catch (Exception ex) {
            log.warn("Failed to sync knowledge to Milvus: {}", ex.getMessage());
        }
    }

    public List<KnowledgeHit> search(String query, String destination, int limit) {
        if (!enabled || query == null || query.isBlank()) {
            return List.of();
        }
        List<Float> vector = embeddingService.embed(query);
        List<KnowledgeHit> hits = searchOnce(vector, limit);
        if (!hits.isEmpty()) {
            return hits;
        }
        try {
            loadCollection();
        } catch (Exception ex) {
            log.warn("Milvus reload before retry failed: {}", ex.getMessage());
        }
        return searchOnce(vector, limit);
    }

    public List<KnowledgeHit> search(String query, int limit) {
        return search(query, "", limit);
    }

    private List<KnowledgeHit> searchOnce(List<Float> vector, int limit) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("collectionName", collectionName);
            requestBody.put("vector", vector);
            requestBody.put("limit", limit);
            requestBody.put("outputFields", List.of("title", "destination", "content", "keywords"));

            JsonNode root = post("/v1/vector/search", requestBody);
            int code = root.path("code").asInt(-1);
            if (code != 0 && code != 200) {
                log.warn("Milvus search returned code {} message {}", code, root.path("message").asText(""));
                return List.of();
            }

            List<KnowledgeHit> result = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                result.add(new KnowledgeHit(
                        new KnowledgeChunk(
                                item.path("id").asText(),
                                item.path("title").asText(""),
                                item.path("destination").asText(""),
                                item.path("content").asText(""),
                                splitKeywords(item.path("keywords").asText(""))
                        ),
                        item.path("distance").asDouble(0.0),
                        "milvus"
                ));
            }
            return result;
        } catch (Exception ex) {
            log.warn("Milvus search failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private void ensureCollection() throws Exception {
        JsonNode hasResponse = post("/v2/vectordb/collections/has", Map.of("collectionName", collectionName));
        if (hasResponse.path("data").path("has").asBoolean(false)) {
            return;
        }

        Map<String, Object> createRequest = Map.of(
                "collectionName", collectionName,
                "schema", Map.of(
                        "autoID", false,
                        "enableDynamicField", false,
                        "fields", List.of(
                                Map.of(
                                        "fieldName", "id",
                                        "dataType", "VarChar",
                                        "isPrimary", true,
                                        "elementTypeParams", Map.of("max_length", 128)
                                ),
                                Map.of(
                                        "fieldName", "destination",
                                        "dataType", "VarChar",
                                        "elementTypeParams", Map.of("max_length", 128)
                                ),
                                Map.of(
                                        "fieldName", "title",
                                        "dataType", "VarChar",
                                        "elementTypeParams", Map.of("max_length", 512)
                                ),
                                Map.of(
                                        "fieldName", "content",
                                        "dataType", "VarChar",
                                        "elementTypeParams", Map.of("max_length", 4096)
                                ),
                                Map.of(
                                        "fieldName", "keywords",
                                        "dataType", "VarChar",
                                        "elementTypeParams", Map.of("max_length", 1024)
                                ),
                                Map.of(
                                        "fieldName", "vector",
                                        "dataType", "FloatVector",
                                        "elementTypeParams", Map.of("dim", embeddingService.dimension())
                                )
                        )
                ),
                "indexParams", List.of(
                        Map.of(
                                "fieldName", "vector",
                                "indexName", "vector_idx",
                                "metricType", "COSINE",
                                "params", Map.of("index_type", "AUTOINDEX")
                        )
                )
        );

        JsonNode createResponse = post("/v2/vectordb/collections/create", createRequest);
        int code = createResponse.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("Milvus create collection failed: " + createResponse.toPrettyString());
        }
    }

    private void recreateCollection() throws Exception {
        JsonNode hasResponse = post("/v2/vectordb/collections/has", Map.of("collectionName", collectionName));
        if (!hasResponse.path("data").path("has").asBoolean(false)) {
            return;
        }
        JsonNode dropResponse = post("/v2/vectordb/collections/drop", Map.of("collectionName", collectionName));
        int code = dropResponse.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("Milvus drop collection failed: " + dropResponse.toPrettyString());
        }
    }

    private void upsert(List<KnowledgeChunk> chunks) throws Exception {
        List<Map<String, Object>> data = new ArrayList<>(chunks.size());
        List<List<Float>> vectors = embeddingService.embedAll(chunks.stream().map(KnowledgeChunk::searchableText).toList());
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            data.add(Map.of(
                    "id", truncate(chunk.getId(), 128),
                    "destination", truncate(chunk.getDestination(), 128),
                    "title", truncate(chunk.getTitle(), 512),
                    "content", truncate(chunk.getContent(), 4096),
                    "keywords", truncate(String.join(" ", chunk.getKeywords()), 1024),
                    "vector", vectors.get(i)
            ));
        }

        JsonNode response = post("/v2/vectordb/entities/upsert", Map.of(
                "collectionName", collectionName,
                "data", data
        ));
        int code = response.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("Milvus upsert failed: " + response.toPrettyString());
        }
    }

    private void loadCollection() throws Exception {
        JsonNode response = post("/v2/vectordb/collections/load", Map.of("collectionName", collectionName));
        int code = response.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("Milvus load failed: " + response.toPrettyString());
        }
    }

    private JsonNode post(String path, Map<String, Object> payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod("POST");
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
        return objectMapper.readTree(responseBody);
    }

    private List<String> splitKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        return List.of(keywords.split("\\s+")).stream()
                .map(item -> item.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxLength) {
            return value;
        }

        StringBuilder builder = new StringBuilder();
        int usedBytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String token = new String(Character.toChars(codePoint));
            int tokenBytes = token.getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + tokenBytes > maxLength) {
                break;
            }
            builder.append(token);
            usedBytes += tokenBytes;
            offset += Character.charCount(codePoint);
        }
        return builder.toString().trim();
    }
}

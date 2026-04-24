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
public class ElasticsearchKnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchKnowledgeStore.class);

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String indexName;
    private final boolean enabled;

    public ElasticsearchKnowledgeStore(ObjectMapper objectMapper,
                                       @Value("${voyu.middleware.elasticsearch.url:http://localhost:9200}") String baseUrl,
                                       @Value("${voyu.rag.elasticsearch-index:voyu_travel_knowledge}") String indexName,
                                       @Value("${voyu.rag.elasticsearch-enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.indexName = indexName;
        this.enabled = enabled;
    }

    public void sync(List<KnowledgeChunk> chunks) {
        if (!enabled || chunks.isEmpty()) {
            return;
        }
        try {
            recreateIndex();
            ensureIndex();
            bulkUpsert(chunks);
        } catch (Exception ex) {
            log.warn("Failed to sync knowledge to Elasticsearch: {}", ex.getMessage());
        }
    }

    public List<KnowledgeHit> search(String query, List<String> destinations, int limit) {
        if (!enabled || query == null || query.isBlank()) {
            return List.of();
        }

        try {
            Map<String, Object> bool = new LinkedHashMap<>();
            List<Object> should = new ArrayList<>();
            should.add(Map.of("multi_match", Map.of(
                    "query", query,
                    "fields", List.of("title^4", "destination^5", "content^2", "keywords^3"),
                    "type", "best_fields"
            )));
            for (String destination : destinations) {
                if (destination == null || destination.isBlank()) {
                    continue;
                }
                should.add(Map.of("match", Map.of(
                        "destination", Map.of(
                                "query", destination,
                                "boost", 5
                        )
                )));
            }
            bool.put("should", should);
            bool.put("minimum_should_match", 1);

            Map<String, Object> requestBody = Map.of(
                    "size", limit,
                    "_source", List.of("id", "title", "destination", "content", "keywords"),
                    "query", Map.of("bool", bool)
            );

            HttpResponseData response = execute(
                    "POST",
                    baseUrl + "/" + indexName + "/_search",
                    "application/json",
                    objectMapper.writeValueAsString(requestBody),
                    10000);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Elasticsearch search failed with status {}", response.statusCode());
                return List.of();
            }

            JsonNode hits = objectMapper.readTree(response.body()).path("hits").path("hits");
            List<KnowledgeHit> result = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                result.add(new KnowledgeHit(
                        new KnowledgeChunk(
                                text(source, "id"),
                                text(source, "title"),
                                text(source, "destination"),
                                text(source, "content"),
                                array(source.path("keywords"))
                        ),
                        hit.path("_score").asDouble(0.0),
                        "elasticsearch"
                ));
            }
            return result;
        } catch (Exception ex) {
            log.warn("Elasticsearch search failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private void ensureIndex() throws Exception {
        HttpResponseData existsResponse = execute("GET", baseUrl + "/" + indexName, null, null, 5000);
        if (existsResponse.statusCode() == 200) {
            return;
        }
        if (existsResponse.statusCode() != 404) {
            throw new IllegalStateException("Unexpected Elasticsearch status: " + existsResponse.statusCode());
        }

        Map<String, Object> requestBody = Map.of(
                "settings", Map.of(
                        "analysis", Map.of(
                                "tokenizer", Map.of(
                                        "voyu_ngram_tokenizer", Map.of(
                                                "type", "ngram",
                                                "min_gram", 1,
                                                "max_gram", 2,
                                                "token_chars", List.of("letter", "digit")
                                        )
                                ),
                                "analyzer", Map.of(
                                        "voyu_ngram_analyzer", Map.of(
                                                "type", "custom",
                                                "tokenizer", "voyu_ngram_tokenizer",
                                                "filter", List.of("lowercase")
                                        )
                                )
                        )
                ),
                "mappings", Map.of(
                        "properties", Map.of(
                                "id", Map.of("type", "keyword"),
                                "title", Map.of("type", "text", "analyzer", "voyu_ngram_analyzer"),
                                "destination", Map.of("type", "text", "analyzer", "voyu_ngram_analyzer"),
                                "content", Map.of("type", "text", "analyzer", "voyu_ngram_analyzer"),
                                "keywords", Map.of("type", "text", "analyzer", "voyu_ngram_analyzer")
                        )
                )
        );

        HttpResponseData response = execute(
                "PUT",
                baseUrl + "/" + indexName,
                "application/json",
                objectMapper.writeValueAsString(requestBody),
                10000);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Failed to create Elasticsearch index: " + response.body());
        }
    }

    private void recreateIndex() throws Exception {
        HttpResponseData existsResponse = execute("GET", baseUrl + "/" + indexName, null, null, 5000);
        if (existsResponse.statusCode() != 200) {
            return;
        }
        HttpResponseData deleteResponse = execute("DELETE", baseUrl + "/" + indexName, null, null, 10000);
        if (deleteResponse.statusCode() < 200 || deleteResponse.statusCode() >= 300) {
            throw new IllegalStateException("Failed to delete Elasticsearch index: " + deleteResponse.body());
        }
    }

    private void bulkUpsert(List<KnowledgeChunk> chunks) throws Exception {
        StringBuilder payload = new StringBuilder();
        for (KnowledgeChunk chunk : chunks) {
            payload.append(objectMapper.writeValueAsString(Map.of(
                    "index", Map.of(
                            "_index", indexName,
                            "_id", chunk.getId()
                    )
            ))).append('\n');
            payload.append(objectMapper.writeValueAsString(Map.of(
                    "id", chunk.getId(),
                    "title", chunk.getTitle(),
                    "destination", chunk.getDestination(),
                    "content", chunk.getContent(),
                    "keywords", chunk.getKeywords()
            ))).append('\n');
        }

        HttpResponseData response = execute(
                "POST",
                baseUrl + "/_bulk?refresh=true",
                "application/x-ndjson",
                payload.toString(),
                15000);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Elasticsearch bulk failed with status " + response.statusCode());
        }
    }

    private HttpResponseData execute(String method,
                                     String url,
                                     String contentType,
                                     String body,
                                     int timeoutMillis) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestMethod(method);
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        if (body != null) {
            connection.setDoOutput(true);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body.getBytes(StandardCharsets.UTF_8));
            }
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
        return new HttpResponseData(statusCode, responseBody);
    }

    private List<String> array(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
            return values;
        }
        values.add(node.asText());
        return values;
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record HttpResponseData(int statusCode, String body) {
    }
}

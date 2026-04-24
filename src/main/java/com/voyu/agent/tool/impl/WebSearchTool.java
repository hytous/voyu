package com.voyu.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.voyu.agent.tool.ToolCapabilityType;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WebSearchTool implements TravelTool {

    private static final ToolTemplate TEMPLATE = ToolTemplate.local(
            "web.search",
            "网页搜索",
            "Search current Chinese web information for travel planning via Bocha.",
            ToolCapabilityType.WEB_SEARCH,
            true,
            true,
            ToolTemplate.jsonObjectSchema("query", "destination", "preferences", "taskName"));

    private final DomesticHttpClient httpClient;
    private final String bochaApiKey;

    public WebSearchTool(DomesticHttpClient httpClient,
                         @Value("${voyu.domestic.bocha.api-key:${BOCHA_API_KEY:}}") String bochaApiKey) {
        this.httpClient = httpClient;
        this.bochaApiKey = bochaApiKey;
    }

    @Override
    public ToolTemplate template() {
        return TEMPLATE;
    }

    @Override
    public String name() {
        return "web.search";
    }

    @Override
    public String description() {
        return "Search current Chinese web information for travel planning via Bocha.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        String query = String.valueOf(input.getOrDefault("query", ""));
        if (!StringUtils.hasText(query)) {
            query = String.join(" ",
                    String.valueOf(input.getOrDefault("destination", "")),
                    String.valueOf(input.getOrDefault("preferences", "")),
                    String.valueOf(input.getOrDefault("taskName", ""))).trim();
        }
        if (!StringUtils.hasText(bochaApiKey)) {
            return Map.of("error", "BOCHA_API_KEY 未配置");
        }
        if (!StringUtils.hasText(query)) {
            return Map.of("error", "web.search 缺少 query");
        }

        try {
            int count = parseCount(input.get("count"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("count", count);
            body.put("summary", true);

            JsonNode root = httpClient.postJson(
                    "https://api.bochaai.com/v1/web-search",
                    body,
                    Map.of("Authorization", "Bearer " + bochaApiKey)
            );
            List<Map<String, Object>> results = extractResults(root);
            if (results.isEmpty()) {
                return Map.of(
                        "error", "Bocha search returned no usable results",
                        "query", query,
                        "rawStatus", root.path("code").asText(root.path("status").asText(""))
                );
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", query);
            payload.put("source", "bocha");
            payload.put("results", results);
            payload.put("summary", "博查实时搜索返回 " + results.size() + " 条结果，可用于核对开放状态、近期攻略和活动信息。");
            return payload;
        } catch (Exception ex) {
            return Map.of(
                    "error", "Bocha search failed: " + ex.getMessage(),
                    "query", query
            );
        }
    }

    private List<Map<String, Object>> extractResults(JsonNode root) {
        JsonNode candidates = root.path("data").path("webPages").path("value");
        if (!candidates.isArray()) {
            candidates = root.path("webPages").path("value");
        }
        if (!candidates.isArray()) {
            candidates = root.path("data").path("value");
        }
        if (!candidates.isArray()) {
            candidates = root.path("results");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        if (!candidates.isArray()) {
            return results;
        }
        for (JsonNode item : candidates) {
            String title = firstText(item, "name", "title");
            String url = firstText(item, "url", "displayUrl");
            String snippet = firstText(item, "snippet", "summary", "description");
            if (!StringUtils.hasText(title) && !StringUtils.hasText(url)) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("title", title);
            result.put("url", url);
            result.put("snippet", snippet);
            result.put("siteName", firstText(item, "siteName", "site"));
            result.put("date", firstText(item, "dateLastCrawled", "datePublished", "date"));
            results.add(result);
            if (results.size() >= 10) {
                break;
            }
        }
        return results;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private int parseCount(Object value) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 10));
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(String.valueOf(value)), 10));
        } catch (Exception ignored) {
            return 6;
        }
    }
}

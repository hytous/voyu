package com.voyu.agent.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ToolResultInterpreter {

    public boolean isUsable(ToolTemplate template, Map<String, Object> rawResult) {
        if (rawResult == null || rawResult.isEmpty() || rawResult.containsKey("error")) {
            return false;
        }
        return switch (template.capabilityType()) {
            case USER_PROFILE -> rawResult.get("destination") != null || rawResult.get("preferences") != null;
            case WEATHER_LOOKUP -> hasText(rawResult.get("summary"));
            case POI_SEARCH -> rawResult.get("pois") instanceof List<?> list && !list.isEmpty();
            case ROUTE_PLAN -> hasText(rawResult.get("summary"));
            case RAG_RETRIEVAL -> rawResult.get("hits") instanceof List<?> list && !list.isEmpty();
            case MEMORY_WRITE -> Boolean.TRUE.equals(rawResult.get("cleared")) || Boolean.TRUE.equals(rawResult.get("success"));
            case WEB_SEARCH -> rawResult.get("results") instanceof List<?> list && !list.isEmpty();
            case BUDGET_AUDIT -> hasText(rawResult.get("summary"));
            case GENERIC_READ -> !rawResult.isEmpty();
            case GENERIC_WRITE -> Boolean.TRUE.equals(rawResult.get("success")) || !rawResult.isEmpty();
        };
    }

    public String buildObservation(ToolTemplate template, Map<String, Object> rawResult) {
        return switch (template.capabilityType()) {
            case USER_PROFILE -> """
                    用户画像聚合：
                    - 目的地：%s
                    - 出发地：%s
                    - 行程时长：%s
                    - 预算：%s
                    - 偏好：%s
                    """.formatted(
                    value(rawResult.get("destination")),
                    value(rawResult.get("departure")),
                    value(rawResult.get("travelDays")),
                    value(rawResult.get("budget")),
                    value(rawResult.get("preferences")));
            case WEATHER_LOOKUP -> """
                    天气规划结论：
                    - 目的地：%s
                    - 时间范围：%s
                    - 摘要：%s
                    - 行程提示：%s
                    """.formatted(
                    value(rawResult.get("destination")),
                    value(rawResult.get("dateRange")),
                    value(rawResult.get("summary")),
                    value(rawResult.get("planningHint")));
            case POI_SEARCH -> """
                    POI 检索结果：
                    - 目的地：%s
                    - 推荐点位：%s
                    - 聚类提示：%s
                    """.formatted(
                    value(rawResult.get("destination")),
                    joinList(rawResult.get("pois")),
                    value(rawResult.get("groupingHint")));
            case ROUTE_PLAN -> """
                    路线估算结果：
                    - 起点：%s
                    - 终点：%s
                    - 摘要：%s
                    """.formatted(
                    value(rawResult.get("origin")),
                    value(rawResult.get("destination")),
                    value(rawResult.get("summary")));
            case RAG_RETRIEVAL -> {
                Object hits = rawResult.get("hits");
                String topHits = "";
                if (hits instanceof List<?> list) {
                    topHits = list.stream()
                            .limit(3)
                            .map(this::formatKnowledgeHit)
                            .collect(Collectors.joining("\n"));
                }
                yield """
                        RAG 检索结果：
                        - 原始查询：%s
                        - 重写查询：%s
                        - Top 命中：
                        %s
                        """.formatted(
                        value(rawResult.get("query")),
                        value(rawResult.get("rewrittenQuery")),
                        indent(topHits.isBlank() ? "无" : topHits, "  "));
            }
            case MEMORY_WRITE -> """
                    会话记忆写操作：
                    - 工具：%s
                    - sessionId：%s
                    - 结果：%s
                    """.formatted(
                    template.name(),
                    value(rawResult.get("sessionId")),
                    value(rawResult.getOrDefault("message", rawResult.get("status"))));
            case WEB_SEARCH -> {
                Object results = rawResult.get("results");
                String topResults = "";
                if (results instanceof List<?> list) {
                    topResults = list.stream()
                            .limit(3)
                            .map(this::formatSearchResult)
                            .collect(Collectors.joining("\n"));
                }
                yield """
                        实时网页搜索：
                        - 查询：%s
                        - 摘要：%s
                        - Top results：
                        %s
                        """.formatted(
                        value(rawResult.get("query")),
                        value(rawResult.get("summary")),
                        indent(topResults.isBlank() ? "无" : topResults, "  "));
            }
            case BUDGET_AUDIT -> """
                    预算审查结论：
                    - 预算：%s
                    - 摘要：%s
                    """.formatted(
                    value(rawResult.get("budget")),
                    value(rawResult.get("summary")));
            case GENERIC_READ, GENERIC_WRITE -> rawResult.entrySet().stream()
                    .map(entry -> "%s: %s".formatted(entry.getKey(), value(entry.getValue())))
                    .collect(Collectors.joining("\n"));
        };
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private String formatKnowledgeHit(Object rawHit) {
        if (!(rawHit instanceof Map<?, ?> hit)) {
            return "- " + value(rawHit);
        }
        return Stream.of(
                        "- 标题：" + value(hit.get("title")),
                        "  目的地：" + value(hit.get("destination")),
                        "  来源：" + value(hit.get("source")),
                        "  分数：" + value(hit.get("score")),
                        "  摘要：" + clip(value(hit.get("content")), 180))
                .collect(Collectors.joining("\n"));
    }

    private String formatSearchResult(Object rawResult) {
        if (!(rawResult instanceof Map<?, ?> result)) {
            return "- " + value(rawResult);
        }
        return Stream.of(
                        "- 标题：" + value(result.get("title")),
                        "  链接：" + value(result.get("url")),
                        "  摘要：" + clip(value(result.get("snippet")), 160))
                .collect(Collectors.joining("\n"));
    }

    private String joinList(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(this::value).collect(Collectors.joining("、"));
        }
        return this.value(value);
    }

    private String indent(String value, String prefix) {
        return value.lines()
                .map(line -> prefix + line)
                .collect(Collectors.joining("\n"));
    }

    private String value(Object value) {
        if (value == null) {
            return "未提供";
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::value).collect(Collectors.joining("、"));
        }
        return String.valueOf(value);
    }

    private String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim() + "...";
    }
}

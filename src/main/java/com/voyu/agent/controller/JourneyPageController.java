package com.voyu.agent.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyu.agent.model.api.TravelChatRequest;
import com.voyu.agent.model.history.ConversationEventRecord;
import com.voyu.agent.model.history.TravelConversationDocument;
import com.voyu.agent.service.history.ConversationHistoryService;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
public class JourneyPageController {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final ConversationHistoryService conversationHistoryService;
    private final ObjectMapper objectMapper;
    private final Parser markdownParser;
    private final HtmlRenderer markdownRenderer;

    public JourneyPageController(ConversationHistoryService conversationHistoryService,
                                 ObjectMapper objectMapper) {
        this.conversationHistoryService = conversationHistoryService;
        this.objectMapper = objectMapper;
        this.markdownParser = Parser.builder().build();
        this.markdownRenderer = HtmlRenderer.builder()
                .escapeHtml(true)
                .sanitizeUrls(true)
                .build();
    }

    @GetMapping("/travel/journey")
    public String journeyForm(Model model) {
        TravelChatRequest request = new TravelChatRequest();
        request.setUserId("voyu-web");
        model.addAttribute("travelBrief", request);
        model.addAttribute("templateCards", templateCards());
        return "journey-form";
    }

    @PostMapping("/travel/journey/plan")
    public String journeyProcessing(@ModelAttribute("travelBrief") TravelChatRequest form, Model model) {
        TravelChatRequest request = normalize(form);
        model.addAttribute("travelBrief", request);
        model.addAttribute("briefSummary", buildBriefSummary(request));
        model.addAttribute("requestJson", toJson(request));
        return "journey-processing";
    }

    @GetMapping("/travel/journey/{sessionId}")
    public String journeyPlan(@PathVariable String sessionId, Model model) {
        TravelConversationDocument document = conversationHistoryService.findSession(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Journey session not found"));

        List<ConversationEventRecord> events = sortEvents(conversationEvents(document));
        Map<String, Object> taskBook = latestPayload(events, "TASK_BOOK");
        Map<String, String> taskStatuses = buildTaskStatuses(events);
        Map<String, Object> requestSnapshot = defaultMap(document.getRequestSnapshot());

        model.addAttribute("sessionId", document.getSessionId());
        model.addAttribute("status", text(document.getStatus(), "UNKNOWN"));
        model.addAttribute("statusLabel", statusLabel(document.getStatus()));
        model.addAttribute("statusTone", statusTone(document.getStatus()));
        model.addAttribute("requestSnapshot", requestSnapshot);
        model.addAttribute("briefSummary", buildBriefSummary(requestSnapshot));
        model.addAttribute("planThought", extractPlanThought(events));
        model.addAttribute("mission", text(taskBook.get("mission"), "等待生成任务书"));
        model.addAttribute("tasks", buildTaskViews(taskBook, taskStatuses));
        model.addAttribute("insights", buildInsightViews(events));
        model.addAttribute("knowledgeHits", buildKnowledgeHits(events));
        model.addAttribute("warnings", buildWarnings(document, events));
        model.addAttribute("timeline", buildTimeline(events));
        model.addAttribute("finalAnswerHtml", renderMarkdown(document.getFinalAnswer()));
        model.addAttribute("finalAnswerText", text(document.getFinalAnswer(), "当前会话还没有生成最终方案。"));
        model.addAttribute("updatedAtText", formatTime(document.getUpdatedAt()));
        model.addAttribute("completedAtText", formatTime(document.getCompletedAt()));
        return "journey-plan";
    }

    private TravelChatRequest normalize(TravelChatRequest form) {
        TravelChatRequest request = new TravelChatRequest();
        request.setSessionId(text(form.getSessionId(), UUID.randomUUID().toString()));
        request.setUserId(text(form.getUserId(), "voyu-web"));
        request.setDestination(trim(form.getDestination()));
        request.setDeparture(trim(form.getDeparture()));
        request.setTravelDays(trim(form.getTravelDays()));
        request.setBudget(trim(form.getBudget()));
        request.setPreferences(trim(form.getPreferences()));
        request.setMessage(text(trim(form.getMessage()), buildFallbackMessage(form)));
        return request;
    }

    private String buildFallbackMessage(TravelChatRequest form) {
        List<String> parts = new ArrayList<>();
        parts.add("请根据以下信息生成一份旅行规划");
        addPart(parts, "目的地", form.getDestination());
        addPart(parts, "出发地", form.getDeparture());
        addPart(parts, "行程时长", form.getTravelDays());
        addPart(parts, "预算", form.getBudget());
        addPart(parts, "偏好", form.getPreferences());
        return String.join("；", parts) + "。";
    }

    private void addPart(List<String> parts, String label, String value) {
        String normalized = trim(value);
        if (normalized != null) {
            parts.add(label + "：" + normalized);
        }
    }

    private List<Map<String, String>> templateCards() {
        return List.of(
                templateCard(
                        "osaka-food",
                        "大阪 4 天美食动漫",
                        "五一从上海去大阪 4 天，预算 7000，喜欢动漫、美食和轻松步行。",
                        "大阪",
                        "上海",
                        "4 天",
                        "7000",
                        "动漫、美食、轻松步行"
                ),
                templateCard(
                        "tokyo-family",
                        "东京家庭慢游",
                        "6 月中旬带父母去东京 5 天，预算 12000，希望交通方便、节奏舒缓。",
                        "东京",
                        "杭州",
                        "5 天",
                        "12000",
                        "家庭出行、轻松行程、购物"
                ),
                templateCard(
                        "kyoto-photo",
                        "京都摄影慢游",
                        "秋天去京都 3 天，想拍寺庙、街区和安静咖啡馆，尽量减少折返。",
                        "京都",
                        "深圳",
                        "3 天",
                        "6500",
                        "摄影、寺庙、咖啡、慢游"
                )
        );
    }

    private Map<String, String> templateCard(String key,
                                             String title,
                                             String message,
                                             String destination,
                                             String departure,
                                             String travelDays,
                                             String budget,
                                             String preferences) {
        Map<String, String> card = new LinkedHashMap<>();
        card.put("key", key);
        card.put("title", title);
        card.put("message", message);
        card.put("destination", destination);
        card.put("departure", departure);
        card.put("travelDays", travelDays);
        card.put("budget", budget);
        card.put("preferences", preferences);
        return card;
    }

    private List<Map<String, String>> buildBriefSummary(TravelChatRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("destination", request.getDestination());
        snapshot.put("departure", request.getDeparture());
        snapshot.put("travelDays", request.getTravelDays());
        snapshot.put("budget", request.getBudget());
        snapshot.put("preferences", request.getPreferences());
        snapshot.put("userId", request.getUserId());
        return buildBriefSummary(snapshot);
    }

    private List<Map<String, String>> buildBriefSummary(Map<String, Object> snapshot) {
        List<Map<String, String>> rows = new ArrayList<>();
        addSummary(rows, "目的地", snapshot.get("destination"));
        addSummary(rows, "出发地", snapshot.get("departure"));
        addSummary(rows, "行程天数", snapshot.get("travelDays"));
        addSummary(rows, "预算", snapshot.get("budget"));
        addSummary(rows, "偏好", snapshot.get("preferences"));
        addSummary(rows, "用户标识", snapshot.get("userId"));
        return rows;
    }

    private void addSummary(List<Map<String, String>> rows, String label, Object value) {
        String normalized = trim(text(value, null));
        if (normalized == null) {
            return;
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", normalized);
        rows.add(row);
    }

    private List<ConversationEventRecord> sortEvents(List<ConversationEventRecord> source) {
        return source == null ? List.of() : source.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing((ConversationEventRecord event) -> event.getTimestamp() == null ? Instant.EPOCH : event.getTimestamp())
                        .thenComparing(event -> event.getRound() == null ? 0 : event.getRound()))
                .toList();
    }

    private Map<String, Object> latestPayload(List<ConversationEventRecord> events, String eventType) {
        for (int index = events.size() - 1; index >= 0; index--) {
            ConversationEventRecord event = events.get(index);
            if (eventType.equals(event.getEventType()) && event.getPayload() != null) {
                return event.getPayload();
            }
        }
        return Map.of();
    }

    private Map<String, String> buildTaskStatuses(List<ConversationEventRecord> events) {
        Map<String, String> statuses = new LinkedHashMap<>();
        for (ConversationEventRecord event : events) {
            if (!"TASK_STATUS".equals(event.getEventType())) {
                continue;
            }
            Map<String, Object> payload = event.getPayload();
            if (payload == null) {
                continue;
            }
            String taskId = text(payload.get("taskId"), null);
            if (taskId != null) {
                statuses.put(taskId, text(payload.get("status"), "UNKNOWN"));
            }
        }
        return statuses;
    }

    private String extractPlanThought(List<ConversationEventRecord> events) {
        String fallback = "Voyu 会先抽取用户画像，再生成任务书并调度工具执行。";
        for (int index = events.size() - 1; index >= 0; index--) {
            ConversationEventRecord event = events.get(index);
            if ("PLAN_DRAFT".equals(event.getEventType())) {
                return text(defaultMap(event.getPayload()).get("thought"), fallback);
            }
        }
        for (int index = events.size() - 1; index >= 0; index--) {
            ConversationEventRecord event = events.get(index);
            if ("THOUGHT".equals(event.getEventType())) {
                return text(defaultMap(event.getPayload()).get("message"), fallback);
            }
        }
        return fallback;
    }

    private List<Map<String, Object>> buildTaskViews(Map<String, Object> taskBook, Map<String, String> taskStatuses) {
        List<Map<String, Object>> tasks = objectMapper.convertValue(taskBook.getOrDefault("tasks", List.of()), new TypeReference<List<Map<String, Object>>>() {
        });
        if (tasks.isEmpty()) {
            return List.of();
        }

        return tasks.stream()
                .map(task -> {
                    String taskId = text(task.get("taskId"), "unknown");
                    List<String> dependsOn = objectMapper.convertValue(task.getOrDefault("dependsOn", List.of()), new TypeReference<List<String>>() {
                    });
                    Map<String, Object> input = defaultMap(task.get("input"));
                    String status = taskStatuses.getOrDefault(taskId, "PENDING");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("taskId", taskId);
                    row.put("name", text(task.get("name"), taskId));
                    row.put("objective", text(task.get("objective"), "未补充任务目标"));
                    row.put("toolLabel", toolLabel(text(task.get("toolName"), "")));
                    row.put("parallelGroup", text(task.get("parallelGroup"), "g0"));
                    row.put("dependsOnText", dependsOn.isEmpty() ? "无" : String.join(", ", dependsOn));
                    row.put("statusLabel", statusLabel(status));
                    row.put("statusTone", statusTone(status));
                    row.put("inputPreview", buildInputPreview(input));
                    return row;
                })
                .toList();
    }

    private String buildInputPreview(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "未提供额外输入";
        }
        List<String> parts = new ArrayList<>();
        addInputPreview(parts, "目的地", input.get("destination"));
        addInputPreview(parts, "日期范围", input.get("dateRange"));
        addInputPreview(parts, "分类", input.get("category"));
        addInputPreview(parts, "预算", input.get("budget"));
        addInputPreview(parts, "偏好", input.get("preferences"));
        addInputPreview(parts, "关键词", input.get("keywords"));
        return parts.isEmpty() ? "未提供额外输入" : String.join(" / ", parts);
    }

    private void addInputPreview(List<String> parts, String label, Object value) {
        String normalized = trim(text(value, null));
        if (normalized != null) {
            parts.add(label + "：" + normalized);
        }
    }

    private List<Map<String, Object>> buildInsightViews(List<ConversationEventRecord> events) {
        Map<String, Map<String, Object>> latestResults = new LinkedHashMap<>();
        for (ConversationEventRecord event : events) {
            if (!"TOOL_RESULT".equals(event.getEventType()) || event.getPayload() == null) {
                continue;
            }
            String toolName = text(event.getPayload().get("toolName"), null);
            if (toolName != null) {
                latestResults.put(toolName, defaultMap(event.getPayload()));
            }
        }

        List<String> orderedTools = List.of(
                "profile.lookup",
                "weather.lookup",
                "map.poi.search",
                "budget.audit",
                "rag.travel.knowledge"
        );

        List<Map<String, Object>> cards = new ArrayList<>();
        for (String toolName : orderedTools) {
            Map<String, Object> payload = latestResults.get(toolName);
            if (payload == null) {
                continue;
            }
            Map<String, Object> result = defaultMap(payload.get("result"));
            List<String> items = switch (toolName) {
                case "profile.lookup" -> List.of(
                        "目的地：" + text(result.get("destination"), "未说明"),
                        "出发地：" + text(result.get("departure"), "未说明"),
                        "天数：" + text(result.get("travelDays"), "未说明"),
                        "预算：" + text(result.get("budget"), "未说明"),
                        "偏好：" + text(result.get("preferences"), "未说明")
                );
                case "weather.lookup" -> compactList(
                        "天气摘要：" + text(result.get("summary"), "未返回"),
                        "行程提示：" + text(result.get("planningHint"), "未返回")
                );
                case "map.poi.search" -> compactList(
                        "候选点位：" + text(result.get("pois"), "未返回"),
                        "聚类建议：" + text(result.get("groupingHint"), "未返回")
                );
                case "budget.audit" -> compactList(
                        "预算：" + text(result.get("budget"), "未说明"),
                        "审查摘要：" + text(result.get("summary"), "未返回")
                );
                case "rag.travel.knowledge" -> compactList(
                        "重写查询：" + text(result.get("rewrittenQuery"), "未返回"),
                        "Top 命中：" + buildKnowledgeHeadline(result.get("hits"))
                );
                default -> List.of("结果：" + text(result, "未返回"));
            };

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("title", toolLabel(toolName));
            card.put("summary", insightSummary(toolName, result));
            card.put("items", items);
            cards.add(card);
        }
        return cards;
    }

    private String insightSummary(String toolName, Map<String, Object> result) {
        return switch (toolName) {
            case "profile.lookup" -> "用户画像被固化为本次任务书的基础约束。";
            case "weather.lookup" -> text(result.get("summary"), "天气策略已完成。");
            case "map.poi.search" -> "候选景点与区域聚类已经产出。";
            case "budget.audit" -> text(result.get("summary"), "预算审查已完成。");
            case "rag.travel.knowledge" -> "RAG 双路召回的知识片段已经重排并注入。";
            default -> "工具执行完成。";
        };
    }

    private String buildKnowledgeHeadline(Object hits) {
        List<Map<String, Object>> entries = objectMapper.convertValue(hits, new TypeReference<List<Map<String, Object>>>() {
        });
        if (entries.isEmpty()) {
            return "无命中";
        }
        return entries.stream()
                .limit(3)
                .map(hit -> text(hit.get("title"), "未命名片段"))
                .collect(Collectors.joining(" / "));
    }

    private List<Map<String, Object>> buildKnowledgeHits(List<ConversationEventRecord> events) {
        Map<String, Object> payload = latestToolResult(events, "rag.travel.knowledge");
        Map<String, Object> result = defaultMap(payload.get("result"));
        List<Map<String, Object>> hits = objectMapper.convertValue(result.getOrDefault("hits", List.of()),
                new TypeReference<List<Map<String, Object>>>() {
                });
        return hits.stream()
                .limit(10)
                .map(hit -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("title", text(hit.get("title"), "未命名知识片段"));
                    row.put("source", text(hit.get("source"), "knowledge"));
                    row.put("destination", text(hit.get("destination"), "未标注"));
                    row.put("score", formatScore(hit.get("score")));
                    row.put("content", text(hit.get("content"), "没有内容摘要。"));
                    return row;
                })
                .toList();
    }

    private Map<String, Object> latestToolResult(List<ConversationEventRecord> events, String toolName) {
        for (int index = events.size() - 1; index >= 0; index--) {
            ConversationEventRecord event = events.get(index);
            if (!"TOOL_RESULT".equals(event.getEventType()) || event.getPayload() == null) {
                continue;
            }
            if (toolName.equals(text(event.getPayload().get("toolName"), null))) {
                return event.getPayload();
            }
        }
        return Map.of();
    }

    private List<Map<String, String>> buildWarnings(TravelConversationDocument document, List<ConversationEventRecord> events) {
        List<String> messages = new ArrayList<>();
        for (ConversationEventRecord event : events) {
            if (!"WARNING".equals(event.getEventType()) || event.getPayload() == null) {
                continue;
            }
            String message = trim(text(event.getPayload().get("message"), null));
            if (message != null && !messages.contains(message)) {
                messages.add(message);
            }
        }

        String errorMessage = trim(document.getErrorMessage());
        if (errorMessage != null && !messages.contains(errorMessage)) {
            messages.add(errorMessage);
        }

        return messages.stream()
                .map(message -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("message", message);
                    return row;
                })
                .toList();
    }

    private List<ConversationEventRecord> conversationEvents(TravelConversationDocument document) {
        if (document.getTraceEvents() != null && !document.getTraceEvents().isEmpty()) {
            return document.getTraceEvents();
        }
        if (document.getEvents() != null) {
            return document.getEvents();
        }
        return List.of();
    }

    private List<Map<String, Object>> buildTimeline(List<ConversationEventRecord> events) {
        return events.stream()
                .map(event -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("label", eventLabel(event.getEventType()));
                    row.put("summary", describeEvent(event));
                    row.put("time", formatTime(event.getTimestamp()));
                    row.put("payloadJson", prettyJson(event.getPayload()));
                    row.put("tone", eventTone(event.getEventType()));
                    return row;
                })
                .toList();
    }

    private String describeEvent(ConversationEventRecord event) {
        Map<String, Object> payload = defaultMap(event.getPayload());
        return switch (text(event.getEventType(), "UNKNOWN")) {
            case "MEMORY" -> text(payload.get("summary"), "历史记忆已加载。");
            case "THOUGHT" -> text(payload.get("message"), "进入主循环。");
            case "PLAN_DRAFT" -> text(payload.get("thought"), "规划器产出了新的思路。");
            case "TASK_BOOK" -> text(payload.get("mission"), "任务书已生成。");
            case "TASK_STATUS" -> text(payload.get("taskName"), text(payload.get("taskId"), "任务")) + " -> " + statusLabel(text(payload.get("status"), "UNKNOWN"));
            case "TOOL_CALL" -> toolLabel(text(payload.get("toolName"), "")) + " 已开始执行";
            case "TOOL_RESULT" -> toolLabel(text(payload.get("toolName"), "")) + " 已返回结果";
            case "WARNING" -> text(payload.get("message"), "执行过程中出现异常。");
            case "FINAL_ANSWER" -> "最终旅行方案已生成";
            default -> "事件已记录";
        };
    }

    private String eventTone(String eventType) {
        return switch (text(eventType, "")) {
            case "WARNING" -> "error";
            case "FINAL_ANSWER" -> "live";
            case "TOOL_CALL", "TASK_STATUS" -> "busy";
            default -> "muted";
        };
    }

    private String eventLabel(String eventType) {
        return switch (text(eventType, "")) {
            case "MEMORY" -> "会话记忆";
            case "THOUGHT" -> "主循环思考";
            case "PLAN_DRAFT" -> "规划草案";
            case "TASK_BOOK" -> "任务书";
            case "TASK_STATUS" -> "任务状态";
            case "TOOL_CALL" -> "工具调用";
            case "TOOL_RESULT" -> "工具结果";
            case "WARNING" -> "告警";
            case "FINAL_ANSWER" -> "最终方案";
            default -> text(eventType, "未知事件");
        };
    }

    private String statusTone(String status) {
        return switch (text(status, "").toUpperCase(Locale.ROOT)) {
            case "COMPLETED", "DONE" -> "live";
            case "RUNNING", "RETRYING" -> "busy";
            case "FAILED", "ABORTED" -> "error";
            case "UNKNOWN" -> "warn";
            default -> "muted";
        };
    }

    private String statusLabel(String status) {
        return switch (text(status, "").toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> "已完成";
            case "DONE" -> "完成";
            case "RUNNING" -> "执行中";
            case "RETRYING" -> "重试中";
            case "SKIPPED" -> "已跳过";
            case "FAILED" -> "失败";
            case "ABORTED" -> "已中止";
            case "PENDING" -> "待执行";
            default -> "未知";
        };
    }

    private String toolLabel(String toolName) {
        return switch (toolName) {
            case "profile.lookup" -> "用户画像";
            case "weather.lookup" -> "天气检查";
            case "map.poi.search" -> "景点 POI 检索";
            case "rag.travel.knowledge" -> "旅游知识检索";
            case "budget.audit" -> "预算审查";
            default -> text(toolName, "未命名工具");
        };
    }

    private String prettyJson(Map<String, Object> payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(defaultMap(payload));
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String renderMarkdown(String markdown) {
        String normalized = trim(markdown);
        if (normalized == null) {
            return "";
        }
        return markdownRenderer.render(markdownParser.parse(normalized));
    }

    private String toJson(TravelChatRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize travel request", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> defaultMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()),
                            Map.Entry::getValue,
                            (left, right) -> right,
                            LinkedHashMap::new
                    ));
        }
        return Map.of();
    }

    private List<String> compactList(String... values) {
        return java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.endsWith("未返回") && !value.endsWith("未说明"))
                .toList();
    }

    private String formatScore(Object value) {
        if (value instanceof Number number) {
            return "%.4f".formatted(number.doubleValue());
        }
        return text(value, "-");
    }

    private String formatTime(Instant instant) {
        return instant == null ? "-" : TIME_FORMATTER.format(instant);
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof List<?> list) {
            String joined = list.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .collect(Collectors.joining("、"));
            return joined.isBlank() ? fallback : joined;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? fallback : string;
    }
}

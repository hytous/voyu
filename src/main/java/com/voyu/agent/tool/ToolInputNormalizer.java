package com.voyu.agent.tool;

import com.voyu.agent.model.agent.ConversationState;
import com.voyu.agent.model.agent.TaskItem;
import com.voyu.agent.model.api.TravelChatRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

@Component
public class ToolInputNormalizer {

    public Map<String, Object> normalize(TaskItem task, ConversationState state) {
        Map<String, Object> raw = task.getInput() == null ? Map.of() : task.getInput();
        TravelChatRequest request = state.getRequest();

        if ("pdf.export".equals(task.getToolName())) {
            return normalizePdfExportInput(raw, request, state.getSessionId(), task);
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("sessionId", state.getSessionId());
        normalized.put("userId", request.getUserId());
        normalized.put("destination", firstNonBlank(raw, request.getDestination(), "destination", "location", "city"));
        normalized.put("departure", firstNonBlank(raw, request.getDeparture(), "departure", "origin", "from"));
        normalized.put("travelDays", firstNonBlank(raw, request.getTravelDays(), "travelDays", "days", "duration"));
        normalized.put("budget", firstNonBlank(raw, request.getBudget(), "budget", "amount"));
        normalized.put("preferences", firstNonBlank(raw, request.getPreferences(), "preferences", "interests", "tags"));
        normalized.put("dateRange", firstNonBlank(raw, null, "dateRange", "dates"));
        normalized.put("category", firstNonBlank(raw, null, "category", "scene"));
        normalized.put("keywords", firstNonBlank(raw, null, "keywords", "keyword"));
        normalized.put("query", resolveQuery(task, raw, request));
        normalized.put("taskName", task.getName());
        normalized.put("objective", task.getObjective());

        if ("weather.lookup".equals(task.getToolName()) && normalized.get("destination") == null) {
            normalized.put("destination", firstNonBlank(raw, null, "location"));
        }

        if ("map.poi.search".equals(task.getToolName())) {
            if (normalized.get("query") == null) {
                normalized.put("query", buildFreeformQuery(normalized));
            }
            if (normalized.get("keywords") == null && normalized.get("query") != null) {
                normalized.put("keywords", normalized.get("query"));
            }
        }

        if ("rag.travel.knowledge".equals(task.getToolName()) && normalized.get("query") == null) {
            normalized.put("query", buildFreeformQuery(normalized));
        }

        if ("web.search".equals(task.getToolName()) && normalized.get("query") == null) {
            normalized.put("query", buildFreeformQuery(normalized));
        }

        return normalized.entrySet().stream()
                .filter(entry -> isMeaningful(entry.getValue()))
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
    }

    private Map<String, Object> normalizePdfExportInput(Map<String, Object> raw,
                                                        TravelChatRequest request,
                                                        String sessionId,
                                                        TaskItem task) {
        String requestedSessionId = firstNonBlank(raw, null, "sessionId");
        String requestedTitle = firstNonBlank(raw, null, "title");
        String requestedUserRequest = firstNonBlank(raw, null, "userRequest", "request", "message", "query");
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("sessionId", requestedSessionId == null ? sessionId : requestedSessionId);
        normalized.put("title", requestedTitle == null ? task.getName() : requestedTitle);
        normalized.put("userRequest", requestedUserRequest == null ? request.getMessage() : requestedUserRequest);
        normalized.put("finalAnswer", firstNonBlank(raw, null, "finalAnswer", "answer"));
        normalized.put("messages", raw.get("messages"));
        normalized.put("outputDir", firstNonBlank(raw, null, "outputDir"));
        return normalized.entrySet().stream()
                .filter(entry -> isMeaningful(entry.getValue()))
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
    }

    private String resolveQuery(TaskItem task, Map<String, Object> raw, TravelChatRequest request) {
        String direct = firstNonBlank(raw, request.getMessage(), "query", "message", "prompt");
        if (direct != null) {
            return direct;
        }
        StringJoiner joiner = new StringJoiner(" ");
        addIfPresent(joiner, stringify(raw.get("location")));
        addIfPresent(joiner, stringify(raw.get("dateRange")));
        addIfPresent(joiner, stringify(raw.get("keywords")));
        addIfPresent(joiner, stringify(raw.get("category")));
        addIfPresent(joiner, task.getObjective());
        String combined = joiner.toString().trim();
        return combined.isBlank() ? null : combined;
    }

    private String buildFreeformQuery(Map<String, Object> normalized) {
        StringJoiner joiner = new StringJoiner(" ");
        addIfPresent(joiner, stringify(normalized.get("destination")));
        addIfPresent(joiner, stringify(normalized.get("preferences")));
        addIfPresent(joiner, stringify(normalized.get("keywords")));
        addIfPresent(joiner, stringify(normalized.get("category")));
        addIfPresent(joiner, stringify(normalized.get("objective")));
        String combined = joiner.toString().trim();
        return combined.isBlank() ? null : combined;
    }

    private String firstNonBlank(Map<String, Object> raw, String preferred, String... keys) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        for (String key : keys) {
            String candidate = stringify(raw.get(key));
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text.isBlank() ? null : text.trim();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::stringify)
                    .filter(Objects::nonNull)
                    .filter(item -> !item.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse(null);
        }
        return String.valueOf(value).trim();
    }

    private void addIfPresent(StringJoiner joiner, String value) {
        if (value != null && !value.isBlank()) {
            joiner.add(value);
        }
    }

    private boolean isMeaningful(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        return true;
    }
}

package com.voyu.agent.tool.impl;

import com.voyu.agent.service.memory.ConversationMemoryService;
import com.voyu.agent.tool.ToolCapabilityType;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RagMemoryClearTool implements TravelTool {

    private static final ToolTemplate TEMPLATE = ToolTemplate.local(
            "memory.rag.clear",
            "清理 RAG 记忆",
            "Clear stale or irrelevant session-level RAG knowledge from conversation memory.",
            ToolCapabilityType.MEMORY_WRITE,
            false,
            false,
            ToolTemplate.jsonObjectSchema("sessionId"));

    private final ConversationMemoryService conversationMemoryService;

    public RagMemoryClearTool(ConversationMemoryService conversationMemoryService) {
        this.conversationMemoryService = conversationMemoryService;
    }

    @Override
    public ToolTemplate template() {
        return TEMPLATE;
    }

    @Override
    public String name() {
        return "memory.rag.clear";
    }

    @Override
    public String description() {
        return "Clear stale or irrelevant session-level RAG knowledge from conversation memory.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        String sessionId = String.valueOf(input.getOrDefault("sessionId", "")).trim();
        if (!StringUtils.hasText(sessionId)) {
            return Map.of("error", "sessionId is required to clear rag memory.");
        }

        conversationMemoryService.clearSessionRag(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("cleared", true);
        result.put("rag", java.util.List.of());
        result.put("message", "当前 session 的 rag[] 已清除，后续不会自动重复检索。");
        return result;
    }
}

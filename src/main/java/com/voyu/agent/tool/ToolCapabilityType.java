package com.voyu.agent.tool;

import java.util.Locale;

public enum ToolCapabilityType {
    USER_PROFILE("profile_lookup"),
    WEATHER_LOOKUP("weather_lookup"),
    POI_SEARCH("poi_search"),
    ROUTE_PLAN("route_plan"),
    RAG_RETRIEVAL("rag_retrieval"),
    MEMORY_WRITE("memory_write"),
    WEB_SEARCH("web_search"),
    BUDGET_AUDIT("budget_audit"),
    GENERIC_READ("generic_read"),
    GENERIC_WRITE("generic_write");

    private final String code;

    ToolCapabilityType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ToolCapabilityType infer(String toolName, Boolean readOnlyHint) {
        String normalized = normalize(toolName);
        return switch (normalized) {
            case "profile.lookup" -> USER_PROFILE;
            case "weather.lookup" -> WEATHER_LOOKUP;
            case "map.poi.search" -> POI_SEARCH;
            case "map.route.plan" -> ROUTE_PLAN;
            case "rag.travel.knowledge" -> RAG_RETRIEVAL;
            case "memory.rag.clear" -> MEMORY_WRITE;
            case "web.search" -> WEB_SEARCH;
            case "budget.audit" -> BUDGET_AUDIT;
            default -> Boolean.TRUE.equals(readOnlyHint) ? GENERIC_READ : GENERIC_WRITE;
        };
    }

    private static String normalize(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
    }
}

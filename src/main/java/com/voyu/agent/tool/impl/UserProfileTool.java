package com.voyu.agent.tool.impl;

import com.voyu.agent.tool.ToolCapabilityType;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class UserProfileTool implements TravelTool {

    private static final ToolTemplate TEMPLATE = ToolTemplate.local(
            "profile.lookup",
            "用户画像分析",
            "Read user preferences and constraints from the current request.",
            ToolCapabilityType.USER_PROFILE,
            true,
            true,
            ToolTemplate.jsonObjectSchema("destination", "departure", "travelDays", "budget", "preferences", "query"));

    @Override
    public ToolTemplate template() {
        return TEMPLATE;
    }

    @Override
    public String name() {
        return "profile.lookup";
    }

    @Override
    public String description() {
        return "Read user preferences and constraints from the current request.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("destination", input.getOrDefault("destination", "未指定目的地"));
        profile.put("budget", input.getOrDefault("budget", "预算未说明"));
        profile.put("travelDays", input.getOrDefault("travelDays", "天数未说明"));
        profile.put("preferences", input.getOrDefault("preferences", "偏好未说明"));
        profile.put("departure", input.getOrDefault("departure", "出发地未说明"));
        profile.put("query", input.getOrDefault("query", "用户未补充更多描述"));
        return profile;
    }
}

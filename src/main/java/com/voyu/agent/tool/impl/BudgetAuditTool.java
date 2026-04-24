package com.voyu.agent.tool.impl;

import com.voyu.agent.tool.ToolCapabilityType;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BudgetAuditTool implements TravelTool {

    private static final ToolTemplate TEMPLATE = ToolTemplate.local(
            "budget.audit",
            "预算审查",
            "Check whether the rough route intensity matches the user's budget statement.",
            ToolCapabilityType.BUDGET_AUDIT,
            true,
            true,
            ToolTemplate.jsonObjectSchema("destination", "budget", "travelDays", "preferences"));

    @Override
    public ToolTemplate template() {
        return TEMPLATE;
    }

    @Override
    public String name() {
        return "budget.audit";
    }

    @Override
    public String description() {
        return "Check whether the rough route intensity matches the user's budget statement.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        String budget = String.valueOf(input.getOrDefault("budget", "预算未说明"));
        String destination = String.valueOf(input.getOrDefault("destination", "目的地"));
        String travelDays = String.valueOf(input.getOrDefault("travelDays", "未说明天数"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("budget", budget);
        result.put("summary", destination + " " + travelDays + " 当前为启发式预算审查：若预算敏感，优先同区域步行路线、地铁通票和免费景点。");
        return result;
    }
}

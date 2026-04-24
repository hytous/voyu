package com.voyu.agent.tool;

import com.voyu.agent.config.McpToolProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private final Map<String, TravelTool> tools;
    private final List<ToolTemplate> localToolTemplates;
    private final McpToolProperties mcpToolProperties;
    private final McpToolGateway mcpToolGateway;

    public ToolRegistry(List<TravelTool> toolList,
                        McpToolProperties mcpToolProperties,
                        McpToolGateway mcpToolGateway) {
        this.mcpToolProperties = mcpToolProperties;
        this.tools = toolList.stream()
                .filter(tool -> !mcpToolProperties.isExternalizedLocalTool(tool.name()))
                .collect(Collectors.toMap(TravelTool::name, Function.identity()));
        this.localToolTemplates = toolList.stream()
                .filter(tool -> !mcpToolProperties.isExternalizedLocalTool(tool.name()))
                .map(TravelTool::template)
                .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
                .toList();
        this.mcpToolGateway = mcpToolGateway;
    }

    public TravelTool get(String name) {
        TravelTool tool = tools.get(name);
        if (tool != null) {
            return tool;
        }
        if (mcpToolGateway.hasTool(name)) {
            return mcpToolGateway.toTravelTool(name);
        }
        throw new IllegalArgumentException("Unknown tool: " + name);
    }

    public List<String> listToolNames() {
        return java.util.stream.Stream.concat(
                        tools.keySet().stream(),
                        mcpToolGateway.listRemoteTools().stream().map(ToolTemplate::name))
                .sorted()
                .distinct()
                .toList();
    }

    public boolean hasLocalTool(String name) {
        return tools.containsKey(name);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name) || mcpToolGateway.hasTool(name);
    }

    public List<ToolTemplate> listLocalToolTemplates() {
        return localToolTemplates;
    }

    public List<ToolTemplate> listDiscoveredToolTemplates() {
        List<ToolTemplate> remoteTools = mcpToolGateway.listRemoteTools();
        java.util.stream.Stream<ToolTemplate> externalStream = remoteTools.isEmpty()
                ? mcpToolProperties.discoveredTools().stream()
                : remoteTools.stream();
        return mergeByName(java.util.stream.Stream.concat(localToolTemplates.stream(), externalStream).toList());
    }

    public ToolTemplate getToolTemplate(String name) {
        TravelTool local = tools.get(name);
        if (local != null) {
            return local.template();
        }
        return mcpToolGateway.findRemoteTool(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool template: " + name));
    }

    public boolean isParallelizable(String name) {
        return hasTool(name) && getToolTemplate(name).parallelizable();
    }

    private List<ToolTemplate> mergeByName(List<ToolTemplate> templates) {
        Map<String, ToolTemplate> merged = new java.util.LinkedHashMap<>();
        for (ToolTemplate template : templates) {
            merged.putIfAbsent(template.name(), template);
        }
        return merged.values().stream()
                .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
                .toList();
    }
}

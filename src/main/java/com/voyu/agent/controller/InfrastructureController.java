package com.voyu.agent.controller;

import com.voyu.agent.config.McpToolProperties;
import com.voyu.agent.service.infra.MiddlewareHealthService;
import com.voyu.agent.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/infrastructure")
public class InfrastructureController {

    private final MiddlewareHealthService middlewareHealthService;
    private final ToolRegistry toolRegistry;
    private final McpToolProperties mcpToolProperties;

    public InfrastructureController(MiddlewareHealthService middlewareHealthService,
                                    ToolRegistry toolRegistry,
                                    McpToolProperties mcpToolProperties) {
        this.middlewareHealthService = middlewareHealthService;
        this.toolRegistry = toolRegistry;
        this.mcpToolProperties = mcpToolProperties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return middlewareHealthService.inspect();
    }

    @GetMapping("/tool-catalog")
    public Map<String, Object> toolCatalog() {
        List<Map<String, Object>> discovered = toolRegistry.listDiscoveredToolTemplates().stream()
                .map(descriptor -> descriptor.toMap())
                .toList();

        List<Map<String, Object>> local = toolRegistry.listLocalToolTemplates().stream()
                .map(descriptor -> descriptor.toMap())
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", discovered.size());
        response.put("localTotal", local.size());
        response.put("mcpEnabled", mcpToolProperties.isEnabled());
        response.put("mcpProvider", mcpToolProperties.getProvider());
        response.put("externalizeDomesticTools", mcpToolProperties.isExternalizeDomesticTools());
        response.put("externalizedLocalTools", mcpToolProperties.getExternalizedLocalTools());
        response.put("localTools", local);
        response.put("allTools", discovered);
        return response;
    }
}

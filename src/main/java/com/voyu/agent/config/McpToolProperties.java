package com.voyu.agent.config;

import com.voyu.agent.tool.ToolCapabilityType;
import com.voyu.agent.tool.ToolSourceType;
import com.voyu.agent.tool.ToolTemplate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "voyu.mcp")
public class McpToolProperties {

    private boolean enabled;
    private boolean allowAllTools;
    private boolean externalizeDomesticTools;
    private String provider = "docker-desktop";
    private List<RemoteTool> tools = new ArrayList<>();
    private List<String> externalizedLocalTools = new ArrayList<>(List.of(
            "weather.lookup",
            "map.poi.search",
            "map.route.plan",
            "web.search"
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public boolean isAllowAllTools() {
        return allowAllTools;
    }

    public void setAllowAllTools(boolean allowAllTools) {
        this.allowAllTools = allowAllTools;
    }

    public boolean isExternalizeDomesticTools() {
        return externalizeDomesticTools;
    }

    public void setExternalizeDomesticTools(boolean externalizeDomesticTools) {
        this.externalizeDomesticTools = externalizeDomesticTools;
    }

    public List<String> getExternalizedLocalTools() {
        return externalizedLocalTools;
    }

    public void setExternalizedLocalTools(List<String> externalizedLocalTools) {
        this.externalizedLocalTools = externalizedLocalTools;
    }

    public boolean isExternalizedLocalTool(String toolName) {
        return enabled
                && externalizeDomesticTools
                && externalizedLocalTools != null
                && externalizedLocalTools.contains(toolName);
    }

    public List<RemoteTool> getTools() {
        return tools;
    }

    public void setTools(List<RemoteTool> tools) {
        this.tools = tools;
    }

    public List<ToolTemplate> discoveredTools() {
        if (!enabled || tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
                .filter(RemoteTool::isEnabled)
                .map(this::toDiscoveredToolTemplate)
                .toList();
    }

    public boolean isToolAllowed(String toolName) {
        if (!enabled) {
            return false;
        }
        if (allowAllTools || tools == null || tools.isEmpty()) {
            return true;
        }
        return tools.stream()
                .filter(RemoteTool::isEnabled)
                .map(RemoteTool::getName)
                .anyMatch(toolName::equals);
    }

    public static class RemoteTool {
        private String name;
        private String title;
        private String description;
        private String source;
        private String capabilityType;
        private String inputSchema;
        private String outputSchema;
        private Boolean readOnlyHint;
        private Boolean destructiveHint;
        private Boolean idempotentHint;
        private Boolean openWorldHint;
        private Boolean returnDirect;
        private boolean enabled = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getCapabilityType() {
            return capabilityType;
        }

        public void setCapabilityType(String capabilityType) {
            this.capabilityType = capabilityType;
        }

        public String getInputSchema() {
            return inputSchema;
        }

        public void setInputSchema(String inputSchema) {
            this.inputSchema = inputSchema;
        }

        public String getOutputSchema() {
            return outputSchema;
        }

        public void setOutputSchema(String outputSchema) {
            this.outputSchema = outputSchema;
        }

        public Boolean getReadOnlyHint() {
            return readOnlyHint;
        }

        public void setReadOnlyHint(Boolean readOnlyHint) {
            this.readOnlyHint = readOnlyHint;
        }

        public Boolean getDestructiveHint() {
            return destructiveHint;
        }

        public void setDestructiveHint(Boolean destructiveHint) {
            this.destructiveHint = destructiveHint;
        }

        public Boolean getIdempotentHint() {
            return idempotentHint;
        }

        public void setIdempotentHint(Boolean idempotentHint) {
            this.idempotentHint = idempotentHint;
        }

        public Boolean getOpenWorldHint() {
            return openWorldHint;
        }

        public void setOpenWorldHint(Boolean openWorldHint) {
            this.openWorldHint = openWorldHint;
        }

        public Boolean getReturnDirect() {
            return returnDirect;
        }

        public void setReturnDirect(Boolean returnDirect) {
            this.returnDirect = returnDirect;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    private ToolTemplate toDiscoveredToolTemplate(RemoteTool tool) {
        Boolean readOnlyHint = tool.getReadOnlyHint();
        ToolCapabilityType capabilityType = resolveCapabilityType(tool.getCapabilityType(), tool.getName(), readOnlyHint);
        return new ToolTemplate(
                tool.getName(),
                tool.getTitle(),
                tool.getDescription(),
                capabilityType,
                ToolSourceType.MCP,
                tool.getSource() == null || tool.getSource().isBlank() ? provider : tool.getSource(),
                false,
                readOnlyHint,
                tool.getDestructiveHint(),
                tool.getIdempotentHint(),
                tool.getOpenWorldHint(),
                tool.getReturnDirect(),
                Boolean.TRUE.equals(readOnlyHint),
                tool.getInputSchema(),
                tool.getOutputSchema(),
                Map.of(),
                List.of());
    }

    private ToolCapabilityType resolveCapabilityType(String configuredValue, String toolName, Boolean readOnlyHint) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return ToolCapabilityType.infer(toolName, readOnlyHint);
        }
        for (ToolCapabilityType candidate : ToolCapabilityType.values()) {
            if (candidate.code().equalsIgnoreCase(configuredValue) || candidate.name().equalsIgnoreCase(configuredValue)) {
                return candidate;
            }
        }
        return ToolCapabilityType.infer(toolName, readOnlyHint);
    }
}

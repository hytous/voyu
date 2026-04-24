package com.voyu.agent.config;

import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpBridgeConfig {

    @Bean
    @ConditionalOnProperty(prefix = "voyu.mcp", name = "enabled", havingValue = "true")
    public McpToolFilter voyuMcpToolFilter(McpToolProperties properties) {
        return (client, tool) -> properties.isToolAllowed(tool.name());
    }
}

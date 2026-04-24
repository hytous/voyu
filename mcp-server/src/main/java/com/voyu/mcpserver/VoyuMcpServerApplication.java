package com.voyu.mcpserver;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class VoyuMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoyuMcpServerApplication.class, args);
    }

    @Bean
    ToolCallbackProvider externalTravelToolCallbacks(ExternalTravelTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}

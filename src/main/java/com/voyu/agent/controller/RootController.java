package com.voyu.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RootController {

    @GetMapping("/meta")
    public Map<String, Object> index() {
        return Map.of(
                "success", true,
                "name", "voyu-agent",
                "message", "Voyu travel planning agent is running.",
                "endpoints", Map.of(
                        "agentHealth", "/api/travel-agent/health",
                        "agentStream", "/api/travel-agent/stream",
                        "infrastructureStatus", "/api/infrastructure/status"
                )
        );
    }
}

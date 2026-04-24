package com.voyu.agent.controller;

import com.voyu.agent.model.api.TravelChatRequest;
import com.voyu.agent.service.TravelAgentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/travel-agent")
public class TravelAgentController {

    private final TravelAgentService travelAgentService;

    public TravelAgentController(TravelAgentService travelAgentService) {
        this.travelAgentService = travelAgentService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("success", true, "name", "voyu-agent");
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody @Valid TravelChatRequest request) {
        return travelAgentService.streamPlan(request);
    }
}

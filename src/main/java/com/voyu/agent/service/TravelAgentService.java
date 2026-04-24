package com.voyu.agent.service;

import com.voyu.agent.model.api.TravelChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface TravelAgentService {

    SseEmitter streamPlan(TravelChatRequest request);
}

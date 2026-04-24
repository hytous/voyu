package com.voyu.agent.model.agent;

import java.time.Instant;
import java.util.Map;

public class AgentSseEvent {
    private final AgentEventType eventType;
    private final String sessionId;
    private final int round;
    private final Instant timestamp;
    private final Map<String, Object> payload;

    public AgentSseEvent(AgentEventType eventType, String sessionId, int round, Instant timestamp, Map<String, Object> payload) {
        this.eventType = eventType;
        this.sessionId = sessionId;
        this.round = round;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    public AgentEventType getEventType() {
        return eventType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getRound() {
        return round;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}

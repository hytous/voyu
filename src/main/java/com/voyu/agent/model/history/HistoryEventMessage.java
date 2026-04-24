package com.voyu.agent.model.history;

import java.time.Instant;
import java.util.Map;

public class HistoryEventMessage {

    private String sessionId;
    private String userId;
    private String eventType;
    private Integer round;
    private Instant timestamp;
    private Map<String, Object> payload;

    public HistoryEventMessage() {
    }

    public HistoryEventMessage(String sessionId,
                               String userId,
                               String eventType,
                               Integer round,
                               Instant timestamp,
                               Map<String, Object> payload) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.eventType = eventType;
        this.round = round;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Integer getRound() {
        return round;
    }

    public void setRound(Integer round) {
        this.round = round;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }
}

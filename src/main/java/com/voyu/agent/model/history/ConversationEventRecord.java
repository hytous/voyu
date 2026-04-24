package com.voyu.agent.model.history;

import java.time.Instant;
import java.util.Map;

public class ConversationEventRecord {

    private String eventType;
    private Integer round;
    private Instant timestamp;
    private Map<String, Object> payload;

    public ConversationEventRecord() {
    }

    public ConversationEventRecord(String eventType, Integer round, Instant timestamp, Map<String, Object> payload) {
        this.eventType = eventType;
        this.round = round;
        this.timestamp = timestamp;
        this.payload = payload;
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

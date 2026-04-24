package com.voyu.agent.model.history;

import java.time.Instant;
import java.util.Map;

public class ConversationTurnRecord {

    private Instant timestamp;
    private Map<String, Object> requestSnapshot;
    private String userMessage;
    private String assistantMessage;
    private String assistantMessageType;
    private String status;

    public ConversationTurnRecord() {
    }

    public ConversationTurnRecord(Instant timestamp, Map<String, Object> requestSnapshot) {
        this(timestamp, requestSnapshot, requestSnapshot == null ? null : String.valueOf(requestSnapshot.get("message")), null, null, null);
    }

    public ConversationTurnRecord(Instant timestamp,
                                  Map<String, Object> requestSnapshot,
                                  String userMessage,
                                  String assistantMessage,
                                  String assistantMessageType,
                                  String status) {
        this.timestamp = timestamp;
        this.requestSnapshot = requestSnapshot;
        this.userMessage = userMessage;
        this.assistantMessage = assistantMessage;
        this.assistantMessageType = assistantMessageType;
        this.status = status;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getRequestSnapshot() {
        return requestSnapshot;
    }

    public void setRequestSnapshot(Map<String, Object> requestSnapshot) {
        this.requestSnapshot = requestSnapshot;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public void setAssistantMessage(String assistantMessage) {
        this.assistantMessage = assistantMessage;
    }

    public String getAssistantMessageType() {
        return assistantMessageType;
    }

    public void setAssistantMessageType(String assistantMessageType) {
        this.assistantMessageType = assistantMessageType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

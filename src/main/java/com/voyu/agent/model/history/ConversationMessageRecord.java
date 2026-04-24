package com.voyu.agent.model.history;

import java.time.Instant;
import java.util.Map;

public class ConversationMessageRecord {

    private String messageId;
    private String parentMessageId;
    private String role;
    private String content;
    private String messageType;
    private String status;
    private Long sequence;
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, Object> requestSnapshot;

    public ConversationMessageRecord() {
    }

    public ConversationMessageRecord(String messageId,
                                     String parentMessageId,
                                     String role,
                                     String content,
                                     String messageType,
                                     String status,
                                     Long sequence,
                                     Instant createdAt,
                                     Instant updatedAt,
                                     Map<String, Object> requestSnapshot) {
        this.messageId = messageId;
        this.parentMessageId = parentMessageId;
        this.role = role;
        this.content = content;
        this.messageType = messageType;
        this.status = status;
        this.sequence = sequence;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.requestSnapshot = requestSnapshot;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getParentMessageId() {
        return parentMessageId;
    }

    public void setParentMessageId(String parentMessageId) {
        this.parentMessageId = parentMessageId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getSequence() {
        return sequence;
    }

    public void setSequence(Long sequence) {
        this.sequence = sequence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, Object> getRequestSnapshot() {
        return requestSnapshot;
    }

    public void setRequestSnapshot(Map<String, Object> requestSnapshot) {
        this.requestSnapshot = requestSnapshot;
    }
}

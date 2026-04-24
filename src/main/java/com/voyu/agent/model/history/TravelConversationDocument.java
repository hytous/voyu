package com.voyu.agent.model.history;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Document(collection = "travel_conversations")
public class TravelConversationDocument {

    @Id
    private String sessionId;
    private String userId;
    private String title;
    private String preview;
    private String status;
    private String finalAnswer;
    private String errorMessage;
    private String lastEventType;
    private Long eventCount;
    private Long turnCount;
    private Long messageCount;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private Map<String, Object> requestSnapshot;
    private Map<String, Object> lastRequestSnapshot;
    private String memorySummary;
    private String preferenceMemory;
    private List<String> memoryHighlights = new ArrayList<>();
    private List<Map<String, Object>> rag = new ArrayList<>();
    private String ragQuery;
    private String ragRewrittenQuery;
    private List<String> ragRecognizedDestinations = new ArrayList<>();
    private Boolean ragInitialized;
    private Boolean ragCleared;
    private List<ConversationMessageRecord> messages = new ArrayList<>();
    private List<ConversationEventRecord> traceEvents = new ArrayList<>();
    private List<ConversationEventRecord> events = new ArrayList<>();
    private List<ConversationTurnRecord> turns = new ArrayList<>();

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getLastEventType() {
        return lastEventType;
    }

    public void setLastEventType(String lastEventType) {
        this.lastEventType = lastEventType;
    }

    public Long getEventCount() {
        return eventCount;
    }

    public void setEventCount(Long eventCount) {
        this.eventCount = eventCount;
    }

    public Long getTurnCount() {
        return turnCount;
    }

    public void setTurnCount(Long turnCount) {
        this.turnCount = turnCount;
    }

    public Long getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(Long messageCount) {
        this.messageCount = messageCount;
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

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Map<String, Object> getRequestSnapshot() {
        return requestSnapshot;
    }

    public void setRequestSnapshot(Map<String, Object> requestSnapshot) {
        this.requestSnapshot = requestSnapshot;
    }

    public Map<String, Object> getLastRequestSnapshot() {
        return lastRequestSnapshot;
    }

    public void setLastRequestSnapshot(Map<String, Object> lastRequestSnapshot) {
        this.lastRequestSnapshot = lastRequestSnapshot;
    }

    public String getMemorySummary() {
        return memorySummary;
    }

    public void setMemorySummary(String memorySummary) {
        this.memorySummary = memorySummary;
    }

    public String getPreferenceMemory() {
        return preferenceMemory;
    }

    public void setPreferenceMemory(String preferenceMemory) {
        this.preferenceMemory = preferenceMemory;
    }

    public List<String> getMemoryHighlights() {
        return memoryHighlights;
    }

    public void setMemoryHighlights(List<String> memoryHighlights) {
        this.memoryHighlights = memoryHighlights;
    }

    public List<Map<String, Object>> getRag() {
        return rag;
    }

    public void setRag(List<Map<String, Object>> rag) {
        this.rag = rag;
    }

    public String getRagQuery() {
        return ragQuery;
    }

    public void setRagQuery(String ragQuery) {
        this.ragQuery = ragQuery;
    }

    public String getRagRewrittenQuery() {
        return ragRewrittenQuery;
    }

    public void setRagRewrittenQuery(String ragRewrittenQuery) {
        this.ragRewrittenQuery = ragRewrittenQuery;
    }

    public List<String> getRagRecognizedDestinations() {
        return ragRecognizedDestinations;
    }

    public void setRagRecognizedDestinations(List<String> ragRecognizedDestinations) {
        this.ragRecognizedDestinations = ragRecognizedDestinations;
    }

    public Boolean getRagInitialized() {
        return ragInitialized;
    }

    public void setRagInitialized(Boolean ragInitialized) {
        this.ragInitialized = ragInitialized;
    }

    public Boolean getRagCleared() {
        return ragCleared;
    }

    public void setRagCleared(Boolean ragCleared) {
        this.ragCleared = ragCleared;
    }

    public List<ConversationMessageRecord> getMessages() {
        return messages;
    }

    public void setMessages(List<ConversationMessageRecord> messages) {
        this.messages = messages;
    }

    public List<ConversationEventRecord> getTraceEvents() {
        return traceEvents;
    }

    public void setTraceEvents(List<ConversationEventRecord> traceEvents) {
        this.traceEvents = traceEvents;
    }

    public List<ConversationEventRecord> getEvents() {
        return events;
    }

    public void setEvents(List<ConversationEventRecord> events) {
        this.events = events;
    }

    public List<ConversationTurnRecord> getTurns() {
        return turns;
    }

    public void setTurns(List<ConversationTurnRecord> turns) {
        this.turns = turns;
    }
}

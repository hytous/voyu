package com.voyu.agent.model.agent;

import com.voyu.agent.model.api.TravelChatRequest;

import java.util.ArrayList;
import java.util.List;

public class ConversationState {
    private final String sessionId;
    private final TravelChatRequest request;
    private int currentRound;
    private String loopFocus;
    private ConversationMemorySnapshot memorySnapshot;
    private final List<String> planThoughts;
    private final List<String> reviewThoughts;
    private final List<TaskBook> taskBooks;
    private final List<TaskExecutionResult> executionResults;

    public ConversationState(String sessionId, TravelChatRequest request, int currentRound) {
        this.sessionId = sessionId;
        this.request = request;
        this.currentRound = currentRound;
        this.loopFocus = "";
        this.memorySnapshot = ConversationMemorySnapshot.empty();
        this.planThoughts = new ArrayList<>();
        this.reviewThoughts = new ArrayList<>();
        this.taskBooks = new ArrayList<>();
        this.executionResults = new ArrayList<>();
    }

    public String getSessionId() {
        return sessionId;
    }

    public TravelChatRequest getRequest() {
        return request;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public void setCurrentRound(int currentRound) {
        this.currentRound = currentRound;
    }

    public void advanceRound() {
        this.currentRound += 1;
    }

    public String getLoopFocus() {
        return loopFocus;
    }

    public void setLoopFocus(String loopFocus) {
        this.loopFocus = loopFocus == null ? "" : loopFocus;
    }

    public ConversationMemorySnapshot getMemorySnapshot() {
        return memorySnapshot;
    }

    public void setMemorySnapshot(ConversationMemorySnapshot memorySnapshot) {
        this.memorySnapshot = memorySnapshot == null ? ConversationMemorySnapshot.empty() : memorySnapshot;
    }

    public List<String> getPlanThoughts() {
        return planThoughts;
    }

    public List<String> getReviewThoughts() {
        return reviewThoughts;
    }

    public List<TaskBook> getTaskBooks() {
        return taskBooks;
    }

    public List<TaskExecutionResult> getExecutionResults() {
        return executionResults;
    }
}

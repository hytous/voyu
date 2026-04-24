package com.voyu.agent.model.agent;

import java.util.List;
import java.util.Map;

public class ConversationMemorySnapshot {

    private final String sessionSummary;
    private final String preferenceSummary;
    private final List<String> chatWindow;
    private final String promptContext;
    private final List<Map<String, Object>> rag;
    private final String ragQuery;
    private final String ragRewrittenQuery;
    private final List<String> ragRecognizedDestinations;
    private final boolean ragInitialized;
    private final boolean ragCleared;

    public ConversationMemorySnapshot(String sessionSummary,
                                      String preferenceSummary,
                                      List<String> chatWindow,
                                      String promptContext) {
        this(sessionSummary, preferenceSummary, chatWindow, promptContext, List.of(), "", "", List.of(), false, false);
    }

    public ConversationMemorySnapshot(String sessionSummary,
                                      String preferenceSummary,
                                      List<String> chatWindow,
                                      String promptContext,
                                      List<Map<String, Object>> rag,
                                      String ragQuery,
                                      String ragRewrittenQuery,
                                      List<String> ragRecognizedDestinations,
                                      boolean ragInitialized,
                                      boolean ragCleared) {
        this.sessionSummary = sessionSummary == null ? "" : sessionSummary;
        this.preferenceSummary = preferenceSummary == null ? "" : preferenceSummary;
        this.chatWindow = chatWindow == null ? List.of() : List.copyOf(chatWindow);
        this.promptContext = promptContext == null ? "" : promptContext;
        this.rag = rag == null ? List.of() : List.copyOf(rag);
        this.ragQuery = ragQuery == null ? "" : ragQuery;
        this.ragRewrittenQuery = ragRewrittenQuery == null ? "" : ragRewrittenQuery;
        this.ragRecognizedDestinations = ragRecognizedDestinations == null ? List.of() : List.copyOf(ragRecognizedDestinations);
        this.ragInitialized = ragInitialized;
        this.ragCleared = ragCleared;
    }

    public static ConversationMemorySnapshot empty() {
        return new ConversationMemorySnapshot("", "", List.of(), "");
    }

    public ConversationMemorySnapshot withRag(String promptContext,
                                              List<Map<String, Object>> rag,
                                              String ragQuery,
                                              String ragRewrittenQuery,
                                              List<String> ragRecognizedDestinations,
                                              boolean ragInitialized,
                                              boolean ragCleared) {
        return new ConversationMemorySnapshot(
                sessionSummary,
                preferenceSummary,
                chatWindow,
                promptContext,
                rag,
                ragQuery,
                ragRewrittenQuery,
                ragRecognizedDestinations,
                ragInitialized,
                ragCleared);
    }

    public ConversationMemorySnapshot withoutRag(String promptContext) {
        return withRag(promptContext, List.of(), "", "", List.of(), true, true);
    }

    public String getSessionSummary() {
        return sessionSummary;
    }

    public String getPreferenceSummary() {
        return preferenceSummary;
    }

    public List<String> getChatWindow() {
        return chatWindow;
    }

    public String getPromptContext() {
        return promptContext;
    }

    public List<Map<String, Object>> getRag() {
        return rag;
    }

    public String getRagQuery() {
        return ragQuery;
    }

    public String getRagRewrittenQuery() {
        return ragRewrittenQuery;
    }

    public List<String> getRagRecognizedDestinations() {
        return ragRecognizedDestinations;
    }

    public boolean isRagInitialized() {
        return ragInitialized;
    }

    public boolean isRagCleared() {
        return ragCleared;
    }
}

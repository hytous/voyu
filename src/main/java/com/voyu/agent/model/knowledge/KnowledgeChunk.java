package com.voyu.agent.model.knowledge;

import java.util.List;

public class KnowledgeChunk {

    private final String id;
    private final String title;
    private final String destination;
    private final String content;
    private final List<String> keywords;

    public KnowledgeChunk(String id, String title, String destination, String content, List<String> keywords) {
        this.id = id;
        this.title = title;
        this.destination = destination;
        this.content = content;
        this.keywords = keywords;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDestination() {
        return destination;
    }

    public String getContent() {
        return content;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public String searchableText() {
        return String.join(" ", title, safe(destination), content, String.join(" ", keywords));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

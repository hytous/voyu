package com.voyu.agent.model.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;

public class KnowledgeHit {

    private final KnowledgeChunk chunk;
    private final double score;
    private final String source;

    public KnowledgeHit(KnowledgeChunk chunk, double score, String source) {
        this.chunk = chunk;
        this.score = score;
        this.source = source;
    }

    public KnowledgeChunk getChunk() {
        return chunk;
    }

    public double getScore() {
        return score;
    }

    public String getSource() {
        return source;
    }

    public Map<String, Object> toDisplayMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", chunk.getId());
        payload.put("title", chunk.getTitle());
        payload.put("destination", chunk.getDestination());
        payload.put("content", chunk.getContent());
        payload.put("keywords", chunk.getKeywords());
        payload.put("score", score);
        payload.put("source", source);
        return payload;
    }
}

package com.voyu.agent.model.knowledge;

import java.util.List;

public class KnowledgeSearchResult {

    private final String originalQuery;
    private final String rewrittenQuery;
    private final List<String> recognizedDestinations;
    private final List<KnowledgeHit> hits;

    public KnowledgeSearchResult(String originalQuery, String rewrittenQuery, List<KnowledgeHit> hits) {
        this(originalQuery, rewrittenQuery, List.of(), hits);
    }

    public KnowledgeSearchResult(String originalQuery,
                                 String rewrittenQuery,
                                 List<String> recognizedDestinations,
                                 List<KnowledgeHit> hits) {
        this.originalQuery = originalQuery;
        this.rewrittenQuery = rewrittenQuery;
        this.recognizedDestinations = recognizedDestinations;
        this.hits = hits;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public String getRewrittenQuery() {
        return rewrittenQuery;
    }

    public List<String> getRecognizedDestinations() {
        return recognizedDestinations;
    }

    public List<KnowledgeHit> getHits() {
        return hits;
    }
}

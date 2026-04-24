package com.voyu.agent.tool.impl;

import com.voyu.agent.model.knowledge.KnowledgeSearchResult;
import com.voyu.agent.service.knowledge.KnowledgeBaseService;
import com.voyu.agent.tool.ToolCapabilityType;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RagKnowledgeTool implements TravelTool {

    private static final ToolTemplate TEMPLATE = ToolTemplate.local(
            "rag.travel.knowledge",
            "旅游知识检索",
            "Retrieve travel heuristics from the local knowledge base.",
            ToolCapabilityType.RAG_RETRIEVAL,
            true,
            true,
            ToolTemplate.jsonObjectSchema("query"));

    private final KnowledgeBaseService knowledgeBaseService;

    public RagKnowledgeTool(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    public ToolTemplate template() {
        return TEMPLATE;
    }

    @Override
    public String name() {
        return "rag.travel.knowledge";
    }

    @Override
    public String description() {
        return "Retrieve travel heuristics from the local knowledge base.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        String query = String.valueOf(input.getOrDefault("query", ""));
        KnowledgeSearchResult retrieval = knowledgeBaseService.searchKnowledge(query);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("rewrittenQuery", retrieval.getRewrittenQuery());
        result.put("hits", retrieval.getHits().stream().map(hit -> hit.toDisplayMap()).toList());
        return result;
    }
}

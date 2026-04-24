package com.voyu.agent.controller;

import com.voyu.agent.model.knowledge.KnowledgeSearchResult;
import com.voyu.agent.service.knowledge.KnowledgeBaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping("/search")
    public KnowledgeSearchResult search(@RequestParam String query) {
        return knowledgeBaseService.searchKnowledge(query);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "chunkCount", knowledgeBaseService.chunkCount()
        );
    }
}

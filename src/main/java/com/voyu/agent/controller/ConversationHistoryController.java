package com.voyu.agent.controller;

import com.voyu.agent.model.history.ConversationSessionSummary;
import com.voyu.agent.model.history.TravelConversationDocument;
import com.voyu.agent.service.agent.PlanFileService;
import com.voyu.agent.service.history.ConversationHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/travel-agent/sessions")
public class ConversationHistoryController {

    private final ConversationHistoryService conversationHistoryService;
    private final PlanFileService planFileService;

    public ConversationHistoryController(ConversationHistoryService conversationHistoryService,
                                         PlanFileService planFileService) {
        this.conversationHistoryService = conversationHistoryService;
        this.planFileService = planFileService;
    }

    @GetMapping
    public List<ConversationSessionSummary> listSessions(@RequestParam(required = false) String userId,
                                                          @RequestParam(defaultValue = "20") int limit) {
        return conversationHistoryService.listSessions(userId, limit);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<TravelConversationDocument> getSession(@PathVariable String sessionId) {
        return conversationHistoryService.findSession(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{sessionId}/chat")
    public ResponseEntity<TravelConversationDocument> getChatSession(@PathVariable String sessionId) {
        return conversationHistoryService.findChatSession(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        boolean deleted = conversationHistoryService.deleteSession(sessionId);
        // 同步清理关联的 plan 文件，避免孤立文件
        planFileService.deletePlanFile(sessionId);
        return deleted
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}

package com.voyu.agent.controller;

import com.voyu.agent.model.history.ConversationSessionSummary;
import com.voyu.agent.model.history.TravelConversationDocument;
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

    public ConversationHistoryController(ConversationHistoryService conversationHistoryService) {
        this.conversationHistoryService = conversationHistoryService;
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
        return conversationHistoryService.deleteSession(sessionId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}

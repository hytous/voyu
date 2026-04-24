package com.voyu.agent.model.history;

import java.time.Instant;

public record ConversationSessionSummary(String sessionId,
                                         String userId,
                                         String title,
                                         String preview,
                                         String destination,
                                         String travelDays,
                                         String status,
                                         Instant createdAt,
                                         Instant updatedAt,
                                         Long messageCount) {
}

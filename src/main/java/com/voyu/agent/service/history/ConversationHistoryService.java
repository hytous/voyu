package com.voyu.agent.service.history;

import com.voyu.agent.model.agent.AgentSseEvent;
import com.voyu.agent.model.agent.ConversationState;
import com.voyu.agent.model.api.TravelChatRequest;
import com.voyu.agent.model.history.ConversationEventRecord;
import com.voyu.agent.model.history.ConversationMessageRecord;
import com.voyu.agent.model.history.ConversationSessionSummary;
import com.voyu.agent.model.history.HistoryEventMessage;
import com.voyu.agent.model.history.TravelConversationDocument;
import com.voyu.agent.repository.TravelConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class ConversationHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryService.class);
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";
    private static final String TYPE_USER_INPUT = "USER_INPUT";

    private final MongoTemplate mongoTemplate;
    private final TravelConversationRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Executor historyExecutor;
    private final String historyTopic;

    public ConversationHistoryService(MongoTemplate mongoTemplate,
                                      TravelConversationRepository repository,
                                      KafkaTemplate<String, Object> kafkaTemplate,
                                      @Qualifier("historyExecutor") Executor historyExecutor,
                                      @Value("${voyu.history.topic:voyu-conversation-history}") String historyTopic) {
        this.mongoTemplate = mongoTemplate;
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.historyExecutor = historyExecutor;
        this.historyTopic = historyTopic;
    }

    public void initializeSession(ConversationState state) {
        Instant now = Instant.now();
        Map<String, Object> requestSnapshot = buildRequestSnapshot(state.getRequest());
        TravelConversationDocument document = repository.findById(state.getSessionId())
                .orElseGet(TravelConversationDocument::new);

        if (!StringUtils.hasText(document.getSessionId())) {
            document.setSessionId(state.getSessionId());
        }
        if (!StringUtils.hasText(document.getUserId())) {
            document.setUserId(state.getRequest().getUserId());
        }
        if (document.getCreatedAt() == null) {
            document.setCreatedAt(now);
        }
        if (document.getEventCount() == null) {
            document.setEventCount(0L);
        }
        if (document.getTurnCount() == null) {
            document.setTurnCount(0L);
        }
        if (document.getMessageCount() == null) {
            document.setMessageCount(0L);
        }
        if (document.getMessages() == null) {
            document.setMessages(new java.util.ArrayList<>());
        }
        if (document.getTraceEvents() == null) {
            document.setTraceEvents(new java.util.ArrayList<>());
        }
        if (document.getEvents() == null) {
            document.setEvents(new java.util.ArrayList<>());
        }
        if (document.getTurns() == null) {
            document.setTurns(new java.util.ArrayList<>());
        }
        if (document.getRag() == null) {
            document.setRag(new java.util.ArrayList<>());
        }

        if (!StringUtils.hasText(document.getTitle())) {
            document.setTitle(buildConversationTitle(state.getRequest()));
        }
        document.setRequestSnapshot(requestSnapshot);
        document.setLastRequestSnapshot(requestSnapshot);
        document.setPreview(buildPreview(state.getRequest().getMessage()));
        document.setStatus(STATUS_RUNNING);
        document.setUpdatedAt(now);
        document.getMessages().add(buildMessageRecord(
                UUID.randomUUID().toString(),
                null,
                ROLE_USER,
                state.getRequest().getMessage(),
                TYPE_USER_INPUT,
                STATUS_COMPLETED,
                now.toEpochMilli() * 10 + 1,
                now,
                requestSnapshot));
        document.setMessageCount((document.getMessageCount() == null ? 0L : document.getMessageCount()) + 1L);
        repository.save(document);
    }

    public void recordEvent(ConversationState state, AgentSseEvent event) {
        runAsync("recordEvent", () -> {
            Instant now = Instant.now();
            Query query = Query.query(Criteria.where("_id").is(state.getSessionId()));
            Update update = baseUpsert(state, now)
                    .setOnInsert("createdAt", now)
                    .setOnInsert("status", STATUS_RUNNING)
                    .setOnInsert("messageCount", 0L)
                    .set("lastEventType", event.getEventType().name())
                    .set("updatedAt", now)
                    .inc("eventCount", 1L)
                    .push("traceEvents", toEventRecord(event))
                    .push("events", toEventRecord(event));
            mongoTemplate.upsert(query, update, TravelConversationDocument.class);
            kafkaTemplate.send(historyTopic, state.getSessionId(), toHistoryMessage(state, event));
        });
    }

    public void markCompleted(ConversationState state, String finalAnswer, String responseKind) {
        runAsync("markCompleted", () -> {
            Instant now = Instant.now();
            TravelConversationDocument document = repository.findById(state.getSessionId())
                    .orElseGet(TravelConversationDocument::new);
            if (!StringUtils.hasText(document.getSessionId())) {
                document.setSessionId(state.getSessionId());
            }
            if (!StringUtils.hasText(document.getUserId())) {
                document.setUserId(state.getRequest().getUserId());
            }
            if (document.getCreatedAt() == null) {
                document.setCreatedAt(now);
            }
            if (document.getMessageCount() == null) {
                document.setMessageCount(0L);
            }
            if (document.getMessages() == null) {
                document.setMessages(new java.util.ArrayList<>());
            }
            if (document.getTraceEvents() == null) {
                document.setTraceEvents(new java.util.ArrayList<>());
            }
            if (document.getEvents() == null) {
                document.setEvents(new java.util.ArrayList<>());
            }
            if (!StringUtils.hasText(document.getTitle())) {
                document.setTitle(buildConversationTitle(state.getRequest()));
            }

            document.setStatus(STATUS_COMPLETED);
            document.setFinalAnswer(finalAnswer);
            document.setErrorMessage(null);
            document.setPreview(buildPreview(finalAnswer));
            document.setUpdatedAt(now);
            document.setCompletedAt(now);
            document.setLastRequestSnapshot(buildRequestSnapshot(state.getRequest()));
            document.getMessages().add(buildMessageRecord(
                    UUID.randomUUID().toString(),
                    latestUserMessageId(document),
                    ROLE_ASSISTANT,
                    finalAnswer,
                    responseKind,
                    STATUS_COMPLETED,
                    now.toEpochMilli() * 10 + 2,
                    now,
                    null));
            document.setMessageCount((document.getMessageCount() == null ? 0L : document.getMessageCount()) + 1L);
            repository.save(document);
        });
    }

    public void markFailed(ConversationState state, String errorMessage) {
        runAsync("markFailed", () -> {
            Instant now = Instant.now();
            TravelConversationDocument document = repository.findById(state.getSessionId())
                    .orElseGet(TravelConversationDocument::new);
            if (!StringUtils.hasText(document.getSessionId())) {
                document.setSessionId(state.getSessionId());
            }
            if (!StringUtils.hasText(document.getUserId())) {
                document.setUserId(state.getRequest().getUserId());
            }
            if (document.getCreatedAt() == null) {
                document.setCreatedAt(now);
            }
            if (document.getMessageCount() == null) {
                document.setMessageCount(0L);
            }
            if (document.getMessages() == null) {
                document.setMessages(new java.util.ArrayList<>());
            }
            if (document.getTraceEvents() == null) {
                document.setTraceEvents(new java.util.ArrayList<>());
            }
            if (document.getEvents() == null) {
                document.setEvents(new java.util.ArrayList<>());
            }
            if (!StringUtils.hasText(document.getTitle())) {
                document.setTitle(buildConversationTitle(state.getRequest()));
            }

            document.setStatus(STATUS_FAILED);
            document.setErrorMessage(errorMessage);
            document.setPreview(buildPreview(errorMessage));
            document.setUpdatedAt(now);
            document.setCompletedAt(now);
            document.setLastRequestSnapshot(buildRequestSnapshot(state.getRequest()));
            document.getMessages().add(buildMessageRecord(
                    UUID.randomUUID().toString(),
                    latestUserMessageId(document),
                    ROLE_ASSISTANT,
                    errorMessage,
                    "ERROR",
                    STATUS_FAILED,
                    now.toEpochMilli() * 10 + 2,
                    now,
                    null));
            document.setMessageCount((document.getMessageCount() == null ? 0L : document.getMessageCount()) + 1L);
            repository.save(document);
        });
    }

    public Optional<TravelConversationDocument> findSession(String sessionId) {
        return repository.findById(sessionId);
    }

    public Optional<TravelConversationDocument> findChatSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }

        Query query = Query.query(Criteria.where("_id").is(sessionId));
        query.fields()
                .include("_id")
                .include("sessionId")
                .include("userId")
                .include("title")
                .include("preview")
                .include("status")
                .include("finalAnswer")
                .include("errorMessage")
                .include("lastEventType")
                .include("eventCount")
                .include("turnCount")
                .include("messageCount")
                .include("createdAt")
                .include("updatedAt")
                .include("completedAt")
                .include("requestSnapshot")
                .include("lastRequestSnapshot")
                .include("memorySummary")
                .include("preferenceMemory")
                .include("memoryHighlights")
                .include("rag")
                .include("ragQuery")
                .include("ragRewrittenQuery")
                .include("ragRecognizedDestinations")
                .include("ragInitialized")
                .include("ragCleared")
                .include("messages")
                .include("turns");

        return Optional.ofNullable(mongoTemplate.findOne(query, TravelConversationDocument.class));
    }

    public List<ConversationSessionSummary> listSessions(String userId, int limit) {
        Query query = new Query();
        if (userId != null && !userId.isBlank()) {
            query.addCriteria(Criteria.where("userId").is(userId));
        }
        query.fields()
                .include("_id")
                .include("sessionId")
                .include("userId")
                .include("title")
                .include("preview")
                .include("status")
                .include("finalAnswer")
                .include("messageCount")
                .include("turnCount")
                .include("createdAt")
                .include("updatedAt")
                .include("requestSnapshot")
                .include("lastRequestSnapshot");
        query.with(Sort.by(Sort.Direction.DESC, "updatedAt"));
        query.limit(Math.max(limit, 1));

        return mongoTemplate.find(query, TravelConversationDocument.class).stream()
                .map(this::toSessionSummary)
                .toList();
    }

    public boolean deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        if (!repository.existsById(sessionId)) {
            return false;
        }
        repository.deleteById(sessionId);
        return true;
    }

    private Update baseUpsert(ConversationState state, Instant now) {
        return new Update()
                .setOnInsert("_id", state.getSessionId())
                .setOnInsert("sessionId", state.getSessionId())
                .setOnInsert("userId", state.getRequest().getUserId())
                .setOnInsert("requestSnapshot", buildRequestSnapshot(state.getRequest()))
                .set("updatedAt", now);
    }

    private Map<String, Object> buildRequestSnapshot(TravelChatRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("message", request.getMessage());
        snapshot.put("destination", request.getDestination());
        snapshot.put("departure", request.getDeparture());
        snapshot.put("travelDays", request.getTravelDays());
        snapshot.put("budget", request.getBudget());
        snapshot.put("preferences", request.getPreferences());
        snapshot.put("userId", request.getUserId());
        return snapshot;
    }

    private ConversationEventRecord toEventRecord(AgentSseEvent event) {
        return new ConversationEventRecord(
                event.getEventType().name(),
                event.getRound(),
                event.getTimestamp(),
                event.getPayload());
    }

    private HistoryEventMessage toHistoryMessage(ConversationState state, AgentSseEvent event) {
        return new HistoryEventMessage(
                state.getSessionId(),
                state.getRequest().getUserId(),
                event.getEventType().name(),
                event.getRound(),
                event.getTimestamp(),
                event.getPayload());
    }

    private ConversationSessionSummary toSessionSummary(TravelConversationDocument document) {
        Map<String, Object> lastRequest = document.getLastRequestSnapshot() == null || document.getLastRequestSnapshot().isEmpty()
                ? document.getRequestSnapshot()
                : document.getLastRequestSnapshot();
        String title = StringUtils.hasText(document.getTitle())
                ? document.getTitle()
                : buildConversationTitle(value(lastRequest == null ? null : lastRequest.get("message")));
        String preview = StringUtils.hasText(document.getPreview())
                ? document.getPreview()
                : buildPreview(latestMessageContent(document));
        String destination = value(lastRequest == null ? null : lastRequest.get("destination"));
        String travelDays = value(lastRequest == null ? null : lastRequest.get("travelDays"));
        return new ConversationSessionSummary(
                document.getSessionId(),
                document.getUserId(),
                title,
                preview,
                destination,
                travelDays,
                document.getStatus(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                messageCount(document));
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private ConversationMessageRecord buildMessageRecord(String messageId,
                                                         String parentMessageId,
                                                         String role,
                                                         String content,
                                                         String messageType,
                                                         String status,
                                                         long sequence,
                                                         Instant timestamp,
                                                         Map<String, Object> requestSnapshot) {
        return new ConversationMessageRecord(
                messageId,
                parentMessageId,
                role,
                content,
                messageType,
                status,
                sequence,
                timestamp,
                timestamp,
                requestSnapshot);
    }

    private String latestUserMessageId(TravelConversationDocument document) {
        if (document == null || document.getMessages() == null || document.getMessages().isEmpty()) {
            return null;
        }
        for (int index = document.getMessages().size() - 1; index >= 0; index--) {
            ConversationMessageRecord message = document.getMessages().get(index);
            if (ROLE_USER.equals(message.getRole()) && StringUtils.hasText(message.getMessageId())) {
                return message.getMessageId();
            }
        }
        return null;
    }

    private String latestMessageContent(TravelConversationDocument document) {
        if (document != null && document.getMessages() != null && !document.getMessages().isEmpty()) {
            for (int index = document.getMessages().size() - 1; index >= 0; index--) {
                String content = value(document.getMessages().get(index).getContent());
                if (StringUtils.hasText(content)) {
                    return content;
                }
            }
        }
        if (StringUtils.hasText(document == null ? null : document.getFinalAnswer())) {
            return document.getFinalAnswer();
        }
        return document == null || document.getLastRequestSnapshot() == null
                ? ""
                : value(document.getLastRequestSnapshot().get("message"));
    }

    private long messageCount(TravelConversationDocument document) {
        if (document == null) {
            return 0L;
        }
        if (document.getMessageCount() != null) {
            return document.getMessageCount();
        }
        if (document.getMessages() != null && !document.getMessages().isEmpty()) {
            return document.getMessages().size();
        }
        return document.getTurnCount() == null ? 0L : document.getTurnCount();
    }

    private String buildConversationTitle(TravelChatRequest request) {
        return buildConversationTitle(request == null ? null : request.getMessage());
    }

    private String buildConversationTitle(String raw) {
        String normalized = clip(value(raw).replace('\n', ' ').replace('\r', ' '), 36);
        return StringUtils.hasText(normalized) ? normalized : "新的旅行对话";
    }

    private String buildPreview(String raw) {
        String normalized = clip(value(raw).replace('\n', ' ').replace('\r', ' '), 88);
        return StringUtils.hasText(normalized) ? normalized : "";
    }

    private String clip(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim() + "...";
    }

    private void runAsync(String action, Runnable task) {
        CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception ex) {
                log.warn("History pipeline failed at {}", action, ex);
            }
        }, historyExecutor);
    }
}

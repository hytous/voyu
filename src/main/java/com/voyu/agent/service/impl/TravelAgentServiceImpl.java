package com.voyu.agent.service.impl;

import com.voyu.agent.model.agent.AgentEventType;
import com.voyu.agent.model.agent.ConversationState;
import com.voyu.agent.model.agent.TaskBook;
import com.voyu.agent.model.agent.TaskExecutionResult;
import com.voyu.agent.model.api.TravelChatRequest;
import com.voyu.agent.service.TravelAgentService;
import com.voyu.agent.service.agent.ExecuteAgent;
import com.voyu.agent.service.agent.ClarificationAgent;
import com.voyu.agent.service.agent.LoopReviewAgent;
import com.voyu.agent.service.agent.PlanAgent;
import com.voyu.agent.service.agent.SummarizeAgent;
import com.voyu.agent.service.history.ConversationHistoryService;
import com.voyu.agent.service.memory.ConversationMemoryService;
import com.voyu.agent.util.AgentEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class TravelAgentServiceImpl implements TravelAgentService {

    private static final Logger log = LoggerFactory.getLogger(TravelAgentServiceImpl.class);

    private final PlanAgent planAgent;
    private final ExecuteAgent executeAgent;
    private final ClarificationAgent clarificationAgent;
    private final LoopReviewAgent loopReviewAgent;
    private final SummarizeAgent summarizeAgent;
    private final ConversationHistoryService conversationHistoryService;
    private final ConversationMemoryService conversationMemoryService;
    private final Executor executor;
    private final long streamTimeoutMs;
    private final int maxRounds;

    public TravelAgentServiceImpl(PlanAgent planAgent,
                                  ExecuteAgent executeAgent,
                                  ClarificationAgent clarificationAgent,
                                  LoopReviewAgent loopReviewAgent,
                                  SummarizeAgent summarizeAgent,
                                  ConversationHistoryService conversationHistoryService,
                                  ConversationMemoryService conversationMemoryService,
                                  @Qualifier("agentExecutor") Executor executor,
                                  @Value("${voyu.agent.stream-timeout-ms:300000}") long streamTimeoutMs,
                                  @Value("${voyu.agent.max-rounds:5}") int maxRounds) {
        this.planAgent = planAgent;
        this.executeAgent = executeAgent;
        this.clarificationAgent = clarificationAgent;
        this.loopReviewAgent = loopReviewAgent;
        this.summarizeAgent = summarizeAgent;
        this.conversationHistoryService = conversationHistoryService;
        this.conversationMemoryService = conversationMemoryService;
        this.executor = executor;
        this.streamTimeoutMs = streamTimeoutMs;
        this.maxRounds = maxRounds;
    }

    @Override
    public SseEmitter streamPlan(TravelChatRequest request) {
        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        String sessionId = request.getSessionId() == null || request.getSessionId().isBlank()
                ? UUID.randomUUID().toString()
                : request.getSessionId();
        request.setSessionId(sessionId);

        ConversationState state = new ConversationState(sessionId, request, 1);
        conversationMemoryService.hydrateRequestContext(state);
        conversationHistoryService.initializeSession(state);

        AgentEventPublisher publisher = new AgentEventPublisher(
                emitter,
                Instant::now,
                event -> conversationHistoryService.recordEvent(state, event)
        );

        executor.execute(() -> {
            try {
                state.setMemorySnapshot(conversationMemoryService.loadMemory(state));
                publish(publisher, state, AgentEventType.THOUGHT, Map.of(
                        "phase", "ORCHESTRATOR",
                        "message", "会话记忆已加载，先进行本轮澄清判断。"
                ));
                publishMemory(publisher, state, "会话记忆已加载。");

                ClarificationAgent.ClarificationDecision clarificationDecision = clarificationAgent.inspect(state);
                if (clarificationDecision.shouldAsk()) {
                    publish(publisher, state, AgentEventType.THOUGHT, Map.of(
                            "phase", "ORCHESTRATOR",
                            "message", clarificationDecision.thought()
                    ));
                    conversationMemoryService.rememberFinalAnswer(state, clarificationDecision.question());
                    publish(publisher, state, AgentEventType.FINAL_ANSWER, Map.of(
                            "answer", clarificationDecision.question(),
                            "responseKind", "QUESTION",
                            "missingFields", clarificationDecision.missingFields()
                    ));
                    conversationHistoryService.markCompleted(state, clarificationDecision.question(), "QUESTION");
                    emitter.complete();
                    return;
                }

                state.setMemorySnapshot(conversationMemoryService.ensureSessionRag(state));
                publish(publisher, state, AgentEventType.THOUGHT, Map.of(
                        "phase", "RAG",
                        "message", state.getMemorySnapshot().isRagCleared()
                                ? "本 session 的 RAG 记忆已被清除，按会话只检索一次的规则不再自动重复检索。"
                                : "会话级 RAG 增强已完成，知识已写入 memory.rag[]。"
                ));
                publishMemory(publisher, state, "会话级 RAG 增强知识已更新。");

                publish(publisher, state, AgentEventType.THOUGHT, Map.of(
                        "phase", "ORCHESTRATOR",
                        "message", "进入 Plan-Execute 主循环。"
                ));

                boolean completedByReview = false;
                for (int round = 1; round <= maxRounds; round++) {
                    state.setCurrentRound(round);
                    publish(publisher, state, AgentEventType.THOUGHT, Map.of(
                            "phase", "ORCHESTRATOR",
                            "round", round,
                            "message", "开始第 %s / %s 轮规划执行。".formatted(round, maxRounds)
                    ));

                    if (!state.getLoopFocus().isBlank()) {
                        publish(publisher, state, AgentEventType.THOUGHT, Map.of(
                                "phase", "ORCHESTRATOR",
                                "round", round,
                                "message", "本轮补充重点：" + state.getLoopFocus()
                        ));
                    }

                    TaskBook taskBook = planAgent.plan(state, publisher);
                    state.getPlanThoughts().add(taskBook.getPlannerThought());
                    state.getTaskBooks().add(taskBook);

                    publish(publisher, state, AgentEventType.PLAN_DRAFT, Map.of(
                            "round", round,
                            "thought", taskBook.getPlannerThought()
                    ));
                    publish(publisher, state, AgentEventType.TASK_BOOK, Map.of(
                            "round", round,
                            "mission", taskBook.getMission(),
                            "taskScript", taskBook.getTaskScript(),
                            "tasks", taskBook.getTasks()
                    ));

                    List<TaskExecutionResult> roundResults = executeAgent.execute(taskBook, state, publisher);
                    state.getExecutionResults().addAll(roundResults);

                    LoopReviewAgent.LoopDecision decision = loopReviewAgent.review(state, taskBook, roundResults);
                    state.getReviewThoughts().add(decision.thought());

                    publish(publisher, state, AgentEventType.THOUGHT, Map.of(
                            "phase", "LOOP_REVIEW",
                            "round", round,
                            "decision", decision.continueLoop() ? "CONTINUE" : "FINISH",
                            "message", decision.thought()
                    ));

                    if (!decision.continueLoop()) {
                        completedByReview = true;
                        break;
                    }

                    state.setLoopFocus(decision.followUpFocus());
                }

                if (!completedByReview) {
                    publish(publisher, state, AgentEventType.WARNING, Map.of(
                            "phase", "ORCHESTRATOR",
                            "round", state.getCurrentRound(),
                            "message", "已达到最大循环次数，基于当前结果生成最终规划。"
                    ));
                }

                String finalAnswer = summarizeAgent.summarize(state);
                conversationMemoryService.rememberFinalAnswer(state, finalAnswer);
                conversationMemoryService.persistCompressedMemory(state, finalAnswer);
                publish(publisher, state, AgentEventType.FINAL_ANSWER, Map.of(
                        "answer", finalAnswer,
                        "responseKind", "PLAN"
                ));
                conversationHistoryService.markCompleted(state, finalAnswer, "PLAN");
                emitter.complete();
            } catch (Exception ex) {
                if (isClientDisconnect(ex)) {
                    log.info("Voyu stream closed by client for session {}", state.getSessionId());
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                        // The response is already closed on the client side.
                    }
                    return;
                }

                log.error("Voyu agent execution failed", ex);
                try {
                    publish(publisher, state, AgentEventType.WARNING, Map.of(
                            "message", String.valueOf(ex.getMessage())
                    ));
                } catch (Exception sendEx) {
                    log.warn("Failed to push warning event", sendEx);
                }
                conversationHistoryService.markFailed(state, String.valueOf(ex.getMessage()));
                emitter.completeWithError(ex);
            }
        });

        return emitter;
    }

    private void publish(AgentEventPublisher publisher,
                         ConversationState state,
                         AgentEventType eventType,
                         Map<String, Object> payload) {
        try {
            publisher.publish(state, eventType, payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish SSE event", ex);
        }
    }

    private void publishMemory(AgentEventPublisher publisher,
                               ConversationState state,
                               String message) {
        TravelChatRequest request = state.getRequest();
        Map<String, Object> memoryPayload = new LinkedHashMap<>();
        memoryPayload.put("phase", "MEMORY");
        memoryPayload.put("message", message);
        memoryPayload.put("destination", Objects.toString(request.getDestination(), ""));
        memoryPayload.put("departure", Objects.toString(request.getDeparture(), ""));
        memoryPayload.put("travelDays", Objects.toString(request.getTravelDays(), ""));
        memoryPayload.put("budget", Objects.toString(request.getBudget(), ""));
        memoryPayload.put("preferences", Objects.toString(request.getPreferences(), ""));
        memoryPayload.put("sessionSummary", Objects.toString(state.getMemorySnapshot().getSessionSummary(), ""));
        memoryPayload.put("preferenceSummary", Objects.toString(state.getMemorySnapshot().getPreferenceSummary(), ""));
        memoryPayload.put("chatWindow", state.getMemorySnapshot().getChatWindow() == null
                ? List.of()
                : state.getMemorySnapshot().getChatWindow());
        memoryPayload.put("rag", state.getMemorySnapshot().getRag() == null
                ? List.of()
                : state.getMemorySnapshot().getRag());
        memoryPayload.put("ragCount", state.getMemorySnapshot().getRag() == null
                ? 0
                : state.getMemorySnapshot().getRag().size());
        memoryPayload.put("ragQuery", Objects.toString(state.getMemorySnapshot().getRagQuery(), ""));
        memoryPayload.put("ragRewrittenQuery", Objects.toString(state.getMemorySnapshot().getRagRewrittenQuery(), ""));
        memoryPayload.put("ragRecognizedDestinations", state.getMemorySnapshot().getRagRecognizedDestinations() == null
                ? List.of()
                : state.getMemorySnapshot().getRagRecognizedDestinations());
        memoryPayload.put("ragInitialized", state.getMemorySnapshot().isRagInitialized());
        memoryPayload.put("ragCleared", state.getMemorySnapshot().isRagCleared());
        memoryPayload.put("summary", Objects.toString(state.getMemorySnapshot().getPromptContext(), ""));
        publish(publisher, state, AgentEventType.MEMORY, memoryPayload);
    }

    private boolean isClientDisconnect(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            String className = cursor.getClass().getName();
            if (className.contains("AsyncRequestNotUsableException")
                    || className.contains("ClientAbortException")) {
                return true;
            }

            String message = cursor.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("broken pipe")
                        || normalized.contains("responsebodyemitter has already completed")
                        || normalized.contains("servletoutputstream failed to flush")) {
                    return true;
                }
            }

            cursor = cursor.getCause();
        }
        return false;
    }
}

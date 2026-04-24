package com.voyu.agent.service.memory;

import com.voyu.agent.model.agent.ConversationMemorySnapshot;
import com.voyu.agent.model.agent.ConversationState;
import com.voyu.agent.model.api.TravelChatRequest;
import com.voyu.agent.model.history.ConversationEventRecord;
import com.voyu.agent.model.history.ConversationMessageRecord;
import com.voyu.agent.model.history.ConversationTurnRecord;
import com.voyu.agent.model.history.TravelConversationDocument;
import com.voyu.agent.model.knowledge.KnowledgeHit;
import com.voyu.agent.model.knowledge.KnowledgeSearchResult;
import com.voyu.agent.repository.TravelConversationRepository;
import com.voyu.agent.service.knowledge.KnowledgeBaseService;
import com.voyu.agent.service.llm.LlmFacade;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ConversationMemoryService {

    private final TravelConversationRepository repository;
    private final LlmFacade llmFacade;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatMemory chatMemory;
    private final ObjectMapper objectMapper;
    private final int maxHistorySessions;
    private final int maxHighlights;

    public ConversationMemoryService(TravelConversationRepository repository,
                                     LlmFacade llmFacade,
                                     KnowledgeBaseService knowledgeBaseService,
                                     ObjectMapper objectMapper,
                                     @Value("${voyu.memory.chat-window-size:12}") int chatWindowSize,
                                     @Value("${voyu.memory.max-history-sessions:5}") int maxHistorySessions,
                                     @Value("${voyu.memory.max-highlights:8}") int maxHighlights) {
        this.repository = repository;
        this.llmFacade = llmFacade;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
        this.chatMemory = MessageWindowChatMemory.builder().maxMessages(chatWindowSize).build();
        this.maxHistorySessions = maxHistorySessions;
        this.maxHighlights = maxHighlights;
    }

    public void hydrateRequestContext(ConversationState state) {
        TravelChatRequest request = state.getRequest();
        mergeFieldsFromMessage(request);
        mergeFieldsFromHistory(state.getSessionId(), request);
    }

    public ConversationMemorySnapshot loadMemory(ConversationState state) {
        List<TravelConversationDocument> relatedDocuments = findRelatedDocuments(state);
        TravelConversationDocument currentDocument = repository.findById(state.getSessionId()).orElse(null);

        seedChatMemory(state.getSessionId(), currentDocument, state.getRequest().getMessage());
        if (StringUtils.hasText(state.getRequest().getMessage())) {
            chatMemory.add(state.getSessionId(), new UserMessage(state.getRequest().getMessage()));
        }

        String preferenceSummary = buildPreferenceSummary(relatedDocuments, state.getRequest());
        String sessionSummary = buildSessionSummary(currentDocument, relatedDocuments, state.getRequest());
        List<String> chatWindow = formatChatWindow(chatMemory.get(state.getSessionId()));
        List<Map<String, Object>> rag = normalizeRag(currentDocument == null ? null : currentDocument.getRag());
        String ragQuery = currentDocument == null ? "" : trimToEmpty(currentDocument.getRagQuery());
        String ragRewrittenQuery = currentDocument == null ? "" : trimToEmpty(currentDocument.getRagRewrittenQuery());
        List<String> ragDestinations = currentDocument == null || currentDocument.getRagRecognizedDestinations() == null
                ? List.of()
                : currentDocument.getRagRecognizedDestinations();
        boolean ragInitialized = currentDocument != null && Boolean.TRUE.equals(currentDocument.getRagInitialized());
        boolean ragCleared = currentDocument != null && Boolean.TRUE.equals(currentDocument.getRagCleared());
        String promptContext = buildPromptContext(preferenceSummary, sessionSummary, chatWindow, ragQuery, ragRewrittenQuery, ragDestinations, rag, ragInitialized, ragCleared);

        return new ConversationMemorySnapshot(
                sessionSummary,
                preferenceSummary,
                chatWindow,
                promptContext,
                rag,
                ragQuery,
                ragRewrittenQuery,
                ragDestinations,
                ragInitialized,
                ragCleared);
    }

    public ConversationMemorySnapshot ensureSessionRag(ConversationState state) {
        ConversationMemorySnapshot base = state.getMemorySnapshot() == null
                ? ConversationMemorySnapshot.empty()
                : state.getMemorySnapshot();
        TravelConversationDocument document = repository.findById(state.getSessionId()).orElse(null);
        if (document == null) {
            return base;
        }

        List<Map<String, Object>> existingRag = normalizeRag(document.getRag());
        boolean initialized = Boolean.TRUE.equals(document.getRagInitialized()) || !existingRag.isEmpty();
        if (initialized) {
            return withDocumentRag(base, document, existingRag);
        }

        String enhancedQuery = enhanceRagQuery(state, base);
        KnowledgeSearchResult retrieval = knowledgeBaseService.searchKnowledge(enhancedQuery);
        List<Map<String, Object>> rag = retrieval.getHits().stream()
                .map(this::toRagMemoryItem)
                .toList();

        document.setRag(rag);
        document.setRagQuery(enhancedQuery);
        document.setRagRewrittenQuery(retrieval.getRewrittenQuery());
        document.setRagRecognizedDestinations(retrieval.getRecognizedDestinations());
        document.setRagInitialized(true);
        document.setRagCleared(false);
        repository.save(document);

        return buildSnapshotWithRag(
                base,
                enhancedQuery,
                retrieval.getRewrittenQuery(),
                retrieval.getRecognizedDestinations(),
                rag,
                true,
                false);
    }

    public ConversationMemorySnapshot clearSessionRag(String sessionId) {
        TravelConversationDocument document = repository.findById(sessionId).orElse(null);
        if (document == null) {
            return ConversationMemorySnapshot.empty();
        }

        document.setRag(List.of());
        document.setRagQuery("");
        document.setRagRewrittenQuery("");
        document.setRagRecognizedDestinations(List.of());
        document.setRagInitialized(true);
        document.setRagCleared(true);
        repository.save(document);

        return new ConversationMemorySnapshot(
                trimToEmpty(document.getMemorySummary()),
                trimToEmpty(document.getPreferenceMemory()),
                List.of(),
                buildPromptContext(
                        trimToEmpty(document.getPreferenceMemory()),
                        trimToEmpty(document.getMemorySummary()),
                        List.of(),
                        "",
                        "",
                        List.of(),
                        List.of(),
                        true,
                        true),
                List.of(),
                "",
                "",
                List.of(),
                true,
                true);
    }

    public void rememberFinalAnswer(ConversationState state, String finalAnswer) {
        if (StringUtils.hasText(finalAnswer)) {
            chatMemory.add(state.getSessionId(), new AssistantMessage(finalAnswer));
        }
    }

    public void persistCompressedMemory(ConversationState state, String finalAnswer) {
        repository.findById(state.getSessionId()).ifPresent(document -> {
            if (StringUtils.hasText(finalAnswer)) {
                document.setFinalAnswer(finalAnswer);
            }
            List<TravelConversationDocument> relatedDocuments = findRelatedDocuments(state);
            document.setPreferenceMemory(buildPreferenceSummary(relatedDocuments, state.getRequest()));
            document.setMemorySummary(buildSessionSummary(document, relatedDocuments, state.getRequest()));
            document.setMemoryHighlights(extractHighlights(document, finalAnswer));
            repository.save(document);
        });
    }

    private List<TravelConversationDocument> findRelatedDocuments(ConversationState state) {
        List<TravelConversationDocument> related = new ArrayList<>();
        repository.findById(state.getSessionId()).ifPresent(related::add);

        String userId = state.getRequest().getUserId();
        if (!StringUtils.hasText(userId)) {
            return related;
        }

        repository.findTop5ByUserIdAndSessionIdNotOrderByUpdatedAtDesc(userId, state.getSessionId())
                .stream()
                .limit(maxHistorySessions)
                .forEach(related::add);
        return related;
    }

    private void seedChatMemory(String sessionId, TravelConversationDocument currentDocument, String currentUserMessage) {
        if (!chatMemory.get(sessionId).isEmpty()) {
            return;
        }
        if (currentDocument == null) {
            return;
        }
        if (StringUtils.hasText(currentDocument.getPreferenceMemory())) {
            chatMemory.add(sessionId, new SystemMessage("历史偏好记忆：" + currentDocument.getPreferenceMemory()));
        }
        if (StringUtils.hasText(currentDocument.getMemorySummary())) {
            chatMemory.add(sessionId, new SystemMessage("历史会话摘要：" + currentDocument.getMemorySummary()));
        }
        List<ConversationMessageRecord> messages = conversationMessages(currentDocument);
        int start = Math.max(0, messages.size() - 6);
        for (int index = start; index < messages.size(); index++) {
            ConversationMessageRecord message = messages.get(index);
            String content = trimToNull(message.getContent());
            if (!StringUtils.hasText(content)) {
                continue;
            }

            boolean isCurrentPendingUserMessage = StringUtils.hasText(currentUserMessage)
                    && "USER".equals(message.getRole())
                    && currentUserMessage.equals(content);
            if (isCurrentPendingUserMessage) {
                continue;
            }

            if ("USER".equals(message.getRole())) {
                chatMemory.add(sessionId, new UserMessage(content));
            } else if ("ASSISTANT".equals(message.getRole())) {
                chatMemory.add(sessionId, new AssistantMessage(content));
            }
        }
    }

    private String buildPromptContext(String preferenceSummary,
                                      String sessionSummary,
                                      List<String> chatWindow,
                                      String ragQuery,
                                      String ragRewrittenQuery,
                                      List<String> ragDestinations,
                                      List<Map<String, Object>> rag,
                                      boolean ragInitialized,
                                      boolean ragCleared) {
        return """
                长期偏好记忆：
                %s

                历史会话压缩记忆：
                %s

                当前窗口记忆：
                %s

                会话级 RAG 增强知识 rag[]：
                %s
                """.formatted(
                preferenceSummary,
                sessionSummary,
                chatWindow.isEmpty() ? "暂无" : String.join("\n", chatWindow),
                formatRagMemory(ragQuery, ragRewrittenQuery, ragDestinations, rag, ragInitialized, ragCleared));
    }

    private ConversationMemorySnapshot withDocumentRag(ConversationMemorySnapshot base,
                                                       TravelConversationDocument document,
                                                       List<Map<String, Object>> rag) {
        return buildSnapshotWithRag(
                base,
                trimToEmpty(document.getRagQuery()),
                trimToEmpty(document.getRagRewrittenQuery()),
                document.getRagRecognizedDestinations() == null ? List.of() : document.getRagRecognizedDestinations(),
                rag,
                true,
                Boolean.TRUE.equals(document.getRagCleared()));
    }

    private ConversationMemorySnapshot buildSnapshotWithRag(ConversationMemorySnapshot base,
                                                            String ragQuery,
                                                            String ragRewrittenQuery,
                                                            List<String> ragDestinations,
                                                            List<Map<String, Object>> rag,
                                                            boolean ragInitialized,
                                                            boolean ragCleared) {
        String promptContext = buildPromptContext(
                base.getPreferenceSummary(),
                base.getSessionSummary(),
                base.getChatWindow(),
                ragQuery,
                ragRewrittenQuery,
                ragDestinations,
                rag,
                ragInitialized,
                ragCleared);
        return base.withRag(promptContext, rag, ragQuery, ragRewrittenQuery, ragDestinations, ragInitialized, ragCleared);
    }

    private String formatRagMemory(String ragQuery,
                                   String ragRewrittenQuery,
                                   List<String> ragDestinations,
                                   List<Map<String, Object>> rag,
                                   boolean ragInitialized,
                                   boolean ragCleared) {
        if (ragCleared) {
            return "rag[] 已被工具 memory.rag.clear 清除；本 session 不会自动重复检索。";
        }
        if (!ragInitialized) {
            return "尚未执行本 session 的一次性 RAG 检索。";
        }
        if (rag == null || rag.isEmpty()) {
            return "已执行本 session 的一次性 RAG 检索，但没有可注入命中。";
        }

        String metadata = """
                检索查询：%s
                重写查询：%s
                识别目的地：%s
                """.formatted(
                StringUtils.hasText(ragQuery) ? ragQuery : "未记录",
                StringUtils.hasText(ragRewrittenQuery) ? ragRewrittenQuery : "未记录",
                ragDestinations == null || ragDestinations.isEmpty() ? "无" : String.join("、", ragDestinations));

        String items = rag.stream()
                .limit(5)
                .map(item -> "- [%s] %s（目的地：%s，来源：%s，score=%s）：%s".formatted(
                        trimToEmpty(item.get("id")),
                        trimToEmpty(item.get("title")),
                        trimToEmpty(item.get("destination")),
                        trimToEmpty(item.get("source")),
                        trimToEmpty(item.get("score")),
                        clip(trimToEmpty(item.get("content")), 360)))
                .collect(Collectors.joining("\n"));
        return metadata + items;
    }

    private String enhanceRagQuery(ConversationState state, ConversationMemorySnapshot base) {
        TravelChatRequest request = state.getRequest();
        String fallback = Stream.of(
                        request.getMessage(),
                        request.getDestination(),
                        request.getDeparture(),
                        request.getTravelDays(),
                        request.getBudget(),
                        request.getPreferences())
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(" "));
        if (!StringUtils.hasText(fallback)) {
            fallback = "旅游路线规划";
        }

        String llmQuery = llmFacade.complete("""
                你是旅游 RAG 查询重写器。
                请把用户最新需求、已补全的字段和会话记忆压缩成一条适合旅游知识库检索的中文查询。
                输出要求：
                - 只输出一行查询文本，不要 JSON、markdown 或解释
                - 保留目的地、天数、预算、偏好、出发地等关键约束
                - 不要虚构明确事实；缺失项可以用“未定”表达
                - 控制在 120 字以内
                """, """
                用户最新消息：%s
                目的地：%s
                出发地：%s
                行程天数：%s
                预算：%s
                偏好：%s
                会话记忆：
                %s
                """.formatted(
                blankAs(request.getMessage(), "未提供"),
                blankAs(request.getDestination(), "未定"),
                blankAs(request.getDeparture(), "未定"),
                blankAs(request.getTravelDays(), "未定"),
                blankAs(request.getBudget(), "未定"),
                blankAs(request.getPreferences(), "未定"),
                base == null || base.getPromptContext().isBlank() ? "暂无" : base.getPromptContext()));

        String sanitized = sanitizeTextLine(llmQuery);
        return StringUtils.hasText(sanitized) ? clip(sanitized, 180) : fallback;
    }

    private Map<String, Object> toRagMemoryItem(KnowledgeHit hit) {
        Map<String, Object> payload = new LinkedHashMap<>(hit.toDisplayMap());
        Object content = payload.get("content");
        if (content != null) {
            payload.put("content", clip(String.valueOf(content), 1200));
        }
        return payload;
    }

    private List<Map<String, Object>> normalizeRag(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(Objects::nonNull)
                .<Map<String, Object>>map(LinkedHashMap::new)
                .toList();
    }

    private String buildPreferenceSummary(List<TravelConversationDocument> relatedDocuments, TravelChatRequest request) {
        LinkedHashSet<String> destinations = new LinkedHashSet<>();
        LinkedHashSet<String> preferences = new LinkedHashSet<>();
        LinkedHashSet<String> budgets = new LinkedHashSet<>();
        LinkedHashSet<String> departures = new LinkedHashSet<>();
        LinkedHashSet<String> travelDays = new LinkedHashSet<>();

        addIfPresent(destinations, request.getDestination());
        addIfPresent(preferences, request.getPreferences());
        addIfPresent(budgets, request.getBudget());
        addIfPresent(departures, request.getDeparture());
        addIfPresent(travelDays, request.getTravelDays());

        for (TravelConversationDocument document : relatedDocuments) {
            collectFromSnapshot(document.getLastRequestSnapshot(), destinations, preferences, budgets, departures, travelDays);
            collectFromSnapshot(document.getRequestSnapshot(), destinations, preferences, budgets, departures, travelDays);
            for (ConversationMessageRecord message : conversationMessages(document)) {
                if ("USER".equals(message.getRole())) {
                    collectFromSnapshot(message.getRequestSnapshot(), destinations, preferences, budgets, departures, travelDays);
                }
            }
        }

        List<String> lines = new ArrayList<>();
        if (!destinations.isEmpty()) {
            lines.add("- 近期目的地偏好：" + String.join("、", limit(destinations, 4)));
        }
        if (!preferences.isEmpty()) {
            lines.add("- 玩法与兴趣：" + String.join("、", limit(preferences, 4)));
        }
        if (!budgets.isEmpty()) {
            lines.add("- 常见预算表达：" + String.join("、", limit(budgets, 3)));
        }
        if (!departures.isEmpty()) {
            lines.add("- 常见出发地：" + String.join("、", limit(departures, 3)));
        }
        if (!travelDays.isEmpty()) {
            lines.add("- 典型行程时长：" + String.join("、", limit(travelDays, 3)));
        }
        return lines.isEmpty() ? "暂无稳定偏好记忆。" : String.join("\n", lines);
    }

    private String buildSessionSummary(TravelConversationDocument currentDocument,
                                       List<TravelConversationDocument> relatedDocuments,
                                       TravelChatRequest request) {
        String rawContext = Stream.concat(
                        buildCurrentSessionFragments(currentDocument, request).stream(),
                        relatedDocuments.stream()
                                .skip(currentDocument == null ? 0 : 1)
                                .flatMap(document -> buildHistoricalFragments(document).stream()))
                .limit(maxHighlights)
                .collect(Collectors.joining("\n"));

        if (!StringUtils.hasText(rawContext)) {
            return "暂无可用的历史会话压缩记忆。";
        }

        String llmSummary = llmFacade.complete("""
                你是旅游规划助手的 Memory Compressor。
                请把给定的历史会话材料压缩成面向后续规划可用的中文要点。
                输出要求：
                - 只输出 3 到 6 条项目符号
                - 保留用户稳定偏好、已确认约束、已完成的重要规划结论
                - 不要虚构任何未出现的信息
                - 控制在 180 字以内
                """, rawContext);

        if (StringUtils.hasText(llmSummary)) {
            return llmSummary.trim();
        }

        return rawContext;
    }

    private List<String> buildCurrentSessionFragments(TravelConversationDocument currentDocument, TravelChatRequest request) {
        List<String> fragments = new ArrayList<>();
        if (StringUtils.hasText(request.getMessage())) {
            fragments.add("- 当前用户问题：" + clip(request.getMessage(), 120));
        }
        if (currentDocument == null) {
            return fragments;
        }
        if (StringUtils.hasText(currentDocument.getMemorySummary())) {
            fragments.add("- 当前会话已有压缩摘要：" + clip(currentDocument.getMemorySummary(), 140));
        }
        conversationMessages(currentDocument).stream()
                .filter(message -> "USER".equals(message.getRole()))
                .map(ConversationMessageRecord::getContent)
                .filter(StringUtils::hasText)
                .limit(3)
                .forEach(message -> fragments.add("- 当前会话历史需求：" + clip(message, 100)));
        conversationMessages(currentDocument).stream()
                .filter(message -> "ASSISTANT".equals(message.getRole()))
                .map(ConversationMessageRecord::getContent)
                .filter(StringUtils::hasText)
                .limit(2)
                .forEach(answer -> fragments.add("- 当前会话历史回复：" + clip(answer, 120)));
        if (!conversationEvents(currentDocument).isEmpty()) {
            conversationEvents(currentDocument).stream()
                    .filter(event -> "FINAL_ANSWER".equals(event.getEventType()))
                    .map(ConversationEventRecord::getPayload)
                    .map(payload -> payload == null ? null : payload.get("answer"))
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .filter(StringUtils::hasText)
                    .limit(1)
                    .forEach(answer -> fragments.add("- 当前会话已输出方案：" + clip(answer, 140)));
        }
        return fragments;
    }

    private List<String> buildHistoricalFragments(TravelConversationDocument document) {
        List<String> fragments = new ArrayList<>();
        if (StringUtils.hasText(document.getPreferenceMemory())) {
            fragments.add("- 历史偏好记忆：" + clip(document.getPreferenceMemory(), 140));
        }
        if (StringUtils.hasText(document.getMemorySummary())) {
            fragments.add("- 历史会话摘要：" + clip(document.getMemorySummary(), 140));
        }
        conversationMessages(document).stream()
                .filter(message -> "ASSISTANT".equals(message.getRole()))
                .map(ConversationMessageRecord::getContent)
                .filter(StringUtils::hasText)
                .limit(2)
                .forEach(answer -> fragments.add("- 历史助手回复：" + clip(answer, 140)));
        if (fragments.stream().noneMatch(item -> item.startsWith("- 历史助手回复：")) && StringUtils.hasText(document.getFinalAnswer())) {
            fragments.add("- 历史助手回复：" + clip(document.getFinalAnswer(), 140));
        }
        return fragments;
    }

    private List<String> formatChatWindow(List<Message> messages) {
        return messages.stream()
                .map(this::formatMessage)
                .filter(StringUtils::hasText)
                .limit(maxHighlights)
                .toList();
    }

    private String formatMessage(Message message) {
        if (message instanceof UserMessage userMessage) {
            return "- 用户：" + clip(userMessage.getText(), 80);
        }
        if (message instanceof AssistantMessage assistantMessage) {
            return "- 助手：" + clip(assistantMessage.getText(), 80);
        }
        if (message instanceof SystemMessage systemMessage) {
            return "- 系统记忆：" + clip(systemMessage.getText(), 80);
        }
        return null;
    }

    private List<String> extractHighlights(TravelConversationDocument document, String finalAnswer) {
        LinkedHashSet<String> highlights = new LinkedHashSet<>();
        conversationMessages(document).stream()
                .filter(message -> "USER".equals(message.getRole()))
                .map(ConversationMessageRecord::getContent)
                .filter(StringUtils::hasText)
                .map(text -> "用户需求：" + clip(text, 100))
                .forEach(highlights::add);
        conversationMessages(document).stream()
                .filter(message -> "ASSISTANT".equals(message.getRole()))
                .map(ConversationMessageRecord::getContent)
                .filter(StringUtils::hasText)
                .map(text -> "助手回复：" + clip(text, 120))
                .forEach(highlights::add);
        if (StringUtils.hasText(finalAnswer)) {
            highlights.add("助手最新回复：" + clip(finalAnswer, 120));
        } else if (StringUtils.hasText(document.getFinalAnswer())) {
            highlights.add("助手最新回复：" + clip(document.getFinalAnswer(), 120));
        }
        return limit(highlights, maxHighlights);
    }

    private void mergeFieldsFromMessage(TravelChatRequest request) {
        if (!StringUtils.hasText(request.getMessage())) {
            return;
        }

        String llmResult = llmFacade.complete("""
                你是旅游对话信息抽取器。
                请从用户当前这一条消息中抽取旅行约束，并输出 JSON。
                要求：
                - 只抽取当前消息中明确出现的信息，不要用历史补全，不要猜测
                - 如果字段没有出现，值就填空字符串
                - 只输出 JSON，不要输出 markdown
                JSON 结构：
                {
                  "destination": "",
                  "departure": "",
                  "travelDays": "",
                  "budget": "",
                  "preferences": ""
                }
                """, request.getMessage());
        if (!StringUtils.hasText(llmResult)) {
            return;
        }

        try {
            Map<String, String> parsed = objectMapper.readValue(sanitizeJsonPayload(llmResult), new TypeReference<Map<String, String>>() {
            });
            applyExplicitValue(request::getDestination, request::setDestination, parsed.get("destination"));
            applyExplicitValue(request::getDeparture, request::setDeparture, parsed.get("departure"));
            applyExplicitValue(request::getTravelDays, request::setTravelDays, parsed.get("travelDays"));
            applyExplicitValue(request::getBudget, request::setBudget, parsed.get("budget"));
            applyExplicitValue(request::getPreferences, request::setPreferences, parsed.get("preferences"));
        } catch (Exception ignored) {
        }
    }

    private void mergeFieldsFromHistory(String sessionId, TravelChatRequest request) {
        if (!hasBlankFields(request) || !StringUtils.hasText(sessionId)) {
            return;
        }
        TravelConversationDocument document = repository.findById(sessionId).orElse(null);
        if (document == null) {
            return;
        }

        List<Map<String, Object>> snapshots = new ArrayList<>();
        List<ConversationMessageRecord> messages = new ArrayList<>(conversationMessages(document));
        if (!messages.isEmpty()) {
            Collections.reverse(messages);
            for (ConversationMessageRecord message : messages) {
                if (!"USER".equals(message.getRole())) {
                    continue;
                }
                if (message.getRequestSnapshot() != null && !message.getRequestSnapshot().isEmpty()) {
                    snapshots.add(message.getRequestSnapshot());
                }
            }
        }
        if (document.getLastRequestSnapshot() != null && !document.getLastRequestSnapshot().isEmpty()) {
            snapshots.add(document.getLastRequestSnapshot());
        }
        if (document.getRequestSnapshot() != null && !document.getRequestSnapshot().isEmpty()) {
            snapshots.add(document.getRequestSnapshot());
        }

        for (Map<String, Object> snapshot : snapshots) {
            applyIfBlank(request::getDestination, request::setDestination, snapshot.get("destination"));
            applyIfBlank(request::getDeparture, request::setDeparture, snapshot.get("departure"));
            applyIfBlank(request::getTravelDays, request::setTravelDays, snapshot.get("travelDays"));
            applyIfBlank(request::getBudget, request::setBudget, snapshot.get("budget"));
            applyIfBlank(request::getPreferences, request::setPreferences, snapshot.get("preferences"));
        }
    }

    private void collectFromSnapshot(Map<String, Object> snapshot,
                                     LinkedHashSet<String> destinations,
                                     LinkedHashSet<String> preferences,
                                     LinkedHashSet<String> budgets,
                                     LinkedHashSet<String> departures,
                                     LinkedHashSet<String> travelDays) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        addIfPresent(destinations, snapshot.get("destination"));
        addIfPresent(preferences, snapshot.get("preferences"));
        addIfPresent(budgets, snapshot.get("budget"));
        addIfPresent(departures, snapshot.get("departure"));
        addIfPresent(travelDays, snapshot.get("travelDays"));
    }

    private void addIfPresent(LinkedHashSet<String> target, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isEmpty() && !"未说明".equals(text)) {
            target.add(text);
        }
    }

    private List<String> limit(LinkedHashSet<String> values, int limit) {
        return values.stream().limit(limit).toList();
    }

    private String clip(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim() + "...";
    }

    private boolean hasBlankFields(TravelChatRequest request) {
        return !StringUtils.hasText(request.getDestination())
                || !StringUtils.hasText(request.getDeparture())
                || !StringUtils.hasText(request.getTravelDays())
                || !StringUtils.hasText(request.getBudget())
                || !StringUtils.hasText(request.getPreferences());
    }

    private void applyIfBlank(java.util.function.Supplier<String> getter,
                              java.util.function.Consumer<String> setter,
                              Object candidate) {
        if (StringUtils.hasText(getter.get())) {
            return;
        }
        String text = candidate == null ? null : String.valueOf(candidate).trim();
        if (StringUtils.hasText(text) && !"未说明".equals(text)) {
            setter.accept(text);
        }
    }

    private void applyExplicitValue(java.util.function.Supplier<String> getter,
                                    java.util.function.Consumer<String> setter,
                                    String candidate) {
        if (StringUtils.hasText(getter.get())) {
            return;
        }
        if (StringUtils.hasText(candidate)) {
            setter.accept(candidate.trim());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private List<ConversationMessageRecord> conversationMessages(TravelConversationDocument document) {
        if (document == null) {
            return List.of();
        }
        if (document.getMessages() != null && !document.getMessages().isEmpty()) {
            return document.getMessages();
        }
        if (document.getTurns() == null || document.getTurns().isEmpty()) {
            return List.of();
        }

        List<ConversationMessageRecord> fallback = new ArrayList<>();
        long sequence = 1L;
        for (ConversationTurnRecord turn : document.getTurns()) {
            String userMessage = firstNonBlank(
                    turn.getUserMessage(),
                    turn.getRequestSnapshot() == null ? null : String.valueOf(turn.getRequestSnapshot().get("message")));
            if (StringUtils.hasText(userMessage)) {
                fallback.add(new ConversationMessageRecord(
                        null,
                        null,
                        "USER",
                        userMessage,
                        "USER_INPUT",
                        turn.getStatus(),
                        sequence++,
                        turn.getTimestamp(),
                        turn.getTimestamp(),
                        turn.getRequestSnapshot()));
            }
            if (StringUtils.hasText(turn.getAssistantMessage())) {
                fallback.add(new ConversationMessageRecord(
                        null,
                        null,
                        "ASSISTANT",
                        turn.getAssistantMessage(),
                        turn.getAssistantMessageType(),
                        turn.getStatus(),
                        sequence++,
                        turn.getTimestamp(),
                        turn.getTimestamp(),
                        null));
            }
        }
        return fallback;
    }

    private List<ConversationEventRecord> conversationEvents(TravelConversationDocument document) {
        if (document == null) {
            return List.of();
        }
        if (document.getTraceEvents() != null && !document.getTraceEvents().isEmpty()) {
            return document.getTraceEvents();
        }
        if (document.getEvents() != null) {
            return document.getEvents();
        }
        return List.of();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String trimToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String blankAs(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String sanitizeTextLine(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String sanitized = raw.trim();
        if (sanitized.startsWith("```")) {
            sanitized = sanitized.replaceFirst("^```(?:text|json)?\\s*", "");
            sanitized = sanitized.replaceFirst("\\s*```$", "");
        }
        return sanitized.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("");
    }

    private String sanitizeJsonPayload(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }
}

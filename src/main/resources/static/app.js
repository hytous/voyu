const SESSION_USER_ID = "voyu-qt";
const SESSION_LIMIT = 30;
const REASONING_EVENT_TYPES = new Set(["THOUGHT", "PLAN_DRAFT", "TASK_BOOK", "TOOL_CALL", "WARNING", "MODE_SWITCH", "STEP_REMINDER"]);

const EVENT_LABELS = {
    THOUGHT: "主循环思考",
    PLAN_DRAFT: "规划器草案",
    TASK_BOOK: "任务书",
    TASK_STATUS: "任务状态",
    TOOL_CALL: "工具调用",
    TOOL_RESULT: "工具结果",
    WARNING: "异常告警",
    FINAL_ANSWER: "最终方案",
    MEMORY: "记忆加载",
    MODE_SWITCH: "模式切换",
    STEP_REMINDER: "步数提醒",
    PLAN_FILE: "计划文件",
};

const TOOL_LABELS = {
    "profile.lookup": "用户画像",
    "weather.lookup": "天气检查",
    "map.poi.search": "POI 检索",
    "map.route.plan": "路线规划",
    "web.search": "网页搜索",
    "rag.travel.knowledge": "知识检索",
    "budget.audit": "预算审查",
    "memory.rag.clear": "清理 RAG 记忆",
};

const MESSAGE_TYPE_LABELS = {
    USER_INPUT: "用户输入",
    STREAM_STATUS: "流式状态",
    FINAL: "最终回答",
    ERROR: "错误",
    CLARIFY: "追问",
};

const state = {
    currentSession: null,
    serverSessions: [],
    infrastructure: null,
    isStreaming: false,
    abortController: null,
    sessionsLoading: false,
    streamTone: "idle",
    streamText: "空闲",
    _tickTimer: null,   // 计时器 id
};

const elements = {
    sidebar: document.getElementById("sidebar"),
    mobileBackdrop: document.getElementById("mobileBackdrop"),
    sidebarToggle: document.getElementById("sidebarToggle"),
    workspaceBody: document.getElementById("workspaceBody"),
    newSessionButton: document.getElementById("newSessionButton"),
    headerNewSessionButton: document.getElementById("headerNewSessionButton"),
    heroNewSessionButton: document.getElementById("heroNewSessionButton"),
    heroFocusFormButton: document.getElementById("heroFocusFormButton"),
    refreshSessionsButton: document.getElementById("refreshSessionsButton"),
    backHomeButton: document.getElementById("backHomeButton"),
    sessionList: document.getElementById("sessionList"),
    sessionSearchInput: document.getElementById("sessionSearchInput"),
    sessionCountBadge: document.getElementById("sessionCountBadge"),
    heroPanel: document.getElementById("heroPanel"),
    streamStatusBadge: document.getElementById("streamStatusBadge"),
    conversationList: document.getElementById("conversationList"),
    userBrief: document.getElementById("userBrief"),
    requestMetaChips: document.getElementById("requestMetaChips"),
    sessionStatusPill: document.getElementById("sessionStatusPill"),
    reasoningList: document.getElementById("reasoningList"),
    planThoughtBlock: document.getElementById("planThoughtBlock"),
    missionBlock: document.getElementById("missionBlock"),
    taskBoard: document.getElementById("taskBoard"),
    finalAnswerBody: document.getElementById("finalAnswerBody"),
    knowledgeList: document.getElementById("knowledgeList"),
    timelineList: document.getElementById("timelineList"),
    infraGrid: document.getElementById("infraGrid"),
    refreshInfraButton: document.getElementById("refreshInfraButton"),
    deleteSessionButton: document.getElementById("deleteSessionButton"),
    sessionIdValue: document.getElementById("sessionIdValue"),
    sessionUpdatedAt: document.getElementById("sessionUpdatedAt"),
    sessionErrorValue: document.getElementById("sessionErrorValue"),
    memorySummaryBody: document.getElementById("memorySummaryBody"),
    preferenceMemoryBody: document.getElementById("preferenceMemoryBody"),
    memoryHighlightsList: document.getElementById("memoryHighlightsList"),
    ragSummaryList: document.getElementById("ragSummaryList"),
    travelForm: document.getElementById("travelForm"),
    messageInput: document.getElementById("messageInput"),
    destinationInput: document.getElementById("destinationInput"),
    departureInput: document.getElementById("departureInput"),
    travelDaysInput: document.getElementById("travelDaysInput"),
    budgetInput: document.getElementById("budgetInput"),
    preferencesInput: document.getElementById("preferencesInput"),
    submitButton: document.getElementById("submitButton"),
    abortButton: document.getElementById("abortButton"),
};

bootstrap().catch((error) => {
    console.error(error);
    setStreamStatus("error", "初始化失败");
});

async function bootstrap() {
    bindEvents();
    renderSidebar();
    renderCurrentSession();
    await Promise.all([refreshSessions({ silent: true }), refreshInfrastructure()]);

    const sessionId = new URL(window.location.href).searchParams.get("session");
    if (sessionId) {
        await loadSession(sessionId);
    }
}

function bindEvents() {
    elements.sidebarToggle?.addEventListener("click", openSidebar);
    elements.mobileBackdrop?.addEventListener("click", closeSidebar);
    elements.newSessionButton?.addEventListener("click", () => showLanding({ clearForm: true, focusInput: true }));
    elements.headerNewSessionButton?.addEventListener("click", () => showLanding({ clearForm: true, focusInput: true }));
    elements.heroNewSessionButton?.addEventListener("click", () => showLanding({ clearForm: true, focusInput: true }));
    elements.heroFocusFormButton?.addEventListener("click", () => {
        showLanding({ clearForm: false, focusInput: false });
        scrollToComposer(true);
    });
    elements.backHomeButton?.addEventListener("click", () => showLanding({ clearForm: false, focusInput: false }));
    elements.refreshSessionsButton?.addEventListener("click", () => refreshSessions());
    elements.refreshInfraButton?.addEventListener("click", refreshInfrastructure);
    elements.deleteSessionButton?.addEventListener("click", async () => {
        if (state.currentSession?.sessionId) {
            await deleteSession(state.currentSession.sessionId);
        }
    });
    elements.travelForm?.addEventListener("submit", handleSubmit);
    elements.abortButton?.addEventListener("click", abortActiveStream);
    elements.sessionSearchInput?.addEventListener("input", () => renderSidebar());
}

function openSidebar() {
    elements.sidebar?.classList.add("mobile-open");
    elements.mobileBackdrop?.classList.add("visible");
}

function closeSidebar() {
    elements.sidebar?.classList.remove("mobile-open");
    elements.mobileBackdrop?.classList.remove("visible");
}

function showLanding({ clearForm = false, focusInput = false } = {}) {
    abortActiveStream();
    state.currentSession = null;
    updateUrlSession(null);
    if (clearForm) {
        elements.travelForm?.reset();
    }
    renderCurrentSession();
    renderSidebar();
    closeSidebar();
    scrollWorkspaceTop();
    if (focusInput) {
        focusComposer();
    }
}

function scrollWorkspaceTop() {
    elements.workspaceBody?.scrollTo({ top: 0, behavior: "smooth" });
}

function scrollToComposer(focus = false) {
    elements.travelForm?.scrollIntoView({ behavior: "smooth", block: "start" });
    if (focus) {
        focusComposer();
    }
}

function scrollConversationTop() {
    elements.conversationList?.scrollTo({ top: 0, behavior: "smooth" });
}

function focusComposer() {
    window.setTimeout(() => elements.messageInput?.focus(), 180);
}

async function refreshSessions({ silent = false } = {}) {
    state.sessionsLoading = true;
    renderSidebar();

    try {
        const response = await fetch(`/api/travel-agent/sessions?limit=${SESSION_LIMIT}`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        state.serverSessions = await response.json();
    } catch (error) {
        console.error("failed to load sessions", error);
        if (!silent) {
            setStreamStatus("warn", "会话列表失败");
        }
    } finally {
        state.sessionsLoading = false;
        renderSidebar();
    }
}

function buildSidebarSessions() {
    const merged = new Map();

    state.serverSessions.forEach((session) => {
        merged.set(session.sessionId, { ...session, draft: false });
    });

    if (state.currentSession?.sessionId) {
        merged.set(state.currentSession.sessionId, summaryFromSession(state.currentSession));
    }

    const sessions = [...merged.values()].sort((left, right) => {
        const leftTime = Date.parse(left.updatedAt || "") || 0;
        const rightTime = Date.parse(right.updatedAt || "") || 0;
        return rightTime - leftTime;
    });

    if (state.currentSession && !state.currentSession.sessionId && hasText(state.currentSession.requestSnapshot?.message)) {
        sessions.unshift(summaryFromSession(state.currentSession, { draft: true }));
    }

    return sessions;
}

function summaryFromSession(session, options = {}) {
    const latestRequest = session.requestSnapshot || {};
    return {
        sessionId: session.sessionId || null,
        title: session.title || buildSessionTitle(latestRequest.message),
        preview: session.preview || clip(stripMarkdown(latestAssistantContent(session) || latestRequest.message || ""), 88),
        destination: latestRequest.destination || "",
        travelDays: latestRequest.travelDays || "",
        status: session.status || "RUNNING",
        createdAt: session.createdAt || session.updatedAt || new Date().toISOString(),
        updatedAt: session.updatedAt || new Date().toISOString(),
        messageCount: safeArray(session.messages).length,
        draft: Boolean(options.draft),
    };
}

function renderSidebar() {
    const list = elements.sessionList;
    if (!list) {
        return;
    }

    const sessions = buildSidebarSessions();

    // 左侧搜索框过滤
    const searchTerm = (elements.sessionSearchInput?.value || "").trim().toLowerCase();
    const filtered = searchTerm
        ? sessions.filter((s) => [
              s.title, s.preview, s.destination, s.travelDays, s.status
          ].join(" ").toLowerCase().includes(searchTerm))
        : sessions;

    elements.sessionCountBadge.textContent = String(filtered.length);
    list.innerHTML = "";

    if (state.sessionsLoading && filtered.length === 0) {
        list.innerHTML = '<div class="empty-state">正在读取服务器历史会话...</div>';
        return;
    }

    if (filtered.length === 0) {
        list.innerHTML = `<div class="empty-state">${searchTerm ? "没有匹配的会话。" : "还没有历史会话，先开始一次旅行对话。"}</div>`;
        return;
    }

    filtered.forEach((session) => {
        const item = document.createElement("article");
        const isActive = isCurrentSidebarSession(session);
        item.className = `session-item${isActive ? " active" : ""}${session.draft ? " draft" : ""}`;

        const mainButton = document.createElement("button");
        mainButton.type = "button";
        mainButton.className = "session-main";
        mainButton.innerHTML = `
            <div class="session-title-row">
                <p class="session-title">${escapeHtml(clip(session.title || "新的旅行对话", 24))}</p>
                <span class="session-status-mini ${statusTone(session.status)}">${escapeHtml(statusLabel(session.status))}</span>
            </div>
            <p class="session-preview">${escapeHtml(clip(session.preview || "暂无预览", 58))}</p>
            <div class="session-meta-line">
                <span>${escapeHtml(session.destination || "未填目的地")}</span>
                <span>${escapeHtml(session.travelDays || formatShortTime(session.updatedAt))}</span>
            </div>
        `;
        mainButton.addEventListener("click", async () => {
            closeSidebar();
            if (session.draft || !session.sessionId) {
                renderCurrentSession();
                return;
            }
            await loadSession(session.sessionId);
        });

        const deleteButton = document.createElement("button");
        deleteButton.type = "button";
        deleteButton.className = "session-delete";
        deleteButton.textContent = "✕";
        deleteButton.title = "删除会话";
        deleteButton.disabled = session.draft || (state.isStreaming && session.sessionId === state.currentSession?.sessionId);
        deleteButton.addEventListener("click", async (event) => {
            event.stopPropagation();
            if (session.sessionId) {
                await deleteSession(session.sessionId);
            }
        });

        item.append(mainButton, deleteButton);
        list.appendChild(item);
    });
}

function isCurrentSidebarSession(summary) {
    if (!summary) {
        return false;
    }
    if (summary.draft) {
        return Boolean(state.currentSession) && !state.currentSession.sessionId;
    }
    return summary.sessionId && summary.sessionId === state.currentSession?.sessionId;
}

function populateForm(snapshot = {}) {
    elements.messageInput.value = snapshot.message || "";
    elements.destinationInput.value = snapshot.destination || "";
    elements.departureInput.value = snapshot.departure || "";
    elements.travelDaysInput.value = snapshot.travelDays || "";
    elements.budgetInput.value = snapshot.budget || "";
    elements.preferencesInput.value = snapshot.preferences || "";
}

function collectFormPayload() {
    return {
        message: elements.messageInput.value.trim(),
        destination: elements.destinationInput.value.trim(),
        departure: elements.departureInput.value.trim(),
        travelDays: elements.travelDaysInput.value.trim(),
        budget: elements.budgetInput.value.trim(),
        preferences: elements.preferencesInput.value.trim(),
        userId: SESSION_USER_ID,
        sessionId: state.currentSession?.sessionId || undefined,
    };
}

async function handleSubmit(event) {
    event.preventDefault();
    if (state.isStreaming) {
        return;
    }

    const payload = collectFormPayload();
    if (!payload.message) {
        elements.messageInput.focus();
        return;
    }

    state.currentSession = prepareSessionForSubmit(payload);
    renderCurrentSession();
    renderSidebar();
    scrollConversationTop();
    elements.messageInput.value = "";
    await streamTravelPlan(payload);
}

function prepareSessionForSubmit(payload) {
    const continueCurrent = Boolean(state.currentSession && state.currentSession.sessionId && state.currentSession.sessionId === payload.sessionId);
    const session = continueCurrent
        ? state.currentSession
        : createSession(payload.sessionId || null, payload);

    session.requestSnapshot = buildRequestSnapshot(payload);
    session.title = session.title || buildSessionTitle(payload.message);
    session.preview = clip(payload.message, 88);
    session.status = "RUNNING";
    session.finalAnswer = "";
    session.errorMessage = "";
    session.planThought = "";
    session.mission = "";
    session.taskBook = null;
    session.taskStatuses = {};
    session.knowledgeHits = [];
    session.completedAt = null;
    session.updatedAt = new Date().toISOString();

    if (!continueCurrent) {
        session.messages = [];
        session.events = [];
        session.createdAt = session.updatedAt;
    }

    appendMessage(session, buildLocalMessage(session, "USER", payload.message, "USER_INPUT", "COMPLETED", payload));
    syncPendingAssistantMessage(session, "思考中，正在整理本轮需求与工具执行计划...");
    return session;
}

function createSession(sessionId, requestSnapshot = {}) {
    return {
        sessionId: sessionId || null,
        title: buildSessionTitle(requestSnapshot.message),
        preview: clip(requestSnapshot.message || "", 88),
        requestSnapshot: buildRequestSnapshot(requestSnapshot),
        status: "DRAFT",
        finalAnswer: "",
        errorMessage: "",
        updatedAt: null,
        createdAt: null,
        completedAt: null,
        planThought: "",
        mission: "",
        taskBook: null,
        taskStatuses: {},
        knowledgeHits: [],
        memorySummary: "",
        preferenceMemory: "",
        memoryHighlights: [],
        rag: [],
        ragQuery: "",
        ragRewrittenQuery: "",
        ragRecognizedDestinations: [],
        ragInitialized: false,
        ragCleared: false,
        messages: [],
        events: [],
    };
}

async function streamTravelPlan(payload) {
    setStreamingState(true);
    setStreamStatus("busy", "思考中");
    const controller = new AbortController();
    state.abortController = controller;

    try {
        const response = await fetch("/api/travel-agent/stream", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "text/event-stream",
            },
            body: JSON.stringify(payload),
            signal: controller.signal,
        });

        if (!response.ok || !response.body) {
            throw new Error(`HTTP ${response.status}`);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");
        let buffer = "";

        while (true) {
            const { value, done } = await reader.read();
            if (done) {
                break;
            }
            buffer += decoder.decode(value, { stream: true });
            const segments = buffer.split("\n\n");
            buffer = segments.pop() || "";

            for (const segment of segments) {
                const parsed = parseSseMessage(segment);
                if (parsed) {
                    ingestEvent(parsed);
                }
            }
        }

        if (state.currentSession && state.currentSession.status === "RUNNING") {
            state.currentSession.status = state.currentSession.errorMessage ? "FAILED" : "COMPLETED";
            state.currentSession.updatedAt ||= new Date().toISOString();
            if (state.currentSession.finalAnswer) {
                finalizeAssistantMessage(state.currentSession, state.currentSession.finalAnswer, "COMPLETED", "FINAL");
            }
        }

        await refreshSessions({ silent: true });
        setStreamStatus(state.currentSession?.errorMessage ? "error" : "live", state.currentSession?.errorMessage ? "执行失败" : "已完成");
    } catch (error) {
        if (error.name === "AbortError") {
            setStreamStatus("warn", "已中止");
            if (state.currentSession) {
                state.currentSession.status = "ABORTED";
                state.currentSession.errorMessage = "流式任务已被手动中止。";
                state.currentSession.updatedAt = new Date().toISOString();
                finalizeAssistantMessage(state.currentSession, state.currentSession.errorMessage, "FAILED", "ERROR");
            }
            await refreshSessions({ silent: true });
            renderCurrentSession();
            renderSidebar();
            return;
        }

        console.error("stream failed", error);
        if (state.currentSession) {
            state.currentSession.status = "FAILED";
            state.currentSession.errorMessage = `请求失败：${error.message}`;
            state.currentSession.updatedAt = new Date().toISOString();
            finalizeAssistantMessage(state.currentSession, state.currentSession.errorMessage, "FAILED", "ERROR");
        }
        setStreamStatus("error", "请求失败");
        await refreshSessions({ silent: true });
    } finally {
        setStreamingState(false);
        state.abortController = null;
        renderCurrentSession();
        renderSidebar();
    }
}

function abortActiveStream() {
    if (state.abortController) {
        state.abortController.abort();
    }
}

function parseSseMessage(chunk) {
    const lines = chunk.split(/\r?\n/);
    const dataLines = [];
    for (const line of lines) {
        if (line.startsWith("data:")) {
            dataLines.push(line.slice(5).trimStart());
        }
    }
    if (dataLines.length === 0) {
        return null;
    }
    try {
        return JSON.parse(dataLines.join("\n"));
    } catch (error) {
        console.warn("Failed to parse SSE message", error, dataLines.join("\n"));
        return null;
    }
}

function ingestEvent(event) {
    if (!state.currentSession) {
        state.currentSession = createSession(event.sessionId, {});
        state.currentSession.status = "RUNNING";
    }

    let refreshNeeded = false;
    if (event.sessionId && !state.currentSession.sessionId) {
        state.currentSession.sessionId = event.sessionId;
        updateUrlSession(event.sessionId);
        refreshNeeded = true;
    }

    applyEventState(state.currentSession, event, { syncMessages: true });
    sortSessionEvents(state.currentSession);
    renderCurrentSession();
    renderSidebar();

    if (refreshNeeded) {
        refreshSessions({ silent: true }).catch((error) => console.error("failed to refresh sessions", error));
    }
}

function applyEventState(session, event, options = {}) {
    const normalized = normalizeEvent(event);
    const syncMessages = options.syncMessages !== false;
    session.events.push(normalized);
    session.updatedAt = normalized.timestamp || new Date().toISOString();

    const payload = normalized.payload || {};
    switch (normalized.eventType) {
        case "THOUGHT":
            session.planThought = payload.message || session.planThought;
            session.status = "RUNNING";
            if (syncMessages) {
                syncPendingAssistantMessage(session, progressTextForEvent(normalized));
                setStreamStatus("busy", "思考中");
            }
            break;
        case "PLAN_DRAFT":
            session.planThought = payload.thought || session.planThought;
            if (syncMessages) {
                syncPendingAssistantMessage(session, progressTextForEvent(normalized));
                setStreamStatus("busy", "规划中");
            }
            break;
        case "TASK_BOOK":
            session.mission = payload.mission || "";
            session.taskBook = payload;
            session.taskStatuses = {};
            safeArray(payload.tasks).forEach((task) => {
                if (task.taskId) {
                    session.taskStatuses[task.taskId] = "PENDING";
                }
            });
            if (syncMessages) {
                syncPendingAssistantMessage(session, progressTextForEvent(normalized));
                setStreamStatus("busy", "执行中");
            }
            break;
        case "TASK_STATUS":
            if (payload.taskId) {
                session.taskStatuses[payload.taskId] = payload.status || "UNKNOWN";
            }
            // Also match by toolName: update any task in taskBook that uses this tool
            if (payload.toolName && session.taskBook) {
                safeArray(session.taskBook.tasks).forEach((task) => {
                    if (task.toolName === payload.toolName && task.taskId) {
                        session.taskStatuses[task.taskId] = payload.status || "UNKNOWN";
                    }
                });
            }
            if (syncMessages) {
                syncPendingAssistantMessage(session, progressTextForEvent(normalized));
            }
            break;
        case "TOOL_CALL":
            if (syncMessages) {
                syncPendingAssistantMessage(session, progressTextForEvent(normalized));
            }
            break;
        case "TOOL_RESULT":
            if (payload.toolName === "rag.travel.knowledge") {
                session.knowledgeHits = safeArray(payload.result?.hits);
            }
            if (payload.toolName === "memory.rag.clear") {
                session.rag = [];
                session.knowledgeHits = [];
                session.ragCleared = true;
            }
            if (syncMessages) {
                syncPendingAssistantMessage(session, progressTextForEvent(normalized));
            }
            break;
        case "MEMORY":
            // Populate session RAG data from MEMORY events
            if (safeArray(payload.rag).length > 0) {
                session.rag = payload.rag;
            }
            if (hasText(payload.ragQuery)) {
                session.ragQuery = payload.ragQuery;
            }
            if (hasText(payload.ragRewrittenQuery)) {
                session.ragRewrittenQuery = payload.ragRewrittenQuery;
            }
            if (safeArray(payload.ragRecognizedDestinations).length > 0) {
                session.ragRecognizedDestinations = payload.ragRecognizedDestinations;
            }
            if (payload.ragInitialized !== undefined) {
                session.ragInitialized = payload.ragInitialized;
            }
            if (payload.ragCleared !== undefined) {
                session.ragCleared = payload.ragCleared;
            }
            if (hasText(payload.sessionSummary)) {
                session.memorySummary = payload.sessionSummary;
            }
            if (hasText(payload.preferenceSummary)) {
                session.preferenceMemory = payload.preferenceSummary;
            }
            if (syncMessages) {
                syncPendingAssistantMessage(session, progressTextForEvent(normalized));
            }
            break;
        case "MODE_SWITCH":
            session.status = "RUNNING";
            if (syncMessages) {
                syncPendingAssistantMessage(session, progressTextForEvent(normalized));
                const toMode = payload.to || "EXECUTE";
                setStreamStatus("busy", toMode === "EXECUTE" ? "执行中" : "规划中");
            }
            break;
        case "STEP_REMINDER":
            if (syncMessages) {
                syncPendingAssistantMessage(session, progressTextForEvent(normalized));
            }
            break;
        case "PLAN_FILE":
            if (syncMessages) {
                syncPendingAssistantMessage(session, progressTextForEvent(normalized));
                setStreamStatus("busy", "执行中");
            }
            break;
        case "FINAL_ANSWER":
            session.finalAnswer = payload.answer || "";
            session.status = "COMPLETED";
            session.completedAt = normalized.timestamp || new Date().toISOString();
            if (syncMessages) {
                finalizeAssistantMessage(session, session.finalAnswer, "COMPLETED", payload.responseKind || "FINAL");
                setStreamStatus("live", "已完成");
            }
            break;
        case "WARNING":
            session.errorMessage = payload.message || "出现异常";
            session.status = "FAILED";
            if (syncMessages) {
                finalizeAssistantMessage(session, session.errorMessage, "FAILED", "ERROR");
                setStreamStatus("error", "执行失败");
            }
            break;
        default:
            break;
    }
}

function normalizeEvent(event) {
    return {
        eventType: event.eventType || "UNKNOWN",
        sessionId: event.sessionId || null,
        round: event.round ?? null,
        timestamp: event.timestamp || null,
        payload: event.payload || {},
    };
}

function sortSessionEvents(session) {
    session.events.sort(compareEvents);
}

function compareEvents(left, right) {
    const leftTime = Date.parse(left.timestamp || "") || 0;
    const rightTime = Date.parse(right.timestamp || "") || 0;
    if (leftTime === rightTime) {
        return (left.round ?? 0) - (right.round ?? 0);
    }
    return leftTime - rightTime;
}

async function loadSession(sessionId) {
    if (!sessionId) {
        return;
    }

    if (state.isStreaming && sessionId !== state.currentSession?.sessionId) {
        abortActiveStream();
    }

    setStreamStatus("busy", "加载会话");
    try {
        const response = await fetch(`/api/travel-agent/sessions/${encodeURIComponent(sessionId)}`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const document = await response.json();
        state.currentSession = hydrateSessionDocument(document);
        populateForm(state.currentSession.requestSnapshot);
        updateUrlSession(sessionId);
        renderCurrentSession();
        renderSidebar();
        scrollWorkspaceTop();
        closeSidebar();
        setStreamStatus("live", "已加载");
    } catch (error) {
        console.error(error);
        setStreamStatus("error", "加载失败");
    }
}

async function deleteSession(sessionId) {
    if (!sessionId || state.isStreaming) {
        return;
    }

    try {
        const response = await fetch(`/api/travel-agent/sessions/${encodeURIComponent(sessionId)}`, {
            method: "DELETE",
        });
        if (!response.ok && response.status !== 404) {
            throw new Error(`HTTP ${response.status}`);
        }
        state.serverSessions = state.serverSessions.filter((session) => session.sessionId !== sessionId);
        const deletingCurrent = state.currentSession?.sessionId === sessionId;
        if (deletingCurrent) {
            const next = state.serverSessions[0];
            if (next?.sessionId) {
                await loadSession(next.sessionId);
            } else {
                showLanding({ clearForm: true, focusInput: false });
            }
        } else {
            renderSidebar();
        }
        setStreamStatus("live", "已删除");
    } catch (error) {
        console.error("delete session failed", error);
        setStreamStatus("error", "删除失败");
    }
}

function hydrateSessionDocument(document) {
    const snapshot = pickLatestRequestSnapshot(document);
    const session = createSession(document.sessionId, snapshot);
    session.title = document.title || buildSessionTitle(snapshot.message);
    session.preview = document.preview || "";
    session.status = document.status || "COMPLETED";
    session.finalAnswer = document.finalAnswer || "";
    session.errorMessage = document.errorMessage || "";
    session.updatedAt = document.updatedAt || null;
    session.createdAt = document.createdAt || document.updatedAt || null;
    session.completedAt = document.completedAt || null;
    session.memorySummary = document.memorySummary || "";
    session.preferenceMemory = document.preferenceMemory || "";
    session.memoryHighlights = safeArray(document.memoryHighlights);
    session.rag = safeArray(document.rag);
    session.ragQuery = document.ragQuery || "";
    session.ragRewrittenQuery = document.ragRewrittenQuery || "";
    session.ragRecognizedDestinations = safeArray(document.ragRecognizedDestinations);
    session.ragInitialized = Boolean(document.ragInitialized);
    session.ragCleared = Boolean(document.ragCleared);
    session.messages = normalizeMessages(document.messages);

    const sourceEvents = safeArray(document.traceEvents).length > 0 ? document.traceEvents : safeArray(document.events);
    sourceEvents
        .map(normalizeEvent)
        .sort(compareEvents)
        .forEach((event) => applyEventState(session, event, { syncMessages: false }));

    sortSessionEvents(session);

    if (session.messages.length === 0 && hasText(snapshot.message)) {
        appendMessage(session, buildLocalMessage(session, "USER", snapshot.message, "USER_INPUT", "COMPLETED", snapshot, session.createdAt));
    }
    if (session.finalAnswer && !hasAssistantFinalMessage(session)) {
        appendMessage(session, buildLocalMessage(session, "ASSISTANT", session.finalAnswer, "FINAL", "COMPLETED", null, session.completedAt || session.updatedAt));
    }
    if (session.errorMessage && !hasAssistantFinalMessage(session)) {
        appendMessage(session, buildLocalMessage(session, "ASSISTANT", session.errorMessage, "ERROR", "FAILED", null, session.completedAt || session.updatedAt));
    }

    return session;
}

function renderCurrentSession() {
    const session = state.currentSession;
    const hasSession = Boolean(session);
    if (elements.heroPanel) {
        elements.heroPanel.style.display = "none";
    }

    if (!hasSession) {
        elements.conversationList.innerHTML = '<div class="empty-state">还没有会话内容，先在右侧输入旅行需求。</div>';
        elements.userBrief.textContent = "还没有开始新的规划，请先输入你的旅行目标。";
        elements.requestMetaChips.innerHTML = "";
        elements.reasoningList.innerHTML = '<div class="empty-state">思考链路会在这里拆成可读步骤，而不是原始事件 JSON。</div>';
        elements.planThoughtBlock.textContent = "等待规划器给出任务拆解。";
        elements.missionBlock.textContent = "任务书生成后，这里会展示本次对话的 mission。";
        elements.taskBoard.innerHTML = '<div class="empty-state">还没有任务书。</div>';
        elements.finalAnswerBody.innerHTML = '<div class="empty-state">最新一轮的结构化回答会显示在这里。</div>';
        elements.knowledgeList.innerHTML = '<div class="empty-state">当本轮会话触发 RAG 后，这里会展示命中的知识片段。</div>';
        elements.timelineList.innerHTML = '<div class="empty-state">还没有事件。</div>';
        elements.sessionIdValue.textContent = "未生成";
        elements.sessionUpdatedAt.textContent = "-";
        elements.sessionErrorValue.textContent = "无";
        elements.memorySummaryBody.textContent = "还没有摘要。";
        elements.preferenceMemoryBody.textContent = "还没有长期偏好。";
        elements.memoryHighlightsList.innerHTML = '<div class="empty-state">还没有记忆亮点。</div>';
        elements.ragSummaryList.innerHTML = '<div class="empty-state">本会话还没有注入 RAG 记忆。</div>';
        elements.deleteSessionButton.disabled = true;
        setSessionStatus("idle", "未开始");
        if (!state.isStreaming) {
            setStreamStatus("idle", "空闲");
        }
        return;
    }

    const activeEvents = activeTurnEvents(session);
    const latestAnswer = session.finalAnswer || latestAssistantContent(session);
    elements.conversationList.innerHTML = buildConversationList(session);
    elements.userBrief.textContent = session.requestSnapshot.message || "未填写旅行目标";
    elements.requestMetaChips.innerHTML = buildMetaChips(session.requestSnapshot);
    elements.reasoningList.innerHTML = buildReasoningList(activeEvents);
    elements.planThoughtBlock.textContent = session.planThought || "等待规划器给出任务拆解。";
    elements.missionBlock.textContent = session.mission || "任务书生成后，这里会展示本次对话的 mission。";
    elements.taskBoard.innerHTML = buildTaskBoard(session);
    elements.finalAnswerBody.innerHTML = latestAnswer
        ? `<article class="answer-markdown">${renderMarkdown(latestAnswer)}</article>`
        : '<div class="empty-state">最新一轮的结构化回答会显示在这里。</div>';
    elements.knowledgeList.innerHTML = buildKnowledgeList(session.knowledgeHits);
    elements.timelineList.innerHTML = buildTimeline(activeEvents);
    elements.sessionIdValue.textContent = session.sessionId || "未生成";
    elements.sessionUpdatedAt.textContent = formatTime(session.updatedAt);
    elements.sessionErrorValue.textContent = session.errorMessage || "无";
    elements.memorySummaryBody.textContent = session.memorySummary || "还没有摘要。";
    elements.preferenceMemoryBody.textContent = session.preferenceMemory || "还没有长期偏好。";
    elements.memoryHighlightsList.innerHTML = buildMemoryHighlights(session.memoryHighlights);
    elements.ragSummaryList.innerHTML = buildRagSummary(session);
    elements.deleteSessionButton.disabled = !session.sessionId || state.isStreaming;
    setSessionStatus(statusTone(session.status), statusLabel(session.status));
    if (!state.isStreaming) {
        setStreamStatus(statusTone(session.status), streamTextForSession(session));
    }
}

function activeTurnEvents(session) {
    const events = safeArray(session.events);
    if (events.length === 0) {
        return [];
    }
    const latestUser = latestUserMessage(session);
    const threshold = Date.parse(latestUser?.createdAt || latestUser?.updatedAt || "") || 0;
    if (!threshold) {
        return events;
    }
    const filtered = events.filter((event) => {
        const eventTime = Date.parse(event.timestamp || "") || 0;
        return eventTime === 0 || eventTime >= threshold - 1000;
    });
    return filtered.length > 0 ? filtered : events;
}

function buildConversationList(session) {
    const messages = normalizeMessages(session.messages);
    if (messages.length === 0) {
        return '<div class="empty-state">还没有对话内容。</div>';
    }

    return messages.map((message) => {
        const isAssistant = message.role === "ASSISTANT";
        const bubbleClass = [
            "conversation-item",
            isAssistant ? "assistant" : "user",
            isAssistant && message.status === "RUNNING" ? "pending" : "",
            isAssistant && message.status === "FAILED" ? "error" : "",
        ].filter(Boolean).join(" ");

        const typeLabel = MESSAGE_TYPE_LABELS[message.messageType] || humanizeCode(message.messageType || (isAssistant ? "ASSISTANT" : "USER"));
        const runtime = isAssistant && message.status === "RUNNING" ? buildRuntimeLabel(session, message) : "";
        return `
            <article class="${bubbleClass}">
                <div class="conversation-bubble">
                    <div class="message-meta">
                        <div class="message-meta-left">
                            <span class="message-role">${escapeHtml(isAssistant ? "Voyu" : "你")}</span>
                            <span class="message-type">${escapeHtml(typeLabel)}</span>
                        </div>
                        <div class="message-meta-right">
                            ${runtime ? `<span class="message-runtime">${escapeHtml(runtime)}</span>` : ""}
                            <span>${escapeHtml(formatTime(message.updatedAt || message.createdAt))}</span>
                        </div>
                    </div>
                    <div class="message-body">
                        ${renderMessageBody(message)}
                    </div>
                </div>
            </article>
        `;
    }).join("");
}

function renderMessageBody(message) {
    if (message.role === "ASSISTANT") {
        if (!hasText(message.content) && message.status === "RUNNING") {
            return '<p class="message-placeholder">思考中，等待更多状态...</p>';
        }
        if (message.status === "RUNNING") {
            return `<p class="message-placeholder">${escapeHtml(message.content || "思考中...")}</p>`;
        }
        if (message.messageType === "ERROR" || message.status === "FAILED") {
            return `<p class="message-placeholder">${escapeHtml(message.content || "本轮执行失败。")}</p>`;
        }
        return `<article class="answer-markdown">${renderMarkdown(message.content || "")}</article>`;
    }
    return `<p>${escapeHtml(message.content || "")}</p>`;
}

function buildRuntimeLabel(session, message) {
    const startedAt = Date.parse(latestUserMessage(session)?.createdAt || message.createdAt || "") || 0;
    if (!startedAt) return "工作中";
    const elapsed = Date.now() - startedAt;
    return `工作中 ${formatDuration(elapsed)}`;
}

function buildMetaChips(snapshot) {
    const chips = [
        ["目的地", snapshot.destination],
        ["出发地", snapshot.departure],
        ["天数", snapshot.travelDays],
        ["预算", snapshot.budget],
        ["偏好", snapshot.preferences],
    ].filter(([, value]) => hasText(value));

    if (chips.length === 0) {
        return '<span class="meta-chip">未提供结构化字段</span>';
    }

    return chips.map(([label, value]) => `<span class="meta-chip">${escapeHtml(label)}：${escapeHtml(value)}</span>`).join("");
}

function buildReasoningList(events) {
    const items = safeArray(events).filter((event) => REASONING_EVENT_TYPES.has(event.eventType));
    if (items.length === 0) {
        return '<div class="empty-state">思考链路会在这里拆成可读步骤，而不是原始事件 JSON。</div>';
    }

    return items.map((event, index) => {
        const badgeTone = event.eventType === "WARNING" ? "error" : event.eventType === "TOOL_CALL" ? "busy" : "live";
        return `
            <article class="reasoning-item">
                <div class="reasoning-head">
                    <span class="reasoning-index">Step ${index + 1}</span>
                    <span class="status-pill ${badgeTone}">${escapeHtml(EVENT_LABELS[event.eventType] || event.eventType)}</span>
                </div>
                <div class="reasoning-body">${renderReasoningBody(event)}</div>
                <div class="reasoning-foot">${escapeHtml(formatTime(event.timestamp))}</div>
            </article>
        `;
    }).join("");
}

function renderReasoningBody(event) {
    const payload = event.payload || {};
    switch (event.eventType) {
        case "THOUGHT":
            return `<p>${escapeHtml(payload.message || "进入主循环。")}</p>`;
        case "PLAN_DRAFT":
            return `<p>${escapeHtml(payload.thought || "规划器正在形成任务书。")}</p>`;
        case "TASK_BOOK":
            return `
                <p>${escapeHtml(payload.mission || "任务书已生成。")}</p>
                <div class="event-inline-chips">${buildTaskPreviewChips(payload.tasks || [])}</div>
            `;
        case "TOOL_CALL":
            return `
                <p><strong>${escapeHtml(TOOL_LABELS[payload.toolName] || payload.toolName || "工具")}</strong> 已加入执行队列。</p>
                <div class="event-inline-chips">${buildInputChips(payload.input || {})}</div>
            `;
        case "WARNING":
            return `<p>${escapeHtml(payload.message || "执行过程中出现异常。")}</p>`;
        default:
            return `<p>${escapeHtml(describeEvent(event))}</p>`;
    }
}

function buildTaskPreviewChips(tasks) {
    if (!safeArray(tasks).length) {
        return '<span class="event-chip">暂无任务</span>';
    }
    return tasks
        .slice(0, 6)
        .map((task) => `<span class="event-chip">${escapeHtml(task.name || task.taskId || "任务")}</span>`)
        .join("");
}

function buildTaskBoard(session) {
    const tasks = safeArray(session.taskBook?.tasks);
    if (tasks.length === 0) {
        return '<div class="empty-state">还没有任务书。</div>';
    }
    return tasks.map((task) => {
        const status = session.taskStatuses[task.taskId] || "PENDING";
        const dependsOn = safeArray(task.dependsOn).length ? task.dependsOn.join(", ") : "无";
        const batch = task.batchIndex ?? task.parallelGroup ?? task.groupIndex;
        const parallelFlag = task.parallelizable === true ? "可并行" : task.parallelizable === false ? "串行" : "";
        const source = task.phase || task.source || "";
        return `
            <article class="task-card">
                <div class="task-head">
                    <h4>${escapeHtml(task.name || task.taskId || "任务")}</h4>
                    <span class="status-pill ${statusTone(status)}">${escapeHtml(statusLabel(status))}</span>
                </div>
                <p>${escapeHtml(task.objective || task.description || "无任务目标说明")}</p>
                <div class="task-meta">
                    <span>${escapeHtml(TOOL_LABELS[task.toolName] || task.toolName || "未指定工具")}</span>
                    ${batch !== null && batch !== undefined ? `<span>批次：${escapeHtml(String(batch))}</span>` : ""}
                    ${parallelFlag ? `<span>${escapeHtml(parallelFlag)}</span>` : ""}
                    ${source ? `<span>来源：${escapeHtml(source)}</span>` : ""}
                    <span>依赖：${escapeHtml(dependsOn)}</span>
                </div>
            </article>
        `;
    }).join("");
}

function buildKnowledgeList(hits) {
    if (!safeArray(hits).length) {
        return '<div class="empty-state">当前会话还没有 RAG 命中结果。</div>';
    }
    return hits.map((hit) => `
        <article class="knowledge-card">
            <div class="knowledge-head">
                <h4>${escapeHtml(hit.title || hit.id || "未命名知识片段")}</h4>
                <span class="status-pill live">${escapeHtml(hit.source || "knowledge")}</span>
            </div>
            <div class="knowledge-meta">
                <span>${escapeHtml(hit.destination || "未标注目的地")}</span>
                <span>score: ${escapeHtml(formatScore(hit.score))}</span>
            </div>
            <p class="knowledge-snippet">${escapeHtml(clip(hit.content || "", 260))}</p>
        </article>
    `).join("");
}

function buildMemoryHighlights(highlights) {
    if (!safeArray(highlights).length) {
        return '<div class="empty-state">还没有记忆亮点。</div>';
    }
    return highlights.map((highlight) => `<span class="token">${escapeHtml(highlight)}</span>`).join("");
}

function buildRagSummary(session) {
    const rag = safeArray(session.rag);
    const queryBlocks = [];
    if (hasText(session.ragQuery)) {
        queryBlocks.push(`<div class="rag-query-block"><span class="memory-label">原始查询</span><div class="memory-body">${escapeHtml(session.ragQuery)}</div></div>`);
    }
    if (hasText(session.ragRewrittenQuery)) {
        queryBlocks.push(`<div class="rag-query-block"><span class="memory-label">重写查询</span><div class="memory-body">${escapeHtml(session.ragRewrittenQuery)}</div></div>`);
    }

    const destinationTokens = safeArray(session.ragRecognizedDestinations)
        .map((item) => `<span class="token">${escapeHtml(item)}</span>`)
        .join("");

    const cards = rag.slice(0, 5).map((hit) => `
        <article class="rag-card">
            <div class="rag-card-head">
                <h4>${escapeHtml(hit.title || hit.id || "未命名片段")}</h4>
                <span class="session-status-mini live">${escapeHtml(hit.source || "rag")}</span>
            </div>
            <p>${escapeHtml(clip(hit.content || "", 180))}</p>
            <div class="task-meta">
                ${hit.destination ? `<span>${escapeHtml(hit.destination)}</span>` : ""}
                <span>score: ${escapeHtml(formatScore(hit.score))}</span>
            </div>
        </article>
    `).join("");

    if (!queryBlocks.length && !destinationTokens && !cards) {
        return '<div class="empty-state">本会话还没有注入 RAG 记忆。</div>';
    }

    return `
        ${queryBlocks.join("")}
        ${destinationTokens ? `<div class="token-list">${destinationTokens}</div>` : ""}
        ${cards}
    `;
}

function buildTimeline(events) {
    if (!safeArray(events).length) {
        return '<div class="empty-state">还没有事件。</div>';
    }

    return [...events]
        .sort(compareEvents)
        .map((event) => `
            <article class="timeline-item">
                <div class="timeline-head">
                    <div>
                        <h4>${escapeHtml(EVENT_LABELS[event.eventType] || event.eventType || "未知事件")}</h4>
                        <p class="timeline-summary">${escapeHtml(describeEvent(event))}</p>
                    </div>
                    <div class="timeline-meta">
                        <span>Round ${escapeHtml(String(event.round ?? "-"))}</span>
                        <span>${escapeHtml(formatTime(event.timestamp))}</span>
                    </div>
                </div>
                <div class="timeline-body">${renderEventBody(event)}</div>
                ${buildRawEventDetails(event)}
            </article>
        `).join("");
}

function renderEventBody(event) {
    const payload = event.payload || {};
    switch (event.eventType) {
        case "THOUGHT":
            return `<p class="event-note">${escapeHtml(payload.message || "进入主循环。")}</p>`;
        case "PLAN_DRAFT":
            return `<p class="event-note">${escapeHtml(payload.thought || "规划器形成了新的思路。")}</p>`;
        case "TASK_BOOK":
            return `
                <p class="event-note">${escapeHtml(payload.mission || "任务书已生成。")}</p>
                <div class="event-inline-chips">${buildTaskPreviewChips(payload.tasks || [])}</div>
            `;
        case "TASK_STATUS":
            return `
                <div class="event-inline-chips">
                    <span class="event-chip">${escapeHtml(payload.taskName || payload.taskId || "任务")}</span>
                    <span class="event-chip">${escapeHtml(statusLabel(payload.status || "UNKNOWN"))}</span>
                </div>
            `;
        case "TOOL_CALL":
            return `
                <p class="event-note"><strong>${escapeHtml(TOOL_LABELS[payload.toolName] || payload.toolName || "工具")}</strong>：${escapeHtml(payload.taskName || payload.taskId || "任务")}。</p>
                <div class="event-inline-chips">${buildInputChips(payload.input || {})}</div>
            `;
        case "TOOL_RESULT":
            return renderToolResult(payload);
        case "WARNING":
            return `<p class="event-alert">${escapeHtml(payload.message || "执行过程中出现异常。")}</p>`;
        case "FINAL_ANSWER":
            return '<p class="event-note">最终方案已生成，详见上方“当前回答”区域。</p>';
        default:
            return `<p class="event-note">${escapeHtml(JSON.stringify(payload))}</p>`;
    }
}

function renderToolResult(payload) {
    const result = payload.result || {};
    const toolName = payload.toolName || "";

    switch (toolName) {
        case "profile.lookup":
            return `
                <p class="event-note">画像分析已归纳为本次旅程的基础约束。</p>
                <div class="event-inline-chips">
                    ${buildChip("目的地", result.destination)}
                    ${buildChip("出发地", result.departure)}
                    ${buildChip("天数", result.travelDays)}
                    ${buildChip("预算", result.budget)}
                    ${buildChip("偏好", result.preferences)}
                </div>
            `;
        case "weather.lookup":
            return `
                <p class="event-note">${escapeHtml(result.summary || "天气策略已返回。")}</p>
                <p class="event-secondary">${escapeHtml(result.planningHint || "")}</p>
            `;
        case "map.poi.search":
            return `
                <p class="event-note">${escapeHtml(result.destination || "目的地")} 的候选点位已聚合。</p>
                <div class="event-inline-chips">${buildPoiChips(result.pois || [])}</div>
                <p class="event-secondary">${escapeHtml(result.groupingHint || "")}</p>
            `;
        case "map.route.plan":
            return `
                <p class="event-note">${escapeHtml(result.summary || "路线规划已完成。")}</p>
                <div class="event-inline-chips">
                    ${buildChip("耗时", result.duration)}
                    ${buildChip("距离", result.distance)}
                </div>
            `;
        case "web.search":
            return `
                <p class="event-note">${escapeHtml(result.summary || "网页搜索已返回。")}</p>
                <div class="event-block">
                    <strong>Top 结果</strong>
                    ${buildKnowledgePreviewList(result.results || result.items || [])}
                </div>
            `;
        case "budget.audit":
            return `
                <p class="event-note">${escapeHtml(result.summary || "预算审查已完成。")}</p>
                <div class="event-inline-chips">${buildChip("预算", result.budget)}</div>
            `;
        case "rag.travel.knowledge":
            return `
                <p class="event-note">知识检索已完成，并进入 rerank。</p>
                <div class="event-block">
                    <strong>重写查询</strong>
                    <p>${escapeHtml(result.rewrittenQuery || "未返回")}</p>
                </div>
                <div class="event-block">
                    <strong>Top 命中</strong>
                    ${buildKnowledgePreviewList(result.hits || [])}
                </div>
            `;
        case "memory.rag.clear":
            return '<p class="event-note">本会话的 RAG 记忆已清空。</p>';
        default:
            return `<p class="event-note">${escapeHtml(JSON.stringify(result))}</p>`;
    }
}

function buildKnowledgePreviewList(hits) {
    if (!safeArray(hits).length) {
        return '<p class="event-secondary">没有命中的结果。</p>';
    }
    return `
        <ul class="event-list">
            ${hits.slice(0, 3).map((hit) => `
                <li>
                    <strong>${escapeHtml(hit.title || hit.id || hit.name || "未命名片段")}</strong>
                    <span>${escapeHtml(hit.source || "knowledge")} · ${escapeHtml(formatScore(hit.score))}</span>
                </li>
            `).join("")}
        </ul>
    `;
}

function buildPoiChips(pois) {
    if (!safeArray(pois).length) {
        return '<span class="event-chip">暂无点位</span>';
    }
    return pois
        .slice(0, 8)
        .map((poi) => {
            const value = typeof poi === "string" ? poi : poi.name || poi.title || JSON.stringify(poi);
            return `<span class="event-chip">${escapeHtml(value)}</span>`;
        })
        .join("");
}

function buildInputChips(input) {
    const chips = [
        ["目的地", input.destination],
        ["出发地", input.departure],
        ["天数", input.travelDays],
        ["预算", input.budget],
        ["分类", input.category],
        ["关键词", Array.isArray(input.keywords) ? input.keywords.join(" / ") : input.keywords],
        ["偏好", Array.isArray(input.preferences) ? input.preferences.join(" / ") : input.preferences],
    ].filter(([, value]) => hasText(value));

    if (!chips.length) {
        return '<span class="event-chip">无额外输入</span>';
    }
    return chips.map(([label, value]) => buildChip(label, value)).join("");
}

function buildChip(label, value) {
    if (!hasText(value)) {
        return "";
    }
    return `<span class="event-chip">${escapeHtml(label)}：${escapeHtml(String(value))}</span>`;
}

function buildRawEventDetails(event) {
    return `
        <details class="raw-details">
            <summary>查看原始事件数据</summary>
            <pre class="timeline-payload">${escapeHtml(JSON.stringify(event.payload || {}, null, 2))}</pre>
        </details>
    `;
}

async function refreshInfrastructure() {
    try {
        const response = await fetch("/api/infrastructure/status");
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        state.infrastructure = await response.json();
        renderInfrastructure();
    } catch (error) {
        console.error(error);
        elements.infraGrid.innerHTML = '<div class="empty-state">无法读取中间件状态。</div>';
    }
}

function renderInfrastructure() {
    const components = state.infrastructure?.components || {};
    const entries = Object.entries(components);
    if (entries.length === 0) {
        elements.infraGrid.innerHTML = '<div class="empty-state">没有可展示的中间件状态。</div>';
        return;
    }

    elements.infraGrid.innerHTML = entries.map(([name, detail]) => `
        <article class="infra-item ${detail.up ? "up" : "down"}">
            <div class="infra-head">
                <h4>${escapeHtml(name)}</h4>
                <span class="infra-status">${escapeHtml(detail.up ? "在线" : "离线")}</span>
            </div>
            <p>${escapeHtml(detail.message || "-")}</p>
        </article>
    `).join("");
}

function setStreamingState(active) {
    state.isStreaming = active;
    if (elements.submitButton) elements.submitButton.disabled = active;
    if (elements.abortButton) elements.abortButton.disabled = !active;

    // 计时器：streaming 时每秒重绘气泡（让工作中分秒走动）
    if (active) {
        state._tickTimer = state._tickTimer || setInterval(() => {
            if (state.currentSession) renderCurrentSession();
        }, 1000);
    } else {
        clearInterval(state._tickTimer);
        state._tickTimer = null;
    }
    elements.deleteSessionButton.disabled = !state.currentSession?.sessionId || active;
}

function setSessionStatus(tone, text) {
    elements.sessionStatusPill.className = `status-pill ${tone}`;
    elements.sessionStatusPill.textContent = text;
}

function setStreamStatus(tone, text) {
    state.streamTone = tone;
    state.streamText = text;
    elements.streamStatusBadge.className = `header-status ${tone}`;
    elements.streamStatusBadge.textContent = text;
}

function updateUrlSession(sessionId) {
    const url = new URL(window.location.href);
    if (sessionId) {
        url.searchParams.set("session", sessionId);
    } else {
        url.searchParams.delete("session");
    }
    window.history.replaceState({}, "", url);
}

function describeEvent(event) {
    const payload = event.payload || {};
    switch (event.eventType) {
        case "THOUGHT":
            return payload.message || "进入主循环";
        case "PLAN_DRAFT":
            return payload.thought || "规划器完成任务拆解";
        case "TASK_BOOK":
            return payload.mission || "任务书已生成";
        case "TASK_STATUS":
            return `${payload.taskName || payload.taskId || "任务"} -> ${statusLabel(payload.status || "UNKNOWN")}`;
        case "TOOL_CALL":
            return `${TOOL_LABELS[payload.toolName] || payload.toolName || "工具"} 已启动`;
        case "TOOL_RESULT":
            return `${TOOL_LABELS[payload.toolName] || payload.toolName || "工具"} 已返回结果`;
        case "WARNING":
            return payload.message || "执行过程中出现异常";
        case "FINAL_ANSWER":
            return "最终旅行方案已生成";
        default:
            return JSON.stringify(payload);
    }
}

function progressTextForEvent(event) {
    const payload = event.payload || {};
    switch (event.eventType) {
        case "THOUGHT":
            return payload.message || "思考中，正在理解本轮需求。";
        case "PLAN_DRAFT":
            return payload.thought || "规划中，正在形成工具执行计划。";
        case "TASK_BOOK":
            return payload.mission || "任务书已生成，开始执行工具。";
        case "TASK_STATUS":
            return `${payload.taskName || payload.taskId || "任务"}：${statusLabel(payload.status || "RUNNING")}`;
        case "TOOL_CALL":
            return `正在调用 ${TOOL_LABELS[payload.toolName] || payload.toolName || "工具"}。`;
        case "TOOL_RESULT":
            return `${TOOL_LABELS[payload.toolName] || payload.toolName || "工具"} 已返回，正在继续整理结果。`;
        default:
            return "思考中，等待更多执行结果。";
    }
}

function statusTone(status) {
    if (status === "COMPLETED" || status === "DONE") {
        return "live";
    }
    if (status === "RUNNING") {
        return "busy";
    }
    if (status === "FAILED" || status === "ABORTED") {
        return "error";
    }
    if (status === "WARNING") {
        return "warn";
    }
    return "idle";
}

function statusLabel(status) {
    const labels = {
        DRAFT: "草稿",
        PENDING: "待执行",
        RUNNING: "执行中",
        DONE: "完成",
        COMPLETED: "已完成",
        FAILED: "失败",
        ABORTED: "已中止",
        UNKNOWN: "未知",
    };
    return labels[status] || status || "未开始";
}

function streamTextForSession(session) {
    if (!session) {
        return "空闲";
    }
    if (session.status === "RUNNING") {
        return "进行中";
    }
    if (session.status === "FAILED") {
        return "执行失败";
    }
    if (session.status === "ABORTED") {
        return "已中止";
    }
    return "已完成";
}

function formatTime(value) {
    if (!value) {
        return "-";
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return String(value);
    }
    return new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
    }).format(date);
}

function formatShortTime(value) {
    if (!value) {
        return "-";
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return String(value);
    }
    return new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
    }).format(date);
}

function formatScore(value) {
    if (value === null || value === undefined || value === "") {
        return "-";
    }
    const parsed = Number(value);
    if (Number.isNaN(parsed)) {
        return String(value);
    }
    return parsed.toFixed(3);
}

function formatDuration(milliseconds) {
    const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}m ${String(seconds).padStart(2, "0")}s`;
}

function buildRequestSnapshot(payload = {}) {
    return {
        message: payload.message || "",
        destination: payload.destination || "",
        departure: payload.departure || "",
        travelDays: payload.travelDays || "",
        budget: payload.budget || "",
        preferences: payload.preferences || "",
        userId: payload.userId || SESSION_USER_ID,
    };
}

function pickLatestRequestSnapshot(document = {}) {
    const latest = document.lastRequestSnapshot;
    if (latest && Object.keys(latest).length > 0) {
        return latest;
    }
    return document.requestSnapshot || {};
}

function buildSessionTitle(raw) {
    return clip(String(raw || "新的旅行对话").replace(/\s+/g, " ").trim(), 36) || "新的旅行对话";
}

function normalizeMessages(messages) {
    return safeArray(messages)
        .map((message) => ({
            messageId: message.messageId || cryptoRandomId(),
            parentMessageId: message.parentMessageId || null,
            role: String(message.role || "").toUpperCase() || "ASSISTANT",
            content: message.content || "",
            messageType: message.messageType || "",
            status: message.status || "COMPLETED",
            sequence: message.sequence ?? null,
            createdAt: message.createdAt || message.updatedAt || null,
            updatedAt: message.updatedAt || message.createdAt || null,
            requestSnapshot: message.requestSnapshot || null,
        }))
        .sort(compareMessages);
}

function sortMessagesInPlace(session) {
    if (!session?.messages) {
        return;
    }
    session.messages.sort(compareMessages);
}

function compareMessages(left, right) {
    if (left.sequence !== null && right.sequence !== null && left.sequence !== right.sequence) {
        return left.sequence - right.sequence;
    }
    const leftTime = Date.parse(left.createdAt || left.updatedAt || "") || 0;
    const rightTime = Date.parse(right.createdAt || right.updatedAt || "") || 0;
    return leftTime - rightTime;
}

function latestUserMessage(session) {
    sortMessagesInPlace(session);
    const messages = safeArray(session?.messages);
    for (let index = messages.length - 1; index >= 0; index -= 1) {
        if (String(messages[index].role || "").toUpperCase() === "USER") {
            return messages[index];
        }
    }
    return null;
}

function latestAssistantMessage(session) {
    sortMessagesInPlace(session);
    const messages = safeArray(session?.messages);
    for (let index = messages.length - 1; index >= 0; index -= 1) {
        if (String(messages[index].role || "").toUpperCase() === "ASSISTANT") {
            return messages[index];
        }
    }
    return null;
}

function latestAssistantContent(session) {
    const assistant = latestAssistantMessage(session);
    return assistant?.content || "";
}

function hasAssistantFinalMessage(session) {
    return normalizeMessages(session.messages).some((message) => {
        return message.role === "ASSISTANT" && hasText(message.content) && message.status !== "RUNNING";
    });
}

function appendMessage(session, message) {
    if (!session.messages) {
        session.messages = [];
    }
    session.messages.push(message);
    sortMessagesInPlace(session);
}

function buildLocalMessage(session, role, content, messageType, status, requestSnapshot = null, timestamp = null) {
    const time = timestamp || new Date().toISOString();
    return {
        messageId: cryptoRandomId(),
        parentMessageId: null,
        role,
        content,
        messageType,
        status,
        sequence: nextMessageSequence(session),
        createdAt: time,
        updatedAt: time,
        requestSnapshot,
    };
}

function nextMessageSequence(session) {
    const messages = safeArray(session?.messages);
    const maxSequence = messages.reduce((max, message) => {
        const value = Number(message.sequence);
        return Number.isFinite(value) ? Math.max(max, value) : max;
    }, 0);
    return maxSequence + 1;
}

function syncPendingAssistantMessage(session, text) {
    const latest = latestAssistantMessage(session);
    const now = new Date().toISOString();
    if (latest && latest.status === "RUNNING") {
        latest.content = text;
        latest.messageType = "STREAM_STATUS";
        latest.updatedAt = now;
        latest.status = "RUNNING";
        session.preview = clip(text, 88);
        return;
    }

    appendMessage(session, {
        messageId: cryptoRandomId(),
        parentMessageId: latestUserMessage(session)?.messageId || null,
        role: "ASSISTANT",
        content: text,
        messageType: "STREAM_STATUS",
        status: "RUNNING",
        sequence: nextMessageSequence(session),
        createdAt: now,
        updatedAt: now,
        requestSnapshot: null,
    });
    session.preview = clip(text, 88);
}

function finalizeAssistantMessage(session, content, status, messageType) {
    const latest = latestAssistantMessage(session);
    const now = new Date().toISOString();
    if (latest && latest.status === "RUNNING") {
        latest.content = content || latest.content;
        latest.status = status;
        latest.messageType = messageType;
        latest.updatedAt = now;
        session.preview = clip(stripMarkdown(latest.content || ""), 88);
        return;
    }

    appendMessage(session, {
        messageId: cryptoRandomId(),
        parentMessageId: latestUserMessage(session)?.messageId || null,
        role: "ASSISTANT",
        content: content || "",
        messageType,
        status,
        sequence: nextMessageSequence(session),
        createdAt: now,
        updatedAt: now,
        requestSnapshot: null,
    });
    session.preview = clip(stripMarkdown(content || ""), 88);
}

function safeArray(value) {
    return Array.isArray(value) ? value : [];
}

function hasText(value) {
    return value !== null && value !== undefined && String(value).trim() !== "";
}

function clip(value, length) {
    if (!value || value.length <= length) {
        return value;
    }
    return `${value.slice(0, length).trim()}...`;
}

function humanizeCode(value) {
    if (!hasText(value)) {
        return "消息";
    }
    return String(value)
        .replaceAll("_", " ")
        .toLowerCase()
        .replace(/\b\w/g, (match) => match.toUpperCase());
}

function stripMarkdown(value) {
    return String(value || "")
        .replace(/[`*#>\[\]()|-]/g, " ")
        .replace(/\s+/g, " ")
        .trim();
}

function renderMarkdown(markdown) {
    const normalized = String(markdown || "").replace(/\r\n?/g, "\n").trim();
    if (!normalized) {
        return "";
    }
    const blocks = normalized.split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
    return blocks.map(renderMarkdownBlock).join("");
}

function renderMarkdownBlock(block) {
    const lines = block.split("\n").map((line) => line.trimEnd());
    if (lines.length === 1 && /^[-*_]{3,}$/.test(lines[0].trim())) {
        return "<hr>";
    }
    if (isTableBlock(lines)) {
        return renderMarkdownTable(lines);
    }
    if (lines.length === 1 && /^###\s+/.test(lines[0])) {
        return `<h3>${renderInlineMarkdown(lines[0].replace(/^###\s+/, ""))}</h3>`;
    }
    if (lines.length === 1 && /^##\s+/.test(lines[0])) {
        return `<h2>${renderInlineMarkdown(lines[0].replace(/^##\s+/, ""))}</h2>`;
    }
    if (lines.length === 1 && /^#\s+/.test(lines[0])) {
        return `<h1>${renderInlineMarkdown(lines[0].replace(/^#\s+/, ""))}</h1>`;
    }
    if (lines.every((line) => /^[-*]\s+/.test(line))) {
        return `<ul>${lines.map((line) => `<li>${renderInlineMarkdown(line.replace(/^[-*]\s+/, ""))}</li>`).join("")}</ul>`;
    }
    if (lines.every((line) => /^\d+\.\s+/.test(line))) {
        return `<ol>${lines.map((line) => `<li>${renderInlineMarkdown(line.replace(/^\d+\.\s+/, ""))}</li>`).join("")}</ol>`;
    }
    return `<p>${lines.map((line) => renderInlineMarkdown(line)).join("<br>")}</p>`;
}

function isTableBlock(lines) {
    return lines.length >= 2
        && lines.every((line) => line.trim().startsWith("|"))
        && /^(\|\s*:?-{3,}:?\s*)+\|?$/.test(lines[1].trim());
}

function renderMarkdownTable(lines) {
    const headers = parseTableRow(lines[0]);
    const rows = lines.slice(2).map(parseTableRow);
    return `
        <div class="markdown-table-wrap">
            <table>
                <thead>
                    <tr>${headers.map((cell) => `<th>${cell}</th>`).join("")}</tr>
                </thead>
                <tbody>
                    ${rows.map((row) => `<tr>${row.map((cell) => `<td>${cell}</td>`).join("")}</tr>`).join("")}
                </tbody>
            </table>
        </div>
    `;
}

function parseTableRow(line) {
    return line
        .trim()
        .replace(/^\|/, "")
        .replace(/\|$/, "")
        .split("|")
        .map((cell) => renderInlineMarkdown(cell.trim()));
}

function renderInlineMarkdown(text) {
    let html = escapeHtml(text);
    html = html.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
    html = html.replace(/`(.+?)`/g, "<code>$1</code>");
    html = html.replace(/\[(.+?)\]\((https?:\/\/[^\s)]+)\)/g, (_, label, url) => {
        return `<a href="${escapeHtml(url)}" target="_blank" rel="noreferrer">${escapeHtml(label)}</a>`;
    });
    return html;
}

function cryptoRandomId() {
    if (window.crypto?.randomUUID) {
        return window.crypto.randomUUID();
    }
    return `voyu-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

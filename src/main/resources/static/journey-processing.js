const PROCESS_RELEVANT_EVENTS = new Set(["THOUGHT", "PLAN_DRAFT", "TOOL_CALL", "WARNING"]);

const EVENT_LABELS = {
    THOUGHT: "主循环思考",
    PLAN_DRAFT: "规划草案",
    TASK_BOOK: "任务书",
    TASK_STATUS: "任务状态",
    TOOL_CALL: "工具调用",
    TOOL_RESULT: "工具结果",
    WARNING: "异常告警",
    FINAL_ANSWER: "最终方案",
};

const TOOL_LABELS = {
    "profile.lookup": "用户画像",
    "weather.lookup": "天气检查",
    "map.poi.search": "景点 POI 检索",
    "rag.travel.knowledge": "旅游知识检索",
    "budget.audit": "预算审查",
};

const state = {
    request: readRequest(),
    sessionId: null,
    status: "RUNNING",
    planThought: "Voyu 正在进入 Plan-Execute 主循环。",
    mission: "",
    tasks: [],
    taskStatuses: {},
    knowledgeHits: [],
    finalAnswer: "",
    errorMessage: "",
    events: [],
};

const elements = {
    streamStatus: document.getElementById("streamStatus"),
    resultLink: document.getElementById("resultLink"),
    planThought: document.getElementById("planThought"),
    missionText: document.getElementById("missionText"),
    missionBadge: document.getElementById("missionBadge"),
    taskList: document.getElementById("taskList"),
    timelineList: document.getElementById("timelineList"),
    knowledgeList: document.getElementById("knowledgeList"),
    finalPreview: document.getElementById("finalPreview"),
    sessionIdValue: document.getElementById("sessionIdValue"),
    completedAtValue: document.getElementById("completedAtValue"),
};

bootstrap().catch((error) => {
    console.error(error);
    setStatus("error", "初始化失败");
    elements.finalPreview.textContent = `无法启动流式规划：${error.message}`;
});

async function bootstrap() {
    render();
    await startStream();
}

function readRequest() {
    const node = document.getElementById("voyu-request-data");
    if (!node?.textContent) {
        throw new Error("Missing request payload");
    }
    return JSON.parse(node.textContent);
}

async function startStream() {
    setStatus("busy", "规划中");

    const response = await fetch("/api/travel-agent/stream", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            Accept: "text/event-stream",
        },
        body: JSON.stringify(state.request),
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
            const event = parseSseSegment(segment);
            if (event) {
                ingestEvent(event);
            }
        }
    }

    if (state.errorMessage) {
        setStatus("error", "执行异常");
    } else {
        setStatus("live", "已完成");
    }
    renderResultLink();
}

function parseSseSegment(segment) {
    const dataLines = segment
        .split(/\r?\n/)
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart());

    if (dataLines.length === 0) {
        return null;
    }

    try {
        return JSON.parse(dataLines.join("\n"));
    } catch (error) {
        console.warn("Failed to parse SSE event", error);
        return null;
    }
}

function ingestEvent(event) {
    const normalized = {
        eventType: event.eventType || "UNKNOWN",
        sessionId: event.sessionId || null,
        round: event.round ?? 0,
        timestamp: event.timestamp || null,
        payload: event.payload || {},
    };

    state.events.push(normalized);
    state.events.sort(compareEvents);

    if (normalized.sessionId) {
        state.sessionId = normalized.sessionId;
        state.request.sessionId = normalized.sessionId;
    }

    switch (normalized.eventType) {
        case "THOUGHT":
            state.planThought = normalized.payload.message || state.planThought;
            break;
        case "PLAN_DRAFT":
            state.planThought = normalized.payload.thought || state.planThought;
            break;
        case "TASK_BOOK":
            state.mission = normalized.payload.mission || "";
            state.tasks = normalized.payload.tasks || [];
            state.taskStatuses = {};
            state.tasks.forEach((task) => {
                if (task.taskId) {
                    state.taskStatuses[task.taskId] = "PENDING";
                }
            });
            break;
        case "TASK_STATUS":
            if (normalized.payload.taskId) {
                state.taskStatuses[normalized.payload.taskId] = normalized.payload.status || "UNKNOWN";
            }
            break;
        case "TOOL_RESULT":
            if (normalized.payload.toolName === "rag.travel.knowledge") {
                state.knowledgeHits = normalized.payload.result?.hits || [];
            }
            break;
        case "FINAL_ANSWER":
            state.finalAnswer = normalized.payload.answer || "";
            state.status = "COMPLETED";
            if (normalized.timestamp) {
                elements.completedAtValue.textContent = formatTime(normalized.timestamp);
            }
            break;
        case "WARNING":
            state.errorMessage = normalized.payload.message || "执行过程中出现异常。";
            state.status = "FAILED";
            break;
        default:
            break;
    }

    render();
}

function render() {
    elements.planThought.textContent = state.planThought || "Voyu 正在生成规划思路。";
    elements.missionText.textContent = state.mission || "任务书生成后，这里会显示本次旅程的 mission。";
    elements.missionBadge.textContent = state.mission ? "任务书已生成" : "等待任务书";
    elements.missionBadge.className = `status-badge ${state.mission ? "live" : "muted"}`;
    elements.taskList.innerHTML = buildTaskList();
    elements.timelineList.innerHTML = buildTimeline();
    elements.knowledgeList.innerHTML = buildKnowledgeList();
    elements.finalPreview.textContent = state.errorMessage || state.finalAnswer || "最终方案生成后，这里会先给出预览，你也可以跳到整理后的结果页查看。";
    elements.sessionIdValue.textContent = state.sessionId || "待生成";
    renderResultLink();
}

function buildTaskList() {
    if (!state.tasks.length) {
        return '<div class="empty-block">还没有任务书。</div>';
    }

    return state.tasks.map((task) => {
        const status = state.taskStatuses[task.taskId] || "PENDING";
        const dependsOn = Array.isArray(task.dependsOn) && task.dependsOn.length
            ? task.dependsOn.join(", ")
            : "无";

        return `
            <article class="task-card">
                <div class="card-topline">
                    <strong>${escapeHtml(task.name || task.taskId || "未命名任务")}</strong>
                    <span class="status-badge ${statusTone(status)}">${escapeHtml(statusLabel(status))}</span>
                </div>
                <p>${escapeHtml(task.objective || "未补充任务目标")}</p>
                <div class="mini-meta">
                    <span>${escapeHtml(TOOL_LABELS[task.toolName] || task.toolName || "未指定工具")}</span>
                    <span>分组：${escapeHtml(task.parallelGroup || "g0")}</span>
                    <span>依赖：${escapeHtml(dependsOn)}</span>
                </div>
            </article>
        `;
    }).join("");
}

function buildTimeline() {
    if (!state.events.length) {
        return '<div class="empty-block">SSE 事件到来后会按顺序显示。</div>';
    }

    return state.events.map((event) => `
        <article class="timeline-item">
            <div class="timeline-head">
                <div>
                    <strong>${escapeHtml(EVENT_LABELS[event.eventType] || event.eventType)}</strong>
                    <p>${escapeHtml(describeEvent(event))}</p>
                </div>
                <span class="status-badge ${eventTone(event.eventType)}">${escapeHtml(formatTime(event.timestamp))}</span>
            </div>
            <details class="raw-details">
                <summary>查看原始事件数据</summary>
                <pre>${escapeHtml(JSON.stringify(event.payload || {}, null, 2))}</pre>
            </details>
        </article>
    `).join("");
}

function buildKnowledgeList() {
    if (!state.knowledgeHits.length) {
        return '<div class="empty-block">当 `rag.travel.knowledge` 返回结果时，这里会展示命中的知识片段。</div>';
    }

    return state.knowledgeHits.slice(0, 10).map((hit) => `
        <article class="knowledge-card">
            <div class="card-topline">
                <strong>${escapeHtml(hit.title || hit.id || "未命名知识片段")}</strong>
                <span>${escapeHtml(hit.source || "knowledge")}</span>
            </div>
            <div class="mini-meta">
                <span>${escapeHtml(hit.destination || "未标注目的地")}</span>
                <span>score: ${escapeHtml(formatScore(hit.score))}</span>
            </div>
            <p>${escapeHtml(clip(hit.content || "", 180))}</p>
        </article>
    `).join("");
}

function renderResultLink() {
    if (!state.sessionId) {
        elements.resultLink.classList.add("is-hidden");
        return;
    }

    elements.resultLink.href = `/travel/journey/${encodeURIComponent(state.sessionId)}`;
    elements.resultLink.textContent = state.finalAnswer ? "查看结果页" : "打开会话页";
    elements.resultLink.classList.remove("is-hidden");
}

function describeEvent(event) {
    const payload = event.payload || {};

    switch (event.eventType) {
        case "THOUGHT":
            return payload.message || "进入主循环。";
        case "PLAN_DRAFT":
            return payload.thought || "规划器形成了新的思路。";
        case "TASK_BOOK":
            return payload.mission || "任务书已生成。";
        case "TASK_STATUS":
            return `${payload.taskName || payload.taskId || "任务"} -> ${statusLabel(payload.status || "UNKNOWN")}`;
        case "TOOL_CALL":
            return `${TOOL_LABELS[payload.toolName] || payload.toolName || "工具"} 已加入执行队列`;
        case "TOOL_RESULT":
            return `${TOOL_LABELS[payload.toolName] || payload.toolName || "工具"} 已返回结果`;
        case "WARNING":
            return payload.message || "执行过程中出现异常。";
        case "FINAL_ANSWER":
            return "最终旅行方案已生成";
        default:
            return "事件已记录";
    }
}

function compareEvents(left, right) {
    const leftTime = Date.parse(left.timestamp || "") || 0;
    const rightTime = Date.parse(right.timestamp || "") || 0;
    if (leftTime === rightTime) {
        return (left.round || 0) - (right.round || 0);
    }
    return leftTime - rightTime;
}

function setStatus(tone, text) {
    elements.streamStatus.className = `status-badge ${tone}`;
    elements.streamStatus.textContent = text;
}

function statusTone(status) {
    switch (String(status || "").toUpperCase()) {
        case "DONE":
        case "COMPLETED":
            return "live";
        case "RUNNING":
            return "busy";
        case "FAILED":
            return "error";
        default:
            return "muted";
    }
}

function statusLabel(status) {
    switch (String(status || "").toUpperCase()) {
        case "RUNNING":
            return "执行中";
        case "DONE":
            return "完成";
        case "COMPLETED":
            return "已完成";
        case "FAILED":
            return "失败";
        case "PENDING":
            return "待执行";
        default:
            return "未知";
    }
}

function eventTone(eventType) {
    switch (eventType) {
        case "WARNING":
            return "error";
        case "FINAL_ANSWER":
            return "live";
        case "TOOL_CALL":
        case "TASK_STATUS":
            return "busy";
        default:
            return "muted";
    }
}

function formatTime(value) {
    if (!value) {
        return "-";
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return value;
    }
    return date.toLocaleString("zh-CN", {
        hour12: false,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
    });
}

function formatScore(value) {
    return typeof value === "number" ? value.toFixed(4) : String(value ?? "-");
}

function clip(value, maxLength) {
    if (!value || value.length <= maxLength) {
        return value;
    }
    return `${value.slice(0, maxLength).trim()}...`;
}

function escapeHtml(value) {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/\"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

package com.voyu.agent.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyu.agent.model.agent.AgentEventType;
import com.voyu.agent.model.agent.ConversationMemorySnapshot;
import com.voyu.agent.model.agent.ConversationState;
import com.voyu.agent.model.agent.TaskBook;
import com.voyu.agent.model.agent.TaskExecutionResult;
import com.voyu.agent.model.agent.TaskItem;
import com.voyu.agent.model.api.TravelChatRequest;
import com.voyu.agent.service.llm.LlmFacade;
import com.voyu.agent.tool.ToolInputNormalizer;
import com.voyu.agent.tool.ToolRegistry;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import com.voyu.agent.util.AgentEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @deprecated 该类已被 {@link UnifiedReActAgent} 替代。
 * Plan 逻辑已合并到统一 ReAct 循环中，不再作为独立的 Plan Agent 使用。
 */
@Deprecated(since = "2.0", forRemoval = true)
@Component
public class PlanAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanAgent.class);
    private static final String TOOL_CATALOG = "tool.catalog";
    private static final String PLANNER_FINISH = "planner.finish";
    private static final List<String> FALLBACK_FACT_TOOLS = List.of(
            "weather.lookup",
            "map.poi.search",
            "budget.audit"
    );

    private final LlmFacade llmFacade;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final ToolInputNormalizer toolInputNormalizer;
    private final int maxReactSteps;

    public PlanAgent(LlmFacade llmFacade,
                     ObjectMapper objectMapper,
                     ToolRegistry toolRegistry,
                     ToolInputNormalizer toolInputNormalizer,
                     @Value("${voyu.agent.max-react-steps:5}") int maxReactSteps) {
        this.llmFacade = llmFacade;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.toolInputNormalizer = toolInputNormalizer;
        this.maxReactSteps = maxReactSteps;
    }

    public TaskBook plan(ConversationState state, AgentEventPublisher publisher) {
        List<String> scratchpad = new ArrayList<>();

        for (int step = 1; step <= maxReactSteps; step++) {
            PlanStepDecision decision = thinkNextStep(state, scratchpad, step);
            publishSafely(publisher, state, AgentEventType.THOUGHT, Map.of(
                    "phase", "PLAN",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "message", decision.thought()
            ));

            if ("CALL_TOOL".equals(decision.actionType())) {
                Map<String, Object> toolResult = invokePlanningTool(state, publisher, step, decision.toolName(), decision.input());
                scratchpad.add("""
                        Step %s
                        Thought: %s
                        Action: %s
                        Observation: %s
                        """.formatted(
                        step,
                        decision.thought(),
                        decision.toolName(),
                        clipJson(toolResult)));
                continue;
            }

            PlanValidationReport decisionValidation = validateFinishDecision(decision, state);
            if (!decisionValidation.valid()) {
                String validationMessage = "当前工具执行计划未通过校验：" + decisionValidation.summary();
                publishSafely(publisher, state, AgentEventType.THOUGHT, Map.of(
                        "phase", "PLAN",
                        "round", state.getCurrentRound(),
                        "step", step,
                        "message", validationMessage,
                        "validationPassed", false,
                        "validationReasons", decisionValidation.reasons()
                ));
                scratchpad.add("""
                        Step %s
                        Thought: %s
                        Action: planner.finish
                        Validation: FAILED
                        Reason: %s
                        """.formatted(
                        step,
                        decision.thought(),
                        decisionValidation.summary()));
                continue;
            }

            TaskBook taskBook = buildTaskBookFromDecision(decision, state);
            PlanValidationReport taskBookValidation = validateTaskBook(taskBook, state);
            if (!taskBookValidation.valid()) {
                String validationMessage = "当前工具执行计划构建后仍未通过校验：" + taskBookValidation.summary();
                publishSafely(publisher, state, AgentEventType.THOUGHT, Map.of(
                        "phase", "PLAN",
                        "round", state.getCurrentRound(),
                        "step", step,
                        "message", validationMessage,
                        "validationPassed", false,
                        "validationReasons", taskBookValidation.reasons()
                ));
                scratchpad.add("""
                        Step %s
                        Thought: %s
                        Action: planner.finish
                        Validation: FAILED_AFTER_BUILD
                        Reason: %s
                        """.formatted(
                        step,
                        decision.thought(),
                        taskBookValidation.summary()));
                continue;
            }

            publishSafely(publisher, state, AgentEventType.TOOL_CALL, Map.of(
                    "phase", "PLAN",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "toolName", PLANNER_FINISH,
                    "taskOrigin", "PLANNER",
                    "arguments", Map.of(
                            "mission", taskBook.getMission(),
                            "taskCount", taskBook.getTasks().size(),
                            "taskScript", taskBook.getTaskScript()
                    ),
                    "input", Map.of(
                            "mission", taskBook.getMission(),
                            "taskCount", taskBook.getTasks().size(),
                            "taskScript", taskBook.getTaskScript()
                    )
            ));
            publishSafely(publisher, state, AgentEventType.TOOL_RESULT, Map.of(
                    "phase", "PLAN",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "toolName", PLANNER_FINISH,
                    "taskOrigin", "PLANNER",
                    "result", Map.of(
                            "validationPassed", true,
                            "validationSummary", taskBookValidation.summary(),
                            "mission", taskBook.getMission(),
                            "taskScript", taskBook.getTaskScript()
                    )
            ));
            return taskBook;
        }

        publishSafely(publisher, state, AgentEventType.WARNING, Map.of(
                "phase", "PLAN",
                "round", state.getCurrentRound(),
                "message", "Plan ReAct 已达到最大步数，转入兜底任务书。"
        ));
        return fallbackPlan(state);
    }

    private PlanStepDecision thinkNextStep(ConversationState state, List<String> scratchpad, int step) {
        String llmPlan = llmFacade.complete(buildSystemPrompt(), buildUserPrompt(state, scratchpad, step));
        if (llmPlan != null && !llmPlan.isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(sanitizeJsonPayload(llmPlan), new TypeReference<Map<String, Object>>() {
                });
                return toPlanDecision(parsed, state);
            } catch (Exception ex) {
                log.warn("Failed to parse planner react step {}, falling back to deterministic planner", step, ex);
            }
        }

        return fallbackDecision(state, step);
    }

    private String buildSystemPrompt() {
        return """
                你是旅游规划助手的 Plan Agent，当前工作在 Plan-Execute 架构中的 PLAN 环节。
                你输出的不是最终旅游方案，而是交给 Execute Agent 使用的“工具执行计划”。
                你必须采用 ReAct 风格输出单步 JSON，每次只做一件事：要么调用一个工具，要么结束规划循环。

                允许动作：
                1. CALL_TOOL：调用一个工具，为任务书补充事实
                2. FINISH：调用 planner.finish，输出最终任务书并结束本轮 PLAN

                内置伪工具：
                - tool.catalog：查看当前本地工具与已发现的 MCP 工具目录
                - planner.finish：当任务书已足够清晰时结束 PLAN 循环

                输出必须是 JSON，不要输出 markdown，不要输出解释文字。
                JSON 结构：
                {
                  "thought": "string",
                  "action": {
                    "type": "CALL_TOOL | FINISH",
                    "toolName": "%s",
                    "input": {}
                  },
                  "mission": "string, only when FINISH，必须明确说明这是供 Execute 使用的工具执行计划，而不是最终旅游方案",
                  "plannerThought": "string, only when FINISH，必须使用固定句式：可完成性：...；格式校验：...；执行意图：...",
                  "taskScript": "string, optional legacy summary；若提供，只能逐行写 taskId，不要使用 <sep>",
                  "tasks": [
                    {
                      "taskId": "t1",
                      "name": "string",
                      "objective": "string",
                      "toolName": "%s",
                      "batchIndex": 1,
                      "input": {}
                    }
                  ]
                }

                规划要求：
                - 所有业务工具都是可选工具，由你根据用户任务、记忆上下文和当前 loop focus 自主决定是否调用。
                - 第一轮可以优先考虑用户画像、天气、POI、路线、预算、实时搜索等事实工具，但不要为“覆盖工具”而机械调用。
                - RAG 增强知识已在记忆上下文的 rag[] 中预注入；默认不要把 rag.travel.knowledge 写入任务书。
                - 只有当 rag[] 缺失、明显不相关或用户明确要求重新查知识库时，才可选择 rag.travel.knowledge 追加检索。
                - 如果你判断 rag[] 已经过时、无关或会误导本轮任务，可以调用 memory.rag.clear 清除该会话记忆。
                - 后续轮次优先围绕给定 loop focus 补齐缺失事实，遵守用户目标。
                - 任务书必须至少给出结构化 tasks，不能只给自然语言段落；taskScript 只是可选摘要。
                - batchIndex 表示串行阶段；同一 batchIndex 内的任务会由 Execute Agent 根据工具模板 parallelizable 自动调度。
                - Execute Agent 会尽量并行执行 parallelizable=true 的工具，但同一时间最多并行 5 个；你不需要使用 <sep>，也不要用 executionMode 强行指定并行。
                - taskScript 若提供，只能逐行写 taskId，用于展示摘要；不要写自然语言说明、不要写序号、不要写“先/后/然后”。
                - 若你选择 profile.lookup，通常让它单独作为较早批次；其他互不依赖的事实工具可以安排到后续同一 batchIndex，由 Execute Agent 自动并行。
                - web.search 是可选实时搜索工具，仅在用户明确需要近期开放状态、临时活动、最新攻略或政策信息时调用；若搜索工具返回额度不足，不要反复重试。
                - map.route.plan 可用于估算出发地到目的地的路线耗时，但它不是每次都必须调用；若 departure 不明确，不要强行调用。
                - 只有当你已经同时判断“该工具执行计划可以支撑最终旅游方案生成”且“格式完全满足要求”时，才能选择 FINISH。
                - 如果你发现任务书格式不合规，必须继续思考并修正，不得直接 FINISH。
                - 当你已有足够信息时，必须选择 FINISH，不要无限调用工具。
                - 不可把 invocable=false 的 MCP 目录项写进任务书执行列表，它们只作为发现结果参考。
                """.formatted(
                formatToolNameOptions(true),
                formatToolNameOptions(false));
    }

    private String buildUserPrompt(ConversationState state, List<String> scratchpad, int step) {
        TravelChatRequest request = state.getRequest();
        return """
                当前 round：%s
                当前 step：%s / %s
                当前 loop focus：%s

                用户原始需求：%s
                目的地：%s
                出发地：%s
                旅行天数：%s
                预算：%s
                偏好：%s

                记忆上下文：
                %s

                当前已发现工具目录：
                %s

                历史 round 任务书摘要：
                %s

                历史执行结果摘要：
                %s

                当前已成功覆盖工具：
                %s

                FINISH 前自检清单：
                1. mission 是否明确说明这是“工具执行计划 / 任务书”，而不是最终旅游方案。
                2. plannerThought 是否严格包含“可完成性：”“格式校验：”“执行意图：”三个片段。
                3. tasks 是否给出了 taskId、toolName、batchIndex、input；batchIndex 是否体现了串行阶段。
                4. 是否避免使用 <sep> 或 executionMode 强行指定并行；并行由 Execute Agent 按工具模板自动决定。
                5. 每个工具是否都是本轮必要的可选工具；是否避免把已预注入的 rag[] 再机械检索一遍。

                Scratchpad：
                %s
                """.formatted(
                state.getCurrentRound(),
                step,
                maxReactSteps,
                state.getLoopFocus().isBlank() ? "无" : state.getLoopFocus(),
                request.getMessage(),
                valueOrDefault(request.getDestination()),
                valueOrDefault(request.getDeparture()),
                valueOrDefault(request.getTravelDays()),
                valueOrDefault(request.getBudget()),
                valueOrDefault(request.getPreferences()),
                memoryContext(state),
                formatToolCatalog(),
                formatTaskBookHistory(state.getTaskBooks()),
                formatExecutionHistory(state.getExecutionResults()),
                formatSuccessfulTools(state),
                scratchpad.isEmpty() ? "暂无" : String.join("\n", scratchpad));
    }

    private PlanStepDecision toPlanDecision(Map<String, Object> parsed, ConversationState state) {
        Map<String, Object> action = objectMapper.convertValue(parsed.getOrDefault("action", Map.of()),
                new TypeReference<Map<String, Object>>() {
                });
        String actionType = String.valueOf(action.getOrDefault("type", "FINISH")).trim().toUpperCase();
        String toolName = sanitizeToolName(String.valueOf(action.getOrDefault("toolName", PLANNER_FINISH)));
        Map<String, Object> input = objectMapper.convertValue(action.getOrDefault("input", Map.of()),
                new TypeReference<Map<String, Object>>() {
                });
        String thought = String.valueOf(parsed.getOrDefault("thought", "先补齐关键事实，再生成工具执行计划。")).trim();
        String mission = String.valueOf(parsed.getOrDefault("mission", "生成供 Execute 使用的工具执行计划")).trim();
        String plannerThought = String.valueOf(parsed.getOrDefault("plannerThought",
                "可完成性：当前信息仍待确认；格式校验：尚未完成；执行意图：先补齐关键事实，再输出工具执行计划。")).trim();
        String taskScript = String.valueOf(parsed.getOrDefault("taskScript", "")).trim();
        List<Map<String, Object>> tasks = objectMapper.convertValue(parsed.getOrDefault("tasks", List.of()),
                new TypeReference<List<Map<String, Object>>>() {
                });

        if (!"CALL_TOOL".equals(actionType)) {
            actionType = "FINISH";
            toolName = PLANNER_FINISH;
        }

        if ("CALL_TOOL".equals(actionType) && PLANNER_FINISH.equals(toolName)) {
            actionType = "FINISH";
        }

        if ("FINISH".equals(actionType) && (tasks == null || tasks.isEmpty())) {
            TaskBook fallback = fallbackPlan(state);
            tasks = fallback.getTasks().stream()
                    .map(this::mapTask)
                    .toList();
            mission = fallback.getMission();
            plannerThought = fallback.getPlannerThought();
            taskScript = fallback.getTaskScript();
        }

        return new PlanStepDecision(thought, actionType, toolName, input, mission, plannerThought, taskScript, tasks);
    }

    private PlanStepDecision fallbackDecision(ConversationState state, int step) {
        if (state.getCurrentRound() <= 1) {
            if (step == 1) {
                return new PlanStepDecision(
                        "先读取工具目录，确认本轮能用哪些本地工具以及已发现的 MCP 能力。",
                        "CALL_TOOL",
                        TOOL_CATALOG,
                        Map.of(),
                        "",
                        "",
                        "",
                        List.of());
            }
            if (step == 2) {
                return new PlanStepDecision(
                        "先提炼用户画像，保证预算、天数和偏好字段进入任务书。",
                        "CALL_TOOL",
                        "profile.lookup",
                        Map.of(),
                        "",
                        "",
                        "",
                        List.of());
            }
            if (step == 3) {
                TaskBook fallback = fallbackPlan(state);
                return new PlanStepDecision(
                        "RAG 知识已经在会话记忆中预注入，直接整理可执行的工具执行计划。",
                        "FINISH",
                        PLANNER_FINISH,
                        Map.of(),
                        fallback.getMission(),
                        fallback.getPlannerThought(),
                        fallback.getTaskScript(),
                        fallback.getTasks().stream().map(this::mapTask).toList());
            }
        } else {
            if (step == 1) {
                return new PlanStepDecision(
                        "上一轮仍有缺口，依据 loop focus 补一个可执行的点位或事实搜索任务。",
                        "CALL_TOOL",
                        "map.poi.search",
                        Map.of(
                                "keywords", state.getLoopFocus().isBlank() ? valueOrDefault(state.getRequest().getPreferences()) : state.getLoopFocus(),
                                "query", state.getRequest().getMessage()),
                        "",
                        "",
                        "",
                        List.of());
            }
            if (step == 2) {
                return new PlanStepDecision(
                        "结合 loop focus 补一个可执行的点位搜索任务。",
                        "CALL_TOOL",
                        "map.poi.search",
                        Map.of(
                                "keywords", state.getLoopFocus().isBlank() ? valueOrDefault(state.getRequest().getPreferences()) : state.getLoopFocus(),
                                "query", state.getRequest().getMessage()),
                        "",
                        "",
                        "",
                        List.of());
            }
        }

        TaskBook fallback = fallbackPlan(state);
        List<Map<String, Object>> tasks = fallback.getTasks().stream()
                .map(this::mapTask)
                .toList();
        return new PlanStepDecision(
                "关键工具和事实已经足够，开始输出符合格式要求的工具执行计划。",
                "FINISH",
                PLANNER_FINISH,
                Map.of(),
                fallback.getMission(),
                fallback.getPlannerThought(),
                fallback.getTaskScript(),
                tasks);
    }

    private Map<String, Object> invokePlanningTool(ConversationState state,
                                                   AgentEventPublisher publisher,
                                                   int step,
                                                   String toolName,
                                                   Map<String, Object> rawInput) {
        Map<String, Object> safeInput = rawInput == null ? Map.of() : rawInput;
        String taskId = "plan-step-" + step;
        String taskName = "规划取数：" + toolName;

        publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                "phase", "PLAN",
                "taskOrigin", "PLANNER",
                "round", state.getCurrentRound(),
                "step", step,
                "batchIndex", step,
                "attempt", 1,
                "executionMode", "SERIAL",
                "taskId", taskId,
                "taskName", taskName,
                "toolName", toolName,
                "status", "RUNNING"
        ));

        if (TOOL_CATALOG.equals(toolName)) {
            Map<String, Object> catalog = Map.of(
                    "tools", toolRegistry.listDiscoveredToolTemplates().stream()
                            .map(ToolTemplate::toMap)
                            .toList());
            publishSafely(publisher, state, AgentEventType.TOOL_CALL, payload(
                    "phase", "PLAN",
                    "taskOrigin", "PLANNER",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "batchIndex", step,
                    "attempt", 1,
                    "executionMode", "SERIAL",
                    "taskId", taskId,
                    "taskName", taskName,
                    "toolName", TOOL_CATALOG,
                    "arguments", safeInput,
                    "input", safeInput
            ));
            publishSafely(publisher, state, AgentEventType.TOOL_RESULT, payload(
                    "phase", "PLAN",
                    "taskOrigin", "PLANNER",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "batchIndex", step,
                    "attempt", 1,
                    "executionMode", "SERIAL",
                    "taskId", taskId,
                    "taskName", taskName,
                    "toolName", TOOL_CATALOG,
                    "result", catalog
            ));
            publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                    "phase", "PLAN",
                    "taskOrigin", "PLANNER",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "batchIndex", step,
                    "attempt", 1,
                    "executionMode", "SERIAL",
                    "taskId", taskId,
                    "taskName", taskName,
                    "toolName", toolName,
                    "status", "DONE"
            ));
            return catalog;
        }

        if (!toolRegistry.hasTool(toolName)) {
            Map<String, Object> unavailable = Map.of(
                    "message", "当前工具不可直接调用",
                    "requestedTool", toolName,
                    "availableTools", toolRegistry.listLocalToolTemplates().stream()
                            .map(ToolTemplate::name)
                            .toList());
            publishSafely(publisher, state, AgentEventType.WARNING, Map.of(
                    "phase", "PLAN",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "message", "Plan Agent 选择了不可调用工具: " + toolName
            ));
            publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                    "phase", "PLAN",
                    "taskOrigin", "PLANNER",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "batchIndex", step,
                    "attempt", 1,
                    "executionMode", "SERIAL",
                    "taskId", taskId,
                    "taskName", taskName,
                    "toolName", toolName,
                    "status", "FAILED"
            ));
            return unavailable;
        }

        TaskItem syntheticTask = new TaskItem(
                taskId,
                taskName,
                "为任务书补齐事实",
                toolName,
                "plan-" + step,
                List.of(),
                safeInput,
                step,
                "SERIAL",
                "PLANNER");
        Map<String, Object> normalizedInput = toolInputNormalizer.normalize(syntheticTask, state);
        TravelTool tool = toolRegistry.get(toolName);

        publishSafely(publisher, state, AgentEventType.TOOL_CALL, payload(
                "phase", "PLAN",
                "taskOrigin", "PLANNER",
                "round", state.getCurrentRound(),
                "step", step,
                "batchIndex", step,
                "attempt", 1,
                "executionMode", "SERIAL",
                "taskId", taskId,
                "taskName", taskName,
                "toolName", toolName,
                "arguments", normalizedInput,
                "input", normalizedInput
        ));

        try {
            Map<String, Object> result = tool.execute(normalizedInput);
            if ("memory.rag.clear".equals(toolName) && !result.containsKey("error")) {
                state.setMemorySnapshot(clearedRagSnapshot(state.getMemorySnapshot()));
            }
            publishSafely(publisher, state, AgentEventType.TOOL_RESULT, payload(
                    "phase", "PLAN",
                    "taskOrigin", "PLANNER",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "batchIndex", step,
                    "attempt", 1,
                    "executionMode", "SERIAL",
                    "taskId", taskId,
                    "taskName", taskName,
                    "toolName", toolName,
                    "result", result
            ));
            publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                    "phase", "PLAN",
                    "taskOrigin", "PLANNER",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "batchIndex", step,
                    "attempt", 1,
                    "executionMode", "SERIAL",
                    "taskId", taskId,
                    "taskName", taskName,
                    "toolName", toolName,
                    "status", "DONE"
            ));
            return result;
        } catch (Exception ex) {
            Map<String, Object> failed = Map.of("error", String.valueOf(ex.getMessage()));
            publishSafely(publisher, state, AgentEventType.TOOL_RESULT, payload(
                    "phase", "PLAN",
                    "taskOrigin", "PLANNER",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "batchIndex", step,
                    "attempt", 1,
                    "executionMode", "SERIAL",
                    "taskId", taskId,
                    "taskName", taskName,
                    "toolName", toolName,
                    "result", failed
            ));
            publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                    "phase", "PLAN",
                    "taskOrigin", "PLANNER",
                    "round", state.getCurrentRound(),
                    "step", step,
                    "batchIndex", step,
                    "attempt", 1,
                    "executionMode", "SERIAL",
                    "taskId", taskId,
                    "taskName", taskName,
                    "toolName", toolName,
                    "status", "FAILED"
            ));
            return failed;
        }
    }

    private TaskBook buildTaskBookFromDecision(PlanStepDecision decision, ConversationState state) {
        if (decision.tasks() == null || decision.tasks().isEmpty()) {
            return fallbackPlan(state);
        }

        List<Map<String, Object>> preparedTasks = prepareRawTasks(decision.tasks());
        List<PreparedTask> stagedTasks = new ArrayList<>();
        for (int index = 0; index < preparedTasks.size(); index++) {
            Map<String, Object> rawTask = preparedTasks.get(index);
            String toolName = sanitizeToolName(String.valueOf(rawTask.getOrDefault("toolName", "profile.lookup")));
            if (!toolRegistry.hasTool(toolName)) {
                continue;
            }

            String taskId = String.valueOf(rawTask.get("taskId"));
            int batchIndex = parseInt(rawTask.get("batchIndex"), index + 1);
            stagedTasks.add(new PreparedTask(rawTask, toolName, batchIndex, index));
        }

        if (stagedTasks.isEmpty()) {
            return fallbackPlan(state);
        }

        List<TaskItem> tasks = stagedTasks.stream()
                .sorted(Comparator.comparingInt(PreparedTask::batchIndex)
                        .thenComparingInt(PreparedTask::ordinal))
                .map(staged -> {
                    Map<String, Object> rawTask = staged.rawTask();
                    List<String> dependsOn = objectMapper.convertValue(rawTask.getOrDefault("dependsOn", List.of()),
                            new TypeReference<List<String>>() {
                            });
                    Map<String, Object> input = objectMapper.convertValue(rawTask.getOrDefault("input", Map.of()),
                            new TypeReference<Map<String, Object>>() {
                            });
                    String executionMode = toolRegistry.isParallelizable(staged.toolName()) ? "PARALLEL" : "SERIAL";
                    return new TaskItem(
                            String.valueOf(rawTask.get("taskId")),
                            String.valueOf(rawTask.getOrDefault("name", "未命名任务")),
                            String.valueOf(rawTask.getOrDefault("objective", "")),
                            staged.toolName(),
                            "batch-" + staged.batchIndex(),
                            dependsOn,
                            input,
                            staged.batchIndex(),
                            executionMode,
                            "PLANNER");
                })
                .toList();

        if (tasks.isEmpty()) {
            return fallbackPlan(state);
        }

        String taskScript = buildTaskScript(tasks);

        return new TaskBook(
                defaultIfBlank(decision.mission(), "生成供 Execute 使用的工具执行计划"),
                defaultIfBlank(decision.plannerThought(), buildPlannerThought(
                        "当前计划已经覆盖关键事实，可以支撑 Execute 进入工具执行。",
                        "tasks 结构完整，batchIndex 可驱动执行器自动并行；taskScript 已按任务顺序生成。",
                        "将该工具执行计划交给 Execute Agent 逐批执行。")),
                taskScript,
                tasks
        );
    }

    private TaskBook fallbackPlan(ConversationState state) {
        TravelChatRequest request = state.getRequest();
        String destination = valueOrDefault(request.getDestination());

        Map<String, Object> sharedInput = new LinkedHashMap<>();
        sharedInput.put("destination", destination);
        sharedInput.put("budget", valueOrDefault(request.getBudget()));
        sharedInput.put("travelDays", valueOrDefault(request.getTravelDays()));
        sharedInput.put("preferences", valueOrDefault(request.getPreferences()));
        sharedInput.put("departure", valueOrDefault(request.getDeparture()));
        sharedInput.put("query", state.getLoopFocus().isBlank() ? request.getMessage() : state.getLoopFocus());

        Set<String> coveredTools = state.getExecutionResults().stream()
                .filter(TaskExecutionResult::isSuccess)
                .map(TaskExecutionResult::getToolName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> plannedTools = new LinkedHashSet<>();

        List<TaskItem> tasks = new ArrayList<>();
        int nextTaskId = 1;
        if (state.getCurrentRound() <= 1 || coveredTools.isEmpty()) {
            tasks.add(new TaskItem("t1", "用户画像分析", "提炼预算、天数和偏好",
                    "profile.lookup", "batch-1", List.of(), sharedInput, 1, "SERIAL", "PLANNER"));
            plannedTools.add("profile.lookup");
            nextTaskId = 2;
        }

        List<String> remainingTools = FALLBACK_FACT_TOOLS.stream()
                .filter(toolName -> !coveredTools.contains(toolName))
                .filter(toolName -> !plannedTools.contains(toolName))
                .toList();

        if (!remainingTools.isEmpty()) {
            int batchIndex = tasks.isEmpty() ? 1 : 2;
            for (String toolName : remainingTools) {
                tasks.add(defaultTaskForTool(nextTaskId++, toolName, sharedInput, batchIndex));
                plannedTools.add(toolName);
            }
        }

        if (tasks.isEmpty()) {
            tasks.add(new TaskItem(
                    "t1",
                    "补充点位搜索",
                    "围绕上一轮缺口补充可执行 POI",
                    "map.poi.search",
                    "batch-1",
                    List.of(),
                    sharedInput,
                    1,
                    toolRegistry.isParallelizable("map.poi.search") ? "PARALLEL" : "SERIAL",
                    "PLANNER"));
            tasks.add(new TaskItem(
                    "t2",
                    "预算审查",
                    "根据当前约束给出预算友好型建议",
                    "budget.audit",
                    "batch-1",
                    List.of(),
                    sharedInput,
                    1,
                    toolRegistry.isParallelizable("budget.audit") ? "PARALLEL" : "SERIAL",
                    "PLANNER"));
        }

        return new TaskBook(
                "生成供 Execute 使用的工具执行计划，逐批收集事实后产出旅游规划方案",
                state.getCurrentRound() <= 1
                        ? buildPlannerThought(
                        "该工具执行计划选择了当前必要的画像、天气、POI 与预算事实，RAG 知识已由会话记忆预置，可支撑 Execute 汇总旅游方案。",
                        "tasks 已按 batchIndex 编排，Execute 会根据 parallelizable 自动并行，taskScript 仅保留顺序摘要。",
                        "先单独执行 profile.lookup，再执行事实采集批次。")
                        : buildPlannerThought(
                        "上一轮仍有事实缺口，本轮补充任务后仍可支撑后续旅游方案生成。",
                        "tasks 已按 batchIndex 编排，Execute 会根据 parallelizable 自动并行，taskScript 仅保留顺序摘要。",
                        "围绕 loop focus 生成补充型工具执行计划。"),
                buildTaskScript(tasks),
                tasks
        );
    }

    private TaskItem defaultTaskForTool(int index,
                                        String toolName,
                                        Map<String, Object> sharedInput,
                                        int batchIndex) {
        String executionMode = toolRegistry.isParallelizable(toolName) ? "PARALLEL" : "SERIAL";
        return switch (toolName) {
            case "profile.lookup" -> new TaskItem("t" + index, "用户画像分析", "提炼预算、天数和偏好",
                    toolName, "batch-" + batchIndex, List.of(), sharedInput, batchIndex, executionMode, "PLANNER");
            case "weather.lookup" -> new TaskItem("t" + index, "天气规划检查", "判断是否需要室内备选方案",
                    toolName, "batch-" + batchIndex, List.of(), sharedInput, batchIndex, executionMode, "PLANNER");
            case "map.poi.search" -> new TaskItem("t" + index, "景点 POI 搜索", "产出可按区域聚合的景点列表",
                    toolName, "batch-" + batchIndex, List.of(), sharedInput, batchIndex, executionMode, "PLANNER");
            case "rag.travel.knowledge" -> new TaskItem("t" + index, "旅游知识检索", "检索旅游经验和路线启发",
                    toolName, "batch-" + batchIndex, List.of(), sharedInput, batchIndex, executionMode, "PLANNER");
            case "web.search" -> new TaskItem("t" + index, "实时网页搜索", "检索近期开放状态、攻略和活动信息",
                    toolName, "batch-" + batchIndex, List.of(), sharedInput, batchIndex, executionMode, "PLANNER");
            case "map.route.plan" -> new TaskItem("t" + index, "路线耗时估算", "估算出发地到目的地的交通距离和耗时",
                    toolName, "batch-" + batchIndex, List.of(), sharedInput, batchIndex, executionMode, "PLANNER");
            case "budget.audit" -> new TaskItem("t" + index, "预算审查", "生成预算友好型建议",
                    toolName, "batch-" + batchIndex, List.of(), sharedInput, batchIndex, executionMode, "PLANNER");
            default -> new TaskItem("t" + index, "补充事实", "补充生成最终规划所需信息",
                    "map.poi.search", "batch-" + batchIndex, List.of(), sharedInput, batchIndex, executionMode, "PLANNER");
        };
    }

    private List<Map<String, Object>> prepareRawTasks(List<Map<String, Object>> rawTasks) {
        List<Map<String, Object>> prepared = new ArrayList<>();
        for (Map<String, Object> rawTask : rawTasks) {
            Map<String, Object> copy = new LinkedHashMap<>(rawTask == null ? Map.of() : rawTask);
            String taskId = String.valueOf(copy.getOrDefault("taskId", "")).trim();
            if (taskId.isEmpty()) {
                taskId = "t-" + UUID.randomUUID();
            }
            copy.put("taskId", taskId);
            prepared.add(copy);
        }
        return prepared;
    }

    private String buildTaskScript(List<TaskItem> tasks) {
        return tasks.stream()
                .map(TaskItem::getTaskId)
                .collect(Collectors.joining("\n"));
    }

    private String sanitizeTaskScript(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private Map<String, Object> mapTask(TaskItem task) {
        return Map.of(
                "taskId", task.getTaskId(),
                "name", task.getName(),
                "objective", task.getObjective(),
                "toolName", task.getToolName(),
                "batchIndex", task.getBatchIndex(),
                "executionMode", task.getExecutionMode(),
                "parallelGroup", task.getParallelGroup(),
                "dependsOn", task.getDependsOn(),
                "input", task.getInput(),
                "sourcePhase", task.getSourcePhase()
        );
    }

    private Map<String, Object> payload(Object... entries) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            payload.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return payload;
    }

    private int parseInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return Math.max(number.intValue(), 1);
        }
        try {
            return Math.max(Integer.parseInt(String.valueOf(value).trim()), 1);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String formatToolCatalog() {
        String discovered = toolRegistry.listDiscoveredToolTemplates().stream()
                .map(tool -> "- %s [sourceType=%s provider=%s capability=%s invocable=%s readOnlyHint=%s parallelizable=%s] %s".formatted(
                        tool.name(),
                        tool.sourceType().code(),
                        tool.provider(),
                        tool.capabilityType().code(),
                        tool.invocable(),
                        tool.readOnlyHint(),
                        tool.parallelizable(),
                        tool.description()))
                .collect(Collectors.joining("\n"));

        return """
                - tool.catalog [sourceType=builtin provider=builtin invocable=true parallelizable=false] 返回当前工具目录
                - planner.finish [sourceType=builtin provider=builtin invocable=true parallelizable=false] 结束本轮 PLAN 循环
                %s
                """.formatted(discovered.isBlank() ? "" : discovered);
    }

    private String formatTaskBookHistory(List<TaskBook> taskBooks) {
        if (taskBooks == null || taskBooks.isEmpty()) {
            return "暂无";
        }
        return taskBooks.stream()
                .map(taskBook -> "- Mission=%s, taskScript=%s".formatted(
                        taskBook.getMission(),
                        taskBook.getTaskScript().isBlank() ? "无" : taskBook.getTaskScript().replace("\n", " | ")))
                .collect(Collectors.joining("\n"));
    }

    private String formatExecutionHistory(List<TaskExecutionResult> results) {
        if (results == null || results.isEmpty()) {
            return "暂无";
        }
        return results.stream()
                .limit(10)
                .map(result -> "- round=%s batch=%s task=%s attempts=%s success=%s observation=%s".formatted(
                        result.getRound(),
                        result.getBatchIndex(),
                        result.getToolName(),
                        result.getAttempts(),
                        result.isSuccess(),
                        clip(result.getObservation(), 120)))
                .collect(Collectors.joining("\n"));
    }

    private String formatSuccessfulTools(ConversationState state) {
        Set<String> successfulTools = successfulTools(state);
        if (successfulTools.isEmpty()) {
            return "暂无";
        }
        return String.join(", ", successfulTools);
    }

    private String memoryContext(ConversationState state) {
        if (state.getMemorySnapshot() == null || state.getMemorySnapshot().getPromptContext().isBlank()) {
            return "暂无";
        }
        return state.getMemorySnapshot().getPromptContext();
    }

    private PlanValidationReport validateFinishDecision(PlanStepDecision decision, ConversationState state) {
        List<String> reasons = new ArrayList<>();
        validateMission(decision.mission(), reasons);
        validatePlannerThought(decision.plannerThought(), reasons);

        List<Map<String, Object>> preparedTasks = prepareRawTasks(decision.tasks() == null ? List.of() : decision.tasks());
        if (preparedTasks.isEmpty()) {
            reasons.add("tasks 为空");
            return new PlanValidationReport(false, reasons);
        }

        validateTasks(preparedTasks, reasons);
        validateCoverage(preparedTasks.stream()
                .map(task -> sanitizeToolName(String.valueOf(task.getOrDefault("toolName", ""))))
                .toList(), state, reasons);
        return new PlanValidationReport(reasons.isEmpty(), reasons);
    }

    private PlanValidationReport validateTaskBook(TaskBook taskBook, ConversationState state) {
        List<String> reasons = new ArrayList<>();
        validateMission(taskBook.getMission(), reasons);
        validatePlannerThought(taskBook.getPlannerThought(), reasons);
        List<Map<String, Object>> taskMaps = taskBook.getTasks().stream()
                .map(this::mapTask)
                .toList();
        if (taskMaps.isEmpty()) {
            reasons.add("构建后的 tasks 为空");
            return new PlanValidationReport(false, reasons);
        }
        validateTasks(taskMaps, reasons);
        validateCoverage(taskBook.getTasks().stream().map(TaskItem::getToolName).toList(), state, reasons);
        return new PlanValidationReport(reasons.isEmpty(), reasons);
    }

    private void validateMission(String mission, List<String> reasons) {
        String normalized = mission == null ? "" : mission.trim();
        if (normalized.isBlank()) {
            reasons.add("mission 为空");
            return;
        }
        if (!(normalized.contains("执行计划") || normalized.contains("任务书") || normalized.contains("工具执行"))) {
            reasons.add("mission 未明确说明这是工具执行计划/任务书");
        }
    }

    private void validatePlannerThought(String plannerThought, List<String> reasons) {
        String normalized = plannerThought == null ? "" : plannerThought.trim();
        if (normalized.isBlank()) {
            reasons.add("plannerThought 为空");
            return;
        }
        if (!normalized.contains("可完成性：")) {
            reasons.add("plannerThought 缺少“可完成性：”片段");
        }
        if (!normalized.contains("格式校验：")) {
            reasons.add("plannerThought 缺少“格式校验：”片段");
        }
        if (!normalized.contains("执行意图：")) {
            reasons.add("plannerThought 缺少“执行意图：”片段");
        }
    }

    private void validateTasks(List<Map<String, Object>> preparedTasks, List<String> reasons) {
        Set<String> taskIds = new LinkedHashSet<>();
        int lastBatchIndex = 0;

        for (int index = 0; index < preparedTasks.size(); index++) {
            Map<String, Object> task = preparedTasks.get(index);
            String taskId = String.valueOf(task.get("taskId"));
            if (!taskIds.add(taskId)) {
                reasons.add("tasks 中存在重复 taskId：" + taskId);
            }

            String toolName = sanitizeToolName(String.valueOf(task.getOrDefault("toolName", "")));
            if (toolName.isBlank()) {
                reasons.add("任务 " + taskId + " 缺少 toolName");
            }

            int batchIndex = parseInt(task.get("batchIndex"), index + 1);
            if (batchIndex < lastBatchIndex) {
                reasons.add("任务 " + taskId + " 的 batchIndex 小于前序任务，必须保持非递减");
            }
            lastBatchIndex = batchIndex;

            Object input = task.get("input");
            if (input != null && !(input instanceof Map<?, ?>)) {
                reasons.add("任务 " + taskId + " 的 input 必须为对象");
            }
        }
    }

    private void validateCoverage(List<String> plannedTools, ConversationState state, List<String> reasons) {
        for (String toolName : plannedTools) {
            String sanitized = sanitizeToolName(toolName);
            if (sanitized.isBlank()) {
                reasons.add("任务存在空 toolName");
                continue;
            }
            if (!toolRegistry.hasTool(sanitized)) {
                reasons.add("任务引用了未注册或不可调用工具：" + sanitized);
                continue;
            }
            ToolTemplate template = toolRegistry.getToolTemplate(sanitized);
            if (!template.invocable()) {
                reasons.add("任务引用了不可调用模板：" + sanitized);
            }
        }
    }

    private Set<String> successfulTools(ConversationState state) {
        return state.getExecutionResults().stream()
                .filter(TaskExecutionResult::isSuccess)
                .map(TaskExecutionResult::getToolName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String buildPlannerThought(String feasibility, String formatCheck, String executionIntent) {
        return "可完成性：" + defaultIfBlank(feasibility, "待评估")
                + "；格式校验：" + defaultIfBlank(formatCheck, "待评估")
                + "；执行意图：" + defaultIfBlank(executionIntent, "待评估");
    }

    private String formatToolNameOptions(boolean includePlannerTools) {
        List<String> options = new ArrayList<>();
        if (includePlannerTools) {
            options.add(TOOL_CATALOG);
        }
        options.addAll(toolRegistry.listDiscoveredToolTemplates().stream()
                .filter(ToolTemplate::invocable)
                .map(ToolTemplate::name)
                .distinct()
                .sorted()
                .toList());
        if (includePlannerTools) {
            options.add(PLANNER_FINISH);
        }
        return String.join(" | ", options);
    }

    private void publishSafely(AgentEventPublisher publisher,
                               ConversationState state,
                               AgentEventType eventType,
                               Map<String, Object> payload) {
        try {
            publisher.publish(state, eventType, payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish SSE event", ex);
        }
    }

    private String sanitizeJsonPayload(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }

    private String sanitizeToolName(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("`", "").trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String valueOrDefault(String value) {
        return value == null || value.isBlank() ? "未说明" : value;
    }

    private String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim() + "...";
    }

    private String clipJson(Map<String, Object> value) {
        try {
            return clip(objectMapper.writeValueAsString(value), 220);
        } catch (Exception ex) {
            return clip(String.valueOf(value), 220);
        }
    }

    private ConversationMemorySnapshot clearedRagSnapshot(ConversationMemorySnapshot snapshot) {
        ConversationMemorySnapshot current = snapshot == null ? ConversationMemorySnapshot.empty() : snapshot;
        String promptContext = """
                长期偏好记忆：
                %s

                历史会话压缩记忆：
                %s

                当前窗口记忆：
                %s

                会话级 RAG 增强知识 rag[]：
                rag[] 已被工具 memory.rag.clear 清除；本 session 不会自动重复检索。
                """.formatted(
                current.getPreferenceSummary(),
                current.getSessionSummary(),
                current.getChatWindow() == null || current.getChatWindow().isEmpty()
                        ? "暂无"
                        : String.join("\n", current.getChatWindow()));
        return current.withoutRag(promptContext);
    }

    private record PlanStepDecision(String thought,
                                    String actionType,
                                    String toolName,
                                    Map<String, Object> input,
                                    String mission,
                                    String plannerThought,
                                    String taskScript,
                                    List<Map<String, Object>> tasks) {
    }

    private record PreparedTask(Map<String, Object> rawTask, String toolName, int batchIndex, int ordinal) {
    }

    private record PlanValidationReport(boolean valid, List<String> reasons) {
        private String summary() {
            return reasons == null || reasons.isEmpty() ? "通过" : String.join("；", reasons);
        }
    }
}

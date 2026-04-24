package com.voyu.agent.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import com.voyu.agent.model.agent.AgentEventType;
import com.voyu.agent.model.agent.AgentMode;
import com.voyu.agent.model.agent.ConversationMemorySnapshot;
import com.voyu.agent.model.agent.ConversationState;
import com.voyu.agent.model.agent.TaskBook;
import com.voyu.agent.model.agent.TaskExecutionResult;
import com.voyu.agent.model.agent.TaskItem;
import com.voyu.agent.model.api.TravelChatRequest;
import com.voyu.agent.service.llm.LlmFacade;
import com.voyu.agent.tool.ToolInputNormalizer;
import com.voyu.agent.tool.ToolRegistry;
import com.voyu.agent.tool.ToolResultInterpreter;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import com.voyu.agent.util.AgentEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 统一 ReAct 循环 Agent，参考 Claude Code 的 plan mode 设计。
 * <p>
 * 替代原有的 PlanAgent + ExecuteAgent 双循环架构，使用单一 while 循环，
 * 通过 {@link AgentMode} 状态区分 PLAN 模式和 EXECUTE 模式。
 * <p>
 * 核心流程：
 * <ol>
 *     <li>默认从 PLAN 模式开始，LLM 做信息收集和任务规划</li>
 *     <li>LLM 决定 FINISH_PLAN 时，生成 plan.md 文件并切换到 EXECUTE 模式</li>
 *     <li>切换时设置 justExitedPlan 标志，在 EXECUTE 第一步注入任务书上下文</li>
 *     <li>EXECUTE 模式下每 N 步注入简短提醒</li>
 *     <li>LLM 决定 FINISH 时，输出最终答案</li>
 * </ol>
 */
@Component
public class UnifiedReActAgent {

    private static final Logger log = LoggerFactory.getLogger(UnifiedReActAgent.class);
    private static final String TOOL_CATALOG = "tool.catalog";

    private final LlmFacade llmFacade;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final ToolInputNormalizer toolInputNormalizer;
    private final ToolResultInterpreter toolResultInterpreter;
    private final PlanFileService planFileService;
    private final int maxReactSteps;
    private final int stepReminderInterval;

    public UnifiedReActAgent(LlmFacade llmFacade,
                             ObjectMapper objectMapper,
                             ToolRegistry toolRegistry,
                             ToolInputNormalizer toolInputNormalizer,
                             ToolResultInterpreter toolResultInterpreter,
                             PlanFileService planFileService,
                             @Value("${voyu.agent.max-react-steps:20}") int maxReactSteps,
                             @Value("${voyu.agent.step-reminder-interval:5}") int stepReminderInterval) {
        this.llmFacade = llmFacade;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.toolInputNormalizer = toolInputNormalizer;
        this.toolResultInterpreter = toolResultInterpreter;
        this.planFileService = planFileService;
        this.maxReactSteps = maxReactSteps;
        this.stepReminderInterval = Math.max(stepReminderInterval, 2);
    }

    @PostConstruct
    void logConfig() {
        log.info("[UnifiedReActAgent] maxReactSteps={}, stepReminderInterval={}", maxReactSteps, stepReminderInterval);
    }

    /**
     * 运行统一 ReAct 循环，返回最终答案文本。
     */
    public String run(ConversationState state, AgentEventPublisher publisher) {
        List<String> scratchpad = new ArrayList<>();

        while (state.getReactStep() < maxReactSteps) {
            int step = state.advanceReactStep();

            // 1. EXECUTE 模式下每 N 步注入简短提醒
            if (state.getAgentMode() == AgentMode.EXECUTE
                    && step > 1
                    && (step % stepReminderInterval == 0)) {
                injectStepReminder(state, publisher, step, scratchpad);
            }

            // 2. 若 justExitedPlan，注入任务书 + 执行指引，然后清标志
            if (state.isJustExitedPlan()) {
                injectPlanTransitionContext(state, publisher, scratchpad);
                state.setJustExitedPlan(false);
            }

            // 3. 调用 LLM 获取决策
            ReActDecision decision = thinkNextStep(state, scratchpad, step);
            publishSafely(publisher, state, AgentEventType.THOUGHT, payload(
                    "phase", "REACT",
                    "agentMode", state.getAgentMode().name(),
                    "step", step,
                    "message", decision.thought()
            ));

            // 4. 根据决策分支
            switch (decision.actionType()) {
                case "CALL_TOOL" -> {
                    Map<String, Object> toolResult = invokeTool(state, publisher, step, decision.toolName(), decision.input());
                    String observation = buildObservationSummary(decision.toolName(), toolResult);
                    scratchpad.add("""
                            Step %s [%s]
                            Thought: %s
                            Action: %s
                            Observation: %s
                            """.formatted(step, state.getAgentMode().name(), decision.thought(), decision.toolName(), observation));

                    // 记录到 executionResults
                    boolean usable = isToolResultUsable(decision.toolName(), toolResult);
                    state.getExecutionResults().add(new TaskExecutionResult(
                            "react-step-" + step,
                            "ReAct 步骤 " + step + ": " + decision.toolName(),
                            decision.toolName(),
                            usable,
                            decision.thought(),
                            observation,
                            state.getCurrentRound(),
                            step,
                            "REACT",
                            1));

                    // 若工具是 memory.rag.clear，更新状态
                    if ("memory.rag.clear".equals(decision.toolName()) && !toolResult.containsKey("error")) {
                        state.setMemorySnapshot(clearedRagSnapshot(state.getMemorySnapshot()));
                    }
                }

                case "FINISH_PLAN" -> {
                    // 生成 plan.md 文件
                    TaskBook taskBook = buildTaskBookFromDecision(decision, state);
                    state.getTaskBooks().add(taskBook);
                    state.getPlanThoughts().add(decision.plannerThought());

                    TravelChatRequest request = state.getRequest();
                    List<String> taskLines = taskBook.getTasks().stream()
                            .map(task -> "%s: %s (%s)".formatted(task.getTaskId(), task.getName(), task.getToolName()))
                            .toList();
                    String planContent = planFileService.buildPlanMarkdown(
                            state.getSessionId(),
                            taskBook.getMission(),
                            decision.plannerThought(),
                            request.getMessage(),
                            request.getDestination(),
                            request.getDeparture(),
                            request.getTravelDays(),
                            request.getBudget(),
                            request.getPreferences(),
                            taskLines);
                    String planPath = planFileService.createPlanFile(state.getSessionId(), planContent);
                    state.setPlanFilePath(planPath);

                    // 发布计划文件事件
                    publishSafely(publisher, state, AgentEventType.PLAN_FILE, payload(
                            "phase", "REACT",
                            "agentMode", "PLAN",
                            "step", step,
                            "planFilePath", planPath,
                            "mission", taskBook.getMission(),
                            "taskCount", taskBook.getTasks().size(),
                            "taskScript", taskBook.getTaskScript()
                    ));

                    // 发布任务书事件（兼容前端）
                    publishSafely(publisher, state, AgentEventType.TASK_BOOK, payload(
                            "round", state.getCurrentRound(),
                            "mission", taskBook.getMission(),
                            "taskScript", taskBook.getTaskScript(),
                            "tasks", taskBook.getTasks()
                    ));

                    // 切换到 EXECUTE 模式
                    state.setAgentMode(AgentMode.EXECUTE);
                    state.setJustExitedPlan(true);
                    publishSafely(publisher, state, AgentEventType.MODE_SWITCH, payload(
                            "phase", "REACT",
                            "from", "PLAN",
                            "to", "EXECUTE",
                            "step", step,
                            "message", "规划完成，进入执行模式。"
                    ));

                    scratchpad.add("""
                            Step %s [PLAN → EXECUTE]
                            Thought: %s
                            Action: FINISH_PLAN
                            Plan: %s
                            Tasks: %s
                            """.formatted(step, decision.thought(), taskBook.getMission(),
                            taskLines.stream().collect(Collectors.joining(", "))));
                }

                case "FINISH" -> {
                    // 输出最终答案
                    String finalAnswer = decision.answer();
                    if (finalAnswer == null || finalAnswer.isBlank()) {
                        finalAnswer = generateFallbackSummary(state);
                    }

                    // 更新 plan 文件的执行记录
                    if (planFileService.exists(state.getSessionId())) {
                        String executionLog = buildExecutionLog(state);
                        planFileService.appendToPlanFile(state.getSessionId(), "\n" + executionLog);
                    }

                    return finalAnswer;
                }

                default -> {
                    log.warn("未知的 ReAct 决策类型: {}, 视为 CALL_TOOL", decision.actionType());
                    scratchpad.add("Step %s: 未知决策类型 %s，跳过".formatted(step, decision.actionType()));
                }
            }
        }

        // 达到最大步数，使用已有结果生成答案
        log.warn("ReAct loop exhausted: actualStep={}, maxReactSteps={}, mode={}",
                state.getReactStep(), maxReactSteps, state.getAgentMode());
        publishSafely(publisher, state, AgentEventType.WARNING, payload(
                "phase", "REACT",
                "agentMode", state.getAgentMode().name(),
                "step", state.getReactStep(),
                "maxSteps", maxReactSteps,
                "message", "ReAct 循环已达最大步数 %s（实际执行 %s 步），基于当前结果生成最终答案。".formatted(maxReactSteps, state.getReactStep())
        ));
        return generateFallbackSummary(state);
    }

    // ======== LLM 调用 ========

    private ReActDecision thinkNextStep(ConversationState state, List<String> scratchpad, int step) {
        String systemPrompt = state.getAgentMode() == AgentMode.PLAN
                ? buildPlanSystemPrompt()
                : buildExecuteSystemPrompt();
        String userPrompt = buildUserPrompt(state, scratchpad, step);

        String llmResult = llmFacade.complete(systemPrompt, userPrompt);
        if (llmResult != null && !llmResult.isBlank()) {
            String sanitized = sanitizeJsonPayload(llmResult);
            try {
                Map<String, Object> parsed = objectMapper.readValue(sanitized,
                        new TypeReference<Map<String, Object>>() {});
                return toDecision(parsed, state);
            } catch (Exception ex) {
                log.warn("Failed to parse ReAct step {} JSON. Raw (first 300): {}. Error: {}",
                        step, clip(llmResult, 300), ex.getMessage());
                // Try to extract embedded JSON from non-standard output
                ReActDecision extracted = tryExtractDecisionFromText(llmResult, state);
                if (extracted != null) {
                    return extracted;
                }
            }
        } else {
            log.warn("LLM returned null/blank at step {} (mode={}), using fallback", step, state.getAgentMode());
        }

        return fallbackDecision(state, step);
    }

    /**
     * When LLM output is not pure JSON, try to find the first { ... } block.
     */
    private ReActDecision tryExtractDecisionFromText(String rawText, ConversationState state) {
        int firstBrace = rawText.indexOf('{');
        int lastBrace = rawText.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            String candidate = rawText.substring(firstBrace, lastBrace + 1);
            try {
                Map<String, Object> parsed = objectMapper.readValue(candidate,
                        new TypeReference<Map<String, Object>>() {});
                log.info("Successfully extracted JSON from non-standard LLM output");
                return toDecision(parsed, state);
            } catch (Exception ignored) {
                // extraction also failed
            }
        }
        return null;
    }

    // ======== System Prompts（参考 Claude Code plan mode 风格）========

    private String buildPlanSystemPrompt() {
        return """
                你是旅游规划助手，当前处于 **PLAN 模式**（规划模式）。

                ## 核心规则
                PLAN 模式的唯一目标是：**制定工具调用计划（任务书）**，然后输出 FINISH_PLAN。
                你不需要在 PLAN 阶段亲自调用业务工具获取数据。工具会在后续 EXECUTE 阶段由系统逐项执行。
                PLAN 阶段你只需要决定：调用哪些工具、按什么顺序、传什么参数。

                ## 你在 PLAN 模式下的职责
                1. 分析用户需求，理解目的地、天数、预算、偏好等关键约束
                2. 查看可用工具目录（tool.catalog），了解每个工具的功能和参数要求
                3. 根据需求和工具能力，制定一份结构化的工具执行计划（任务书）
                4. 任务书制定完成后，选择 FINISH_PLAN 输出，系统将进入 EXECUTE 模式逐项执行

                ## 可用动作
                1. **CALL_TOOL** — 仅限调用 tool.catalog 查看工具目录，或 memory.rag.clear 清除过时 RAG。不要在 PLAN 中调用其他业务工具。
                2. **FINISH_PLAN** — 输出最终任务书，退出 PLAN 模式进入 EXECUTE 模式。这是 PLAN 模式的正常结束方式。

                ## 输出 JSON 格式（直接输出 JSON，不要输出 markdown 包裹或解释文字）
                {
                  "thought": "你的思考过程：分析用户需求并决定需要哪些工具",
                  "action": {
                    "type": "CALL_TOOL 或 FINISH_PLAN",
                    "toolName": "工具名称（仅 CALL_TOOL 时需要）",
                    "input": {}
                  },
                  "plannerThought": "仅 FINISH_PLAN 时填写：可完成性、格式校验、执行意图",
                  "mission": "仅 FINISH_PLAN 时填写：本次旅游规划的总目标",
                  "tasks": [
                    {
                      "taskId": "t1",
                      "name": "任务名称",
                      "objective": "这个工具调用要达成什么目标",
                      "toolName": "具体工具名称",
                      "batchIndex": 1,
                      "input": { "具体参数": "值" }
                    }
                  ]
                }

                ## 任务书设计原则
                - tasks 数组中的每个任务对应 EXECUTE 阶段的一次工具调用
                - batchIndex 表示串行阶段编号。同一 batchIndex 的任务会并行执行，不同 batchIndex 按顺序串行
                - 每个 task 的 input 应包含该工具所需的全部参数，参数值从用户需求和上下文中提取
                - 根据旅游规划需要合理安排工具（天气查询、POI搜索、路线规划、网页搜索等）
                - 只安排用户需求真正需要的工具，不要为了全面而堆砌
                - RAG 知识已在 rag[] 中预注入，除非缺失或不相关，否则不安排 rag.travel.knowledge
                - 如果 rag[] 过时，可先 CALL_TOOL memory.rag.clear 清除，再 FINISH_PLAN

                ## 典型流程
                Step 1: CALL_TOOL tool.catalog -> 了解可用工具及其参数
                Step 2: FINISH_PLAN -> 根据用户需求和可用工具制定任务书

                ## 可用工具名称（详细信息通过 tool.catalog 获取）
                %s
                """.formatted(formatToolNameOptions());
    }

    private String buildExecuteSystemPrompt() {
        return """
                你是旅游规划助手，当前处于 **EXECUTE 模式**（执行模式）。

                ## 你在 EXECUTE 模式下的职责
                你正在根据 PLAN 阶段制定的任务书，逐项调用工具收集事实，最后综合所有工具返回的真实数据生成旅游规划方案。

                ## 执行流程
                1. 查看任务书中还有哪些工具未执行（参考 Scratchpad 和已成功覆盖工具列表）
                2. 逐项 CALL_TOOL 执行任务书中的工具（一次选一个）
                3. 如果工具调用失败，可以调整参数重试或跳过继续下一个
                4. 如果所有工具执行后仍缺少关键信息，可以补充调用额外工具
                5. 当所有必要数据收集完毕后，选择 FINISH 综合所有工具结果输出最终方案

                ## 重要：不要过早 FINISH
                - 必须先执行任务书中的工具（至少尝试调用每个工具一次），然后才能 FINISH
                - 只有在对已收集的数据有足够信心时才选择 FINISH

                ## 可用动作
                1. **CALL_TOOL** — 调用一个工具并获取结果
                2. **FINISH** — 综合所有已收集的数据，输出最终旅游规划方案

                ## 输出 JSON 格式（直接输出 JSON，不要输出 markdown 包裹或解释文字）
                {
                  "thought": "当前要执行任务书中的哪个工具、为什么",
                  "action": {
                    "type": "CALL_TOOL 或 FINISH",
                    "toolName": "工具名称（CALL_TOOL 时）",
                    "input": { "参数": "值" }
                  },
                  "answer": "仅 FINISH 时填写：基于工具返回数据综合的最终旅游规划方案（markdown 格式）"
                }

                ## 最终答案要求（FINISH 时的 answer 字段）
                必须包含以下结构，且内容需基于工具返回的真实数据：
                1. **行程概览** — 总天数、核心目的地、主题
                2. **每日安排建议** — 每天的景点、餐饮、交通建议
                3. **风险与备选方案** — 天气风险、备选景点
                4. **预算/交通提示** — 费用估算、出行方式
                5. **依据与说明** — 引用具体工具返回的数据作为支撑

                ## 执行原则
                - 使用自然、专业、面向用户的中文 markdown
                - 若工具结果是启发式或占位信息，明确写成建议而非实时事实
                - 如果某些事实仍不足，在方案中明确列出缺口
                - 不要提及系统内部实现细节（如 tool.catalog、任务书、ReAct 步数等）
                - 可用工具名称：%s
                """.formatted(formatToolNameOptions());
    }

    private String buildUserPrompt(ConversationState state, List<String> scratchpad, int step) {
        TravelChatRequest request = state.getRequest();
        StringBuilder sb = new StringBuilder();

        sb.append("当前模式：").append(state.getAgentMode().name()).append("\n");
        sb.append("当前步数：").append(step).append(" / ").append(maxReactSteps).append("\n\n");

        sb.append("用户原始需求：").append(request.getMessage()).append("\n");
        sb.append("目的地：").append(valueOrDefault(request.getDestination())).append("\n");
        sb.append("出发地：").append(valueOrDefault(request.getDeparture())).append("\n");
        sb.append("旅行天数：").append(valueOrDefault(request.getTravelDays())).append("\n");
        sb.append("预算：").append(valueOrDefault(request.getBudget())).append("\n");
        sb.append("偏好：").append(valueOrDefault(request.getPreferences())).append("\n\n");

        sb.append("记忆上下文：\n").append(memoryContext(state)).append("\n\n");
        sb.append("当前已发现工具目录：\n").append(formatToolCatalog()).append("\n\n");

        if (!state.getTaskBooks().isEmpty()) {
            sb.append("历史任务书摘要：\n").append(formatTaskBookHistory(state.getTaskBooks())).append("\n\n");
        }
        if (!state.getExecutionResults().isEmpty()) {
            sb.append("历史执行结果摘要：\n").append(formatExecutionHistory(state.getExecutionResults())).append("\n\n");
        }

        Set<String> successfulTools = successfulTools(state);
        if (!successfulTools.isEmpty()) {
            sb.append("当前已成功覆盖工具：").append(String.join(", ", successfulTools)).append("\n\n");
        }

        // plan 文件内容（EXECUTE 模式下注入）
        if (state.getAgentMode() == AgentMode.EXECUTE && planFileService.exists(state.getSessionId())) {
            String planContent = planFileService.readPlanFile(state.getSessionId());
            if (!planContent.isBlank()) {
                sb.append("当前 Plan 文件内容：\n").append(clip(planContent, 2000)).append("\n\n");
            }
        }

        sb.append("Scratchpad：\n");
        sb.append(scratchpad.isEmpty() ? "暂无" : String.join("\n", scratchpad)).append("\n");

        return sb.toString();
    }

    // ======== 模式切换注入 ========

    /**
     * 刚退出 PLAN 模式进入 EXECUTE 模式时的过渡注入。
     * 参考 Claude Code：退出 plan 模式后注入任务书和执行指引。
     */
    private void injectPlanTransitionContext(ConversationState state, AgentEventPublisher publisher, List<String> scratchpad) {
        String planContent = planFileService.readPlanFile(state.getSessionId());
        String transitionMessage;
        if (planContent.isBlank()) {
            transitionMessage = "已退出规划模式，进入执行模式。请根据用户需求直接执行工具并生成最终方案。";
        } else {
            transitionMessage = """
                    已退出规划模式，进入执行模式。以下是规划阶段产出的任务书，请按照计划逐项执行：

                    %s

                    现在请开始按计划执行工具调用，收集完全部事实后生成最终旅游规划方案。
                    """.formatted(clip(planContent, 2000));
        }

        publishSafely(publisher, state, AgentEventType.THOUGHT, payload(
                "phase", "REACT",
                "agentMode", "EXECUTE",
                "step", state.getReactStep(),
                "message", transitionMessage
        ));

        scratchpad.add("""
                [模式切换 PLAN → EXECUTE]
                任务书已加载，开始按计划执行工具调用。
                """);
    }

    /**
     * 每 N 步简短提醒当前进度。
     * 参考 Claude Code：定期提示剩余任务。
     */
    private void injectStepReminder(ConversationState state, AgentEventPublisher publisher, int step, List<String> scratchpad) {
        long totalTasks = state.getTaskBooks().stream()
                .mapToLong(tb -> tb.getTasks().size())
                .sum();
        long completedTasks = state.getExecutionResults().stream()
                .filter(TaskExecutionResult::isSuccess)
                .count();

        String reminder = "[提醒] 当前已执行 %s 步，已完成 %s/%s 个任务，剩余步数上限 %s。请评估是否已有足够信息生成最终方案。"
                .formatted(step, completedTasks, totalTasks, maxReactSteps - step);

        publishSafely(publisher, state, AgentEventType.STEP_REMINDER, payload(
                "phase", "REACT",
                "agentMode", state.getAgentMode().name(),
                "step", step,
                "completedTasks", completedTasks,
                "totalTasks", totalTasks,
                "remainingSteps", maxReactSteps - step,
                "message", reminder
        ));

        scratchpad.add(reminder);
    }

    // ======== 工具调用 ========

    private Map<String, Object> invokeTool(ConversationState state, AgentEventPublisher publisher,
                                            int step, String toolName, Map<String, Object> rawInput) {
        Map<String, Object> safeInput = rawInput == null ? Map.of() : rawInput;
        String taskId = "react-step-" + step;
        String taskName = (state.getAgentMode() == AgentMode.PLAN ? "规划取数" : "执行") + "：" + toolName;

        publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                "phase", "REACT",
                "agentMode", state.getAgentMode().name(),
                "taskOrigin", state.getAgentMode().name(),
                "step", step,
                "taskId", taskId,
                "taskName", taskName,
                "toolName", toolName,
                "status", "RUNNING"
        ));

        // 内置伪工具：tool.catalog
        if (TOOL_CATALOG.equals(toolName)) {
            Map<String, Object> catalog = Map.of(
                    "tools", toolRegistry.listDiscoveredToolTemplates().stream()
                            .map(ToolTemplate::toMap)
                            .toList());
            publishToolCallAndResult(publisher, state, step, taskId, taskName, TOOL_CATALOG, safeInput, catalog);
            publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                    "phase", "REACT", "agentMode", state.getAgentMode().name(),
                    "taskOrigin", state.getAgentMode().name(),
                    "step", step, "taskId", taskId, "taskName", taskName,
                    "toolName", toolName, "status", "DONE"
            ));
            return catalog;
        }

        // 检查工具是否注册
        if (!toolRegistry.hasTool(toolName)) {
            Map<String, Object> unavailable = Map.of(
                    "message", "当前工具不可直接调用",
                    "requestedTool", toolName,
                    "availableTools", toolRegistry.listLocalToolTemplates().stream()
                            .map(ToolTemplate::name)
                            .toList());
            publishSafely(publisher, state, AgentEventType.WARNING, payload(
                    "phase", "REACT", "agentMode", state.getAgentMode().name(),
                    "step", step, "message", "选择了不可调用工具: " + toolName
            ));
            publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                    "phase", "REACT", "agentMode", state.getAgentMode().name(),
                    "taskOrigin", state.getAgentMode().name(),
                    "step", step, "taskId", taskId, "taskName", taskName,
                    "toolName", toolName, "status", "FAILED"
            ));
            return unavailable;
        }

        // 正常执行工具
        TaskItem syntheticTask = new TaskItem(taskId, taskName, "ReAct 步骤执行",
                toolName, "react-" + step, List.of(), safeInput, step, "REACT", state.getAgentMode().name());
        Map<String, Object> normalizedInput = toolInputNormalizer.normalize(syntheticTask, state);
        TravelTool tool = toolRegistry.get(toolName);

        publishSafely(publisher, state, AgentEventType.TOOL_CALL, payload(
                "phase", "REACT", "agentMode", state.getAgentMode().name(),
                "taskOrigin", state.getAgentMode().name(),
                "step", step, "taskId", taskId, "taskName", taskName,
                "toolName", toolName, "arguments", normalizedInput, "input", normalizedInput
        ));

        try {
            Map<String, Object> result = tool.execute(normalizedInput);
            publishSafely(publisher, state, AgentEventType.TOOL_RESULT, payload(
                    "phase", "REACT", "agentMode", state.getAgentMode().name(),
                    "taskOrigin", state.getAgentMode().name(),
                    "step", step, "taskId", taskId, "taskName", taskName,
                    "toolName", toolName, "result", result
            ));
            publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                    "phase", "REACT", "agentMode", state.getAgentMode().name(),
                    "taskOrigin", state.getAgentMode().name(),
                    "step", step, "taskId", taskId, "taskName", taskName,
                    "toolName", toolName, "status", "DONE"
            ));
            return result;
        } catch (Exception ex) {
            Map<String, Object> failed = Map.of("error", String.valueOf(ex.getMessage()));
            publishSafely(publisher, state, AgentEventType.TOOL_RESULT, payload(
                    "phase", "REACT", "agentMode", state.getAgentMode().name(),
                    "taskOrigin", state.getAgentMode().name(),
                    "step", step, "taskId", taskId, "taskName", taskName,
                    "toolName", toolName, "result", failed
            ));
            publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                    "phase", "REACT", "agentMode", state.getAgentMode().name(),
                    "taskOrigin", state.getAgentMode().name(),
                    "step", step, "taskId", taskId, "taskName", taskName,
                    "toolName", toolName, "status", "FAILED"
            ));
            return failed;
        }
    }

    private void publishToolCallAndResult(AgentEventPublisher publisher, ConversationState state,
                                           int step, String taskId, String taskName,
                                           String toolName, Map<String, Object> input, Map<String, Object> result) {
        publishSafely(publisher, state, AgentEventType.TOOL_CALL, payload(
                "phase", "REACT", "agentMode", state.getAgentMode().name(),
                "taskOrigin", state.getAgentMode().name(),
                "step", step, "taskId", taskId, "taskName", taskName,
                "toolName", toolName, "arguments", input, "input", input
        ));
        publishSafely(publisher, state, AgentEventType.TOOL_RESULT, payload(
                "phase", "REACT", "agentMode", state.getAgentMode().name(),
                "taskOrigin", state.getAgentMode().name(),
                "step", step, "taskId", taskId, "taskName", taskName,
                "toolName", toolName, "result", result
        ));
    }

    // ======== 决策解析 ========

    private ReActDecision toDecision(Map<String, Object> parsed, ConversationState state) {
        Map<String, Object> action = objectMapper.convertValue(
                parsed.getOrDefault("action", Map.of()), new TypeReference<>() {});
        String actionType = String.valueOf(action.getOrDefault("type", "CALL_TOOL")).trim().toUpperCase();
        String toolName = sanitizeToolName(String.valueOf(action.getOrDefault("toolName", "")));
        Map<String, Object> input = objectMapper.convertValue(
                action.getOrDefault("input", Map.of()), new TypeReference<>() {});
        String thought = String.valueOf(parsed.getOrDefault("thought", "分析当前状态并决定下一步。")).trim();

        // FINISH_PLAN 相关
        String mission = String.valueOf(parsed.getOrDefault("mission", "")).trim();
        String plannerThought = String.valueOf(parsed.getOrDefault("plannerThought", "")).trim();
        List<Map<String, Object>> tasks = objectMapper.convertValue(
                parsed.getOrDefault("tasks", List.of()), new TypeReference<>() {});

        // FINISH 相关
        String answer = String.valueOf(parsed.getOrDefault("answer", "")).trim();

        // 规范化 action type
        if ("FINISH_PLAN".equals(actionType) && state.getAgentMode() != AgentMode.PLAN) {
            // 非 PLAN 模式下不允许 FINISH_PLAN，降级为 FINISH
            actionType = "FINISH";
        }
        if ("FINISH".equals(actionType) && state.getAgentMode() == AgentMode.PLAN) {
            // PLAN 模式下的 FINISH 视为 FINISH_PLAN
            actionType = "FINISH_PLAN";
        }

        return new ReActDecision(thought, actionType, toolName, input, mission, plannerThought, tasks, answer);
    }

    private ReActDecision fallbackDecision(ConversationState state, int step) {
        if (state.getAgentMode() == AgentMode.PLAN) {
            return fallbackPlanDecision(state, step);
        }
        return fallbackExecuteDecision(state, step);
    }

    private ReActDecision fallbackPlanDecision(ConversationState state, int step) {
        // PLAN fallback: check if tool.catalog was already seen, not step-number based
        boolean hasSeenCatalog = state.getExecutionResults().stream()
                .anyMatch(r -> TOOL_CATALOG.equals(r.getToolName()) && r.isSuccess());

        if (!hasSeenCatalog) {
            return new ReActDecision(
                    "[fallback] 先读取工具目录，了解可用工具及参数。",
                    "CALL_TOOL", TOOL_CATALOG, Map.of(), "", "", List.of(), "");
        }

        // Already seen catalog, generate task book
        TaskBook fallback = buildFallbackTaskBook(state);
        List<Map<String, Object>> taskMaps = fallback.getTasks().stream()
                .map(this::mapTaskToRaw)
                .toList();
        return new ReActDecision(
                "[fallback] 根据可用工具和用户需求生成工具执行计划。",
                "FINISH_PLAN", "", Map.of(),
                fallback.getMission(),
                fallback.getPlannerThought(),
                taskMaps, "");
    }

    private ReActDecision fallbackExecuteDecision(ConversationState state, int step) {
        // Track which tools have been successfully executed
        Set<String> successfulToolNames = state.getExecutionResults().stream()
                .filter(TaskExecutionResult::isSuccess)
                .map(TaskExecutionResult::getToolName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Find the first unfinished tool in the task book
        for (TaskBook taskBook : state.getTaskBooks()) {
            for (TaskItem task : taskBook.getTasks()) {
                if (!successfulToolNames.contains(task.getToolName()) && toolRegistry.hasTool(task.getToolName())) {
                    // Skip tools that have failed too many times
                    long failCount = state.getExecutionResults().stream()
                            .filter(r -> task.getToolName().equals(r.getToolName()) && !r.isSuccess())
                            .count();
                    if (failCount >= 2) {
                        log.info("[fallback] Skipping tool {} after {} failures", task.getToolName(), failCount);
                        continue;
                    }
                    return new ReActDecision(
                            "[fallback] 按任务书执行下一个未完成工具：" + task.getToolName(),
                            "CALL_TOOL", task.getToolName(), task.getInput(),
                            "", "", List.of(), "");
                }
            }
        }

        // All tools done or skipped
        log.info("[fallback] All planned tools executed (successful: {}), generating final answer", successfulToolNames);
        return new ReActDecision(
                "[fallback] 所有计划内工具已执行完毕，生成最终旅游规划方案。",
                "FINISH", "", Map.of(), "", "", List.of(),
                generateFallbackSummary(state));
    }

    // ======== TaskBook 构建 ========

    private TaskBook buildTaskBookFromDecision(ReActDecision decision, ConversationState state) {
        if (decision.tasks() == null || decision.tasks().isEmpty()) {
            return buildFallbackTaskBook(state);
        }

        List<TaskItem> tasks = new ArrayList<>();
        for (int i = 0; i < decision.tasks().size(); i++) {
            Map<String, Object> rawTask = decision.tasks().get(i);
            String toolName = sanitizeToolName(String.valueOf(rawTask.getOrDefault("toolName", "")));
            if (!toolRegistry.hasTool(toolName)) {
                continue;
            }
            String taskId = String.valueOf(rawTask.getOrDefault("taskId", "t" + (i + 1)));
            int batchIndex = parseInt(rawTask.get("batchIndex"), i + 1);
            List<String> dependsOn = objectMapper.convertValue(
                    rawTask.getOrDefault("dependsOn", List.of()), new TypeReference<>() {});
            Map<String, Object> input = objectMapper.convertValue(
                    rawTask.getOrDefault("input", Map.of()), new TypeReference<>() {});
            String executionMode = toolRegistry.isParallelizable(toolName) ? "PARALLEL" : "SERIAL";

            tasks.add(new TaskItem(taskId,
                    String.valueOf(rawTask.getOrDefault("name", "未命名任务")),
                    String.valueOf(rawTask.getOrDefault("objective", "")),
                    toolName, "batch-" + batchIndex, dependsOn, input,
                    batchIndex, executionMode, "PLANNER"));
        }

        if (tasks.isEmpty()) {
            return buildFallbackTaskBook(state);
        }

        String taskScript = tasks.stream().map(TaskItem::getTaskId).collect(Collectors.joining("\n"));
        return new TaskBook(
                defaultIfBlank(decision.mission(), "生成供执行的工具执行计划"),
                defaultIfBlank(decision.plannerThought(),
                        "可完成性：当前信息可支撑规划；格式校验：tasks 结构完整；执行意图：按任务书逐项执行。"),
                taskScript,
                tasks);
    }

    private TaskBook buildFallbackTaskBook(ConversationState state) {
        TravelChatRequest request = state.getRequest();
        Map<String, Object> sharedInput = new LinkedHashMap<>();
        sharedInput.put("destination", valueOrDefault(request.getDestination()));
        sharedInput.put("budget", valueOrDefault(request.getBudget()));
        sharedInput.put("travelDays", valueOrDefault(request.getTravelDays()));
        sharedInput.put("preferences", valueOrDefault(request.getPreferences()));
        sharedInput.put("departure", valueOrDefault(request.getDeparture()));
        sharedInput.put("query", request.getMessage());

        List<TaskItem> tasks = new ArrayList<>();
        int nextId = 1;

        if (toolRegistry.hasTool("profile.lookup")) {
            tasks.add(new TaskItem("t" + nextId++, "用户画像分析", "提炼预算、天数和偏好",
                    "profile.lookup", "batch-1", List.of(), sharedInput, 1, "SERIAL", "PLANNER"));
        }

        List<String> factTools = List.of("weather.lookup", "map.poi.search", "budget.audit");
        for (String toolName : factTools) {
            if (toolRegistry.hasTool(toolName)) {
                String executionMode = toolRegistry.isParallelizable(toolName) ? "PARALLEL" : "SERIAL";
                tasks.add(new TaskItem("t" + nextId++,
                        defaultToolTaskName(toolName),
                        defaultToolObjective(toolName),
                        toolName, "batch-2", List.of(), sharedInput, 2, executionMode, "PLANNER"));
            }
        }

        if (tasks.isEmpty()) {
            tasks.add(new TaskItem("t1", "景点搜索", "搜索目的地景点",
                    "map.poi.search", "batch-1", List.of(), sharedInput, 1, "SERIAL", "PLANNER"));
        }

        String taskScript = tasks.stream().map(TaskItem::getTaskId).collect(Collectors.joining("\n"));
        return new TaskBook(
                "生成供执行的工具执行计划，收集事实后产出旅游规划方案",
                "可完成性：兜底计划覆盖基础事实；格式校验：tasks 结构完整；执行意图：按 batch 逐步执行。",
                taskScript,
                tasks);
    }

    // ======== 最终答案生成 ========

    private String generateFallbackSummary(ConversationState state) {
        String llmResult = llmFacade.complete("""
                你是旅游规划助手。请综合所有收集到的信息，为用户输出最终中文旅游规划。
                输出结构要求：
                1. 行程概览
                2. 每日安排建议
                3. 风险与备选方案
                4. 预算/交通提示
                5. 依据与说明

                输出要求：
                - 使用自然、专业、面向用户的中文 markdown
                - 不要提及系统内部实现细节
                - 若工具结果是启发式信息，明确写成建议
                - 如果某些事实仍不足，明确列出缺口
                """, buildFallbackSummaryPrompt(state));

        if (llmResult != null && !llmResult.isBlank()) {
            return llmResult;
        }

        // 最终硬编码兜底
        TravelChatRequest request = state.getRequest();
        String dest = valueOrDefault(request.getDestination());
        return """
                ### 行程概览
                围绕 %s 做区域化路线规划。

                ### 每日安排建议
                根据当前收集到的信息，建议优先安排核心景点，合理分配每日行程。

                ### 风险与备选方案
                - 雨天优先切换到室内景点
                - 旺季建议提前预订

                ### 依据说明
                以上建议基于有限的信息生成，仅供参考。
                """.formatted(dest);
    }

    private String buildFallbackSummaryPrompt(ConversationState state) {
        TravelChatRequest request = state.getRequest();
        StringBuilder sb = new StringBuilder();
        sb.append("用户需求：").append(request.getMessage()).append("\n");
        sb.append("目的地：").append(valueOrDefault(request.getDestination())).append("\n");
        sb.append("出发地：").append(valueOrDefault(request.getDeparture())).append("\n");
        sb.append("天数：").append(valueOrDefault(request.getTravelDays())).append("\n");
        sb.append("预算：").append(valueOrDefault(request.getBudget())).append("\n");
        sb.append("偏好：").append(valueOrDefault(request.getPreferences())).append("\n\n");

        sb.append("记忆上下文：\n").append(memoryContext(state)).append("\n\n");

        if (!state.getExecutionResults().isEmpty()) {
            sb.append("工具执行结果：\n");
            for (TaskExecutionResult result : state.getExecutionResults()) {
                sb.append("- ").append(result.getToolName())
                        .append(" (成功=").append(result.isSuccess()).append("): ")
                        .append(clip(result.getObservation(), 200)).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildExecutionLog(ConversationState state) {
        if (state.getExecutionResults().isEmpty()) {
            return "## 执行记录\n\n_暂无执行记录_\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 执行记录\n\n");
        for (TaskExecutionResult result : state.getExecutionResults()) {
            String status = result.isSuccess() ? "✅" : "❌";
            sb.append("- ").append(status).append(" Step ").append(result.getBatchIndex())
                    .append(": ").append(result.getToolName())
                    .append(" — ").append(clip(result.getObservation(), 100)).append("\n");
        }
        return sb.toString();
    }

    // ======== 工具结果判断 ========

    private boolean isToolResultUsable(String toolName, Map<String, Object> result) {
        if (result == null || result.containsKey("error")) {
            return false;
        }
        if (!toolRegistry.hasTool(toolName)) {
            return !result.isEmpty();
        }
        ToolTemplate template = toolRegistry.getToolTemplate(toolName);
        return toolResultInterpreter.isUsable(template, result);
    }

    private String buildObservationSummary(String toolName, Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return "无结果";
        }
        if (result.containsKey("error")) {
            return "执行失败: " + result.get("error");
        }
        if (!toolRegistry.hasTool(toolName)) {
            return clipJson(result);
        }
        ToolTemplate template = toolRegistry.getToolTemplate(toolName);
        return toolResultInterpreter.buildObservation(template, result);
    }

    // ======== 格式化辅助方法 ========

    private String formatToolNameOptions() {
        List<String> options = new ArrayList<>();
        options.add(TOOL_CATALOG);
        options.addAll(toolRegistry.listDiscoveredToolTemplates().stream()
                .filter(ToolTemplate::invocable)
                .map(ToolTemplate::name)
                .distinct()
                .sorted()
                .toList());
        return String.join(" | ", options);
    }

    private String formatToolCatalog() {
        String discovered = toolRegistry.listDiscoveredToolTemplates().stream()
                .map(tool -> "- %s [%s invocable=%s parallelizable=%s] %s".formatted(
                        tool.name(), tool.sourceType().code(), tool.invocable(), tool.parallelizable(), tool.description()))
                .collect(Collectors.joining("\n"));
        return """
                - tool.catalog [builtin invocable=true] 返回当前工具目录
                %s
                """.formatted(discovered.isBlank() ? "" : discovered);
    }

    private String formatTaskBookHistory(List<TaskBook> taskBooks) {
        return taskBooks.stream()
                .map(tb -> "- Mission=%s, tasks=%s".formatted(tb.getMission(),
                        tb.getTaskScript().isBlank() ? "无" : tb.getTaskScript().replace("\n", " | ")))
                .collect(Collectors.joining("\n"));
    }

    private String formatExecutionHistory(List<TaskExecutionResult> results) {
        return results.stream()
                .limit(15)
                .map(r -> "- step=%s tool=%s success=%s obs=%s".formatted(
                        r.getBatchIndex(), r.getToolName(), r.isSuccess(), clip(r.getObservation(), 120)))
                .collect(Collectors.joining("\n"));
    }

    private String memoryContext(ConversationState state) {
        if (state.getMemorySnapshot() == null || state.getMemorySnapshot().getPromptContext().isBlank()) {
            return "暂无";
        }
        return state.getMemorySnapshot().getPromptContext();
    }

    private Set<String> successfulTools(ConversationState state) {
        return state.getExecutionResults().stream()
                .filter(TaskExecutionResult::isSuccess)
                .map(TaskExecutionResult::getToolName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, Object> mapTaskToRaw(TaskItem task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", task.getTaskId());
        map.put("name", task.getName());
        map.put("objective", task.getObjective());
        map.put("toolName", task.getToolName());
        map.put("batchIndex", task.getBatchIndex());
        map.put("input", task.getInput());
        return map;
    }

    private String defaultToolTaskName(String toolName) {
        return switch (toolName) {
            case "weather.lookup" -> "天气规划检查";
            case "map.poi.search" -> "景点 POI 搜索";
            case "budget.audit" -> "预算审查";
            case "map.route.plan" -> "路线耗时估算";
            case "web.search" -> "实时网页搜索";
            case "rag.travel.knowledge" -> "旅游知识检索";
            default -> "补充事实";
        };
    }

    private String defaultToolObjective(String toolName) {
        return switch (toolName) {
            case "weather.lookup" -> "判断是否需要室内备选方案";
            case "map.poi.search" -> "产出可按区域聚合的景点列表";
            case "budget.audit" -> "生成预算友好型建议";
            case "map.route.plan" -> "估算出发地到目的地的交通距离和耗时";
            case "web.search" -> "检索近期开放状态、攻略和活动信息";
            case "rag.travel.knowledge" -> "检索旅游经验和路线启发";
            default -> "补充生成最终规划所需信息";
        };
    }

    // ======== 通用辅助方法 ========

    private void publishSafely(AgentEventPublisher publisher, ConversationState state,
                                AgentEventType eventType, Map<String, Object> payload) {
        try {
            publisher.publish(state, eventType, payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish SSE event", ex);
        }
    }

    private Map<String, Object> payload(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
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

    private String sanitizeJsonPayload(String raw) {
        String trimmed = raw.trim();
        // Remove markdown code fences
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*\\n?", "");
            trimmed = trimmed.replaceFirst("\\n?\\s*```\\s*$", "");
        }
        trimmed = trimmed.trim();
        // If doesn't start with {, try to find JSON block
        if (!trimmed.startsWith("{") && trimmed.contains("{")) {
            int braceStart = trimmed.indexOf('{');
            int braceEnd = trimmed.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                trimmed = trimmed.substring(braceStart, braceEnd + 1);
            }
        }
        return trimmed;
    }

    private String sanitizeToolName(String value) {
        return value == null ? "" : value.replace("`", "").trim();
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

    private int parseInt(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return Math.max(n.intValue(), 1);
        try {
            return Math.max(Integer.parseInt(String.valueOf(value).trim()), 1);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    // ======== 内部记录类 ========

    private record ReActDecision(String thought,
                                  String actionType,
                                  String toolName,
                                  Map<String, Object> input,
                                  String mission,
                                  String plannerThought,
                                  List<Map<String, Object>> tasks,
                                  String answer) {
    }
}

package com.voyu.agent.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyu.agent.model.agent.ConversationState;
import com.voyu.agent.model.agent.TaskBook;
import com.voyu.agent.model.agent.TaskExecutionResult;
import com.voyu.agent.service.llm.LlmFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class LoopReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(LoopReviewAgent.class);

    private final LlmFacade llmFacade;
    private final ObjectMapper objectMapper;

    public LoopReviewAgent(LlmFacade llmFacade, ObjectMapper objectMapper) {
        this.llmFacade = llmFacade;
        this.objectMapper = objectMapper;
    }

    public LoopDecision review(ConversationState state, TaskBook taskBook, List<TaskExecutionResult> roundResults) {
        String llmResult = llmFacade.complete("""
                你是旅游规划助手的 Loop Review Agent。
                你的职责是判断当前 round 的任务执行结果是否已经足够生成最终方案。
                你必须输出 JSON，不要输出 markdown。
                JSON 结构：
                {
                  "decision": "FINISH | CONTINUE",
                  "thought": "string",
                  "followUpFocus": "string"
                }
                规则：
                - 如果关键事实仍缺失、任务失败、或者任务结果明显不足以支撑最终方案，则返回 CONTINUE。
                - 如果 Planner 自主选择的工具已经返回足够事实，并且 memory.rag[] 中的知识没有明显误导，则返回 FINISH。
                - 不要因为没有调用某个固定工具就继续循环；所有业务工具都是可选工具。
                - thought 要简洁、可展示给前端。
                - followUpFocus 只在 CONTINUE 时给出下一轮的补充重点。
                """, buildPrompt(state, taskBook, roundResults));

        if (llmResult != null && !llmResult.isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(sanitizeJsonPayload(llmResult), new TypeReference<Map<String, Object>>() {
                });
                String decision = String.valueOf(parsed.getOrDefault("decision", "FINISH")).trim().toUpperCase();
                String thought = String.valueOf(parsed.getOrDefault("thought", "当前 round 已完成，可以进入最终汇总。")).trim();
                String focus = String.valueOf(parsed.getOrDefault("followUpFocus", "")).trim();
                if ("CONTINUE".equals(decision)) {
                    return new LoopDecision(true, thought, focus);
                }
                return new LoopDecision(false, thought, focus);
            } catch (Exception ex) {
                log.warn("Failed to parse loop review, falling back to deterministic review", ex);
            }
        }

        return fallbackReview(state, roundResults);
    }

    private String buildPrompt(ConversationState state, TaskBook taskBook, List<TaskExecutionResult> roundResults) {
        return """
                当前 round：%s
                用户需求：%s
                当前轮 focus：%s
                记忆上下文：%s

                当前任务书：
                Mission：%s
                Planner Thought：%s
                Task Script：
                %s
                Tasks：
                %s

                当前轮执行结果：
                %s

                历史累计工具：
                %s
                """.formatted(
                state.getCurrentRound(),
                state.getRequest().getMessage(),
                state.getLoopFocus().isBlank() ? "无" : state.getLoopFocus(),
                state.getMemorySnapshot() == null || state.getMemorySnapshot().getPromptContext().isBlank()
                        ? "暂无"
                        : state.getMemorySnapshot().getPromptContext(),
                taskBook.getMission(),
                taskBook.getPlannerThought(),
                taskBook.getTaskScript().isBlank() ? "无" : taskBook.getTaskScript(),
                taskBook.getTasks().stream()
                        .map(task -> "- %s %s -> %s [batch=%s mode=%s]".formatted(
                                task.getTaskId(),
                                task.getName(),
                                task.getToolName(),
                                task.getBatchIndex(),
                                task.getExecutionMode()))
                        .collect(Collectors.joining("\n")),
                roundResults.stream()
                        .map(result -> "- %s %s 成功=%s batch=%s attempts=%s".formatted(
                                result.getTaskId(),
                                result.getToolName(),
                                result.isSuccess(),
                                result.getBatchIndex(),
                                result.getAttempts()))
                        .collect(Collectors.joining("\n")),
                state.getExecutionResults().stream()
                        .map(TaskExecutionResult::getToolName)
                        .collect(Collectors.joining(", "))
        );
    }

    private LoopDecision fallbackReview(ConversationState state, List<TaskExecutionResult> roundResults) {
        boolean failedInRound = roundResults.stream().anyMatch(result -> !result.isSuccess());

        if (failedInRound) {
            String failedTools = roundResults.stream()
                    .filter(result -> !result.isSuccess())
                    .map(TaskExecutionResult::getToolName)
                    .distinct()
                    .collect(Collectors.joining("、"));
            String focus = failedTools.isBlank()
                    ? "重试失败任务，补齐关键事实。"
                    : "重试或替代这些失败工具：" + failedTools;
            return new LoopDecision(true, "当前 round 有任务未成功，继续下一轮补齐必要事实。", focus);
        }

        if (state.getExecutionResults().isEmpty()) {
            return new LoopDecision(true, "当前还没有可用于汇总的工具执行结果，继续补齐必要事实。", "选择必要工具补充事实。");
        }

        return new LoopDecision(false, "当前 round 的工具结果已足以进入最终汇总。", "");
    }

    private String sanitizeJsonPayload(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }

    public record LoopDecision(boolean continueLoop, String thought, String followUpFocus) {
    }
}

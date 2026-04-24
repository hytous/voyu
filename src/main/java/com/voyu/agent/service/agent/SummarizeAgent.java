package com.voyu.agent.service.agent;

import com.voyu.agent.model.agent.ConversationState;
import com.voyu.agent.model.agent.TaskBook;
import com.voyu.agent.model.agent.TaskExecutionResult;
import com.voyu.agent.model.api.TravelChatRequest;
import com.voyu.agent.service.llm.LlmFacade;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SummarizeAgent {

    private final LlmFacade llmFacade;

    public SummarizeAgent(LlmFacade llmFacade) {
        this.llmFacade = llmFacade;
    }

    public String summarize(ConversationState state) {
        String llmResult = llmFacade.complete("""
                你是旅游规划助手的 Summarize Agent。
                请综合多轮 Plan-Execute 结果，为用户输出最终中文旅游规划。
                输出结构要求：
                1. 行程概览
                2. 每日安排建议
                3. 风险与备选方案
                4. 预算/交通提示
                5. 依据与说明

                输出要求：
                - 使用自然、专业、面向用户的中文 markdown。
                - 可以用二级/三级标题、项目符号、表格。
                - 不要提及“对象引用”“Java 对象”“系统内部实现”“序列化”等技术细节。
                - 若工具结果是启发式、占位信息或非实时事实，要明确写成建议，不要伪装成实时事实。
                - 吸收多轮任务书和执行结果后再写，不要把 JSON 原样搬给用户。
                - 如果某些事实仍不足，要明确列出缺口和建议补充方式。
                """, buildSummaryPrompt(state));
        if (llmResult != null && !llmResult.isBlank()) {
            return llmResult;
        }

        return fallbackSummary(state.getRequest(), state.getExecutionResults());
    }

    private String buildSummaryPrompt(ConversationState state) {
        TravelChatRequest request = state.getRequest();
        return """
                用户需求概览：
                - 原始需求：%s
                - 目的地：%s
                - 出发地：%s
                - 行程时长：%s
                - 预算：%s
                - 偏好：%s

                记忆上下文：
                %s

                累计轮次：%s
                各轮 Planner Thought：
                %s

                各轮 Review Thought：
                %s

                历史任务书：
                %s

                历史工具执行结果：
                %s
                """.formatted(
                request.getMessage(),
                valueOrDefault(request.getDestination()),
                valueOrDefault(request.getDeparture()),
                valueOrDefault(request.getTravelDays()),
                valueOrDefault(request.getBudget()),
                valueOrDefault(request.getPreferences()),
                state.getMemorySnapshot() == null || state.getMemorySnapshot().getPromptContext().isBlank()
                        ? "暂无"
                        : state.getMemorySnapshot().getPromptContext(),
                state.getTaskBooks().size(),
                formatThoughts(state.getPlanThoughts()),
                formatThoughts(state.getReviewThoughts()),
                formatTaskBooks(state.getTaskBooks()),
                formatResults(state.getExecutionResults()));
    }

    private String fallbackSummary(TravelChatRequest request, List<TaskExecutionResult> results) {
        String destination = request.getDestination() == null || request.getDestination().isBlank()
                ? "目的地"
                : request.getDestination();

        StringBuilder builder = new StringBuilder();
        builder.append("### 行程概览\n");
        builder.append("围绕 ").append(destination).append(" 做区域化路线规划，先锁定核心景点，再用天气与预算建议修正每日节奏。\n\n");
        builder.append("### 每日安排建议\n");
        builder.append("第 1 天优先安排城市核心地标与历史文化点，熟悉交通和周边环境。\n");
        builder.append("第 2 天安排高热度景点和重点美食区，避免跨区反复折返。\n");
        builder.append("最后一天保留机动时间，可用于补漏、购物或室内备选点。\n\n");
        builder.append("### 工具执行依据\n");
        for (TaskExecutionResult result : results) {
            builder.append("- ").append(result.getTaskName())
                    .append("（").append(result.isSuccess() ? "成功" : "未完成").append("，第 ")
                    .append(result.getRound()).append(" 轮 / 第 ").append(result.getBatchIndex()).append(" 批 / ")
                    .append(result.getAttempts()).append(" 次尝试）：")
                    .append(result.getObservation()).append("\n");
        }
        builder.append("\n### 风险与备选方案\n");
        builder.append("- 雨天优先切换到博物馆、商场、观景台等室内点位。\n");
        builder.append("- 若预算敏感，优先同区域步行路线与地铁通票。\n");
        return builder.toString();
    }

    private String formatThoughts(List<String> thoughts) {
        if (thoughts == null || thoughts.isEmpty()) {
            return "- 无";
        }
        return thoughts.stream()
                .map(thought -> "- " + thought)
                .collect(Collectors.joining("\n"));
    }

    private String formatTaskBooks(List<TaskBook> taskBooks) {
        if (taskBooks == null || taskBooks.isEmpty()) {
            return "- 无任务书";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < taskBooks.size(); index++) {
            TaskBook taskBook = taskBooks.get(index);
            builder.append("Round ").append(index + 1).append("\n");
            builder.append(formatTaskBook(taskBook)).append("\n");
        }
        return builder.toString().trim();
    }

    private String formatTaskBook(TaskBook taskBook) {
        String tasks = taskBook.getTasks().stream()
                .map(task -> """
                        - %s %s
                          工具：%s
                          批次：%s
                          模式：%s
                          目标：%s
                        """.formatted(
                        task.getTaskId(),
                        task.getName(),
                        task.getToolName(),
                        task.getBatchIndex(),
                        task.getExecutionMode(),
                        task.getObjective()))
                .collect(Collectors.joining("\n"));

        return """
                Mission：%s
                Planner Thought：%s
                Task Script：
                %s
                Tasks：
                %s
                """.formatted(taskBook.getMission(), taskBook.getPlannerThought(), taskBook.getTaskScript(), tasks);
    }

    private String formatResults(List<TaskExecutionResult> results) {
        if (results.isEmpty()) {
            return "- 无工具执行结果";
        }
        return results.stream()
                .map(result -> """
                        - %s %s
                          工具：%s
                          是否成功：%s
                          round：%s
                          批次：%s
                          模式：%s
                          尝试次数：%s
                          执行思路：%s
                          观察结果：
                        %s
                        """.formatted(
                        result.getTaskId(),
                        result.getTaskName(),
                        result.getToolName(),
                        result.isSuccess() ? "是" : "否",
                        result.getRound(),
                        result.getBatchIndex(),
                        result.getExecutionMode(),
                        result.getAttempts(),
                        result.getThought(),
                        indent(result.getObservation(), "    ")))
                .collect(Collectors.joining("\n"));
    }

    private String indent(String value, String prefix) {
        return value.lines()
                .map(line -> prefix + line)
                .collect(Collectors.joining("\n"));
    }

    private String valueOrDefault(String value) {
        return value == null || value.isBlank() ? "未说明" : value;
    }
}

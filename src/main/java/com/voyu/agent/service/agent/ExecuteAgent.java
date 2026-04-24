package com.voyu.agent.service.agent;

import com.voyu.agent.model.agent.AgentEventType;
import com.voyu.agent.model.agent.ConversationMemorySnapshot;
import com.voyu.agent.model.agent.ConversationState;
import com.voyu.agent.model.agent.TaskBook;
import com.voyu.agent.model.agent.TaskExecutionResult;
import com.voyu.agent.model.agent.TaskItem;
import com.voyu.agent.tool.ToolCapabilityType;
import com.voyu.agent.tool.ToolInputNormalizer;
import com.voyu.agent.tool.ToolResultInterpreter;
import com.voyu.agent.tool.ToolRegistry;
import com.voyu.agent.tool.ToolTemplate;
import com.voyu.agent.tool.TravelTool;
import com.voyu.agent.util.AgentEventPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ExecuteAgent {

    private static final String EXECUTOR_FINISH = "executor.finish";

    private final ToolRegistry toolRegistry;
    private final ToolInputNormalizer toolInputNormalizer;
    private final ToolResultInterpreter toolResultInterpreter;
    private final Executor executor;
    private final int maxTaskAttempts;
    private final int maxParallelTools;

    public ExecuteAgent(ToolRegistry toolRegistry,
                        ToolInputNormalizer toolInputNormalizer,
                        ToolResultInterpreter toolResultInterpreter,
                        @Qualifier("agentExecutor") Executor executor,
                        @Value("${voyu.agent.max-task-attempts:3}") int maxTaskAttempts,
                        @Value("${voyu.agent.max-parallel-tools:5}") int maxParallelTools) {
        this.toolRegistry = toolRegistry;
        this.toolInputNormalizer = toolInputNormalizer;
        this.toolResultInterpreter = toolResultInterpreter;
        this.executor = executor;
        this.maxTaskAttempts = maxTaskAttempts;
        this.maxParallelTools = Math.max(maxParallelTools, 1);
    }

    public List<TaskExecutionResult> execute(TaskBook taskBook, ConversationState state, AgentEventPublisher publisher) {
        List<TaskExecutionResult> completed = new ArrayList<>();
        List<TaskBatch> batches = resolveBatches(taskBook);

        for (TaskBatch batch : batches) {
            boolean parallelBatch = "PARALLEL".equalsIgnoreCase(batch.executionMode());
            publishSafely(publisher, state, AgentEventType.THOUGHT, Map.of(
                    "phase", "EXECUTE",
                    "taskOrigin", "EXECUTOR",
                    "round", state.getCurrentRound(),
                    "batchIndex", batch.batchIndex(),
                    "executionMode", batch.executionMode(),
                    "message", describeBatchStart(batch)
            ));

            if (!parallelBatch) {
                for (TaskItem task : batch.tasks()) {
                    completed.add(runTaskWithRetries(task, state, publisher, batch.batchIndex(), "SERIAL"));
                }
                continue;
            }

            List<CompletableFuture<TaskExecutionResult>> futures = batch.tasks().stream()
                    .map(task -> CompletableFuture.supplyAsync(
                            () -> runTaskWithRetries(task, state, publisher, batch.batchIndex(), batch.executionMode()),
                            executor))
                    .toList();
            completed.addAll(futures.stream().map(CompletableFuture::join).toList());
        }

        return completed;
    }

    private List<TaskBatch> resolveBatches(TaskBook taskBook) {
        List<TaskBatch> batches = new ArrayList<>();
        Map<Integer, List<TaskItem>> grouped = new LinkedHashMap<>();
        for (TaskItem task : taskBook.getTasks()) {
            grouped.computeIfAbsent(Math.max(task.getBatchIndex(), 1), ignored -> new ArrayList<>()).add(task);
        }

        grouped.forEach((batchIndex, tasks) -> splitBatch(batchIndex, tasks, batches));
        return batches;
    }

    private TaskExecutionResult runTaskWithRetries(TaskItem task,
                                                   ConversationState state,
                                                   AgentEventPublisher publisher,
                                                   int batchIndex,
                                                   String executionMode) {
        if (!toolRegistry.hasTool(task.getToolName())) {
            publishTaskStatus(publisher, state, task, batchIndex, executionMode, 1, "FAILED");
            publishSafely(publisher, state, AgentEventType.WARNING, Map.of(
                    "phase", "EXECUTE",
                    "taskOrigin", "EXECUTOR",
                    "round", state.getCurrentRound(),
                    "batchIndex", batchIndex,
                    "attempt", 1,
                    "taskId", task.getTaskId(),
                    "taskName", task.getName(),
                    "message", "任务请求了未注册工具: " + task.getToolName()
            ));
            return new TaskExecutionResult(
                    task.getTaskId(),
                    task.getName(),
                    task.getToolName(),
                    false,
                    "工具目录校验失败。",
                    "未找到对应本地工具。",
                    state.getCurrentRound(),
                    batchIndex,
                    executionMode,
                    1);
        }

        TravelTool tool = toolRegistry.get(task.getToolName());
        ToolTemplate template = tool.template();
        Map<String, Object> baseInput = toolInputNormalizer.normalize(task, state);
        Map<String, Object> currentInput = new LinkedHashMap<>(baseInput);
        String lastThought = "";
        String lastObservation = "";

        for (int attempt = 1; attempt <= maxTaskAttempts; attempt++) {
            publishTaskStatus(publisher, state, task, batchIndex, executionMode, attempt, "RUNNING");

            lastThought = "执行器在第 %s 批的第 %s 次尝试中调用 %s 完成任务 [%s]，随后立即评估结果是否足够。"
                    .formatted(batchIndex, attempt, tool.name(), task.getName());
            publishSafely(publisher, state, AgentEventType.THOUGHT, Map.of(
                    "phase", "EXECUTE",
                    "taskOrigin", "EXECUTOR",
                    "round", state.getCurrentRound(),
                    "batchIndex", batchIndex,
                    "attempt", attempt,
                    "executionMode", executionMode,
                    "taskId", task.getTaskId(),
                    "taskName", task.getName(),
                    "step", attempt,
                    "message", lastThought
            ));

            publishSafely(publisher, state, AgentEventType.TOOL_CALL, payload(
                    "phase", "EXECUTE",
                    "taskOrigin", "EXECUTOR",
                    "round", state.getCurrentRound(),
                    "batchIndex", batchIndex,
                    "attempt", attempt,
                    "executionMode", executionMode,
                    "taskId", task.getTaskId(),
                    "taskName", task.getName(),
                    "toolName", tool.name(),
                    "arguments", currentInput,
                    "input", currentInput
            ));

            Map<String, Object> rawResult;
            try {
                rawResult = tool.execute(currentInput);
            } catch (Exception ex) {
                rawResult = Map.of("error", String.valueOf(ex.getMessage()));
            }

            if ("memory.rag.clear".equals(tool.name()) && !rawResult.containsKey("error")) {
                state.setMemorySnapshot(clearedRagSnapshot(state.getMemorySnapshot()));
            }

            publishSafely(publisher, state, AgentEventType.TOOL_RESULT, Map.of(
                    "phase", "EXECUTE",
                    "taskOrigin", "EXECUTOR",
                    "round", state.getCurrentRound(),
                    "batchIndex", batchIndex,
                    "attempt", attempt,
                    "executionMode", executionMode,
                    "taskId", task.getTaskId(),
                    "taskName", task.getName(),
                    "toolName", tool.name(),
                    "result", rawResult
            ));

            lastObservation = toolResultInterpreter.buildObservation(template, rawResult);
            boolean usable = toolResultInterpreter.isUsable(template, rawResult);
            publishSafely(publisher, state, AgentEventType.THOUGHT, Map.of(
                    "phase", "EXECUTE",
                    "taskOrigin", "EXECUTOR",
                    "round", state.getCurrentRound(),
                    "batchIndex", batchIndex,
                    "attempt", attempt,
                    "executionMode", executionMode,
                    "taskId", task.getTaskId(),
                    "taskName", task.getName(),
                    "message", usable
                            ? "当前任务结果满足目标，结束该任务循环。"
                            : "当前任务结果仍不充分，需要重新组织输入后继续尝试。"
            ));

            if (usable) {
                publishSafely(publisher, state, AgentEventType.TOOL_CALL, payload(
                        "phase", "EXECUTE",
                        "taskOrigin", "EXECUTOR",
                        "round", state.getCurrentRound(),
                        "batchIndex", batchIndex,
                        "attempt", attempt,
                        "executionMode", executionMode,
                        "taskId", task.getTaskId(),
                        "taskName", task.getName(),
                        "toolName", EXECUTOR_FINISH,
                        "arguments", Map.of(
                                "taskId", task.getTaskId(),
                                "attempt", attempt,
                                "reason", "结果已可用于最终汇总"
                        ),
                        "input", Map.of(
                                "taskId", task.getTaskId(),
                                "attempt", attempt,
                                "reason", "结果已可用于最终汇总"
                        )
                ));
                publishTaskStatus(publisher, state, task, batchIndex, executionMode, attempt, "DONE");
                return new TaskExecutionResult(
                        task.getTaskId(),
                        task.getName(),
                        tool.name(),
                        true,
                        lastThought,
                        lastObservation,
                        state.getCurrentRound(),
                        batchIndex,
                        executionMode,
                        attempt);
            }

            if (attempt < maxTaskAttempts) {
                currentInput = refineInput(task, template, currentInput, rawResult, attempt);
                publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                        "phase", "EXECUTE",
                        "taskOrigin", "EXECUTOR",
                        "round", state.getCurrentRound(),
                        "batchIndex", batchIndex,
                        "attempt", attempt,
                        "nextAttempt", attempt + 1,
                        "executionMode", executionMode,
                        "taskId", task.getTaskId(),
                        "taskName", task.getName(),
                        "toolName", tool.name(),
                        "status", "RETRYING"
                ));
            }
        }

        publishTaskStatus(publisher, state, task, batchIndex, executionMode, maxTaskAttempts, "FAILED");
        return new TaskExecutionResult(
                task.getTaskId(),
                task.getName(),
                tool.name(),
                false,
                lastThought,
                lastObservation,
                state.getCurrentRound(),
                batchIndex,
                executionMode,
                maxTaskAttempts);
    }

    private void publishTaskStatus(AgentEventPublisher publisher,
                                   ConversationState state,
                                   TaskItem task,
                                   int batchIndex,
                                   String executionMode,
                                   int attempt,
                                   String status) {
        publishSafely(publisher, state, AgentEventType.TASK_STATUS, payload(
                "phase", "EXECUTE",
                "taskOrigin", "EXECUTOR",
                "round", state.getCurrentRound(),
                "batchIndex", batchIndex,
                "attempt", attempt,
                "executionMode", executionMode,
                "taskId", task.getTaskId(),
                "taskName", task.getName(),
                "toolName", task.getToolName(),
                "status", status
        ));
    }

    private Map<String, Object> refineInput(TaskItem task,
                                            ToolTemplate template,
                                            Map<String, Object> currentInput,
                                            Map<String, Object> rawResult,
                                            int attempt) {
        Map<String, Object> nextInput = new LinkedHashMap<>(currentInput);
        nextInput.put("reactRetry", attempt);

        ToolCapabilityType capabilityType = template.capabilityType();
        if (capabilityType == ToolCapabilityType.WEATHER_LOOKUP) {
            nextInput.putIfAbsent("dateRange", "近期");
        }
        if (capabilityType == ToolCapabilityType.POI_SEARCH) {
            nextInput.putIfAbsent("keywords", task.getObjective());
            nextInput.putIfAbsent("query", task.getObjective());
        }
        if (capabilityType == ToolCapabilityType.WEB_SEARCH) {
            String fallbackQuery = Stream.of(
                            value(nextInput.get("destination")),
                            value(nextInput.get("preferences")),
                            task.getObjective())
                    .filter(item -> !item.isBlank())
                    .collect(Collectors.joining(" "));
            if (!fallbackQuery.isBlank()) {
                nextInput.put("query", fallbackQuery);
            }
        }
        if (capabilityType == ToolCapabilityType.RAG_RETRIEVAL) {
            Object hits = rawResult.get("hits");
            if (!(hits instanceof List<?> list) || list.isEmpty()) {
                String fallbackQuery = Stream.of(
                                value(nextInput.get("destination")),
                                value(nextInput.get("preferences")),
                                task.getObjective())
                        .filter(item -> !item.isBlank())
                        .collect(Collectors.joining(" "));
                if (!fallbackQuery.isBlank()) {
                    nextInput.put("query", fallbackQuery);
                }
            }
        }
        return nextInput;
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

    private void splitBatch(int batchIndex, List<TaskItem> tasks, List<TaskBatch> result) {
        List<TaskItem> parallelWindow = new ArrayList<>();
        for (TaskItem task : tasks) {
            if (isParallelizable(task)) {
                parallelWindow.add(task);
                if (parallelWindow.size() >= maxParallelTools) {
                    flushParallelWindow(batchIndex, parallelWindow, result);
                }
                continue;
            }
            flushParallelWindow(batchIndex, parallelWindow, result);
            result.add(new TaskBatch(batchIndex, "SERIAL", List.of(task)));
        }
        flushParallelWindow(batchIndex, parallelWindow, result);
    }

    private void flushParallelWindow(int batchIndex, List<TaskItem> parallelWindow, List<TaskBatch> result) {
        if (parallelWindow.isEmpty()) {
            return;
        }
        if (parallelWindow.size() == 1) {
            result.add(new TaskBatch(batchIndex, "SERIAL", List.of(parallelWindow.get(0))));
        } else {
            result.add(new TaskBatch(batchIndex, "PARALLEL", List.copyOf(parallelWindow)));
        }
        parallelWindow.clear();
    }

    private boolean isParallelizable(TaskItem task) {
        return toolRegistry.hasTool(task.getToolName()) && toolRegistry.isParallelizable(task.getToolName());
    }

    private String describeBatchStart(TaskBatch batch) {
        if ("PARALLEL".equalsIgnoreCase(batch.executionMode())) {
            return "执行器开始并行处理第 %s 批任务，当前并行窗口共 %s 个任务（上限 %s）。"
                    .formatted(batch.batchIndex(), batch.tasks().size(), maxParallelTools);
        }
        if (batch.tasks().size() == 1 && isParallelizable(batch.tasks().get(0))) {
            return "第 %s 批当前窗口只有 1 个可并行工具，执行器按单任务执行。".formatted(batch.batchIndex());
        }
        return "执行器开始串行处理第 %s 批任务。".formatted(batch.batchIndex());
    }

    private String value(Object value) {
        if (value == null) {
            return "未提供";
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::value).collect(Collectors.joining("、"));
        }
        return String.valueOf(value);
    }

    private String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim() + "...";
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

    private Map<String, Object> payload(Object... entries) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            payload.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return payload;
    }

    private record TaskBatch(int batchIndex, String executionMode, List<TaskItem> tasks) {
    }
}

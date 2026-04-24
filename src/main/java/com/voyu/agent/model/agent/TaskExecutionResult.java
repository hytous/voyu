package com.voyu.agent.model.agent;

public class TaskExecutionResult {
    private final String taskId;
    private final String taskName;
    private final String toolName;
    private final boolean success;
    private final String thought;
    private final String observation;
    private final int round;
    private final int batchIndex;
    private final String executionMode;
    private final int attempts;

    public TaskExecutionResult(String taskId, String taskName, String toolName, boolean success, String thought, String observation) {
        this(taskId, taskName, toolName, success, thought, observation, 0, 1, "SERIAL", 1);
    }

    public TaskExecutionResult(String taskId,
                               String taskName,
                               String toolName,
                               boolean success,
                               String thought,
                               String observation,
                               int round,
                               int batchIndex,
                               String executionMode,
                               int attempts) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.toolName = toolName;
        this.success = success;
        this.thought = thought;
        this.observation = observation;
        this.round = round;
        this.batchIndex = Math.max(batchIndex, 1);
        this.executionMode = executionMode == null || executionMode.isBlank() ? "SERIAL" : executionMode;
        this.attempts = Math.max(attempts, 1);
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getToolName() {
        return toolName;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getThought() {
        return thought;
    }

    public String getObservation() {
        return observation;
    }

    public int getRound() {
        return round;
    }

    public int getBatchIndex() {
        return batchIndex;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public int getAttempts() {
        return attempts;
    }
}

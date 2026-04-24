package com.voyu.agent.model.agent;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public class TaskItem {
    private String taskId;
    private String name;
    private String objective;
    private String toolName;
    private String parallelGroup;
    private List<String> dependsOn;
    private Map<String, Object> input;
    private int batchIndex;
    private String executionMode;
    private String sourcePhase;

    public TaskItem() {
        this(null, null, null, null, "", List.of(), Map.of(), 1, "SERIAL", "PLANNER");
    }

    public TaskItem(String taskId,
                    String name,
                    String objective,
                    String toolName,
                    String parallelGroup,
                    List<String> dependsOn,
                    Map<String, Object> input) {
        this(taskId, name, objective, toolName, parallelGroup, dependsOn, input, 1, "SERIAL", "PLANNER");
    }

    public TaskItem(String taskId,
                    String name,
                    String objective,
                    String toolName,
                    String parallelGroup,
                    List<String> dependsOn,
                    Map<String, Object> input,
                    int batchIndex,
                    String executionMode,
                    String sourcePhase) {
        this.taskId = taskId;
        this.name = name;
        this.objective = objective;
        this.toolName = toolName;
        this.parallelGroup = parallelGroup == null ? "" : parallelGroup;
        this.dependsOn = dependsOn == null ? List.of() : new ArrayList<>(dependsOn);
        this.input = input == null ? Map.of() : new LinkedHashMap<>(input);
        this.batchIndex = Math.max(batchIndex, 1);
        this.executionMode = executionMode == null || executionMode.isBlank() ? "SERIAL" : executionMode;
        this.sourcePhase = sourcePhase == null || sourcePhase.isBlank() ? "PLANNER" : sourcePhase;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getObjective() {
        return objective;
    }

    public void setObjective(String objective) {
        this.objective = objective;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getParallelGroup() {
        return parallelGroup;
    }

    public void setParallelGroup(String parallelGroup) {
        this.parallelGroup = parallelGroup == null ? "" : parallelGroup;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn == null ? List.of() : new ArrayList<>(dependsOn);
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input == null ? Map.of() : new LinkedHashMap<>(input);
    }

    public int getBatchIndex() {
        return batchIndex;
    }

    public void setBatchIndex(int batchIndex) {
        this.batchIndex = Math.max(batchIndex, 1);
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode == null || executionMode.isBlank() ? "SERIAL" : executionMode;
    }

    public String getSourcePhase() {
        return sourcePhase;
    }

    public void setSourcePhase(String sourcePhase) {
        this.sourcePhase = sourcePhase == null || sourcePhase.isBlank() ? "PLANNER" : sourcePhase;
    }
}

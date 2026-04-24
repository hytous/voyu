package com.voyu.agent.model.agent;

import java.util.List;

public class TaskBook {
    private String mission;
    private String plannerThought;
    private String taskScript;
    private List<TaskItem> tasks;

    public TaskBook() {
        this("", "", "", List.of());
    }

    public TaskBook(String mission, String plannerThought, List<TaskItem> tasks) {
        this(mission, plannerThought, "", tasks);
    }

    public TaskBook(String mission, String plannerThought, String taskScript, List<TaskItem> tasks) {
        this.mission = mission;
        this.plannerThought = plannerThought;
        this.taskScript = taskScript == null ? "" : taskScript;
        this.tasks = tasks;
    }

    public String getMission() {
        return mission;
    }

    public void setMission(String mission) {
        this.mission = mission;
    }

    public String getPlannerThought() {
        return plannerThought;
    }

    public void setPlannerThought(String plannerThought) {
        this.plannerThought = plannerThought;
    }

    public String getTaskScript() {
        return taskScript;
    }

    public void setTaskScript(String taskScript) {
        this.taskScript = taskScript == null ? "" : taskScript;
    }

    public List<TaskItem> getTasks() {
        return tasks;
    }

    public void setTasks(List<TaskItem> tasks) {
        this.tasks = tasks;
    }
}

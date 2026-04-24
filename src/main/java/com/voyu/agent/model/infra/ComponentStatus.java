package com.voyu.agent.model.infra;

import java.util.Map;

public class ComponentStatus {

    private final String name;
    private final boolean up;
    private final String detail;
    private final Map<String, Object> metadata;

    public ComponentStatus(String name, boolean up, String detail, Map<String, Object> metadata) {
        this.name = name;
        this.up = up;
        this.detail = detail;
        this.metadata = metadata;
    }

    public String getName() {
        return name;
    }

    public boolean isUp() {
        return up;
    }

    public String getDetail() {
        return detail;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}

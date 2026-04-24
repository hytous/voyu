package com.voyu.agent.tool;

import java.util.Map;

public interface TravelTool {

    ToolTemplate template();

    default String name() {
        return template().name();
    }

    default String description() {
        return template().description();
    }

    default ToolCapabilityType capabilityType() {
        return template().capabilityType();
    }

    default boolean isParallelizable() {
        return template().parallelizable();
    }

    Map<String, Object> execute(Map<String, Object> input);
}

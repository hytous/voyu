package com.voyu.agent.tool;

public enum ToolSourceType {
    LOCAL("local"),
    MCP("mcp"),
    BUILTIN("builtin");

    private final String code;

    ToolSourceType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}

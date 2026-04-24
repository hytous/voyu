package com.voyu.agent.model.agent;

/**
 * Agent 模式枚举，参考 Claude Code 的 plan mode 设计。
 * <p>
 * 整个 ReAct 循环共用一个 while 循环，通过 AgentMode 状态区分当前处于何种工作模式：
 * <ul>
 *     <li>PLAN  — 信息收集 + 任务规划，产出 plan.md</li>
 *     <li>EXECUTE — 按计划执行工具调用，产出最终答案</li>
 * </ul>
 */
public enum AgentMode {

    /**
     * 规划模式：只做信息收集和计划制定，允许只读工具调用，产出 plan.md 文件。
     */
    PLAN,

    /**
     * 执行模式（普通模式）：按计划执行工具、产出最终答案。
     */
    EXECUTE
}

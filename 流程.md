    flowchart TD
        A[用户输入] --> B[TravelAgentServiceImpl.streamPlan]
        B --> C[加载会话记忆]
        C --> D{ClarificationAgent\n信息是否足够?}
        D -- 缺少关键信息 --> E[返回追问\nFINAL_ANSWER:QUESTION]
        D -- 信息足够 --> F[RAG 增强\nensureSessionRag]
        F --> G[UnifiedReActAgent.run\n★ 核心单循环]
    
        subgraph LOOP ["while (reactStep < 20)"]
            G --> H{当前 AgentMode?}
    
            subgraph PLAN_MODE ["AgentMode = PLAN"]
                H -- PLAN --> P1[LLM 调用\nbuildPlanSystemPrompt]
                P1 --> P2{LLM 决策}
                P2 -- CALL_TOOL --> P3[调用工具\n收集事实]
                P3 --> P1
                P2 -- FINISH_PLAN --> P4[生成 plan.md\nPlanFileService]
                P4 --> P5[切换 AgentMode\n→ EXECUTE\nsetJustExitedPlan=true]
                P5 --> P6[发布 MODE_SWITCH 事件]
            end
    
            subgraph EXEC_MODE ["AgentMode = EXECUTE"]
                P6 --> E1{justExitedPlan?}
                E1 -- true --> E2[注入任务书上下文\n读取 plan.md 内容]
                E2 --> E3[setJustExitedPlan=false]
                E1 -- false --> E4
                E3 --> E4
    
                E4 -- "step % 5 == 0" --> E5[发布 STEP_REMINDER\n进度提醒事件]
                E5 --> E6
                E4 -- 其他步数 --> E6[LLM 调用\nbuildExecuteSystemPrompt]
                E6 --> E7{LLM 决策}
                E7 -- CALL_TOOL --> E8[调用工具\n执行任务]
                E8 --> E6
                E7 -- FINISH --> E9[生成最终答案\ngenerateFallbackSummary]
            end
        end
    
        E9 --> Z[保存记忆\npersistCompressedMemory]
        Z --> ZZ[发布 FINAL_ANSWER 事件]
        ZZ --> END[SSE 流结束]
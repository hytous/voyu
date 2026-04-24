# Voyu 待进化清单

当前版本目标是先把你的核心 Agent 思路快速跑通，所以这里明确列出后续再补的点，避免和当前实现范围混淆。

## 1. 检索与知识库

- 将本地启发式知识检索替换成 `Milvus + Elasticsearch` 双路召回。
- 增加 query rewrite、merge、dedup、rerank 处理链。
- 引入文档元数据，如 `city`、`country`、`poi`、`source`、`updatedAt`。
- 将当前本地 markdown 知识源替换为真实旅游资料导入管线。

## 2. 工具体系

- 将当前本地 mock 工具替换为真实天气、地图、POI、交通、酒店 API。
- 接入 MCP client，实现本地工具与 MCP 工具统一注册。
- 增加工具超时、熔断、重试和幂等控制。
- 增加工具成本与优先级路由策略。

## 3. Agent Runtime

- 将当前单轮 `Plan -> Execute -> Summarize` 扩展成真正的多轮外循环。
- 为 `Plan` 和 `Execute` 内部补齐严格的 ReAct step 记录。
- 支持任务依赖图而不仅是简单 `g0/g1` 分组。
- 加入防无限循环、任务预算、失败回退策略。

## 4. 流式与前端协议

- 细化 SSE 事件协议，区分 `THOUGHT_CHUNK`、`SLAVE_START`、`SLAVE_END`、`RAG_HIT` 等更细粒度事件。
- 接入前端页面，展示任务书、并发状态、工具结果卡片和最终路线。
- 增加会话中断、重试、取消执行能力。

## 5. 记忆与持久化

- 将当前纯内存会话态升级为 Kafka 事件流。
- 异步消费并落库 MongoDB。
- 提炼长期偏好记忆和用户画像，而不是只保存原始消息。
- 做上下文压缩和历史摘要回灌。

## 6. 可观测性

- 接入 Langfuse 或等价 tracing 系统。
- 记录 prompt、plan、task、tool、final answer 全链路轨迹。
- 统计任务耗时、工具成功率、RAG 命中率、用户修正率。

## 7. 工程化

- 加测试：planner、executor、controller、tool registry。
- 增加配置分环境管理。
- 增加 Dockerfile 和部署脚本。
- 增加异常分层和统一错误码。

## 8. 当前版本边界

当前版本已经实现：

- Spring Boot 后端工程
- SSE 流式输出
- Plan-Execute 主链路
- 结构化 TaskBook
- Master 并发执行任务
- 本地工具注册与调用
- 本地知识库占位检索
- 最终规划汇总

当前版本暂不承诺：

- 真实天气/地图/酒店实时数据准确性
- 生产级记忆持久化
- 双路 RAG 检索效果
- 完整前端交互页面

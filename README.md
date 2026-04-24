# Voyu

Voyu 是一个按 `Plan-Execute + ReAct 风格执行 + SseEmitter 流式输出 + CompletableFuture 并发任务执行` 快速落地的旅游规划助手后端原型。

## 当前实现范围

- Spring Boot + Spring AI 项目骨架
- `POST /api/travel-agent/stream` SSE 流式接口
- Plan Agent 负责生成结构化 `TaskBook`
- Execute Agent 负责按任务书执行，支持 `g0` 串行和其他分组并发
- 本地工具注册表
- `Elasticsearch + Milvus + SiliconFlow Reranker + 本地 fallback` 的混合 RAG
- Final Summarize 输出最终行程建议

## 启动

直接启动：

```bash
mvn spring-boot:run
```

默认不要求 API Key，也能以本地 fallback 模式运行。

当前默认已经切到 Kimi 2.5：

- `voyu.llm.base-url=https://api.moonshot.cn/v1`
- `voyu.llm.model=kimi-k2.5`

当前项目配置文件里已经写入 Kimi API 配置，可直接启动。

如果后续要改成环境变量覆盖，也可以设置：

```bash
set MOONSHOT_API_KEY=your_api_key
```

也可以自行覆盖：

- `voyu.llm.api-key`
- `voyu.llm.base-url`
- `voyu.llm.model`

## 当前内置工具

- `profile.lookup`
- `weather.lookup`
- `map.poi.search`
- `rag.travel.knowledge`
- `budget.audit`

## 请求示例

```bash
curl -N -X POST "http://localhost:8090/api/travel-agent/stream" ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"我想五一去东京玩4天，预算7000，喜欢动漫和美食\",\"destination\":\"东京\",\"travelDays\":\"4天\",\"budget\":\"7000\",\"preferences\":\"动漫,美食\"}"
```

## 当前待进化点

- 将 Wikivoyage 等外部旅行知识自动抓取并持续清洗
- 将本地工具升级成真实 MCP / API 工具
- 将 Kafka 历史事件消费链路拆到独立后台进程
- 引入可观测性、Prompt 版本管理和失败重试策略
- 将当前启发式预算/天气工具替换为真实实时数据

详细清单见 [EVOLUTION_TODO.md](E:\J\Job\AI\voyu\EVOLUTION_TODO.md)。

## 中间件

Voyu 的本地中间件编排已经放在 [infra](E:\J\Job\AI\voyu\infra\README.md)：

- MongoDB
- Kafka
- Elasticsearch
- Milvus

数据目录默认全部落在 `E:\J\Job\AI\voyu\infra\data`。

应用内已经补充了中间件连接配置和基础状态接口：

- MongoDB: `spring.data.mongodb.uri=mongodb://localhost:27017/voyu`
- Kafka: `spring.kafka.bootstrap-servers=localhost:9094`
- Elasticsearch: `voyu.middleware.elasticsearch.url=http://localhost:9200`
- Milvus: `voyu.middleware.milvus.host=localhost`, `voyu.middleware.milvus.grpc-port=19530`

状态检查接口：

```bash
GET http://localhost:8090/api/infrastructure/status
```

会话历史查询接口：

```bash
GET http://localhost:8090/api/travel-agent/sessions/{sessionId}
```

当前 SSE 事件会异步写入 MongoDB 集合 `travel_conversations`，同时投递到 Kafka Topic `voyu-conversation-history`。

当前知识检索链路会在应用启动时把 `knowledge/**/*.md` 下的知识文件同步到：

- Elasticsearch 索引 `voyu_travel_knowledge`
- Milvus 集合 `voyu_travel_knowledge`

当前默认已经接入：

- SiliconFlow Embedding: `Qwen/Qwen3-Embedding-8B`
- SiliconFlow Reranker: `Qwen/Qwen3-Reranker-8B`

Wikivoyage 抓取脚本位于 [fetch-wikivoyage.ps1](E:\J\Job\AI\voyu\scripts\fetch-wikivoyage.ps1)，生成的知识文件默认落到 `src/main/resources/knowledge/wikivoyage`。

`rag.travel.knowledge` 会对 `Elasticsearch + Milvus + 本地 fallback` 三路结果做融合，再交给 SiliconFlow reranker 精排。

# Voyu Middleware

这套中间件部署面向 Voyu 当前与后续规划的依赖：

- `MongoDB`：会话历史、用户画像、压缩记忆
- `Kafka`：历史事件流、异步入库、分析事件
- `Elasticsearch`：关键词检索、倒排索引
- `Milvus`：向量检索

所有宿主机数据目录都放在当前工程下的 `E:\J\Job\AI\voyu\infra\data`。

## 目录

- `docker-compose.middleware.yml`：中间件编排文件
- `start-middleware.ps1`：初始化目录并启动
- `stop-middleware.ps1`：停止中间件

## 端口

- MongoDB: `27017`
- Elasticsearch: `9200`
- Kafka internal: `9092`
- Kafka external: `9094`
- MinIO API: `9000`
- MinIO Console: `9001`
- Milvus gRPC: `19530`
- Milvus health: `9091`

## 启动

```powershell
powershell -ExecutionPolicy Bypass -File .\infra\start-middleware.ps1
```

## 停止

```powershell
powershell -ExecutionPolicy Bypass -File .\infra\stop-middleware.ps1
```

## 说明

- Elasticsearch 以单节点模式启动，关闭安全认证，适合本地开发。
- Kafka 使用 KRaft 单节点模式，不依赖 ZooKeeper。
- Milvus 使用 standalone，依赖同编排内的 `etcd` 和 `minio`。
- 若本机资源紧张，可先只启动 `mongodb`、`kafka`、`elasticsearch`，后续再补 `milvus`。

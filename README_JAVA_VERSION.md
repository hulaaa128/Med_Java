# EchoMind Java 版本说明

EchoMind Java 是 Python 版 EchoMind 的 Java/Spring 技术栈重构版，目录位于：

```text
/Users/xiao_xiong/Desktop/code/EchoMindJava
```

当前版本已经覆盖智能客服主链路：对话请求、Redis 工作记忆、知识库检索、多 Agent 路由、Spring AI 模型调用、回答校验、评测、监控和 Docker 部署。

## 技术栈

| 类型 | 技术 |
|------|------|
| 语言 | Java 21 |
| Web 框架 | Spring Boot 3.5 |
| AI 框架 | Spring AI 1.1 |
| LLM Provider | Anthropic、DeepSeek |
| 文档处理 | LangChain4j DocumentSplitter |
| 记忆缓存 | Spring Data Redis |
| RAG | BM25 + 本地 hash vector + LLM rerank |
| 持久化 | Redis 工作记忆 + JSON 知识库/长期记忆/用户画像 |
| 监控 | Spring Boot Actuator、Micrometer、Prometheus |
| API 文档 | Springdoc OpenAPI、Swagger UI |
| 部署 | Docker、Docker Compose、Nginx、Prometheus |
| 构建 | Maven Wrapper |

## 核心链路

```text
POST /chat
  -> MemoryManager 读取 Redis 工作记忆、会话摘要、长期记忆、用户画像
  -> KnowledgeToolManager 做查询改写、并行召回、LLM rerank
  -> AgentOrchestrator 做意图识别和 Agent 路由
  -> General / Technical / Billing Agent 生成回复
  -> AnswerVerifier 校验回复是否可信、是否需要转人工
  -> 写入 Redis，并异步更新用户画像
```

## 和 Python 版的主要不同

Python 版使用 FastAPI、Anthropic Async SDK、ChromaDB 和自定义 MCPToolManager。

Java 版使用 Spring Boot、Spring AI、LangChain4j、Spring Data Redis、Micrometer，并额外支持 DeepSeek profile、Answer Verifier 和 Hybrid RAG。

当前 Java 版已经补齐：

- `/chat`、`/search`、`/knowledge/add`、`/knowledge/upload`、`/knowledge/stats`
- `/monitor`、`/metrics`、`/eval/run`
- Swagger UI：`/docs`
- Redis 工作记忆
- 长期记忆和用户画像 JSON 持久化
- 知识库导入内容 JSON 持久化
- Docker Compose 中的 Redis、ChromaDB、Prometheus、Nginx

仍然不同：

- Java 版还没有抽象成 Python 那种通用 MCPToolManager / ToolRegistry。
- Java 版保留 ChromaDB 容器，但当前主检索走本地持久化 Hybrid RAG，不是直接查 ChromaDB collection。
- Python 版有 CLI 模式，Java 版当前只提供 HTTP 服务。
- Python 版监控里有 Z-score 异常检测，Java 版目前是阈值告警 + Micrometer + Webhook。

更完整的对照见 [JAVA_PYTHON_DIFF.md](/Users/xiao_xiong/Desktop/code/EchoMindJava/JAVA_PYTHON_DIFF.md)。

## 模型配置

Java 版通过 Spring profile 切换模型。

DeepSeek：

```env
SPRING_PROFILES_ACTIVE=deepseek
DEEPSEEK_API_KEY=your_key
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
```

Anthropic：

```env
SPRING_PROFILES_ACTIVE=anthropic
ANTHROPIC_API_KEY=your_key
ANTHROPIC_BASE_URL=https://api.anthropic.com
ANTHROPIC_MODEL=claude-3-5-sonnet-20241022
```

没有真实 API Key 时应用也可以启动。代码会使用启动占位 key 避免 Spring AI 自动配置失败；实际调用模型时如果没有真实 key，并且：

```env
LLM_FALLBACK_ENABLED=true
```

系统会返回本地降级回复。

## 数据持久化

默认本地路径：

| 数据 | 默认路径 | 环境变量 |
|------|----------|----------|
| 知识库片段 | `data/java/knowledge-store.json` | `KNOWLEDGE_STORE_PATH` |
| 长期记忆和用户画像 | `data/java/memory-store.json` | `MEMORY_STORE_PATH` |
| 评测 baseline | `data/eval/baseline.json` | `EVAL_BASELINE_PATH` |

Docker 中路径：

```text
/app/data/java/knowledge-store.json
/app/data/java/memory-store.json
```

这些文件挂载在 `app-data` volume 下，容器重建后仍可保留。

## 主要接口

默认端口：`8080`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查 |
| POST | `/chat` | 主对话接口 |
| POST | `/search` | 知识库检索 |
| POST | `/knowledge/add` | 批量添加知识文档 |
| POST | `/knowledge/upload` | 上传 `.txt` / `.md` / `.json` 文件 |
| GET | `/knowledge/stats` | 知识库统计 |
| GET | `/monitor` | 监控摘要 |
| GET | `/metrics` | Prometheus 指标，兼容 Python 版路径 |
| GET | `/actuator/prometheus` | Spring Actuator Prometheus 指标 |
| POST | `/eval/run` | 运行评测 |
| GET | `/docs` | Swagger UI，可在线调用接口 |
| GET | `/v3/api-docs` | OpenAPI JSON |

Jackson 已配置 `SNAKE_CASE`，所以接口 JSON 字段会输出为 `conversation_id`、`agent_type`、`latency_ms`、`knowledge_used` 等格式。

## 本地运行

### macOS / Linux

准备环境：

- JDK 21 或更高版本
- Docker Desktop 或 Docker Engine

启动依赖：

```bash
docker compose up -d redis chromadb
```

DeepSeek 启动：

```bash
export SPRING_PROFILES_ACTIVE=deepseek
export DEEPSEEK_API_KEY=your_key
./mvnw spring-boot:run
```

Anthropic 启动：

```bash
export SPRING_PROFILES_ACTIVE=anthropic
export ANTHROPIC_API_KEY=your_key
./mvnw spring-boot:run
```

健康检查：

```bash
curl http://localhost:8080/health
```

Swagger：

```text
http://localhost:8080/docs
```

### Windows PowerShell

启动依赖：

```powershell
docker compose up -d redis chromadb
```

DeepSeek 启动：

```powershell
$env:SPRING_PROFILES_ACTIVE="deepseek"
$env:DEEPSEEK_API_KEY="your_key"
.\mvnw.cmd spring-boot:run
```

Anthropic 启动：

```powershell
$env:SPRING_PROFILES_ACTIVE="anthropic"
$env:ANTHROPIC_API_KEY="your_key"
.\mvnw.cmd spring-boot:run
```

健康检查：

```powershell
curl http://localhost:8080/health
```

Swagger：

```text
http://localhost:8080/docs
```

## Docker 部署

复制配置：

```bash
cp .env.example .env
```

Windows PowerShell：

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，选择模型 profile 并填写对应 API Key。

启动：

```bash
docker compose up -d --build
```

查看状态：

```bash
docker compose ps
```

查看日志：

```bash
docker compose logs -f echomind-java
```

停止：

```bash
docker compose down
```

Compose 服务和端口：

| 服务 | 容器名 | 地址 |
|------|--------|------|
| Java App | `echomind-java-app` | `http://localhost:8080` |
| Nginx | `echomind-java-nginx` | `http://localhost:8081` |
| Prometheus | `echomind-java-prometheus` | `http://localhost:9091` |
| ChromaDB | `echomind-java-chromadb` | `http://localhost:8002` |
| Redis | `echomind-java-redis` | `localhost:6380` |

常用验证：

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
curl http://localhost:8080/metrics
```

Swagger：

```text
http://localhost:8080/docs
http://localhost:8081/docs
```

## API 示例

### 对话

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "我想申请退款，订单号是 #12345",
    "user_id": "u1001"
  }'
```

也可以打开 Swagger UI，通过页面直接调用：

```text
http://localhost:8080/docs
```

### 检索

```bash
curl -X POST "http://localhost:8080/search?query=退款多久能到账&topK=3"
```

### 添加知识库

```bash
curl -X POST http://localhost:8080/knowledge/add \
  -H "Content-Type: application/json" \
  -d '{
    "documents": [
      {
        "title": "退款补充政策",
        "content": "大促期间退款审核时间可能延长到 3-5 个工作日。"
      }
    ]
  }'
```

### 上传知识库文件

```bash
curl -X POST http://localhost:8080/knowledge/upload \
  -F "file=@docs.md"
```

### 运行评测

```bash
curl -X POST http://localhost:8080/eval/run \
  -H "Content-Type: application/json" \
  -d '{}'
```

## 常见问题

### 为什么 Compose 里还有 ChromaDB？

当前 Java 版主检索使用持久化 Hybrid RAG。ChromaDB 容器保留给 Spring AI VectorStore / ChromaDB 主检索扩展，也用于和 Python 版部署结构保持接近。

### 为什么没有 API Key 也能启动？

为了避免 Docker 和本地开发阶段因为缺少模型 key 直接启动失败，配置里使用了 `echomind-local-placeholder` 作为启动占位值。真实模型调用前会检查是否配置了真实 key；未配置时会走本地 fallback。

### 和 Python 版完全等价了吗？

还没有完全等价。Java 版已经补齐主链路、DeepSeek、Hybrid RAG、LLM rerank、上传接口、持久化、评测、监控和 Docker 部署配套。剩余主要差异是通用 ToolRegistry、ChromaDB 主检索、CLI 模式和更复杂的监控算法。

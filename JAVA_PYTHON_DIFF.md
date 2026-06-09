# EchoMind Java 与 Python 版本差异说明

本文档基于当前代码重新修订，对比：

- Python 版本：`/Users/xiao_xiong/Desktop/code/EchoMind`
- Java 版本：`/Users/xiao_xiong/Desktop/code/EchoMindJava`

结论：Java 版本已经覆盖 Python 版的主业务链路和大部分工程能力，并增加了 Spring AI、DeepSeek、Hybrid RAG、Answer Verifier、Actuator/Micrometer 等 Java 技术栈能力。仍然没有完全等价的部分主要是通用 MCPToolManager、ChromaDB 作为主检索存储、CLI 模式和 Z-score 监控算法。

## 总体对照

| 能力 | Python 版本 | Java 版本 | 当前状态 |
|------|-------------|-----------|----------|
| Web 框架 | FastAPI | Spring Boot MVC | 已对齐 |
| 模型调用 | Anthropic Async SDK | Spring AI ChatModel | 已对齐，调用栈不同 |
| DeepSeek | 未内置 | Spring AI DeepSeek profile | Java 增强 |
| Agent 类型 | General / Technical / Billing | General / Technical / Billing | 已对齐 |
| Agent 路由 | 意图路由 + 性能路由 + 降级 | 意图路由 + 性能路由 + 降级 | 已对齐 |
| 复合问题并行处理 | 支持 | 支持 | 已对齐 |
| 意图识别 | LLM + embedding/hash + pattern | LLM + char n-gram semantic + pattern | 基本对齐 |
| 工作记忆 | Redis | Redis | 已对齐 |
| 情景记忆 | ChromaDB `episodic` collection | JSON 持久化 + 本地向量检索 | 功能对齐，存储不同 |
| 用户画像 | ChromaDB `user_profile` collection | JSON 持久化 | 功能对齐，存储不同 |
| 知识库 | ChromaDB `knowledge_base` collection | JSON 持久化 Hybrid RAG | 功能对齐，主检索实现不同 |
| 查询改写 | LLM 改写 | LLM 改写 | 已对齐 |
| 检索重排 | LLM rerank | LLM rerank，失败回退融合分 | 已对齐 |
| 工具框架 | 通用 MCPToolManager | 专用 KnowledgeToolManager | 部分对齐 |
| 熔断/缓存/超时/fallback | 支持 | 支持 | 已对齐到知识库工具 |
| 评测 | Intent、Macro-F1、LLM Judge、baseline | Intent、Macro-F1、LLM Judge、baseline | 基本对齐 |
| 监控 | Prometheus client、Webhook、Z-score | Actuator、Micrometer、Webhook、阈值告警 | 部分对齐 |
| API 文档 | FastAPI `/docs` | Springdoc Swagger UI `/docs` | 已对齐 |
| Docker | App、Redis、ChromaDB、Prometheus、Nginx | App、Redis、ChromaDB、Prometheus、Nginx | 已对齐 |
| CLI | `api/main.py --cli` | 未实现 | Java 缺失 |

## Java 版已补齐的能力

### 1. 主对话链路

Java 版 `/chat` 当前链路：

```text
EchoMindController
  -> MemoryManager.getContext()
  -> KnowledgeToolManager.searchWithRewrite()
  -> AgentOrchestrator.run()
  -> BaseAgent / GeneralAgent / TechnicalAgent / BillingAgent
  -> AnswerVerifier.verify()
  -> MemoryManager.addMessage()
  -> MemoryManager.updateProfile()
```

相关文件：

- `src/main/java/com/echomind/api/EchoMindController.java`
- `src/main/java/com/echomind/memory/MemoryManager.java`
- `src/main/java/com/echomind/tool/KnowledgeToolManager.java`
- `src/main/java/com/echomind/agent/AgentOrchestrator.java`
- `src/main/java/com/echomind/agent/AnswerVerifier.java`

### 2. DeepSeek 兼容

Java 版通过 Spring profile 切换模型：

```env
SPRING_PROFILES_ACTIVE=deepseek
DEEPSEEK_API_KEY=your_key
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
```

当前还做了启动容错：缺少真实 API Key 时使用 `echomind-local-placeholder` 避免 Spring AI 自动配置阶段直接失败。真实调用时如果没有配置真实 key，并且 `LLM_FALLBACK_ENABLED=true`，会返回本地降级回复。

### 3. 记忆和知识库持久化

Python 版：

- Redis 保存工作记忆。
- ChromaDB 保存情景记忆、用户画像和知识库。

Java 版：

- Redis 保存工作记忆。
- `data/java/memory-store.json` 保存情景记忆和用户画像。
- `data/java/knowledge-store.json` 保存知识库片段。
- Docker 中保存到 `/app/data/java`，由 `app-data` volume 持久化。

配置项：

```env
ECHOMIND_DATA_DIR=data/java
KNOWLEDGE_STORE_PATH=data/java/knowledge-store.json
MEMORY_STORE_PATH=data/java/memory-store.json
```

当前用户可见效果已经对齐：应用重启后，导入的知识库、长期记忆和画像可以恢复。

底层差异仍然存在：Java 没有直接写入 ChromaDB collection，而是 JSON 持久化 + 本地 hash vector 检索。

### 4. Hybrid RAG

Python 版知识库主要通过 ChromaDB 做语义检索。

Java 版当前检索链路：

```text
文档导入
  -> LangChain4j recursive splitter
  -> JSON 持久化
  -> 本地 documents 索引

查询
  -> LLM 查询改写
  -> 多子查询并行召回
  -> BM25 关键词得分
  -> 本地 hash vector 语义得分
  -> 加权融合
  -> LLM rerank
  -> fallback 到融合分排序
```

这比 Python 版多了 BM25 + vector 融合检索，但没有把 ChromaDB 作为主召回源。

### 5. 评测和监控

Java 版已经实现：

- Intent Accuracy
- Macro-F1
- per-class Precision / Recall / F1
- LLM-as-Judge 四维评分
- baseline 保存和回归检测
- `/monitor`
- `/metrics`
- `/actuator/prometheus`
- Webhook 告警
- Agent 路由惩罚反馈

相关文件：

- `src/main/java/com/echomind/evaluation/EndToEndEvaluator.java`
- `src/main/java/com/echomind/evaluation/LLMJudge.java`
- `src/main/java/com/echomind/monitor/PerformanceMonitor.java`

### 6. Docker 部署

Python 版和 Java 版现在都具备完整 Compose 部署结构。

Java 版 Compose 服务：

- `echomind-java-app`
- `echomind-java-redis`
- `echomind-java-chromadb`
- `echomind-java-prometheus`
- `echomind-java-nginx`

端口：

| 服务 | 地址 |
|------|------|
| Java App | `http://localhost:8080` |
| Nginx | `http://localhost:8081` |
| Prometheus | `http://localhost:9091` |
| ChromaDB | `http://localhost:8002` |
| Redis | `localhost:6380` |

相关文件：

- `docker-compose.yml`
- `Dockerfile`
- `config/prometheus.yml`
- `config/nginx/nginx.conf`

### 7. Swagger / OpenAPI 文档

Python 版 FastAPI 默认提供 `/docs`。

Java 版现在通过 Springdoc OpenAPI 提供同名入口：

- Swagger UI：`http://localhost:8080/docs`
- Nginx 代理：`http://localhost:8081/docs`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

相关实现：

- `pom.xml`
- `src/main/resources/application.yml`
- `src/main/java/com/echomind/config/OpenApiConfig.java`
- `src/main/java/com/echomind/api/EchoMindController.java`

## 当前仍然不同的地方

### 1. Java 版还不是通用 MCPToolManager

Python 版 `MCPToolManager` 可以注册任意 Tool，并统一处理：

- register / unregister
- JSON Schema 参数校验
- timeout
- circuit breaker
- TTL cache
- fallback
- rerank
- 工具统计

Java 版当前是专用 `KnowledgeToolManager`，只服务 `knowledge_search`，但已经具备查询改写、并行召回、缓存、超时、熔断、fallback、rerank 和统计。

要完全对齐，可以继续新增：

- `ToolDefinition`
- `ToolRegistry`
- `ToolExecutor`
- JSON Schema validator

### 2. ChromaDB 不是 Java 版主检索源

Java 版保留 ChromaDB 容器，并引入了 Spring AI Chroma VectorStore starter，但当前 profile 中排除了 Chroma VectorStore 自动配置，主检索仍然走本地 Hybrid RAG。

原因：

- DeepSeek Chat starter 不提供 EmbeddingModel。
- 当前实现优先保证 DeepSeek/Anthropic 都能启动和运行。

后续可增强为：

```text
ChromaDB VectorStore semantic search
  + BM25 keyword recall
  + RRF / weighted fusion
  + LLM rerank
```

### 3. CLI 模式未迁移

Python 版支持：

```bash
python api/main.py --cli
```

Java 版当前没有 CLI。如果需要对齐，可以使用 Spring Shell 或 `CommandLineRunner` 实现。

### 4. 监控算法仍有差异

Python 版监控包含 Z-score 异常检测。

Java 版当前是：

- 成功率阈值告警
- 平均延迟阈值告警
- Webhook
- Micrometer 指标
- 路由惩罚反馈

可继续补：

- Z-score 异常检测
- 告警 resolved 状态
- 请求 latency histogram
- tool success rate gauge

## API 字段差异

Java 版配置了 Jackson `SNAKE_CASE`，响应字段会输出为：

```json
{
  "conversation_id": "...",
  "response": "...",
  "intent": "...",
  "agent_type": "...",
  "escalated": false,
  "latency_ms": 123,
  "knowledge_used": true,
  "verified": true,
  "grounded": true
}
```

和 Python 版主要差异：

- Python 使用 `conv_id`。
- Java 使用 `conversation_id`。
- Java 额外返回 `verified` 和 `grounded`。

请求字段也受 `SNAKE_CASE` 配置影响，推荐使用：

```json
{
  "message": "我想申请退款",
  "user_id": "u1001",
  "conversation_id": "optional-conversation-id"
}
```

## 总结

Java 版当前已经可以作为 Spring Boot + Spring AI + LangChain4j 技术栈的完整重构版使用。它在主链路、模型切换、知识库上传、持久化、评测、监控和容器部署上已经基本对齐 Python 版。

Swagger UI 也已补齐，可以通过 `/docs` 在线调用接口。

剩余主要差异不影响主流程运行，更多是工程抽象和底层存储实现差异：

- 通用 MCPToolManager 尚未迁移。
- ChromaDB 还不是 Java 主检索源。
- CLI 模式未迁移。
- Z-score 监控算法未迁移。

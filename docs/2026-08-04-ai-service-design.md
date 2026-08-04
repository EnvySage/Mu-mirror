# AI 服务层设计文档 — Python gRPC 服务

> 版本：v0.2
> 日期：2026-08-04
> 状态：设计中

**关联文档：**
- [系统设计文档](2026-07-23-ai-diary-mirror-design.md) — 整体功能设计、数据库、API、异常机制
- [落地实现文档](2026-07-23-mirror-implementation.md) — Java 端实现思路、项目结构、开发计划

---

## 一、架构总览

### 1.1 整体架构

```
Vue 3 前端
    ↓ HTTP
Spring Boot（Java）
  ├── 业务逻辑、CRUD、认证、定时任务
  ├── 数据库操作（PostgreSQL + pgvector 查询）
  └── gRPC Client
        ↓ gRPC
Python AI 服务（gRPC Server）
  ├── 记录分类（标题/摘要/标签/情绪）
  ├── Embedding（本地 BGE-m3 / API）
  ├── 对话生成（多轮 RAG）
  └── 画像生成
        ↓
  ├── 本地 Embedding 模型（BGE-m3）
  └── LLM API（用户自配：OpenAI/通义千问/智谱）
```

### 1.2 职责划分

| 层 | 职责 | 不做什么 |
|---|---|---|
| **Java（Spring Boot）** | 业务逻辑、CRUD、认证、定时任务、数据库读写（含 pgvector 向量查询）、gRPC 调用 | 不做 AI 推理、不调 LLM API |
| **Python（AI 服务）** | 纯 AI 推理：分类、Embedding、对话、画像生成 | 不碰数据库、不管业务逻辑 |

**核心原则：Python 是无状态的推理引擎，所有数据持久化由 Java 负责。**

### 1.3 数据流

**随手记处理流程：**
```
用户输入 → Java 接收
  → gRPC: Classify（Python 返回标题/摘要/标签）
  → Java 保存 records 表
  → gRPC: Embed（Python 返回向量）
  → Java 保存 chunks 表（pgvector）
  → 返回前端
```

**向镜子提问流程：**
```
用户提问 → Java 接收
  → gRPC: ExtractIntent（Python 返回过滤条件）
  → Java 执行 pgvector 向量检索 + 元数据过滤
  → gRPC: Chat（Python 基于检索结果生成回答）
  → Java 保存对话历史
  → 返回前端
```

**画像生成流程：**
```
用户点击"查看镜子" → Java 接收
  → Java 从数据库查询各维度数据（待办/学习/情绪/关键词/活跃时段）
  → gRPC: GenerateProfile（Python 基于数据生成画像）
  → Java 保存画像
  → 返回前端
```

---

## 二、gRPC Proto 设计

### 2.1 文件结构

```
proto/
├── common.proto           # 公共消息定义
├── record_processor.proto # 记录处理服务
├── embedding.proto        # Embedding 服务
├── mirror_chat.proto      # 对话服务
├── mirror_profile.proto   # 画像服务
└── config_service.proto   # 配置更新服务
```

### 2.2 common.proto

```protobuf
syntax = "proto3";
package mirror;

// 情绪标签（枚举）
enum MoodType {
  MOOD_UNKNOWN = 0;
  // 正面
  HAPPY = 1;      // 开心
  EXCITED = 2;    // 兴奋
  SATISFIED = 3;  // 满足
  GRATEFUL = 4;   // 感恩
  EXPECTING = 5;  // 期待
  // 中性
  CALM = 6;       // 平静
  BORED = 7;      // 无聊
  CONFUSED = 8;   // 困惑
  // 负面
  ANXIOUS = 9;    // 焦虑
  SAD = 10;       // 难过
  ANGRY = 11;     // 愤怒
  EXHAUSTED = 12; // 疲惫
  STRESSED = 13;  // 压力
}

// 内容类型
enum ContentType {
  CONTENT_UNKNOWN = 0;
  TODO = 1;       // 待办
  THOUGHT = 2;    // 感想
  LEARNING = 3;   // 学习
  PLAN = 4;       // 计划
  NOTE = 5;       // 随记
  WORK = 6;       // 工作
  SOCIAL = 7;     // 社交
  HEALTH = 8;     // 健康
}

// 状态
enum TaskStatus {
  STATUS_UNKNOWN = 0;
  NOT_STARTED = 1;
  IN_PROGRESS = 2;
  COMPLETED = 3;
}
```

### 2.3 record_processor.proto — 记录处理

```protobuf
syntax = "proto3";
package mirror;

import "common.proto";

service RecordProcessor {
  // 分类：输入文本，返回标题/摘要/标签
  rpc Classify(ClassifyRequest) returns (ClassifyResponse);

  // 多事件拆分：判断是否需要拆分，返回拆分结果
  rpc Split(SplitRequest) returns (SplitResponse);
}

// --- 分类 ---

message ClassifyRequest {
  string content = 1;  // 用户输入的原始文本
}

message ClassifyResponse {
  bool skip = 1;                // 是否跳过（无意义内容）
  string skip_reason = 2;       // 跳过原因
  string title = 3;             // 标题（10字以内）
  string summary = 4;           // 摘要（30字以内）
  ContentType content_type = 5; // 内容类型
  repeated MoodType moods = 6;  // 情绪（多选）
  TaskStatus status = 7;        // 状态（仅待办/计划类）
  repeated string keywords = 8; // 关键词 3-5 个
}

// --- 拆分 ---

message SplitRequest {
  string content = 1;
}

message SplitResponse {
  bool need_split = 1;              // 是否需要拆分
  repeated SplitSegment segments = 2; // 拆分后的段落
}

message SplitSegment {
  string content = 1;  // 段落内容
  int32 order = 2;     // 顺序
}
```

### 2.4 embedding.proto — Embedding 服务

```protobuf
syntax = "proto3";
package mirror;

service EmbeddingService {
  // 文本转向量
  rpc Embed(EmbedRequest) returns (EmbedResponse);

  // 批量转向量
  rpc EmbedBatch(EmbedBatchRequest) returns (EmbedBatchResponse);

  // 查询当前使用的 embedding 模型信息
  rpc GetModelInfo(ModelInfoRequest) returns (ModelInfoResponse);
}

message EmbedRequest {
  string text = 1;
}

message EmbedResponse {
  repeated float vector = 1;    // 向量
  int32 dimension = 2;          // 维度
  string model_name = 3;        // 使用的模型名
}

message EmbedBatchRequest {
  repeated string texts = 1;
}

message EmbedBatchResponse {
  repeated EmbedResponse results = 1;
}

message ModelInfoRequest {}

message ModelInfoResponse {
  string model_name = 1;     // 模型名称，如 "bge-m3"
  string source = 2;         // 来源："local" 或 "api"
  int32 dimension = 3;       // 向量维度
  bool available = 4;        // 当前是否可用
}
```

### 2.5 mirror_chat.proto — 对话服务

```protobuf
syntax = "proto3";
package mirror;

service MirrorChat {
  // 提取意图（过滤条件）
  rpc ExtractIntent(ExtractIntentRequest) returns (ExtractIntentResponse);

  // 生成回答（支持流式）
  rpc Chat(ChatRequest) returns (stream ChatChunk);
}

// --- 意图提取 ---

message ExtractIntentRequest {
  string query = 1;  // 用户问题
}

message ExtractIntentResponse {
  optional string content_type = 2;  // 过滤：内容类型
  repeated string moods = 3;         // 过滤：情绪
  string time_range = 4;             // 过滤：时间范围（如 "最近7天"）
  string rewritten_query = 5;        // 改写后的检索 query
}

// --- 对话 ---

message ChatRequest {
  string question = 1;                // 用户问题
  repeated ChatMessage history = 2;   // 对话历史
  repeated RetrievedChunk chunks = 3; // Java 端检索好的相关记录
}

message ChatMessage {
  string role = 1;    // "user" 或 "assistant"
  string content = 2;
}

message RetrievedChunk {
  int64 record_id = 1;
  string content = 2;       // 原文
  string title = 3;
  string created_at = 4;
  string content_type = 5;
  repeated string moods = 6;
  float score = 7;          // 相似度分数
}

// 流式返回
message ChatChunk {
  string content = 1;       // 本次返回的文本片段
  bool done = 2;            // 是否结束
  repeated Source sources = 3; // 来源（最后一次返回）
}

message Source {
  int64 record_id = 1;
  string quote = 2;         // 引用的原文片段
  string date = 3;
}
```

### 2.6 mirror_profile.proto — 画像服务

```protobuf
syntax = "proto3";
package mirror;

service MirrorProfile {
  // 生成画像
  rpc GenerateProfile(GenerateProfileRequest) returns (GenerateProfileResponse);
}

message GenerateProfileRequest {
  // Java 端查询好的各维度数据，直接传给 Python
  repeated TodoItem todos = 1;          // 未完成的待办
  repeated LearningItem learnings = 2;  // 最近学习内容
  repeated MoodStat mood_stats = 3;     // 情绪统计
  repeated KeywordStat keywords = 4;    // 关键词统计
  ActiveTimeStats active_time = 5;      // 活跃时段
  int32 total_records = 6;              // 总记录数
  string time_range = 7;                // 数据时间范围
}

message TodoItem {
  int64 record_id = 1;
  string title = 2;
  string summary = 3;
  string created_at = 4;
}

message LearningItem {
  int64 record_id = 1;
  string title = 2;
  string summary = 3;
  repeated string keywords = 4;
  string created_at = 5;
}

message MoodStat {
  string mood = 1;
  int32 count = 2;
  float percentage = 3;
}

message KeywordStat {
  string keyword = 1;
  int32 count = 2;
}

message ActiveTimeStats {
  map<string, int32> hour_distribution = 1;  // {"22": 5, "23": 3, ...}
  map<string, int32> weekday_distribution = 2; // {"周一": 3, ...}
  int32 records_last_7_days = 3;
  string peak_hour = 4;   // 最活跃时段
}

message GenerateProfileResponse {
  // 各维度的分析结果
  string todo_analysis = 1;      // 未完成的事
  string learning_analysis = 2;  // 学习分析
  string mood_analysis = 3;      // 情绪分析
  repeated string user_tags = 4; // 用户特征标签
  string rhythm_analysis = 5;    // 生活节奏分析
  string overall_summary = 6;    // 整体总结
}
```

### 2.7 config_service.proto — 配置更新

```protobuf
syntax = "proto3";
package mirror;

// 配置更新服务（Java 数据库为配置源，Python 通过此 RPC 热更新）
service ConfigService {
  // 更新 LLM 配置
  rpc UpdateLlmConfig(UpdateLlmConfigRequest) returns (UpdateConfigResponse);

  // 更新 Embedding 配置
  rpc UpdateEmbeddingConfig(UpdateEmbeddingConfigRequest) returns (UpdateConfigResponse);

  // 查询当前配置状态
  rpc GetConfigStatus(GetConfigStatusRequest) returns (GetConfigStatusResponse);
}

message UpdateLlmConfigRequest {
  string provider = 1;    // openai / zhipu / qwen
  string api_key = 2;
  string base_url = 3;    // 可选
  string model = 4;
}

message UpdateEmbeddingConfigRequest {
  string source = 1;      // local / api
  // 本地模式
  string local_model = 2; // 如 "BAAI/bge-m3"
  // API 模式
  string api_provider = 3;
  string api_key = 4;
  string api_model = 5;
}

message UpdateConfigResponse {
  bool success = 1;
  string message = 2;     // 成功/失败信息
  bool need_restart = 3;  // 是否需要重启服务（如切换 embedding 模型）
}

message GetConfigStatusRequest {}

message GetConfigStatusResponse {
  string llm_provider = 1;
  string llm_model = 2;
  string embedding_source = 3;
  string embedding_model = 4;
  int32 embedding_dimension = 5;
  bool llm_available = 6;
  bool embedding_available = 7;
}
```

---

## 三、Python AI 服务设计

### 3.1 模块结构

```
mirror-ai/
├── server.py                # gRPC 服务启动入口
├── config.py                # 配置管理（embedding 来源、LLM 配置）
├── services/
│   ├── record_processor.py  # 记录分类服务
│   ├── embedding_service.py # Embedding 服务（本地/API 切换）
│   ├── chat_service.py      # 对话服务
│   ├── profile_service.py   # 画像服务
│   └── config_service.py    # 配置热更新服务
├── llm/
│   ├── base.py              # LLM 统一接口
│   ├── openai_llm.py        # OpenAI 实现
│   ├── qwen_llm.py          # 通义千问实现
│   └── zhipu_llm.py         # 智谱实现
├── embedding/
│   ├── base.py              # Embedding 统一接口
│   ├── local_embedder.py    # 本地 BGE-m3
│   └── api_embedder.py      # API Embedding
├── prompts/
│   ├── classify.txt         # 分类 prompt 模板
│   ├── chat.txt             # 对话 prompt 模板
│   ├── intent.txt           # 意图提取 prompt 模板
│   └── profile.txt          # 画像生成 prompt 模板
├── requirements.txt
├── Dockerfile
└── config.example.yml       # 配置示例
```

### 3.2 Embedding 可切换设计

**统一接口：**
```python
# embedding/base.py
class BaseEmbedder(ABC):
    @abstractmethod
    def embed(self, text: str) -> list[float]: ...

    @abstractmethod
    def embed_batch(self, texts: list[str]) -> list[list[float]]: ...

    @abstractmethod
    def get_model_info(self) -> dict: ...
```

**本地实现（BGE-m3）：**
```python
# embedding/local_embedder.py
class LocalEmbedder(BaseEmbedder):
    def __init__(self, model_name: str = "BAAI/bge-m3"):
        from sentence_transformers import SentenceTransformer
        self.model = SentenceTransformer(model_name)
        self.model_name = model_name

    def embed(self, text: str) -> list[float]:
        return self.model.encode(text).tolist()
```

**API 实现：**
```python
# embedding/api_embedder.py
class ApiEmbedder(BaseEmbedder):
    def __init__(self, provider: str, api_key: str, base_url: str, model: str):
        # provider: "openai" / "zhipu" / "qwen"
        ...
```

**配置切换（config.yml）：**
```yaml
embedding:
  source: "local"          # "local" 或 "api"
  local:
    model: "BAAI/bge-m3"
  api:
    provider: "openai"     # openai / zhipu / qwen
    api_key: "sk-xxx"
    base_url: ""           # 可选，留空用默认
    model: "text-embedding-3-small"
```

**工厂函数：**
```python
def create_embedder(config: dict) -> BaseEmbedder:
    source = config["embedding"]["source"]
    if source == "local":
        return LocalEmbedder(**config["embedding"]["local"])
    elif source == "api":
        return ApiEmbedder(**config["embedding"]["api"])
    else:
        raise ValueError(f"Unknown embedding source: {source}")
```

### 3.3 LLM 统一接口

```python
# llm/base.py
class BaseLlm(ABC):
    @abstractmethod
    def chat(self, messages: list[dict], temperature: float = 0.7) -> str: ...

    @abstractmethod
    def chat_stream(self, messages: list[dict], temperature: float = 0.7) -> Generator[str]: ...
```

**配置：**
```yaml
llm:
  provider: "openai"       # openai / zhipu / qwen
  api_key: "sk-xxx"
  base_url: ""             # 可选
  model: "gpt-4o-mini"
  temperature: 0.7
```

### 3.4 配置管理

**配置源优先级**：Java 数据库（user_settings）为生产环境唯一配置源。Python 的 `config.yml` 仅用于本地开发默认值。

**配置更新流程**：
```
用户在前端改配置 → Java 存 user_settings 表
  → Java gRPC 调用 ConfigService.UpdateLlmConfig / UpdateEmbeddingConfig
  → Python 热更新内存配置
  → 如果需要重启（如切换 embedding 模型），返回 need_restart=true
```

```yaml
# config.yml（本地开发默认值，生产环境以数据库为准）
server:
  port: 50051              # gRPC 端口
  workers: 4               # 并发数

embedding:
  source: "local"          # local / api
  local:
    model: "BAAI/bge-m3"
  api:
    provider: "openai"
    api_key: ""
    base_url: ""
    model: "text-embedding-3-small"

llm:
  provider: "openai"
  api_key: ""
  base_url: ""
  model: "gpt-4o-mini"
  temperature: 0.7
  max_tokens: 2048

prompts:
  classify: "prompts/classify.txt"
  chat: "prompts/chat.txt"
  intent: "prompts/intent.txt"
  profile: "prompts/profile.txt"
```

---

## 四、Java 端 gRPC 客户端设计

### 4.1 模块结构（新增部分）

```
src/main/java/com/mirror/
├── grpc/
│   ├── GrpcClientConfig.java      # gRPC 连接配置
│   ├── AiGrpcClient.java          # 统一的 AI 调用封装
│   └── interceptors/
│       └── LoggingInterceptor.java # 日志拦截器
```

### 4.2 调用封装

```java
@Service
public class AiGrpcClient {

    // 记录分类
    public ClassifyResponse classify(String content) {
        // 调用 RecordProcessor/Classify
    }

    // 生成向量
    public EmbedResponse embed(String text) {
        // 调用 EmbeddingService/Embed
    }

    // 批量向量
    public EmbedBatchResponse embedBatch(List<String> texts) {
        // 调用 EmbeddingService/EmbedBatch
    }

    // 提取意图
    public ExtractIntentResponse extractIntent(String query) {
        // 调用 MirrorChat/ExtractIntent
    }

    // 对话（流式）
    public Iterator<ChatChunk> chat(String question, List<ChatMessage> history, List<RetrievedChunk> chunks) {
        // 调用 MirrorChat/Chat，返回流式迭代器
    }

    // 生成画像
    public GenerateProfileResponse generateProfile(GenerateProfileRequest request) {
        // 调用 MirrorProfile/GenerateProfile
    }
}
```

### 4.3 配置（application.yml）

```yaml
grpc:
  client:
    ai-service:
      address: localhost:50051
      negotiation-type: plaintext  # 开发环境，生产用 TLS
```

### 4.4 超时与重试

```java
// 关键调用设置超时
public ClassifyResponse classify(String content) {
    return recordProcessorStub
        .withDeadlineAfter(30, TimeUnit.SECONDS)  // 30秒超时
        .classify(ClassifyRequest.newBuilder()
            .setContent(content)
            .build());
}

// Embedding 可重试（幂等操作）
public EmbedResponse embed(String text) {
    return Retryer.of(EmbedResponse.class)
        .maxRetries(2)
        .retryOn(StatusRuntimeException.class)
        .call(() -> embeddingStub
            .withDeadlineAfter(10, TimeUnit.SECONDS)
            .embed(EmbedRequest.newBuilder().setText(text).build()));
}
```

---

## 五、Docker 部署设计

### 5.1 docker-compose.yml

```yaml
version: '3.8'

services:
  # PostgreSQL + pgvector
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: mirror
      POSTGRES_USER: mirror
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./sql/init.sql:/docker-entrypoint-initdb.d/init.sql
    ports:
      - "5432:5432"

  # Java 后端
  mirror-backend:
    build: ./mirror-backend
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/mirror
      GRPC_CLIENT_AI-SERVICE_ADDRESS: mirror-ai:50051
    depends_on:
      - postgres
      - mirror-ai

  # Python AI 服务
  mirror-ai:
    build: ./mirror-ai
    ports:
      - "50051:50051"
    environment:
      EMBEDDING_SOURCE: ${EMBEDDING_SOURCE:-local}
      LLM_API_KEY: ${LLM_API_KEY}
      LLM_PROVIDER: ${LLM_PROVIDER:-openai}
    volumes:
      - ai-models:/app/models  # 持久化模型文件
    deploy:
      resources:
        limits:
          memory: 4G  # 本地 embedding 模型需要内存

volumes:
  pgdata:
  ai-models:
```

### 5.2 环境变量

```bash
# .env
DB_PASSWORD=your_db_password
EMBEDDING_SOURCE=local        # local 或 api
LLM_PROVIDER=openai           # openai / zhipu / qwen
LLM_API_KEY=sk-xxx
LLM_MODEL=gpt-4o-mini
EMBEDDING_API_KEY=            # 仅 api 模式需要
EMBEDDING_API_MODEL=          # 仅 api 模式需要
```

---

## 六、错误处理设计

### 6.1 gRPC 错误码映射

| gRPC 状态码 | Java 端处理 | 用户看到 |
|---|---|---|
| `OK` | 正常处理 | — |
| `DEADLINE_EXCEEDED` | 标记 failed，可重试 | "处理超时，请重试" |
| `UNAVAILABLE` | 重试1次 | "AI 服务暂时不可用" |
| `INVALID_ARGUMENT` | 记录日志 | "输入内容有误" |
| `INTERNAL` | 标记 failed | "处理失败，请重试" |

### 6.2 Python 端异常处理

```python
# 服务端统一异常处理
class AiServiceException(Exception):
    def __init__(self, message: str, code: grpc.StatusCode):
        self.message = message
        self.code = code

# 每个 RPC 方法包装 try-except
async def Classify(self, request, context):
    try:
        result = self.classifier.classify(request.content)
        return ClassifyResponse(**result)
    except TimeoutError:
        context.abort(grpc.StatusCode.DEADLINE_EXCEEDED, "LLM 调用超时")
    except Exception as e:
        logger.exception("Classify failed")
        context.abort(grpc.StatusCode.INTERNAL, f"处理失败: {str(e)}")
```

### 6.3 降级策略

| 场景 | 降级方案 |
|---|---|
| Python 服务不可用 | Java 端捕获异常，记录 status=failed，用户可重试 |
| Embedding 失败 | 不写入向量库，记录保持无向量状态 |
| LLM 分类失败 | 使用默认值（type=note, mood=calm），标记 AI 未处理 |
| 画像生成失败 | 返回上次缓存的画像 |

---

## 七、开发顺序建议

| 阶段 | 内容 | 依赖 |
|---|---|---|
| **Phase 1** | 定义 proto 文件，生成 Java/Python 代码 | 无 |
| **Phase 2** | Python 搭骨架：gRPC server + Embedding（本地） | Phase 1 |
| **Phase 3** | Java 搭骨架：gRPC client + 记录 CRUD | Phase 1 |
| **Phase 4** | 联调：随手记流程（classify → save → embed → store） | Phase 2 + 3 |
| **Phase 5** | 对话功能（intent + chat） | Phase 4 |
| **Phase 6** | 画像生成 | Phase 4 |
| **Phase 7** | Embedding API 模式 | Phase 2 |
| **Phase 8** | 多 LLM 支持 | Phase 2 |

---

## 八、变更记录

| 日期 | 版本 | 变更内容 |
|---|---|---|
| 2026-08-04 | v0.1 | 初始设计：架构、proto、Python 模块、部署方案 |
| 2026-08-04 | v0.2 | 文档审查修正：新增 ConfigService Proto、明确 grpcio-aio、配置以 Java 数据库为准 |

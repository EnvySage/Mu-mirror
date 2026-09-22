# Mu-mirror-B

「AI 日记镜子」系统的 Java 后端。用户随手记录日常，AI 自动拆分、分类、打标签，用户审核确认后数据进入 RAG，最终生成用户画像（"镜子"）帮助认识自己。

**分工原则：Java 管数据与业务，Python 管纯推理。** 向量检索用 pgvector SQL 在本仓完成；AI 服务无状态，用户模型配置随每次 gRPC 请求携带。

## 在系统中的位置

```
Vue 3 前端 ──HTTP(/api, JWT)──> 本仓 ──SQL──> PostgreSQL + pgvector
                                  │
                                  └──gRPC──> Mu-mirror-AI（Python，无状态推理）
```

## 技术栈

| 项 | 版本 |
|---|---|
| Java | **17** |
| Spring Boot | 3.5.16 |
| MyBatis-Plus | 3.5.5（注解式 mapper，无 XML） |
| Druid | 1.2.25（连接池） |
| PostgreSQL | + pgvector 0.1.6 |
| gRPC | 1.68.0（netty-shaded）/ protobuf 3.25.5 |
| JWT | jjwt 0.12.3 |
| 文档 | springdoc-openapi 2.9.0 + Knife4j 4.4.0 |
| 文件解析 | PDFBox 2.0.29 / POI 4.1.2 |
| 构建 | Maven 3.9.10（wrapper） |

> 未使用 Redis（部分示例配置里有 Redis 段落，属历史残留）。

## 目录结构

```
src/main/java/org/xianshen/mumirrorb/
├── controller/     11 个 REST 控制器
├── service/        业务实现（Mirror / Chat / Record / Chunk / Vault / Glossary / Todo…）
├── mapper/         17 个 MyBatis mapper（全部注解式 @Select）
├── pipeline/       事件驱动的记录处理管道
│   ├── CleanProcessor      (@Order 1) 文本清洗
│   ├── ClassifyProcessor   (@Order 2) gRPC 分类（拆分 + 打标签一次调用）
│   └── TimeSubstitutionApplier  相对时间消解执行器
├── grpc/           AiGrpcClient（唯一的 gRPC 调用点）+ 生成的 stub
├── tools/          工具调用框架 + 9 个工具实现
├── vault/          资产存储（PG BYTEA）、文件类型嗅探、内容抽取
├── pojo/           R 统一响应 + DO/DTO/VO
├── common/         枚举、异常、类型处理器、JWT 过滤器、加解密工具
└── config/         异步、gRPC、安全、Knife4j、各业务 Properties
```

## REST API

上下文路径 `/api`。统一响应 `R<T>`：`{code, message, data, timestamp}`，`code == 200` 为成功。

| 控制器 | 路径 | 主要端点 |
|---|---|---|
| `AuthController` | `/auth` | `status` / `register` / `login` / `me` |
| `RecordController` | `/records` | 创建、列表、详情、`{id}/confirm`、`{id}/suggestions`、`{id}/retry`、删除、`calendar`、`{id}/chunks` |
| `ChunkController` | `/chunks` | `{id}` 编辑片段与元数据（仅 reviewing 态）、删除 |
| `MirrorController` | `/mirror` | 最新画像、`generate`、`generate-monthly`、`stats`、`snapshots`、`snapshots/{id}`、`chat`（SSE）、会话管理 |
| `SettingsController` | `/settings` | 读写配置、`test-ai` / `test-embedding` / `test-db` |
| `SummaryController` | `/summaries` | 游标分页列表、`missing`、`backfill`、`regenerate` |
| `InspirationController` | `/inspiration` | 写作灵感 |
| `ExportController` | `/export` | `json` / `markdown`（只导出不导入） |
| `VaultController` | `/vault` | 上传、列表、下载、预览、删除、`{id}/confirm`、`search` |
| `GlossaryController` | `/glossary` | CRUD、`{id}/confirm`、`{id}/dismiss`、`extract` |
| `TodoController` | `/todos` | `pending-suggestions`、`suggestions/{id}/resolve`、列表、`open-chain`、删除 |

**对话 SSE 事件契约**（`POST /api/mirror/chat`）：`meta`（会话与路由，循环期带工具轨迹）/ `thinking`（规划器思考流）/ `delta`（回答增量）/ `sources`（引用来源）/ `done` / `error`。

Swagger UI 在 `/api/doc.html`。

## 数据库

PostgreSQL + pgvector，14 张表。DDL 见 `src/main/resources/db/schema.sql`（幂等，唯一权威基线，需 `CREATE EXTENSION vector`）。

核心模型决策：**Chunk 是唯一业务单元**。一条用户输入 = 一条 Record（只存原文与状态）；AI 拆出的每个语义片段 = 一个 Chunk，承载 segment 文本、元数据（JSONB）与向量（1024 维，HNSW 索引）。

| 表 | 存什么 |
|---|---|
| `users` / `user_settings` | 账号；用户 AI 配置（API Key AES-256-GCM 加密） |
| `records` | 日记原文（不可变）、状态机 `processing/reviewing/done/failed`、软删除 |
| `chunks` | **业务单元**：segment（唯一真源）、metadata JSONB、`embedding vector(1024)` |
| `profile_snapshots` | "镜子"快照：五维分析 + 总述 + 向量（用于漂移检测） |
| `chat_sessions` / `conversation_history` | 会话与消息（含 sources / tools_used 溯源） |
| `user_terms` | 个人词典（pending / confirmed / dismissed） |
| `vault_items` / `vault_blobs` | 文件资产与字节（存 PG 的 BYTEA，无对象存储） |
| `tool_calls` | 工具调用审计 |
| `todo_registry` / `todo_registry_links` / `todo_suggestions` | 跨日记待办追踪与建议 |

## 核心流程

### 记录管道（事件驱动、异步）

```
POST /records → 落库(status=processing) → 发 RecordCreatedEvent
   → @Async 执行 RecordPipeline
      → CleanProcessor    文本清洗
      → ClassifyProcessor gRPC Classify（拆分 + 分类，一次 LLM 调用）
   → 每个 segment 建一个 Chunk（此时不 embed）
```

向量化延迟到**审核确认**时——这是"AI 做整理，用户做决策"原则的落地：用户没确认的内容不进 RAG。

### 审核确认（`confirmReview`）

相对时间消解（只改 `segment`，`record.content` 与 `chunk.content` 原文全程不动）→ 未分类片段补分类 → 逐 chunk 向量化 → 待办登记 → `status=done`。

### 对话循环（工具调用）

```
POST /mirror/chat
  → ExtractIntent 路由（profile / structured / semantic / hybrid）
  → 四路检索
  → ToolOrchestrator 循环：每步调 PlanNextStep（思考流实时透传 SSE）
  → 带工具结果流式生成回答
```

**架构裁决**：LLM 决策在 Python，工具执行在 Java，循环状态（步数、累积结果、预算）在 Java，Python 严格无状态。任何失败/超时/解析错误都降级到已收集结果，再降级到纯 RAG——"零回归"。

9 个工具：`search_records` / `get_stats` / `get_profile` / `get_glossary` / `compare_snapshots` / `save_item` / `find_item` / `recall_item` / `get_coverage`。注册表在 `ToolRegistry`（自动收集所有 `ToolExecutor` bean），快照随请求发给 Python。

### 其他模块

- **词典**：从日记文本学个人指代（"论文 = 毕设 RAG 检索"）。仅 `confirmed` 词条参与注入（同审核门禁哲学），四处注入点：Classify / ExtractIntent / Chat / GenerateProfile。
- **资产 vault**：文件存 PG BYTEA（数据主权），20MB/文件、500MB/用户，magic bytes 类型白名单，SHA-256 去重。三档消化：文本/PDF/docx 全量、图片半量、音视频仅元数据。五态机 `pending → extracted → confirmed`（+ skipped / failed），确认是进入 embedding 的门禁。删除需提交文件名后四位（`X-Confirm-Name` 头）。
- **滑动镜子**：镜像是**累计**画像（上月镜子 + 本月增量），不是月度切片。四道防洪闸（chunks 数 / 字符数 / 单条截断 / 12 月摘要化）全部可配。漂移 = 相邻月度快照的余弦距离。
- **待办 registry**：跨日记追踪。真源是 `chunk.metadata.taskStatus`，registry 是物化索引，双写。LLM 提出的状态变更只落为建议，用户确认后才写 evidence 链接。
- **定时任务**：每日总结（`0 0 1 * * ?` Asia/Shanghai，回溯补 7 天，顺路跑词典抽取）、月度快照（`0 0 2 1 * ?`）。

## 快速开始

### 前置

- PostgreSQL（需 `vector` 扩展）
- Python AI 服务在 `grpc.client.ai-service.address`（dev 默认 `127.0.0.1:10003`）

### 运行

```bash
# 建库（首次）
psql -U postgres -c "CREATE DATABASE mu_mirror;"
psql -U postgres -d mu_mirror -f src/main/resources/db/schema.sql

# 启动（必须 dev profile：仓库没有 application-prod.yml 的完整配置，
# 默认 profile 是 prod，会因 jwt.secret 解析不到而启动失败）
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev     # Git Bash
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev   # Windows
```

服务监听 **9050**（prod profile 下为 10002），基础地址 `http://localhost:9050/api`。

### 配置

| 项 | 说明 |
|---|---|
| `spring.profiles.active` | 默认 `prod`；本地开发用 `dev` |
| `jwt.secret` / `jwt.expiration` | prod 默认空，须设 `JWT_SECRET` |
| `MUMIRROR_AES_KEY` | 密钥加密用；未设时回退内置开发密钥并打 WARN |
| `grpc.client.ai-service.address` | AI 服务地址 |
| `vault.chat-loop-enabled` | 对话循环开关（关闭则走旧 PlanTools 路径，可回滚） |

**用户 LLM / Embedding 配置不在 YAML 里**——存在 `user_settings` 表，随每次 gRPC 请求下发。

### 测试

```bash
./mvnw test     # 248 个测试，18 个测试类
```

绝大多数是 Mockito 纯单测。`MuMirrorBApplicationTests` 会起 Spring 上下文（继承 `prod` profile），需要可达的 PostgreSQL 与 gRPC 端。

## 部署

- `Jenkinsfile` — 单并发（2C2G 服务器），`MAVEN_OPTS=-Xmx512m`，默认 `-DskipTests`
- `deploy/backend-release.sh` — 时间戳目录发布 + DB 备份 + 幂等迁移 + 原子符号链接切换 + 重启 + 健康检查，失败自动回滚
- `deploy/systemd/mirror-backend.service` — `-Xmx384m`、`MemoryMax=520M`
- `deploy/nginx/mirror.conf` — 监听 10000，SPA 回退，`client_max_body_size 25m`

## 相关仓库

| 仓库 | 说明 |
|---|---|
| `Mu-mirror-AI` | Python gRPC AI 服务（proto 契约源头） |
| `Mu-mirror-F` | Vue 3 前端 |
| `coordination/` | 三仓协作文档；`shared-protocol.md` 是 proto 契约登记表，**改 proto 必须在此登记** |

## 文档

- `docs/2026-09-03-system-design-v2.md` — **系统唯一权威设计文档**（v2.3，15 章 + 裁决清单）
- `HANDOVER.md` — 交接文档
- `docs/coordination/` — 三仓协作与协议登记
  - `chat-loop-design.md` / `chat-loop-integration.md` — 对话循环设计与联调手册
  - `lexicon-design.md` / `rolling-mirror-design.md` / `toolcalling-vault-design.md` / `todo-status-removal-design.md`

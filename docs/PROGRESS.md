# Mu-mirror-B 当前现状（唯一维护文档）

> **最后核对：2026-11-11**
> 核对方式：逐项对照 `src/main/java`、`src/main/resources`、`src/test/java` 与当前工作树，**不沿用旧进度文档的结论**。
> 维护约定：项目只维护这一份"当前真相"。其他文档要么是专题说明，要么是历史决策记录；与代码冲突时，以代码为准，并回来更新本文件。
> 旧 `PROGRESS.md`（停留在 2026-08-13）与 `HANDOVER.md`（2026-09-06）不再作为进度依据。

---

## 1. 项目定位与系统边界

「AI 日记镜子」：用户随手写记录，AI 拆分、分类、打标签；用户审核确认后，片段进入 pgvector 检索与画像；对话时由 Agent 按需调用只读工具，再用有证据的材料回答。

```
Vue 3 前端（Mu-mirror-F）
        │ HTTP /api，JWT
        ▼
本仓 Mu-mirror-B（Spring Boot，端口 9050）
        │ SQL
        ├──────────────► PostgreSQL + pgvector
        │ gRPC
        ├── 10003 ────► Mu-mirror-AI legacy 推理（分类/Embedding/Chat/画像）
        └── 10004 ────► Mu-mirror-AI Agent v2（graph_v2 实验路径，默认关闭）
```

**分工原则**：Java 管数据、权限、业务状态与工具执行；Python 管纯推理，不连业务库。用户自己的 LLM / Embedding 配置存在 `user_settings`，随每次 gRPC 请求下发。

---

## 2. 技术栈与运行

| 层 | 事实 |
|---|---|
| Java | **17**（`pom.xml`） |
| 框架 | Spring Boot **3.5.16**、Spring Security、Spring Web |
| 持久层 | MyBatis-Plus **3.5.5**（注解式 Mapper，无 XML 业务 SQL）、Druid 1.2.25 |
| 数据库 | PostgreSQL + pgvector 0.1.6（向量 1024 维，HNSW cosine） |
| gRPC | 1.68.0（netty-shaded）、protobuf 3.25.5 |
| 认证 | JWT（jjwt 0.12.3），24 小时 |
| 文档 | springdoc-openapi 2.9.0 + Knife4j 4.4.0，`/api/doc.html` |
| 文件解析 | PDFBox 2.0.29、POI 4.1.2 |
| Redis | **未使用**，旧配置里的 Redis 段落是历史残留 |

**运行事实**

- 端口 `9050`，上下文路径 `/api`。
- 默认 profile 是 `prod`；仓库没有完整的 `application-prod.yml`，本地必须用：
  `mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev`
- dev 数据源：`jdbc:postgresql://localhost:5432/mu_mirror`，时区 `Asia/Shanghai`。
- 旧 AI 服务默认 `127.0.0.1:10003`；Agent v2 默认 `localhost:10004`。
- 新库执行 `src/main/resources/db/schema.sql`（已包含 V16 最终结构）。
- 已有库按 `migration-v2.sql` → `v3` → … → `v16` 顺序升级；`schema.sql` 不能替代存量迁移。

**测试**

```powershell
.\mvnw.cmd test                       # 大多数是 Mockito 单测
.\mvnw.cmd -DskipTests compile        # 只编译
```

需要真实 PostgreSQL 的测试会读 `MIRROR_TEST_DB_URL` / `MIRROR_TEST_DB_USERNAME` / `MIRROR_TEST_DB_PASSWORD`。未配置时部分测试会跳过；**跳过不能当作通过**。成就测试还要求库名匹配 `codex_assistant_growth_test_*` 并在随机 schema 中执行。

---

## 3. 模块状态（以代码为准）

| 模块 | 状态 | 代码入口 / 说明 |
|---|---|---|
| 认证 | ✅ | `AuthController`，注册/登录/JWT/`me` |
| 记录与片段 Chunk | ✅ | `RecordController`；事件管道 `pipeline/`；审核、拆分、重试、日历、片段编辑、异步向量化 |
| 用户设置 | ✅ | `SettingsController`；`user_settings`；API Key AES-256-GCM 加密、脱敏；`test-ai` / `test-embedding` / `test-db` |
| 模型连接档案 | ✅ | `ModelProfileController`（同 `/settings` 前缀）；`user_model_profiles`；保存与"应用"分离 |
| 镜子 / 画像 | ✅ | `MirrorController`、`MirrorServiceImpl`；累计画像、月度快照、漂移、来源 lineage、归档分页 |
| 对话 / Agent | ✅（双引擎） | `MirrorController` `/chat`、`AgentRunService`、`ChatServiceImpl`、`ToolOrchestrator`；`legacy` 默认，`graph_v2` 按用户灰度，`shadow` 影子采样 |
| 会话与每日总结 | ✅ | `chat_sessions`、`chat_day_summaries`；按日会话、结束日总结、失败重试 |
| 聊天记忆与遗忘 | ✅ | `chat_memories`、`chat_source_exclusions`；最多注入 5 条相关记忆；遗忘通过操作卡执行，历史原文仍可查但不再进入后续上下文 |
| 操作卡 / 助手动作 | ✅ | `AssistantActionController`；`assistant_actions`；准备→编辑→确认/取消，幂等与版本冲突 |
| 每日总结 | ✅ | `SummaryController`；01:00 Asia/Shanghai 定时，分页、缺失、回填、重生成 |
| 写作灵感 | ✅ | `InspirationController` |
| 日历导航 | ✅ | `GET /records/calendar`，按上海时区统计 |
| 数据导出 | ✅ | `ExportController`；JSON / Markdown，只导出不导入，排除向量 |
| 资产 Vault | ✅ | `VaultController`、`VaultService`、`DigestService`；PG BYTEA、magic bytes 白名单、配额、五态机、确认后才进入检索 |
| 个人词典 | ✅ | `GlossaryController`；候选/确认/忽略/抽取；仅 confirmed 词条注入 |
| 待办 Todo | ✅ | `TodoController`；registry 物化索引 + chunk.metadata 真源双写；建议需用户确认 |
| 五维成长 | ✅ | `GrowthController`；成长账本、维度锁定、手动贡献、来源追溯 |
| 塔罗 | ✅ | `TarotController`；78 张牌 v2 目录、每日抽牌、问题牌、分析版本、收藏、偏好 |
| 成就 | 🚧 第一批已进代码，未提交 | `achievement/`、`AchievementController`、`catalog.json`（**56 枚**：原 34 + 第一批 22），V16 迁移；第二至四批按 `docs/achievement-implementation-plan.md` 待实现 |
| 前端 | 独立仓 | `Mu-mirror-F`（Vue 3） |
| 部署 | ✅ | `Jenkinsfile`、`deploy/backend-release.sh`、systemd、nginx 配置 |

**当前工作树特殊状态（2026-11-11）**：成就系统整体尚未提交（`git status` 中为 `??`）；`README.md`、`ChatServiceImpl`、`GlossaryServiceImpl`、`ModelProfileService`、`ExportController`、`schema.sql` 和两个测试有未提交修改。做任何发布前先确认这批改动是否完整、是否应拆成独立提交。

---

## 4. REST API 清单（19 个控制器）

统一响应 `R<T>`：`{code, message, data, timestamp}`，`code == 200` 为成功。以下只列路径与用途，详细 DTO 以 Controller 注解和 `/api/doc.html` 为准。

| 控制器 | 基础路径 | 端点 |
|---|---|---|
| `AuthController` | `/auth` | `GET /status`、`POST /register`、`POST /login`、`GET /me` |
| `RecordController` | `/records` | `POST /`、`POST /{id}/organize`、`PUT /{id}/content`、`GET /`、`GET /{id}`、`PUT /{id}/confirm`、`GET /{id}/suggestions`、`POST /{id}/retry`、`DELETE /{id}`、`GET /calendar`、`POST /{id}/chunks` |
| `ChunkController` | `/chunks` | `PUT /{id}`、`POST /{id}/embedding/retry`、`DELETE /{id}` |
| `MirrorController` | `/mirror` | `GET /`、`POST /generate`、`POST /generate-monthly`、`GET /stats`、`GET /snapshots`、`GET /snapshot-months`、`GET /snapshots/{id}`、**`POST /chat`（SSE）**、`GET /sessions`、`GET /sessions/today`、`POST /sessions/today`、`POST /sessions/{id}/summary/retry`、`GET /sessions/{id}`、`DELETE /sessions/{id}` |
| `AgentRunController` | `/mirror/runs` | `GET /{runId}`（断线重连/幂等恢复读取） |
| `SettingsController` | `/settings` | `GET /`、`PUT /`、`POST /test-ai`、`POST /test-embedding`、`POST /test-db` |
| `ModelProfileController` | `/settings` | `GET/POST /model-profiles`、`PUT/DELETE /model-profiles/{id}`、`POST /model-profiles/{id}/activate`、`POST /test-ai/draft` |
| `SummaryController` | `/summaries` | `GET /`、`GET /missing`、`POST /backfill`、`POST /regenerate` |
| `InspirationController` | `/inspiration` | `POST /` |
| `ExportController` | `/export` | `GET /json`、`GET /markdown` |
| `VaultController` | `/vault` | `POST /upload`、`GET /`、`GET /{id}/download`、`GET /{id}/preview`、`DELETE /{id}`、`POST /{id}/confirm`、`PUT /{id}`、`GET /search` |
| `GlossaryController` | `/glossary` | `GET /`、`POST /`、`PUT /{id}`、`DELETE /{id}`、`POST /{id}/confirm`、`POST /{id}/dismiss`、`POST /extract` |
| `TodoController` | `/todos` | `POST /`、`PATCH /{id}`、`GET /pending-suggestions`、`POST /suggestions/{id}/resolve`、`GET /`、`GET /open-chain`、`DELETE /{id}` |
| `AssistantActionController` | `/assistant/actions` | `POST /prepare`、`PATCH /{id}`、`POST /{id}/confirm`、`POST /{id}/cancel`、`GET /{id}`、`GET /?sessionId=`、`GET /records` |
| `ChatMemoryController` | `/chat-memories` | `GET /`、`GET /{id}`（遗忘不直连 HTTP，走操作卡） |
| `GrowthController` | `/growth` | `GET /`、`GET /axes`、`PUT /axes`、`GET /entries`、`POST /manual` |
| `TarotController` | `/tarot` | `GET /cards`、`GET /history`、`GET /daily`、`POST /daily/draw`、`GET /daily/requests/{id}`、`POST /readings`、`GET /readings/latest`、`GET /readings/{id}`、`POST /readings/{id}/analyses`、`GET /analyses/{id}`、`GET /readings/{id}/analyses`、`GET /collection`、`GET/PUT /preferences` |
| `AchievementController` | `/achievements` | `GET /`、`GET /pending`、`POST /acknowledge`、`GET/PUT /showcase`、`POST /activity` |
| `AgentCommandController` | `/internal/agent/commands` | `POST /create-record`、`GET /receipts/{callId}`（内部服务令牌 + HMAC，不是普通 JWT 接口） |

---

## 5. 对话功能：真实链路（重点）

### 5.1 入口与请求

`POST /api/mirror/chat`，`SseEmitter`，超时取 `mirror.sse-timeout-ms`（默认 600000 ms）。

请求体 `ChatRequestDTO`：

- `question`：必填，非空。
- `sessionId`：可空；为空时解析/创建**当天**会话。
- `requestId`：客户端幂等 ID；重连/重试必须复用，用户重新提问要生成新值。

响应不是一次性 JSON，而是 SSE 事件流。**两个引擎共用同一套事件名**：

| 事件 | 用途 |
|---|---|
| `meta` | 会话、run、request、engine、route、累积 `tools_used`、`sourceMessageId` |
| `thinking` | 规划器/模型的思考增量（解决"黑屏等待"） |
| `delta` | 回答正文增量 |
| `sources` | 引用的日记来源 |
| `vault_refs` | 引用的资产文件卡 |
| `action_proposal` / `action_status` | 操作卡准备/状态变化 |
| `done` | 结束：sessionId、runId、requestId、engine、status、outcome、route、fallback、created_records、stop_reason、error_code |
| `error` | 失败：code、partial、message |

### 5.2 请求接纳（两个引擎共用）

`AgentRunService.startOrFindRun()` 在一个事务里完成：

1. 用 `requestId` + `requestHash(question, sessionId)` 做幂等；同 ID 不同问题返回冲突。
2. `DailyChatSessionResolver` 解析或创建当天会话（`chat_sessions.user_id + day_key` 唯一）。
3. 插入 `agent_runs`（status=`running`，engine=`legacy` / `graph_v2`，lease 默认 30 秒）。
4. 插入**一条 user 消息**到 `conversation_history`，并回填 `agent_runs.user_message_id`。
5. 构建 Java 侧可信 `AssistantContext`（角色语气、当前日期/时区、操作卡状态摘要、最多 5 条相关聊天记忆；塔罗上下文按需附加）。

随后 `dispatch()` 异步执行。控制器在 SSE 关闭/超时/报错时会请求取消，避免孤儿 run。断线后可用 `GET /mirror/runs/{runId}` 读取已落库状态。

### 5.3 引擎选择

`chat.engine` 决定本轮走哪条路：

| 值 | 行为 |
|---|---|
| `legacy`（默认） | 走 Java 侧 `ChatServiceImpl.runLegacy()`；用户可见 |
| `graph_v2` | 仅 `chat.graph-enabled-user-ids` 白名单（支持 `*`）命中时走 Python Agent v2；否则仍走 legacy |
| `shadow` | 用户仍走 legacy；按 `shadow-sample-rate` 抽样在后台跑 graph_v2，影子 run 不产生用户可见副作用 |

`chat.create-record-enabled` 默认 `false`，控制 graph_v2 是否能通过内部命令创建记录。

### 5.4 legacy 引擎

`ChatServiceImpl.runLegacy()` 内有两条子路径，由 `vault.chat-loop-enabled` 切换：

**A. 循环路径（默认 `true`）**

```
问题
 → 不预先跑 ExtractIntent / RAG
 → ToolOrchestrator.loopAndExecute()
     每步：Python PlanNextStep 流式返回 thinking + calls
     Java 执行工具（审计 tool_calls）
     thinking 逐块推 SSE；每步推累积 meta.tools_used
     终止：done、calls 为空、步数 > 4、累计预算 > 180s、
           同工具同参数指纹重复、流式中断/解析失败
 → 始终进入 Chat 流式回答（即使没有任何工具结果）
 → ChatFinalResult → AgentRunService.finish() 统一落库
```

关键事实：

- "材料够不够"由模型每步判断，不再由检索完那一刻一次性判死。
- 工具失败/循环失败只提前退出并带回已有结果；不会把整轮对话打挂。
- 正常收束时若只执行过 `find_item`、没执行过 `recall_item`，会补读一次正文并打 `[auto-backstop]` 审计标记。
- 回答历史窗口 `HISTORY_ROUNDS = 3`；规划器历史窗口 `vault.plan-history-rounds = 6`。两个窗口不同是**有意设计**：答案侧怕模型顺着上文措辞跑偏，规划侧需要更多指代消解。
- 即使 `chunks` 和工具结果都为空，也照常调用模型；兜底文案只在 AI 失败/流为空时触发。
- 兜底文案分三档：零记录新用户 / 有记录但没匹配上 / 情绪倾诉；兜底消息写 `is_fallback=true`，后续轮次不进入历史上下文。

**B. 单次规划回滚路径（`false`）**

先 `ExtractIntent` 路由 → 四路检索 → `PlanTools` 单次规划（最多 2 步）→ 回答。行为与改造前一致，用于一键回滚。

### 5.5 graph_v2 引擎

```
Java AgentRunService
 → AgentGrpcClient.buildRequest()
     question、session、run、userMessageId、
     AssistantContext、用户 LLM/Embedding 配置、
     referenceDate、timezone、maxPlanRounds=4、
     totalBudgetMs=480000、loopBudgetMs=180000、halfLife=30
 → gRPC 10004，Python Agent 流式返回 AgentEvent
 → AgentEventAdapter 校验：
     run_id 必须一致；seq 必须连续；同一 call_id 不得重复；
     终态后不得再收事件；影子 run 不允许 prepare_action
 → thinking / delta / meta 直接转发 SSE
 → tool_finished：若 Python 没审计，Java 补写 tool_calls
 → prepare_action：Java 执行 prepare_* 工具，生成操作卡
 → completed / failed：生成 ChatFinalResult
 → AgentRunService.finish() 统一落库并发 done
```

信任边界：

- Python 通过**只读** `mirror_ai` 视图读业务数据；不直连业务表，也不直接写业务状态。
- 写操作必须回到 Java 的 `prepare_*` 工具，生成待用户确认的操作卡。
- 内部命令入口 `/internal/agent/commands/*` 需要内部令牌 + HMAC 授权，不是普通用户接口。
- 只要 graph 路径失败，**不会自动重跑 legacy**；本轮按失败/部分结果收尾。

### 5.6 工具注册表（14 只）

`ToolRegistry` 自动收集所有 `ToolExecutor` Bean，启动时校验名称唯一、effect 声明一致。

**只读（10）**：`search_records`、`get_stats`、`get_profile`、`get_glossary`、`compare_snapshots`、`find_item`、`recall_item`、`get_coverage`、`list_todos`、`save_item`。

**准备写（4）**：`prepare_record`、`prepare_todo`、`prepare_memory_change`、`prepare_growth_adjustment`。

graph_v2 只暴露 `PREPARE_WRITE` 工具给 Python（`ToolRegistry.prepareWriteProtoSpecs()`）；legacy 循环暴露完整注册表。

### 5.7 会话、每日总结与记忆

- **按日会话**：`chat_sessions.day_key` + `timezone`，同一用户同一天唯一。`GET /mirror/sessions/today` 只读；`POST` 幂等创建。`today()` 建的标题是"今日对话"占位，首条真实问题到达后替换一次。
- **每日总结**：`chat_day_summaries` 按结束日生成；失败可 `POST /mirror/sessions/{id}/summary/retry`。总结不阻塞当天聊天。
- **聊天记忆**：`chat_memories` 从对话中抽取（`ACTIVE`、可过期、有版本、记录来源 message ids）。注入时按问题词相关性排序，最多 5 条；只有授权来源可进入上下文。
- **遗忘**：不提供直接删除 HTTP 端点；由 `MemoryActionHandler` 执行操作卡，写入 `chat_source_exclusions` 墓碑。被遗忘消息的**原始历史仍可查看**，但不会进入后续模型上下文；墓碑只存来源 ID，不复制正文。
- **来源 lineage**：assistant 消息保存 `context_source_message_ids`；镜像快照有 `chat_lineage_known`，迁移来的旧快照在未重建前视为 lineage 不完整。

### 5.8 统一落库出口

无论哪个引擎，assistant 消息、来源、工具轨迹、文件引用、创建记录、run 终态、会话更新时间都由 `AgentRunService.finish()` 在一个事务里提交。这样"模型输出"和"持久化事实"不会各写一套。

---

## 6. 数据模型

`schema.sql` 当前有 **39 张表 + 8 个只读视图**。按用途分组：

| 组 | 表 |
|---|---|
| 账号与配置 | `users`、`user_settings`、`user_model_profiles` |
| 记录与片段 | `records`、`chunks`、`chunk_embedding_jobs` |
| 画像 | `profile_snapshots`、`snapshot_inputs` |
| 对话与 Agent | `chat_sessions`、`conversation_history`、`agent_runs`、`agent_command_receipts`、`tool_calls` |
| 记忆与总结 | `chat_day_summaries`、`chat_memories`、`chat_source_exclusions` |
| 词典 | `user_terms` |
| 资产 | `vault_items`、`vault_blobs` |
| 待办与操作卡 | `todo_registry`、`todo_registry_links`、`todo_suggestions`、`assistant_actions` |
| 成长 | `growth_axes`、`growth_entries`、`growth_axis_history`、`growth_entry_history` |
| 塔罗 | `tarot_daily_draw`、`tarot_daily_request`、`tarot_collection`、`tarot_preferences`、`tarot_reading`、`tarot_analysis`、`tarot_chat_state` |
| 成就 | `achievement_definition`、`achievement_event`、`user_activity_fact`、`user_achievement`、`user_achievement_showcase` |

`mirror_ai.*` 的 8 个视图：`record_chunks`、`glossary_terms`、`chat_history`、`profiles`、`vault_metadata`、`vault_content`、`vault_keys`、`todos`。它们是 Python Agent v2 的只读数据边界，带 `security_barrier`。

**迁移顺序**（已有库）：`v2` → `v3`（记录 AI 授权）→ `v4`（镜像归档/lineage）→ `v5`（异步片段向量化）→ `v6`（assistant actions）→ `v7`（待办直接操作）→ `v8`（记录记忆状态）→ `v9`（成长）→ `v10`（每日记忆）→ `v11`（Agent v2 视图/run/receipt）→ `v12`（塔罗）→ `v13`（plan→todo）→ `v14`（塔罗 v2）→ `v15`（模型连接档案）→ `v16`（成就）。

新库直接跑 `schema.sql`；它已包含 V16，不要再逐份重放。`chunks.sql` 是早期建表脚本，已被 `schema.sql` 覆盖。

---

## 7. 关键配置开关

| 配置 | 默认 | 作用 |
|---|---|---|
| `chat.engine` | `legacy` | 对话引擎：legacy / graph_v2 / shadow |
| `chat.graph-enabled-user-ids` | 空 | graph_v2 用户白名单，`*` 为全量 |
| `chat.shadow-sample-rate` | `0.0` | 影子采样率 |
| `chat.create-record-enabled` | `false` | graph_v2 是否可创建记录 |
| `agent.grpc-address` | `localhost:10004` | Agent v2 gRPC |
| `agent.internal-token` / `command-hmac-key` | 空 | 内部命令鉴权，生产必须注入 |
| `agent.total-budget-ms` | `480000` | graph 单轮总预算 |
| `agent.loop-budget-ms` | `180000` | graph 循环预算 |
| `agent.max-plan-rounds` | `4` | graph 规划轮数 |
| `agent.lease-seconds` / `heartbeat-seconds` | `30` / `10` | run 租约与心跳 |
| `vault.chat-loop-enabled` | `true` | legacy 内循环开关；false 回滚到单次 PlanTools |
| `vault.max-loop-steps` | `4` | legacy 循环步数上限 |
| `vault.loop-budget-ms` | `180000` | legacy 循环累计预算 |
| `vault.plan-tools-timeout-ms` | `120000` | 单步规划超时，必须小于 SSE 超时 |
| `vault.plan-history-rounds` | `6` | 规划器历史窗口 |
| `mirror.sse-timeout-ms` | `600000` | SSE 连接超时 |
| `mirror.rag-max-cosine-distance` | `0.35` | 语义检索阈值（有意保守） |
| `mirror.lookback-*` / `per-chunk-max-chars` | 600 / 150000 / 2000 | 镜子四道防洪闸 |
| `review.auto-enabled` | `false` | 自动审核模式 |
| `mirror.achievements.worker-delay-ms` | `2000` | 成就事件消费间隔 |

---

## 8. 测试地图

| 范围 | 代表测试 |
|---|---|
| 对话主链路 | `ChatRetrievalTest`、`ChatLoopTest`、`ChatSessionDeletionTest`、`ChatInvocationTest` |
| Agent v2 | `AgentRunServiceTest`、`AgentEventAdapterTest`、`AgentCommandAuthTest`、`AgentCommandServiceTest`、`AgentLifecycleTest`、`AgentGrpcClientContextTest`、`AgentShadowRequestTest` |
| 工具 | `ToolOrchestratorTest`、`ToolsTest`、`PrepareWriteToolRegistryTest`、`PreparedActionReplyTest`、`SearchRecordsToolRoutingTest` |
| 会话/记忆 | `DailyChatSessionTest`、`DailyChatSessionDatabaseTest`、`ChatMemoryServiceTest`、`ChatMemoryForgetTest`、`MemoryCandidateCapTest` |
| 画像/归档 | `MirrorStatsTest`、`MirrorLineageReplayDatabaseTest`、`MirrorArchiveControllerTest` |
| 记录/审核 | `ConfirmReviewTest`、`RecordAuthorizationGateTest`、`RecordAiAuthorizationDatabaseTest`、`ChunkEditInvalidationTest` |
| 资产/词典 | `VaultServiceTest`、`DigestServiceTest`、`VaultRefsParseTest`、`GlossaryServiceTest` |
| 成长/塔罗/成就 | `GrowthServiceTest`、`GrowthLedgerDatabaseTest`、`Tarot*DatabaseTest`、`AchievementDatabaseTest` |
| 模型配置 | `ModelProfileServiceTest`、`ModelProfileDatabaseTest`、`SettingsServiceImplTest` |

---

## 9. 文档地图（本文件之外，均非当前进度依据）

**仍可用的专题/运维文档**

- `README.md`：项目概览与快速开始。注意其中的控制器数、工具数、表数、迁移描述已过时，**以本文件为准**。
- `docs/agent-v2-rollout.md`：Agent v2 的数据库准备、灰度顺序、回滚闸。
- `docs/tarot-operations.md`、`docs/model-profiles.md`、`docs/achievements-operations.md`、`docs/decisions-achievements.md`：各自模块的发布与维护说明。
- `docs/achievement-implementation-plan.md`：成就四批计划（代码已进第一批，文档状态需要随本文件更新）。
- `docs/superpowers/specs/` 与 `reports/`：2026-10 的设计与验收记录，属于决策证据，不是当前状态。

**历史参考（不要据此判断现状）**

- `HANDOVER.md`（2026-09-06）：Java 版本、profile、AI 端口、工具数、表数、目录结构均已过时。
- `docs/2026-09-03-system-design-v2.md`（v2.3，2026-09-06）：v2.3 基线的权威设计，但早于 Agent v2、塔罗、成长、模型档案、成就、聊天记忆。
- `docs/coordination/*`（2026-09）：多 Agent sprint 文档，其中 `PROGRESS-BOARD.md` 停留在 2026-09-06。
- `docs/archive/*`：更早的设计稿，已明确归档。

---

## 10. 夺回掌控路线（从对话功能开始）

### 阶段 0：止血与建立基线（半天）

1. 确认当前工作树：成就系统未提交；先把这批改动单独提交或至少建分支/备份，避免后续修改把它冲掉。
2. 跑一次 `.\mvnw.cmd -DskipTests compile`；有数据库时再跑 `.\mvnw.cmd test`，把失败分成"代码问题 / 环境缺失 / 已知跳过"三类记录在本文件。
3. 从今天起只维护本文件；每完成一次代码改动，当天更新"模块状态"和"对话链路"两节。

### 阶段 1：读懂对话，不写代码（2–3 天）

按这个顺序读，每读完一个文件，用自己的话写 3 行摘要到本文件或笔记：

1. `MirrorController.chat()` → `AgentRunService.startOrFindRun()` / `dispatch()`：请求如何被接纳、幂等、租约、会话如何确定。
2. `AgentRunService.runGraph()` + `AgentEventAdapter`：graph 事件如何校验、转发、落库。
3. `ChatServiceImpl.runLegacy()` + `streamAnswer()`：legacy 两条子路径、兜底分档、sources/vault_refs 如何产生。
4. `ToolOrchestrator.loopAndExecute()`：循环终止条件、审计、自动补读。
5. `ToolRegistry` + `tools/impl/*`：14 只工具各自读/写什么（先读 `ToolDefinition`，再读执行体）。
6. `DailyChatSessionResolver` + `ChatMemoryServiceImpl` + `ChatService`：每日会话、结束日总结、记忆注入与遗忘。

产出：一张自己画的时序图（前端 → Controller → AgentRunService → 引擎 → Python → 落库 → SSE），以及一张"分支矩阵"（legacy 循环 / legacy 单次 / graph_v2 / shadow 各自何时触发、失败怎么办）。

### 阶段 2：亲手跑通，建立验证肌肉（3–5 天）

准备：本地 PostgreSQL（含 vector 扩展）、Python legacy AI（10003）；graph_v2 需要 Agent 服务（10004）和只读 DSN。

按顺序验证并记录看哪些表/日志：

1. 新用户第一问：零记录时的回答与兜底分档。
2. 普通记录查询：`meta` 的 `tools_used`、`tool_calls` 行、`conversation_history` 的 assistant 行。
3. 语义查询：`chunks.embedding` 命中、`sources` 事件。
4. 文件查询：`find_item` → `recall_item`、`vault_refs` 文件卡。
5. 操作卡：`action_proposal` → 确认 → `assistant_actions` 状态变化。
6. 幂等/断线：同 `requestId` 重复请求、`GET /mirror/runs/{runId}` 恢复。

每跑通一个场景，在本文件第 5 节补一句"我实际观察到的现象"。**跑不通的不要标通过**。

### 阶段 3：接管规则（长期）

- 一次只改一个可解释的改动；每个 AI 生成的 diff 必须能用一句话说清"为什么这样改"。
- 每个改动必须同时有：测试（或明确说明为什么不能测）、回滚方式、对应文档更新。
- 在对话链路稳定前，不新增模块、不扩大 Agent 权限、不默认开启 graph_v2。
- 每周做一次"未知清单"复盘：还有哪些分支、配置、表、外部依赖说不清；只挑一个消灭。

### 阶段 4：做一次明确的架构裁决（对话稳定后）

三选一，不要悬着：

1. **继续灰度 graph_v2**：补齐 `docs/agent-v2-rollout.md` 要求的权限、幂等、延迟和答案质量测量，再决定默认值。
2. **长期保持 legacy 默认**：把 graph_v2 标为实验分支，明确维护成本，避免两套逻辑同时演进。
3. **移除 graph_v2**：在确认 legacy 满足需求后，删代码、删视图、删配置，减少认知负担。

### 验收标准（做到这些才算"拿回来"）

- 不看代码能白板画出对话主链路，并说清四个分支的触发条件与失败策略。
- 任意一轮对话出问题，能从 SSE 事件、`agent_runs`、`conversation_history`、`tool_calls`、`chunks` 定位到具体阶段。
- 能独立复现并验证第 5 节的 6 个场景，不依赖"AI 说它好了"。
- 每个模块都有一份"已知 / 未知 / 待验证"清单，且本文件是最新的。
- 任何新改动都能由你解释、测试、回滚。

---

## 11. 已知风险与未验证项（2026-11-11）

1. **工作树未提交**：成就系统整包、V16 迁移、目录 JSON、测试和若干修改仍在工作树中；发布前必须整理成可回滚的提交。
2. **graph_v2 未完成小流量验证**：`docs/agent-v2-rollout.md` 明确写着尚无小流量灰度、延迟样本和配对答案评分；默认值仍是 `legacy`。
3. **前端浏览器验收缺口**：助手成长改造验收报告把 R5 双端呈现标为"部分通过"，真实浏览器全流程未完成。
4. **旧文档持续漂移**：README 仍写着 11 个控制器、9 个工具、14 张表；系统设计 v2.3 停在 2026-09-06。除本文件外不要拿它们判断现状。
5. **测试依赖环境**：`MuMirrorBApplicationTests` 需要可达的 PostgreSQL；数据库测试未配置环境变量时会跳过，跳过不等于通过。
6. **成就计划文档状态滞后**：计划文档写"第一批实施中"，但 `catalog.json` 已有 56 枚定义；需要核对第一批测试、迁移和发布是否真的完成。
7. **双引擎并存成本**：legacy 循环、legacy 单次规划、graph_v2、shadow 四套路径同时存在，任何对话行为变更都要确认影响面。

---

## 12. 变更记录

| 日期 | 事项 |
|---|---|
| 2026-11-11 | 以代码为准重写本文件；取代 2026-08-13 版进度文档；补充 Agent v2、成就、塔罗、成长、模型档案、聊天记忆等缺失模块；加入接管路线与已知风险 |

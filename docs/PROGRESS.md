# Mu-mirror-B 当前现状（唯一维护文档）

> **最后核对：2026-11-13**
> 核对方式：逐项对照 `src/main/java`、`src/main/resources`、`src/test/java` 与当前工作树，**不沿用旧进度文档的结论**。
> 维护约定：项目只维护这一份"当前真相"。其他文档要么是专题说明，要么是历史决策记录；与代码冲突时，以代码为准，并回来更新本文件。
> 旧进度文档与 `docs/archive/HANDOVER-2026-09-06.md`不再作为进度依据。

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
- 新库执行 `src/main/resources/db/schema.sql`（已包含 V18 最终结构）。
- 已有库按 `migration-v2.sql` → `v3` → … → `v18` 顺序升级；`schema.sql` 不能替代存量迁移。

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
| 记录与片段 Chunk | ✅ | `RecordController`；事件管道 `pipeline/`；审核、拆分、重试、日历、片段编辑；version 1 独立记录授权与逐片段向量索引，未选索引的授权正文仍可读、可结构化查询 |
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
| 成就 | ✅ 四批实现与数据库验收完成 | 96 枚普通章、24 项隐藏镀层；V17、结构化可验证行为、独立通知和账号展架；升级见 `achievements-operations.md` |
| 前端 | 独立仓 | `Mu-mirror-F`（Vue 3） |
| 部署 | ✅ | `Jenkinsfile`、`deploy/backend-release.sh`、systemd、nginx 配置 |

**本轮状态（2026-11-13）**：`ai` 分支已实施记录选择性索引的 AI、后端与前端适配。`legacy` 继续保留且没有自动切换对话引擎。完整后端测试、AI 全量测试、前端全量测试/构建、隔离浏览器流程、合成样例上的真实模型分类复核及 legacy/graph_v2 各 8 问真实聊天矩阵均已完成；目标运行环境配置与生产迁移仍未验证。部署目标必须按顺序应用 V18，不能把隔离测试库迁移当作生产已升级。

---

## 4. 对话功能：真实链路（重点）

### 4.1 入口与请求

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

### 4.2 请求接纳（两个引擎共用）

`AgentRunService.startOrFindRun()` 在一个事务里完成：

1. 用 `requestId` + `requestHash(question, sessionId)` 做幂等；同 ID 不同问题返回冲突。
2. `DailyChatSessionResolver` 解析或创建当天会话（`chat_sessions.user_id + day_key` 唯一）。
3. 插入 `agent_runs`（status=`running`，engine=`legacy` / `graph_v2`，lease 默认 30 秒）。
4. 插入**一条 user 消息**到 `conversation_history`，并回填 `agent_runs.user_message_id`。
5. 构建 Java 侧可信 `AssistantContext`（角色语气、当前日期/时区、操作卡状态摘要、最多 5 条相关聊天记忆；塔罗上下文按需附加）。

随后 `dispatch()` 异步执行。控制器在 SSE 关闭/超时/报错时会请求取消，避免孤儿 run。断线后可用 `GET /mirror/runs/{runId}` 读取已落库状态。

### 4.3 引擎选择

`chat.engine` 决定本轮走哪条路：

| 值 | 行为 |
|---|---|
| `legacy`（默认） | 走 Java 侧 `ChatServiceImpl.runLegacy()`；用户可见 |
| `graph_v2` | 仅 `chat.graph-enabled-user-ids` 白名单（支持 `*`）命中时走 Python Agent v2；否则仍走 legacy |
| `shadow` | 用户仍走 legacy；按 `shadow-sample-rate` 抽样在后台跑 graph_v2，影子 run 不产生用户可见副作用 |

`chat.create-record-enabled` 默认 `false`，控制 graph_v2 是否能通过内部命令创建记录。

### 4.4 legacy 引擎

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

### 4.5 graph_v2 引擎

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

### 4.6 工具注册表（14 只）

`ToolRegistry` 自动收集所有 `ToolExecutor` Bean，启动时校验名称唯一、effect 声明一致。

**只读（10）**：`search_records`、`get_stats`、`get_profile`、`get_glossary`、`compare_snapshots`、`find_item`、`recall_item`、`get_coverage`、`list_todos`、`save_item`。

**准备写（4）**：`prepare_record`、`prepare_todo`、`prepare_memory_change`、`prepare_growth_adjustment`。

graph_v2 只暴露 `PREPARE_WRITE` 工具给 Python（`ToolRegistry.prepareWriteProtoSpecs()`）；legacy 循环暴露完整注册表。

### 4.7 会话、每日总结与记忆

- **按日会话**：`chat_sessions.day_key` + `timezone`，同一用户同一天唯一。`GET /mirror/sessions/today` 只读；`POST` 幂等创建。`today()` 建的标题是"今日对话"占位，首条真实问题到达后替换一次。
- **每日总结**：`chat_day_summaries` 按结束日生成；失败可 `POST /mirror/sessions/{id}/summary/retry`。总结不阻塞当天聊天。
- **聊天记忆**：`chat_memories` 从对话中抽取（`ACTIVE`、可过期、有版本、记录来源 message ids）。注入时按问题词相关性排序，最多 5 条；只有授权来源可进入上下文。
- **遗忘**：不提供直接删除 HTTP 端点；由 `MemoryActionHandler` 执行操作卡，写入 `chat_source_exclusions` 墓碑。被遗忘消息的**原始历史仍可查看**，但不会进入后续模型上下文；墓碑只存来源 ID，不复制正文。
- **来源 lineage**：assistant 消息保存 `context_source_message_ids`；镜像快照有 `chat_lineage_known`，迁移来的旧快照在未重建前视为 lineage 不完整。

### 4.8 统一落库出口

无论哪个引擎，assistant 消息、来源、工具轨迹、文件引用、创建记录、run 终态、会话更新时间都由 `AgentRunService.finish()` 在一个事务里提交。这样"模型输出"和"持久化事实"不会各写一套。

---

## 5. 数据模型

`schema.sql` 当前有 **40 张表 + 8 个只读视图**。按用途分组：

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
| 成就 | `achievement_definition`、`achievement_event`、`user_activity_fact`、`user_achievement_activity`、`user_achievement`、`user_achievement_showcase` |

`mirror_ai.*` 的 8 个视图：`record_chunks`、`glossary_terms`、`chat_history`、`profiles`、`vault_metadata`、`vault_content`、`vault_keys`、`todos`。它们是 Python Agent v2 的只读数据边界，带 `security_barrier`。

**迁移顺序**（已有库）：`v2` → `v3`（记录 AI 授权）→ `v4`（镜像归档/lineage）→ `v5`（异步片段向量化）→ `v6`（assistant actions）→ `v7`（待办直接操作）→ `v8`（记录记忆状态）→ `v9`（成长）→ `v10`（每日记忆）→ `v11`（Agent v2 视图/run/receipt）→ `v12`（塔罗）→ `v13`（plan→todo）→ `v14`（塔罗 v2）→ `v15`（模型连接档案）→ `v16`（成就基础）→ `v17`（成就行为与镀层）→ `v18`（选择性索引与只读 Agent 视图）。

新库直接跑 `schema.sql`；它已包含 V18，不要再逐份重放。`chunks.sql` 是早期建表脚本，已被 `schema.sql` 覆盖。

---

## 6. 文档索引

当前文档、模块运维说明、归档与维护规则见 [docs/README.md](README.md)。本文件只保留当前状态，历史设计与一次性记录在 `docs/archive/`。

## 7. 已知风险与未验证项（2026-11-11）

1. **graph_v2 未完成小流量验证**：`docs/agent-v2-rollout.md` 明确写着尚无小流量灰度、延迟样本和配对答案评分；默认值仍是 `legacy`。
2. **前端浏览器验收缺口**：助手成长改造验收报告把 R5 双端呈现标为"部分通过"，真实浏览器全流程未完成。
3. **文档入口已收口**：当前文档地图见 `docs/README.md`；历史设计与一次性记录在 `docs/archive/`，不再放入 `docs/` 根目录。
4. **测试依赖环境**：`MuMirrorBApplicationTests` 需要可达的 PostgreSQL；数据库测试未配置环境变量时会跳过，跳过不等于通过。
5. **发布前置条件**：成就目标环境须先执行 V17；选择性索引目标环境还须执行 V18。V18 只在本轮隔离 PostgreSQL 验证过，尚未应用到生产数据库。外部真实模型提供商连通性不属于数据库验收，仍需可用 Python 服务与用户配置。
6. **双引擎并存成本**：legacy 循环、legacy 单次规划、graph_v2、shadow 四套路径同时存在，任何对话行为变更都要确认影响面。

---

## 8. 变更记录

| 日期 | 事项 |
|---|---|
| 2026-11-11 | 以代码为准重写本文件；取代 2026-08-13 版进度文档；补充 Agent v2、成就、塔罗、成长、模型档案、聊天记忆等缺失模块；加入接管路线与已知风险 |
| 2026-11-13 | 整理推送材料：选择性索引计划与模型验收摘要归档，修正文档索引；前端成就确认收口到现有批量路径；保留正式回归测试。用户决定停止 tidy 流程，三仓 20KB 大小检查改为提醒，文档同步门禁保留，新钩子 18 项隔离检查通过。原始证据在三仓外 `_mirror_verification_archive_20261113/`；批量删除曾被拒，后续改为逐项核对备份、逐项执行非递归删除，已清理 51 个临时文件和 8 个空目录，运行日志与验收证据保留 |

## 9. 成就最终验证（2026-11-12）

- 2026-11-13 追加批量通知适配：多个成就在同一限宽 640px 弹窗并列展示，桌面三列、手机两列，长列表内部纵向滚动、底部操作固定。真实隔离 HTTP/PostgreSQL 验收覆盖五项同批、新增第六项留待下一批、同编号普通/镀层独立展示，以及 480px 矮屏 50 项滚动与一次批量确认。前端全量 99/99、构建通过；证据在 F `.verify/achievement-batch/`。原账号和当前记忆系统手工测试账号的成就未被测试造数或确认。
- 后端全量：604 项通过、0 失败、0 跳过。
- 随后的标签规范化与成长首笔事实补丁：65 项专项通过、0 失败、0 跳过，package 成功。
- 前端：74 项通过、0 失败、0 跳过，构建成功。
- 目录同步检查及 Python 生成边界测试通过；V17 在隔离 PostgreSQL 可重复执行。
- 最终真实浏览器：1440px 桌面及 390px 手机均通过；真实 API 校验 96/24 目录、普通/镀层独立确认、8/10 展架保存刷新、真实情绪/类型筛选、总结来源、三类滚底事实，浏览器错误 0。截图和 proof.json 在前端 .verify/achievement-complete/。
- 完成计划已移至 docs/archive/plans/2026-11-12-achievement-implementation.md；规则、接口与发布步骤见成就专题，不再沿用旧的34/56枚状态。

## 10. 选择性索引验收（2026-11-13）

- 推送前再次完整复核：后端 647/647、AI 807/807、前端 99/99，均 0 失败、0 跳过；前端构建、upload 同步、跨端 proto 业务声明及待提交文档 73 个链接检查通过。后端使用新建且符合命名保护的隔离库，AI 使用独立 reader 夹具，临时库/角色均已移除。证据在三仓外 `_mirror_verification_archive_20261113/publish/`；早期密码/测试库命名预检失败与最后完整通过日志一并保留。未重跑真实模型聊天矩阵或浏览器全流程，本次仍沿用下方已记录的验收边界。
- 最终补齐独立 PostgreSQL reader 夹具后，AI 完整套件 **807 通过、0 失败、0 跳过**，包含新超时预算回归及上一轮跳过的 18 项。临时视图只读角色已移除；手工验证库和运行服务未受影响。最终证据：`.verify/record-selective-index/ai-full-postgres-after-diary-fix.log`、`ai-full-postgres-after-diary-fix-grants.log`。下方 789/18 与 805/805 保留为此前轮次记录。
- 追加真实日记写入验收：用指定 DeepSeek 本地代理从书写页创建、审核确认并查询。全日常记录实际 3 段/0 向量，成长 care +1；混合记录实际 4 段/2 向量，care +1、expression +1，生成 1 条物业待办。结构化查询读回零向量日常，语义查询命中新餐馆偏好。隔离手工实例最初漏开新写入开关，已仅在本地 9050 实例显式开启；chat.engine 保持 legacy。证据：`.verify/record-selective-index/live-diary-acceptance.json`。
- 真实混合输入在修正前两次模型超时，验证了原文保留及用户确认后仅保存。AI Classify 现改为一次使用完整的 60 秒配置预算、关闭 SDK 内部重试，避免拆成两次 30 秒重复中断；总预算不增加。同输入后续复测成功，但有限样例不保证模型永不超时。新增慢响应回归先失败后通过；定向 31 通过，最新 AI 全量 789 通过、18 跳过（本次未配置独立的 `MIRROR_TEST_READ_DSN` 夹具），`upload/` 同步检查通过。下方 805/805 是此追加修正之前、配置完整数据库夹具时的首轮结果。
- 记录授权与向量索引选择已分离。全日常 version 1 记录可以 `done/READY` 且零向量；重要授权片段进入 embedding 队列并可语义检索；失败只改变索引任务/片段状态，不撤销记录授权、成长或待办。v0 存量记录继续使用原行为。
- legacy 工具保留 120 字 excerpt 的 `search_records` 兼容行为，并新增受用户授权、状态与 vault 边界过滤的全文分页 `read_record`。legacy 仍是现行路径之一；未删除、未自动切换。
- 后端全量 `mvn -B test`：647/647 通过、0 失败/错误/跳过（隔离 PostgreSQL），详见 `.verify/record-selective-index/backend-full-test-release-final.log`。最后排序变更及 legacy reader 定向组：54/54，详见 `.verify/record-selective-index/backend-order-and-task7-targeted.log`。开关关闭的 AI_ASSISTED 候选回归也通过，默认生成 version 0 并走旧 single 分类接口。
- 首轮 AI pytest 全量：805/805 通过、0 跳过；PostgreSQL 读权限集成包含在全量结果内。Anthropic thinking block 兼容修复有定向回归。固定 12 个合成分类样例（4 低信息、8 具体）使用用户指定的 `cn:deepseek-v4.1-flash` 本地 OpenAI-compatible 代理，12/12 符合预期。详见 `.verify/record-selective-index/ai-full-with-deepseek-and-postgres-final.log`、`real-model-12-case-deepseek.log`、`real-model-12-case-deepseek-results.json` 与 [AI 验收归档](../../Mu-mirror-AI/docs/archive/integration/2026-11-13-record-selective-index-real-model.md)。`sync_upload.py --check` 与两仓 proto 语义声明比对均通过。
- 前端 `npm test`：93/93；`npm run build` 成功。隔离 Edge 浏览器流程覆盖桌面全日常、390px 手机混合、刷新、编辑正文后重选、重要片段最终 `ready` 且有向量，以及受控索引失败后记录仍 `done/READY`。请求、结果和截图见 `.verify/record-selective-index/browser-e2e-final/`。
- V18 fresh schema/旧数据升级、reader 8 个视图授权及基础表拒读，在本地专用 PostgreSQL 验证；证据见 `.verify/record-selective-index/task2-postgres-test.log`、`actual-schema-setup.log`、`actual-reader-grant-script.log`。旧 migration history ledger 缺失，无法证明任何既有环境历史执行顺序或回填状态。
- 已用用户指定的本地代理与合成数据完成 legacy、graph_v2 各 8 问的真实聊天矩阵：当前复测汇总均为 7 项通过、1 项需人工判断（宽泛的“最近该关注什么”）；店名问题 legacy 首轮漏检、隔离会话复测命中，保留两次输出。疲惫、全日常零向量查询、未索引关键词、21 段跨页全文及空结果均覆盖。空结果和 graph_v2 的工具读取由原验收脚本漏判，修正后保留 raw/复核结果及规则。详情见 `.verify/record-selective-index/chat-matrix-real-model-results.json`、`chat-matrix-real-model-results-first-attempt-raw.json`、`chat-matrix-restaurant-name-retake.json`。
- 未检查目标环境有效 `chat.engine`/白名单/近期 `agent_runs`，也未执行真实部署或生产迁移。用户确认当前仍使用 legacy；本轮只为隔离验收临时启动双引擎实例，没有切换目标环境。旧数据/空开关/冲突路径由隔离数据库回归覆盖；未对业务库写入。迁移历史 ledger 缺失，不能证明现有环境的迁移顺序与回填状态。

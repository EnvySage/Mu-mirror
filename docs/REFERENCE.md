# Mu-mirror-B 参考：API、配置与测试

## AI 用量（V21）

三个只读接口均返回当前认证用户的 `R.data`，拒绝客户端写入用量。后端 JSON 使用 camelCase，前端公共请求层转换为 snake_case。

| 接口 | 参数与返回 |
|---|---|
| `GET /ai-usage/summary` | `period=today/month/all`（缺省 today），可用成对 `from/to=YYYY-MM-DD` 覆盖周期；可选 `scene/provider/model` 精确筛选。返回 `inputTokens/outputTokens/totalTokens/requestCount/taskCount/unknownCount/incompleteCount/activeCount/credits/since/timezone` |
| `GET /ai-usage/tasks` | `limit` 缺省 30、上限 100；`before` 是不透明游标；`period=today/month/all` 缺省 all，保留最近任务轮询；成对 `from/to` 覆盖 period，上海日/月边界及非法参数校验与 summary 一致；支持 `scene/provider/model`。返回 `{items,nextCursor}`，按 updatedAt/id 倒序稳定分页 |
| `GET /ai-usage/tasks/{id}` | 任务聚合与安全 `attempts`、`invocations` 明细；非本人或不存在返回 code 404，无归属信息 |

任务字段：`id,scene,label,provider,model,status,startedAt,completedAt,updatedAt,inputTokens,outputTokens,totalTokens,requestCount,unknownCount,incompleteCount,activeCount,credits`。status 为 running/complete/partial/error/cancelled，描述用量归集状态，不宣称业务写入成功。已确认 attempt 用量不足时计入 unknownCount；未完整报告的 invocation 计入 incompleteCount；pending 计入 activeCount，三者不伪装成物理请求数。实际请求数只来自 attempt。Token 累计是已知下界：每个 attempt 优先使用已返回 total；total 缺失时只加已知 input/output，原始 attempt.totalTokens 仍保持 null，unknownCount 不被清除。缓存/思考细项不重复加入总量；只有接口明确提供的零才是已知零。

credits 为 `{channelKey,channelName,unit,amount}` 数组，amount 始终是十进制字符串，不混合渠道或单位，不换算人民币。渠道 key 由调用时模型档案标识和规范化入口摘要组成，显示名只含当时档案名/入口 host；不会保存 URL 用户信息、query、密钥或原文。档案标识与配置在同一次设置读取时绑定，通过弱身份引用传递至 RPC，即使两个档案配置值相同也不会串档。已保存档案的草稿连接测试先验证归属，再显式绑定该档案 ID；未保存草稿保持无档案，普通连接测试保留当时生效档案。历史快照不依赖后续模型档案是否被修改或删除。日期按 invocation 开始时间和 Asia/Shanghai 日/月边界筛选；`since` 为该用户最早用量凭据时间，未记录返回 null。model/provider 匹配请求快照或实际返回名称。

持久表为 `ai_usage_tasks`、`ai_usage_invocations`、`ai_usage_attempts`，升级执行 `migration-v21-ai-usage.sql`，新库基线已同步。回执独立事务、单次本地补录，写入失败不改变 AI 业务响应；20 分钟过期 pending 显式转 incomplete。崩溃后未收口的任务若所有 RPC 已结束、且空闲超过 20 分钟，则按最后已观察 RPC 完成时间收口，保持已有回执和 coverage，不伪造未知消费。一次记录确认内多个片段补分类共用一个 record_analysis 任务，分类阶段 finally 收口；已有父作用域则沿用并由父任务收口，业务回滚不撤销回执。协议与边界见 [shared-protocol](shared-protocol.md)。

`UsageReportTest` 覆盖类型、版本、截断、未知与精确小数；`UsageDatabaseTest` 使用真实 PostgreSQL 和本地 gRPC 覆盖业务回滚、幂等、用户隔离、上海日界、游标、聊天归集及三客户端/后台入口。数据库需 `MIRROR_TEST_DB_URL/USERNAME/PASSWORD`，库名必须以 `codex_assistant_growth_test_` 开头；每次使用随机 usage_fixture schema，结束只清理该 schema。未设置数据库变量会跳过数据库测试，不能当作通过。

完整测试同时需要在该安全库预装 `schema.sql`，并令 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 指向同库，给应用上下文提供测试 `JWT_SECRET`。用量定向命令为 `mvnw.cmd "-Dtest=UsageReportTest,UsageDatabaseTest,UsageGrpcDatabaseTest" test`；`UsageGrpcDatabaseTest` 通过真实本地 gRPC 校验 metadata、模型档案弱身份绑定、流结束落库及 Embedding 排除。现有发布前提测试在 Windows 优先查找 D 盘或 Program Files 下的 Git Bash，避免 System32 的 WSL 启动器改变命令语义。

`UsagePeriodsDatabaseTest` 用真实回执库和 MockMvc 同时验证 tasks/summary 的 today、month、all、自定义范围覆盖、范围内游标分页和非法周期拒绝；最近轮询未传 period 的行为保持 all。全量账本计划测试还需 `MIRROR_LEDGER_PLANNING_TEST_DB_URL/USERNAME/PASSWORD`，其中 URL 必须指向独立的 `mirror_ledger_planning_test`；不要将它指向业务库。

`UsageRecordReviewDatabaseTest` 使用当前 records/chunks DDL、真实 MyBatis/gRPC 和事务验证两片段一任务、业务回滚仍保留回执；`UsageModelProfilesDatabaseTest` 使用真实档案归属查询和 gRPC 验证同入口不同档案、未保存草稿和普通探针快照。`src/test/resources/usage/ai-usage-name-contract.json` 是 AI 同步的无密钥夹具，解析测试接受方括号/未知模型名，两个 attempt 精确累计 100 Token，测试不依赖另一仓 checkout。



## 1. REST API

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
| `VaultReviewController` | `/vault` | `GET /{id}/chunks`，本人文件提取片段，排除内部 keyChunk |
| `GlossaryController` | `/glossary` | `GET /`、`POST /`、`PUT /{id}`、`DELETE /{id}`、`POST /{id}/confirm`、`POST /{id}/dismiss`、`POST /extract` |
| `TodoController` | `/todos` | `POST /`、`PATCH /{id}`、`GET /pending-suggestions`、`POST /suggestions/{id}/resolve`、`GET /`、`GET /open-chain`、`DELETE /{id}` |
| `AssistantActionController` | `/assistant/actions` | `POST /prepare`、`PATCH /{id}`、`POST /{id}/confirm`、`POST /{id}/cancel`、`GET /{id}`、`GET /?sessionId=`、`GET /records` |
| `ChatMemoryController` | `/chat-memories` | `GET /`、`GET /{id}`（遗忘不直连 HTTP，走操作卡） |
| `GrowthController` | `/growth` | `GET /`、`GET /axes`、`PUT /axes`、`GET /entries`、`POST /manual` |
| `TarotController` | `/tarot` | `GET /cards`、`GET /history`、`GET /daily`、`POST /daily/draw`、`GET /daily/requests/{id}`、`POST /readings`、`GET /readings/latest`、`GET /readings/{id}`、`POST /readings/{id}/analyses`、`GET /analyses/{id}`、`GET /readings/{id}/analyses`、`GET /collection`、`GET/PUT /preferences` |
| `AchievementController` | `/achievements` | `GET /`、`GET /pending`、`POST /acknowledge`、`GET/PUT /showcase`、`POST /activity` |
| `AgentCommandController` | `/internal/agent/commands` | `POST /create-record`、`GET /receipts/{callId}`（内部服务令牌 + HMAC，不是普通 JWT 接口） |

---

## 2. 关键配置开关

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
| `record-memory.selective-index-enabled` | `false` | 环境变量`MIRROR_RECORD_SELECTIVE_INDEX_ENABLED`；只选择未来候选的索引策略版本，不改变已持久化版本1的可读规则 |
| `mirror.achievements.worker-delay-ms` | `2000` | 成就事件消费间隔 |

---

## 3. 测试地图

| 范围 | 代表测试 |
|---|---|
| 对话主链路 | `ChatRetrievalTest`、`ChatLoopTest`、`ChatSessionDeletionTest`、`ChatInvocationTest` |
| Agent v2 | `AgentRunServiceTest`、`AgentEventAdapterTest`、`AgentCommandAuthTest`、`AgentCommandServiceTest`、`AgentLifecycleTest`、`AgentGrpcClientContextTest`、`AgentShadowRequestTest` |
| 工具 | `ToolOrchestratorTest`、`ToolsTest`、`PrepareWriteToolRegistryTest`、`PreparedActionReplyTest`、`SearchRecordsToolRoutingTest` |
| 会话/记忆 | `DailyChatSessionTest`、`DailyChatSessionDatabaseTest`、`ChatMemoryServiceTest`、`ChatMemoryForgetTest`、`MemoryCandidateCapTest` |
| 画像/归档 | `MirrorStatsTest`、`MirrorLineageReplayDatabaseTest`、`MirrorArchiveControllerTest` |
| 记录/审核 | `ConfirmReviewTest`、`RecordAuthorizationGateTest`、`RecordAiAuthorizationDatabaseTest`、`ChunkEditInvalidationTest` |
| 选择性索引数据库 | `RecordSelectiveIndexingDatabaseTest`：默认值/返回字段常规运行；v18重放、可读门槛、fresh/升级和reader边界须隔离PostgreSQL |
| 资产/词典 | `VaultServiceTest`、`DigestServiceTest`、`VaultRefsParseTest`、`GlossaryServiceTest` |
| 成长/塔罗/成就 | `GrowthServiceTest`、`GrowthLedgerDatabaseTest`、`Tarot*DatabaseTest`、`Achievement*Test`、`AchievementBusinessIntegrationTest`、`AgentRunAchievementContextTest` |
| 模型配置 | `ModelProfileServiceTest`、`ModelProfileDatabaseTest`、`SettingsServiceImplTest` |

---

## 成就与来源接口补充

完整目录 96 枚普通章，24 项镀层。字段、普通/镀层通知精确确认、结构化活动上下文、V17升级与补发见 [成就运维](achievements-operations.md)。SSE done新增assistantMessageId，恢复接口assistant.id；总结stats.sourceRecordIds列出实际输入的原始记录（stats含metadata，前端兼容camel/snake）。记录列表新增mood/contentType真实片段过滤，可与tags和日期叠加。

## 4. 选择性索引数据库迁移与兼容回退

记录候选新增服务器生成的`indexPolicyVersion`（0/1），片段新增`embeddingEnabled`（缺失按true）、`embeddingReason`（最多200字符）；`memoryEnabled`继续独立表示AI使用授权，显式false优先。版本与当前准备策略冲突返回409并保留原稿，要求重新准备；兼容策略候选不接受跳过索引选择。开启后一次拆分分类调用返回全部片段及索引建议，不为每段追加判断请求。分类整体失败仍返回待确认的仅保存候选。

版本1有授权片段的确认事务立即设置DONE/READY/已审核，成长与待办按确认规则入账；操作卡立即完成，jobIds只表示后台索引，不延长卡片执行状态。只有授权、选择索引且非空的片段排任务；跳过段保存为not_requested，retry拒绝。后台索引失败只影响对应片段状态，不撤销授权、重置审核或清成长；版本0继续旧PENDING与失败降级行为。已审核的版本1正文即使没有记忆授权也只读。删除/撤回覆盖无向量授权片段，取消任务、递增正文版本、清向量并失效派生来源；worker读取和原子回写检查归属、授权、索引选择、删除、正文hash与任务版本。

两台开发机都使用同一份 `src/main/resources/db/migration-v18-record-selective-index.sql`；连接差异放本机配置。全新空库使用 `schema.sql`；已有 v17 库在备份后以 `psql --single-transaction -v ON_ERROR_STOP=1 -f src/main/resources/db/migration-v18-record-selective-index.sql` 升级。v18 可重放，不修改历史正文、授权、向量和状态；旧记录默认 `index_policy_version=0`，旧片段默认 `embedding_enabled=true`。已有库不要以重放全量 schema 代替迁移。

低于 v17 的已有库须先按编号顺序完成尚未执行的历史迁移：v3授权 → v4归档依赖 → v5持久向量队列 → v6操作卡 → v7待办直接操作 → v8记忆状态 → v9成长 → v10每日记忆 → v11 Agent视图 → v12塔罗 → v13计划改待办 → v14塔罗v2 → v15模型配置 → v16成就 → v17成就进阶，再执行 v18；更早结构先按 `migration-v2.sql` 的入口说明处理。`backend-release.sh` 保留 v2/v13 原有入口，在备份后、切换代码前执行 v18；其发布前提检查覆盖记录/片段字段、v17新增表的全部列、v17新增或替换函数、索引与启用触发器，以及v11原有8个视图的列面和security_barrier；缺失时停止，不自动补跑历史迁移。仓库当前没有迁移版本台账，这些检查只证明当前具体结构满足发布要求，不能证明历史脚本执行顺序或回填已完成；历史升级仍须核对各机迁移执行记录。v18 后不要重放 v11 的旧视图定义，它会尝试删除已追加的视图列。

`record_memory_is_complete` 保持原规则；新增 `record_ai_is_readable` 对版本0委托旧规则，对版本1检查用户来源、未删除、DONE、已审核、处理授权、READY及至少一条同用户授权非空片段，不等待向量。`mirror_ai.record_chunks` 内联相同权限分支，保留 `security_barrier`、用户归属连接和旧7列的顺序，末尾追加 `embedding_enabled`、`embedding_status`、`index_policy_version`。reader继续只读原有8个视图；迁移不扩展基础表授权。

普通日记查询、词典统计、日报、画像、塔罗与镜子材料使用可读门槛并逐段核对用户及授权；关键词只搜索片段和标题，不搜索整篇原文。结构化及关键词检索按记录时间、记录 ID、片段 ID 倒序稳定分页；向量检索另要求该片段启用索引、状态 ready 且有向量。镜子回看先选窗口内最近片段，按原顺序发送并执行条数、单段和总字符预算，快照记录实际保留片段及记录的来源。`GenerateProfileRequest.records=16` 的字段存在性及旧端兼容见 [gRPC 契约变更登记](shared-protocol.md)。离线重建仅重建可读且启用索引的 ready 向量，不重新排队或处理主动跳过的片段。

先迁移并部署兼容代码，再按跨端验收结果开启 `record-memory.selective-index-enabled`（`MIRROR_RECORD_SELECTIVE_INDEX_ENABLED`，初始false）。回退使用本次兼容代码关闭开关，关闭只影响未来候选；已持久化版本1仍按新规则可读。不能直接回退到不认识版本1的旧二进制，也不删除原始日记、授权或历史向量作为回退步骤。发布脚本自动切回上一产物前，须确保该上一产物也已支持版本1；首次引入兼容代码时保持开关关闭。

隔离数据库测试需显式设置 `MIRROR_TEST_DB_URL`、`MIRROR_TEST_DB_USERNAME`、`MIRROR_TEST_DB_PASSWORD`，运行 `mvn "-Dtest=RecordSelectiveIndexingDatabaseTest" test`。数据库须预装pgvector；reader用例要求预置且可 `SET ROLE` 的 `mirror_ai_reader`，不创建或修改集群角色。测试仅在随机 `test_selective_*` / 对应视图schema建对象，读取 `deploy/agent-reader-role.sql` 的精确SELECT授权子句后映射到测试视图，检查完整夹具中每张基础表均无SELECT且全部可SELECT关系恰为该8视图；全部在事务结束回滚，不会改真实 `mirror_ai`。完整角色收紧脚本另在隔离部署验证中执行。未设置DSN时数据库用例跳过，不能视为迁移或权限验收通过。


## 记账与愿望接口（2026-11-13）

`/api/ledger/categories` 读取/创建分类；`records` 按 start/end/q/limit 查询并创建账目，`records/{id}` 查改删，`records/{id}/flows` 追加当天流水。流水性质为 EXPENSE、INCOME、RECOVERY，上海自然日校验，历史流水只读。RECOVERY 冲减原消费归属期，现金流仍按实际到账日展示。

`brief` 汇总已确认账目，`export?format=csv|json` 按发生日导出，`recognize` 接收 vaultItemId 返回可编辑候选。`diaries/{diaryId}/records` GET/PUT 双向关联。`wishes` 支持普通/周期愿望，金额可调、规则固定，`wish-events` 查询达成快照；`recurring` 创建周期规则，`recurring/due/{id}/confirm|skip` 决定本期候选，确认才入账。

AI 发起写入使用 `ledger.create` 操作卡，legacy 查询用 `query_ledger`，graph_v2 用 ledger_summary/list_ledger_flows。截图协议为 `mirror.LedgerVision/Recognize`。Java 保留当日和用户归属校验，重复确认只落一份账目。模板随记明确采用 index_policy_version=1、READY、已授权及 embedding_enabled=false，直接可查询；含用户正文时沿用现有记录审核和独立索引选择。

迁移顺序现为 v18 → v19-ledger → v20-ledger-planning，schema.sql 同步最终结构。发布脚本先备份再原子重放迁移，reader 增加 ledger_cashflows 视图读取权限。当前选择性索引二进制兼容边界继续适用，回退时保留新增表。


提交信息含 `[skip deploy]` 时，发布脚本在创建发布目录、执行迁移、同步代码或重启服务之前退出。用于只推送代码的同步提交。需要发布该版本时，后续提交不带此标记再走正常发布流程。

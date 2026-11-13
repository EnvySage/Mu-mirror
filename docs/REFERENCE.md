# Mu-mirror-B 参考：API、配置与测试



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

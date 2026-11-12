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
| 资产/词典 | `VaultServiceTest`、`DigestServiceTest`、`VaultRefsParseTest`、`GlossaryServiceTest` |
| 成长/塔罗/成就 | `GrowthServiceTest`、`GrowthLedgerDatabaseTest`、`Tarot*DatabaseTest`、`Achievement*Test`、`AchievementBusinessIntegrationTest`、`AgentRunAchievementContextTest` |
| 模型配置 | `ModelProfileServiceTest`、`ModelProfileDatabaseTest`、`SettingsServiceImplTest` |

---

## 成就与来源接口补充

完整目录 96 枚普通章，24 项镀层。字段、普通/镀层通知精确确认、结构化活动上下文、V17升级与补发见 [成就运维](achievements-operations.md)。SSE done新增assistantMessageId，恢复接口assistant.id；总结stats.sourceRecordIds列出实际输入的原始记录（stats含metadata，前端兼容camel/snake）。记录列表新增mood/contentType真实片段过滤，可与tags和日期叠加。

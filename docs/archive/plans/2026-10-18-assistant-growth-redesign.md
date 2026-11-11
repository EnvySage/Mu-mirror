# Mirror Assistant and Growth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留原有入口和有效历史数据的基础上，交付可确认办事的助手、自由五维成长、可遗忘的每日聊天记忆，以及分别维护的移动/桌面呈现。

**Architecture:** Vue 共用业务状态和 API，分别组织移动/桌面布局与页面。Java 拥有业务、权限、操作卡、记忆生命周期和成长账本；Python 只生成计划、候选和对话文本，不直接操作业务数据库。复用现有异步 embedding 队列、记录来源链和画像归档。

**Tech Stack:** Vue 3 / Pinia / Vue Router / Vite；Java 17 / Spring Boot 3.5 / MyBatis-Plus / PostgreSQL + pgvector；Python / gRPC / 现有模型适配器。

**Spec:** [需求与交互设计](../specs/2026-10-18-assistant-growth-redesign-design.md)。执行前完整阅读；本计划不能替代需求中的隐私和失败语义。

## Global Constraints

- 所有 AI 发起的业务修改，执行前都有操作卡，用户确认后才执行。
- 保留记录、待办、镜子、日历、资料库、设置等原有入口；助手是综合入口。
- AI 打理自动准备候选，人工审核后才进入记忆；旧自动审核路径必须收口。
- 初次入库失败降级仅保存；已成功记忆的原文和检索片段只读，不能主动切回仅保存解锁。
- 固定五个稳定维度 ID，支持自定义名称/含义、自由标签和手动贡献；不做断更扣分。
- 日会话使用 Asia/Shanghai；自动生成记忆候选，确认后生效，可查看、编辑和遗忘。
- 同一前端应用，两套页面呈现，共享业务逻辑；现有业务链接可用。
- 不实现录音/STT，不实现完整占卜，不自动处理或加分历史私有内容。
- 不重写技术栈，不复制竞品源码/素材。不得擅自修改真实业务数据库、推送或部署。
- 只有本地可逆实施获授权；遇到与本设计冲突的旧文档，以本设计和用户最新要求为准。

## Review Focus

1. 确认响应丢失、两端重复点确认：查询恢复真实结果，绝不重复创建（任务 1、3）。
2. 初次向量部分成功后失败、旧 worker 晚到：整条不可检索、原文可恢复编辑，旧任务不能把它重新授权（任务 5）。
3. 待办无源片段，以及源片段已锁定：独立待办仍可正常操作，不能写回只读正文或被 orphan 过滤误伤（任务 2）。
4. 遗忘后重新生成总结/画像：原始聊天、助手复述、缓存、历史画像不能把同一旧来源再送回模型（任务 8）。
5. 跨日回复、窗口改变、键盘弹出、多端切换：不丢输入/流式输出/操作卡，今日会话唯一（任务 7、9）。

---

## 执行方式与目录约定

执行者：Luna。先读设计与本计划，按阶段完成并验收；无需重新询问已经确定的产品选择。可逆的实施细节按设计默认值处理；真正超出范围的功能先记录，不顺手扩建。

工作区根：`E:/project/mirror/own`。根目录不是一个 Git 仓库，三个仓库独立：

| 简写 | 绝对目录 | 本次检查的 HEAD |
| --- | --- | --- |
| F | `E:/project/mirror/own/Mu-mirror-F` | `8139e305b9deb28b83225e072e3589d59e3bc481` |
| B | `E:/project/mirror/own/Mu-mirror-B` | `d07322d299233b25a232f1bb58d91acefb613136` |
| AI | `E:/project/mirror/own/Mu-mirror-AI` | `bb3348267e8d8b946ba1c720f6a5c581040cb780` |

下文 `J/` = `B/src/main/java/org/xianshen/mumirrorb/`，`JT/` = `B/src/test/java/org/xianshen/mumirrorb/`。`F/`、`B/`、`AI/` 均按上表展开，不是新建目录。标为 Create 的路径是计划新增；其余是已检查到的入口。新增 DTO/VO/Mapper 与现有分层习惯一致，不将所有业务塞入 ChatServiceImpl。

先检查各仓库当前分支、未提交改动、实际版本和可用测试依赖；隔离实施，不 reset 用户代码。不把数据库可用、模型可用、旧测试全绿视为既定事实。不读取/输出密钥。每阶段保留变更与验证记录；此计划本身不要求自动提交或发布。

## 最小共享契约

### A. 操作卡

`ActionProposalVO`：`id:UUID, kind:String, state, version:int, preparationStatus:RUNNING|READY|FAILED, payload:JSON, preview:JSON, targetVersions:JSON, sourceSessionId:UUID?, sourceMessageId:Long?, result:JSON?, errorCode:String?, createdAt, updatedAt`。

- `kind` 是服务端白名单；`payload` 按 kind 独立校验，不能任意字段透传给 Mapper。
- `preview` 包含修改前后及逐项效果。`result` 包含真实对象 ID、效果状态和 `outcome=APPLIED|SAVED_ONLY_FALLBACK`。
- `prepareKey` 绑定当前用户、用户消息和本轮标准化调用。同一轮的相同调用复用同一卡，取消后模型重试不能重建；新用户指令才有新键。
- 卡片 `version` 与目标对象版本分开；编辑 payload 增加卡版本，确认再次校验目标版本。过期返回 409 和最新信息，标记 STALE；不得自动套用旧指令。

HTTP 路径以下均相对现有 API base，不额外重复 `/api`：

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| POST | `/assistant/actions/prepare` | `{kind,payload,prepareKey,sourceSessionId?,sourceMessageId?}`，只准备 |
| PATCH | `/assistant/actions/{id}` | `{expectedVersion,payload}`，重校验并重新生成预览 |
| POST | `/assistant/actions/{id}/confirm` | `{expectedVersion,requestId}`，唯一提交入口 |
| POST | `/assistant/actions/{id}/cancel` | `{expectedVersion}`，取消待确认卡 |
| GET | `/assistant/actions/{id}` | 查询实际状态/恢复未知结果 |
| GET | `/assistant/actions?sessionId=...` | 会话中的持久化卡片 |

普通功能页仍可使用原 API，但同样经过新的域规则。直接输入页明确的“保存”计为用户提交；AI 生成的候选必须先向用户呈现再确认。

SSE 保留现有事件，新增 `action_proposal` 和 `action_status`，data 均为 `ActionProposalVO`。异步入库结束后不要求旧 SSE 仍在线，前端使用已有轮询模式按卡 ID 恢复。

### B. 工具、记录与成长

`ToolExecutor.effect(): ToolEffect`，值 `READ | PREPARE_WRITE`。只有 Java 注册表定义的 effect 可信。新增 `prepare_todo`、`prepare_record`、`prepare_growth_adjustment`、`prepare_memory_change`；未实施的 kind 不注册。不存在 confirm/commit/delete-now 工具。

记录候选 `RecordDraft`：`sourceRecordId?, sourceRecordVersion?, rawContent, finalContent, segments[], growthSuggestions[], axesVersion, mode`。每个 segment 含稳定草稿 ID、text、title、现有分类/情绪、keywords、独立 `tags:string[]`、`memoryEnabled:boolean`。未确认不创建新的业务 Record，不给既有记录扩权。

`GrowthAxis`：`id, name, description, definitionVersion`；`GrowthSuggestion`：`dimensionId, points, reason`。`GrowthEntry`：`id, actionId, sourceRecordId?, dimensionId, points, reason, definitionVersion, version, confirmedAt`。同一来源与维度唯一；更正更新该条最终值并留审计，不制造负分任务。

### C. AI 协议

以 `AI/proto/` 为源，同步 `B/src/main/proto/`，在 `E:/project/mirror/own/coordination/shared-protocol.md` 追加变更。已有字段不改号、不复用。当前字段号经检查：

- `common.proto:ToolSpec` 使用 1..3；新增 `effect=4`。
- `mirror_chat.proto:ChatRequest` 使用 1..6；新增 `AssistantContext assistant_context=7`。
- `PlanNextStepRequest` 现有字段到 9；新增 `assistant_context=10`；旧 PlanTools 如需要同样上下文则在现有 1..4 之后追加 5。
- `AssistantContext` 定义在 common.proto，包含角色口吻、当前日期/时区、有效记忆、卡片状态摘要与可选 `DivinationContext`；有来源的记忆与占卜字段分开。
- `record_processor.proto:ClassifyRequest` 到 8；新增 `GrowthContext growth_context=9`；`ClassifyResponse` 到 3，新增 `repeated GrowthSuggestion growth_suggestions=4`；`ClassifyItem` 到 9，新增 `repeated string user_tags=10`。
- 增加 `MirrorChat.SummarizeDay`：输入仅包含已过滤的用户消息及其 ID/日期、sourceHash、日期/时区和本次模型配置；输出 `summary, memory_candidates[], sourceHash`。候选必须引用输入消息 ID，不可引用助手句子。

实施前再核对字段未被其他工作占用。Python generated 只能生成；`generate_proto.py` 会同步 upload/generated，改过的 services/prompts/辅助文件还需同步对应 upload 副本。

## 阶段一：先跑通“聊天创建待办 → 卡片确认 → 列表可见”

### Task 1：持久化操作卡与受控提交

**Files**

- Create: `B/src/main/resources/db/migration-v6-assistant-actions.sql`。
- Create: `J/controller/AssistantActionController.java`、`J/service/ActionProposalService.java`、`J/service/impl/ActionProposalServiceImpl.java`。
- Create: `J/action/ActionHandler.java`、`J/action/ActionExecution.java`、`J/action/AuthorizedAction.java`，分别定义类型处理器、同步/异步结果、仅由服务端确认路径产生的提交上下文。
- Create: `J/pojo/DO/ActionProposal.java`、`J/mapper/ActionProposalMapper.java`、`J/pojo/DTO/ActionPrepareDTO.java`、`ActionEditDTO.java`、`ActionConfirmDTO.java`、`J/pojo/VO/ActionProposalVO.java`。
- Test: `JT/service/ActionProposalServiceTest.java`、`JT/controller/AssistantActionControllerTest.java`。

**Interfaces**

- Produces: `ActionProposalService.prepare(UUID userId, ActionPrepareDTO body): ActionProposalVO`；`edit(UUID, UUID actionId, ActionEditDTO)`；`confirm(UUID, UUID, ActionConfirmDTO)`；`cancel(UUID, UUID, int expectedVersion)`；`get(UUID, UUID)`；`listForSession(UUID, UUID)`，均返回 VO 或其列表。
- Produces: `ActionHandler.kinds(): Set<String>`、`validateAndPreview(UUID, String kind, JsonNode payload): JsonNode`、`execute(AuthorizedAction): ActionExecution`；注册表按 kind 建索引并拒绝重复注册。领域效果不由 Controller 或模型直接执行。
- Produces: `completeAsync(UUID actionId, int executionVersion, JsonNode result)`，只给后台生命周期使用，不暴露模型工具。

- [ ] 1. 写失败测试：未确认只生成 PENDING，领域处理器执行次数为 0；外用户 get/edit/confirm 均不可见；未知 kind 拒绝。
- [ ] 2. 运行 `./mvnw.cmd '-Dtest=ActionProposalServiceTest,AssistantActionControllerTest' test`（B）；确认失败来自新行为缺失。
- [ ] 3. 实现表、DTO、归属校验和 prepare/edit/cancel/get，唯一键绑定 user + prepareKey；编辑使版本递增，运行中的候选不可确认。
- [ ] 4. 实现 confirm：数据库条件更新/锁与领域 SQL 写入在同一事务，检查卡版本及目标版本；同一 action 即使不同 requestId 也不重复执行。异步效果提交后保持 EXECUTING，由既有 job 状态恢复。
- [ ] 5. 加入 `duplicateConfirmExecutesOnce`、`lostResponseCanRecover`、`staleTargetDoesNotExecute`、`cancelledPrepareKeyStaysCancelled`、`transactionFailureDoesNotReportSuccess`；再运行上述命令至通过。
- [ ] 6. 在迁移说明列出唯一约束、状态索引、确认事务边界和异步执行恢复查询；并发/事务真实性在隔离 PostgreSQL 集成验收，不用 Mock 通过冒充数据库证明。

### Task 2：独立待办与只准备写操作的 tool calling

**Files**

- Modify: `J/service/TodoRegistryService.java`、`J/service/impl/TodoRegistryServiceImpl.java`、`J/controller/TodoController.java`、`J/pojo/DO/TodoRegistry.java`、`J/mapper/TodoRegistryMapper.java`、`J/mapper/ProfileStatsMapper.java`。
- Modify: `J/tools/ToolExecutor.java`、`ToolDefinition.java`、`ToolRegistry.java`、`ToolOrchestrator.java`、`J/service/impl/ChatServiceImpl.java`。
- Create: `J/tools/ToolEffect.java`、`J/tools/impl/PrepareTodoTool.java`、`J/action/TodoActionHandler.java`、`J/pojo/DTO/TodoCommandDTO.java`、`B/src/main/resources/db/migration-v7-todo-direct-operations.sql`。
- Modify: `AI/proto/common.proto`、`B/src/main/proto/common.proto`、`AI/services/plan_service.py`、`AI/prompts/plan_tools.txt`、`plan_tools_single.txt` 及 upload 镜像。
- Test: `JT/service/TodoRegistryServiceTest.java`、`JT/tools/ToolOrchestratorTest.java`、`JT/tools/impl/PrepareTodoToolTest.java`、`JT/mapper/TodoStatusCaliberTest.java`、`AI/tests/test_plan_next_step.py`。

**Interfaces**

- Consumes: Task 1 的 prepare / ActionHandler，不调用 confirm。
- Produces: `createDirect(UUID, TodoCommandDTO): TodoItemVO`、`updateDirect(UUID, Long todoId, long expectedVersion, TodoCommandDTO): TodoItemVO`；`deleteTodo` 补版本检查或提供等价版本化重载。
- `todo.create/update/complete/delete` payload：`todoId?、expectedTargetVersion?、title(1..200)、notes?、dueAt?、currentStatus`；状态沿用 `not_started/in_progress/completed`，不凭空添加提醒推送能力。
- 现有 `/todos` 增 POST，`/todos/{id}` 增 PATCH；直接页通过明确提交调用，助手仅经卡确认调用同一服务。

- [ ] 1. 写失败测试：无 sourceChunkId 的 direct 待办能列出、计数、进待办提示并修改；源记录删除形成的旧 orphan 不伪装成 direct。
- [ ] 2. 运行 `./mvnw.cmd '-Dtest=TodoRegistryServiceTest,TodoStatusCaliberTest,ToolOrchestratorTest,PrepareTodoToolTest' test`（B）及 `./.venv/Scripts/python.exe -m pytest tests/test_plan_next_step.py -q`（AI），记录预期失败。
- [ ] 3. 加 `originKind=record|direct`、待办版本、notes/dueAt 字段；明确 registry 为当前状态真源。记录片段作为历史证据保持只读，旧双写路径改用当前状态覆盖层，更新统计/证据链/提示清单和旧测试。
- [ ] 4. 实现 direct CRUD 和 TodoActionHandler；模糊目标先返回可选择目标，不随便完成“最近一条”。所有目标归属由 Java 查验。
- [ ] 5. 添加 ToolEffect，已有工具逐个按实际行为标 READ（save_item 只是查询已上传文件，不是写入）；PrepareTodoTool 仅调用 prepare，回传 proposal ID。增 ToolSpec.effect=4 并同步/生成协议。
- [ ] 6. 改工具计划提示及 Java 执行门禁：没有提交工具；参数伪造 userId/confirmed 不能绕过；闲聊零业务写。新增断言 prepare 后待办写入次数为 0，确认后为 1，修改只读记录关联待办不触发 Chunk 更新。
- [ ] 7. 重跑本任务测试至通过；增加隔离数据库用例验证 direct/orphan 查询口径及乐观锁，未配置测试库则明确未完成数据库验证。

### Task 3：前端操作卡与聊天恢复

**Files**

- Create: `F/src/api/actions.js`、`F/src/stores/actions.js`、`F/src/components/organisms/ActionCard.vue`、`F/src/components/organisms/action-forms/TodoActionForm.vue`。
- Modify: `F/src/stores/chat.js`、`F/src/views/ChatView.vue`、`F/src/api/todo.js`、`F/src/stores/todo.js`、`J/service/impl/ChatServiceImpl.java`、`J/tools/ToolOrchestrator.java`、`J/grpc/AiGrpcClient.java`。
- Modify: `AI/proto/common.proto`、`mirror_chat.proto`、对应 B proto、`AI/services/chat_service.py`、`plan_service.py` 及 upload 镜像，先接 AssistantContext 的日期/角色/操作状态，记忆和占卜暂时为空。
- Create: `F/.verify/assistant-actions-scenarios.md`（浏览器验收数据及结果，不是假测试结论）。
- Test: `JT/service/ChatLoopTest.java`，现有浏览器可用时增加同等交互用例，不为纯布局另建大套测试基础设施。

**Interfaces**

- Consumes: Task 1 HTTP 契约、Task 2 工具结果、现有会话详情和 SSE。
- Produces: actions store 的 `upsert(proposal)`、`loadForSession(sessionId)`、`edit(id,payload)`、`confirm(id)`、`cancel(id)`、`refresh(id)`；按 id+version 丢弃乱序旧事件。
- Produces: ActionCard 输入 `proposal`，编辑表单按 kind 注册；表单不直写业务 API。

- [ ] 1. 在假数据/受控本地 API 准备 pending、executing、成功、降级、失败、取消、过期卡，确认每种文案对应真实状态，按钮权限一致。
- [ ] 2. Java 将 proposal 与用户消息/会话关联并发 SSE；会话历史查询能补回所有卡及最新状态，不能只靠本轮文本重建。每轮规划与回答都注入服务端当前卡状态摘要，覆盖用户看到的旧消息中“待确认”状态，取消卡不被模型重建或误称已执行。
- [ ] 3. 实现内联卡、展开编辑、确认/取消、防重复提交和状态查询；未知结果先 refresh，页面卸载不停止正在进行的共享 SSE。
- [ ] 4. 聊天主界面收起 INTENT/工具轨迹，移除原始思维链展示，改为简短操作状态；来源与文件仍可查看，不加入假打字延迟。
- [ ] 5. 浏览器检查“创建待办→编辑日期→确认→列表可见→刷新”；取消不创建；两端确认只有一个对象；响应丢失后刷新可找回；离开聊天再回来不丢卡。
- [ ] 6. 运行 `npm run build`（F）与 `./mvnw.cmd '-Dtest=ChatLoopTest,ActionProposalServiceTest' test`（B），通过后阶段一可验收。提交实际截图/步骤和数据库验证范围，不用单独 build 代表业务通过。

## 阶段二：记录审核、失败恢复和自由成长

### Task 4：保存偏好与可编辑的整理候选

**Files**

- Modify: `J/pojo/DO/UserSettings.java`、`J/pojo/DTO/SettingsDTO.java`、`J/service/impl/SettingsServiceImpl.java`、`J/service/impl/RecordServiceImpl.java`、`J/pipeline/event/RecordEventListener.java`。
- Create: `J/service/RecordDraftService.java`、`J/service/impl/RecordDraftServiceImpl.java`、`J/pojo/DTO/RecordDraftDTO.java`、`J/action/RecordActionHandler.java`、`J/tools/impl/PrepareRecordTool.java`。
- Modify: `F/src/stores/settings.js`、`F/src/views/SettingsView.vue`、`F/src/components/organisms/WriteModal.vue`、`ReviewPanel.vue`、`DetailPanel.vue`、`F/src/api/records.js`、`F/src/stores/records.js`。
- Create: `F/src/components/organisms/action-forms/RecordActionForm.vue`、`B/src/main/resources/db/migration-v8-record-memory-state.sql`（本任务先写设置/状态字段，下一任务补完整就绪与回填规则）。
- Test: `JT/service/RecordDraftServiceTest.java`、`JT/service/RecordAiAuthorizationDatabaseTest.java`、`AI/tests/test_authorization.py`。

**Interfaces**

- Consumes: Task 1 ActionProposal 和记录草稿共享契约；Task 6 补入 growthSuggestions，之前允许为空，不造假分。
- Produces: `RecordDraftService.prepare(UUID userId, RecordDraftDTO input): RecordDraftDTO`，只读来源记录、调用现有分类并产候选；record.create / record.organize Handler 在确认后才写业务。
- Produces: `defaultRecordMode=ONLY_SAVE|AI_ASSISTED`；与旧 reviewMode 区分，旧 auto 不再自动确认。

- [ ] 1. 写失败测试：ONLY_SAVE 保存零 AI 调用；AI prepare 不创建 Record、不改变旧记录授权；分类异常只产生待确认的仅保存候选。
- [ ] 2. 运行 `./mvnw.cmd '-Dtest=RecordDraftServiceTest,ConfirmReviewTest' test`（B）和 `./.venv/Scripts/python.exe -m pytest tests/test_authorization.py -q`（AI），记录新用例失败。
- [ ] 3. 加默认设置并保持既有用户 ONLY_SAVE；原输入页每条可覆盖，2000 字限制前后端一致。真正私有输入绕过全部模型处理；聊天内仅保存给出准确权限说明。
- [ ] 4. 抽出候选生成复用现有分类、时间消解与 single 模式，原始输入和最终正文分离；运行中卡片显示准备状态，取消后迟到结果不能覆盖新版本。
- [ ] 5. 让 WriteModal/ReviewPanel 和聊天卡共用编辑模型，支持片段文字、分类、自由 tags、记忆选择；保留既有 todoResolutions 审核能力并在卡中逐项列出，未确认不执行。
- [ ] 6. 收口 auto 及旧 organize/confirm 路径；旧 API 若保留兼容，仍经过人工审核及新域校验。默认偏好更新不触发历史任务。重跑本任务测试，数据库专用用例仅在隔离库启用。

### Task 5：记忆完整就绪、只读约束与可解释降级

**Files**

- Modify: `J/service/impl/RecordServiceImpl.java`、`ChunkServiceImpl.java`、`ChunkEmbeddingJobServiceImpl.java`、`J/maintenance/ChunkEmbeddingWorker.java`、`J/pojo/DO/Record.java`、`J/pojo/DTO/RecordConfirmDTO.java`。
- Modify: `J/mapper/ChunkMapper.java`、`ChatSearchMapper.java`、`DailySummaryMapper.java`、`ProfileStatsMapper.java`、`ProfileSnapshotMapper.java`、`J/service/impl/MirrorServiceImpl.java`。
- Create: `J/service/RecordMemoryLifecycleService.java`、`J/service/impl/RecordMemoryLifecycleServiceImpl.java`、`J/common/enums/RecordMemoryState.java`；补全 v8 SQL。
- Modify: `F/src/components/organisms/ReviewPanel.vue`、`ChunkCard.vue`、`DetailPanel.vue`、`F/src/stores/records.js`、`F/src/stores/actions.js`。
- Test: `JT/service/ConfirmReviewTest.java`、`ChunkEmbeddingJobServiceTest.java`、`ChunkEditInvalidationTest.java`、`RecordMemoryLifecycleTest.java`、`JT/maintenance/ChunkEmbeddingWorkerTest.java`。

**Interfaces**

- Produces: `onInitialIndexComplete(UUID userId, Long recordId, int processingVersion): void`、`onInitialIndexExhausted(UUID, Long, int, String reason): void`；只接受当前代次的初次入库结果。
- Produces: 唯一来源可用规则：本人、未删、有效授权、当前版本、Record.memoryState=READY、所选片段全部 ready。所有 AI 内容查询复用或等价落实该规则。
- Consumes: Task 1 completeAsync；Task 6 在 ready 的同一数据库事务内落确认成长，之前接口可为空实现但不能错误发放。

- [ ] 1. 写失败测试 `partialEmbeddingsNeverVisible`、`lateWorkerCannotRestoreDegradedRecord`、`readyRecordRejectsTextEdit`、`laterOutageDoesNotUnlockReadyRecord`。
- [ ] 2. 运行 `./mvnw.cmd '-Dtest=ConfirmReviewTest,ChunkEmbeddingJobServiceTest,ChunkEditInvalidationTest,RecordMemoryLifecycleTest,ChunkEmbeddingWorkerTest' test`（B），确认新增断言失败。
- [ ] 3. 在确认事务冻结最终稿、设置 PENDING、提交既有异步 embedding jobs；READY 前任何已完成子片段都不进入检索/画像/近期语境。
- [ ] 4. 全部所选片段 ready 才原子切 READY 并结束卡；后端拒绝 raw/segment/tag 等源内容更新，前端显示只读、补充/更正入口。当前待办状态用 registry 覆盖展示，不更改冻结片段。
- [ ] 5. 初次处理重试耗尽后先递增处理代次、撤权和禁用全部部分产物，再降级 record_only/NONE 并恢复编辑；清理/取消旧 jobs。结果卡逐项保留已确认的独立待办效果，不能谎报撤销或成功记忆。
- [ ] 6. 实现 DISABLED 的停止未来记忆使用，保持源只读，调用既有来源失效机制处理未来画像/摘要；删除仍走现有归档语义。已成功记录遇后续断网不进入降级分支。
- [ ] 7. 修订旧“done chunk 可编辑”的测试为拒绝；新增“仅保存可重交 AI”“取消全部记忆按仅保存”“并发编辑冲突”用例，重跑本任务测试和现有 MirrorLineageReplayDatabaseTest（隔离库）。
- [ ] 8. v8 回填只读数据库元信息，不调模型；给出 READY/PENDING/NONE/DISABLED 计数、旧异常清单和演练结果，不把所有 done 直接设为 READY。

### Task 6：自定义五维、自由标签和独立成长账本

**Files**

- Create: `B/src/main/resources/db/migration-v9-growth.sql`、`J/controller/GrowthController.java`、`J/service/GrowthService.java`、`J/service/impl/GrowthServiceImpl.java`、`J/config/GrowthProperties.java`。
- Create: `J/pojo/DO/GrowthAxis.java`、`GrowthEntry.java`、`J/mapper/GrowthAxisMapper.java`、`GrowthEntryMapper.java`、`J/pojo/DTO/GrowthAdjustmentDTO.java`、`J/pojo/VO/GrowthOverviewVO.java`、`J/action/GrowthActionHandler.java`、`J/tools/impl/PrepareGrowthAdjustmentTool.java`。
- Modify: `J/service/impl/RecordDraftServiceImpl.java`、`J/service/impl/RecordMemoryLifecycleServiceImpl.java`、`J/service/impl/RecordServiceImpl.java`、`J/grpc/AiGrpcClient.java`。
- Modify: `AI/proto/common.proto`、`record_processor.proto`、对应 B proto、`AI/services/record_processor.py`、`AI/prompts/classify.txt`、`classify-single.txt` 及 upload 镜像。
- Create: `F/src/api/growth.js`、`F/src/stores/growth.js`、`F/src/components/charts/GrowthRadar.vue`、`F/src/components/organisms/GrowthPanel.vue`、`F/src/components/organisms/action-forms/GrowthActionForm.vue`。
- Modify: `F/src/views/MirrorView.vue`、`ReviewPanel.vue`、`RecordActionForm.vue`、`F/src/views/RecordsView.vue` 及对应 records 查询参数。
- Test: `JT/service/GrowthServiceTest.java`、`AI/tests/test_growth_suggestions.py`、`test_classify_prompt.py`。

**Interfaces**

- Produces: `GET /growth`（累计分、阶段、最近贡献）、`GET /growth/axes`、`PUT /growth/axes`（用户明确编辑页提交，携带定义版本）、`GET /growth/entries`；聊天改分走成长卡。
- `GrowthService.applyConfirmedRecord(UUID, Long recordId, UUID actionId, List<GrowthAdjustmentDTO>, long axesVersion): void`，与 READY 状态同事务；`adjustManual(UUID, GrowthAdjustmentDTO): GrowthOverviewVO`，仅已确认卡或用户明确表单调用。
- 维度默认名与含义、0..5 AI 建议、0..100 审核/手动值、阶段阈值 0/10/30/60/100 集中在 GrowthProperties，Java 传给 Python，前端读取同一配置；禁止三端各写一套。

- [ ] 1. 写失败测试：默认恰好五维、自定义轴原样传入 AI、无证据为 0、越界/未知轴拒绝、重新总结不重复加分、轴改名不改既有 entry。
- [ ] 2. 运行 `./mvnw.cmd '-Dtest=GrowthServiceTest,RecordMemoryLifecycleTest' test`（B）及 `./.venv/Scripts/python.exe -m pytest tests/test_growth_suggestions.py tests/test_classify_prompt.py -q`（AI），记录失败。
- [ ] 3. 建五维定义/定义版本与成长账本；唯一来源键覆盖重复确认和 worker 重放。记录审核分在 READY 才入账，初次失败不入账；纯更正默认 0。手动来源不依赖 AI 或日记。
- [ ] 4. 分类协议增 axes/tags/growth 结构并做双方校验；提示只评本条活动贡献，不评人格。未知/无意义结果不编造分；用户改定义使旧候选需重新确认。
- [ ] 5. 审核页提供自由标签与分数编辑；记录列表支持 tags 筛选。镜子页放成长雷达、累计值和可追溯贡献，保留近期统计/月度归档，分别写明时间范围。
- [ ] 6. 加贡献更正的目标版本校验；删除记录移除关联贡献且卡上预告影响；仅停记忆保留账本。测试首次失败/删除/手动更正/重复 worker/换轴名/长时间未使用不扣分。
- [ ] 7. 重跑本任务测试及 `npm run build`（F）；浏览器完成“自定义五维→写记录改分→只加一次→手动加分→查看来源”。阶段二验收不依赖日聊天记忆或新双端页面。

## 阶段三：按天对话与可以真正忘记的记忆

### Task 7：日会话、每日总结与少量待确认记忆

**Files**

- Modify: `J/controller/MirrorController.java`、`J/service/ChatService.java`、`J/service/impl/ChatServiceImpl.java`、`J/pojo/DO/ChatSession.java`、`J/mapper/ChatSessionMapper.java`、`ConversationHistoryMapper.java`。
- Create: `B/src/main/resources/db/migration-v10-daily-memory.sql`、`J/service/ChatMemoryService.java`、`J/service/impl/ChatMemoryServiceImpl.java`、`J/pojo/DO/ChatDaySummary.java`、`ChatMemory.java`、`J/mapper/ChatDaySummaryMapper.java`、`ChatMemoryMapper.java`、`J/action/MemoryActionHandler.java`、`J/tools/impl/PrepareMemoryChangeTool.java`。
- Modify: `AI/proto/mirror_chat.proto`、`common.proto`、对应 B proto、`AI/services/chat_service.py`、`AI/server.py` 及 upload 镜像。
- Create: `AI/services/chat_memory_service.py`、`AI/prompts/chat_day_summary.txt` 及 upload 镜像；`F/src/components/organisms/ChatDaySummaryCard.vue`、`F/src/components/organisms/action-forms/MemoryActionForm.vue`。
- Modify: `F/src/api/chat.js`、`F/src/stores/chat.js`、`F/src/views/ChatView.vue`。
- Test: `JT/service/DailyChatSessionTest.java`、`ChatMemoryServiceTest.java`、`AI/tests/test_chat_memory.py`。

**Interfaces**

- Produces: `GET /mirror/sessions/today`（只查，未创建返回空）；`POST /mirror/sessions/today`（幂等解析/建立今日会话）；不占用既有 `/{id}` 路由。
- `ChatMemoryService.prepareEndedDays(UUID userId, LocalDate today): void`，只处理有聊天的已结束日期，返回/存储可查看总结及 `memory.accept` 候选卡，不启用记忆。
- `ChatMemory` 至少有 id、content、kind、sourceSessionId、sourceMessageIds、sourceDate、eventDate?、expiresAt?、state、version；一天最多 3 个候选，事件默认 30 天，不确定事实日期不填。
- `memory.accept/edit/forget` 经 Task 1；候选未确认时不进任何个人上下文。

- [ ] 1. 写失败测试 `sameDayTwoClientsResolveSameSession`、`streamAcrossMidnightFinishesInOriginalSession`、`noEmptyDaySummary`、`candidatesNeverActiveBeforeConfirm`。
- [ ] 2. 运行 `./mvnw.cmd '-Dtest=DailyChatSessionTest,ChatMemoryServiceTest' test`（B）与 `./.venv/Scripts/python.exe -m pytest tests/test_chat_memory.py -q`（AI），确认预期失败。
- [ ] 3. 增可空 dayKey/timezone 及每用户每天唯一约束；历史手动会话 dayKey 不强行回填。服务端发送时解析今日日期，旧轮结束后再切换；卡片留在原来源日，今日可显示待办卡摘要链接。
- [ ] 4. 实现懒处理：首次进入今天时检查已结束有消息日期，按 sourceHash 幂等总结；用租约/版本控制避免多端重复生成。失败可重试，不能阻塞今日发消息。
- [ ] 5. 增 SummarizeDay RPC 和严格输出校验，仅用户明确事实可抽取；sourceMessageId 必须属于本次输入。生成 1 个最多含 3 条可逐项选择的记忆确认卡；无有用事实则空候选，不硬凑。
- [ ] 6. 展示日期历史、简短总结与可展开的候选卡；允许直接继续聊天，不强制处理昨日卡。重复总结/模型重试不能重复激活记忆。
- [ ] 7. 加周末后回来、旧会话回看、消息跨日、用户与助手内容混合、无模型配置的用例，重跑测试与 F build。

### Task 8：记忆管理、遗忘和全链路上下文收口

**Files**

- Create: `J/controller/ChatMemoryController.java`、`J/service/AssistantContextService.java`、`J/service/impl/AssistantContextServiceImpl.java`、`J/pojo/DO/ChatSourceExclusion.java`、`J/mapper/ChatSourceExclusionMapper.java`；补全 v10 SQL 的来源排除及衍生依赖。
- Modify: `J/service/impl/ChatMemoryServiceImpl.java`、`ChatServiceImpl.java`、`MirrorServiceImpl.java`、`J/service/impl/SnapshotLineageService.java`、`J/mapper/ProfileStatsMapper.java`、`ProfileSnapshotMapper.java`、`ConversationHistoryMapper.java`、`J/grpc/AiGrpcClient.java`。
- Modify: `AI/prompts/chat.txt`、`plan_tools.txt`、`AI/services/chat_service.py`、`plan_service.py`、`profile_service.py` 及 upload 镜像。
- Create: `F/src/api/chatMemories.js`、`F/src/components/organisms/ChatMemoryPanel.vue`；修改聊天历史/设置二级入口。
- Test: `JT/service/ChatMemoryForgetTest.java`、`AssistantContextServiceTest.java`、`ChatRetrievalTest.java`、`MirrorStatsTest.java`、`AI/tests/test_assistant_context.py`。

**Interfaces**

- Produces: `GET /chat-memories`、`GET /chat-memories/{id}`；修改/遗忘通过操作卡。日会话删除接口复用既有 `/mirror/sessions/{id}`，增强明确的衍生范围提示。
- `AssistantContextService.build(UUID userId, UUID sessionId, String question): AssistantContext`：最多 5 条相关 active 且未过期记忆、日期/时区、当前相关卡状态、稳定角色口吻与可空 divination。以 Java 当前授权查询为准，不接收前端伪造 history。
- `ChatMemoryService.forget(UUID userId, Long memoryId, long expectedVersion): void`：原子写来源排除、失效记忆/总结/未来画像与缓存；后台已开始生成的结果落库前重验来源版本。

- [ ] 1. 写失败测试：遗忘后同一旧消息、其助手复述、总结重建、画像重建、缓存命中、已在跑的生成任务都不能再次供模型使用；历史界面原文仍可看。
- [ ] 2. 运行 `./mvnw.cmd '-Dtest=ChatMemoryForgetTest,AssistantContextServiceTest,ChatRetrievalTest,MirrorStatsTest' test`（B）及 `./.venv/Scripts/python.exe -m pytest tests/test_assistant_context.py tests/test_chat_memory.py -q`（AI），记录失败。
- [ ] 3. 建来源排除表，只保存来源 ID/版本与必要指纹；forget 先使来源代次失效，再移除缓存/候选和相关总结。删除整天还取消未确认关联卡；独立确认保存的记录/待办/成长保留并在确认页解释。
- [ ] 4. 统一构造上下文，过滤被遗忘 turn（用户消息与关联回答）；限制记忆数量/有效期；来源日期真实。与当前问题无关不强行引用，已拒绝/已解决事件不反复建议。本期无主动推送。
- [ ] 5. 重点替换 `ProfileStatsMapper.selectRecentChats` 直接送原聊天的旧路径；检查聊天检索、画像、日/月总结、词典、历史快照压缩和工具摘要的权限入口。扩展既有 SnapshotLineage 支持聊天来源失效，历史画像仍可人工看但不进未来 AI。
- [ ] 6. 接入 optional DivinationContext（仅 schema/render/隔离测试），空值不注入；有值标娱乐、时间、牌义，不成为用户事实、抽取材料、成长或执行权限。本期不建 tarot 数据表或页面。
- [ ] 7. 收敛聊天提示：有来源才引用，不强推任务、不每句追问、不输出“已完成”冒充成功。验证记忆删除、原聊天仍可看、独立保存记录作用范围说明完整。
- [ ] 8. 重跑本任务测试，隔离库执行既有 MirrorLineageReplayDatabaseTest 与新增遗忘来源用例；记录所有来源读取审计结果。阶段三以“忘掉后旧来源不复活”为硬验收。

## 阶段四：两套呈现与体验收口

### Task 9：同一应用中的移动/桌面布局和页面

**Files**

- Modify: `F/src/router/index.js`、`F/src/components/templates/MainLayout.vue`、`F/src/stores/ui.js`、`F/src/views/SettingsView.vue`。
- Create: `F/src/composables/usePresentationMode.js`、`F/src/components/templates/PresentationLayout.vue`、`DesktopLayout.vue`、`MobileLayout.vue`、`F/src/router/presentationPages.js`。
- Create: `F/src/views/desktop/` 与 `F/src/views/mobile/` 下的 `ChatPage.vue`、`RecordsPage.vue`、`MirrorPage.vue`、`CalendarPage.vue`、`VaultPage.vue`、`SettingsPage.vue`；这些是呈现入口，不复制 API 和领域代码。
- Modify: 现有 `F/src/views/ChatView.vue`、`RecordsView.vue`、`MirrorView.vue`、`CalendarView.vue`、`VaultView.vue`、`SettingsView.vue`，逐页提取数据逻辑后迁移，临时 wrapper 验证完成再删除。
- Modify: `F/src/components/organisms/AppSidebar.vue`、`BottomNav.vue`、`MobileHeader.vue`、`WriteModal.vue`、`ReviewPanel.vue`、`DetailPanel.vue`。
- Create: `F/.verify/dual-presentation-scenarios.md`。

**Interfaces**

- Consumes: 前三阶段 API、Pinia 业务状态、ActionCard 和现有鉴权。
- Produces: `usePresentationMode()` 的 `mode=desktop|mobile`、`override=auto|desktop|mobile`、`requestMode(mode)`；首次按 900px 判断并保存人工偏好，当前会话不因 resize 自动换壳。
- Produces: `presentationPages.js` 对同一业务 route name 解析对应页面；身份与角色不取自 URL。登录/根路径使用设置的首页，无显式设置时手机 `/chat`、桌面 `/records`，有业务 deep link 则优先 deep link。

- [ ] 1. 先把 HTTP、SSE、领域校验、可恢复草稿留在共享层，记录哪些状态属于单端面板/滚动。避免将同一 business store 复制为 mobileStore/desktopStore。
- [ ] 2. 建集中式模式选择、双布局和同语义路由 resolver；显式切换前保留草稿与流式状态，不能重复挂载业务轮询或新建同一会话。
- [ ] 3. 先迁移 ChatPage，再 Records/审核，再 Mirror；桌面保留多栏，手机用完整宽度卡片/抽屉，避免三四层嵌套确认。卡片内容可编辑和可见效果保持一致。
- [ ] 4. 迁移 Calendar/Vault/Settings 并保留全部链接；手机常用导航 + 更多入口能到全部功能。通用原子组件共享，确实不同的版式独立，不在业务组件遍布设备判断。
- [ ] 5. 浏览器按 390x844 与 1440x900 检查全部页面；另检查 899/900 边界、手机横屏、键盘弹出、主动切换、路由刷新、返回导航、SSE 途中切页、编辑草稿恢复、多端同卡。
- [ ] 6. 运行 `npm run build`（F）；没有实际浏览器环境则准确列为未验证，不能声称双端已验收。移除迁移 wrapper 前确认旧路由及原入口没有消失。

### Task 10：迁移演练、协议一致性与最终验收

**Files**

- Modify: 新增 v6-v10 SQL、`B/src/main/resources/db/schema.sql`（新建库与迁移结果一致）、`AI/upload/` 对应镜像、共享协议文档。
- Create: `B/docs/superpowers/reports/2026-10-18-assistant-growth-acceptance.md`（实施时填写真实结果）。
- Test: 按下列检查选择现有 Mock/纯函数测试；新增隔离 PostgreSQL 的迁移、并发和读取口径用例，复用项目既有测试模式，不默认连业务库。

**Interfaces**

- Consumes: 所有阶段产物及设计第 9 节验收表。
- Produces: 可复核的变更清单、API/字段契约、迁移前后计数、测试命令/真实结果、截图/流程证据、剩余限制；没有真实凭据时不假称模型联调成功。

- [ ] 1. 核对设计 R1-R7：每项都有代码入口与验收记录；确认无工具直接提交、旧 auto confirm 或 raw chats 画像后门。
- [ ] 2. 在隔离库复制结构与合成数据演练 v6-v10：历史仅保存不扩权；已成功记忆只读；部分成功不可检索；旧会话保留；direct/orphan 区分；历史不自动加分；重复确认唯一；迁移中断可恢复。
- [ ] 3. 验证 SQL 回退/发布顺序：不做未经证明的 down migration。数据库备份、版本兼容和数据导出方案形成报告，真实业务库迁移留给用户实际发布流程。
- [ ] 4. 从 AI 根运行 `./.venv/Scripts/python.exe generate_proto.py`，核对 B/AI proto 一致、generated 与 upload/generated 一致、改过的 services/prompts 镜像一致；运行选定的 `tests/test_*.py`。先检查 tests 中是否含真实网络调用，不盲跑 e2e_*.py。
- [ ] 5. B 运行本计划涉及的单元/Mock 测试及相关历史回归；数据库测试只指向隔离库。F 运行 `npm run build` 并完成两端业务验收。若新变更或失败无额外理由，不反复扩大测试范围。
- [ ] 6. 完整走设计第 9 节流程，再补“忘记时生成正在运行”“RAG 部分成功后最终降级”“更改轴定义时旧卡仍打开”三种场景。
- [ ] 7. 填验收报告，明确通过、未验证和限制。每个仓库执行 `git diff --check`、检查变更范围，确认没有密钥、真实聊天导出、无关重构或测试副作用。
- [ ] 8. 交付本地可审核代码与报告，不自动推送、部署或运行真实数据迁移。尚未验证的核心链路必须标明，不能以构建通过代替验收。

## 覆盖与完成标准

| 需求 | 任务 | 阶段验收 |
| --- | --- | --- |
| R1 助手边界、卡片、幂等、所有原入口 | 1-3、9 | 未确认零业务写，确认只执行一次，原入口可达 |
| R2 保存偏好、审核、只读、降级、隐私 | 4-5、8、10 | 私有绕过模型，部分 RAG 不可检索，入库后只读 |
| R3 五维、自由标签、手动成长、不强迫任务 | 6、9 | 自定义有效、确认可改、来源不重复加分、断更不扣分 |
| R4 自然聊天、日会话、记忆与遗忘 | 3、7-8 | 跨日不断流，候选须确认，遗忘后旧来源不复活 |
| R5 双端呈现 | 9 | 入口齐、业务一致、草稿和 SSE 不丢 |
| R6 占卜扩展、系统键盘输入 | 8-9 | 可选占卜隔离且默认为空，无录音/STT 工程 |
| R7 历史兼容、分阶段、无真实库/发布副作用 | 4-5、10 | 有迁移演练和真实验证报告，范围未扩张 |

本轮计划阶段未修改产品代码、未运行上述产品测试、未执行数据库迁移；执行者填写实际结果后才能将对应 checkbox 标为完成。

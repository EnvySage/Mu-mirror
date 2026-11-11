# Mu-mirror-B 接手路线




### 阶段 0：止血与建立基线（半天）

1. 确认当前工作树干净，`ai` 已包含成就第一批；发布前按迁移顺序核对数据库。
2. 跑一次 `.\mvnw.cmd -DskipTests compile`；有数据库时再跑 `.\mvnw.cmd test`，把失败分成"代码问题 / 环境缺失 / 已知跳过"三类记录在 `docs/PROGRESS.md`。
3. 从今天起只维护 `docs/PROGRESS.md`；每完成一次代码改动，当天更新"模块状态"和"对话链路"两节。

### 阶段 1：读懂对话，不写代码（2–3 天）

按这个顺序读，每读完一个文件，用自己的话写 3 行摘要到`docs/PROGRESS.md` 或笔记：

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

每跑通一个场景，在 `docs/PROGRESS.md` 第 4 节补一句"我实际观察到的现象"。**跑不通的不要标通过**。

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
- 每个模块都有一份"已知 / 未知 / 待验证"清单，且 `docs/PROGRESS.md` 是最新的。
- 任何新改动都能由你解释、测试、回滚。

---

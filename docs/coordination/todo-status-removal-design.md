# 待办交互重构 + 删除功能（设计蓝本，2026-09-12）

> 用户逐轮拍板定稿。本文件是本轮 B 侧实现的唯一蓝本；配套 todo-registry-design.md（登记/判别/裁决三段），本轮只改"状态变更入口 + 删除 + 窗口绑定"。

## 1. 交互模型（定稿）

| 动线 | 现状 | 本轮定稿 |
|---|---|---|
| 侧栏状态直调 | PUT /todos/{id}/status（三态 chip 直调） | **移除**：Controller 端点 + `setStatusDirectly` 接口/实现已彻底删除，杜绝绕过"审核页唯一入口"的通道 |
| 状态变更（未开始/进行中/完成） | 侧栏直调 / 建议裁决 | **唯一入口 = 记录修改页（审核页）**：仅 REVIEWING 记录，用户在审核页选状态，随 `PUT /records/{id}/confirm` **入库时一起生效**；提交后锁死（不改已入库记录的 todo 状态） |
| 旧 todo 改状态 | 直调 | 必须借**新记录的审核窗口**（新记录 evidence 命中旧 todo → 建议 → 审核页裁决一起入库） |
| 删除 | 无 | **特例**：侧栏直删 + 弹框（软删，见 §3） |
| pending 建议 | 侧栏角标常驻 | **与审核窗口绑定**：记录 confirm 入库时，该记录下未处理的 pending 建议一律作废（dismissed） |

核心不变量：
- 真源唯一（裁决 #33）不改：状态写 `chunk.metadata.taskStatus`，registry.current_status 是物化索引，事务内双写。
- 新需求补齐缺口：**终态回写源头片段**——confirmed 时除写 evidence 片段外，同时回写旧 todo 的 source 片段 taskStatus（原建议裁决路径只写 evidence）。
- 提交后锁死：已入库（DONE）记录不再接受状态变更；证据记录离开 REVIEWING 后 pending 建议不再展示（§7）。

## 2. 存储改动

- `todo_registry.deleted_at TIMESTAMPTZ`（NULL = 活；非空 = 已删除）。行保留，终态可追溯。
- 源头片段 `chunks.metadata.todoRemoved = true`（仅标记；用于 chunk 口径统计排除）。
- 索引：新增活行部分索引
  `CREATE INDEX idx_todo_reg_user_active ON todo_registry(user_id, current_status) WHERE deleted_at IS NULL;`
  （查询口径一律 `deleted_at IS NULL`，命中部分索引；保留原 idx_todo_reg_user 不删）。
- 迁移：基准 DDL 落 `schema.sql`；存量库增量落 `migration-v2.sql` 第 12 段（`ALTER TABLE ... ADD COLUMN IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`，幂等重放无害）。

## 3. 删除语义（软删 + 三写）

`DELETE /api/todos/{id}`（body 无）：

1. ownership 校验（非本人 404，不暴露存在性）；**已删幂等返回成功**。
2. 事务内三写：
   - ① `todo_registry.deleted_at = now()`（行保留）+ `updated_at`。
   - ② 源头片段 metadata 加 `todoRemoved: true`（`source_chunk_id` 为空或 chunk 物理不存在则跳过）。
   - ③ 该 todo 全部 `pending` 建议 → `dismissed` + `resolved_at`。
3. 删除后：所有视图过滤（清单/注入/证据链/统计）不可见；不再产生新建议；不可改状态；原始记录保留。

## 4. 接口契约（与前端写死）

### DELETE /api/todos/{id}
删除（软删）。200 成功（含幂等）；404 非本人/不存在。

### PUT /api/records/{id}/confirm
body 可选，条目分两类（`suggestionId` 与 `todoId` **恰好其一**，都无/都有 → 400）：

```json
{"todoResolutions":[{"suggestionId":123,"action":"confirmed","status":"completed"},
                    {"suggestionId":124,"action":"dismissed"},
                    {"todoId":6,"action":"confirmed","status":"in_progress"}]}
```

处理规则（事务内）：
- **suggestionId 分支（裁决 AI 建议）**：`action=confirmed` 时 **status 必填**（`not_started`/`in_progress`/`completed`）→ 更新该 todo registry 状态（closed_at 语义对齐 `applyRegistryStatus`）→ **回写 source chunk taskStatus**（新需求）+ evidence chunk taskStatus（保留）→ 落 evidence link（若不存在）→ 建议置 `confirmed` + `resolved_at`。`action=dismissed`：建议置 `dismissed` + `resolved_at`。
- **todoId 分支（用户主动挂载，无建议）**：见 §10。`action` 仅允许 `confirmed`（`dismissed` → 400，无"忽略"语义——不想挂载就不提交该行）；`status` 必填三态。
- 未出现在 body 中的本记录 pending 建议 → **一律 `dismissed`**；body 缺省（旧客户端）同样执行"未处理建议作废"（**行为变化**）。
- 参数非法（action 未知；confirmed 缺 status 或 status 非法；恰好其一校验不通过）→ 400，事务回滚。
- 顺序：待办决议**先于** `registerFromRecord`——evidence chunk 若本身是 todo 片段，回写的 taskStatus 会被登记读取，避免物化值落后。
- 运行时故障（非参数类）不阻断确认主流程（沿用登记钩子的"不阻断"哲学）。

### GET /api/records/{id}/suggestions
审核页数据接口，返回该记录 evidence 的 pending 建议：

```json
[{"suggestionId":123,"todoId":1,"todoTitle":"补作业","todoStatus":"not_started",
  "suggestedStatus":"completed","evidenceChunkId":77}]
```

ownership 在 SQL 层过滤（`r.user_id`），非本人返回空；已删除 todo 的建议不返回。

## 5. 查询过滤改动（`deleted_at IS NULL`）

`TodoRegistryMapper`：`selectOpenTodos`（判别注入）、`selectOpenChainBase`（证据链）、`selectAllByUserRaw`（GET /todos 列表）、`selectOneByUser`（防御口径，暂无消费方）。

`TodoSuggestionMapper.selectPendingByUser`：JOIN `records`，只返回**证据记录仍为 REVIEWING** 的 pending 建议（展示兜底，兼顾历史遗留数据与窗口绑定语义）；`AND t.deleted_at IS NULL`。

## 6. chunk 口径统计排除（`AND COALESCE(c.metadata->>'todoRemoved','false') != 'true'`）

以下 SQL 已要求 `status='done'`，删除待办所在 chunk 仍属 done 记录，故按 `todoRemoved` 标记排除：
- `ProfileStatsMapper.selectOpenTodos`、`selectTodoStatusCounts`
- `DailySummaryMapper.selectOpenTodos`

## 7. 建议产生 / 展示

- 产生：`TodoRegistryServiceImpl.suggestFromChunk` 增加"已删除 todo 跳过"（`deleted_at` 非空直接返回）。
- 展示：`pendingSuggestions` 只取证据记录仍 REVIEWING 的建议（JOIN records 过滤，见 §5）。

## 8. 行为变化声明

1. **confirm 缺省 body 语义变化**：旧客户端仅 `PUT /records/{id}/confirm`（无 body）现在会**作废该记录全部未处理 pending 建议**（原为保留）。
2. **状态变更入口收口**：侧栏直调接口（PUT /todos/{id}/status）已彻底移除；状态只能经审核页随 confirm 入库。
3. **终态回写源头**：confirmed 现在同时回写旧 todo 的 source chunk taskStatus（原来只写 evidence 片段）。
4. **pendingSuggestions 收窄**：只展示证据记录仍 REVIEWING 的建议（历史遗留 pending 若证据记录已 DONE 将不再展示）。
5. 删除是软删：registry 行保留、原始记录保留，仅不可见/不可再变更。

## 9. 测试覆盖

- 删除：幂等 / registry 软删 / 源头 todoRemoved 标记（含 source chunk 缺失跳过）/ pending 建议作废。
- suggestFromChunk：已删除 todo 不产生建议（原有：去重、已完成、他人、非法状态）。
- confirm resolutions：confirmed（回写源头+证据 chunk、registry、evidence link、建议 confirmed；status 必填）/ dismissed / 未处理一律作废 / body 缺省。
- 用户主动挂载（§10）：todoId 直挂五写 / 同状态幂等 / 源头 orphan + link 去重 / 合并确认建议不被"未处理作废"覆盖 / 不存在·已删除·非本人静默忽略 / 恰好其一·status 必填·dismissed 非法 400。
- 审核页接口：`listRecordSuggestions` 字段映射。

## 10. 用户主动挂载（本轮新增，2026-09-12）

### 背景
原文模型下"记录↔旧 todo 关联"只能由 LLM 判别（`refers_to_todo`）被动产生。用户需要在审核页**主动**把已注册 todo 挂到当前记录：解决"想关联但 LLM 没建议"的被动缺口。

### 契约（与前端写死）
`PUT /records/{id}/confirm` 的 `body.todoResolutions` 条目扩展为两类，**`suggestionId` 与 `todoId` 恰好提供其一**（都无/都有 → 400，防歧义）：

```json
{"todoResolutions":[
  {"suggestionId":123,"action":"confirmed","status":"in_progress"},  // 现有：裁决 AI 建议
  {"todoId":6,"action":"confirmed","status":"in_progress"}           // 新增：用户主动挂载（无建议）
]}
```

### 语义（事务内，todoId 分支）
1. **action 仅允许 `confirmed`**：无"忽略"语义——用户不想挂载就不提交该行；传 `dismissed` → 400。
2. **`status` 必填三态**；"只关联不改状态"通过**预填该 todo 当前状态**表达（契约不新增特例）。
3. **可选范围含已完成 todo**（`GET /todos` 默认排序未完成在前，见下）；一条记录允许挂多个 todo（提交多行）。
4. 单个 todoId 的动作序列（顺序同 doConfirm；状态与当前相同也走，幂等）：
   - 回写**源头片段** `taskStatus`（复用 `patchChunkTaskStatus`；源头 orphan 则跳过，惯例）。
   - `registry.current_status` 物化 + `closed_at`（completed 落值 / 非 completed 清空，对齐 `applyRegistryStatus`）。
   - 落 **evidence link**（`todo_id` + 本记录**全部** chunk；`UNIQUE(todo_id, chunk_id)` 已存在则跳过，含该 chunk 即 todo 源头片段的情形）。
   - 该 todo **全部** pending 建议一并置 `confirmed` + `resolved_at`（防孤儿建议）。
   - 注：挂载**不**回写 evidence chunk 的 `taskStatus`（仅源头真源 + registry 物化），与 suggestionId 分支保留的"evidence 片段也回写"行为区分。
5. **防御（不阻断 confirm）**：todo 不存在 / 非本人 / 已删除（`deleted_at` 非空）→ 静默忽略该条 + warn 日志（与"越窗忽略"防御哲学一致；不报错、不回滚）。

### 语义选择说明（合并 pending 建议：todo 级 vs 记录级）
挂载时对该 todo 的 **全部 pending 建议**（不限本记录 evidence）置 `confirmed`，而非仅本记录。依据：
- todo 状态已由用户**直接拍板**，任何证据来源的待处理建议都不再有意义，留下即"孤儿建议"；
- 与 `deleteTodo` 的 **todo 级** pending 作废对称（删除 = 该 todo 全部 pending → dismissed；挂载 = 该 todo 全部 pending → confirmed）；
- 实现上把合并确认的建议 id 纳入 `handled`，避免随后"本记录未处理 pending 一律作废"把它们误改回 `dismissed`。

### GET /todos 排序调整
`selectAllByUserRaw` 增加"未完成在前"：`ORDER BY (t.current_status = 'completed') ASC, t.created_at DESC, t.id DESC`（PG 布尔 false < true）。过滤（`deleted_at IS NULL`）与返回字段不变。

### 边界与不变式
- 现有 `suggestionId` 分支行为**零回归**（含 dismissed 语义）。
- 真源唯一（#33）不变：`chunk.metadata.taskStatus` 真源 + `registry.current_status` 物化双写。
- 不碰前端仓库；不改 AI 侧。

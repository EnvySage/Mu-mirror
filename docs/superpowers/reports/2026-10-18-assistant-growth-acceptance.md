# 助手成长改造验收报告

日期：2026-10-19

分支：`codex/assistant-growth-redesign`（B、F、AI 三仓）

范围：设计文档 R1–R7、实施计划 Task 1–10。

## 验收结论

四阶段实现、自动化验证和隔离数据库迁移演练已完成。R1–R4、R6、R7 的代码与自动化验收通过。R5 的双端入口、共享业务状态和布局偏好已经静态检查并由构建/单元用例验证；**真实浏览器中的整套双端交互尚未验证**，因此不把 R5 标为完整通过。

没有部署、推送或迁移真实业务数据库。用户提供的计划和设计文件保持未跟踪、未暂存、未修改。

## R1–R7 验收矩阵

| 需求 | 结果 | 代码入口及证据 | 限制 |
| --- | --- | --- | --- |
| R1 操作卡、确认、幂等、保留旧入口 | 通过 | B `ActionProposalServiceImpl`、`ActionHandler`；F `ActionCard.vue` / action store；`ActionProposalDatabaseTest` 覆盖重复确认、并发与恢复。旧业务路由保留。 | 未完成真实登录态下的全栈浏览器操作。 |
| R2 保存偏好、审核、只读、失败降级、隐私 | 通过 | B `RecordDraftServiceImpl`、`RecordMemoryLifecycleServiceImpl`、记忆授权与检索过滤；数据库测试覆盖 READY 门控和安全降级。 | 未连接真实模型服务验证凭据下的联调。 |
| R3 五维成长、标签、手动贡献、来源追溯 | 通过 | B `GrowthServiceImpl` 与成长账本；五维顺序稳定，记录贡献在 READY 后幂等入账；成长回归测试通过。 | 浏览器中的成长页面操作未重新验收。 |
| R4 日会话、记忆候选、真正遗忘 | 通过 | B `ChatServiceImpl`、`DailyChatSessionResolver`、`ChatMemoryServiceImpl`、`ChatSourceExclusion`；`MirrorLineageReplayDatabaseTest` 和忘记来源回归验证旧来源不会进入后续模型上下文，原历史仍可查看。 | 未做模型服务端到端联调。 |
| R5 移动/桌面双呈现 | 部分通过 | F `MainLayout.vue`、`DesktopLayout.vue`、`MobileLayout.vue`、`presentationPages.js`；同一业务页面和 Pinia/SSE 状态；草稿仅在内存。静态审查未发现操作卡确认绕过；900px 边界单测通过。 | 未验证 390×844、1440×900、899/900 实际布局、横屏/键盘、路由返回刷新、SSE 中切页、草稿恢复及多端同卡浏览器流程。 |
| R6 可选占卜扩展与系统键盘输入 | 范围内通过 | 仅保留空值可选扩展上下文；未增加录音/STT、牌库、页面或评分联动。输入沿用系统键盘。 | 占卜上下文无产品入口；系统键盘体验属于浏览器未验证项。 |
| R7 历史兼容、分阶段、无真实库/发布副作用 | 通过 | B `schema.sql` 已补齐 v6–v10 最终结构；协议字段保持追加式兼容；旧业务路由保留。迁移演练与三仓 diff 检查完成。 | 本报告的发布顺序是建议；实际发布仍需按维护窗口执行。 |

## 阶段验证

### 阶段一：操作卡与独立待办（Task 1–3）

- B：操作卡 controller/service 与 PostgreSQL 测试通过；Task 3 的 `ChatLoopTest,ActionProposalServiceTest` 为 39/39。
- AI：Task 2 pytest 为 48/48；Task 3 `test_plan_next_step.py` 为 48/48。
- F：构建通过。受控 Chromium 合成 API 回放验证了持久化卡片恢复只显示一张，以及确认响应丢失后刷新操作状态和待办列表。
- 尚未覆盖真实认证后端、真实双客户端竞争、取消、SSE 跨路由连续性和源文件交互。

### 阶段二：记录审核、失败恢复与成长（Task 4–6）

- B：生命周期相关 PostgreSQL 测试 71/71；成长单测 6/6、`GrowthLedgerDatabaseTest` 3/3。
- F：构建通过，Node 测试 5/5；AI 分类和成长建议测试 12/12。
- v8 仅保存回填演练：四条合成状态中仅“已审核且所有批准向量就绪”的一条成为 READY，其余三条保持 NONE；没有调用模型。
- 浏览器曾用合成 API 验证轴名修改、手动贡献和来源记录跳转；记录审核确认流程未完成浏览器验收。

### 阶段三：日会话与可遗忘记忆（Task 7–8）

- B：隔离 PostgreSQL 测试 99/99；遗忘来源过滤做过 RED/GREEN 回归验证；历史查看仍保留被遗忘的对话原文。
- F：Node 测试 9/9，构建通过。
- AI：pytest 387/387；生成文件与 upload 镜像一致。
- 隔离测试库中用户、会话、消息、摘要、记忆、来源排除、记录、片段和成长数据在测试后均为 0。

### 阶段四：双端呈现与验收（Task 9–10）

- F：`npm run build` 成功；`node --test tests/*.test.js` 12/12。纯函数用例覆盖 899/900px、手动偏好和未知值降级；静态审查确认路由名及业务页面身份保留。
- AI：`pytest tests -q` 387/387；`generate_proto.py` 成功。生成的 common stub 和 upload 镜像、修改过的 service/prompt 镜像一致。
- B：`mvnw.cmd -DskipTests compile` 成功；本会话独立重跑 unit/Mock 160/160、隔离 PostgreSQL 45/45。
- 协议：B/AI 的 common、embedding、mirror_chat、mirror_profile、record_processor 五个 proto wire/API 描述符对照一致。旧 `has_retrieval` 和 `recent_chats` 字段号保留并标明忽略；enum alias 保留原数值；新增 `EmbedBatch` 未改变既有字段号。
- 新库 DDL 与基线加 v6–v10 迁移演练的 catalog 对照：relations 37、columns 256、constraints 82、indexes 71、functions 1；完整 catalog 行差异为 0。只创建并清理两个随机临时 schema。
- 测试结束后隔离数据库 `codex_assistant_growth_test_20261018` 的 users、records、chunks、assistant_actions、chat_sessions、growth_entries 均为 0；临时对照 schema 为 0。真实业务数据库没有连接或写入。
- B、F、AI 均通过 `git diff --check`。剩余浏览器未验证项见 R5。

## 迁移与发布约定

- 本轮仅在 `mirror-archive-pg-test` 容器中的隔离测试数据库验证迁移。v6–v10 顺序重放、重放安全和合成数据场景均通过；不提供未经证明的 down migration。中断恢复依赖可幂等重放及现有执行状态恢复逻辑。
- 新数据库 DDL 与迁移最终态已经对齐。存量数据库仍应按 v6、v7、v8、v9、v10 顺序升级；不得用 `schema.sql` 替代存量迁移。
- 当前 `deploy/backend-release.sh` 会在迁移前创建 PostgreSQL custom-format dump，并在 dump 失败时停止；但默认只执行 `migration-v2.sql`，且应用版本回退不会自动恢复数据库。真实发布前必须明确传入经审核的迁移编排，先确认备份可读，再执行顺序迁移；如需数据库恢复，应在停止写入后恢复到隔离的新库、验证兼容性，再切换连接，不能把切回旧应用版本当作数据库回滚。
- 建议发布顺序：保留旧字段/路由的兼容窗口；备份并执行 v6–v10；部署 AI 协议服务，再部署 B，最后部署 F；完成健康检查及操作卡、记录降级、遗忘回归后再开放流量。此建议未在真实环境执行。

## 数据导出范围与方案

现有 `/api/export/json` 与 `/api/export/markdown` 只导出用户记录、画像快照和会话/聊天历史，排除 embedding，当前为只导出、不导入。Task 1–8 新增的成长账本、记忆候选/遗忘状态、偏好与操作卡审计信息尚未纳入这两个导出格式。

如果发布要求“完整携带本轮新增的个人数据”，应新增带格式版本的导出区段，至少包含用户设置、成长维度及历史、成长贡献及更正历史、记忆候选状态与来源 ID、按日摘要，以及用户可见的操作卡结果/引用 ID；继续排除 embedding、密钥、模型配置凭据及运行日志，不导出被遗忘聊天正文的副本。当前没有导入接口；任何导入能力应单独设计并默认不恢复操作卡执行权限、记忆授权或成长加分。本轮记录此范围，不擅自扩大实现。

## 分阶段提交

阶段一、二、三分别在 B/F/AI 独立仓库提交，末次提交分别为：

- 阶段一：B `4c05980`，F `ace7686`，AI `a4c3dce`。
- 阶段二：B `a7102fe`，F `2395083`，AI `7181eda`。
- 阶段三：B `a63fb98`，F `90cffd4`，AI `f79d732`。
- 阶段四：分别在三个仓库独立提交；未执行 push、部署或真实数据迁移。

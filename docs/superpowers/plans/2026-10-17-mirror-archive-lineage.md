# 镜子归档与累计链路实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让镜子在原始记录删除或授权变化后仍作为历史归档可见，同时阻断受影响内容进入后续累计镜子，并只在确有脏链时按需重放。

**Architecture:** 后端为每份快照保存版本、上下文资格和不含正文的来源依赖；来源变化时只标记直接依赖快照及递归后代为仅归档。下次生成时从最早失效月之前的安全检查点重建已有受影响月份到目标期；普通生成仍只调用一次生成服务。历史 API 使用稳定游标分页并提供基于 `period_month` 与可用来源月份计算的月份清单，前端时间线据此显示全部归档和状态。

**Tech Stack:** Java 17、Spring Boot 3.5、MyBatis-Plus、PostgreSQL/pgvector；Vue 3、Pinia、Vite。

**Spec:** [`docs/superpowers/specs/2026-10-17-mirror-archive-lineage-design.md`](../specs/2026-10-17-mirror-archive-lineage-design.md)

## Global Constraints

- “所有快照默认只读、归档且对所属用户可见。`invalidated` 表示‘不可作为后续生成上下文’，不表示删除或隐藏。”
- “软删除只设置 `deleted_at`。历史镜子保持生成时的内容。”
- “新镜子使用生成时仍有效且获授权的记录与片段；被软删除、撤销记忆授权或物理清除的来源不得作为新的原始输入。”
- “删除/撤权请求中同步重算”不得发生；只标记依赖链，下次生成时懒重放。
- “没有受影响祖先时，只生成一份新快照”，普通新月只进行一次镜子生成调用。
- “同一月份重新生成应新增版本，旧版本继续作为归档。”
- 迁移必须可重复执行，不能清空已有快照正文、向量或失效状态；生产数据库写入和服务部署不属于本地实施。
- 来源依赖仅存类型、ID、依赖角色和时间，不复制原始正文；来源物理清除后依赖墓碑仍可用于归档说明。
- 前端改动只落在独立 worktree `E:\project\mirror\own\worktrees\Mu-mirror-F-mirror-archive-lineage` 的镜子 API、store 和 `MirrorView.vue`；根目录 `E:\project\mirror\own\Mu-mirror-F` 中现有未提交的 `src/views/ChatView.vue` 改动保持原样。

## Review Focus

- 迁移前生成、没有依赖清单的快照必须继续可见，但不能因迁移自动恢复上下文资格；由 Task 1 的迁移回归与 Task 4 的列表测试覆盖。
- 用户 A 删除来源不得使用户 B 的镜子失效；由 Task 2 的来源隔离测试覆盖。
- 依赖源物理清除后不能因依赖外键级联而丢失墓碑；由 Task 1 的数据库集成测试覆盖。
- 同月连续重生成只能有一个当前版本，旧版本都可读；由 Task 3 的版本测试覆盖。
- 当前快照月份早于系统月、且来源月份跨度不连续时，分页与月份候选仍须正确；由 Task 4 的 keyset 边界测试及 Task 5 的前端构建/交互验收覆盖。

---

## 文件地图

后端工作目录：`E:\project\mirror\own\worktrees\Mu-mirror-B-db-integration`。

- `src/main/resources/db/schema.sql`：空库的权威结构，新增版本、失效原因和依赖表。
- `src/main/resources/db/migration-v4-mirror-archive-lineage.sql`：已有库的幂等增量迁移；不碰服务或线上数据。
- `src/main/java/org/xianshen/mumirrorb/pojo/DO/ProfileSnapshot.java`：快照版本和上下文归档状态。
- `src/main/java/org/xianshen/mumirrorb/pojo/DO/SnapshotInput.java`：快照来源/父快照依赖行。
- `src/main/java/org/xianshen/mumirrorb/mapper/ProfileSnapshotMapper.java`：当前快照、归档分页、依赖图失效传播和回放查询。
- `src/main/java/org/xianshen/mumirrorb/mapper/SnapshotInputMapper.java`：批量写入/读取来源依赖。
- `src/main/java/org/xianshen/mumirrorb/mapper/ProfileStatsMapper.java`、`ChunkMapper.java`：按实际统计、待办、学习、校正索引和回看输入收集来源 ID。
- `src/main/java/org/xianshen/mumirrorb/service/impl/MirrorServiceImpl.java`：记录输入依赖、累计父镜子、重放、版本创建、归档列表和来源墓碑计数。
- `src/main/java/org/xianshen/mumirrorb/service/impl/SnapshotLineageService.java`：来源变更后的定向失效与递归后代传播。
- `src/main/java/org/xianshen/mumirrorb/service/impl/RecordServiceImpl.java`、`ChunkServiceImpl.java`：软删除、授权撤销或已授权片段编辑改为调用定向失效。
- `src/main/java/org/xianshen/mumirrorb/pojo/VO/SnapshotListVO.java`、`SnapshotPageVO.java`、`MirrorProfileVO.java`：历史分页、月份/版本/上下文状态和删除来源数协议。
- `src/main/java/org/xianshen/mumirrorb/controller/MirrorController.java`、`service/MirrorService.java`：历史游标分页和可生成月份 API。
- `src/test/java/org/xianshen/mumirrorb/service/MirrorStatsTest.java`：镜子服务的 mock 单测。
- `src/test/java/org/xianshen/mumirrorb/service/RecordAiAuthorizationDatabaseTest.java`：PostgreSQL/MyBatis 来源状态与迁移集成覆盖（仅配置本地测试库时运行）。
- `README.md`：记载新迁移的本地运行和测试顺序。

前端工作目录：`E:\project\mirror\own\worktrees\Mu-mirror-F-mirror-archive-lineage`。

- `src/api/mirror.js`：游标分页和月份列表请求。
- `src/stores/mirror.js`：分页拼接、元数据归一化、月份候选、历史详情状态。
- `src/views/MirrorView.vue`：所有归档节点、月份/版本/仅归档状态、失效原因、来源删除标记、加载更多和重新生成提示。

## Task 1: 数据迁移与快照依赖存储

**Files:**
- Modify: `src/main/resources/db/schema.sql`
- Create: `src/main/resources/db/migration-v4-mirror-archive-lineage.sql`
- Modify: `src/main/java/org/xianshen/mumirrorb/pojo/DO/ProfileSnapshot.java`
- Create: `src/main/java/org/xianshen/mumirrorb/pojo/DO/SnapshotInput.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/mapper/ProfileSnapshotMapper.java`
- Create: `src/main/java/org/xianshen/mumirrorb/mapper/SnapshotInputMapper.java`
- Test: `src/test/java/org/xianshen/mumirrorb/service/RecordAiAuthorizationDatabaseTest.java`

**Interfaces:**
- `profile_snapshots` 新字段：`version_no INT NOT NULL DEFAULT 1`、`is_current BOOLEAN NOT NULL DEFAULT TRUE`、`lineage_tracked BOOLEAN NOT NULL DEFAULT FALSE`、`archive_discontinuity BOOLEAN NOT NULL DEFAULT FALSE`、`invalidated_at TIMESTAMPTZ NULL`、`invalidated_reason VARCHAR(80) NULL`。
- 新表 `snapshot_inputs(snapshot_id BIGINT NOT NULL, input_kind VARCHAR(16) NOT NULL, input_id BIGINT NOT NULL, input_role VARCHAR(24) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`。`input_kind` 为 `snapshot`/`record`/`chunk`，`input_role` 为 `parent`/`stats`/`todo`/`learning`/`lookback`/`correction_index`。只对 `snapshot_id` 设快照外键；来源 ID 不设外键，避免来源物理清除级联抹去墓碑。
- 存量快照统一 `version_no=1`；原有 `invalidated` 不改；旧行 `lineage_tracked=false`，已有失效行补 `invalidated_reason='legacy_invalidated'`、`invalidated_at=created_at`。
- 删除原月度 `(user_id, period_month)` 唯一索引，改为只约束 `snapshot_type='monthly' AND period_month IS NOT NULL AND is_current=TRUE` 的部分唯一索引；为依赖反查建立 `(input_kind,input_id)` 索引。
- `SnapshotInputMapper.insertAll(Long snapshotId, List<SnapshotInput> inputs)` 批量写入依赖；`selectBySnapshotId(Long snapshotId)` 返回该快照依赖。

- [ ] **Step 1: 写迁移回归测试**

在 `RecordAiAuthorizationDatabaseTest` 加测试：插入一份含正文、embedding 和 `invalidated=true` 的快照及一条指向不存在来源 ID 的 `record` 依赖；断言快照正文/失效状态和依赖行均保存，证明来源无外键级联。

```java
assertEquals("archive body", profileSnapshotMapper.selectById(snapshot.getId()).getOverallSummary());
assertTrue(profileSnapshotMapper.selectById(snapshot.getId()).getInvalidated());
assertEquals(1, snapshotInputMapper.selectBySnapshotId(snapshot.getId()).size());
```

- [ ] **Step 2: 在 PostgreSQL 测试库验证测试先失败**

Run: `./mvnw -Dtest=RecordAiAuthorizationDatabaseTest test`（Windows 使用 `mvnw.cmd -Dtest=RecordAiAuthorizationDatabaseTest test`，并设置 `MIRROR_TEST_DB_URL`、`MIRROR_TEST_DB_USERNAME`、`MIRROR_TEST_DB_PASSWORD`）。

Expected: FAIL，新增实体字段或依赖表尚不存在；未配置 `MIRROR_TEST_DB_URL` 时该数据库测试会被条件跳过，不把跳过当作通过。

- [ ] **Step 3: 实现实体、Mapper 与幂等迁移**

先在 `schema.sql` 更新空库基线，再写 v4 迁移：每个增列和索引使用 `IF NOT EXISTS`；迁移用 `DO $$` 安全替换旧唯一索引；不更新正文、embedding 或 `invalidated`。实体字段映射新增列；来源依赖批量 Mapper 使用参数化 SQL，不写入内容文本。

```sql
CREATE TABLE IF NOT EXISTS snapshot_inputs (
  snapshot_id BIGINT NOT NULL REFERENCES profile_snapshots(id) ON DELETE CASCADE,
  input_kind VARCHAR(16) NOT NULL,
  input_id BIGINT NOT NULL,
  input_role VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (snapshot_id, input_kind, input_id, input_role)
);
```

- [ ] **Step 4: 重跑测试并验证迁移可重放**

Run: `mvnw.cmd -Dtest=RecordAiAuthorizationDatabaseTest test`。

Expected: PASS；在一次性本地 PostgreSQL 测试库对 v4 文件连续执行两次，第二次成功且快照正文、embedding、失效状态和依赖数量不变。

- [ ] **Step 5: 提交本任务**

```bash
git add src/main/resources/db/schema.sql src/main/resources/db/migration-v4-mirror-archive-lineage.sql src/main/java/org/xianshen/mumirrorb/pojo/DO/ProfileSnapshot.java src/main/java/org/xianshen/mumirrorb/pojo/DO/SnapshotInput.java src/main/java/org/xianshen/mumirrorb/mapper/ProfileSnapshotMapper.java src/main/java/org/xianshen/mumirrorb/mapper/SnapshotInputMapper.java src/test/java/org/xianshen/mumirrorb/service/RecordAiAuthorizationDatabaseTest.java
git commit -m "feat: persist mirror archive lineage"
```

## Task 2: 来源变更只失效受影响镜子

**Files:**
- Create: `src/main/java/org/xianshen/mumirrorb/service/impl/SnapshotLineageService.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/mapper/ProfileSnapshotMapper.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/service/impl/RecordServiceImpl.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/service/impl/ChunkServiceImpl.java`
- Test: `src/test/java/org/xianshen/mumirrorb/service/RecordAiAuthorizationDatabaseTest.java`

**Interfaces:**
- `SnapshotLineageService.invalidateSource(UUID userId, String inputKind, Long inputId, String reason)`：先找直接依赖该 `record` 或 `chunk` 的快照，再以 `input_kind='snapshot'` 的父子依赖递归找后代；仅更新该用户对应快照的 `invalidated=true`、`invalidated_at=NOW()`、原因字段，不删行、不调用 AI。
- 旧的 `lineage_tracked=false` 当前快照在来源发生改变时按保守策略一并失效；这只影响后续 AI 上下文，旧快照仍可读。
- 原因值固定为 `record_deleted`、`memory_revoked`、`approved_chunk_edited`、`chunk_removed`、`legacy_untracked_source_change`。

- [ ] **Step 1: 写失效范围集成测试**

构造用户 A 的记录/片段、用户 B 的记录、直接依赖快照和引用该快照的后代快照；调用 `invalidateSource(A, "record", recordId, "record_deleted")`，断言 A 的直接镜子及后代失效、B 的镜子有效、正文仍存在。

- [ ] **Step 2: 验证当前测试失败**

Run: `mvnw.cmd -Dtest=RecordAiAuthorizationDatabaseTest test`。

Expected: FAIL，当前 `invalidateForUser` 会让 A 的无关快照也失效，且 Mapper 没有父子依赖传播接口。

- [ ] **Step 3: 实现递归 CTE 与事务服务**

在 Mapper 用 PostgreSQL `WITH RECURSIVE affected(snapshot_id) AS (...)` 从来源依赖开始向子快照遍历，并在更新时附加 `profile_snapshots.user_id=#{userId}`。Service 负责同一事务内记录原因并对旧的未追踪快照执行 fail-closed 失效。调用点改动：`RecordServiceImpl.softDelete` 传记录 ID；`confirmReview` 在授权变化时传该记录 ID；`ChunkServiceImpl.update` 在获批片段文本/元数据变化并撤权时传 chunk ID；`ChunkServiceImpl.delete` 删除片段前传 chunk ID。任何路径都不执行生成调用。

- [ ] **Step 4: 验证定向失效与无 AI 副作用**

Run: `mvnw.cmd -Dtest=RecordAiAuthorizationDatabaseTest test`。

Expected: PASS；新增 Mockito 验证 `invalidateSource` 被带正确来源类型/ID/原因调用，且 `AiGrpcClient.generateProfile` 从未在删除/撤权调用中执行。

- [ ] **Step 5: 提交本任务**

```bash
git add src/main/java/org/xianshen/mumirrorb/service/impl/SnapshotLineageService.java src/main/java/org/xianshen/mumirrorb/mapper/ProfileSnapshotMapper.java src/main/java/org/xianshen/mumirrorb/service/impl/RecordServiceImpl.java src/main/java/org/xianshen/mumirrorb/service/impl/ChunkServiceImpl.java src/test/java/org/xianshen/mumirrorb/service/RecordAiAuthorizationDatabaseTest.java
git commit -m "fix: invalidate only affected mirror lineage"
```

## Task 3: 生成依赖、版本归档与懒重放

**Files:**
- Modify: `src/main/java/org/xianshen/mumirrorb/mapper/ProfileStatsMapper.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/mapper/ChunkMapper.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/mapper/ProfileSnapshotMapper.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/service/impl/MirrorServiceImpl.java`
- Modify: `src/test/java/org/xianshen/mumirrorb/service/MirrorStatsTest.java`

**Interfaces:**
- 新增 `SnapshotSourceRef(String inputKind, Long inputId, String inputRole)`；统计依赖查询覆盖目标统计窗口中所有符合现有 AI 授权口径的记录/片段，并并入实际进入 open todos、recent learnings、lookback、correction index 的来源 ID。
- `PrevMirrorContext(String text, Set<Long> snapshotIds)` 同时返回 prompt 实际引用的父镜子 ID；保存镜子时把这些 ID 以 `input_kind='snapshot', input_role='parent'` 写入 `snapshot_inputs`。
- 新快照写入和依赖保存处于同一数据库事务；写入后设 `lineage_tracked=true`。月度镜子 `version_no` 为该月份最大版本加一，先把旧当前版 `is_current=false`，再插入新 `is_current=true`；manual 快照保持独立归档且不清旧版本。
- `generateMonthlyThrough(UUID userId, YearMonth target)`：没有目标期前的失效当前镜子时只生成目标期；有失效链时从最早失效月份前最近有效镜子作为检查点，按月重建至目标期中所有曾有当前月度镜子的月份，并补目标期。失效旧版保留为归档，新版本覆盖当前标记。手动生成在生成 manual 前把失效月度链懒重放到上一个完整自然月。
- 移除 manual/monthly 的数量清理以及同月物理删除；`buildPrevMirror` 只选择 `invalidated=false AND is_current=true` 的月度版，并准确返回 prompt 所用快照 ID。

- [ ] **Step 1: 写生成依赖与版本测试**

在 `MirrorStatsTest` 增加测试：目标月 prompt 引用了 7 月快照时，新保存快照依赖中有该父快照 ID；第二次生成 8 月生成 `version_no=2`、旧版 `is_current=false` 且未调用 `deleteById`。另测无失效祖先的普通目标月只调用一次 `generateProfile`。

```java
verify(aiGrpcClient, times(1)).generateProfile(eq(USER_ID), any());
verify(snapshotMapper, never()).deleteById(anyLong());
assertEquals(2, inserted.getValue().getVersionNo());
```

- [ ] **Step 2: 验证测试先失败**

Run: `mvnw.cmd -Dtest=MirrorStatsTest test`。

Expected: FAIL，当前重生成删除旧快照，且没有版本和来源依赖。

- [ ] **Step 3: 让输入装配同时返回依赖 ID**

为五维统计和四块 rolling 输入增加来源 ID 收集。沿用现有 SQL 的 `deleted_at IS NULL`、`status='done'`、`source='user'` 和授权过滤，不放宽任何输入口径。对 `recent_chats` 不创建 record 依赖，因为对话行不是记录表来源；对镜子 prompt 的父快照逐项记录实际 ID。

- [ ] **Step 4: 写入版本和懒重放实现**

手动/月度新版本与 `snapshot_inputs` 同事务保存。重放月份只从现存失效当前月度快照的 `period_month` 集合与目标月构造，不对无历史快照的中间空月逐月重算。外部 LLM 中途失败时回滚本次数据库版本/依赖变更，不覆盖原档。

- [ ] **Step 5: 写重放失效链测试**

构造 7 月有效检查点、8 月/9 月/10 月失效当前版；请求 10 月时断言只生成 8、9、10 月各一次，三个旧版本保留且变为非当前；7 月不重算。再以无失效祖先单独验证目标月一次调用。

- [ ] **Step 6: 验证服务与回滚行为**

Run: `mvnw.cmd -Dtest=MirrorStatsTest test`。

Expected: PASS；额外验证第 9 月 LLM 调用抛错时事务回滚且旧的 8/9/10 月快照仍可查询。

- [ ] **Step 7: 提交本任务**

```bash
git add src/main/java/org/xianshen/mumirrorb/mapper/ProfileStatsMapper.java src/main/java/org/xianshen/mumirrorb/mapper/ChunkMapper.java src/main/java/org/xianshen/mumirrorb/mapper/ProfileSnapshotMapper.java src/main/java/org/xianshen/mumirrorb/service/impl/MirrorServiceImpl.java src/test/java/org/xianshen/mumirrorb/service/MirrorStatsTest.java
git commit -m "feat: version and replay mirror snapshots"
```

## Task 4: 归档 API、稳定分页和月份候选

**Files:**
- Modify: `src/main/java/org/xianshen/mumirrorb/pojo/VO/SnapshotListVO.java`
- Create: `src/main/java/org/xianshen/mumirrorb/pojo/VO/SnapshotPageVO.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/pojo/VO/MirrorProfileVO.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/service/MirrorService.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/service/impl/MirrorServiceImpl.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/mapper/ProfileSnapshotMapper.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/mapper/ProfileStatsMapper.java`
- Modify: `src/main/java/org/xianshen/mumirrorb/controller/MirrorController.java`
- Modify: `src/test/java/org/xianshen/mumirrorb/service/MirrorStatsTest.java`

**Interfaces:**
- `GET /mirror/snapshots?beforeCreatedAt=<ISO>&beforeId=<id>&limit=<1..100>` 返回 `SnapshotPageVO { items, nextCreatedAt, nextId, hasMore }`；排序固定 `created_at DESC, id DESC`，第二页以 `(created_at,id) < (beforeCreatedAt,beforeId)` 取值。首屏缺省 `limit=20`。
- 每个列表项和详情包含 `periodMonth`、`versionNo`、`isCurrent`、`contextStatus`（`usable`/`archive_only`）、`invalidatedAt`、`invalidatedReason`、`deletedSourceCount`；失效详情仍通过归属校验后读取。
- `GET /mirror/snapshot-months` 返回升序 `List<String>`，月份集合为：有有效授权记录的历史月份与所有已有月度快照 `period_month` 的并集，去掉当前月/未来月；已有可用月也可选，确认后创建新版本，旧版保留归档。
- `MirrorProfileVO` 的归档状态字段与 `SnapshotListVO` 一致；删除来源数只给数量，不返回已删除来源正文。

- [ ] **Step 1: 写归档列表/详情行为测试**

修改 `MirrorStatsTest`：分页结果包含 `invalidated=true` 快照；详情透传 `archive_only`、`period_month`、版本号和 `deletedSourceCount`；分页第二页游标不会与第一页重复；来源月份只过滤当前/未来月，不按 `created_at` 代替 `period_month`。

- [ ] **Step 2: 验证测试失败**

Run: `mvnw.cmd -Dtest=MirrorStatsTest test`。

Expected: FAIL，当前列表过滤失效记录、固定 14 条且没有分页和归属月份字段。

- [ ] **Step 3: 实现 keyset 查询和 VO**

增加带可空游标的 mapper 查询；查询所有归档、不加 `invalidated=false`。对详情用批量依赖查询统计来源记录中 `deleted_at IS NOT NULL` 或来源行已物理清除的数量。月份接口查询已授权有效记录的月份并合并所有月度归属月，再按约定筛掉有效当前月。

- [ ] **Step 4: 接入 Controller 并保留推断资格隔离**

Controller 校验 `beforeCreatedAt` 与 `beforeId` 必须同时出现或同时缺省，`limit` 限制到 1–100。`selectLatest`、`selectRecent`、`selectActiveById`、聊天比较工具和 rolling context 查询仍须排除失效及非当前月度快照。

- [ ] **Step 5: 验证 API 合约与授权过滤**

Run: `mvnw.cmd -Dtest=MirrorStatsTest,GetProfileMonthTest,ToolsTest test`。

Expected: PASS；旧的工具/聊天推断测试证明仅归档镜子不会被重新用于 AI，上述五种关注输入对应的回归断言均通过。

- [ ] **Step 6: 提交本任务**

```bash
git add src/main/java/org/xianshen/mumirrorb/pojo/VO/SnapshotListVO.java src/main/java/org/xianshen/mumirrorb/pojo/VO/SnapshotPageVO.java src/main/java/org/xianshen/mumirrorb/pojo/VO/MirrorProfileVO.java src/main/java/org/xianshen/mumirrorb/service/MirrorService.java src/main/java/org/xianshen/mumirrorb/service/impl/MirrorServiceImpl.java src/main/java/org/xianshen/mumirrorb/mapper/ProfileSnapshotMapper.java src/main/java/org/xianshen/mumirrorb/mapper/ProfileStatsMapper.java src/main/java/org/xianshen/mumirrorb/controller/MirrorController.java src/test/java/org/xianshen/mumirrorb/service/MirrorStatsTest.java
git commit -m "feat: expose paged mirror archives"
```

## Task 5: 前端时间线呈现归档和重建状态

**Files (all relative to `E:\project\mirror\own\worktrees\Mu-mirror-F-mirror-archive-lineage`):**
- Modify: `src/api/mirror.js`
- Modify: `src/stores/mirror.js`
- Modify: `src/views/MirrorView.vue`

**Interfaces:**
- `getSnapshots({ beforeCreatedAt, beforeId, limit })` returns page object; store normalizes both snake_case and camelCase API properties and appends `items` without replacing the first page.
- `getSnapshotMonths()` fetches the authoritative month candidates; remove local `created_at`-based month inference.
- Timeline labels monthly nodes by `period_month`, displays version and `usable`/`archive_only`, and keeps every returned archive selectable. Details display invalidation reason and “原始记录已删除（N 条）” when `deleted_source_count > 0`.
- Loading an older history page uses a visible “加载更早镜子” action. Regenerating a month with an existing archive asks for explicit in-product confirmation that a new version will be created and old versions remain; it does not silently imply replacement.

- [ ] **Step 1: 更新 API/store 并构建验证契约**

把分页游标和月份清单封装在 `mirror.js`；store 提供 `fetchSnapshots()`、`fetchMoreSnapshots()`、`fetchSnapshotMonths()`，映射 `period_month`/`version_no`/`context_status`/`invalidated_reason`/`deleted_source_count`。错误时保留已加载页和可重试状态。

Run: `npm run build`（工作目录 `E:\project\mirror\own\worktrees\Mu-mirror-F-mirror-archive-lineage`）。

Expected: PASS；若模板还未切换分页对象，TypeScript/Vite 编译错误或运行时引用在下一步修正前需可识别。

- [ ] **Step 2: 接入全部归档节点和状态**

修改 `MirrorView.vue` 时间线：月份优先使用 `period_month`，手动镜子使用生成日期；每节点附版本及上下文状态。点击 `archive_only` 节点仍加载原正文，在详情区标出原因与删除来源数量。首屏载入完后按按钮追加后页。

- [ ] **Step 3: 修正月份选择与重生成提示**

下拉选项只从后端月份清单生成；一个月份存在失效归档时仍可选并提示会建立新当前版本，已存在可用当前镜子的月份仍可通过明确的“重新生成”动作选择。空状态使用“暂无可生成的历史月份”，不再说“近两个月都有镜子”。

- [ ] **Step 4: 验证前端交互与本地构建**

Run: `npm run build`（工作目录 `E:\project\mirror\own\worktrees\Mu-mirror-F-mirror-archive-lineage`）。

Expected: PASS；手工验收：第一页与加载更多无重复节点；月度节点显示归属月而非生成月；仅归档月份能选并重建；已删除来源只显示墓碑数量，不请求或显示其正文；现有 ChatView 改动仍保持原样。

- [ ] **Step 5: 提交仅限镜子页面的改动**

在前端 worktree 只 stage 三个镜子文件，不 stage从主目录带来的 `src/views/ChatView.vue`、`src/stores/chat.js` 或 `.superpowers/`。

```bash
git add src/api/mirror.js src/stores/mirror.js src/views/MirrorView.vue
git commit -m "feat: show mirror archive lineage"
```

## Task 6: 文档、全量回归和本地交付检查

**Files:**
- Modify: `README.md`
- Verify only: `src/main/resources/db/migration-v4-mirror-archive-lineage.sql`
- Verify only: `E:\project\mirror\own\Mu-mirror-F/src/views/ChatView.vue`（仅确认工作区改动保留，不编辑）

- [ ] **Step 1: 记录本地迁移顺序与范围**

在 README 数据库说明中补充：已有本地测试库应用 v4 的命令、可重复执行语义、建议先用一次性库验证；声明部署需另行执行，当前工作只更新代码和本地测试，不连接或修改生产库。

- [ ] **Step 2: 跑后端全量单测**

Run: `mvnw.cmd test`。

Expected: PASS；没有数据库环境的 PostgreSQL 专项测试显示为 skipped，并在结果中如实标注；不得把它们写成已通过。

- [ ] **Step 3: 跑可用的本地 PostgreSQL 集成测试**

仅当明确的 `MIRROR_TEST_DB_URL` 指向一次性本地测试数据库时运行：`mvnw.cmd -Dtest=RecordAiAuthorizationDatabaseTest test`。完成后确认 v4 连跑两次幂等，已有 `profile_snapshots` 正文和 embedding 未变。

- [ ] **Step 4: 跑前端构建并检查改动边界**

Run: `npm run build`（前端镜子 worktree）。随后在后端、前端 worktree 和前端主目录分别检查 `git status --short` 和 diff；确认后端没有生产配置/凭据改动，前端 ChatView 原有改动仍留在主目录且没有被纳入镜子提交。

- [ ] **Step 5: 提交文档并交付本地结果**

```bash
git add README.md
git commit -m "docs: document mirror archive migration"
```

完成后汇报迁移文件、接口字段、各测试通过/跳过情况，以及生产数据库和服务尚未操作。

## 自审记录

- **设计覆盖：** 归档可见与上下文资格分离由 Tasks 1/4/5；软删除历史保留与未来输入过滤由 Tasks 2/3；父镜子和原始来源依赖由 Tasks 1/3；定向失效与递归后代由 Task 2；安全检查点和按需重放由 Task 3；同月新版本/停用清理由 Task 3；分页与 `period_month` 由 Tasks 4/5；不追溯旧快照授权由 Tasks 1/2；迁移和生产边界由 Tasks 1/6。无未覆盖的设计条款。
- **占位词检查：** 无 TBD、TODO、待补、按需实现等占位步骤；各任务含目标文件、接口、测试命令和预期结果。
- **类型一致性：** `SnapshotInput` 的 `inputKind/inputId/inputRole` 与 Task 1 表结构一致；Task 2/3 使用同一 `snapshot_inputs` 父子图；Task 4 的 `SnapshotPageVO` 游标字段与 Controller 参数一致；Task 5 接收相同的分页协议和蛇形化字段名。
- **Review Focus 检查：** 五项分别落实到 Task 1 数据库测试、Task 2 用户隔离测试、Task 1 来源墓碑测试、Task 3 版本测试、Tasks 4/5 分页与月份验收。


# 记录编辑与异步向量重建实施计划

> **For agentic workers:** 按任务顺序执行并在每步验证；实现者已获用户授权在当前本地工作树内直接实施。

**目标：** 编辑草稿与保存提交分离；只有显式保存不同文本才使旧向量失效；重新授权后通过持久化后台任务重建向量，并在前端展示真实状态。

**架构：** 后端以 chunk 的 embedding 状态和持久任务表作为唯一事实来源。授权事务写入任务，Spring 定时 worker 使用数据库租约领取任务，在事务外调用 AI，再按当前内容版本条件写回；前端用显式草稿保存和状态轮询消费该接口。

**技术栈：** Vue 3 / Pinia / Axios；Java 17 / Spring Boot 3 / MyBatis-Plus / PostgreSQL + pgvector / Spring Scheduling；JUnit 5 + Mockito；Node 内置 `node:test`。

**设计依据：** [2026-10-17-async-embedding-edit-design.md](../specs/2026-10-17-async-embedding-edit-design.md)

## 全局约束

- 编辑草稿、取消草稿、以及恢复成当前原文都不得写入后端或改变现有授权、向量和镜子 lineage。
- 不同文本只有在显式保存成功后才替换正文、撤销旧文本授权、清除旧向量并使依赖旧内容的镜子退出后续上下文；这些数据库动作必须同事务提交。
- 重新授权接口不得同步等待 embedding RPC；授权和持久任务必须在同一事务提交。
- 迟到任务不得覆盖已编辑或已撤权 chunk 的状态；只允许当前授权且内容版本匹配时写入向量。
- 迁移不得重算、删除或改变存量正文、向量与记忆授权；不连接或修改生产服务器/数据库，不部署。
- 本次不新增消息队列、SSE 或 WebSocket；前端只在记录详情可见且有 pending 任务时轮询。

## 文件职责与改动范围

后端工作树：`E:\project\mirror\own\worktrees\Mu-mirror-B-db-integration`

- `src/main/resources/db/migration-v5-async-chunk-embedding.sql`：给 chunk 增加状态/内容版本字段，新增可租约领取的持久任务表与活动任务索引；以现有 migration v2-v4 的重入风格编写。
- `src/main/java/org/xianshen/mumirrorb/pojo/DO/Chunk.java`、`pojo/VO/ChunkVO.java`：映射并返回 embedding 状态与内容版本，不暴露向量本身。
- `mapper/ChunkMapper.java`、新增 `mapper/ChunkEmbeddingJobMapper.java` 与任务 DO：chunk 状态的原子条件更新；以 `FOR UPDATE SKIP LOCKED` 领取 due/retry 任务、回收过期 lease、完成/失败/取消任务。
- `service/impl/ChunkServiceImpl.java`：对文本变化做后端幂等判断；仅正文确实变化时清向量、撤权、递增内容版本并失效 lineage；仅改元数据不再破坏正文向量。
- `service/impl/RecordServiceImpl.java`、`service/ChunkEmbeddingJobService.java` 与实现、`maintenance/ChunkEmbeddingWorker.java`：授权时排入任务；调度器事务外调用 AI；通过版本和授权条件落库；有限退避、失败状态、重试和启动后 lease 恢复。
- `controller/RecordController.java` 与 `controller/ChunkController.java`（以现存路由为准）：增加针对 chunk 的 embedding 重试入口；现有 chunk 查询响应包含状态。
- `src/test/java/org/xianshen/mumirrorb/service/ChunkEditInvalidationTest.java`：纯单元测试，覆盖正文变化、无变化、仅 metadata 变化和 lineage 失效调用。
- `src/test/java/org/xianshen/mumirrorb/service/ChunkEmbeddingJobServiceTest.java`、`maintenance/ChunkEmbeddingWorkerTest.java`：任务幂等、重试、租约恢复、撤权/版本竞态及 AI 超时行为。
- `src/test/java/org/xianshen/mumirrorb/service/RecordAiAuthorizationDatabaseTest.java`：用现有 PostgreSQL 可选集成测试核验迁移、活动任务唯一性和 SQL 原子条件。

前端工作树：`E:\project\mirror\own\worktrees\Mu-mirror-F-record-ai-authorization`

- `src/components/organisms/ChunkCard.vue`：将正文改动变成本地草稿，增加明确保存/取消，输入框失焦不保存；metadata 自动保存仅提交 metadata 脏字段，不能夹带正文草稿。
- `src/api/records.js`：新增只针对当前 chunk 内容版本的重试请求，并复用已有 chunk 更新 API。
- `src/stores/records.js`：更新 chunk 后以服务端返回状态为准，不乐观清空向量状态；提供重试/刷新状态 action。
- `src/components/organisms/DetailPanel.vue`：授权接口快速返回时显示“已加入重建队列”，为 pending chunk 开始轮询，ready/failed 后停止；离开详情时清理 timer。
- `src/utils/chunkDraft.js` 与 `tests/chunkDraft.test.js`：纯函数维护原文/草稿脏状态、取消及 no-op 判定；用 Node 内置测试，无新增依赖。

## 审查重点

1. 草稿与 metadata 自动保存竞态：编辑正文后点击情绪/关键词，不能意外提交尚未确认的正文；Task 4 的测试覆盖 metadata payload 不含 segment。
2. 用户取消授权与 worker 正好完成的竞态：迟到向量不能写回；Task 3 的 worker 单测覆盖撤权和版本改变。
3. 两个 worker 同时领取或进程崩溃后租约过期：同一个活动任务只能被一个 worker 处理，过期任务可再次领取；Task 2/3 的 mapper/worker 测试覆盖。
4. embedding 服务超时、非法维度或空响应：任务不可显示 ready；Task 3 的 worker 测试覆盖失败与重试上限。
5. 同一内容被重复保存/重复确认、旧客户端重放请求：不能重复撤权、递增版本或创建重复活动任务；Task 1/2 的幂等测试覆盖。

---

### Task 1：让显式正文提交成为唯一失效边界

**文件：**
- 修改：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/service/impl/ChunkServiceImpl.java`
- 修改：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/mapper/ChunkMapper.java`
- 测试：`Mu-mirror-B-db-integration/src/test/java/org/xianshen/mumirrorb/service/ChunkEditInvalidationTest.java`

**接口：**
- 消费：现有 `ChunkServiceImpl.update(Long chunkId, ChunkDTO dto, UUID userId)`。
- 产出：服务端以存储的原 segment 比较请求 segment；相同文本不清向量、不撤权、不使 lineage 失效；不同文本继续在事务中执行这些动作，后续任务再补充内容版本与 embedding 状态字段。

- [ ] **步骤 1：先写失败测试。** `ChunkEditInvalidationTest` 用 Mockito 构造已授权 chunk，覆盖相同 segment、不同 segment、仅 metadata 改动；验证只有不同 segment 会调用 `clearEmbedding`、撤权更新和 `SnapshotLineageService.invalidateSource`。
- [ ] **步骤 2：运行指定测试确认 RED。** 在 BE 工作树运行 `mvn -q -Dtest=ChunkEditInvalidationTest test`；预期相同 segment 与仅 metadata 案例失败，因为当前实现把 metadata 改动也当作正文失效。
- [ ] **步骤 3：最小实现幂等正文判断。** 在 `ChunkServiceImpl.update` 先比较持久化的 segment 与非 null `dto.segment`，将 `textChanged` 与 `metadataChanged` 分开；只在 `textChanged` 时撤权、清向量、清 classified segment 和使 lineage 失效。无变化请求不做以上写入。
- [ ] **步骤 4：运行指定测试确认 GREEN。** 再运行 `mvn -q -Dtest=ChunkEditInvalidationTest test`；预期所有正文/no-op/metadata 用例通过。
- [ ] **步骤 5：运行既有授权回归测试。** 运行 `mvn -q -Dtest=RecordAiAuthorizationDatabaseTest test`；无 `MIRROR_TEST_DB_URL` 时该可选测试被 skip，配置了本地测试 DB 时覆盖真实事务与检索。

### Task 2：持久化 embedding 状态和任务队列

**文件：**
- 新建：`Mu-mirror-B-db-integration/src/main/resources/db/migration-v5-async-chunk-embedding.sql`
- 修改：`Mu-mirror-B-db-integration/src/main/resources/db/schema.sql`
- 修改：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/pojo/DO/Chunk.java`
- 修改：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/pojo/VO/ChunkVO.java`
- 新建：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/pojo/DO/ChunkEmbeddingJob.java`
- 修改：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/mapper/ChunkMapper.java`
- 新建：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/mapper/ChunkEmbeddingJobMapper.java`
- 测试：`Mu-mirror-B-db-integration/src/test/java/org/xianshen/mumirrorb/service/ChunkEmbeddingJobServiceTest.java`

**接口：**
- 任务状态：`PENDING`、`PROCESSING`、`READY`、`FAILED`、`CANCELLED`；chunk 暴露简化状态 `not_requested`、`pending`、`ready`、`failed`。
- 任务字段至少包含：`id`, `chunk_id`, `user_id`, `content_version`, `content_hash`, `status`, `attempts`, `available_at`, `lease_until`, `last_error`, `created_at`, `updated_at`。
- mapper 提供 `enqueueIfAbsent(chunkId, userId, contentVersion, contentHash)`、`claimDue(limit, leaseUntil)`、`markReadyIfCurrent(...)`、`markFailed(...)`、`cancelActive(chunkId, userId)`；真实 SQL 名称可按 MyBatis 项目风格调整，参数语义保持一致。

- [ ] **步骤 1：先写 mapper/服务测试。** 验证重复同版本 enqueue 只有一个活动任务；内容版本不同可以创建新活动任务；撤权后活动任务不可再被 worker 领取；只有 lease 到期的 PROCESSING 任务可恢复。
- [ ] **步骤 2：运行指定测试确认 RED。** 运行 `mvn -q -Dtest=ChunkEmbeddingJobServiceTest test`；预期因任务实体/服务/mapper 缺失而失败。
- [ ] **步骤 3：添加迁移与模型。** migration v5 为 chunks 增加 `embedding_status`, `content_version`，为已有 `embedding IS NOT NULL` 行映射 `ready`，其他行映射 `not_requested`；创建 job 表、状态约束、due 查询索引，以及每个 chunk+内容版本唯一的活动任务索引。SQL 可重复运行且不变更已有向量/授权。
- [ ] **步骤 4：实现 mapper 原子操作。** `claimDue` 使用单条 `WITH ... FOR UPDATE SKIP LOCKED ... UPDATE ... RETURNING` 原子租约领取；ready 写入必须同时匹配 chunk user、内容版本、授权仍有效和 pending/processing job；状态读取不返回向量内容。
- [ ] **步骤 5：完成测试并验证数据库迁移。** 运行 `mvn -q -Dtest=ChunkEmbeddingJobServiceTest,RecordAiAuthorizationDatabaseTest test`；设置本地 `MIRROR_TEST_DB_URL` 时覆盖迁移后状态映射、重复 enqueue 和并发领取语义。

### Task 3：将授权后的 embedding 改为可恢复后台任务

**文件：**
- 新建：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/service/ChunkEmbeddingJobService.java`
- 新建：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/service/impl/ChunkEmbeddingJobServiceImpl.java`
- 新建：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/maintenance/ChunkEmbeddingWorker.java`
- 修改：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/service/impl/RecordServiceImpl.java`
- 修改：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/controller/RecordController.java`
- 修改：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/controller/ChunkController.java`
- 修改：`Mu-mirror-B-db-integration/src/main/java/org/xianshen/mumirrorb/mapper/ChunkMapper.java`
- 测试：`Mu-mirror-B-db-integration/src/test/java/org/xianshen/mumirrorb/service/ConfirmReviewTest.java`
- 测试：`Mu-mirror-B-db-integration/src/test/java/org/xianshen/mumirrorb/maintenance/ChunkEmbeddingWorkerTest.java`
- 测试：`Mu-mirror-B-db-integration/src/test/java/org/xianshen/mumirrorb/service/RecordAiAuthorizationDatabaseTest.java`

**接口：**
- `ChunkEmbeddingJobService.enqueue(Long chunkId, UUID userId, long contentVersion, String segment)`：授权事务内幂等新增任务并返回状态。
- `ChunkEmbeddingJobService.retry(Long chunkId, UUID userId)`：仅当前已授权 chunk 且最近任务为 FAILED 时创建/重新排入当前版本任务。
- `ChunkEmbeddingWorker.processDueJobs()`：计划调度入口，领取任务后按 user/chunk/version 加载内容并在事务外调用 `AiGrpcClient.embed(userId, segment)`。
- 状态失败响应只返回 `embeddingStatus`, `contentVersion`, `updatedAt`, `errorSummary`，不包含向量。

- [ ] **步骤 1：先改测试断言。** 更新 `ConfirmReviewTest`，确认 `confirmReview` 在确认授权后设置 pending 并 enqueue，但不调用 `aiGrpcClient.embed`；对取消授权断言清向量并取消 pending job。新增 worker 测试：成功、RPC 异常、非 1024 维、内容版本变化、撤权、达到重试上限。
- [ ] **步骤 2：运行测试确认 RED。** 运行 `mvn -q -Dtest=ConfirmReviewTest,ChunkEmbeddingWorkerTest test`；预期旧同步 embed 测试失败且 worker 测试缺少实现。
- [ ] **步骤 3：把授权与 enqueue 放进一个事务。** `RecordServiceImpl.confirmReview` 保留补分类和现有记录/待办行为；每个获授权 chunk 保存 classified segment 后，调用 job service enqueue 并返回 `embedding_status=pending`，不得等待 AI embedding。
- [ ] **步骤 4：实现 worker、过期回收和退避。** worker 每次只 claim 有界数量任务；默认批量 4、lease 180 秒，覆盖每次最多 30 秒的串行 embedding 调用；gRPC 在无数据库事务期间运行；成功后 mapper 条件写入当前授权/内容版本匹配的向量并将 chunk/job 置 ready。失败按有限指数退避重排，超过配置上限标 failed；调度周期和重试上限从 application 配置读取。启动后依靠 lease expiry 恢复崩溃任务。
- [ ] **步骤 5：实现撤权与手动重试。** confirmReview 撤权在同一事务取消 chunk 的活动任务、清向量并更新 embedding 状态；重试 controller 验证当前用户拥有记录且仍授权，避免跨用户读取或重放已撤权任务。
- [ ] **步骤 6：运行服务单测和 DB 集成回归。** 运行 `mvn -q -Dtest=ConfirmReviewTest,ChunkEmbeddingWorkerTest,ChunkEmbeddingJobServiceTest,RecordAiAuthorizationDatabaseTest test`；预期确认请求不调用 embedding，worker 才执行调用，且撤权/编辑后的迟到响应不能落库。

### Task 4：前端草稿、显式提交与状态反馈

**文件：**
- 修改：`Mu-mirror-F-record-ai-authorization/src/components/organisms/ChunkCard.vue`
- 修改：`Mu-mirror-F-record-ai-authorization/src/components/organisms/DetailPanel.vue`
- 修改：`Mu-mirror-F-record-ai-authorization/src/stores/records.js`
- 修改：`Mu-mirror-F-record-ai-authorization/src/api/records.js`
- 新建：`Mu-mirror-F-record-ai-authorization/src/utils/chunkDraft.js`
- 新建：`Mu-mirror-F-record-ai-authorization/tests/chunkDraft.test.js`

**接口：**
- `createChunkDraft(originalSegment)` 返回 `{ original, draft, dirty }`；`updateChunkDraft(state, text)` 更新草稿；`discardChunkDraft(state)` 恢复原文；`hasChunkDraftChanges(state)` 对 trim 后正文相同返回 false。
- 前端保存正文只调用 `updateChunk(chunkId, { segment: draft })`；metadata autosave 只发 metadata 脏字段，不包含 segment。
- UI 状态按后端 `embedding_status` 显示，不通过乐观更新伪造 ready/pending。

- [ ] **步骤 1：先写 draft 工具测试。** `node --test tests/chunkDraft.test.js` 覆盖输入变更、撤销回原文、取消恢复原文、空白归一后 no-op 四种行为。
- [ ] **步骤 2：运行指定测试确认 RED。** 运行 `node --test tests/chunkDraft.test.js`；预期模块文件缺失导致失败。
- [ ] **步骤 3：实现草稿工具并接入 ChunkCard。** 移除正文 `@blur="onSegmentBlur"` 的网络保存；显示保存/取消按钮，取消恢复 `props.chunk.segment`；同原文时禁用保存；保存失败保留草稿并显示重试。新建 local chunk 在显式保存时才 POST。
- [ ] **步骤 4：隔离 metadata 自动保存 payload。** 情绪、类型、待办状态、关键词变更只提交实际字段，绝不使用包含 `edit.segment` 的完整 payload；正文 draft 期间执行 metadata 保存不能触发后端正文提交。
- [ ] **步骤 5：加入授权和状态反馈。** 授权确认立即显示“已加入重建队列”；DetailPanel 在其可见且 chunk pending 时轮询详情，ready/failed 后结束轮询；failed 显示“重试 embedding”；组件卸载或关闭时清理 timer。轮询使用最新 chunk 内容版本，旧响应不覆盖新状态。
- [ ] **步骤 6：运行前端验证。** 在 FE 工作树运行 `node --test tests/chunkDraft.test.js` 与 `npm run build`；预期纯工具测试通过且生产构建成功。

### Task 5：端到端兼容验证与文档更新

**文件：**
- 修改：`Mu-mirror-B-db-integration/src/test/java/org/xianshen/mumirrorb/service/RecordAiAuthorizationDatabaseTest.java`
- 修改：`Mu-mirror-B-db-integration/src/main/resources/db/schema.sql`
- 修改：`Mu-mirror-B-db-integration/src/main/resources/application.yml`（仅新增 worker enabled / interval / retry 上限配置）
- 修改：`Mu-mirror-B-db-integration/docs/superpowers/specs/2026-10-17-async-embedding-edit-design.md`（实现后补充实际状态/路由命名）

- [ ] **步骤 1：补端到端数据库用例。** 创建获授权且已有向量 chunk，执行相同正文更新并验证授权/向量保留；显式保存不同正文并验证事务提交后向量清空、授权撤销、版本增加；随后确认授权产生 pending job，worker 成功后只检索到新向量。
- [ ] **步骤 2：运行后端完整测试。** 在 BE 工作树运行 `mvn test`；预期全套单测通过，可选 PostgreSQL 集成测试在无测试 DB 时被跳过。
- [ ] **步骤 3：运行前端完整检查。** 在 FE 工作树运行 `node --test tests/chunkDraft.test.js` 和 `npm run build`；预期通过。
- [ ] **步骤 4：检查最终差异。** 检查两个工作树 `git diff --check`，确认没有部署脚本、服务器配置、密钥或生成产物进入改动；本次不部署。

## 覆盖自检

- 草稿、取消、恢复原文：Task 4。
- 服务端正文幂等、仅 metadata 编辑、lineage：Task 1 与 Task 5。
- 持久任务、原子领取、迁移兼容：Task 2 与 Task 5。
- 异步、失败退避、重试、重启恢复、撤权竞态、过期响应：Task 3。
- UI 状态、刷新后恢复、轮询销毁、文本与向量 ready 的分离：Task 4。
- 本地验证与不部署约束：Task 5。

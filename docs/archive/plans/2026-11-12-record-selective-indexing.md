# 记录选择性向量索引与工具回查 Implementation Plan

> 已完成实施归档（2026-11-13）。本文件仅保留历史步骤和验收，当前状态以 [PROGRESS](../../PROGRESS.md) 为准；不作为新的执行指令。

**Goal:** LLM 一次分类选择值得 embedding 的片段；已授权日常保留工具回查、统计和回顾能力。
**Architecture:** 授权、索引选择、任务状态分离。version=1新策略，version=0兼容旧行为；分别验收legacy与graph_v2。
**Tech Stack:** 现有 Java/Spring Boot/MyBatis-Plus、PostgreSQL/pgvector、Python/gRPC、Vue 3、pytest/JUnit/Node test。
**Spec:** [设计与行为契约](../../record-memory-tiering-plan.md)。执行前必读，禁止继续采用旧版“日常不授权”的方案。

## Global Constraints

- 根目录 `C:/Users/cheng/Desktop/claude/own`；B/AI/F指三个Mu-mirror仓，文件路径相对各仓。
- 三仓使用 `ai`；先查分支和dirty状态，不reset/clean或覆盖他人修改。B已有README/ONBOARDING等文档改动；所有worker都非独占工作区。
- 本计划授权范围为实现与验证；不自动提交、推送、部署、执行正式库迁移或切换对话引擎。
- 原文保留；仅保存/未授权/未确认/撤回/删除/其他用户数据不能进入工具。Python 不获业务表权限。
- 一次拆分分类、一次用户确认；不逐段额外调用 LLM；向量输入仍为 `segment`，维度仍为 1024。
- 旧授权、旧向量、旧任务无自动重判/清理；不能只用 `embedding IS NULL` 判断普通日常。
- 单文本文件约20KB门禁；遵循AGENTS.md，代码/协议/文档同批更新，不建平行进度文档。
- 保留预算/取消/隔离，不无限翻页；每项先运行新增测试确认失败，再实现并重跑。

## Review Focus

1. 明确 false 被默认值覆盖：Task 1/3/6 验证 proto presence、落库和前端提交。
2. 普通日常与未授权混淆：Task 2/4/5 验证普通可读、未授权不可读，含同篇混合授权。
3. 索引失败/迟到回写撤销整条记录或重复计分：Task 3 验证重试、撤回、删除及成长幂等。
4. 升级把旧 PENDING 暴露或旧任务破坏：Task 2/3 验证 version=0 和迁移前后快照。
5. 截取120字/第一页冒充全文：Task 4/5/7 验证末尾证据、稳定分页及覆盖标记。

## 分工与依赖

| Owner | 独占范围 | 任务 |
|---|---|---|
| 协调 agent | 本设计/计划、B `docs/PROGRESS.md`、最终验收记录 | 0、8；汇总接口和结论 |
| Worker AI | AI 仓全部本任务代码/生成产物/测试/README | 1、5、7的AI提示词 |
| Worker B | B Java/proto/SQL/配置/测试/deploy、`docs/shared-protocol.md`、`docs/REFERENCE.md` | 2、3、4、7 |
| Worker F | F 表单/工作流/展示/测试、`docs/record-workflow.md` | 6 |

最多协调者+3worker。0之后1/2/6可并行；3依赖1/2，4依赖2，5真实库验证依赖2/4，8等待必选任务。跨仓改动交对应owner。

---

### Task 0：记录运行基线，冻结契约（协调 agent）

**Files:** 读三仓AGENTS、B配置/ChatEngineProperties、AI rollout文档；维护本设计/计划。
**Interfaces:** 两引擎均适配；运行基线记录实际配置/run。字段和状态以Spec为准。

- [ ] 只读核对有效chat.engine、白名单及近期20条目标用户agent_runs.engine，不输出密钥/正文。
- [ ] 区分源码默认、运行配置和实际run；shadow用户响应仍是legacy。
- [x] 用户已确认当前仍用legacy，Task7必做；graph_v2用隔离环境验证，不擅自切换线上引擎。
- [x] 复核字段号11/12和迁移序号；当前v17，预留v18，有占号由协调者统一改名。
- [x] 将 Spec 的 DTO、数据库字段、工具页格式发给三 worker；后续接口变动先同步消费者。
- [x] 不删除 legacy。后续清理必须区分旧 Chat 与仍承载分类/embedding/画像的共用 RPC。

**Gate:** 三方接口一致；legacy保持可用。

### Task 1：AI 索引判断与兼容 proto（Worker AI）

**Files**
- Modify AI `proto/record_processor.proto`、`services/record_processor.py`、`prompts/classify.txt`、`prompts/classify-single.txt`。
- Create AI `mirror_core/embedding_decision.py`、`tests/test_embedding_decision.py`。
- Extend AI `tests/test_classify_prompt.py`、`tests/test_authorization.py`；更新 `generated/`、`upload/` 和 `README.md`。

**Interfaces**
- `ClassifyItem.optional bool embedding_enabled=11`；`string embedding_reason=12`。
- `_parse_embedding_decision(item: dict) -> tuple[bool, str]` 放新 helper；只接受真实 bool，缺失/null/字符串/数字均默认 true；原因截200字符。
- single/split均返回建议，保留原文、时间替换及分类等输出。

- [x] 编写 parser 参数测试：false→false、true→true、缺失/null/"false"/0→true；200字符截断。
- [x] 用假 LLM 响应测试 single 和 split：混合建议保持逐段对应，false 的 proto presence 为 true；只调用一次模型。
- [x] 改proto、两模板及两分支映射；保留无意义skip/未授权拒绝，低信息有效日记不能整体skip。
- [x] 运行 `.venv/Scripts/python.exe generate_proto.py`，把源 proto 交 Worker B 同步；不得手写 generated stub。
- [x] 运行 `.venv/Scripts/python.exe -B -m pytest tests/test_embedding_decision.py tests/test_classify_prompt.py tests/test_authorization.py -q -p no:cacheprovider`；失败项修复后重跑。
- [x] 运行 `.venv/Scripts/python.exe scripts/sync_upload.py` 及 `--check`，更新README契约。

**Gate:** presence与两模式正确；真实语义质量留8。

### Task 2：数据库与可读门槛（Worker B）

以下 `J=src/main/java/org/xianshen/mumirrorb`，`T=src/test/java/org/xianshen/mumirrorb`，均为 B 内精确路径前缀。

**Files**
- Modify B `src/main/resources/db/schema.sql`。
- Create B `src/main/resources/db/migration-v18-record-selective-index.sql`、`T/service/RecordSelectiveIndexingDatabaseTest.java`。
- Modify B `J/pojo/DO/Record.java`、`J/pojo/DO/Chunk.java`、`J/pojo/VO/RecordVO.java`、`J/pojo/VO/ChunkVO.java`、`J/service/impl/RecordQueries.java`、`J/service/impl/RecordServiceImpl.java`。
- 更新 B `deploy/backend-release.sh` 的迁移检查/执行顺序，`docs/REFERENCE.md` 的两机迁移和兼容回退说明；不实际发布。

**Interfaces**
- 按Spec§3新增DO/SQL字段；旧Java builder未赋embeddingEnabled时必须按true解释/落库。
- `record_ai_is_readable(target_record_id BIGINT) RETURNS BOOLEAN`，按 Spec 区分0/1；原 `record_memory_is_complete` 保持旧定义。
- `mirror_ai.record_chunks`按Spec§5扩展，索引字段仅追加；VO按Spec§3映射。

- [x] 建迁移测试：在旧数据上执行v18两次，原列的正文/授权/向量/状态和可见记录集合不变；默认 version0/eligible=true。
- [x] 加数据库断言：version1全日常可读；未确认/未授权/删除/DISABLED/其他用户不可读；version0 PENDING仍不可读。
- [x] 对齐fresh schema/升级SQL；视图内联可读谓词，保留security_barrier和归属连接，不引入基础表权限依赖。
- [x] reader实测仍仅8个视图、不能读基础表；不DROP schema或扩大grants。
- [x] 更新DO/VO和release路径；版本由服务器控制。
- [x] 隔离库设置MIRROR_TEST_DB_URL/USERNAME/PASSWORD，运行 `mvn "-Dtest=RecordSelectiveIndexingDatabaseTest" test`；skip不算通过。

**Gate:** 两种建库一致、旧行为不变、reader只读授权内容。

### Task 3：确认、索引任务与成长解耦（Worker B）

**Files**
- 同步 B `src/main/proto/record_processor.proto`（来自Task1）；Modify `J/pojo/DTO/RecordDraftDTO.java`。
- Modify `J/service/impl/RecordDraftServiceImpl.java`、`J/action/RecordActionPreparation.java`、`J/action/RecordActionExecution.java`、`J/action/RecordActionValidation.java`。
- Modify `J/service/impl/RecordReview.java`、`J/service/impl/RecordCommands.java`、`J/service/impl/ChunkServiceImpl.java`、`J/service/impl/RecordMemoryLifecycleServiceImpl.java`、`J/service/impl/ChunkEmbeddingJobServiceImpl.java`、`J/service/impl/ChunkEmbeddingJobStateChangedListener.java`、`J/mapper/ChunkMapper.java`。
- Create `J/config/RecordMemoryProperties.java`；Modify `src/main/resources/application.yml`、`docs/shared-protocol.md`、`docs/REFERENCE.md`。
- Extend `T/service/RecordDraftServiceTest.java`、`RecordActionHandlerTest.java`、`RecordMemoryLifecycleTest.java`、`ChunkEmbeddingJobServiceTest.java`、`RecordSelectiveIndexingDatabaseTest.java`（后四个同 service 目录）。

**Interfaces**
- `record-memory.selective-index-enabled: ${MIRROR_RECORD_SELECTIVE_INDEX_ENABLED:false}`。
- DTO及白名单按Spec§3；严格校验类型。开关0/1控制候选版本，服务器校验策略冲突409保留原稿。
- AI_ASSISTED默认授权，索引建议不影响授权，显式撤权优先。
- 新确认按Spec§4立即完成卡片，jobIds仅代表后台索引；授权且enabled的非空段才排任务，false用not_requested。

- [x] 测3段全日常→3可读/0任务，混合→1任务，全索引→3任务；显式未授权不能被AI_ASSISTED覆盖。
- [x] 用hasEmbeddingEnabled区分缺失/false，保存原因；false不得改成撤权。
- [x] version1确认时成长/待办按原规则入账；0授权不可读；已审核正文只读不依赖索引。
- [x] 隔离version1索引回调，不撤权/清成长/重置审核；保留version0旧回调。
- [x] 排队/retry/取文本/回写均验证enabled、授权、归属、删除、文本及任务版本；不能用完整可读门槛拦死旧PENDING任务。
- [x] 测试失败仍可读/计分一次、撤回删除后迟到写回失败、跳过段retry拒绝、新回调不重复计分、旧失败降级不变。
- [x] 运行 `mvn "-Dtest=RecordDraftServiceTest,RecordActionHandlerTest,RecordMemoryLifecycleTest,ChunkEmbeddingJobServiceTest,RecordSelectiveIndexingDatabaseTest" test`，并 `mvn -DskipTests compile` 验证Java proto生成。

**Gate:** 新记录不被索引阻塞，旧任务行为不变。

### Task 4：共享查询与原始材料回查（Worker B）

**Files**
- Modify `J/mapper/ChatSearchMapper.java`、`ChunkMapper.java`、`DailySummaryMapper.java`、`ProfileStatsMapper.java`、`UserTermMapper.java`（后四同mapper目录）。
- 检查/修改 `J/service/impl/TarotContextServiceImpl.java`、`MirrorRolling.java`、`MirrorStatistics.java`、`SnapshotLineageService.java` 及 `J/mapper/SnapshotInputMapper.java`。
- Extend `T/service/RecordSelectiveIndexingDatabaseTest.java`、`RecordAiAuthorizationDatabaseTest.java`；更新 `docs/REFERENCE.md`。

**Interfaces**
- 查询按Spec§5分别校验可读与向量资格。
- 按日期稳定排序 `r.created_at DESC,r.id DESC,c.id DESC`；读单条按 `c.id ASC`，分页不能因同时间戳丢/重片段。
- 关键词只查segment/title，不用c.content整篇匹配未授权内容。
- 统计保持既有record计数/情绪去重口径，不能误用chunk数量充当日记数量；不修改vault权限。

- [x] SQL夹具含同日索引/日常/未授权/别用户/旧PENDING/删除片段。
- [x] 日期/关键词/record_id和情绪统计都能拿到已授权日常；语义只拿到ready且enabled的段。人为放false但残留旧vector，语义仍排除。
- [x] rg搜索所有完整性/授权谓词逐处分类，检查镜子/日报/语境/词典/Tarot，保留窗口与上限；禁止机械替换。
- [x] 测试秘密词只在未授权段时无命中；快照仅登记实际授权来源，撤回无向量段也失效派生内容。
- [x] 测试跨页无重漏、记录数不被分段膨胀、用户归属连接严格；运行 `mvn "-Dtest=RecordSelectiveIndexingDatabaseTest,RecordAiAuthorizationDatabaseTest" test`。

**Gate:** “没索引”只影响向量入口；普通查询覆盖完整授权范围。

### Task 5：graph_v2 工具回查、分页与路由（Worker AI）

**Files**
- Modify AI `agent_data/records.py`、`agent_tools/read_tools.py`、`agent_tools/registry.py`、`agent_core/retrieval.py`，必要时 `agent_core/graph.py`、`agent_core/material.py`。
- Modify `prompts/agent_intent.txt`、`prompts/agent_plan.txt`、`prompts/agent_chat.txt`。
- Extend `tests/agent_v2/test_read_tools.py`、`test_retrieval.py`、`test_material_flow.py`、`test_data_access_integration.py`、`test_answer_acceptance.py`、`fixtures/agent_data.sql`（均位于tests/agent_v2）。
- 更新 AI `README.md` 和 `upload/`。

**Interfaces**
- `RecordRepository.search(...)` 签名不变，查全部授权正文；`search_vector(...)` 加 enabled=true/status=ready。
- `search_records` 仍无mode参数；query是字面关键词，时间/类型/情绪过滤和offset沿用注册表。
- read_record完整授权正文与分页字段按Spec§5；列表是excerpt，full只表示授权范围读完。

- [x] 测日期走search/0次embed、餐厅主题走vector、日常可search/read_record；向量空不等于没记录。
- [x] 普通读不加vector条件；夹具保留库名防误用检查，增加日常样本；setup执行B/v11→v18→reader-role。
- [x] 日期回顾不塞关键词；主题先语义，证据不足查短关键词/日期再读正文；无法定位则澄清。
- [x] 验证向量EMPTY后工具查询成功；工具故障与成功零结果区分；保留硬日期/用户过滤。不要给graph发legacy的mode参数。
- [x] 测末尾>120字事实、21段分页、提前停读、预算耗尽；partial说明范围，保留预算/取消上限。
- [x] 运行 `.venv/Scripts/python.exe -B -m pytest tests/agent_v2/test_read_tools.py tests/agent_v2/test_retrieval.py tests/agent_v2/test_material_flow.py tests/agent_v2/test_answer_acceptance.py -q -p no:cacheprovider`。
- [x] 用隔离reader运行 `.venv/Scripts/python.exe -B -m pytest tests/agent_v2/test_data_access_integration.py -q -p no:cacheprovider`；skip不算通过。同步upload。

**Gate:** 未索引正文可读且覆盖可信；不用模板文字测试代替路由验证。

### Task 6：一次确认的前端选择与状态（Worker F）

**Files**
- Modify F `src/components/organisms/action-forms/RecordActionForm.vue`、`src/components/organisms/RecordReviewWorkspace.vue`、`src/components/molecules/RecordCard.vue`、`src/components/molecules/RecordWorkflowCard.vue`。
- Modify `src/utils/recordWorkflow.js`、`src/composables/useRecordPolling.js`；Create `src/utils/recordIndexing.js`、`tests/recordActionIndexing.test.mjs`。
- Extend `tests/recordActionPresentation.test.js`、`tests/browser/record-workflow-live.mjs`；更新 `docs/record-workflow.md`。

**Interfaces**
- 消费Task3的draft及Task2的VO，兼容camelCase/snake_case既有转换。
- `buildRecordSubmission(payload)`：返回提交对象，保留明确的embeddingEnabled=false，缺失默认true，不改memoryEnabled/todoEnabled。
- `resetEmbeddingSuggestion(segment)`：返回正文修改后的建议true及Spec固定原因；用户随后可以再次关索引。
- version1确认成功就是已保存可查询；索引pending/failed另展示，false+not_requested不是失败。version0原状态显示保留。

- [x] Node行为测试覆盖false不被submit改true、授权不被索引开关改掉、旧payload默认true、改正文重置建议且后续手动选择优先。
- [x] 删除提交中的索引强制全选；AI_ASSISTED授权默认保留，提供可折叠的“参与联想检索”开关和简短原因，不新增必须确认步骤。
- [x] 面向用户只说明“日常内容仍可在对话中按需查询”；未授权段必须显示实际不可读状态，不能说所有已保存内容都能查。主按钮保持一次确认。
- [x] 区分保存/可查询/后台索引；0索引不转圈、失败不变仅保存。按pending结束轮询，不能依赖READY判断索引完成。
- [x] 运行 `node --test tests/recordActionIndexing.test.mjs tests/recordActionPresentation.test.js`、`npm run build`。
- [x] 隔离服务浏览器验收全日常/混合/刷新/改文再选择/失败；桌面与390px手机保留截图和请求证据。

**Gate:** 显示与落库一致，单次确认。

### Task 7：legacy 正文读取与回查（Worker B + AI）

**Owner:** B改Java，AI改自己仓的legacy提示词。本项必做，只补本次对话所需能力，后续单独删除legacy。
**Files:** B沿用Task4 mapper，修改 `J/tools/impl/SearchRecordsTool.java`、`J/pojo/DTO/RetrievedChunkDTO.java`；必要时新增 `J/tools/impl/ReadRecordTool.java`、`T/tools/impl/ReadRecordToolTest.java`；扩展同目录 `SearchRecordsToolRoutingTest.java`。AI改 `prompts/plan_tools.txt`、`prompts/plan_tools_single.txt`。

**Interfaces**
- 先核实是否有等价完整读取；当前search_records仅120字quote，不算正文读取。
- 若缺等价工具，新增 `read_record`，参数 `{record_id,offset:0,limit:20}`，分页结果采用Task5格式。
- Mapper新增 `List<RetrievedChunkDTO> readAuthorizedRecord(UUID userId,Long recordId,int limit,int offset)`，多取一条判断has_more；DTO新增chunkId，提供稳定引用。
- search_records保留legacy mode契约，增加offset/next_offset/truncated/content_scope=excerpt；不能把摘录当全文。

- [x] 写末尾事实、21段分页、未索引可读、未授权不可读测试并验证旧行为缺陷。
- [x] 接入共享可读门槛，实现/复用全文读取；注册表和实际规划工具schema一致，提示词不要声称120字就是全文。
- [x] 测完整正文到达最终回答输入，保留预算和部分覆盖说明。
- [x] 运行 `mvn "-Dtest=SearchRecordsToolRoutingTest,ReadRecordToolTest" test`（复用现有工具时使用实际测试类名）。
- [x] 使用真实模型完成实际 legacy 对话验收；与 graph_v2 一同完成 8 问合成数据矩阵。店名 legacy 首轮未命中，独立复测命中；模型输出与证据保留在 `.verify/record-selective-index/`。
- [x] 保留legacy最小适配清单，供后续单独清理；本期不删旧Chat或共用RPC。

### Task 8：整体验收与交付（协调 agent，三worker配合）

**Files:** 更新 B `docs/PROGRESS.md` 的真实状态；产物放 `.verify/record-selective-index/`，不新增另一套现状文档。
**Interfaces:** 每worker交付文件清单、命令/通过数/跳过数、接口变化、剩余限制；无密钥和真实日记正文。

- [x] 自查Spec逐项覆盖、proto字段/DTO/SQL/VO命名一致，尤其用户授权与索引选择没有串用。
- [x] 隔离库跑fresh schema与“旧数据→v18”；记录全日常、混合、全索引行为及旧数据兼容摘要。数值和 SQL 证据见 `.verify/record-selective-index/`。
- [x] 两引擎分别验8问：餐厅评价、店名、昨日全日常、疲惫、未索引关键词、模糊问法、跨页、空结果；两引擎复测汇总各 7 项通过、1 项人工判断。店名 legacy 首轮未命中但独立复测命中；空结果措辞与 graph_v2 工具读取的判定器已修正，原始首轮输出保留。详见 `.verify/record-selective-index/chat-matrix-real-model-results.json`、`chat-matrix-real-model-results-first-attempt-raw.json` 与 `.verify/record-selective-index/chat-matrix-real-model-final.log`。
- [x] 真实模型复核固定12例（4低信息、8具体信息），记录期望/实际/模型；使用用户指定的 `cn:deepseek-v4.1-flash` 本地 OpenAI-compatible 代理与合成内容，12/12符合预期。详见 `.verify/record-selective-index/real-model-12-case-deepseek.log`、`real-model-12-case-deepseek-results.json` 与 AI `docs/archive/integration/2026-11-13-record-selective-index-real-model.md`。
- [x] 定向回归后各跑一次 `mvn test`、`.venv/Scripts/python.exe -B -m pytest tests/ -q -p no:cacheprovider`、`npm test`、`npm run build`；报告既有失败/skip，不改无关模块。
- [x] 检查 `git diff --check`、AI `scripts/sync_upload.py --check`、三仓proto一致；用户原有改动仍在且未被提交。
- [x] 关闭开关验证新候选version0、存量version1可读、冲突409保留原稿；不删数据/用旧二进制/切引擎。
- [x] 汇报完成/缺口/证据。两引擎实际聊天矩阵、权限、分页和计划内数据路径均有隔离测试；目标运行环境配置与生产迁移仍未验证，不擅自发布。

**交付标准:** 全日常可读且0向量、重要内容能语义找回、未索引内容能工具读全文、权限不变、成长/待办/只读不受索引影响、旧行为兼容，且legacy与graph_v2均有链路验收证据。

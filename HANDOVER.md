# Mu-mirror-B 后端交接文档

> 2026-09-06 多 Agent 开发阶段收尾交接。接手人：项目所有者。
> 配套阅读：`docs/2026-09-03-system-design-v2.md`（主设计，v2.3，裁决 #1-#36）、`docs/coordination/`（各 sprint 设计蓝本）。

## 1. 技术栈与运行

| 项 | 值 |
|---|---|
| Java 21 + Spring Boot 3.5 + MyBatis-Plus | `pom.xml` |
| 端口 | **9050**，上下文 `/api` |
| 激活 profile | dev（application.yml `active: dev`） |
| 数据库 | PostgreSQL `jdbc:postgresql://localhost:5432/mu_mirror`，用户 postgres，docker 容器 `mu-mirror-pg` |
| Redis | localhost:6379 |
| Python AI 服务 | `localhost:50051`（gRPC，不在线时相关功能降级，主流程不炸） |
| 启动类 | `MuMirrorBApplication`（IDEA 直接跑） |
| 测试 | `./mvnw test` —— 当前 **132/132 全绿** |

## 2. 包结构导航（org.xianshen.mumirrorb）

```
controller/        REST 入口（auth/records/chunks/mirror/chat/settings/summaries/vault/glossary/export…）
service/impl/      12 个服务实现，重点：
  MirrorServiceImpl      递归累计镜子（四块组装：prev_mirror/records/correction_index/stats_facts 真源直查）
  ChatServiceImpl        SSE 对话 + 四路检索 + extractVaultRefs（只认 [F\d+] 出文件卡）
  VaultServiceImpl       vault CRUD/配额/去重（update 用 LambdaUpdateWrapper 定向 SET——别改回全列）
  DigestService          vault 消化管道（五态 pending→extracted→confirmed/skipped/failed）
  GlossaryServiceImpl    词典服务（top30 注入/60s 缓存/命中计数）
  ChunkService/RecordService  审核管道（confirmReview 补分类+Embedding 不阻断）
tools/             PlanTools 框架：ToolRegistry/ToolOrchestrator/ToolExecutor + 8 工具实现 + AuditService（tool_calls 落库）
vault/             VaultStorage 接口 + ByteaVaultStorage（PG BYTEA）+ FileTypeDetector（magic bytes）+ ContentExtractor
pipeline/          记录提交管道（事件驱动）
config/            AsyncConfig（vaultDigestExecutor 线程池 core2/max4/queue64）+ MirrorProperties（四道防洪闸）等
pojo/              DO/DTO/VO 三层
```

## 3. 关键配置（application.yml）

```yaml
mirror:                        # 递归镜子四道防洪闸（rolling-mirror-design.md §3）
  lookback-max-chunks: 600
  lookback-max-chars: 150000
  per-chunk-max-chars: 2000
  mirror-summary-after-months: 12
```

gRPC 地址在 application-dev.yml `grpc.client...address: localhost:50051`。LLM/embedding 的**用户级配置在数据库 user_settings 表**（随每次 gRPC 请求下发，yml 里没有）。

## 4. 数据库

- 全量基准：`src/main/resources/db/schema.sql`；增量迁移：`db/migration-v2.sql`（幂等，重放无害）
- 手工进库：`docker exec -it mu-mirror-pg psql -U postgres -d mu_mirror`
- 现状：用户 xxx（密码 111111），records 57 条，vault_items 13 活行全 confirmed，词典 9 词，2026-08 累计快照在位
- ⚠️ **user_settings 里 xxx 的 LLM/embedding 是真实 API key，清测试数据时绝不能动**

## 5. 近两轮大改动（为什么长这样）

1. **递归累计镜子**（1065c25）：镜子=上月镜子全文+本月增量，不是当月切片。回看深度 mirror_lookback 0-3 档在 user_settings。GenerateProfileRequest proto 字段 prev_mirror=11/correction_index=12/mirror_lookback=13（optional，与 AI 仓对账）。
2. **审查修复批**（1065c25→31600cb）：检索放行系统记录但 vault 白名单（`r.source<>'vault' OR keyChunk='true'`）、[F\d+] 引用解析、DigestService 拆分、五态迁移、确认门禁（`POST /vault/{id}/confirm`——确认才 embed，key chunk=key+description+类型拼合）、删除后四位校验头 `X-Confirm-Name`（对话内用 `X-Confirm-Skip: inline` 免校验）。
3. **两高危修复**（31600cb）：① vault update() 改定向 SET 防 digest_status 被覆盖回 pending ② @Async 消化任务专用线程池防静默丢失（原默认 executor 零存活线程，任务被拒无感知——这就是之前上传一直卡"排队中"的根因）。

## 6. 已知问题 / 待办

| 项 | 说明 |
|---|---|
| **上传事务可见性竞态（已立案未修）** | digest 异步线程 READ_COMMITTED 偶发读不到未提交的新行 → 文件卡 pending。修法：上传事务 afterCommit 回调里再触发 digest，或 REQUIRES_NEW。5 连传 3 卡住那次即此问题 |
| **PlanTools 端到端未验证** | tool_calls 表 0 条——"问论文→命中 key chunk→对话出文件卡"链路没真跑过（B3 解析有单测，Python PlanTools 曾超时降级）。联调时先跑这个 |
| B3 解析单测在 VaultRefsParseTest | 反射直测 5 用例；真实链路等上面一条 |
| 每日总结无手动触发端点 | 定时 01:00，调试时只能等或造数据 |

## 7. 联调顺序建议

1. IDEA 起后端 → 日志确认 `vaultDigestExecutor` 线程池初始化
2. Python AI 起在 50051（见 AI 仓 HANDOVER）、桩 LLM 18080（可选，测真实 LLM 则跳过）
3. 前端 `npm run dev`（5199，proxy 已指向 9050）
4. 全链路验证：上传文件→extracted→回执确认→confirmed→对话问"我论文咋样了"→文件卡
5. 有问题先看 digest-N 线程前缀日志（消化管道）与 tool_calls 表（工具执行）

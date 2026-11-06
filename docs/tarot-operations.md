# 塔罗发布与回退

当前目录版本 v2，共 78 张牌（22 张大阿尔卡纳与四组各 14 张小阿尔卡纳）；时间边界 Asia/Shanghai，自然日 00:00；每日牌与问答牌独立持久化，只有每日牌更新收藏。每次抽牌持久化正逆位，重复请求保留原牌及方向。

## 部署顺序

本轮记录流程发布还必须同步执行 [记录分类迁移](record-category-migration.md) 中的 `migration-v13-plan-to-todo.sql`，并发布新增的 `GET /assistant/actions/records` 接口与前端。数据库迁移不能遗漏；本地执行记录不代表正式库已执行。

1. 备份业务库，依次应用 `src/main/resources/db/migration-v12-tarot.sql` 和 `src/main/resources/db/migration-v14-tarot-v2.sql`。v14 为两种抽牌表添加方向及历史索引，已有记录默认正位，可重复执行；新安装使用已包含相同结构的 `db/schema.sql`。不要在真实库运行测试脚本。
2. 部署新版 Python：tarot.proto 生成产物、services/tarot_service.py、mirror_core/tarot_reading.py、prompts/tarot.txt；RPC 注册在现有 legacy 端口。先运行 `scripts/sync_upload.py --check`。
3. 部署 Java：沿用用户模型配置，不新增供应商或系统模型密钥。
4. 部署前端与 `public/art/tarot/v1`、`public/art/tarot/v2` 卡面资源。部署前核对 `tarot/catalog-v2.json` 的每个 `imageUrl` 都有对应资产。确认登录后 `/api/tarot/cards` 返回 78 张有效牌与分类、正逆位牌义字段；保留旧 v1 资产以兼容旧链接。
5. 使用目标模型运行 AI/tests/fixtures/tarot-evaluation.json 的 20 条样例和三类资料组合，确认任务不硬提塔罗、闲聊至多一句、正向但不保证结果、镜子月份归属准确。当前开发验收仅使用合成模型桩，不能替代这一步。

## 状态与恢复

- `GET /tarot/history?limit=20&offset=0` 返回 `items/total/limit/offset/hasMore`，每项包含 `id/kind/cardId/catalogVersion/orientation/drawDate/createdAt/latestAnalysisId`；`kind` 是 daily 或 question，日期统一按上海时区。为后续日历入口保留稳定记录 ID 与日期，目前没有自动写入日历或日记。
- `GET /tarot/readings/{readingId}/analyses` 返回该用户该问题牌的分析版本列表（ID、问题、状态、创建/完成时间），按新到旧排列。正文仍须通过 `GET /tarot/analyses/{analysisId}` 读取，以执行资料授权与版本重校验；不可绕过它直接展示数据库中的旧正文。
- 同一次问题牌重新分析会保存新版本；抽牌历史不因再次分析新增一条抽牌，正逆位也不会增加收藏副本。未收藏卡在图鉴中仍可看黑白插画、名称与牌义。

- daily/draw 和 readings 使用 clientRequestId；响应丢失要重用原 ID。日牌请求别名会跨日返回原牌，不自动抽今天。
- 分析 pending 等待 30 秒；running 总期限 90 秒；gRPC 60 秒；GET 将超期任务置 failed。进程重启后不自动重跑模型，用户可明确重新解读。
- 专用分析执行器 2 个工作线程、16 个排队位置，队列满返回可重试 failed。后台失败不会重抽牌。
- 同 requestId、相同载荷返回同一结果；不同载荷 409。同张牌生成中不接受第二个并行分析。
- 已完成分析读取时重新校验实际引用的授权/版本；失效改 stale，清空正文，原牌保留。
- 无模型配置：抽牌/收藏正常，分析给出设置提示。无需 embedding 服务才能使用塔罗。

## 回退

按发布前记录的提交回退前端、Java 与 Python；数据库新增表保留，不删除用户抽牌与收藏。旧服务忽略新增 proto 字段。旧版前端没有入口；回退 Java 后聊天也不再附带日牌。仅回退 Python、保留新版 Java 会使分析失败，因此要成组回退服务能力。

## 验证

`TarotExperienceDatabaseTest`、`TarotReferencesDatabaseTest`、`TarotAnalysisExperienceTest` 使用显式 `MIRROR_TEST_DB_URL/USERNAME/PASSWORD`，只接受命名的独立测试库并使用隔离 schema。浏览器及全链路测试见前端 `docs/tarot-verification.md` 与本仓 `scripts/tarot_e2e.py`。不得以 skipped 当数据库测试通过。

开发分支全量检查的 9 个 ChatRetrievalTest/ToolsTest 断言失败已在原始 abe7ea2 复现，未混入本功能修改；完整结果见前端验收记录。

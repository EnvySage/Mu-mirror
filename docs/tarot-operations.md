# 塔罗发布与回退

首版 12 张牌，目录版本 v1；时间边界 Asia/Shanghai，自然日 00:00；每日牌与问答牌独立持久化，只有每日牌更新收藏。

## 部署顺序

1. 备份业务库，应用 `src/main/resources/db/migration-v12-tarot.sql`。这是新增表/索引的幂等迁移；新安装使用包含相同结构的 `db/schema.sql`。不要在真实库运行测试脚本。
2. 部署新版 Python：tarot.proto 生成产物、services/tarot_service.py、mirror_core/tarot_reading.py、prompts/tarot.txt；RPC 注册在现有 legacy 端口。先运行 `scripts/sync_upload.py --check`。
3. 部署 Java：沿用用户模型配置，不新增供应商或系统模型密钥。
4. 部署前端与 `public/art/tarot/v1` 全部 13 张 PNG。确认登录后 `/api/tarot/cards` 返回 12 张有效牌。
5. 使用目标模型运行 AI/tests/fixtures/tarot-evaluation.json 的 20 条样例和三类资料组合，确认任务不硬提塔罗、闲聊至多一句、正向但不保证结果、镜子月份归属准确。当前开发验收仅使用合成模型桩，不能替代这一步。

## 状态与恢复

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

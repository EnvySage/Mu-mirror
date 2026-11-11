# 成就系统：数据库升级与维护

版本：V16，2026-11-11。此文记录新增结构、上线顺序和后续扩展入口。

## 数据库变更清单

迁移：`src/main/resources/db/migration-v16-achievements.sql`。前置条件：数据库已更新至 V15；需要有建表、建函数、建触发器权限。

| 结构 | 用途 |
| --- | --- |
| `achievement_definition` | 启动时同步的规则目录及启用状态 |
| `achievement_event` | 同事务入队的待核对事件，含处理时间、重试次数、下次处理时间和错误类型 |
| `user_activity_fact` | 按用户、类型、来源去重的长期事实 |
| `user_achievement` | 用户解锁记录、授予时的规则版本、通知确认时间；用户 + 编号唯一 |
| `user_achievement_showcase` | 8 / 10 位容量、有序编号数组、并发版本 |
| `agent_runs.achievement_model_profile_id` | 新增可空 UUID 列，固定记录本轮开始时使用的模型配置身份 |

新增部分索引：待消费事件索引、未确认解锁索引。新增函数：`achievement_profile_key`、`achievement_fact_change`、`achievement_capture_model`、`achievement_domain_change`。

新增触发器：`user_activity_fact` 插入事件；`agent_runs` 插入时固定模型身份；以下业务表写入或删除时排队核对：`records`、`chunks`、`agent_runs`、`user_settings`、`user_model_profiles`、`todo_registry`、`assistant_actions`、`growth_entries`、`growth_axes`、`profile_snapshots`、`tarot_daily_draw`、`tarot_collection`、`vault_items`、`user_terms`。触发器与业务写入同事务，业务回滚时事件也回滚。

没有删除或改写现有业务列。事件和事实只存 ID、行为类型及时间，不复制日记正文、模型提示词或 API Key。

## 已有环境升级

1. 备份数据库，停止旧应用写入，在同一发布窗口升级后端及前端。
2. 对已完成 V15 的数据库执行 V16，使用事务和遇错停止选项：

```powershell
# PGHOST / PGPORT / PGUSER / PGDATABASE 等使用目标环境配置；密码通过本机安全方式提供。
psql -X -v ON_ERROR_STOP=1 -1 -f src/main/resources/db/migration-v16-achievements.sql
```

3. SQL 成功后启动新版后端。应用不会自动执行 DDL；启动会校验并加载目录，随后后台核对旧用户。
4. 发布新版前端，登录检查图鉴、通知确认和展示架。通知初次补发可能包含多枚历史成就。

迁移支持重复执行；重复执行不会重复授予成就。SQL 失败会整体回滚，先修正前置版本或权限后重试。**不要跳过 SQL 直接启动新版后端。**

新建数据库使用 `src/main/resources/db/schema.sql`，它已包含 V16。基线中的 V16 段由同步脚本生成，无需再逐份执行 V1–V16。

回退应用版本时可以保留这些附加结构，已有业务仍能运行；旧应用运行期间事件会积累。回到新版后继续处理。不要用删表方式回退，以免丢失已获得成就及展架。若需长期停用，应另行发布有版本记录的停用迁移，明确移除触发器与后台任务。

## 老用户补发与口径

V16 会保存现存成功对话、已完成待办和已自定义成长维度的可证明事实，并为所有旧用户排队。当前仍保留的记录、词典、塔罗、资产、画像和成长记录按现有数据核对。

无法从旧数据可靠证明的行为不猜测补发：旧的页面查看、历史模型连接测试、旧轮次所用模型身份，以及旧 legacy 对话是否真正带入词典。graph_v2 对话若保留了成功回复与已应用词典标记，可补发词典章。旧数据后补的 `unlocked_at` 是本次识别并授予的时间，不冒充原始完成时间。

| 分类 / 编号 | 核对依据 |
| --- | --- |
| M-01 / M-02 / M-03 | 同一已保存模型版本测试通过并被应用 / 首条用户记忆 READY / 两个不同已保存模型各完成成功对话 |
| R-01 / R-02 / R-03 | 未删除、已保存的用户记录 1 条 / 分布 7 日 / 30 条；不要求连续 |
| D-01 / D-02 / D-03 | live 完成且有非兜底非空回复 / 20 轮且分布 5 日 / 打开有有效回复的往日会话 |
| K-01 / K-02 / K-03 | 用户记录 READY / 已确认词典项 / 同轮词典命中并成功回复；失败回复不计 |
| P-01 / P-02 / P-03 | 有效待办 / 3 个不同待办完成 / AI 行动 SUCCEEDED 且结果 APPLIED |
| C-01 / C-02 / C-03 | 展开已有总结 1 日 / 7 个不同 summaryDate / 打开至少 30 天前记录 |
| REF-01 / REF-02 / REF-03 | 已生成画像 / 3 个不同月度画像 / 打开两个不同本人快照的对比 |
| T-01 / T-02 / T-03 | 每日抽牌 1 日 / 7 日 / 收藏目录中的全部 22 张大阿卡纳 |
| V-01 / V-02 / V-03 | 已确认归档资产 1 个 / 10 个 / 打开对话中检索出的已归档资产 |
| G-01 / G-02 / G-03 | 正向成长记录 / 涉及 3 个不同维度 / 保存自定义维度 |
| S-01 / S-02 / S-03 | READY 已批准记忆中的 5 种情绪 / 4 种内容类型 / 服务端生成导出 |
| SYS-01 | 前端保存默认首页后上报的有效首页选项 |

READY 同时为 M-02 提供“检索配置实际产生过可用记忆”的依据；仅填写检索配置、没有成功就绪的记忆不发章。已获得后不会因删除来源而撤章；未获章的当前状态统计会随删除变化。待办反复完成同一项只计一次；总结按日期去重；对话按 run ID 去重，清理旧 run 不清理已有成功事实。

## API 契约

均位于 `/api/achievements`，复用 JWT 与 `R` 响应包装。不接收客户端用户 ID。

| 方法 | 路径 | 数据 |
| --- | --- | --- |
| GET | `/achievements` | `revision`、`items`；每项含公开定义、earned、unlockedAt、progress |
| GET | `/achievements/pending` | 最多 50 条待展示解锁；读取不自动确认 |
| POST | `/achievements/acknowledge` | `{ "codes": ["D-01"] }`；幂等确认本人通知 |
| GET | `/achievements/showcase` | `{capacity,codes,version}`；默认 8 位空架、version=0 |
| PUT | `/achievements/showcase` | `{capacity,codes,expectedVersion}`；成功后 version 增加 |
| POST | `/achievements/activity` | `{kind,subjectId,otherId,value}`；仅允许白名单查看动作 |

展示架只接受本人已获得、当前启用、互不重复的编号。只允许 8 / 10 位；超出容量拒绝。版本过期返回业务 code=409，客户端应重新读取。没有任意授章、修改别人成就、客户端累计次数接口。

原子进度为 `{current,target}`，组合为 `{all:[...]}` 或 `{any:[...]}`。通知按“至少展示一次”设计：关闭后确认，断网或多设备可能再出现，数据库授章本身始终只有一次。前端当前每 10 秒检查一次，页面隐藏或其他弹窗打开时延后。

## 扩展与目录同步

后端目录是规则源；前端 `src/preview/achievementCatalog.json` 是生成的展示副本。正式账号不靠副本判断解锁。

```powershell
# 在 Mu-mirror-B 执行；默认查找相邻的 Mu-mirror-F
python scripts/sync-achievements.py --write
python scripts/sync-achievements.py
# 非相邻 checkout 可指定：--frontend C:/path/to/Mu-mirror-F
```

更改文案、难度、章面不需要改表；新增阈值章一般也不需要改表。新增统计维度先查 `AchievementMetrics` 能否复用。确有新 DDL 时新增 V17 等迁移，并同时更新新库基线；不要在已部署 V16 上偷偷追加新结构。

删除目录项会在启动时停用数据库定义，既有授予记录保留。多实例发布涉及规则变更时，需确保实例使用同一目录版本，避免不同版本争相同步定义；当前没有在线规则管理后台。

## 事件处理与排查

后台默认每 2 秒取一个用户的至多 100 个事件，事务内锁定并核对；使用 `SKIP LOCKED`，崩溃回滚后可重新消费。失败退避至最多 300 秒，错误仅存异常类型。每小时删除最多 5000 条、已处理超过 7 天的事件。长期事实和获章记录不随队列清理。

可配置 `mirror.achievements.worker-delay-ms` 与 `mirror.achievements.cleanup-delay-ms`。本版适合当前规模，核对会查询业务表，没有引入一套新的累计值缓存；业务规模增长时，先根据队列延迟和数据库耗时判断是否需要增加批量统计或投影。

```sql
SELECT count(*) FROM achievement_definition WHERE enabled; -- 当前目录为 56（原 34 + 第一批 22）
SELECT count(*) AS pending, min(created_at) AS oldest FROM achievement_event WHERE processed_at IS NULL;
SELECT kind, attempts, available_at, last_error FROM achievement_event
 WHERE processed_at IS NULL AND attempts>0 ORDER BY available_at LIMIT 20;
SELECT code, count(*) FROM user_achievement GROUP BY code ORDER BY code;
```

## 验证

`AchievementDatabaseTest` 使用真实 PostgreSQL，随机独立 schema，结束后清理；测试拒绝非 `codex_assistant_growth_test_` 前缀的数据库。必须指向可丢弃环境并安装 pgvector；不要使用业务数据库。重复迁移、事务回滚、重复消费、失败对话、按日期去重、跨用户来源校验、隐藏条件、并发发章、展架版本冲突等均有行为测试。

```powershell
$env:MIRROR_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55439/codex_assistant_growth_test_achievements'
$env:MIRROR_TEST_DB_USERNAME='mirror_test'
# 在本地设置 MIRROR_TEST_DB_PASSWORD，不把真实环境密码写入文档
mvn '-Dtest=AchievementDatabaseTest' test
```

全量 Spring 测试还需将 `SPRING_DATASOURCE_*` 指向同一个隔离库，使用 test profile 及本地测试 JWT 配置。前端执行 `npm test` 和 `npm run build`，再通过真实浏览器验证账号 API、领取通知、展架保存及刷新恢复。调用真实模型提供商的连通性需要可用的 Python 服务和用户模型配置，不由本数据库测试伪造通过。

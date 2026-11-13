# 成就系统：升级、接口与维护

版本：V17，2026-11-12。目录 96 枚普通章、24 项隐藏镀层挑战。行为口径见 [维护约定](decisions-achievements.md)，完整目录见相邻前端 `docs/achievement-catalog-review.md`。

## 数据库升级

已有库先具备 V16，再执行 `src/main/resources/db/migration-v17-achievement-progression.sql`。应用不会自动执行 DDL；备份、停止旧实例写入，同一发布窗口升级数据库与两端代码：

```powershell
psql -X -v ON_ERROR_STOP=1 -1 -f src/main/resources/db/migration-v16-achievements.sql
psql -X -v ON_ERROR_STOP=1 -1 -f src/main/resources/db/migration-v17-achievement-progression.sql
```

已完成 V16 时只执行 V17。迁移可重复执行。新库直接执行 `schema.sql`，它已包含 V16/V17，不逐份重放。连接差异和密码只放本机配置或环境变量。

| V17 结构 | 用途 |
| --- | --- |
| `user_achievement_activity` | 结构化行为：用户、类型、对象、关联、上下文、值、时间、上海日期；同日同一动作去重 |
| `user_achievement.plated_at` | 永久镀层授予时间 |
| `user_achievement.plating_rule_version` | 授予时的隐藏规则版本 |
| `user_achievement.plating_acknowledged_at` | 与普通章独立的通知确认 |
| `agent_runs.achievement_settings` | 本轮开始时的配置和验证快照，不保存明文凭据 |
| `records.achievement_default_mode` | 创建记录时的默认记录方式快照 |

新增行为入队、业务变更和记录方式捕获触发器，并扩展模型捕获函数。下一次结构变更新增 V18，同时更新新库基线，不改写已发布 V16/V17。

回退应用可保留附加结构与授予，旧程序期间事件会积累，恢复新版后继续消费。不要删表回退；长期停用需单独迁移移除触发器及后台消费。

## 规则与补发

普通目录由 `achievements/catalog.json` 索引 12 份 `catalog-*.json`；隐藏规则独立放 `plating-rules.json`。共享统计和关联、连续窗口逻辑集中在 `AchievementMetrics`、`AchievementExtendedMetrics`、`AchievementActivityMetrics` 和 `AchievementSequences`。

启动同步定义并以 revision 为旧用户排队；图鉴及 pending 读取也核对。普通章和镀层均幂等且永久保留；先达到更高挑战时同时授予原章。补发时间是识别时间，不伪造历史完成时间。

只按可证明事实补发。旧查看、本人编辑、连接测试、配置使用、缺失来源清单不能猜补。成功回复事实保留跨 run 清理的验证能力；首次正分事实不随成长来源级联删除，防止后自定义被误认成首次计分前。日期统一 `Asia/Shanghai`，周一开始自然周，月份含年份，同年四季度不能跨年拼接。

事实只存 ID、枚举、配置指纹和时间，不复制日记正文、提示词或 API Key。客户端不能上报成功对话、连接成功、导出成功或本人编辑等服务端事实。引用打开必须匹配本人的成功非兜底回复及实际返回的来源。

隐藏普通章获得前 API 遮住名称、图案、普通条件和进度；镀层只返回资格、已获状态和时间，挑战规则、目标和进度不出 API 或前端生成目录。设计预览与真实账号状态隔离。

## API

复用 JWT 与 `R` 包装，不接收客户端用户 ID。

| 方法 | 路径（含 /api） | 契约 |
| --- | --- | --- |
| GET | `/achievements` | `{revision,items}`；公开定义、`earned/unlockedAt/progress`、`plating/plated/platedAt` |
| GET | `/achievements/pending` | 至多 50 条 `{code,kind,unlocked_at}`，kind=`earned`/`plated`；读取不确认 |
| POST | `/achievements/acknowledge` | `{notifications:[{code,kind}]}` 精确确认；兼容 `{codes:[...]}` 只确认普通章 |
| GET | `/achievements/showcase` | `{capacity,codes,version}`，默认 8 位空架、版本 0 |
| PUT | `/achievements/showcase` | `{capacity,codes,expectedVersion}`；8/10 位；本人已获且启用、不同编号；版本过期业务 code=409 |
| POST | `/achievements/activity` | `{kind,subjectId,otherId,value,contextId}`，仅白名单查看/本机设置动作 |
| GET | `/vault/{id}/chunks` | 本人未删、extracted/confirmed 的 `{id,segment}` 数组，排除内部 keyChunk |

响应经前端请求封装转换为 snake_case；普通进度为 `{current,target}` 或 `{all:[...]}`/`{any:[...]}`。通知每 10 秒检查，页面隐藏或已有模态框时延后。单项沿用详情揭晓，多项在限宽 640px 的同一弹窗中并列展示（桌面三列、手机两列），内容过多时向下滚动、底部操作固定。关闭时只将弹窗打开时的 `(code,kind)` 快照通过一次批量请求确认；后来新增的通知留到下一批。普通章和镀层分别展示、分别确认。断网或多设备可能再次展示，数据库授予只发生一次。

查看白名单包含总结及真实来源、历史会话、日历、对话记录/资产引用、词典/待办来源、画像全文/档案/对比、塔罗正逆释义、文件预览/片段检查、情绪/类型/标签筛选、布局与设置区域。资源须本人可访问且状态、上下文匹配；来源详情成功加载后才上报。阅读必须前台滚到底；不能用展开或刷新冒充完成。

新回复的引用上下文使用 SSE `done.assistantMessageId`，恢复接口用 `assistant.id`。总结来源仅使用新生成总结 `stats.sourceRecordIds`（前端 `source_record_ids`），不从日期猜测旧总结实际输入。

## 同步与扩展

```powershell
python scripts/sync-achievements.py --write
python scripts/sync-achievements.py
python -m unittest discover -s scripts/tests -p test_sync_achievements.py -v
```

脚本按公开字段白名单汇总分片，生成相邻前端 `src/preview/achievementCatalog.json`，并同步 schema 中 V16/V17 标记块，保留其他后缀。非相邻 checkout 可指定 `--frontend C:/path/to/Mu-mirror-F`。生成文件不手改。

新增阈值/组合优先复用现有指标；新来源按业务成功、归属、去重和时间顺序建可信事实，不复制逐章业务流程。变更语义递增 ruleVersion，仍保留已得授予。镀层共用原 code，不占第二个展位。

## 运维与验证

后台每 2 秒处理一个用户至多 100 个事件，事务内 `SKIP LOCKED`、幂等核对；失败退避至多 300 秒。每小时清理至多 5000 条已处理超过 7 天的队列项，不清理长期事实、授予或展架。

```sql
SELECT count(*) FROM achievement_definition WHERE enabled; -- 96
SELECT count(*) AS pending,min(created_at) AS oldest FROM achievement_event WHERE processed_at IS NULL;
SELECT kind,attempts,available_at,last_error FROM achievement_event
 WHERE processed_at IS NULL AND attempts>0 ORDER BY available_at LIMIT 20;
SELECT code,count(*),count(plated_at) AS plated FROM user_achievement GROUP BY code ORDER BY code;
```

可配置 worker-delay-ms / cleanup-delay-ms。扩容前先量队列延迟、每用户核对耗时和账本体积，再考虑批量统计/投影。

数据库测试要求真实 PostgreSQL + pgvector、可丢弃 `codex_assistant_growth_test_*` 库，随机 schema 自动清理；缺少环境变量而跳过不能算通过。

```powershell
$env:MIRROR_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55439/codex_assistant_growth_test_achievements_v17'
$env:MIRROR_TEST_DB_USERNAME='mirror_test'
# 本机提供 MIRROR_TEST_DB_PASSWORD
.\mvnw.cmd '-Dtest=Achievement*Test,AgentRunAchievementContextTest,SettingsServiceImplTest,ModelProfileServiceTest' test
```

覆盖 96 普通规则、24 镀层逐项正反例、来源校验、时序窗口、迁移/消费幂等、永久授予、跨用户、并发、通知和展架冲突、真实导出与连接测试入口。前端执行 `npm test`、`npm run build` 及 `tests/browser/achievements-live.mjs` 的真实账号桌面/手机流程。外部真实模型连通性需 Python 服务及用户配置，不由本数据库测试模拟通过。

自由标签统一写 userTags，编辑时清理旧 user_tags/tags 别名，保证写入、筛选与成就验证使用同一值。首次成长维度初始化使用幂等插入，允许并发的多个页面请求。

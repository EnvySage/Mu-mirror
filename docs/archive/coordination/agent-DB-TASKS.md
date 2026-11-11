# 数据回填 + 按月快照生成 任务书（Agent 单轮）

## 背景与目标
用户要求能生成历史月份（如 8 月）的镜子快照。当前两个障碍：① 57 条日记的 created_at 全挤在 2026-09-06（批量插入时间），不是日记归属日期，日历/按日查询/活跃时段统计全错；② 快照生成统计窗口写死"当前往前 30 天"，无法按月生成。本任务两步修完。

## 仓库
E:\project\mirror\own\Mu-mirror-B（Spring Boot 3.5 / Java 21 / MyBatis-Plus，端口 9050，上下文路径 /api）

## 任务 1：created_at 数据回填（先做，一次性）

### 1a. 摸清数据
- 库：`docker exec -i mu-mirror-pg psql -U postgres -d mu_mirror`（heredoc 必须 -i）
- 57 条 records（id 10001~10057），chunks 表有对应切片。日记归属日期可从三处反推：① metadata->>'summary' 与 title ② segment/content 正文里的事件（FGO 活动结算=9 月初、实训中期答辩=7 月下旬、回家=7 月底断更等）③ E:\project\mirror\own\coordination\logs\agent-DB.md T-DB-7 条目里有完整弧线说明（哪段日期讲了什么）
- 弧线锚点：7 月中下旬实训 → 7-29~7-31 前后回家断更 → 8 月上旬追番工具项目 → 8 月下旬开题/手办/跑步 → 9 月开学/FGO/组会。写日记时间多为晚上 22-23 点，凌晨 1 点一条（熬夜返工那条）

### 1b. 回填
- 写 Python 脚本（E:\project\mirror\own\coordination\tmp\fix_created_at.py，用 E:/python/python.exe，**禁用裸 python**）：
  - SELECT 全部 records + chunks（id/segment/title/summary），按弧线锚点给每条 record 判定归属日期（YYYY-MM-DD）
  - 生成 UPDATE：records.created_at / records.updated_at，chunks.created_at / chunks.updated_at 同步改为所属 record 的日期 + 原时段（22-23 点为主，保留原时分秒的时段感）
  - **注意**：同一天多记录（9/6 有 5 条）保持各自时段错开
- 执行后验证：
  - 每月记录数（7 月约 15-18 / 8 月约 20-22 / 9 月约 18-20，与弧线密度吻合）
  - 日历口径抽查：`TO_CHAR(created_at AT TIME ZONE 'Asia/Shanghai','YYYY-MM')` 分组计数
  - chunks 与所属 record 日期一致性（join 校验 0 不一致）

## 任务 2：按月快照生成（B 侧功能）

### 2a. collectStats 加月份参数
- `MirrorServiceImpl.collectStats(UUID userId)` → `collectStats(UUID userId, YearMonth month)`（保留原签名重载，默认 YearMonth.now(ZONE) 向后兼容）
- since = month.atDay(1).atStartOfDay(ZONE)；until = month.plusMonths(1).atDay(1).atStartOfDay(ZONE)——**检查 ProfileStatsMapper 的各 select SQL 是否有上界**，若只有 since 条件需加 `AND created_at < #{until}`（防止 9 月生成把 9/6 之后的未来日记算进去；同理月度中段生成也要截断）
- time_range 动态化：`month.getYear() + "年" + month.getMonthValue() + "月"`，替换硬编码"最近30天"（generate() 的 manual 快照继续用默认"最近30天"语义——只有 monthly 类型才按月）

### 2b. generateMonthly 支持指定月份
- `generateMonthly(UUID userId)` → `generateMonthly(UUID userId, YearMonth month)`，重载保留（定时任务传 YearMonth.now(ZONE).minusMonths(1)——**注意语义变化**：月度定时任务应生成"上个月"的完整画像，比当前实现更正确，日志说明这个行为变化）
- 挂接：MirrorService 接口加 `MirrorProfileVO generateMonthlyFor(UUID userId, String month)`（"2026-08" 格式，可空=上个月），MirrorController 加 `POST /api/mirror/generate-monthly?month=2026-08`（month 可空）
- 保留策略：monthly 快照保留 12 份的清理逻辑核对按月生成是否兼容
- **幂等**：同一 (user, month) 已有 monthly 快照时——重新生成则替换（删除旧的或标记 superseded，看现有表结构决定），在 controller 层校验 month 不能是当前月（当前月用 manual 语义），不能晚于当前月

### 2c. 测试
- 单测：MirrorStatsTest 补月份窗口用例（8 月窗口只统计 8 月记录、time_range="2026年8月"）
- 真库实测（9051 临时实例，模式同前）：生成 2026-08 快照 → 验证 profile_snapshots 有 monthly 8 月记录、五维数据是 8 月语料（不掺 7 月实训/9 月开学内容）→ 生成 2026-07 → 验证 3 份快照并存
- `./mvnw test` 既有 90 全过 + 新增用例

## 约束
- 不要 git commit；不要动 F 仓/AI 仓；key 不落日志
- 写日志 E:\project\mirror\own\coordination\logs\agent-DB.md（T-DB-9 条目）
- 供应商网关不稳，中断自重试

## 最终返回
回填前后每月记录数对照、抽样 3 条回填前后日期、单测+真库实测结果、改动文件清单、偏差

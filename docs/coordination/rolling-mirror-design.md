# 递归累计镜子（Rolling Mirror）设计稿 v1.0

> 2026-09-06 总指挥与用户对齐定稿。镜子语义从"当月切片"改为"截至当月的累计画像"。
> 原则：**叙事让 LLM 传承，账本让数据库记**；四道防洪闸全配置化。

## 0. 语义变更

| 项 | 旧（切片） | 新（累计） |
|---|---|---|
| N 月镜子 | 只看 N 月记录 | 上一份镜子（N-1）+ N 月增量 = **截至 N 月的你** |
| 首月（genesis） | — | 无上月镜子，全量生成（7 月已是 genesis） |
| 对比模式 Δ | 两段切片对比 | 相邻月相减 = "这一个月你变了什么"（漂移逻辑不动） |
| 幽灵卡/时间线/按月接口 | — | 全部保留，节点语义变为累计 |

## 1. 生成时 LLM 输入四块（prompt 结构）

```
① 上期镜子全文（N-1 月快照的 overall_summary + 五维分析拼接；超 12 个月的更早镜子只带一行压缩摘要）
② 本月原始记录（按回看深度档位带原文，见 §2）
③ 校正索引（上期镜子涉及的记录 title+日期清单，仅当回看深度=0 时带上——低档用户防误差累积的补偿）
④ 待办/统计实况直查（数据库实时值：未完成/已完成待办、本月记录数、情绪分布计数——LLM 只叙事不记账）
```

genesis 月无①③。④ 每次生成都查，与 get_stats 同源 SQL。

## 2. 回看深度滑块（mirror_lookback，0-3，默认 1）

| 档 | 原文带入 | ③ 校正索引 |
|---|---|---|
| 0 | 无（纯继承） | 带（唯一防误差手段） |
| 1 | 上月原文（默认） | 不带 |
| 2 | 近三月原文 | 不带 |
| 3 | 全部历史原文 | 不带 |

- 设置页位置：记忆权重滑块（rag_half_life）旁，同一组"镜子引擎"配置；文案：「生成镜子时带多少原文回看：0=只继承上月镜子 / 1=上月原文 / 2=近三月原文 / 3=全部原文（慢，消耗大）」
- user_settings 加列 `mirror_lookback INT DEFAULT 1`

## 3. 四道防洪闸（全配置化，触发时日志+前端 toast 提示"已截取最近部分"）

```yaml
mirror:
  lookback_max_chunks: 600      # 条数闸，超限取最近的
  lookback_max_chars: 150000    # 总字符闸（与条数闸先触发者生效），旧→新裁剪直到塞下
  per_chunk_max_chars: 2000     # 单条日记渲染截断
  mirror_summary_after_months: 12  # 递归链超 12 个月，更早镜子只带一行摘要
```

## 4. 分工

### B 侧（Java）
1. user_settings 加 `mirror_lookback INT DEFAULT 1`（活库 ALTER + schema/migration + SettingsDTO/VO + PUT 设置接口透传）
2. GenerateProfileRequest 组装：查上一份 monthly 快照全文做①；按 lookback 档位带②（四闸限流）+③；实时查④（复用 stats mapper）
3. proto：GenerateProfileRequest 加字段 `prev_mirror=10（string，上一份镜子全文，可空）`、`correction_index=11（string）`、`mirror_lookback=12（int32）`——字段号与 AI 仓对账后登记 shared-protocol（④用现有统计字段，不加 proto）
4. 快照表加 `period_month CHAR(7)` 列（补上轮幂等尾巴）：生成时写归属月份，幂等判断切精确列，历史 3 行 UPDATE 补值；活库 ALTER + schema/migration
5. 单测（窗口/闸门/真源直查）+ 真库实测（生成 8 月累计镜子，验证内容含 7 月元素）

### AI 侧（Python）
1. GenerateProfile prompt 重写（第 6 套 profile.txt 改造）：
   - 语义声明改为"撰写**截至 N 月的累计画像**——承续上月镜子，融合本月新记录，待办/统计以给定实况为准"
   - 四块输入渲染：{prev_mirror}/{records}/{correction_index}/{stats_facts} 占位符；空块不留孤儿节头（glossary 同模式）
   - 明确指令：待办状态以④为准（不继承①中的旧说法）；本月日记未提但④显示已完成的事项写"本月完成"
2. PlanTools/chat prompt 不动；tool_results/glossary 渲染不受影响
3. E2E：有上月镜子（累计断言：本月镜子文本应提及上月元素）/genesis（无①正常）/闸门截断/待办状态矛盾（①说没做、④说做完了→输出已完成）/空块
4. 单测 + 全量回归

### F 侧（前端）
1. 设置页"镜子引擎"组：回看深度下拉（0-3 四档文案照设计稿 §2）+ 3 档慢提示；走 settings store PUT
2. 生成完成 toast：截断发生时后端如返回 meta 提示则显示"历史记录较多，已按回看深度截取最近部分"（若后端未透出则本项降级为不做，日志声明）
3. 幽灵卡/时间线不动；B 部署后 USE_MOCK_MONTHLY 切 false 联调验证真累计镜子
4. 走查+build+375px

## 5. 联调验收口径

- 重启后端 → F 切 USE_MOCK_MONTHLY=false → 幽灵卡生成 8 月 → **8 月镜子应同时提及 7 月实训（继承）与 8 月追番工具（增量）**，待办状态与设置页实况一致
- 切回看深度 0 → 再生成 → 镜子仍累计（继承链生效）但细节更少
- tool_calls 无异常；92+ 测试回归全绿

## 6. 分工顺序

B（proto 字段+真源直查+闸门+period_month）与 AI（prompt 重写）可并行（proto 字段号 B 提案登记，AI 对账）——但 AI 仓 proto 源头惯例：本轮 proto 由 **B 提案、AI 落地**（GenerateProfileRequest 在 AI 仓 mirror_profile.proto）。F 独立并行（滑块不依赖后端新字段可先做 UI+mock）。

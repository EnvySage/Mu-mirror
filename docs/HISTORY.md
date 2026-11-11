# Mu-mirror-B 历史摘要

本文件汇总已归档文档的结论，避免为了查历史反复读旧稿。原文在 `archive/`，代码实现与当前行为以 `PROGRESS.md` 为准。

## 时间线

- 2026-07/08：早期 AI 日记、Java 实现、AI 服务与优化设计。原文在 `archive/2026-07-*`、`archive/2026-08-*`。
- 2026-09-03：v2.3 总体设计，确立 Chunk 为唯一业务单元、审核门禁、四路检索、递归画像、词典、vault、待办。原文在 `archive/2026-09-03-system-design-v2.md`。
- 2026-09：四 Agent 并行 sprint。任务书、进度板、词典、递归镜子、工具调用/vault、待办状态移除等设计。原文在 `archive/coordination/`。
- 2026-10-16~18：Agent v2、异步向量化、镜子归档与 lineage、成长改造的设计与验收。原文在 `archive/specs/`、`archive/reports/`、`archive/plans/`。
- 2026-10-21：Agent v2 三端合并与操作卡工具修复。原文在 `archive/`（AI 仓）与本仓历史记录。
- 2026-11-11：成就系统第一批、塔罗 v2、模型档案、文档收口。

## 仍然有效的历史裁决

1. **Chunk 是唯一业务单元**：记录只存原文与状态；片段承载文本、元数据、向量与授权。
2. **记忆与待办分别授权**：`ai_memory_approved`、`todo_approved` 独立；只有用户确认才进入检索或待办。
3. **审核按文本变化补分类**：文本没变直接入库；文本变过才对该片段 `Classify(single=true)`。
4. **镜像 = 累计画像**：上月镜子 + 本月增量；叙事由 LLM 传承，待办/统计从数据库实查。
5. **工具执行在 Java，决策在 Python**：Python 无状态，只读视图 + 具名写命令。
6. **失败降级不静默**：Embedding 耗尽降级为仅保存；工具失败保留已有材料；写入结果未知先查回执。
7. **时区统一 Asia/Shanghai**；业务日期左闭右开。
8. **成就规则集中**：`catalog.json` 是唯一规则源，事件队列 + 后台 reconcile，已授予不撤销。

## 已归档目录

| 目录 | 内容 |
|---|---|
| `archive/coordination/` | 2026-09 四 Agent 任务书、进度板、词典/镜子/工具/vault/待办设计、对话循环设计与联调 |
| `archive/specs/` | 2026-10 Agent v2、异步向量、镜子归档、成长改造设计 |
| `archive/reports/` | 成长改造验收报告 |
| `archive/plans/` | 已完成实施计划 |
| `archive/HANDOVER-2026-09-06.md` | 旧交接快照 |
| `archive/todo-workflow-fix.md` | 一次性待办修复说明 |
| `archive/2026-*` | 早期设计与旧总体设计 |

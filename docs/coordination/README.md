# 协作中心（coordination/）

四 Agent 并行开发协调目录。总指挥 = 主会话（Claude Code 主线程）。

## 结构
- `agent-DB-TASKS.md` / `agent-AI-TASKS.md` / `agent-B-TASKS.md` / `agent-F-TASKS.md` — 四 Agent 任务书（读自己那份）
- `logs/` — 进度日志（每 Agent 一个文件，每完成一项追加一条）
- `PROGRESS-BOARD.md` — 进度总表（**只有总指挥写**，Agent 只读）
- `shared-protocol.md` — gRPC 契约变更登记（B 或 AI 改 proto 必须在此登记，对方开工前必读）

## 协同规则
1. 每个 Agent 只改自己仓库 + 自己日志文件
2. 交叉依赖走本目录：DB 变更请求写日志，proto 变更写 shared-protocol.md
3. Agent 完成或阻塞时在日志尾部写 `[STATUS] done|blocked|in-progress <说明>`
4. 总指挥汇总写 PROGRESS-BOARD.md

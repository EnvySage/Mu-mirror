# Mu-mirror-B 文档索引

当前真相只维护 `PROGRESS.md`。其他文档分三类：当前参考、模块运维、历史归档。与代码冲突时，以代码为准并回来更新本索引和 `PROGRESS.md`。

## 当前文档

| 文档 | 用途 |
|---|---|
| [README](../README.md) | 快速开始、架构、部署 |
| [PROGRESS](PROGRESS.md) | 当前模块状态、对话链路、数据模型、已知风险 |
| [REFERENCE](REFERENCE.md) | REST API、关键配置、测试地图 |
| [ONBOARDING](ONBOARDING.md) | 接手路线：从对话链路开始读懂系统 |
| [shared-protocol](shared-protocol.md) | gRPC 契约变更登记，改 proto 必须更新 |
| [agent-v2-rollout](agent-v2-rollout.md) | graph_v2 灰度、回滚与验收门槛 |
| [achievements-operations](achievements-operations.md) | 成就 V16 升级与维护 |
| [achievement-implementation-plan](achievement-implementation-plan.md) | 成就四批计划（第一批已完成） |
| [decisions-achievements](decisions-achievements.md) | 成就方案裁决与维护约定 |
| [tarot-operations](tarot-operations.md) | 塔罗发布、迁移与回退 |
| [model-profiles](model-profiles.md) | 模型档案 V15 接口与行为 |
| [record-category-migration](record-category-migration.md) | plan→todo 分类迁移 |
| [HISTORY](HISTORY.md) | 已归档设计与一次性记录的一页摘要 |
| [archive/](archive/) | 历史设计、specs、reports、已完成计划与旧 sprint 文档 |

## 维护规则

1. **代码优先**：文档与实现冲突时，以当前代码为准。
2. **一处维护**：当前状态只写 `PROGRESS.md`；API/配置/测试写 `REFERENCE.md`；历史细节放 `HISTORY.md` 或 `archive/`。
3. **完成后归档**：一次性任务书、诊断、联调记录、完成态计划移入 `archive/`，不要留在 `docs/` 根目录。
4. **文件大小**：pre-commit 拒绝超过约 20KB 的文本文件；`PROGRESS.md`、`REFERENCE.md` 已按职责拆分。
5. **新克隆**：运行 `git config core.hooksPath .githooks` 启用钩子。

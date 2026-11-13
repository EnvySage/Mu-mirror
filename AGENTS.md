# Mu-mirror-B

Java 后端负责 REST API、业务规则、用户授权和 PostgreSQL 数据。前端通过 REST 调用本仓，推理由 Python gRPC 服务提供。

- 跨端进度和历史入口见 [项目进度](docs/PROGRESS.md)，启动与联调见 [README](README.md)。
- 写新函数或规则前先找现有实现。读写数据时从认证上下文取得用户身份，并在查询和更新中限制归属。
- pre-commit 对超过约 20KB 的文本文件给出提醒；按职责评估是否拆分。高风险改动仍须同步维护文档。
- 新克隆后运行 `git config core.hooksPath .githooks` 启用钩子。
- 数据库变更同时核对 [基线 DDL](src/main/resources/db/schema.sql) 和已有库的迁移路径。发布或回退按对应专题文档执行。
- 表结构变更必须同时更新 `schema.sql` 并新增下一编号迁移；两台开发机都执行同一迁移，连接差异只放 `application-local.yml`。
- 改 gRPC 契约时先对齐 AI 仓的源定义，再同步 [Java proto](src/main/proto/) 并更新 [协议登记](docs/shared-protocol.md)。

## 文档维护契约

- **单一来源**：当前状态只写 `docs/PROGRESS.md`；API/配置/测试写 `docs/REFERENCE.md`；双机环境写 `docs/LOCAL-ENV.md`；模块运维写对应 `docs/*-operations.md`；一次性任务完成即移入 `docs/archive/`。
- **同一提交更新**：改 Controller/API、proto、`schema.sql`/迁移、`application*.yml` 或模块状态时，必须在同一提交更新对应文档；不要等代码合并后再补。
- **禁止文档泛滥**：不新建平行的“进度/现状/总结”文档；同一事实只写一处，需要新视角时扩展现有文档或写进 `docs/HISTORY.md`。
- **提交门禁**：pre-commit 会拦截“高风险改动但没有文档改动”的提交；紧急情况用 `SKIP_DOC_CHECK=1`，并在提交说明里写明原因。

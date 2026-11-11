# Mu-mirror-B

Java 后端负责 REST API、业务规则、用户授权和 PostgreSQL 数据。前端通过 REST 调用本仓，推理由 Python gRPC 服务提供。

- 跨端进度和历史入口见 [项目进度](docs/PROGRESS.md)，启动与联调见 [README](README.md)。
- 写新函数或规则前先找现有实现。读写数据时从认证上下文取得用户身份，并在查询和更新中限制归属。
- pre-commit 钩子会拒绝超过约 20KB 的文本文件；提交被拒时按报错拆分，不要绕过钩子。
- 新克隆后运行 `git config core.hooksPath .githooks` 启用钩子。
- 数据库变更同时核对 [基线 DDL](src/main/resources/db/schema.sql) 和已有库的迁移路径。发布或回退按对应专题文档执行。
- 改 gRPC 契约时先对齐 AI 仓的源定义，再同步 [Java proto](src/main/proto/) 并更新 [协议登记](docs/shared-protocol.md)。

# 双机开发环境协调

目标：两台机器共用同一套代码和文档，但各自保留自己的端口、数据库、密钥和依赖目录。仓库里只放默认值与示例，机器差异放未跟踪的本地文件。

## 1. 原则

- **代码走 Git，环境走本地文件**：`ai` 分支同步代码、迁移和文档；端口/数据库/密钥不进 Git。
- **数据库各建各的**：不要用 Git 同步数据库文件；需要搬数据时用 `pg_dump` / `pg_restore`。
- **默认值可共享，差异只覆盖**：`application-dev.yml`、`config.yml`、`vite.config.js` 里的默认值保持仓库一致；每台机器只改本地覆盖文件。
- **离开一台机器前必须提交并推送**：不要带着未提交改动换机器。

## 2. 端口与地址

| 服务 | 仓库默认 | 本机覆盖位置 |
|---|---|---|
| Java 后端 | `9050` | `application-local.yml` 的 `server.port` |
| Python legacy gRPC | `10003` | `config.local.yml` 的 `server.port`，或 `MIRROR_AI_PORT` |
| Python Agent gRPC | `10004` | `config.local.yml` 的 `agent.port`，或 `MIRROR_AI_AGENT_PORT` |
| PostgreSQL | `5432` / 库名 `mu_mirror` | `application-local.yml` 的 `spring.datasource.url` |
| 前端 Vite | Vite 默认端口 | `.env.local` 的 `VITE_API_TARGET` 指后端 |
| 桩 LLM | `18080` | 启动参数或环境变量 |

端口冲突时只改本机覆盖文件，不要改仓库默认值；否则另一台机器会被动跟着变。

## 3. 两台机器第一次配置

### 3.1 公共

```bash
cd E:/project/mirror/own
git switch ai
git pull --rebase
git config core.hooksPath .githooks
```

数据库需 PostgreSQL + `vector` 扩展。新库执行 `Mu-mirror-B/src/main/resources/db/schema.sql`；已有库按 `migration-v2` 至 `migration-v20` 顺序升级。

### 3.2 Java 后端

```bash
cd Mu-mirror-B
cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
# 编辑 application-local.yml：数据库 URL/账号、9050 是否被占用、AI 地址
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,local
```

`application-dev.yml` 是共享默认值，**不要把自己的密码写进去**。

### 3.3 Python AI

```bash
cd Mu-mirror-AI
cp config.local.example.yml config.local.yml
# 编辑 config.local.yml：10003/10004 是否被占用
.venv/Scripts/python.exe server.py
```

启用 graph_v2 Agent 时，通过环境变量提供：

```bash
export MIRROR_AGENT_INTERNAL_TOKEN=...
export MIRROR_AI_READ_DSN=postgresql://mirror_ai_reader:...@127.0.0.1:5432/mu_mirror
export MIRROR_AGENT_COMMAND_BASE_URL=http://127.0.0.1:9050
```

不要把 token/DSN 写进 `config.yml` 或 `config.local.yml`。

### 3.4 Vue 前端

```bash
cd Mu-mirror-F
cp .env.example .env.local
# 编辑 .env.local：VITE_API_TARGET=http://127.0.0.1:9050
npm install
npm run dev
```

`node_modules`、`.venv`、`target`、`dist`、`graphify-out` 都按机器重建，不跨机器复制。

## 4. 两台机器之间切换

离开前：

```bash
git status                 # 必须干净
git pull --rebase
git push origin ai
```

到另一台后：

```bash
git switch ai
git pull --rebase
git status
```

本地覆盖文件是未跟踪文件，`git pull` 不会覆盖；如果换了目录或重装，按第 3 节重新复制示例。

同时在两台机器改同一个文件会冲突。建议：

- 同一时间只在一台机器写代码；
- 另一台只读/运行；
- 需要并行时各开分支，最后合并，不要两边直接改 `ai`。

## 5. 数据库同步

默认不同步数据库。每台机器独立建库、独立执行迁移。

### 5.1 表结构变更

数据库名和端口可以两台机器不同，但**表结构必须通过 Git 同步**。任何新表、列、索引、函数、触发器都按同一流程：

1. 在 `src/main/resources/db/` 新增下一个编号的迁移（当前已到 `migration-v17-achievement-progression.sql`，下一条是 `migration-v18-*.sql`）。
2. 同一提交更新 `schema.sql`，让新库一次建到最新结构；不要只改迁移。
3. 不改已发布的迁移文件；需要修正时新开一条迁移。
4. 两台机器都执行同一条新迁移：

```bash
psql -X -v ON_ERROR_STOP=1 -1 -d mu_mirror -f src/main/resources/db/migration-v18-*.sql
```

5. 新机器/新库直接执行 `schema.sql`，不要逐份重放旧迁移。
6. 核对迁移顺序、表/列/索引/函数是否一致；涉及成就、权限、检索时同步更新对应文档和测试。

不要用“复制旧数据库”代替迁移；不要只在其中一台改表。

### 5.2 搬数据

需要搬数据时：

```bash
# 源机器
pg_dump -Fc -d mu_mirror -f mirror.dump

# 目标机器（先建空库并执行 schema.sql / 迁移）
pg_restore --clean --if-exists -d mu_mirror mirror.dump
```

注意：

- 数据库里的 `user_settings` 含加密后的 API Key；搬库会把密钥一起搬过去，按需决定。
- 不要用 Git 存 `.dump`、`.sql` 数据文件或 `vault_blobs`。
- 迁移脚本是结构真源；不要靠复制旧数据库来“对齐结构”。
- 成就、`agent_runs`、`assistant_actions` 等表会随迁移创建；旧库必须按顺序升级。

## 6. 密钥与用户配置

| 内容 | 存放位置 | 是否入库 |
|---|---|---|
| JWT / AES 密钥 | 环境变量或本机配置 | 否 |
| 用户 LLM / Embedding Key | 各库 `user_settings`（AES-256-GCM） | 否 |
| Agent 内部 token | 环境变量 | 否 |
| 只读 DSN | 环境变量 | 否 |
| 本地数据库密码 | `application-local.yml` | 否（已 gitignore） |

## 7. 机器差异清单

每台机器只维护这些文件：

```
Mu-mirror-B/src/main/resources/application-local.yml
Mu-mirror-AI/config.local.yml
Mu-mirror-F/.env.local
```

检查它们是否被忽略：

```bash
git check-ignore -v Mu-mirror-B/src/main/resources/application-local.yml
git check-ignore -v Mu-mirror-AI/config.local.yml
git check-ignore -v Mu-mirror-F/.env.local
```

## 8. 启动顺序与自检

1. PostgreSQL
2. Python AI（10003；需要 graph_v2 时 10004）
3. Java 后端（9050）
4. 前端

自检：

```bash
# 后端健康
curl http://127.0.0.1:9050/api/auth/status

# 端口占用（Windows）
netstat -ano | findstr :9050
netstat -ano | findstr :10003

# Python 配置
.venv/Scripts/python.exe -c "from config import CONFIG; print(CONFIG['server'], CONFIG['agent']['port'])"

# 前端代理目标
cat Mu-mirror-F/.env.local
```

## 9. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 后端启动报数据库连接失败 | `application-local.yml` 未建或 URL 不对 | 复制示例，核对库名/端口/账号 |
| 后端启动报 `jwt.secret` | 没带 `dev` profile | 用 `dev,local` |
| 端口被占用 | 本机已有服务 | 只改本地覆盖文件 |
| 对话没有工具轨迹 | Java 的 AI 地址与实际端口不一致 | 对齐 `application-local.yml` 和 AI 端口 |
| 前端 404 / 代理失败 | `.env.local` 未建或目标不对 | 复制 `.env.example`，重启 Vite |
| 另一台机器的改动没出现 | 没 push/pull | 离开前 push，到另一台 pull --rebase |
| 本地覆盖文件丢失 | 换目录/重装 | 重新复制示例，不要从 Git 恢复 |

## 10. 相关文档

- 后端启动：[Mu-mirror-B/README.md](../README.md)
- AI 启动：[Mu-mirror-AI/README.md](../../Mu-mirror-AI/README.md)
- 前端启动：[Mu-mirror-F/README.md](../../Mu-mirror-F/README.md)
- 当前状态：[PROGRESS.md](PROGRESS.md)
- 文档索引：[README.md](README.md)

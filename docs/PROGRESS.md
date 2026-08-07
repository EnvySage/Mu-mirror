# AI 日记镜子系统 - 开发进度

> 本文档用于跨对话快速跟踪项目进度，避免重复理解项目结构。
> 最后更新：2026-08-06（记录模块基础架构完成）

---

## 一、项目概览

- **项目名称**：Mu-mirror-B（AI 日记镜子系统）
- **项目性质**：毕业设计 / 论文项目
- **核心理念**：零负担记录 → AI 自动整理 → 生成用户画像（"镜子"）
- **技术栈**：Spring Boot 3.5 + MyBatis-Plus + PostgreSQL + Redis + JWT + Python gRPC AI 服务
- **端口**：9005，上下文路径 `/api`
- **开发时间线**：2026-08 ~ 2027-04（论文答辩）

---

## 二、模块开发状态

| 优先级 | 模块 | 状态 | 说明 |
|--------|------|------|------|
| P0 | 用户认证 (Auth) | ✅ 完成 | 注册、登录、JWT、/auth/me |
| P0 | 记录管理 (Records) | 🔧 进行中 | CRUD + 管道框架 + 事件机制，待对接 AI |
| P0 | 镜子/画像 (Mirror) | ⬜ 未开始 | 依赖 Records + AI 服务 |
| P0 | 对话 (Chat) | ⬜ 未开始 | 依赖 Records + AI 服务 |
| P0 | 用户设置 (Settings) | ⬜ 未开始 | AI 模型配置等 |
| P1 | 每日摘要 (Daily Summary) | ⬜ 未开始 | 依赖 Records |
| P1 | 写作灵感 (Inspiration) | ⬜ 未开始 | 依赖 AI 服务 |
| P2 | 活动统计 (Activity) | ⬜ 未开始 | 依赖 Records |
| - | AI 服务 (Python gRPC) | ⬜ 未开始 | 独立服务，处理/分类/embedding/对话 |
| - | 前端 (Vue 3) | ⬜ 未开始 | |

---

## 三、已完成的代码结构

```
src/main/java/org/xianshen/mumirrorb/
├── MuMirrorBApplication.java
├── common/
│   ├── enums/ResultCode.java              # 统一错误码
│   ├── exception/
│   │   ├── BusinessException.java
│   │   ├── AuthenticationException.java
│   │   └── GlobalExceptionHandler.java
│   ├── security/
│   │   ├── JwtAuthenticationFilter.java   # JWT 过滤器
│   │   └── UserDetailsServiceImpl.java
│   └── utils/JwtUtils.java
├── config/
│   ├── SecurityConfig.java                # 路由权限配置
│   ├── Knife4jConfig.java                 # API 文档
│   └── WebMvcConfig.java                  # CORS
├── controller/
│   └── AuthController.java                # 4 个端点
├── mapper/
│   └── UserMapper.java
├── pojo/
│   ├── R.java                             # 统一响应 {code, message, data, timestamp}
│   ├── DO/User.java                       # UUID id, username, passwordHash, createdAt
│   ├── DTO/UserLoginDTO.java
│   ├── DTO/UserRegisterDTO.java
│   ├── VO/LoginVO.java                    # token, tokenType, expiresIn, user
│   └── VO/UserVO.java                     # id, username, createdAt
└── service/
    ├── AuthService.java
    └── impl/AuthServiceImpl.java
```

---

## 四、数据库状态

### 已创建的表

```sql
-- users 表（已实现）
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_users_username ON users(username);
```

### 待创建的表（设计文档中已定义）

- `records` — 日记记录（content, title, summary, content_type, mood, status）
- `tags` — 标签（关联 records）
- `chunks` — pgvector 向量存储（embedding + metadata）
- `daily_summaries` — 每日摘要
- `mirror_profiles` — AI 生成的用户画像（JSONB）
- `conversation_history` — 对话历史
- `chat_sessions` — 对话会话
- `user_settings` — 用户设置（认证 + AI 模型配置）

---

## 五、已有 API 端点

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/auth/status` | 公开 | 健康检查 |
| POST | `/api/auth/register` | 公开 | 用户注册 |
| POST | `/api/auth/login` | 公开 | 用户登录，返回 JWT |
| GET | `/api/auth/me` | JWT | 获取当前用户信息 |

---

## 六、关键设计文档索引

| 文件 | 内容 |
|------|------|
| `docs/2026-07-23-ai-diary-mirror-design.md` | 主设计文档 v1.1（功能、数据库、API、提示词模板） |
| `docs/2026-07-23-mirror-implementation.md` | 实现文档 v0.3（技术选型、项目结构、核心代码模式） |
| `docs/2026-08-04-ai-service-design.md` | AI 服务设计 v0.2（Proto 定义、Python 模块、部署方案） |

---

## 七、下一步计划

**当前目标：实现记录模块 (Records)**

1. 创建 `records` 和 `tags` 数据库表
2. 编写实体类 `Record`、`Tag` 及 Mapper
3. 实现 DTO/VO（CreateRecordDTO, UpdateRecordDTO, RecordVO 等）
4. 编写 `RecordService` 接口和实现
5. 编写 `RecordController`（CRUD：创建、列表、详情、更新、删除）
6. 后续接入 AI 处理（分类、打标签、embedding）

---

## 八、开发约定

- **实体命名**：DO 类用数据库表名对应，DTO 用于输入，VO 用于输出
- **响应格式**：统一使用 `R<T>` 包装
- **主键**：UUID，由数据库 `gen_random_uuid()` 生成
- **时间**：使用 `TIMESTAMPTZ`，应用层用 `Instant`
- **密码**：BCrypt 哈希存储
- **JWT**：24 小时过期，Bearer Token 放 Authorization 头
- **API 文档**：Knife4j，地址 `/api/doc.html`

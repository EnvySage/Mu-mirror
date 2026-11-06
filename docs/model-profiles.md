# 模型连接配置

已有环境在 v14 后执行 `db/migration-v15-model-profiles.sql`；新环境的 `db/schema.sql` 已包含结构。迁移可重复执行。发布前必须先迁移数据库，再启动新版 Java。Python 与协议无需变更。

模型连接存入 `user_model_profiles`，密钥复用 `CryptoUtils` 的 AES-GCM 加密。配置接口只返回 `hasApiKey` / `apiKeyNeedsReentry`，不返回明文、密文或掩码密钥。所有接口都以当前登录用户限制数据范围。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/settings/model-profiles` | 列出配置；首次将原有完整模型配置复制为“当前配置”，保持原生效字段不变 |
| POST | `/settings/model-profiles` | 新建连接，必须填写密钥 |
| PUT | `/settings/model-profiles/{id}` | 编辑连接，密钥留空或省略保留已有值 |
| DELETE | `/settings/model-profiles/{id}` | 删除非当前配置；当前配置需先切换后删除 |
| POST | `/settings/model-profiles/{id}/activate` | 应用配置到 `user_settings` 的 AI 字段 |
| POST | `/settings/test-ai/draft` | 测试提交的草稿，不保存、不应用 |

新建/编辑字段：`name`、`aiProvider`、`aiProtocol`（`openai` / `anthropic`）、`aiBaseUrl`、`aiModel`、`aiApiKey`。API 地址必须为 HTTP(S) URL，不含用户密码、查询参数或片段。返回字段包括 `id`、上述非密钥字段、`hasApiKey`、`apiKeyNeedsReentry`、`active`、`unappliedChanges`、时间戳。

保存不代表应用。编辑当前配置后，`active=true`、`unappliedChanges=true`；原先生效模型继续用于业务，直到再次应用。应用只更新 AI 连接字段，Embedding 和其他偏好保持原值。旧客户端直接更新 AI 设置时会清除当前配置标识，避免显示错误的生效关系。

草稿测试提交同一组 AI 字段（无需 `name`），可带 `profileId` 复用本人已有密钥。即使提交了新密钥，也会校验 `profileId` 所有权。不带 `profileId` 时，只有显式 `useCurrentKey=true` 才复用当前生效密钥；其他情况必须提交密钥。测试通过现有 Python Classify RPC 发送固定无个人数据探针；不创建记录、不保存配置、不切换模型。

列表导入、写入、应用和旧设置编辑以 `user_settings` 的用户行锁串行执行。数据库还以复合外键限制当前配置必须属于同一用户。

验证命令：`mvnw.cmd -Dtest=ModelProfileServiceTest,ModelProfileDatabaseTest,SettingsServiceImplTest,AiGrpcClientDraftConnectionTest test`。数据库测试需要显式提供 `MIRROR_TEST_DB_URL`、`MIRROR_TEST_DB_USERNAME`、`MIRROR_TEST_DB_PASSWORD`，且库名为 `mirror_tarot_test` 或 `codex_assistant_growth_test_*`。测试在随机独立 schema 中执行迁移与并发验收并清理该 schema。禁止指向业务库。

回退应用版本前可保留新增表和列，原 `user_settings` AI 字段仍为上一版本可读取的生效配置；不要删除现有配置或密钥来回退。

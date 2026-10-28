# 记录分类「计划」合并为「待办」

记录分类存储于 `chunks.metadata.contentType`（JSONB），不是独立的计划表或 PostgreSQL 枚举。
新分类统一为 `todo`；保留 proto `PLAN` 和旧 API 输入兼容。聊天记忆 `kind=plan` 是另一套类型，保留。

## 发布顺序

1. 备份业务库，并记录待迁移 chunk ID：

   ```sql
   SELECT c.id, c.record_id
   FROM chunks c JOIN records r ON r.id = c.record_id AND r.user_id = c.user_id
   WHERE r.source = 'user' AND c.metadata->>'contentType' = 'plan';
   ```

2. 部署兼容版本的 Java、Python 和前端。新记录写入 `todo`；结构化、向量和混合检索对 `todo` / `plan` 均匹配两个旧值，因此迁移前后都能查询。
3. 在目标业务库执行 `src/main/resources/db/migration-v13-plan-to-todo.sql`。这是单条原子 UPDATE，可重复执行。空库不需要回填；塔罗功能另需 `migration-v12-tarot.sql`。
4. 重新运行上面的 SELECT，应返回零行。核对记录数量、待办数量和状态与迁移前一致。

## 数据保留

迁移仅更新用户记录片段的 `contentType` 键；原文、片段、标题、标签、状态、向量、授权、ID 与其余 metadata 都保留。
`todo_registry`、关联证据和建议表不做删除、重建或改状态。已有登记仍关联同一个 chunk；未授权加入待办的旧记录仍需用户单独确认，不会因为合并分类自动登记。
文件资料和聊天记忆的计划类型不受影响。已准备但尚未确认的旧操作卡会在确认时归一为 `todo`。

## 验证与回退

`RecordAiAuthorizationDatabaseTest` 使用真实 PostgreSQL 检查旧分类查询兼容、迁移重复执行，以及原文、其他元数据、待办外键和状态不变。
回退应用时可以保留 `todo` 数据，旧版也识别该分类。如确需恢复原 `plan` 分类，仅按迁移前保存的 ID 和备份恢复，并核对迁移后是否存在新的用户修改；不要把所有 `todo` 改回 `plan`。

本次本地验证只在 Docker 隔离测试库执行迁移，未访问生产数据库。

`deploy/backend-release.sh` 已将 v13 设为本轮发布的必需迁移：在备份成功后执行，迁移失败时不切换代码或重启服务。手工发布也必须同步执行；本地验证不代表正式库已迁移。

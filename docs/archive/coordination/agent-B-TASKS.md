# 子 Agent 2：Java 后端（Mu-mirror-B）—— 词典轮收尾 + 第十一轮预告

## 当前状态（2026-09-05 晚）
词典轮（本轮）接近完成：UserTerm/Mapper/VO/DTO/GlossaryService/Impl/Controller/双调度接线/glossary 四注入点/ChunkDTO taskStatus 已落地，正修编译错。**先把手头词典轮做完交付，本轮任务书不变。**

## 词典轮收尾清单（按序）
1. 编译通过（当前 GlossaryProtoMapper/CommonProto 类型适配中）
2. 新增 GlossaryServiceTest（top30 截断+缓存/alias 匹配+hit++/confirm/dismiss 状态机/applyCandidates 各分支）
3. mvnw test 既有 34 测试全过
4. user_terms CRUD 真库实测（起 9050，otaku_it 登录，curl 走 GET/POST/PUT/confirm/dismiss/extract）
5. 日志 coordination/logs/agent-B.md + 不要 git commit

## 交付后预告：第十一轮 = toolcalling+vault 后端
设计稿已定稿：E:\project\mirror\own\coordination\toolcalling-vault-design.md（先通读全文，第 2/3/5/6/8 节是你的范围）。DB 三表（vault_items/vault_blobs/tool_calls）已建好并同步进 schema.sql/migration-v2.sql（commit af60c45），直接写实体/Mapper。

核心任务（下轮任务书会展开，此处先挂账）：
1. VaultService：上传（magic bytes/清洗/SHA-256 去重/配额 500MB/20MB——全 config.yml 配置化）+ 下载/预览（ownership 404 语义）+ 三层检索 + Digest 管道（PDFBox 抽文本→chunk 管道挂 vault_item_id）
2. ToolRegistry + ToolExecutor + AuditService（落 tool_calls）+ 8 只读工具实现
3. PlanTools gRPC 调用编排 + ChatRequest.tool_results 注入 + SSE meta tools_used + vault_refs 事件
4. vault REST（upload/list/download/preview/delete/update）+ save_item/find_item/recall_item
5. proto：PlanTools RPC + ToolSpec/ToolResult/PlannedCall + ChatRequest.tool_results 字段（AI 仓 proto 同步，登记 shared-protocol）
6. 测试 + 真库实测

**收到词典轮完成通知后自动进入第十一轮，无需等待总指挥。**

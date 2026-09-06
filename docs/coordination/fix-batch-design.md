# 审查修复批（Y1-Y6 + Q1/Q2/Q3 落地）任务书

## 背景
全量设计审查发现 6 处实现与设计相反（Y1-Y6）+ 3 项用户裁决落地（Q1=B+日常补丁 / Q2=硬删三层防误删 / Q3=key-embed 进通用检索）+ 确认门禁（用户定稿，见 toolcalling-vault-design.md §3.3b/c/d）。设计稿已更新，本任务书为唯一执行蓝本。

**并行约束：B Agent（递归镜子轮）与 AI Agent 正在作业中——本任务书派独立修复 Agent，只允许改审查报告点名的文件，禁碰 MirrorServiceImpl（B 递归轮在改 MirrorServiceImpl/MirrorController/ProfileStatsMapper/proto 四件），见各任务的"避让"说明。**

## B 侧任务（独立修复 Agent）

### B1. Y1 检索放行系统记录（一行删除）
`ChatSearchMapper.java` 三段 SQL（searchStructured/searchSemantic/searchHybrid）删 `AND r.source = 'user'`（保留 deleted_at IS NULL 与 embedding IS NOT NULL）。**避让：不动 MirrorServiceImpl。**

### B2. Y2 词典语料收口
`GlossaryServiceImpl.java:317-323`（doExtract 语料）、`:484-488`（审计语料）、`GetCoverageTool.java`：查询补 `r.status='done' AND r.source='user'`。**避让：GlossaryServiceImpl 的 extract/审计语料方法可改，但不动该文件其他方法（月度审计 C1 保持现状）。**

### B3. Y3 引用编号 [F1]
- Python 渲染 tool_results 时文件引用用独立编号 [F1][F2]（AI 侧任务，B 配合）：B 的 extractVaultRefs（ChatServiceImpl:196-266）解析规则改为只认 `[F\d+]` 格式，普通 `[n]` 不再触发文件卡
- chat prompt 的编号说明由 AI 侧同步（见 AI 任务）

### B4. Y5 get_profile 加月份
`GetProfileTool.java` args_schema 加 `{"month": "YYYY-MM"}` 可选参数：有值→按 period_month/createdAt 窗口取该月最新快照（优先 monthly），无值→维持最新。月度归属列正在递归轮加（period_month），本任务先用 createdAt 窗口近似实现+注释声明 period_month 落地后切换。

### B5. Y6 拆 DigestService
`VaultServiceImpl.java:134` 的 `this.digestAsync(...)` 自调用失效：拆独立 `DigestService` Bean（@Async @Transactional 移过去），VaultServiceImpl 注入调用。

### B6. Q2 三层防误删（硬删保留）
1. 删除端点响应照旧（真删）；防误删主要在前端（F 任务）
2. 后端配合：DELETE /vault/{id} 加请求头/参数 `confirm_name`（文件名后四位校验，不符 400"输入的文件名后四位不符"）——资产页路径用；对话内路径免此校验（有内联确认卡）

### B7. Q3 key-embed + B4 五态 + 确认门禁（核心）
1. **digest_status 五态迁移**：pending/extracted/confirmed/skipped/failed（活库 ALTER CHECK 或仅应用层枚举+schema 注释，现有数据迁移：done→confirmed，图片 done→extracted）
2. 消化管道改：文本/PDF 抽文本后生成 key（元数据+LLM 命名产物拼合）→ 落 extracted 停（**不再自动 embed 全文**）；图片→extracted
3. **确认端点** `POST /vault/{id}/confirm`：body {key, description, category}（用户可改后提交）→ ①更新元数据 ②生成 key chunk（embed 文本=key+description+类型拼合，contentType='note'，挂 vault_item_id）③全文消化 chunks 这时才 embed ④状态→confirmed。异步，返回 202 语义
4. Y4 联动：图片上传后 digest_status=extracted（如实），描述为空时 F 强制输入（F 任务）
5. Q1 补丁：每日抽取的 update 分支保留漂移候选（现状已支持），无代码改动则日志确认
6. C5 顺手修：glossary extract 响应改 {candidates:[...]} 对齐 F 契约
7. C7 顺手修：合并建议不再追加进 description（移到 aliases 预填）
8. C4 顺手修：ExportServiceImpl 排除 source='vault' 虚拟记录
9. 单测覆盖 B1-B7 + 真库实测（上传→extracted→confirm→key chunk 可检索→删除级联）

### 避让总结
不动：MirrorServiceImpl/MirrorController/ProfileStatsMapper/MirrorService/proto 四件套/mirror 相关单测（递归轮在改）。GlossaryServiceImpl 只动语料查询方法与 C5/C7 点名处。

## AI 侧任务（并入正在跑的递归轮 AI Agent 传递，不新派）
- chat prompt 编号说明改：「引用日记资料用 [n]，引用用户文件用 [F编号]」（配 B3）
- profile.txt 重写不受影响继续

## F 侧任务（F 滑块 Agent 交付后新派或续用）
1. 上传卡：图片 description 必填（Y4）；回执卡三键适配五态（extracted 态显示 key/description 编辑+确认按钮）
2. 资产页：digest 五态徽标；删除改"输文件名后四位"交互；未确认灰标"未确认 · 检索不到"；补确认入口
3. 对话删除确认文案带文件名 + 5 秒撤销 toast
4. vault store：confirmDigest action 接 POST /vault/{id}/confirm 真契约（替换本地标记）；USE_MOCK 适配五态

## 验证
- B：./mvnw test 全绿 + 新增用例 + 真库实测全链路（上传 PDF→extracted→confirm→key chunk 可被对话检索命中→vault_refs 文件卡）
- 联调口径：问"我论文咋样了"→ 命中 key chunk → 返回文件卡，不喂全文

# 进度总表 PROGRESS-BOARD

> 只有总指挥（主会话）写。四 Agent 只读自己任务书 + 只写自己日志（coordination/logs/agent-XX.md）。
> 汇总来源：各 Agent 日志 `[STATUS]` 行。

## Agent 状态一览

| Agent | 仓库 | 当前任务 | 状态 | 最近更新 |
|-------|------|----------|------|----------|
| DB | mu-mirror-pg 容器 + B/db | user_terms（含 source_record_id 补列）+ vault 三表已建 + schema/migration 同步 | ✅ done | 2026-09-06 |
| AI | Mu-mirror-AI | 递归镜子轮完成 ✅（a3396f6：profile prompt 累计语义+proto 11/12/13 optional+待办真源硬指令+[F] 编号修正，143 单测+16 E2E+18 冒烟全过） | done | 2026-09-06 |
| B | Mu-mirror-B | **审查修复批+两高危修复全部完成 ✅**（31600cb：update() 定向 SET 防竞态 + AsyncConfig vaultDigestExecutor 防静默丢失，132/132 测试）。设计文档 v2.3 同步 ✅（09eafdb，裁决 #35/#36 登记）。待办：PlanTools 端到端复验"问论文→key chunk→文件卡"；已立案未修：上传事务未提交可见性竞态（afterCommit 方案，见 logs/agent-B.md） | done | 2026-09-06 |
| F | Mu-mirror-F | 修复批完成 ✅（ae2bc0c：确认门禁交互+三层防误删+五态徽标+图片必填+三 mock 全切真，桌面 25/25+移动 15/15 真接口走查 0 error）。@B 两高危已被 B 修复批确认+修复 ✅ | done | 2026-09-06 |
| DOC | 设计文档 | v2.3 同步完成 ✅（1209 行）：6.10 vault 章改"已实现"（确认门禁/五态/三层防误删/两高危修复/立案竞态）、裁决 #35/#36 续编、路线图两 sprint 状态更新、变更记录两条新增，四份副本同步（B 09eafdb/AI 81e64b7/F 260736a） | done | 2026-09-06 |
| DB | 数据专项 | 按月快照生成 ✅（76590bc：until 窗口+generate-monthly 接口+月度任务语义修正，92/92 测试+真库生成 7/8 月快照实测）；created_at 核查 0 不一致无需回填 | done | 2026-09-06 |

## 词典 sprint（lexicon-design.md v1.0）
- 设计稿：coordination/lexicon-design.md（与用户逐项对齐定稿）
- 分工：DB 表 ✅ → B（proto+服务+接口+调度）running → AI ExtractTerms RPC（等 Py 修复交付）→ F 前端（等 B 接口）
- 关键裁决：pending 不注入 / 14 天窗口抽取 / 每日总结 sheet 词条分区（用户主提议）/ 月度漂移审计 / 注入上限 30

## B 第二轮完成摘要（2026-09-04）
- T-B-4：POST /api/mirror/chat SSE（meta/delta/sources/done/error 事件）、四路检索 SQL（PROFILE 快照回退 HYBRID / STRUCTURED 纯 SQL / SEMANTIC 距离+衰减开关 / HYBRID 预过滤+衰减公式 half_life 可调）、会话管理三端点、兜底落库
- T-B-5：DailySummaryScheduler 01:00 幂等、GET /api/summaries、POST /api/inspiration、export json/markdown（排除向量）
- fail_reason 选落库方案（截 500 字，retry 清空），schema.sql/migration 同步
- ModelInfoRequest 加 embedding_config=1（wire 兼容），test-embedding api 模式不再失真
- 18/18 测试通过（原 11 + ChatRetrievalTest 7）

## 下一轮（待总指挥派发）
1. DB Agent：1 条 DDL（fail_reason 列）
2. AI Agent：ModelInfoRequest 字段同步 + 真实冒烟
3. F Agent：chat mock 换 SSE 真接口 + SummarySheet/导出接线
4. **端到端联调**（三服务串全链路，最大风险点）

## F 第二轮完成摘要（2026-09-04，v2 原型 UI 重做）
- 设计基座：原型 token 全量替换 + aurora 光斑挂 App.vue + 玻璃卡/reduced-motion
- 全页面重做：布局双导航（sidebar+bottom-nav 凸钮）、记录四态卡、抽屉写日记、审核「光源→镜面反射」、镜子 sheen+漂移+渐变数字、对话气泡+route 眉标+sources 芯片、设置五组、总结 sheet、日历辉光点
- 登录页保持 mirror-auth.html 浅色例外
- 10 个标志性元素全部移植（13 色 mood 映射在 constants/moodColor.js）
- 接真接口：mirror 页（GET /api/mirror、POST /api/mirror/generate）；mock 仅剩 3 处（chat 流式/每日总结/导出，接口就绪即插即用）
- 清死代码 16 文件，组件 22→13；build 276ms 零警告

## B Agent 完成摘要（2026-09-04）
- T-B-0：test-ai 真探测、新增 test-embedding（1024 校验）、taskStatus 落 metadata（ClassifyItemConverter 共用）
- T-B-1：DELETE /chunks/{id}、POST /records/{id}/chunks、/retry、confirmReview 重写（补分类+Embedding 不阻断）、classified_segment 状态机、review_mode=auto
- T-B-2：日历 source='user'、检索 embedding IS NOT NULL、rag_half_life 接线
- T-B-3：ProfileSnapshot 实体/Mapper（含余弦漂移）、五维统计 SQL（EXPLAIN 验证）、MirrorServiceImpl（manual 保 2 / monthly 保 12、每月 1 号 02:00 定时）、GET /api/mirror、POST /api/mirror/generate
- 编译 + 11/11 测试通过
- 未做：T-B-4 对话（四路检索/流式转发/sources 落库/会话管理）、T-B-5（每日总结/灵感/导出）——下一轮派发

## F Agent 完成摘要（2026-09-04）
- 契约修复 2 个严重问题：① R 包装无 success 字段，前端改按 code!==200 判错；② ChunkDTO 扁平结构 vs 前端嵌套 metadata（会被后端静默忽略），已改扁平 payload
- 审核页按 8.3 全新实现：useRecordPolling（2.5s 轮询）+ ChunkCard + ReviewPanel（增删改/合并/拆分引导/长时 confirm loading）
- Settings 补 rag_half_life 滑块+衰减权重预览、auto 审核权衡提示
- build 通过；npm run dev 验证后已停
- F 等 B 的接口清单：DELETE /chunks/{id}、POST /records/{id}/chunks、POST /records/{id}/retry、SettingsDTO.rag_half_life、1024 维校验、fail_reason 透出

## DB Agent 完成摘要（2026-09-03）
- v2 基线迁移：DROP records.segment / DROP tags（结构已备份日志）/ ADD records.source / 补 chunks.classified_segment + user_edited
- 新建 profile_snapshots / chat_sessions / conversation_history；user_settings 补 rag_half_life
- HNSW cosine 索引就位；cosine 检索链路验证通过（测试数据 ROLLBACK 清理）
- schema.sql 重写为 v2.1 全量基准；migration-v2.sql 幂等迁移脚本；空库 scratch_verify 实测从零建库成功
- 待办：db/*.sql 无人引用（无 docker-compose、无 SQL init 配置）——B Agent 需对齐谁来执行

## 交叉依赖

| 依赖 | 提供方 | 等待方 | 状态 |
|------|--------|--------|------|
| records.source 列 / DROP segment / DROP tags | DB Agent | B Agent（实体对账） | ✅ 已就绪 |
| profile_snapshots / chat_sessions / conversation_history 建表 | DB Agent | B Agent（T-B-3/4） | ✅ 已就绪 |
| ClassifyRequest.single | 双方已落地（single=3） | — | ✅ 完成 |
| mirror_chat / mirror_profile Python 实现 | AI Agent ✅（Chat 流式 + ExtractIntent + GenerateProfile） | B Agent（T-B-3/4 接线） | ✅ 就绪待接线 |
| GetModelInfo 携带 EmbeddingConfig | B 提出 → AI 未处理（健康检查改了，但未加 embedding_config 字段） | B Agent（维度校验裁决 #18） | ⚠️ 未解决，需 B 侧自己加 proto 字段 |
| ExtractIntent.query_type / GenerateProfile.recent_chats | AI Agent（proto 已改） | B Agent（Java 侧对账） | 🔄 B 已对账完成（字段一致） |
| /api/mirror* 接口 | B Agent ✅（GET /api/mirror、POST /api/mirror/generate） | F Agent（T-F-4） | ✅ 可接线（需 F 与 AI 服务联调） |
| /api/mirror/chat 流式 | B Agent（T-B-4 未做） | F Agent（T-F-5） | ⏳ 下一轮派发 |
| /api/summaries | B Agent（T-B-5 未做） | F Agent（T-F-6） | ⏳ 下一轮派发 |
| DELETE /chunks/{id}、POST /records/{id}/chunks、/retry | B Agent ✅ | F Agent（已开发完等接口） | ✅ 已就绪，F 可拆 mock |
| SettingsDTO.rag_half_life + 1024 维校验 + fail_reason | B Agent（rag_half_life ✅、1024 校验 ✅） | F Agent | ✅ 大部分就绪；fail_reason 仍未透出 |
| GetModelInfo 携带 EmbeddingConfig | B 提出，AI 未加（空消息仍在） | B Agent（维度校验 api 模式失真） | ⚠️ 悬而未决，B 已做折衷 |
| 枚举名统一（AI_PROTOCOL_UNKNOWN vs PROTOCOL_UNKNOWN） | 双方需对齐 | — | ⚠️ wire 兼容，低优先 |

## 里程碑（设计文档第十四章路线图）

- [x] 阶段 0：技术债清理（DB/AI/B）
- [x] 阶段 1：segment 手动调整全链路（DB/AI/B）
- [x] 阶段 2：前端骨架 + 审核页（F，v2 原型重做）
- [x] 阶段 3：镜子画像（DB/AI/B + F 接线）
- [x] 阶段 4：对话（AI/B + F 页面就绪待拆 mock）
- [x] 阶段 5：总结/灵感/导出/auto 审核（B）
- [x] **端到端联调**（2026-09-04：10/10 链路通过，8 集成 bug 修复，18/18 测试回归）
- [ ] 阶段 6：测试 + 论文素材

## E2E 联调报告（2026-09-04）
- 10/10 验收清单全过：auth→配置→管道→审核增删改→confirm向量→画像→SSE对话(sources落库)→会话→导出→failed+retry
- 修复 8 bug：①MyBatis typeHandler 全局污染 ②druid wall 不识别 LATERAL ③jsonb ? 占位符冲突(改函数形式) ④会话首条 UUID ⑤自定义@Select autoResultMap ⑥uuid::varchar 转型 ⑦PGobject 向量解析(hasEmbedding 恒 false) ⑧vite proxy 9005→9050
- 提交：B `5e6243b` fix(e2e)、F `f6ed878` fix(proxy)，均入 ai 分支
- 未验项（环境限制非代码问题）：BGE-m3 本地 embedding（本机无 torch，链路等价已验）、每日总结真实生成（无手动触发端点）、monthly 漂移非零、真实 LLM/anthropic 分支
- e2etest 测试用户数据保留在库供查验

## 变更日志

| 时间 | 事项 |
|------|------|
| 2026-09-03 | 建协作中心，四任务书下发，Agent 未启动 |
| 2026-09-03 | DB Agent 完成 T-DB-1~5（v2 迁移+3 规划表+HNSW+schema 重写+验证） |
| 2026-09-03 | AI/B proto 对齐：single=3 双侧落地；AI 改 mirror_chat/mirror_profile proto 已登记；B 发现 GetModelInfo 缺 EmbeddingConfig 评估中 |
| 2026-09-03 | AI Agent 完成 T-AI-1~6：Classify single/taskStatus 兜底、ExtractIntent 真实现、Chat 真流式（sources 解析）、GenerateProfile 真实现、errors.py 状态码映射、6 套 prompts、桩模式 17 测试全过 |
| 2026-09-03 | F Agent 盘点发现严重契约偏差：① R 包装无 success 字段，前端错误判断失效；② ChunkDTO 扁平结构 vs 前端嵌套 metadata——修复中 |
| 2026-09-03 | B Agent 对账：T-B-0 大部分基线已有（Tag 删除/AES 密钥外置/@EnableScheduling/软删除过滤均已就位）；剩 retry 端点、confirm 补分类、真连接测试、GetModelInfo 维度校验 |
| 2026-09-04 | F Agent 完成 T-F-0~3：审核片段卡片全链路（轮询+ChunkCard+ReviewPanel）、R 包装判错修复、ChunkDTO 扁平化修复、rag_half_life 滑块。commit f9026ee。T-F-4~6 等 B 接口 |
| 2026-09-04 | B Agent 完成 T-B-0~3（限流中断一次，已恢复）：confirm 补分类/新端点/retry/auto 审核/画像快照+漂移检测+定时。11/11 测试过。T-B-4 对话、T-B-5 未做 |
| 2026-09-04 | **用户裁决：前端大改**——现有 UI 拉胯，按 docs/prototype/mirror-prototype-v2.html 重做（深蓝黑玻璃拟态设计语言）。F 任务书重写为 T-F-R1~R7，保留 api/stores/轮询等工程资产 |
| 2026-09-04 | 第二轮派发：B 做 T-B-4 对话 + T-B-5 收尾 + fail_reason 透出 + GetModelInfo 加字段；F 按 v2 原型重做全部 UI |
| 2026-09-04 | F 第二轮完成（网络证书错误中断一次已恢复）：v2 原型 UI 全量重做、10 标志元素全移植、镜子页接真接口、死代码清理 22→13 组件、build 零警告 |
| 2026-09-04 | B 第二轮完成（网络证书错误中断一次已恢复）：T-B-4 对话 SSE 四路检索、T-B-5 总结/灵感/导出、fail_reason 落库（@agent-DB 1 条 DDL）、ModelInfoRequest 加 embedding_config（@agent-AI 同步）。18/18 测试过 |
| 2026-09-04 | **路线图阶段 0-5 主体全部完成**。剩余：1 条 DDL、AI 字段同步、F 拆 mock、端到端联调、阶段 6 测试+论文素材 |
| 2026-09-04 | 用户报告 2 个前端 bug 并修复：① 写日记弹窗闪退（Transition 基础 transform 屏外位，同修 SummarySheet/ChatView 抽屉）② 日历标记错位+按日查询落空（后端 UTC/北京时区双口径，日历 SQL AT TIME ZONE + 列表 ZoneId + VO JsonFormat 全统一 Asia/Shanghai）。实测验证过边界用例 |
| 2026-09-04 | **画像链路人格数据验证**：造 otaku_it 用户（二次元 IT 男人设，8月上旬冲刺焦虑→8月下旬夜猫子+待办→9月回暖，11 天 12 records 25 chunks 全 metadata），走通 generate→snapshot(1024维向量)→PROFILE 路由对话→export 全链路 |
| 2026-09-04 | F 第二轮完成（网络证书错误中断一次已恢复）：v2 原型 UI 全量重做、10 标志元素全移植、镜子页接真接口、死代码清理 22→13 组件、build 零警告 |
| 2026-09-05 | **「晨纸」亮色主题重做完成**（commit 28577d9）：暖白纸面 #FAFAF8 + 墨蓝 #2C5FE8 + 白卡细边，删玻璃拟态/aurora/渐变/backdrop-filter；微交互动画（卡片 hover 上浮、记录瀑布入场、日历 dayPop、镜子数字 rAF 滚动、气泡入场）；Toast/空态全 SVG 无 emoji；mood 13 色白底降饱和。26 文件，深色 token grep 0 残留，build 过 |
| 2026-09-05 | 对话链路修复（ca2cad7）：ExtractIntent 超时回退响应补 rewritten_query=原文，修 HYBRID 兜底链 embed(null) NPE；SSE 安全噪音根治（19f01ef：ERROR/FORWARD dispatch permitAll + emitter 回调）；会话记忆窗口 3→20 轮 |
| 2026-09-05 | 第五轮布局+会话体验完成（22dae80）：桌面内容居中 880px、记录双列、日历左右布局、镜子四列；移动端对话历史入口补进 MobileHeader；会话自动恢复（localStorage）+ 抽屉今天/昨天/更早分组 |
| 2026-09-05 | 桌面 fluid 化（1789856）：记录/日历/镜子去定宽改弹性侧距，内容占比 45%→75%；hero 内文限宽 |
| 2026-09-05 | **三页信息密度升级完成**：B `GET /mirror/stats` 聚合端点（2188a21，28/28 测试）+ F 六图表系统（e78e44f，纯 SVG 零依赖）+ 记录页总结侧栏 + 对话会话常驻栏（≥1440px）。待后端重启后真接口联调 |
| 2026-09-05 | 白屏+页面锁死连环修复（76518a3/798f67b）：RecordsSidebar Date 传参崩、ChatView 漏 import、MirrorView 变量名手滑——新增 check-imports.js 扫描防复发 |
| 2026-09-05 | **快照历史与对比上线**：B 快照历史两接口（482d5c2，34/34 测试）+ F 时间线/查看切换/对比模式（30c55be，含图例可读性+三格口径修复）；otaku_it 造 3 份历史 monthly 快照（6/7/8月）供对比验收 |

## 2026-09-06 收尾记录（审查修复批+两高危）
- B 31600cb 两高危：update() LambdaUpdateWrapper 定向 SET / AsyncConfig vaultDigestExecutor（core2 max4 queue64 CallerRuns）
- 132/132 测试独立复跑全绿；真库 vault_items 13 活行全 confirmed、key chunks 12、词典 9 词、tool_calls 0（待联调）
- 数据库状态：digest_status 五态迁移完成（done→confirmed 11 行）；period_month CHAR(7) 2026-08 快照在位
- 四服务已停（9050/50051/18080/5199 全清），用户接手 IDEA 启动验证：上传→extracted→确认→confirmed→检索链路

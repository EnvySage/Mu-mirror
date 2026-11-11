# 个人词典（User Lexicon）设计稿 v1.0

> 2026-09-05 总指挥与用户逐项对齐后定稿。词典 sprint 唯一蓝本。
> 一句话：从用户日记里学"论文=毕设RAG检索"这类个人指代，确认后注入 LLM 全链路，提升分类/检索/对话准确率。

## 0. 核心哲学（不可违背）

1. **机器猜的不直接用**：pending 只展示不注入，confirmed 才生效——与"审核过的才进记忆"同构
2. **词表是加权不是替换**：原文 query 照常走向量检索，词表错了退化为普通检索，不是灾难
3. **Python 无状态铁律**：词表随 gRPC 请求携带，用完即弃，Python 不查库
4. **不动存量向量**：1024 维老 chunk 向量零重建（裁决 #18），词表只走 query 侧改写 + prompt grounding
5. **不新增用户等待路径**：抽取挂定时任务顺路，confirmReview 不碰

## 1. 数据模型（DB Agent）

```sql
CREATE TABLE IF NOT EXISTS user_terms (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    term VARCHAR(100) NOT NULL,
    aliases JSONB DEFAULT '[]'::jsonb,     -- ["毕设","那个设计"]
    description TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending/confirmed/dismissed
    query_hit_count INT DEFAULT 0,          -- 用户提问命中（控制注入优先级）
    content_hit_count INT DEFAULT 0,        -- 入库内容命中（控制过期沉底）
    last_confirmed_at TIMESTAMPTZ,          -- confirmed 卡片显示"最后确认于x日"
    last_seen_at TIMESTAMPTZ,               -- 最近一次语料出现（衰减依据）
    source_chunk_id BIGINT REFERENCES chunks(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT uq_user_terms UNIQUE (user_id, term)
);
CREATE INDEX IF NOT EXISTS idx_user_terms_user_status ON user_terms(user_id, status);
```

- hit_count 拆两字段：query 命中管"活跃度/注入优先级"，content 命中管"还活着没"，衰减逻辑不打架
- dismissed 不删行（30 天后可重新浮现），沉底即可

## 2. proto 契约（shared-protocol.md 登记，B/AI 同步）

```protobuf
message GlossaryTerm {
  string term = 1;
  string description = 2;
  repeated string aliases = 3;
  string confirmed_at = 4;   // ISO 日期，prompt 里标注"x月确认"
}

// 三个请求各加：
repeated GlossaryTerm glossary = N;

// 新 RPC：
message ExtractTermsRequest {
  repeated ChunkDTO chunks = 1;            // 14 天窗口已确认 chunks（user_edited 优先）
  repeated GlossaryTerm existing_terms = 2; // 现有词条（去重+合并依据）
  ModelInfo model = 3;
}
message ExtractTermsReply {
  message TermCandidate {
    string term = 1;
    repeated string aliases = 2;
    string description = 3;       // 含来源依据，用户可判断
    string kind = 4;              // new / evidence / update
    string evidence = 5;          // 佐证摘要（"近14天出现3次"）
    int64 source_chunk_id = 6;
  }
  repeated TermCandidate candidates = 1;
}
rpc ExtractTerms(ExtractTermsRequest) returns (ExtractTermsReply);
```

- 注入点：ClassifyRequest / ExtractIntentRequest / ChatRequest / GenerateProfileRequest 全加 glossary
- **Classify 注入是最大增量**：错误在分类现场修最便宜
- 第 7 套 prompt：prompts/extract_terms.txt

## 3. 抽取（AI Agent 实现 RPC，B Agent 调度）

- **每日 01:00**：DailySummaryScheduler 顺路。语料=近 14 天 confirmed chunks（user_edited=true 权重优先），调 ExtractTerms
- **去重**：近 30 天已处理（confirmed/dismissed/pending 已存在）的 term 跳过；dismissed 30 天后可重新浮现
- **分级**：new（新词）/ evidence（已有 pending 证据+1，更新 evidence 与 source）/ update（confirmed 词解释过时，生成更新建议，打回 pending 等确认）
- **每月 1 号 02:00**：MonthlyMirrorScheduler 顺路两项：
  1. **词条合并**：重复/矛盾词条（"RAG那个设计"与"论文"同指）生成 aliases 合并建议 → 落 pending 复核
  2. **漂移审计**：拿 confirmed 词条 description vs 近 30 天含该词语料，LLM 判一致性；不一致 → 打回 pending + 新解释建议（"游戏：8月起多指FGO，3-7月指明日方舟"）。**漂移最多活一个月且被系统主动递到用户面前**
- LLM 抽取有噪音是已知风险，靠 pending 门禁 + 月度审计兜底，不上实时 co-occurrence（future work 素材）

## 4. 注入（B Agent）

- `GlossaryService`：查 confirmed 词条，按 query_hit_count 排序 top **30** 截断，进程内缓存 60s
- **ExtractIntent query 侧匹配**：term/alias 与 query 字符串匹配（几十条数据，不搞花活）→ 命中词条 query_hit_count++ 且**只把命中的**传 Python（省 prompt）；未命中传 top 高频词（grounding 用）
- Classify/GenerateProfile/Chat 传 top 30 全量
- prompt 软约束固定话术（Python 渲染）："以下用户个人词汇表**仅供参考，解释可能过时**；与近期记录矛盾时，以近期记录为准。" + 每词条附 confirmed_at

## 5. 前端（F Agent）

### 5a. 每日总结 sheet 词条候选分区（用户主提议——塞进已有浏览动线）
```
── 个人词典候选 ──────────
论文  待确认 · 证据 3 条（近 14 天）
  AI 理解：你的毕业设计《AI日记镜子系统》，RAG 检索方向
  依据：查看原文 →（跳 source_chunk 记录详情，复用现有交互）
  ⚠️ 确认后对话会按此理解检索你的记录；理解过时会导致偏差，可随时在设置页修改
  [确认] [改一改] [不要]
```
- ⚠️ 提示语**固定文案**（非 LLM 生成）
- 改一改 = 卡片原地展开编辑（term/aliases/description），改完确认
- evidence/update 候选同分区展示，update 注明"建议更新解释"

### 5b. 设置页"个人词典"卡（全量管理入口）
- 三组：待确认（badge 角标）/ 已生效（显示"最后确认于x日 · 近30天相关记录n条"）/ 已忽略
- CRUD：确认/编辑/删除/手动新增（"教镜子一个词"）
- 入口角标：侧栏设置图标，有 pending 才显示，不弹窗不打断

### 5c. API（B 提供）
GET /api/glossary（三组分好）· POST /api/glossary（手动新增 confirmed）
PUT /api/glossary/{id}（编辑/更新解释）· DELETE /api/glossary/{id}
POST /api/glossary/{id}/confirm · POST /api/glossary/{id}/dismiss
POST /api/glossary/extract（手动触发抽取，懒人立即出候选）

## 6. 参数表（首版默认值，论文消融实验变量）

| 参数 | 值 | 依据 |
|---|---|---|
| 抽取窗口 | 14 天 | 跨周归纳"游戏→明日方舟→FGO"演变；断更日免疫 |
| 去重窗 | 30 天 | 已处理词不重复提，防刷屏 |
| dismissed 复活窗 | 30 天 | 用户可能改主意 |
| 注入上限 | 30 条 | prompt 膨胀防线 |
| 缓存 | 60s | 词条低频变 |

## 7. 验收口径

- otaku_it 账号造含"论文/毕设/RAG/游戏"语料 → 凌晨任务跑出 pending 候选 → 每日总结 sheet 可见 → 确认 → 对话问"我论文咋样了" → rewritten_query 含毕设/RAG → 检索命中相关 chunk
- 漂移演练：确认"游戏=明日方舟"后造 FGO 语料 → 月度审计打回 pending
- 关词表 vs 开词表 Hit@5/MRR 对比（阶段 6 素材）

## 8. 分工与顺序

| 序 | Agent | 任务 | 依赖 |
|---|---|---|---|
| 1 | DB | user_terms 建表 + schema/migration | 无（可立即开工） |
| 2 | B | GlossaryService + 5c 接口 + 双调度器接线 + glossary 注入 + **ChunkDTO 补 taskStatus**（F 第八轮欠账） | DB |
| 3 | AI | ExtractTerms RPC + 第 7 套 prompt + glossary 渲染 | 2 的 proto 登记 |
| 4 | F | 5a + 5b | 2 的接口 |
| 5 | 联调 | 验收口径全链路 | 全部 |

**串行约束**：Py 修复 Agent（第九轮）还在 AI 仓作业——任务 3 必须等它交付验收后再派。任务 1 无冲突可先行。

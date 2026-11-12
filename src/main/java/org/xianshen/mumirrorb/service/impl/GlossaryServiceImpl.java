package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.RecordStatus;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.AgentRunMapper;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.UserTermMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.UserTerm;
import org.xianshen.mumirrorb.pojo.DTO.GlossaryCreateDTO;
import org.xianshen.mumirrorb.pojo.DTO.GlossaryUpdateDTO;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO.UserTermVO;
import org.xianshen.mumirrorb.service.GlossaryService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 个人词典服务实现（lexicon-design.md 第 2/3/4/5c 节）
 *
 * <p>核心哲学落地：</p>
 * <ul>
 *   <li>pending 只展示不注入，confirmed 才生效（#0.1）</li>
 *   <li>注入 = confirmed 按 query_hit_count 排序 top 30 截断 + 60s 进程内缓存（#4）</li>
 *   <li>ExtractIntent query 侧匹配：term/alias 字符串包含，命中 query_hit_count++，
 *       只把命中的传 Python（省 prompt）；未命中传 top 高频词 grounding（调用方拼）</li>
 *   <li>抽取挂定时任务顺路，不新增用户等待路径（#0.5）</li>
 * </ul>
 *
 * <p><strong>ExtractTerms 降级策略</strong>（Python RPC 本轮由 AI Agent 实现，未上线时不能炸）：
 * gRPC 调用失败 → 落日志 → 降级为本地语料包含计数（term=语料高频专有词的保守近似只做
 * evidence 通道关闭，完全跳过本轮抽取），保证每日总结主流程零影响（任务书防御要求）。</p>
 */

@Service
@RequiredArgsConstructor
public class GlossaryServiceImpl implements GlossaryService {
    protected static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GlossaryServiceImpl.class);
    private final GlossaryEntries glossaryEntries = new GlossaryEntries(this);
    private final GlossaryInjection glossaryInjection = new GlossaryInjection(this);
    private final GlossaryExtraction glossaryExtraction = new GlossaryExtraction(this);
    private final GlossaryMaintenance glossaryMaintenance = new GlossaryMaintenance(this);


    @org.springframework.beans.factory.annotation.Autowired(required=false)
    protected org.xianshen.mumirrorb.achievement.AchievementActivity achievementActivity;

    protected static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 注入上限（lexicon-design.md 参数表：prompt 膨胀防线） */
    static final int INJECTION_LIMIT = 30;
    /** 缓存时长（参数表：60s，词条低频变） */
    static final long CACHE_TTL_MS = 60_000L;
    /** 抽取语料窗口（参数表：14 天） */
    static final int EXTRACT_WINDOW_DAYS = 14;
    /** 去重窗（参数表：30 天；dismissed 复活窗同值） */
    static final int DEDUP_WINDOW_DAYS = 30;
    /** 月度审计语料窗口（第 3 节：近 30 天含该词语料） */
    static final int AUDIT_WINDOW_DAYS = 30;
    /** 抽取语料上限（14 天 confirmed chunks，防止 prompt 爆炸） */
    static final int CORPUS_LIMIT = 200;
    /** 审计语料 record 扫描上限（先 record 后 chunk 两段式，防止 IN 列表爆炸） */
    static final int AUDIT_CORPUS_RECORD_LIMIT = 500;
    /** confirmed 卡片"近30天相关记录n条"统计上限（控制 ILIKE 成本） */
    protected static final int RECENT_HITS_TERMS_LIMIT = 30;
    /** 降级抽取：语料包含计数达到该次数才生成候选（噪音防线） */
    static final int FALLBACK_MIN_HITS = 3;

    protected final UserTermMapper termMapper;
    protected final ChunkMapper chunkMapper;
    protected final RecordMapper recordMapper;
    protected final AiGrpcClient aiGrpcClient;
    protected final AgentRunMapper agentRunMapper;

    /**
     * confirmed 注入列表缓存：userId → (加载时间, 词条列表)
     *
     * <p>进程内 60s TTL；writeThrough 在 confirm/dismiss/update 后主动失效本用户缓存。</p>
     */
    protected final Map<UUID, CacheEntry> injectionCache = new ConcurrentHashMap<>();

    protected record CacheEntry(long loadedAt, List<UserTermVO> terms) {
    }



    @Override
    @Transactional(readOnly = true)
    public List<UserTermVO> list(UUID userId) {
        return glossaryEntries.list(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public GlossaryGroupVO listGrouped(UUID userId) {
        return glossaryEntries.listGrouped(userId);
    }



    @Override
    @Transactional
    public UserTermVO create(UUID userId, GlossaryCreateDTO dto) {
        return glossaryEntries.create(userId, dto);
    }

    @Override
    @Transactional
    public UserTermVO update(Long id, GlossaryUpdateDTO dto, UUID userId) {
        return glossaryEntries.update(id, dto, userId);
    }

    @Override
    @Transactional
    public void delete(Long id, UUID userId) {
        glossaryEntries.delete(id, userId);
    }



    @Override
    @Transactional
    public UserTermVO confirm(Long id, UUID userId, String newDescription, List<String> newAliases) {
        return glossaryEntries.confirm(id, userId, newDescription, newAliases);
    }

    @Override
    @Transactional
    public UserTermVO dismiss(Long id, UUID userId) {
        return glossaryEntries.dismiss(id, userId);
    }



    @Override
    @Transactional(readOnly = true)
    public List<UserTermVO> confirmedForInjection(UUID userId) {
        return glossaryInjection.confirmedForInjection(userId);
    }

    @Override
    @Transactional
    public List<UserTermVO> matchQueryTerms(UUID userId, String query) {
        return glossaryInjection.matchQueryTerms(userId, query);
    }

    @Override
    @Transactional
    public void recordQueryHits(UUID userId, UUID runId, List<Long> termIds) {
        glossaryInjection.recordQueryHits(userId, runId, termIds);
    }

    /**
     * term/alias 与 query 字符串包含匹配（几十条数据，不搞花活，#4）
     */
    public static boolean matches(UserTermVO vo, String query) {
        if (vo.getTerm() != null && !vo.getTerm().isBlank() && query.contains(vo.getTerm())) {
            return true;
        }
        if (vo.getAliases() != null) {
            for (String alias : vo.getAliases()) {
                if (alias != null && !alias.isBlank() && query.contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }



    @Override
    @Transactional
    public List<UserTermVO> extractForUser(UUID userId) {
        return glossaryExtraction.extractForUser(userId);
    }

    @Override
    public void extractScheduled(UUID userId) {
        glossaryExtraction.extractScheduled(userId);
    }


    protected List<UserTermVO> doExtract(UUID userId, int windowDays) {
        return glossaryExtraction.doExtract(userId, windowDays);
    }


    protected void applyUpdateCandidate(UUID userId, String term,
                                     RecordProcessorProto.ExtractTermsReply.TermCandidate c,
                                     Set<String> known, OffsetDateTime now) {
        glossaryExtraction.applyUpdateCandidate(userId, term, c, known, now);
    }


    protected void applyEvidence(UUID userId, String term,
                               RecordProcessorProto.ExtractTermsReply.TermCandidate c, OffsetDateTime now) {
        glossaryExtraction.applyEvidence(userId, term, c, now);
    }



    @Override
    public void monthlyMaintenance(UUID userId) {
        glossaryMaintenance.monthlyMaintenance(userId);
    }


    protected void doMonthlyMaintenance(UUID userId) {
        glossaryMaintenance.doMonthlyMaintenance(userId);
    }

    protected boolean isAliasOf(UserTerm term, String name) {
        return glossaryMaintenance.isAliasOf(term, name);
    }


    protected boolean ensureMergeCandidate(UUID userId, UserTerm shortTerm, UserTerm longTerm, OffsetDateTime now) {
        return glossaryMaintenance.ensureMergeCandidate(userId, shortTerm, longTerm, now);
    }

    /**
     * 语料是否提及该词（term 或任一别名出现在 segment/content）
     */
    static boolean corpusMentions(List<Chunk> corpus, UserTerm term) {
        for (Chunk chunk : corpus) {
            String text = chunk.getSegment() == null ? "" : chunk.getSegment();
            if (text.contains(term.getTerm())) {
                return true;
            }
            if (term.getAliases() != null) {
                for (String alias : term.getAliases()) {
                    if (alias != null && !alias.isBlank() && text.contains(alias)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }



    protected boolean hasLlmConfig(UUID userId) {
        return glossaryExtraction.hasLlmConfig(userId);
    }


    protected Set<String> knownTermNames(UUID userId) {
        return glossaryExtraction.knownTermNames(userId);
    }


    protected void attachRecentHitCounts(UUID userId, List<UserTermVO> terms) {
        glossaryInjection.attachRecentHitCounts(userId, terms);
    }

    protected static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    protected UserTerm requireOwned(Long id, UUID userId) {
        return glossaryEntries.requireOwned(id, userId);
    }

    protected static String statusOf(String status) {
        return status == null ? "pending" : status.trim().toLowerCase(Locale.ROOT);
    }


    protected Chunk ownedSourceChunk(UUID userId, long sourceChunkId) {
        return glossaryEntries.ownedSourceChunk(userId, sourceChunkId);
    }

    protected static List<String> normalizeAliases(List<String> aliases) {
        if (aliases == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String a : aliases) {
            if (a != null && !a.isBlank()) {
                set.add(a.trim());
            }
        }
        return new ArrayList<>(set);
    }

    protected static List<String> norm(List<String> aliases) {
        return aliases == null ? List.of() : aliases;
    }

    protected List<UserTermVO> toVOList(List<UserTerm> entities) {
        return glossaryInjection.toVOList(entities);
    }

    protected static UserTermVO toVO(UserTerm e, String evidence) {
        return UserTermVO.builder()
                .id(e.getId())
                .term(e.getTerm())
                .aliases(e.getAliases())
                .description(e.getDescription())
                .status(e.getStatus())
                .queryHitCount(e.getQueryHitCount())
                .contentHitCount(e.getContentHitCount())
                .lastConfirmedAt(e.getLastConfirmedAt())
                .lastSeenAt(e.getLastSeenAt())
                .sourceChunkId(e.getSourceChunkId())
                .sourceRecordId(e.getSourceRecordId())
                .evidence(evidence)
                .createdAt(e.getCreatedAt())
                .build();
    }

    /**
     * UserTerm → proto GlossaryTerm（confirmed_at 格式 yyyy-MM-dd，Python prompt 标注"x月确认"）
     */
    public static CommonProto.GlossaryTerm toProtoTerm(UserTerm term) {
        CommonProto.GlossaryTerm.Builder builder = CommonProto.GlossaryTerm.newBuilder()
                .setTerm(nullSafe(term.getTerm()))
                .setDescription(nullSafe(term.getDescription()));
        if (term.getAliases() != null) {
            builder.addAllAliases(term.getAliases());
        }
        if (term.getLastConfirmedAt() != null) {
            builder.setConfirmedAt(term.getLastConfirmedAt().format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        return builder.build();
    }

    /**
     * Chunk → proto ChunkDTO（定义在 common.proto；metadata JSONB 展平为具名字段）
     */
    public static CommonProto.ChunkDTO toProtoChunk(Chunk chunk) {
        Map<String, Object> metadata = chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
        CommonProto.ChunkDTO.Builder builder = CommonProto.ChunkDTO.newBuilder()
                .setChunkId(chunk.getId() == null ? 0 : chunk.getId())
                .setRecordId(chunk.getRecordId() == null ? 0 : chunk.getRecordId())
                .setSegment(nullSafe(chunk.getSegment()))
                .setContent(nullSafe(chunk.getSegment()))
                .setCreatedAt(chunk.getCreatedAt() == null
                        ? "" : chunk.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")))
                .setUserEdited(Boolean.TRUE.equals(chunk.getUserEdited()));
        Object title = metadata.get("title");
        if (title != null) {
            builder.setTitle(String.valueOf(title));
        }
        Object summary = metadata.get("summary");
        if (summary != null) {
            builder.setSummary(String.valueOf(summary));
        }
        Object contentType = metadata.get("contentType");
        if (contentType != null) {
            builder.setContentType(String.valueOf(contentType));
        }
        Object taskStatus = metadata.get("taskStatus");
        if (taskStatus != null) {
            builder.setTaskStatus(String.valueOf(taskStatus));
        }
        addStrings(builder::addMoods, metadata.get("mood"));
        addStrings(builder::addKeywords, metadata.get("keywords"));
        return builder.build();
    }

    protected static void addStrings(java.util.function.Consumer<String> adder, Object value) {
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    adder.accept(String.valueOf(o));
                }
            }
        }
    }

    protected static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    protected void invalidateCache(UUID userId) {
        glossaryInjection.invalidateCache(userId);
    }
}

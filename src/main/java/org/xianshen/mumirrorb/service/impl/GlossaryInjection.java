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

import static org.xianshen.mumirrorb.service.impl.GlossaryServiceImpl.*;

/** GlossaryInjection keeps the service entry points and transaction boundaries in GlossaryServiceImpl. */
final class GlossaryInjection {
    private final GlossaryServiceImpl owner;
    GlossaryInjection(GlossaryServiceImpl owner) { this.owner=owner; }


    // ==================== 注入（第 4 节） ====================

    @Transactional(readOnly = true)
    public List<UserTermVO> confirmedForInjection(UUID userId) {
        CacheEntry entry = owner.injectionCache.get(userId);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.loadedAt() < owner.CACHE_TTL_MS) {
            return entry.terms();
        }
        List<UserTermVO> terms = owner.toVOList(owner.termMapper.selectConfirmedTop(userId, owner.INJECTION_LIMIT));
        owner.injectionCache.put(userId, new CacheEntry(now, terms));
        return terms;
    }


    @Transactional
    public List<UserTermVO> matchQueryTerms(UUID userId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query;
        List<UserTermVO> matched = new ArrayList<>();
        for (UserTermVO vo : owner.confirmedForInjection(userId)) {
            if (owner.matches(vo, q)) {
                matched.add(vo);
                // 命中即计数（query 侧命中管注入优先级；flush 落库）
                owner.termMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserTerm>()
                        .eq(UserTerm::getId, vo.getId())
                        .setSql("query_hit_count = COALESCE(query_hit_count, 0) + 1"));
            }
        }
        if (!matched.isEmpty()) {
            if (owner.achievementActivity != null) owner.achievementActivity.glossaryMatched(userId, query);
            owner.invalidateCache(userId); // hit_count 变了，缓存里的排序过期
            owner.log.debug("ExtractIntent query 侧命中 {} 个词条，用户: {}", matched.size(), userId);
        }
        return matched;
    }


    @Transactional
    public void recordQueryHits(UUID userId, UUID runId, List<Long> termIds) {
        if (userId == null || runId == null || termIds == null || termIds.isEmpty()) {
            return;
        }
        List<Long> distinctIds = termIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (distinctIds.isEmpty() || owner.agentRunMapper.applyGlossaryHitsOnce(userId, runId) != 1) {
            return;
        }
        int updated = owner.termMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserTerm>()
                .eq(UserTerm::getUserId, userId)
                .eq(UserTerm::getStatus, "confirmed")
                .in(UserTerm::getId, distinctIds)
                .setSql("query_hit_count = COALESCE(query_hit_count, 0) + 1"));
        if (updated > 0) {
            owner.invalidateCache(userId);
        }
    }



    /**
     * confirmed 卡片"近30天相关记录n条"（仅已生效词，前 30 条控制成本）
     */
    protected void attachRecentHitCounts(UUID userId, List<UserTermVO> terms) {
        int budget = owner.RECENT_HITS_TERMS_LIMIT;
        OffsetDateTime since = LocalDate.now(owner.ZONE).minusDays(owner.AUDIT_WINDOW_DAYS).atStartOfDay(owner.ZONE).toOffsetDateTime();
        for (UserTermVO vo : terms) {
            if (!"confirmed".equals(owner.statusOf(vo.getStatus())) || budget-- <= 0) {
                continue;
            }
            List<String> patterns = new ArrayList<>();
            if (vo.getTerm() != null && !vo.getTerm().isBlank()) {
                patterns.add("%" + owner.escapeLike(vo.getTerm()) + "%");
            }
            if (vo.getAliases() != null) {
                for (String alias : vo.getAliases()) {
                    if (alias != null && !alias.isBlank()) {
                        patterns.add("%" + owner.escapeLike(alias) + "%");
                    }
                }
            }
            if (patterns.isEmpty()) {
                continue;
            }
            Integer cnt = owner.termMapper.countRecentRecordHits(userId, patterns, since);
            vo.setQueryHitCount(vo.getQueryHitCount() == null ? 0 : vo.getQueryHitCount());
            vo.setContentHitCount(cnt == null ? 0 : cnt);
        }
    }



    protected List<UserTermVO> toVOList(List<UserTerm> entities) {
        List<UserTermVO> result = new ArrayList<>(entities.size());
        for (UserTerm e : entities) {
            result.add(owner.toVO(e, null));
        }
        return result;
    }



    protected void invalidateCache(UUID userId) {
        owner.injectionCache.remove(userId);
    }
}

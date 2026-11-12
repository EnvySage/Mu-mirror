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

/** GlossaryMaintenance keeps the service entry points and transaction boundaries in GlossaryServiceImpl. */
final class GlossaryMaintenance {
    private final GlossaryServiceImpl owner;
    GlossaryMaintenance(GlossaryServiceImpl owner) { this.owner=owner; }


    // ==================== 月度维护（第 3 节：合并 + 漂移审计） ====================

    public void monthlyMaintenance(UUID userId) {
        try {
            owner.doMonthlyMaintenance(userId);
        } catch (Exception e) {
            owner.log.warn("词典月度维护失败（不影响月度画像主流程），用户: {}, 原因: {}", userId, e.getMessage());
        }
    }



    /**
     * 月度两项（失败互不影响）：
     * ① 词条合并：term 互为子串的重复词（"RAG那个设计" 与 "论文"同指场景的保守近似）
     *    生成合并候选（短词并入长词的别名），落 pending 复核
     * ② 漂移审计：confirmed 词解释 vs 近 30 天语料包含度——词已不再出现于语料 →
     *    打回 pending（语义一致性判断由 ExtractTerms RPC 的 update 分支承担，Python 侧实现；
     *    此处做无 LLM 的确定性兜底：语料 30 天 0 命中的 confirmed 词视为"疑似过时"打回）
     */
    protected void doMonthlyMaintenance(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(owner.ZONE);

        // ① 词条合并：互为包含的 confirmed 词对（保守近似：完全包含关系才建议合并）
        List<UserTerm> confirmed = owner.termMapper.selectConfirmedTop(userId, 100);
        int merged = 0;
        for (UserTerm a : confirmed) {
            for (UserTerm b : confirmed) {
                if (a.getId().equals(b.getId())) {
                    continue;
                }
                String ta = a.getTerm(), tb = b.getTerm();
                if (ta.length() > tb.length() && ta.contains(tb) && !owner.isAliasOf(a, tb)) {
                    // b 是 a 的子串：建议把 b 并为 a 的别名（b 保留待用户确认合并建议）
                    if (owner.ensureMergeCandidate(userId, b, a, now)) {
                        merged++;
                    }
                }
            }
        }

        // ② 漂移审计（确定性兜底）：confirmed 词近 30 天语料 0 命中 → 打回 pending
        // fix-batch B2（Y2）：审计语料同口径收口（status='done' AND source='user'）
        OffsetDateTime since = LocalDate.now(owner.ZONE).minusDays(owner.AUDIT_WINDOW_DAYS).atStartOfDay(owner.ZONE).toOffsetDateTime();
        List<Long> userRecordIds = owner.recordMapper.selectList(new LambdaQueryWrapper<Record>()
                .eq(Record::getUserId, userId)
                .eq(Record::getSource, "user")
                .eq(Record::getStatus, RecordStatus.DONE)
                .isNull(Record::getDeletedAt)
                .ge(Record::getCreatedAt, since)
                .last("LIMIT " + owner.AUDIT_CORPUS_RECORD_LIMIT))
                .stream().map(Record::getId).toList();
        List<Chunk> corpus = userRecordIds.isEmpty() ? List.of()
                : owner.chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getUserId, userId)
                        .in(Chunk::getRecordId, userRecordIds)
                        .eq(Chunk::getAiMemoryApproved, true)
                        .isNotNull(Chunk::getSegment)
                        .last("LIMIT " + owner.CORPUS_LIMIT));
        int drifted = 0;
        for (UserTerm term : confirmed) {
            if (!owner.corpusMentions(corpus, term)) {
                // 语料长期不出现：疑似漂移/过时。打回 pending 主动递到用户面前（#3：漂移最多活一个月）
                term.setStatus("pending");
                term.setUpdatedAt(now);
                owner.termMapper.updateById(term);
                drifted++;
            }
        }
        if (merged > 0 || drifted > 0) {
            owner.invalidateCache(userId);
        }
        owner.log.info("词典月度维护完成，用户: {}: 合并建议 {} 条, 漂移打回 {} 条", userId, merged, drifted);
    }



    protected boolean isAliasOf(UserTerm term, String name) {
        return term.getAliases() != null && term.getAliases().contains(name);
    }



    /**
     * 合并建议：给"被包含的短词"打 pending + 建议并入的别名预填进 aliases（fix-batch C7：
     * 不再追加进 description——描述保持用户原文，别名候选由 confirm 的 newAliases 并入语义天然支持）
     *
     * @return 是否新建了建议
     */
    protected boolean ensureMergeCandidate(UUID userId, UserTerm shortTerm, UserTerm longTerm, OffsetDateTime now) {
        // 短词已是 pending（有未处理建议）不重复堆
        if (!"confirmed".equals(shortTerm.getStatus())) {
            return false;
        }
        shortTerm.setStatus("pending");
        // C7：合并建议目标词预填进 aliases（用户确认时 confirm 的 newAliases 通道并入；
        // 忽略则 dismiss，aliases 不留痕）
        Set<String> prefill = new LinkedHashSet<>(owner.norm(shortTerm.getAliases()));
        prefill.add(longTerm.getTerm());
        shortTerm.setAliases(new ArrayList<>(prefill));
        shortTerm.setUpdatedAt(now);
        owner.termMapper.updateById(shortTerm);
        owner.log.info("词条合并建议已生成：{} → {}（pending 复核，别名预填 {}），用户: {}",
                shortTerm.getTerm(), longTerm.getTerm(), longTerm.getTerm(), userId);
        return true;
    }
}

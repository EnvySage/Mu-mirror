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

/** GlossaryEntries keeps the service entry points and transaction boundaries in GlossaryServiceImpl. */
final class GlossaryEntries {
    private final GlossaryServiceImpl owner;
    GlossaryEntries(GlossaryServiceImpl owner) { this.owner=owner; }


    // ==================== 查询（5b 三组分好） ====================

    @Transactional(readOnly = true)
    public List<UserTermVO> list(UUID userId) {
        List<UserTermVO> all = owner.toVOList(owner.termMapper.selectByUser(userId));
        owner.attachRecentHitCounts(userId, all);
        return all;
    }


    @Transactional(readOnly = true)
    public GlossaryGroupVO listGrouped(UUID userId) {
        List<UserTermVO> all = owner.list(userId);
        List<UserTermVO> pending = new ArrayList<>();
        List<UserTermVO> confirmed = new ArrayList<>();
        List<UserTermVO> dismissed = new ArrayList<>();
        for (UserTermVO vo : all) {
            switch (owner.statusOf(vo.getStatus())) {
                case "pending" -> pending.add(vo);
                case "confirmed" -> confirmed.add(vo);
                default -> dismissed.add(vo);
            }
        }
        return GlossaryGroupVO.builder()
                .pending(pending).confirmed(confirmed).dismissed(dismissed)
                .build();
    }



    // ==================== CRUD（5c） ====================

    @Transactional
    public UserTermVO create(UUID userId, GlossaryCreateDTO dto) {
        String term = dto.getTerm().trim();
        // 唯一约束（user_id, term）先行校验，报可读错误而非 DB 异常
        UserTerm existing = owner.termMapper.selectOne(new LambdaQueryWrapper<UserTerm>()
                .eq(UserTerm::getUserId, userId)
                .eq(UserTerm::getTerm, term));
        if (existing != null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "词条已存在：" + term);
        }
        UserTerm entity = UserTerm.builder()
                .userId(userId)
                .term(term)
                .aliases(owner.normalizeAliases(dto.getAliases()))
                .description(dto.getDescription().trim())
                .status("confirmed") // 手动新增 = 用户亲口教，直接生效（5c）
                .queryHitCount(0)
                .contentHitCount(0)
                .lastConfirmedAt(OffsetDateTime.now(owner.ZONE))
                .createdAt(OffsetDateTime.now(owner.ZONE))
                .updatedAt(OffsetDateTime.now(owner.ZONE))
                .build();
        owner.termMapper.insert(entity);
        if (owner.achievementActivity != null)
            owner.achievementActivity.glossaryEdited(userId, entity.getId(),
                    entity.getAliases() != null && !entity.getAliases().isEmpty(), !entity.getDescription().isBlank());
        owner.invalidateCache(userId);
        owner.log.info("词条手动新增（confirmed），用户: {}, 词: {}", userId, term);
        return owner.toVO(entity, null);
    }


    @Transactional
    public UserTermVO update(Long id, GlossaryUpdateDTO dto, UUID userId) {
        UserTerm entity = owner.requireOwned(id, userId);
        var previousAliases = entity.getAliases() == null ? List.<String>of() : List.copyOf(entity.getAliases());
        String previousDescription = entity.getDescription();
        String term = dto.getTerm().trim();
        if (!term.equals(entity.getTerm())) {
            UserTerm dup = owner.termMapper.selectOne(new LambdaQueryWrapper<UserTerm>()
                    .eq(UserTerm::getUserId, userId)
                    .eq(UserTerm::getTerm, term));
            if (dup != null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "词条已存在：" + term);
            }
            entity.setTerm(term);
        }
        entity.setAliases(owner.normalizeAliases(dto.getAliases()));
        entity.setDescription(dto.getDescription().trim());
        // 编辑解释视为一次重新确认：刷新 last_confirmed_at（"最后确认于x日"口径）
        if ("confirmed".equals(entity.getStatus())) {
            entity.setLastConfirmedAt(OffsetDateTime.now(owner.ZONE));
        }
        entity.setUpdatedAt(OffsetDateTime.now(owner.ZONE));
        owner.termMapper.updateById(entity);
        if (owner.achievementActivity != null)
            owner.achievementActivity.glossaryEdited(userId, id,
                    !java.util.Objects.equals(previousAliases, entity.getAliases()),
                    !java.util.Objects.equals(previousDescription, entity.getDescription()));
        owner.invalidateCache(userId);
        return owner.toVO(entity, null);
    }


    @Transactional
    public void delete(Long id, UUID userId) {
        UserTerm entity = owner.requireOwned(id, userId);
        owner.termMapper.deleteById(entity.getId());
        owner.invalidateCache(userId);
        owner.log.info("词条已删除，ID: {}, 词: {}, 用户: {}", id, entity.getTerm(), userId);
    }



    // ==================== 状态机（confirm / dismiss） ====================

    @Transactional
    public UserTermVO confirm(Long id, UUID userId, String newDescription, List<String> newAliases) {
        UserTerm entity = owner.requireOwned(id, userId);
        var previousAliases = entity.getAliases() == null ? List.<String>of() : List.copyOf(entity.getAliases());
        String previousDescription = entity.getDescription();
        String from = owner.statusOf(entity.getStatus());
        if ("confirmed".equals(from)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "词条已是 confirmed 状态");
        }
        // update 候选确认：覆盖为新解释建议；合并建议确认：并入别名
        if (newDescription != null && !newDescription.isBlank()) {
            entity.setDescription(newDescription.trim());
        }
        if (newAliases != null && !newAliases.isEmpty()) {
            Set<String> merged = new LinkedHashSet<>(owner.norm(entity.getAliases()));
            merged.addAll(owner.normalizeAliases(newAliases));
            entity.setAliases(new ArrayList<>(merged));
        }
        entity.setStatus("confirmed");
        entity.setLastConfirmedAt(OffsetDateTime.now(owner.ZONE));
        entity.setUpdatedAt(OffsetDateTime.now(owner.ZONE));
        owner.termMapper.updateById(entity);
        if (owner.achievementActivity != null)
            owner.achievementActivity.glossaryEdited(userId, id,
                    !java.util.Objects.equals(previousAliases, entity.getAliases()),
                    !java.util.Objects.equals(previousDescription, entity.getDescription()));
        owner.invalidateCache(userId);
        owner.log.info("词条已确认（{} → confirmed），ID: {}, 词: {}, 用户: {}", from, id, entity.getTerm(), userId);
        return owner.toVO(entity, null);
    }


    @Transactional
    public UserTermVO dismiss(Long id, UUID userId) {
        UserTerm entity = owner.requireOwned(id, userId);
        if ("dismissed".equals(owner.statusOf(entity.getStatus()))) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "词条已是 dismissed 状态");
        }
        // dismissed 不删行（#1：30 天后可重新浮现），沉底
        entity.setStatus("dismissed");
        entity.setUpdatedAt(OffsetDateTime.now(owner.ZONE));
        owner.termMapper.updateById(entity);
        owner.invalidateCache(userId);
        owner.log.info("词条已忽略（{} → dismissed），ID: {}, 词: {}, 用户: {}", owner.statusOf(entity.getStatus()), id, entity.getTerm(), userId);
        return owner.toVO(entity, null);
    }



    protected UserTerm requireOwned(Long id, UUID userId) {
        UserTerm entity = owner.termMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "词条不存在");
        }
        return entity;
    }



    /**
     * 校验佐证 chunk：返回该用户真实存在的 Chunk，否则 null
     *
     * <p>2026-09-23 加。source_chunk_id 由 LLM 填，AI 侧已有"须在本次语料内"防线，此处是
     * 第二道。模型写错数字时 chunks.id 全局自增，那个整数会命中别人的 chunk——原先只判
     * &gt; 0 就落库，佐证会指到其他用户的记录上（F 侧"查看原文"随之跳错）。</p>
     */
    protected Chunk ownedSourceChunk(UUID userId, long sourceChunkId) {
        if (sourceChunkId <= 0) {
            return null;
        }
        Chunk chunk = owner.chunkMapper.selectById(sourceChunkId);
        if (chunk == null || !userId.equals(chunk.getUserId())) {
            owner.log.warn("佐证 chunk 校验不通过，丢弃：chunkId={}, 存在={}, 期望 userId={}",
                    sourceChunkId, chunk != null, userId);
            return null;
        }
        return chunk;
    }
}

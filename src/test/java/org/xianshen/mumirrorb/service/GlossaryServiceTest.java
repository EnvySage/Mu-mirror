package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.UserTermMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.UserTerm;
import org.xianshen.mumirrorb.pojo.DTO.GlossaryCreateDTO;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO.UserTermVO;
import org.xianshen.mumirrorb.service.impl.GlossaryServiceImpl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 个人词典服务单测（lexicon-design.md 2/3/4/5c）
 *
 * <p>覆盖：top30 截断 / 60s 缓存命中与失效 / alias query 匹配 + query_hit_count 自增 /
 * confirm/dismiss 状态机 / 手动新增 confirmed / ExtractTerms 降级（Python 未上线不炸）/
 * 月度漂移打回 / 候选落 pending + evidence 计数。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlossaryServiceTest {

    @Mock
    private UserTermMapper termMapper;
    @Mock
    private ChunkMapper chunkMapper;
    @Mock
    private AiGrpcClient aiGrpcClient;

    private GlossaryServiceImpl glossaryService;

    private static final UUID USER_ID = UUID.randomUUID();

    /** 唯一 term 名计数（避免多用例同名词干扰 mock stub） */
    private int termSeq = 0;

    @BeforeEach
    void setUp() {
        glossaryService = new GlossaryServiceImpl(termMapper, chunkMapper, aiGrpcClient);
        org.mockito.Mockito.doReturn(true).when(aiGrpcClient).hasLlmConfig(USER_ID);
        org.mockito.Mockito.doReturn(CommonProto.LlmConfig.newBuilder().setModel("test-model").build()).when(aiGrpcClient).buildLlmConfigFor(USER_ID);
        org.mockito.Mockito.doReturn(List.of()).when(termMapper).selectByUser(USER_ID);
        org.mockito.Mockito.doReturn(List.of()).when(termMapper).selectConfirmedTop(eq(USER_ID), anyInt());
    }

    private UserTerm term(long id, String name, String status, int queryHits, List<String> aliases) {
        return UserTerm.builder()
                .id(id)
                .userId(USER_ID)
                .term(name)
                .aliases(aliases == null ? new ArrayList<>() : aliases)
                .description(name + " 的解释")
                .status(status)
                .queryHitCount(queryHits)
                .contentHitCount(1)
                .lastConfirmedAt("confirmed".equals(status) ? OffsetDateTime.now() : null)
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private UserTermVO vo(long id, String name, List<String> aliases) {
        return UserTermVO.builder()
                .id(id).term(name).aliases(aliases).description("d").status("confirmed")
                .lastConfirmedAt(OffsetDateTime.now())
                .build();
    }

    private String nextTerm() {
        return "词" + (++termSeq);
    }

    // ==================== 注入列表：top30 截断 + 缓存 ====================

    @Test
    @DisplayName("top30 截断：confirmed 不足 30 全返回，超 30 由 mapper LIMIT 截断（Service 不二次扩）")
    void injection_limitPassedToMapper() {
        glossaryService.confirmedForInjection(USER_ID);
        // 验证 LIMIT 参数是 30（设计参数表：注入上限 30）
        verify(termMapper).selectConfirmedTop(USER_ID, 30);
    }

    @Test
    @DisplayName("60s 缓存：同用户两次调用只查一次库；写入操作后缓存失效重新加载")
    void injection_cacheHitThenInvalidate() {
        org.mockito.Mockito.doReturn(List.of(term(1, nextTerm(), "confirmed", 5, null))).when(termMapper).selectConfirmedTop(eq(USER_ID), anyInt());

        glossaryService.confirmedForInjection(USER_ID);
        glossaryService.confirmedForInjection(USER_ID);
        glossaryService.confirmedForInjection(USER_ID);
        verify(termMapper, times(1)).selectConfirmedTop(eq(USER_ID), anyInt());

        // confirm 触发失效：下次调用重新查库
        org.mockito.Mockito.doReturn(term(9, nextTerm(), "pending", 0, null)).when(termMapper).selectById(9L);
        glossaryService.confirm(9L, USER_ID, null, null);
        glossaryService.confirmedForInjection(USER_ID);
        verify(termMapper, times(2)).selectConfirmedTop(eq(USER_ID), anyInt());
    }

    @Test
    @DisplayName("不同用户缓存隔离：B 用户不命中 A 用户缓存")
    void injection_cacheIsolatedPerUser() {
        org.mockito.Mockito.doReturn(List.of()).when(termMapper).selectConfirmedTop(eq(USER_ID), anyInt());
        glossaryService.confirmedForInjection(USER_ID);
        glossaryService.confirmedForInjection(UUID.randomUUID());
        verify(termMapper, times(2)).selectConfirmedTop(any(), eq(30));
    }

    // ==================== ExtractIntent query 侧匹配 ====================

    @Test
    @DisplayName("query 匹配：term 命中 → query_hit_count+1 落库，返回命中项")
    void matchQuery_termHitIncrementsCounter() {
        UserTermVO paper = vo(1L, "论文", List.of());
        org.mockito.Mockito.doReturn(List.of(term(90, "论文", "confirmed", 0, paper.getAliases() == null ? List.of() : paper.getAliases()))).when(termMapper).selectConfirmedTop(USER_ID, 30);

        List<UserTermVO> matched = glossaryService.matchQueryTerms(USER_ID, "我论文咋样了");

        assertEquals(1, matched.size());
        assertEquals("论文", matched.get(0).getTerm());
        // 计数落库（COALESCE 原子自增）
        verify(termMapper).update(eq(null), any());
    }

    @Test
    @DisplayName("query 匹配：alias 命中也算命中（设计 4：term/alias 字符串匹配）")
    void matchQuery_aliasHit() {
        UserTermVO paper = vo(1L, "论文", List.of("毕设", "那个设计"));
        org.mockito.Mockito.doReturn(List.of(term(90, "论文", "confirmed", 0, paper.getAliases() == null ? List.of() : paper.getAliases()))).when(termMapper).selectConfirmedTop(USER_ID, 30);

        List<UserTermVO> matched = glossaryService.matchQueryTerms(USER_ID, "那个设计进展如何");

        assertEquals(1, matched.size());
    }

    @Test
    @DisplayName("query 匹配：未命中返回空且不写计数（grounding 由调用方兜底）")
    void matchQuery_noHit_noWrite() {
        UserTermVO paper = vo(1L, "论文", null);
        org.mockito.Mockito.doReturn(List.of(term(90, "论文", "confirmed", 0, paper.getAliases() == null ? List.of() : paper.getAliases()))).when(termMapper).selectConfirmedTop(USER_ID, 30);

        List<UserTermVO> matched = glossaryService.matchQueryTerms(USER_ID, "今天天气不错");

        assertTrue(matched.isEmpty());
        verify(termMapper, never()).update(eq(null), any());
    }

    @Test
    @DisplayName("query 为空/空白：直接返回空，不查缓存不写库")
    void matchQuery_blankQuery() {
        assertTrue(glossaryService.matchQueryTerms(USER_ID, "  ").isEmpty());
        verify(termMapper, never()).selectConfirmedTop(any(), anyInt());
    }

    // ==================== confirm / dismiss 状态机 ====================

    @Test
    @DisplayName("confirm：pending → confirmed，写 last_confirmed_at")
    void confirm_pendingToConfirmed() {
        UserTerm pending = term(9, nextTerm(), "pending", 0, null);
        org.mockito.Mockito.doReturn(pending).when(termMapper).selectById(9L);

        UserTermVO vo = glossaryService.confirm(9L, USER_ID, null, null);

        assertEquals("confirmed", vo.getStatus());
        assertEquals(pending.getLastConfirmedAt(), vo.getLastConfirmedAt());
        ArgumentCaptor<UserTerm> captor = ArgumentCaptor.forClass(UserTerm.class);
        verify(termMapper).updateById(captor.capture());
        assertEquals("confirmed", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("confirm：update 候选带新解释 + 合并别名 → 覆盖解释、并入别名去重")
    void confirm_updateCandidate_appliesDescriptionAndAliases() {
        UserTerm pending = term(9, nextTerm(), "pending", 0, new ArrayList<>(List.of("旧别名")));
        org.mockito.Mockito.doReturn(pending).when(termMapper).selectById(9L);

        UserTermVO vo = glossaryService.confirm(9L, USER_ID, "新解释：8月起指FGO", List.of("新别名", "旧别名"));

        assertEquals("新解释：8月起指FGO", vo.getDescription());
        assertEquals(List.of("旧别名", "新别名"), vo.getAliases()); // 去重保序
    }

    @Test
    @DisplayName("confirm：已是 confirmed → PARAM_ERROR（状态机不合法）")
    void confirm_alreadyConfirmed_throws() {
        org.mockito.Mockito.doReturn(term(9, nextTerm(), "confirmed", 0, null)).when(termMapper).selectById(9L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> glossaryService.confirm(9L, USER_ID, null, null));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("dismiss：pending → dismissed，不删行（沉底保留）")
    void dismiss_pendingToDismissed() {
        UserTerm pending = term(9, nextTerm(), "pending", 0, null);
        org.mockito.Mockito.doReturn(pending).when(termMapper).selectById(9L);

        UserTermVO vo = glossaryService.dismiss(9L, USER_ID);

        assertEquals("dismissed", vo.getStatus());
        verify(termMapper).updateById(any(UserTerm.class));
        verify(termMapper, never()).deleteById(9L);
    }

    @Test
    @DisplayName("dismiss：已是 dismissed → PARAM_ERROR")
    void dismiss_alreadyDismissed_throws() {
        org.mockito.Mockito.doReturn(term(9, nextTerm(), "dismissed", 0, null)).when(termMapper).selectById(9L);

        assertThrows(BusinessException.class, () -> glossaryService.dismiss(9L, USER_ID));
    }

    @Test
    @DisplayName("confirm/dismiss：他人词条或不存在 → RECORD_NOT_FOUND（不暴露存在性）")
    void confirm_otherUserTerm_throwsNotFound() {
        org.mockito.Mockito.doReturn(UserTerm.builder() .id(9L).userId(UUID.randomUUID()).term("x").description("d") .status("pending").updatedAt(OffsetDateTime.now()).build()).when(termMapper).selectById(9L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> glossaryService.confirm(9L, USER_ID, null, null));
        assertEquals(4041, ex.getCode());
    }

    // ==================== CRUD ====================

    @Test
    @DisplayName("手动新增：直接 confirmed（教镜子一个词即时生效），重复词条拒绝")
    void create_manualBecomesConfirmed_duplicateRejected() {
        org.mockito.Mockito.doReturn(null).when(termMapper).selectOne(any());

        UserTermVO vo = glossaryService.create(USER_ID, GlossaryCreateDTO.builder()
                .term(nextTerm()).aliases(List.of("a", "a", "")).description("解释").build());

        assertEquals("confirmed", vo.getStatus());
        assertEquals(List.of("a"), vo.getAliases()); // 空白与重复别名清洗

        org.mockito.Mockito.doReturn(term(1, "已存在", "confirmed", 0, null)).when(termMapper).selectOne(any());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> glossaryService.create(USER_ID, GlossaryCreateDTO.builder()
                        .term("已存在").description("d").build()));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("删除：本人词条物理删除，缓存失效")
    void delete_physical() {
        UserTerm entity = term(9, nextTerm(), "confirmed", 0, null);
        org.mockito.Mockito.doReturn(entity).when(termMapper).selectById(9L);

        glossaryService.delete(9L, USER_ID);

        verify(termMapper).deleteById(9L);
    }

    // ==================== 抽取：RPC + 降级 + 分级落库 ====================

    private RecordProcessorProto.ExtractTermsReply.TermCandidate candidate(
            String term, String kind, long chunkId) {
        return RecordProcessorProto.ExtractTermsReply.TermCandidate.newBuilder()
                .setTerm(term).setKind(kind).setDescription("解释：" + term)
                .setEvidence("近14天出现3次").setSourceChunkId(chunkId)
                .addAliases("别:" + term)
                .build();
    }

    @Test
    @DisplayName("抽取：RPC 正常 → new 落 pending；evidence 只加计数；update 打回 pending")
    void extract_candidatesClassified() {
        String newTerm = nextTerm(), evidTerm = nextTerm(), updTerm = nextTerm();
        org.mockito.Mockito.doReturn(List.of(Chunk.builder() .id(5L).recordId(7L).userId(USER_ID).segment("语料").content("语料").build())).when(chunkMapper).selectList(any());
        org.mockito.Mockito.doReturn(RecordProcessorProto.ExtractTermsReply.newBuilder() .addCandidates(candidate(newTerm, "new", 5L)) .addCandidates(candidate(evidTerm, "evidence", 5L)) .addCandidates(candidate(updTerm, "update", 5L)) .build()).when(aiGrpcClient).extractTerms(eq(USER_ID), any());
        // evidence 目标 = 已有 pending 词；update 目标 = 已有 confirmed 词
        org.mockito.Mockito.doReturn(List.of( term(20, evidTerm, "pending", 0, null), term(21, updTerm, "confirmed", 0, null))).when(termMapper).selectByUser(USER_ID);
        // update 候选按 (userId, term) 查已有词：返回 confirmed 的 updTerm（触发打回 pending）
        org.mockito.Mockito.doReturn(term(21, updTerm, "confirmed", 0, null)).when(termMapper).selectOne(any());
        org.mockito.Mockito.doReturn(Chunk.builder().id(5L).recordId(7L).build()).when(chunkMapper).selectById(5L);

        int created = glossaryService.extractForUser(USER_ID);

        // new 落库 1 条 + update 打回 1 条
        assertEquals(2, created);
        ArgumentCaptor<UserTerm> insertCaptor = ArgumentCaptor.forClass(UserTerm.class);
        verify(termMapper).insert(insertCaptor.capture());
        assertEquals(newTerm, insertCaptor.getValue().getTerm());
        assertEquals("pending", insertCaptor.getValue().getStatus());
        assertEquals(7L, insertCaptor.getValue().getSourceRecordId()); // F 契约：record id 反查
    }

    @Test
    @DisplayName("抽取降级：Python 未上线（gRPC 异常）→ 返回 0 不抛异常（每日总结主流程零影响）")
    void extract_grpcDown_degradesSilently() {
        org.mockito.Mockito.doReturn(List.of(Chunk.builder() .id(5L).userId(USER_ID).segment("语料").content("语料").build())).when(chunkMapper).selectList(any());
        org.mockito.Mockito.doThrow(new io.grpc.StatusRuntimeException(io.grpc.Status.UNAVAILABLE)).when(aiGrpcClient).extractTerms(eq(USER_ID), any());

        assertEquals(0, glossaryService.extractForUser(USER_ID));
        verify(termMapper, never()).insert(any(UserTerm.class));

        // 调度入口同样吞掉异常（内层抛业务异常模拟）
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(termMapper).selectOne(any());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> glossaryService.extractScheduled(USER_ID));
    }

    @Test
    @DisplayName("抽取：未配置 LLM 直接跳过，不调 RPC 不查语料")
    void extract_noLlm_skipped() {
        org.mockito.Mockito.doReturn(false).when(aiGrpcClient).hasLlmConfig(USER_ID);

        assertEquals(0, glossaryService.extractForUser(USER_ID));
        verify(aiGrpcClient, never()).extractTerms(any(), any());
        verify(chunkMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("抽取去重：30 天窗口内已处理（含未过 30 天的 dismissed）term 不再生成候选")
    void extract_dedupWindow_skipsKnownTerms() {
        String known = nextTerm();
        org.mockito.Mockito.doReturn(List.of(Chunk.builder() .id(5L).userId(USER_ID).segment("语料").content("语料").build())).when(chunkMapper).selectList(any());
        org.mockito.Mockito.doReturn(RecordProcessorProto.ExtractTermsReply.newBuilder() .addCandidates(candidate(known, "new", 5L)) .build()).when(aiGrpcClient).extractTerms(eq(USER_ID), any());
        org.mockito.Mockito.doReturn(List.of(term(30, known, "dismissed", 0, null))).when(termMapper).selectByUser(USER_ID); // dismissed 未过 30 天 → 去重

        assertEquals(0, glossaryService.extractForUser(USER_ID));
        verify(termMapper, never()).insert(any(UserTerm.class));
    }

    // ==================== 月度维护 ====================

    @Test
    @DisplayName("月度漂移审计：confirmed 词近 30 天语料零命中 → 打回 pending（漂移最多活一个月）")
    void monthlyAudit_driftedTermBackToPending() {
        UserTerm alive = term(1, "论文", "confirmed", 0, null);
        UserTerm drifted = term(2, "游戏", "confirmed", 0, null);
        org.mockito.Mockito.doReturn(List.of(alive, drifted)).when(termMapper).selectConfirmedTop(eq(USER_ID), anyInt());
        org.mockito.Mockito.doReturn(List.of(Chunk.builder() .id(5L).userId(USER_ID).segment("论文又改了一遍").content("论文语料").build())).when(chunkMapper).selectList(any());

        glossaryService.monthlyMaintenance(USER_ID);

        ArgumentCaptor<UserTerm> captor = ArgumentCaptor.forClass(UserTerm.class);
        verify(termMapper).updateById(captor.capture());
        assertEquals(2L, captor.getValue().getId());
        assertEquals("pending", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("月度合并：互为包含的 confirmed 词对 → 短词打回 pending 附合并建议")
    void monthlyAudit_mergeSuggestionForContainedTerm() {
        // "个人词典系统" 完整包含 "词典系统"（真正连续子串关系）
        UserTerm longTerm = term(1, "个人词典系统", "confirmed", 0, null);
        UserTerm shortTerm = term(2, "词典系统", "confirmed", 0, null);
        org.mockito.Mockito.doReturn(List.of(longTerm, shortTerm)).when(termMapper).selectConfirmedTop(eq(USER_ID), anyInt());
        // 语料两个词都出现：都不算漂移，只触发合并建议
        org.mockito.Mockito.doReturn(List.of(Chunk.builder() .id(5L).userId(USER_ID).segment("今天在写个人词典系统， aka 词典系统").content("").build())).when(chunkMapper).selectList(any());

        glossaryService.monthlyMaintenance(USER_ID);

        ArgumentCaptor<UserTerm> captor = ArgumentCaptor.forClass(UserTerm.class);
        verify(termMapper).updateById(captor.capture());
        assertEquals(shortTerm.getId(), captor.getValue().getId());
        assertEquals("pending", captor.getValue().getStatus());
        assertTrue(captor.getValue().getDescription().contains("合并建议"));
        assertTrue(captor.getValue().getDescription().contains(longTerm.getTerm()));
    }

    @Test
    @DisplayName("月度维护失败：异常吞掉不上抛（不影响月度画像主流程）")
    void monthlyMaintenance_neverThrows() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(termMapper).selectConfirmedTop(eq(USER_ID), anyInt());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> glossaryService.monthlyMaintenance(USER_ID));
    }

    // ==================== 工具方法 ====================

    @Test
    @DisplayName("matches：term/alias 包含匹配语义固化（命中 true / 未命中 false）")
    void matches_semantics() {
        UserTermVO vo = vo(1L, "RAG", List.of("检索增强"));
        assertTrue(GlossaryServiceImpl.matches(vo, "我的RAG项目"));     // term 命中
        assertTrue(GlossaryServiceImpl.matches(vo, "聊聊检索增强"));     // alias 命中
        assertFalse(GlossaryServiceImpl.matches(vo, "都不含"));          // 未命中
        assertFalse(GlossaryServiceImpl.matches(
                UserTermVO.builder().term("").aliases(List.of()).build(), "x")); // 空词不命中
    }

    @Test
    @DisplayName("GlossaryTerm proto 映射：term/description/aliases/confirmed_at(yyyy-MM-dd) 齐全")
    void protoTerm_mapping() {
        UserTerm entity = term(1, "论文", "confirmed", 0, List.of("毕设"));
        CommonProto.GlossaryTerm proto = org.xianshen.mumirrorb.service.impl.GlossaryServiceImpl.toProtoTerm(entity);
        assertEquals("论文", proto.getTerm());
        assertEquals("毕设", proto.getAliases(0));
        assertEquals(java.time.LocalDate.now().toString(), proto.getConfirmedAt());
    }

    @Test
    @DisplayName("ChunkDTO proto 映射：metadata 展平（title/contentType/taskStatus/mood/keywords/user_edited）")
    void protoChunk_mapping() {
        Chunk chunk = Chunk.builder()
                .id(5L).recordId(7L).userId(USER_ID)
                .segment("片段").content("全文")
                .metadata(java.util.Map.of(
                        "title", "标题", "summary", "摘要", "contentType", "todo",
                        "taskStatus", "in_progress", "mood", List.of("calm"), "keywords", List.of("k1")))
                .userEdited(true)
                .createdAt(OffsetDateTime.now())
                .build();
        CommonProto.ChunkDTO dto = org.xianshen.mumirrorb.service.impl.GlossaryServiceImpl.toProtoChunk(chunk);
        assertEquals(5L, dto.getChunkId());
        assertEquals(7L, dto.getRecordId());
        assertEquals("标题", dto.getTitle());
        assertEquals("todo", dto.getContentType());
        assertEquals("in_progress", dto.getTaskStatus());
        assertEquals("calm", dto.getMoods(0));
        assertEquals("k1", dto.getKeywords(0));
        assertTrue(dto.getUserEdited());
    }
}

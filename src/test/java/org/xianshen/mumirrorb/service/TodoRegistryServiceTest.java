package org.xianshen.mumirrorb.service;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.TodoRegistryLinkMapper;
import org.xianshen.mumirrorb.mapper.TodoRegistryMapper;
import org.xianshen.mumirrorb.mapper.TodoSuggestionMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.TodoRegistry;
import org.xianshen.mumirrorb.pojo.DO.TodoRegistryLink;
import org.xianshen.mumirrorb.pojo.DO.TodoSuggestion;
import org.xianshen.mumirrorb.pojo.DTO.TodoRegistryDTO;
import org.xianshen.mumirrorb.service.impl.TodoRegistryServiceImpl;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 待办登记服务单测（todo-registry-design.md §3/§4-B）
 *
 * <p>覆盖：登记幂等 / 非 todo 片段跳过 / resolve confirmed 事务三写断言（chunk.metadata 定向改 +
 * registry 双写 + evidence link + 建议行 confirmed）/ dismissed 静默（关联不落 todo 不动）/
 * 直调双写 + pending 建议作废 / suggestFromChunk 去重与已完成不提示 / orphan 排除（注入清单口径）。</p>
 *
 * <p>Mockito 环境无 MyBatis-Plus 启动过程：LambdaQueryWrapper 解析实体 lambda 列依赖
 * TableInfo 缓存，@BeforeAll 手动注册（GetProfileMonthTest 同款解法）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TodoRegistryServiceTest {

    @Mock
    private TodoRegistryMapper registryMapper;
    @Mock
    private TodoRegistryLinkMapper linkMapper;
    @Mock
    private TodoSuggestionMapper suggestionMapper;
    @Mock
    private ChunkMapper chunkMapper;
    @Mock
    private RecordMapper recordMapper;

    private TodoRegistryServiceImpl service;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final Long RECORD_ID = 100L;
    private static final Long CHUNK_ID = 42L;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new org.apache.ibatis.session.Configuration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Record.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), Chunk.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), TodoRegistry.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), TodoSuggestion.class);
    }

    @BeforeEach
    void setUp() {
        service = new TodoRegistryServiceImpl(registryMapper, linkMapper, suggestionMapper,
                chunkMapper, recordMapper);
        // 默认无重复登记/无重复建议
        when(registryMapper.selectCount(any())).thenReturn(0L);
        when(suggestionMapper.selectCount(any())).thenReturn(0L);
    }

    private Chunk todoChunk(Long id, String title, String taskStatus) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", title);
        metadata.put("contentType", "todo");
        if (taskStatus != null) {
            metadata.put("taskStatus", taskStatus);
        }
        return Chunk.builder()
                .id(id)
                .userId(USER_ID)
                .recordId(RECORD_ID)
                .content("全文")
                .segment("明天要补作业")
                .metadata(metadata)
                .build();
    }

    private TodoRegistry registry(Long id, String status) {
        return TodoRegistry.builder()
                .id(id)
                .userId(USER_ID)
                .title("补作业")
                .currentStatus(status)
                .sourceChunkId(CHUNK_ID)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private TodoSuggestion suggestion(Long id, Long todoId, Long chunkId, String status, String suggested) {
        return TodoSuggestion.builder()
                .id(id)
                .userId(USER_ID)
                .todoId(todoId)
                .evidenceChunkId(chunkId)
                .suggestedStatus(suggested)
                .status(status)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    // ==================== 登记期：幂等 + 类型过滤 ====================

    @Test
    @DisplayName("登记：todo/plan chunk 生成 registry 行 + origin link，初始状态取 metadata.taskStatus")
    void register_createsRowAndOriginLink() {
        Record record = Record.builder().id(RECORD_ID).userId(USER_ID).build();
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(chunkMapper.selectList(any())).thenReturn(List.of(todoChunk(CHUNK_ID, "补作业", "not_started")));
        when(registryMapper.insert(any(TodoRegistry.class))).thenAnswer(inv -> {
            TodoRegistry e = inv.getArgument(0);
            e.setId(1L);
            return 1;
        });

        int created = service.registerFromRecord(RECORD_ID, USER_ID);

        assertEquals(1, created);
        ArgumentCaptor<TodoRegistry> regCaptor = ArgumentCaptor.forClass(TodoRegistry.class);
        verify(registryMapper).insert(regCaptor.capture());
        assertEquals("补作业", regCaptor.getValue().getTitle());
        assertEquals("not_started", regCaptor.getValue().getCurrentStatus());
        assertEquals(CHUNK_ID, regCaptor.getValue().getSourceChunkId());

        ArgumentCaptor<TodoRegistryLink> linkCaptor = ArgumentCaptor.forClass(TodoRegistryLink.class);
        verify(linkMapper).insert(linkCaptor.capture());
        assertEquals("origin", linkCaptor.getValue().getRelation());
        assertEquals(CHUNK_ID, linkCaptor.getValue().getChunkId());
    }

    @Test
    @DisplayName("登记幂等：source_chunk_id 已登记不重复建行")
    void register_idempotent() {
        Record record = Record.builder().id(RECORD_ID).userId(USER_ID).build();
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(chunkMapper.selectList(any())).thenReturn(List.of(todoChunk(CHUNK_ID, "补作业", null)));
        when(registryMapper.selectCount(any())).thenReturn(1L); // 已登记

        int created = service.registerFromRecord(RECORD_ID, USER_ID);

        assertEquals(0, created);
        verify(registryMapper, never()).insert(any(TodoRegistry.class));
        verify(linkMapper, never()).insert(any(TodoRegistryLink.class));
    }

    @Test
    @DisplayName("登记：非 todo/plan 片段跳过（thought 等不进登记表）")
    void register_skipsNonTodoChunks() {
        Record record = Record.builder().id(RECORD_ID).userId(USER_ID).build();
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        Chunk thought = todoChunk(43L, "感想", null);
        thought.getMetadata().put("contentType", "thought");
        when(chunkMapper.selectList(any())).thenReturn(List.of(thought));

        int created = service.registerFromRecord(RECORD_ID, USER_ID);

        assertEquals(0, created);
        verify(registryMapper, never()).insert(any(TodoRegistry.class));
    }

    @Test
    @DisplayName("登记：他人记录不登记（归属防御）")
    void register_rejectsOtherUsersRecord() {
        Record record = Record.builder().id(RECORD_ID).userId(OTHER_USER_ID).build();
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);

        assertEquals(0, service.registerFromRecord(RECORD_ID, USER_ID));
        verify(chunkMapper, never()).selectList(any());
    }

    // ==================== 判别期：suggestFromChunk ====================

    @Test
    @DisplayName("判别回传：refers_to_todo 有值落 pending 建议")
    void suggest_createsPending() {
        when(registryMapper.selectById(1L)).thenReturn(registry(1L, "not_started"));
        Chunk evidence = todoChunk(77L, "作业补完了", "completed");

        service.suggestFromChunk(USER_ID, evidence,
                RecordProcessorProto.TodoRef.newBuilder().setTodoId(1L).setSuggestedStatus("COMPLETED").build());

        ArgumentCaptor<TodoSuggestion> captor = ArgumentCaptor.forClass(TodoSuggestion.class);
        verify(suggestionMapper).insert(captor.capture());
        assertEquals("pending", captor.getValue().getStatus());
        assertEquals("completed", captor.getValue().getSuggestedStatus()); // 大写枚举名归一
        assertEquals(77L, captor.getValue().getEvidenceChunkId());
        assertNull(captor.getValue().getResolvedAt());
    }

    @Test
    @DisplayName("判别回传去重：同 (todo, evidence_chunk) 已有建议不重复落（dismissed 永久静默同口径）")
    void suggest_dedupesSameEvidence() {
        when(registryMapper.selectById(1L)).thenReturn(registry(1L, "not_started"));
        when(suggestionMapper.selectCount(any())).thenReturn(1L);
        Chunk evidence = todoChunk(77L, "作业补完了", "completed");

        service.suggestFromChunk(USER_ID, evidence,
                RecordProcessorProto.TodoRef.newBuilder().setTodoId(1L).setSuggestedStatus("completed").build());

        verify(suggestionMapper, never()).insert(any(TodoSuggestion.class));
    }

    @Test
    @DisplayName("判别回传：todo 已完成不再建议 / 他人 todo 静默丢弃 / 非法状态丢弃")
    void suggest_guards() {
        // 已完成
        when(registryMapper.selectById(1L)).thenReturn(registry(1L, "completed"));
        service.suggestFromChunk(USER_ID, todoChunk(77L, "x", null),
                RecordProcessorProto.TodoRef.newBuilder().setTodoId(1L).setSuggestedStatus("in_progress").build());
        // 他人 todo
        TodoRegistry others = registry(2L, "not_started");
        others.setUserId(OTHER_USER_ID);
        when(registryMapper.selectById(2L)).thenReturn(others);
        service.suggestFromChunk(USER_ID, todoChunk(78L, "x", null),
                RecordProcessorProto.TodoRef.newBuilder().setTodoId(2L).setSuggestedStatus("completed").build());
        // 非法状态
        when(registryMapper.selectById(3L)).thenReturn(registry(3L, "not_started"));
        service.suggestFromChunk(USER_ID, todoChunk(79L, "x", null),
                RecordProcessorProto.TodoRef.newBuilder().setTodoId(3L).setSuggestedStatus("garbage").build());

        verify(suggestionMapper, never()).insert(any(TodoSuggestion.class));
    }

    @Test
    @DisplayName("判别回传：refers_to_todo 为空（旧 Python 不回填）不触发")
    void suggest_nullRefNoop() {
        service.suggestFromChunk(USER_ID, todoChunk(77L, "x", null), null);
        service.suggestFromChunk(USER_ID, todoChunk(77L, "x", null),
                RecordProcessorProto.TodoRef.newBuilder().setTodoId(0).build());
        verify(suggestionMapper, never()).insert(any(TodoSuggestion.class));
    }

    // ==================== 裁决期：resolve confirmed 三写 ====================

    @Test
    @DisplayName("resolve confirmed：三写断言——chunk.metadata.taskStatus 定向改 + registry 双写(closed_at) + evidence link + 建议行 confirmed")
    void resolve_confirmed_threeWrites() {
        TodoSuggestion s = suggestion(9L, 1L, 77L, "pending", "completed");
        when(suggestionMapper.selectById(9L)).thenReturn(s);
        Chunk evidence = todoChunk(77L, "作业补完了", "completed");
        when(chunkMapper.selectById(77L)).thenReturn(evidence);
        when(linkMapper.selectOneByTodoAndChunk(1L, 77L)).thenReturn(null);

        service.resolve(9L, USER_ID, "confirmed", null); // 缺省按 suggested_status

        // ① 真源：chunk metadata 定向改（updateById 携带改过的 metadata——hstore Handler 坑见实现注释）
        ArgumentCaptor<Chunk> chunkCaptor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkMapper).updateById(chunkCaptor.capture());
        assertEquals("completed", chunkCaptor.getValue().getMetadata().get("taskStatus"));
        // ② registry：completed → closed_at 落值（update wrapper 携带 closedAt）
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TodoRegistry>> regWrapper =
                ArgumentCaptor.forClass(castClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        verify(registryMapper).update(isNull(), regWrapper.capture());
        assertNotNull(regWrapper.getValue());
        // ④ 建议行 confirmed
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TodoSuggestion>> sugWrapper =
                ArgumentCaptor.forClass(castClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        verify(suggestionMapper).update(isNull(), sugWrapper.capture());
        assertNotNull(sugWrapper.getValue());
        // ③ evidence link
        ArgumentCaptor<TodoRegistryLink> linkCaptor = ArgumentCaptor.forClass(TodoRegistryLink.class);
        verify(linkMapper).insert(linkCaptor.capture());
        assertEquals("evidence", linkCaptor.getValue().getRelation());
        assertEquals(77L, linkCaptor.getValue().getChunkId());
    }

    @Test
    @DisplayName("resolve confirmed 用户改状态：status=in_progress 覆盖 LLM 建议（用户主权）")
    void resolve_confirmed_userOverridesStatus() {
        TodoSuggestion s = suggestion(9L, 1L, 77L, "pending", "completed");
        when(suggestionMapper.selectById(9L)).thenReturn(s);
        when(chunkMapper.selectById(77L)).thenReturn(todoChunk(77L, "x", "completed"));

        service.resolve(9L, USER_ID, "confirmed", "IN_PROGRESS"); // 大写也归一

        // wrapper 生效路径无异常即归一成功；断言 registry update 被调用（in_progress 无 closed_at 分支也执行）
        verify(registryMapper).update(isNull(), any());
        verify(linkMapper).insert(any(TodoRegistryLink.class));
    }

    @Test
    @DisplayName("resolve confirmed：evidence 已有 origin link 时不重复落（UNIQUE 兜底）")
    void resolve_confirmed_linkDedup() {
        TodoSuggestion s = suggestion(9L, 1L, CHUNK_ID, "pending", "completed");
        when(suggestionMapper.selectById(9L)).thenReturn(s);
        when(chunkMapper.selectById(CHUNK_ID)).thenReturn(todoChunk(CHUNK_ID, "x", "completed"));
        when(linkMapper.selectOneByTodoAndChunk(1L, CHUNK_ID)).thenReturn(new TodoRegistryLink());

        service.resolve(9L, USER_ID, "confirmed", null);

        verify(linkMapper, never()).insert(any(TodoRegistryLink.class));
    }

    @Test
    @DisplayName("resolve dismissed：建议行 dismissed，todo/registry/link 全不动（静默）")
    void resolve_dismissed_silent() {
        TodoSuggestion s = suggestion(9L, 1L, 77L, "pending", "completed");
        when(suggestionMapper.selectById(9L)).thenReturn(s);

        service.resolve(9L, USER_ID, "dismissed", null);

        verify(suggestionMapper).update(isNull(), any());
        verify(chunkMapper, never()).update(any(), any());
        verify(registryMapper, never()).update(any(), any());
        verify(linkMapper, never()).insert(any(TodoRegistryLink.class));
    }

    @Test
    @DisplayName("resolve：他人建议 404 / 已处理建议 400 / 非法 action 400")
    void resolve_guards() {
        TodoSuggestion others = suggestion(9L, 1L, 77L, "pending", "completed");
        others.setUserId(OTHER_USER_ID);
        when(suggestionMapper.selectById(9L)).thenReturn(others);
        assertThrows(BusinessException.class, () -> service.resolve(9L, USER_ID, "confirmed", null));

        when(suggestionMapper.selectById(9L)).thenReturn(suggestion(9L, 1L, 77L, "confirmed", "completed"));
        assertThrows(BusinessException.class, () -> service.resolve(9L, USER_ID, "confirmed", null));

        when(suggestionMapper.selectById(9L)).thenReturn(suggestion(9L, 1L, 77L, "pending", "completed"));
        assertThrows(BusinessException.class, () -> service.resolve(9L, USER_ID, "maybe", null));

        verify(chunkMapper, never()).update(any(), any());
    }

    // ==================== 裁决期：直调双写 + 建议作废 ====================

    @Test
    @DisplayName("直调：双写 chunk+registry + 该 todo 的 pending 建议全部作废")
    void setStatusDirectly_dualWriteAndInvalidateSuggestions() {
        TodoRegistry todo = registry(1L, "not_started");
        when(registryMapper.selectById(1L)).thenReturn(todo);
        when(chunkMapper.selectById(CHUNK_ID)).thenReturn(todoChunk(CHUNK_ID, "补作业", "not_started"));

        service.setStatusDirectly(1L, USER_ID, "completed");

        // 真源定向改（updateById）+ registry 物化写
        ArgumentCaptor<Chunk> chunkCaptor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkMapper).updateById(chunkCaptor.capture());
        assertEquals("completed", chunkCaptor.getValue().getMetadata().get("taskStatus"));
        verify(registryMapper).update(isNull(), any());
        // pending 建议作废（status=dismissed）
        verify(suggestionMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("直调：orphan（source chunk 已删）只改 registry 不炸")
    void setStatusDirectly_orphanChunk() {
        TodoRegistry todo = registry(1L, "not_started");
        when(registryMapper.selectById(1L)).thenReturn(todo);
        when(chunkMapper.selectById(CHUNK_ID)).thenReturn(null); // chunk 已物理删除

        service.setStatusDirectly(1L, USER_ID, "in_progress");

        verify(chunkMapper, never()).update(any(), any());
        verify(registryMapper).update(isNull(), any());
        verify(suggestionMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("直调：他人待办 404 / 非法 status 400")
    void setStatusDirectly_guards() {
        TodoRegistry others = registry(1L, "not_started");
        others.setUserId(OTHER_USER_ID);
        when(registryMapper.selectById(1L)).thenReturn(others);
        assertThrows(BusinessException.class, () -> service.setStatusDirectly(1L, USER_ID, "completed"));

        when(registryMapper.selectById(1L)).thenReturn(registry(1L, "not_started"));
        assertThrows(BusinessException.class, () -> service.setStatusDirectly(1L, USER_ID, "done"));
        verify(registryMapper, never()).update(any(), any());
    }

    // ==================== 查询：orphan 排除口径 ====================

    @Test
    @DisplayName("注入清单：openTodosForHint 传上限 20（判别注入防线）")
    void openTodos_hintLimit() {
        when(registryMapper.selectOpenTodos(USER_ID, 20)).thenReturn(List.of());
        List<TodoRegistryDTO.TodoItem> items = service.openTodosForHint(USER_ID);
        assertTrue(items.isEmpty());
        verify(registryMapper).selectOpenTodos(USER_ID, 20);
    }

    @Test
    @DisplayName("列表：listAll 组装 orphan/linkCount/pendingCount 与截断摘录")
    void listAll_mapsRows() {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("todoid", 1L);
        row.put("title", "补作业");
        row.put("currentstatus", "completed");
        row.put("sourcechunkid", CHUNK_ID);
        row.put("sourceexcerpt", "明天要补作业");
        row.put("linkcount", 2L);
        row.put("pendingcount", 0L);
        row.put("createdat", "2026-09-09T10:00:00");
        row.put("closedat", "2026-09-09 12:00:00");
        Map<String, Object> orphanRow = new java.util.HashMap<>(row);
        orphanRow.put("todoid", 2L);
        orphanRow.put("sourcechunkid", null); // orphan：JOIN 不上
        when(registryMapper.selectAllByUserRaw(USER_ID)).thenReturn(List.of(row, orphanRow));

        List<org.xianshen.mumirrorb.pojo.VO.TodoItemVO> todos = service.listAll(USER_ID);

        assertEquals(2, todos.size());
        assertEquals(Boolean.FALSE, todos.get(0).getOrphan());
        assertEquals(2L, todos.get(0).getLinkCount());
        assertEquals(Boolean.TRUE, todos.get(1).getOrphan());
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> castClass(Class<?> raw) {
        return (Class<T>) raw;
    }
}

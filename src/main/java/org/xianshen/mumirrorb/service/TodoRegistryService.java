package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DTO.TodoResolutionDTO;
import org.xianshen.mumirrorb.pojo.VO.TodoChainVO;
import org.xianshen.mumirrorb.pojo.VO.TodoItemVO;
import org.xianshen.mumirrorb.pojo.VO.TodoSuggestionVO;

import java.util.List;
import java.util.UUID;

/** Todo state and explicit submissions; source records/chunks are read-only evidence. */
public interface TodoRegistryService {

    TodoItemVO createDirect(UUID userId, org.xianshen.mumirrorb.pojo.DTO.TodoCommandDTO command);

    TodoItemVO updateDirect(UUID userId, Long todoId, long expectedVersion,
                            org.xianshen.mumirrorb.pojo.DTO.TodoCommandDTO command);

    void deleteTodo(Long todoId, UUID userId, long expectedVersion);

    org.xianshen.mumirrorb.pojo.DO.TodoRegistry ownedActive(UUID userId, Long todoId);

    /** Includes soft-delete tombstones so confirmation can detect a changed target snapshot. */
    org.xianshen.mumirrorb.pojo.DO.TodoRegistry ownedSnapshot(UUID userId, Long todoId);

    int registerFromRecord(Long recordId, UUID userId);

    void suggestFromChunk(UUID userId, Chunk evidenceChunk, RecordProcessorProto.TodoRef todoRef);

    void resolve(Long suggestionId, UUID userId, String action, String status);

    List<org.xianshen.mumirrorb.pojo.DTO.TodoRegistryDTO.TodoItem> openTodosForHint(UUID userId);

    List<TodoSuggestionVO.SuggestionCard> pendingSuggestions(UUID userId);

    List<TodoItemVO> listAll(UUID userId);

    List<TodoChainVO> listOpenChains(UUID userId);

    void deleteTodo(Long todoId, UUID userId);

    void applyRecordResolutions(Long recordId, UUID userId, List<TodoResolutionDTO> resolutions);

    List<TodoSuggestionVO.RecordSuggestion> listRecordSuggestions(Long recordId, UUID userId);
}

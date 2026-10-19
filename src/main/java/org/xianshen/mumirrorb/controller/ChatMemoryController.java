package org.xianshen.mumirrorb.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xianshen.mumirrorb.pojo.DO.ChatMemory;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.ChatMemoryVO;
import org.xianshen.mumirrorb.service.ChatMemoryService;
import java.util.List;
import java.util.UUID;

@RestController @RequiredArgsConstructor
@RequestMapping("/chat-memories")
public class ChatMemoryController {
    private final ChatMemoryService memories;
    private final ObjectMapper json;

    @GetMapping
    public R<List<ChatMemoryVO>> list() {
        UUID userId = currentUser();
        return R.ok("查询成功", memories.listActive(userId).stream().map(this::toVO).toList());
    }

    @GetMapping("/{id}")
    public R<ChatMemoryVO> get(@PathVariable UUID id) {
        return R.ok("查询成功", toVO(memories.getActive(currentUser(), id)));
    }

    private UUID currentUser() {
        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return UUID.fromString(userId);
    }

    private ChatMemoryVO toVO(ChatMemory memory) {
        List<Long> sourceIds;
        try { sourceIds = json.readValue(memory.getSourceMessageIds(), new TypeReference<>() { }); }
        catch (Exception e) { sourceIds = List.of(); }
        return ChatMemoryVO.builder().id(memory.getId()).content(memory.getContent()).kind(memory.getKind())
                .sourceDate(memory.getSourceDate()).version(memory.getVersion()).sourceMessageIds(sourceIds)
                .sourceSessionId(memory.getSourceSessionId()).eventDate(memory.getEventDate()).expiresAt(memory.getExpiresAt()).build();
    }
}

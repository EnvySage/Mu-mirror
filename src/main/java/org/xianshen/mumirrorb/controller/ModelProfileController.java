package org.xianshen.mumirrorb.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.DTO.ModelConnectionTestDTO;
import org.xianshen.mumirrorb.pojo.DTO.ModelProfileDTO;
import org.xianshen.mumirrorb.pojo.VO.ModelProfileVO;
import org.xianshen.mumirrorb.service.ModelProfileService;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class ModelProfileController {
    private final ModelProfileService service;

    private UUID userId() {
        return UUID.fromString((String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @GetMapping("/model-profiles")
    public R<List<ModelProfileVO>> list() { return R.ok(service.list(userId())); }

    @PostMapping("/model-profiles")
    public R<ModelProfileVO> create(@Valid @RequestBody ModelProfileDTO dto) { return R.ok(service.create(userId(), dto)); }

    @PutMapping("/model-profiles/{id}")
    public R<ModelProfileVO> update(@PathVariable UUID id, @Valid @RequestBody ModelProfileDTO dto) {
        return R.ok(service.update(userId(), id, dto));
    }

    @DeleteMapping("/model-profiles/{id}")
    public R<Void> delete(@PathVariable UUID id) { service.delete(userId(), id); return R.ok(); }

    @PostMapping("/model-profiles/{id}/activate")
    public R<ModelProfileVO> activate(@PathVariable UUID id) { return R.ok(service.activate(userId(), id)); }

    @PostMapping("/test-ai/draft")
    public R<String> test(@Valid @RequestBody ModelConnectionTestDTO dto) { return R.ok(service.testDraft(userId(), dto)); }
}

package org.xianshen.mumirrorb.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.action.ActionConflictException;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.DTO.*;
import org.xianshen.mumirrorb.pojo.VO.ActionProposalVO;
import org.xianshen.mumirrorb.service.ActionProposalService;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/assistant/actions")
@RequiredArgsConstructor
public class AssistantActionController {
    private final ActionProposalService service;

    @PostMapping("/prepare")
    public R<ActionProposalVO> prepare(@RequestBody ActionPrepareDTO body) { return R.ok(service.prepare(owner(), body)); }

    @PatchMapping("/{id}")
    public R<ActionProposalVO> edit(@PathVariable UUID id, @RequestBody ActionEditDTO body) { return R.ok(service.edit(owner(), id, body)); }

    @PostMapping("/{id}/confirm")
    public R<ActionProposalVO> confirm(@PathVariable UUID id, @RequestBody ActionConfirmDTO body) { return R.ok(service.confirm(owner(), id, body)); }

    public record CancelBody(Integer expectedVersion) {}

    @PostMapping("/{id}/cancel")
    public R<ActionProposalVO> cancel(@PathVariable UUID id, @RequestBody CancelBody body) {
        UUID userId = owner();
        if (body == null || body.expectedVersion() == null) throw new BusinessException(400, "缺少 expectedVersion");
        return R.ok(service.cancel(userId, id, body.expectedVersion()));
    }

    @GetMapping("/{id}")
    public R<ActionProposalVO> get(@PathVariable UUID id) { return R.ok(service.get(owner(), id)); }

    @GetMapping
    public R<List<ActionProposalVO>> list(@RequestParam UUID sessionId) { return R.ok(service.listForSession(owner(), sessionId)); }

    @GetMapping("/records")
    public R<List<ActionProposalVO>> records() { return R.ok(service.listRecordWorkflows(owner())); }

    @ExceptionHandler(ActionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public R<ActionProposalVO> conflict(ActionConflictException error) {
        R<ActionProposalVO> response = R.fail(409, error.getMessage());
        response.setData(error.getLatest());
        return response;
    }

    private UUID owner() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
            throw new BusinessException(401, "请先登录");
        try { return UUID.fromString(auth.getName()); }
        catch (IllegalArgumentException e) { throw new BusinessException(401, "登录身份无效"); }
    }
}

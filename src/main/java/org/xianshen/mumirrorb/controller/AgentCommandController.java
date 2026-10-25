package org.xianshen.mumirrorb.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xianshen.mumirrorb.agent.AgentCommandException;
import org.xianshen.mumirrorb.agent.AgentCommandResponse;
import org.xianshen.mumirrorb.agent.AgentCommandService;
import org.xianshen.mumirrorb.agent.AgentCreateRecordRequest;
import org.xianshen.mumirrorb.agent.VerifiedGrant;

import java.util.Optional;
import java.util.UUID;

/** Named internal commands; deliberately returns plain JSON rather than the public R envelope. */
@RestController
@RequestMapping("/internal/agent/commands")
@RequiredArgsConstructor
public class AgentCommandController {

    private final AgentCommandService commandService;

    @PostMapping("/create-record")
    public AgentCommandResponse createRecord(@RequestBody AgentCreateRecordRequest request) {
        return commandService.createRecord(currentGrant(), request.runId(), request.callId(), request.content());
    }

    @GetMapping("/receipts/{callId}")
    public AgentCommandResponse getReceipt(@PathVariable UUID callId) {
        Optional<AgentCommandResponse> receipt = commandService.getReceipt(currentGrant(), callId);
        return receipt.orElseThrow(() -> new AgentCommandException(HttpStatus.NOT_FOUND, "RECEIPT_NOT_FOUND"));
    }

    private VerifiedGrant currentGrant() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof VerifiedGrant grant)) {
            throw AgentCommandException.unauthorized();
        }
        return grant;
    }
}

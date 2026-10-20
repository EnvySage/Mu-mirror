package org.xianshen.mumirrorb.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xianshen.mumirrorb.agent.AgentRunService;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.RunStatusVO;

import java.util.UUID;

/** Owner-authenticated read endpoint for reconnect and idempotent request recovery. */
@RestController
@RequestMapping("/mirror/runs")
@RequiredArgsConstructor
public class AgentRunController {

    private final AgentRunService agentRunService;

    @GetMapping("/{runId}")
    public R<RunStatusVO> getRun(@PathVariable UUID runId) {
        UUID userId = UUID.fromString((String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal());
        return R.ok("查询成功", agentRunService.getRun(runId, userId));
    }
}

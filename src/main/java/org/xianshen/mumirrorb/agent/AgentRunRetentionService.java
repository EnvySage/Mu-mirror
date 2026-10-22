package org.xianshen.mumirrorb.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.mapper.AgentRunMapper;

import java.time.OffsetDateTime;

/** Transactional retention cleanup for finished runs and their receipts. */
@Service
@RequiredArgsConstructor
public class AgentRunRetentionService {

    private final AgentRunMapper runMapper;

    @Transactional
    public int deleteExpired(OffsetDateTime cutoff) {
        runMapper.deleteExpiredCommandReceipts(cutoff);
        return runMapper.deleteExpiredRuns(cutoff);
    }
}

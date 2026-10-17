package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;

import java.util.Set;
import java.util.UUID;

/** Propagates source changes through the snapshots that actually depend on them. */
@Service
@RequiredArgsConstructor
public class SnapshotLineageService {

    private static final Set<String> SOURCE_KINDS = Set.of("record", "chunk");

    private final ProfileSnapshotMapper profileSnapshotMapper;

    @Transactional
    public void invalidateSource(UUID userId, String inputKind, Long inputId, String reason) {
        if (userId == null || inputId == null || inputKind == null || !SOURCE_KINDS.contains(inputKind)) {
            throw new IllegalArgumentException("A user and a record or chunk source are required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An invalidation reason is required");
        }

        profileSnapshotMapper.invalidateTrackedFromSource(userId, inputKind, inputId, reason);
        profileSnapshotMapper.invalidateUntrackedCurrentForUser(userId);
    }
}

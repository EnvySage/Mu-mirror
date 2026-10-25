package org.xianshen.mumirrorb.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.mapper.ProfileSnapshotMapper;
import org.xianshen.mumirrorb.mapper.SnapshotInputMapper;
import org.xianshen.mumirrorb.pojo.DO.ProfileSnapshot;
import org.xianshen.mumirrorb.pojo.DO.SnapshotInput;

import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;
import java.util.UUID;

/** Propagates source changes through the snapshots that actually depend on them. */
@Service
public class SnapshotLineageService {

    private static final Set<String> SOURCE_KINDS = Set.of("record", "chunk");

    private final ProfileSnapshotMapper profileSnapshotMapper;
    private final SnapshotInputMapper snapshotInputMapper;

    /** Compatibility constructor for existing record/chunk lineage callers. */
    public SnapshotLineageService(ProfileSnapshotMapper profileSnapshotMapper) {
        this(profileSnapshotMapper, null);
    }

    @Autowired
    public SnapshotLineageService(ProfileSnapshotMapper profileSnapshotMapper,
                                  SnapshotInputMapper snapshotInputMapper) {
        this.profileSnapshotMapper = profileSnapshotMapper;
        this.snapshotInputMapper = snapshotInputMapper;
    }

    /** Hold the user's row lock for the surrounding transaction, shared with all source changes. */
    @Transactional
    public void lockUserLineage(UUID userId) {
        if (userId == null || profileSnapshotMapper.lockUserLineage(userId) == null) {
            throw new IllegalArgumentException("A persisted user is required for mirror lineage operations");
        }
    }

    @Transactional
    public void invalidateSource(UUID userId, String inputKind, Long inputId, String reason) {
        if (userId == null || inputId == null || inputKind == null || !SOURCE_KINDS.contains(inputKind)) {
            throw new IllegalArgumentException("A user and a record or chunk source are required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An invalidation reason is required");
        }

        lockUserLineage(userId);
        profileSnapshotMapper.invalidateTrackedFromSource(userId, inputKind, inputId, reason);
        profileSnapshotMapper.invalidateUntrackedForUser(userId);
    }

    /** Invalidate snapshots derived from forgotten chat messages and conservatively retire legacy snapshots. */
    @Transactional
    public void invalidateForForgottenChatSources(UUID userId, List<Long> sourceMessageIds) {
        if (userId == null || sourceMessageIds == null || sourceMessageIds.isEmpty()
                || sourceMessageIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("A user and persisted chat message IDs are required");
        }
        lockUserLineage(userId);
        profileSnapshotMapper.invalidateTrackedFromChatSources(
                userId, sourceMessageIds.stream().distinct().toList(), "forgotten_chat_source");
        profileSnapshotMapper.invalidateUnknownChatLineageForUser(userId);
    }

    /** Invalidate only snapshots that depend on edited memory sources; the messages remain eligible. */
    @Transactional
    public void invalidateForChangedChatSources(UUID userId, List<Long> sourceMessageIds) {
        if (userId == null || sourceMessageIds == null || sourceMessageIds.isEmpty()
                || sourceMessageIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("A user and persisted chat message IDs are required");
        }
        lockUserLineage(userId);
        profileSnapshotMapper.invalidateTrackedFromChatSources(
                userId, sourceMessageIds.stream().distinct().toList(), "changed_chat_memory");
    }

    /** Persist all authorized chat messages that directly contributed to this newly-created snapshot. */
    @Transactional
    public void recordChatMessageInputs(Long snapshotId, List<Long> sourceMessageIds) {
        if (snapshotId == null || snapshotId <= 0 || sourceMessageIds == null || sourceMessageIds.isEmpty()
                || sourceMessageIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("A snapshot and persisted chat message IDs are required");
        }
        ProfileSnapshot snapshot = profileSnapshotMapper.selectById(snapshotId);
        if (snapshot == null || snapshot.getUserId() == null
                || !Boolean.TRUE.equals(snapshot.getChatLineageKnown())) {
            throw new IllegalArgumentException("A persisted snapshot with known chat lineage is required");
        }
        lockUserLineage(snapshot.getUserId());
        List<Long> distinctSourceIds = sourceMessageIds.stream().distinct().toList();
        if (profileSnapshotMapper.hasForgottenChatSources(snapshot.getUserId(), distinctSourceIds)) {
            throw new IllegalStateException("A forgotten chat source cannot be saved into a profile snapshot");
        }
        List<SnapshotInput> inputs = distinctSourceIds.stream()
                .map(messageId -> SnapshotInput.builder()
                        .snapshotId(snapshotId)
                        .inputKind("chat_message")
                        .inputId(messageId)
                        .inputRole("memory_source")
                        .build())
                .collect(Collectors.toList());
        snapshotInputMapper.insertAll(snapshotId, inputs);
        profileSnapshotMapper.markChatLineageKnown(snapshotId);
    }
}

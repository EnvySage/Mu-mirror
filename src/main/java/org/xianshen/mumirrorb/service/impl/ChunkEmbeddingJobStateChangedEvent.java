package org.xianshen.mumirrorb.service.impl;

import java.util.UUID;

record ChunkEmbeddingJobStateChangedEvent(UUID userId, Long chunkId, String state, String reason) {}

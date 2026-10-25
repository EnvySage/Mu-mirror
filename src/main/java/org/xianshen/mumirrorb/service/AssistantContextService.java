package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import java.util.List;
import java.util.UUID;

/** The only server-side constructor for trusted personal memory context. */
public interface AssistantContextService {
    CommonProto.AssistantContext build(UUID userId, UUID sessionId, String question);
    BuiltContext buildForProfile(UUID userId, String question);

    record BuiltContext(CommonProto.AssistantContext context, List<Long> sourceMessageIds) { }
}

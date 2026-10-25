package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.DTO.RecordDraftDTO;
import java.util.UUID;

/** Prepares editable record proposals without creating records or changing authorization. */
public interface RecordDraftService {
    RecordDraftDTO prepare(UUID userId, RecordDraftDTO input);
}

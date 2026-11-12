package org.xianshen.mumirrorb.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owner-only extracted text review; vectors and internal key chunks stay private. */
@Service
public class VaultReviewService {
    private final JdbcTemplate db;
    public VaultReviewService(JdbcTemplate db) { this.db = db; }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> chunks(UUID user, long item) {
        var states = db.queryForList("""
            SELECT digest_status FROM vault_items WHERE id=? AND user_id=? AND deleted_at IS NULL
            """, String.class, item, user);
        if (states.isEmpty()) throw new BusinessException(404, "资产不存在");
        if (!List.of("extracted", "confirmed").contains(states.get(0)))
            throw new BusinessException(409, "文件片段尚未准备好");
        return db.queryForList("""
            SELECT id,segment FROM chunks WHERE vault_item_id=? AND user_id=?
            AND coalesce(metadata->>'keyChunk','false')<>'true'
            AND nullif(btrim(segment),'') IS NOT NULL ORDER BY id
            """, item, user);
    }
}

package org.xianshen.mumirrorb.pojo.VO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/** Keyset page of the durable mirror archive. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "镜子归档分页")
public class SnapshotPageVO {

    private List<SnapshotListVO> items;

    @Schema(description = "下一页游标创建时间（完整 ISO-8601 时间与时区偏移）")
    private OffsetDateTime nextCreatedAt;

    @Schema(description = "下一页游标快照 ID")
    private Long nextId;

    @Schema(description = "是否还有下一页")
    private boolean hasMore;
}

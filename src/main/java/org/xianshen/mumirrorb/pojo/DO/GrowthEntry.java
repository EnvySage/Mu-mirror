package org.xianshen.mumirrorb.pojo.DO;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.time.OffsetDateTime; import java.util.UUID;
@Data @TableName("growth_entries") public class GrowthEntry { @TableId(type=IdType.AUTO) private Long id; private UUID userId; private String dimensionId; private Integer points; private String reason; private Long definitionVersion; private String sourceType; private Long sourceRecordId; private UUID actionId; private Long correctionOf; private Long version; private OffsetDateTime createdAt; }

package org.xianshen.mumirrorb.pojo.DO;
import com.baomidou.mybatisplus.annotation.TableName; import lombok.Data; import java.time.OffsetDateTime; import java.util.UUID;
@Data @TableName("growth_axes") public class GrowthAxis { private UUID userId; private String dimensionId; private String name; private String description; private Long definitionVersion; private OffsetDateTime updatedAt; public String getId(){return dimensionId;} }

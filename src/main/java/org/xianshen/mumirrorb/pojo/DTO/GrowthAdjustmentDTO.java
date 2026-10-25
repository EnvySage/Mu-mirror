package org.xianshen.mumirrorb.pojo.DTO;
import jakarta.validation.constraints.*; import lombok.Data;
@Data public class GrowthAdjustmentDTO { @NotBlank private String dimensionId; @NotNull @Min(0) @Max(100) private Integer points; @NotBlank @Size(max=500) private String reason; private Long entryId; private Long expectedVersion; private java.util.UUID requestId; }

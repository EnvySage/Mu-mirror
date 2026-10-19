package org.xianshen.mumirrorb.service;
import org.xianshen.mumirrorb.config.GrowthProperties;
import org.xianshen.mumirrorb.pojo.DO.GrowthAxis; import org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO; import org.xianshen.mumirrorb.pojo.VO.GrowthOverviewVO; import java.util.*;
public interface GrowthService { List<GrowthAxis> axes(UUID userId); GrowthOverviewVO overview(UUID userId); GrowthOverviewVO adjustManual(UUID userId,GrowthAdjustmentDTO dto); void applyConfirmedRecord(UUID userId,Long recordId,UUID actionId,List<GrowthAdjustmentDTO> suggestions,long axesVersion); List<?> entries(UUID userId); long axesVersion(UUID userId); List<GrowthAxis> updateAxes(UUID userId,long expectedVersion,List<GrowthProperties.Axis> axes); }

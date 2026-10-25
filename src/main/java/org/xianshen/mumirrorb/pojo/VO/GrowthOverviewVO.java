package org.xianshen.mumirrorb.pojo.VO;
import lombok.Data; import java.util.*;
@Data public class GrowthOverviewVO { private long totalPoints; private Stage stage; private long axesVersion; private List<Integer> thresholds=new ArrayList<>(); private Config config; private List<Axis> axes=new ArrayList<>(); private List<Entry> recentEntries=new ArrayList<>();
 @Data public static class Config { private int aiSuggestionMin,aiSuggestionMax,reviewMin,reviewMax,manualMin,manualMax; private List<Integer> thresholds; }
 @Data public static class Stage { private int index; private String label; private int threshold; }
 @Data public static class Axis { private String id,name,description; private long definitionVersion,totalPoints; private int stageIndex; }
 @Data public static class Entry { private Long id; private String dimensionId,axisName,axisDescription,reason,sourceType; private Integer points; private Long sourceRecordId,version,definitionVersion; private String createdAt; }
}

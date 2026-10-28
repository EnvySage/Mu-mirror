package org.xianshen.mumirrorb.pojo.DTO;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Editable record candidate stored in an ActionProposal payload; never a Record entity. */
@Data
public class RecordDraftDTO {
    private Long sourceRecordId;
    private Long sourceRecordVersion;
    private String rawContent;
    private String finalContent;
    private String mode;
    private boolean privateInput;
    private String preparationStatus;
    private String preparationError;
    private int axesVersion;
    private List<Object> growthSuggestions = new ArrayList<>();
    private List<java.util.Map<String, Object>> todoSuggestions = new ArrayList<>();
    private List<Segment> segments = new ArrayList<>();

    @Data
    public static class Segment {
        private UUID draftId;
        private String text;
        private String title;
        private String summary;
        private String contentType;
        private List<String> moods = new ArrayList<>();
        private List<String> keywords = new ArrayList<>();
        private List<String> tags = new ArrayList<>();
        private String taskStatus;
        private boolean memoryEnabled;
        private boolean todoEnabled;
    }
}

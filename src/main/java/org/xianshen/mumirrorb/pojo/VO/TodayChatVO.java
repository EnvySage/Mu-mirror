package org.xianshen.mumirrorb.pojo.VO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TodayChatVO {
    private ChatSessionVO session;
    @Builder.Default
    private List<EndedDaySummaryVO> endedDaySummaries = List.of();

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EndedDaySummaryVO {
        private LocalDate sourceDate;
        private UUID sourceSessionId;
        private String summary;
        private String sourceHash;
        private String state;
        @Builder.Default
        private List<ActionProposalVO> actions = List.of();
    }
}

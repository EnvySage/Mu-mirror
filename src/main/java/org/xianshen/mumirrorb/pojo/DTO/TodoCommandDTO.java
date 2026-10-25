package org.xianshen.mumirrorb.pojo.DTO;

import lombok.Data;
import java.time.OffsetDateTime;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import java.util.Set;

/** Explicit todo submission. No identity or confirmation fields are accepted from a model. */
@Data
public class TodoCommandDTO {
    private String title;
    private String notes;
    private OffsetDateTime dueAt;
    private String currentStatus;
    private Long expectedTargetVersion;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean notesProvided;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean dueAtProvided;

    public void setNotes(String notes) { this.notes = notes; notesProvided = true; }
    public void setDueAt(OffsetDateTime dueAt) { this.dueAt = dueAt; dueAtProvided = true; }

    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new BusinessException(ResultCode.PARAM_ERROR, "不允许的待办参数: " + field);
    }

    public void validate() {
        if (title == null || title.isBlank() || title.trim().length() > 200)
            throw new BusinessException(ResultCode.PARAM_ERROR, "待办标题须为 1..200 字");
        if (currentStatus == null || !Set.of("not_started", "in_progress", "completed").contains(currentStatus))
            throw new BusinessException(ResultCode.PARAM_ERROR, "待办状态非法");
        title = title.trim();
    }
}

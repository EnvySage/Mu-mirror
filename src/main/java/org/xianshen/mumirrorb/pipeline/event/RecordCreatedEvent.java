package org.xianshen.mumirrorb.pipeline.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 记录创建事件
 *
 * 记录入库后（status=processing）发布，触发后台管道处理
 */
@Getter
public class RecordCreatedEvent extends ApplicationEvent {

    /**
     * 记录ID
     */
    private final Long recordId;

    /**
     * 用户ID
     */
    private final String userId;

    public RecordCreatedEvent(Object source, Long recordId, String userId) {
        super(source);
        this.recordId = recordId;
        this.userId = userId;
    }
}

package org.xianshen.mumirrorb.pipeline.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

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
    private final UUID userId;

    public RecordCreatedEvent(Object source, Long recordId, UUID userId) {
        super(source);
        this.recordId = recordId;
        this.userId = userId;
    }
}

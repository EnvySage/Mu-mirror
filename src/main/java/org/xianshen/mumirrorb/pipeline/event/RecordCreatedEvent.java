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

    /** 防止用户撤回/删除后的旧异步任务回写。 */
    private final int processingVersion;

    public RecordCreatedEvent(Object source, Long recordId, UUID userId, int processingVersion) {
        super(source);
        this.recordId = recordId;
        this.userId = userId;
        this.processingVersion = processingVersion;
    }
}

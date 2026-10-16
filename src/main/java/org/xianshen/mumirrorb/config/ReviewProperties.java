package org.xianshen.mumirrorb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 旧审核模式配置兼容项（保留读取旧 application.yml；auto 审核已永久停用）
 *
 * <p>配置项已不再控制任何运行时行为；SettingsService 强制 manual，写入口拒绝 auto。
 * 保留此类型仅避免部署配置中的旧字段造成误解或绑定问题。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "review")
public class ReviewProperties {

    /** 仅为旧配置兼容保留，当前忽略。 */
    private boolean autoEnabled = false;
}

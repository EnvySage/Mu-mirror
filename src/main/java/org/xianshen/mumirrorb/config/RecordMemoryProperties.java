package org.xianshen.mumirrorb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "record-memory")
public class RecordMemoryProperties {
    private boolean selectiveIndexEnabled;

    public int indexPolicyVersion() { return selectiveIndexEnabled ? 1 : 0; }
}

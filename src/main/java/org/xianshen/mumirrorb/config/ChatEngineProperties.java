package org.xianshen.mumirrorb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/** Request-start engine selection; graph_v2 remains opt-in per user. */
@Data
@Component
@ConfigurationProperties(prefix = "chat")
public class ChatEngineProperties {

    private String engine = "legacy";
    private List<String> graphEnabledUserIds = new ArrayList<>();
    private double shadowSampleRate = 0.0;
    private boolean createRecordEnabled = false;

    public boolean graphEnabled(UUID userId) {
        return userId != null && "graph_v2".equalsIgnoreCase(engine)
                && graphEnabledUserIds.stream().anyMatch(value -> userId.toString().equalsIgnoreCase(value.trim()));
    }

    public boolean shadowMode() {
        return "shadow".equalsIgnoreCase(engine);
    }

    public boolean sampleShadowRun() {
        double rate = Math.max(0.0, Math.min(1.0, shadowSampleRate));
        return shadowMode() && rate > 0.0 && ThreadLocalRandom.current().nextDouble() < rate;
    }
}

package org.xianshen.mumirrorb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Independent Python Agent endpoint and per-run limits. */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private String grpcAddress = "localhost:10004";
    private String internalToken = "";
    private String commandHmacKey = "";
    private long totalBudgetMs = 480_000;
    private long loopBudgetMs = 180_000;
    private int maxPlanRounds = 4;
    private int leaseSeconds = 30;
    private int heartbeatSeconds = 10;
}

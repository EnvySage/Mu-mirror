package org.xianshen.mumirrorb.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatEnginePropertiesTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void wildcardEnablesEveryUser() {
        ChatEngineProperties properties = new ChatEngineProperties();
        properties.setEngine("graph_v2");
        properties.setGraphEnabledUserIds(List.of("*"));

        assertTrue(properties.graphEnabled(UUID.randomUUID()));
    }

    @Test
    void whitelistOnlyEnablesListedUsers() {
        ChatEngineProperties properties = new ChatEngineProperties();
        properties.setEngine("graph_v2");
        properties.setGraphEnabledUserIds(List.of(" " + userId + " "));

        assertTrue(properties.graphEnabled(userId));
        assertFalse(properties.graphEnabled(UUID.randomUUID()));
    }

    @Test
    void legacyEngineIgnoresWildcard() {
        ChatEngineProperties properties = new ChatEngineProperties();
        properties.setEngine("legacy");
        properties.setGraphEnabledUserIds(List.of("*"));

        assertFalse(properties.graphEnabled(userId));
    }
}

package org.xianshen.mumirrorb.achievement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.tarot.TarotCatalog;
import static org.junit.jupiter.api.Assertions.*;

class AchievementCatalogTest {
    AchievementCatalog catalog() throws Exception {
        var json = new ObjectMapper();
        var db=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://127.0.0.1/unused","unused","unused"));
        return new AchievementCatalog(json, new AchievementMetrics(db, new TarotCatalog(json)));
    }

    @Test void completeCatalogContainsTwelveSetsOfEightStableCodes() throws Exception {
        var catalog = catalog();
        assertEquals(96, catalog.definitions().size());
        assertEquals(96, catalog.definitions().stream().map(item -> item.path("code").asText()).distinct().count());
    }

    @Test void platingEligibilityIsPublicWhileEveryChallengeStaysServerSide() throws Exception {
        var catalog = catalog();
        long eligible = catalog.definitions().stream().filter(item -> catalog.display(item, true).path("plating").asBoolean()).count();
        assertEquals(24, eligible);
        for (var definition : catalog.definitions()) {
            var item = catalog.display(definition, false);
            assertFalse(item.has("rule"));
            assertFalse(item.has("platingRule"));
            assertFalse(item.has("platingProgress"));
            if (definition.path("hidden").asBoolean()) {
                assertFalse(item.has("atlas"));
                assertFalse(item.has("atlasIndex"));
                assertTrue(item.path("image").isNull());
            }
        }
    }
}

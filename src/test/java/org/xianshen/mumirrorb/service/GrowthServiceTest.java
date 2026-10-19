package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.xianshen.mumirrorb.mapper.GrowthAxisMapper;
import org.xianshen.mumirrorb.mapper.GrowthEntryMapper;
import org.xianshen.mumirrorb.service.impl.GrowthServiceImpl;
import org.xianshen.mumirrorb.config.GrowthProperties;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;

class GrowthServiceTest {
    @Test void initializesExactlyFiveStableDefaultAxes() {
        var axes = Mockito.mock(GrowthAxisMapper.class);
        var entries = Mockito.mock(GrowthEntryMapper.class);
        var service = new GrowthServiceImpl(axes, entries, new GrowthProperties());
        var result = service.axes(UUID.randomUUID());
        assertEquals(5, result.size());
        assertEquals(5, result.stream().map(a -> a.getId()).distinct().count());
    }
}

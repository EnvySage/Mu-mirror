package org.xianshen.mumirrorb.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.xianshen.mumirrorb.mapper.GrowthAxisMapper;
import org.xianshen.mumirrorb.mapper.GrowthEntryMapper;
import org.xianshen.mumirrorb.service.impl.GrowthServiceImpl;
import org.xianshen.mumirrorb.config.GrowthProperties;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.xianshen.mumirrorb.pojo.DO.GrowthAxis;

class GrowthServiceTest {
    @Test void initializesExactlyFiveStableDefaultAxes() {
        var axes = Mockito.mock(GrowthAxisMapper.class);
        var entries = Mockito.mock(GrowthEntryMapper.class);
        var service = new GrowthServiceImpl(axes, entries, new GrowthProperties());
        var result = service.axes(UUID.randomUUID());
        assertEquals(5, result.size());
        assertEquals(5, result.stream().map(a -> a.getId()).distinct().count());
    }

    @Test void aggregateVersionAdvancesWhenDifferentAxesAreRenamed() {
        var axesMapper = Mockito.mock(GrowthAxisMapper.class);
        var entries = Mockito.mock(GrowthEntryMapper.class);
        var userId = UUID.randomUUID();
        var current = new ArrayList<GrowthAxis>();
        var defaults = new GrowthProperties().getDefaults();
        for (var definition : defaults) {
            var axis = new GrowthAxis();
            axis.setUserId(userId);
            axis.setDimensionId(definition.getId());
            axis.setName(definition.getName());
            axis.setDescription(definition.getDescription());
            axis.setDefinitionVersion(1L);
            current.add(axis);
        }
        Mockito.when(axesMapper.selectList(ArgumentMatchers.any())).thenReturn(current);
        var service = new GrowthServiceImpl(axesMapper, entries, new GrowthProperties());

        assertEquals(5, service.axesVersion(userId));
        var firstEdit = defaults.stream().map(axis -> new GrowthProperties.Axis(axis.getId(), axis.getName(), axis.getDescription())).toList();
        firstEdit.get(0).setName("新学识");
        service.updateAxes(userId, 5, firstEdit);
        Mockito.verify(axesMapper).updateDefinition(current.get(0));
        assertEquals(6, service.axesVersion(userId));

        var secondEdit = current.stream().map(axis -> new GrowthProperties.Axis(axis.getDimensionId(), axis.getName(), axis.getDescription())).toList();
        secondEdit.get(1).setName("新勇气");
        service.updateAxes(userId, 6, secondEdit);
        Mockito.verify(axesMapper).updateDefinition(current.get(1));
        assertEquals(7, service.axesVersion(userId));
        assertEquals(2, current.get(0).getDefinitionVersion());
        assertEquals(2, current.get(1).getDefinitionVersion());
    }
}

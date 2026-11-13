package org.xianshen.mumirrorb.service.impl;

import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.config.MirrorProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.MirrorProfileProto;
import org.xianshen.mumirrorb.mapper.*;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DTO.ProfileStatsDTO;
import org.xianshen.mumirrorb.pojo.DTO.SnapshotSourceRef;
import org.xianshen.mumirrorb.service.GlossaryService;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MirrorLookbackRecordsTest {
    @Test
    void sendsOnlyBudgetedLookbackAndTracksExactSources() {
        var chunks=mock(ChunkMapper.class);
        when(chunks.selectLookbackChunks(any(),any(),any(),anyInt())).thenReturn(List.of(
                chunk(1,11,"older"),chunk(2,12,"newer")));
        var props=new MirrorProperties(); props.setLookbackMaxChars(20);
        var owner=owner(chunks,props); var refs=new ArrayList<SnapshotSourceRef>();
        var req=new MirrorLineage(owner).buildRequest(UUID.randomUUID(),stats(),YearMonth.of(2026,11),refs);
        var field=req.getDescriptorForType().findFieldByName("records");
        assertNotNull(field); assertTrue(req.hasField(field));
        var body=(String)req.getField(field);
        assertTrue(body.contains("newer")); assertFalse(body.contains("older"));
        assertTrue(refs.contains(SnapshotSourceRef.builder().inputKind("record").inputId(12L).inputRole("lookback").build()));
        assertTrue(refs.contains(SnapshotSourceRef.builder().inputKind("chunk").inputId(2L).inputRole("lookback").build()));
        assertFalse(refs.stream().anyMatch(r->"lookback".equals(r.getInputRole()) && (Long.valueOf(1).equals(r.getInputId()) || Long.valueOf(11).equals(r.getInputId()))));
    }

    @Test
    void emptyLookbackIsExplicitlyPresent() {
        var owner=owner(mock(ChunkMapper.class),new MirrorProperties());
        var req=new MirrorLineage(owner).buildRequest(UUID.randomUUID(),stats(),YearMonth.of(2026,11),new ArrayList<>());
        var field=req.getDescriptorForType().findFieldByName("records");
        assertNotNull(field); assertTrue(req.hasField(field)); assertEquals("",req.getField(field));
    }

    @Test
    void zeroLookbackNeverReadsOrSendsRecordsForManualOrMonthlyMirror() {
        for (YearMonth month : Arrays.asList(null,YearMonth.of(2026,11))) {
            var chunks=mock(ChunkMapper.class);
            when(chunks.selectLookbackChunks(any(),any(),any(),anyInt())).thenReturn(List.of(chunk(3,13,"must stay private")));
            var owner=owner(chunks,new MirrorProperties());
            var settings=new org.xianshen.mumirrorb.pojo.DO.UserSettings(); settings.setMirrorLookback(0);
            when(owner.settingsMapper.selectOne(any())).thenReturn(settings);
            var refs=new ArrayList<SnapshotSourceRef>();
            var req=new MirrorLineage(owner).buildRequest(UUID.randomUUID(),stats(),month,refs);
            var field=req.getDescriptorForType().findFieldByName("records");
            assertTrue(req.hasField(field)); assertEquals("",req.getField(field));
            assertFalse(refs.stream().anyMatch(r->"lookback".equals(r.getInputRole())));
            verify(chunks,never()).selectLookbackChunks(any(),any(),any(),anyInt());
        }
    }

    @Test
    void manualMirrorWithPositiveLookbackStillSendsAndTracksRecords() {
        var chunks=mock(ChunkMapper.class);
        when(chunks.selectLookbackChunks(any(),any(),any(),anyInt())).thenReturn(List.of(chunk(3,13,"ordinary")));
        var refs=new ArrayList<SnapshotSourceRef>();
        var req=new MirrorLineage(owner(chunks,new MirrorProperties())).buildRequest(UUID.randomUUID(),stats(),null,refs);
        assertTrue(req.getRecords().contains("ordinary"));
        assertTrue(refs.contains(SnapshotSourceRef.builder().inputKind("chunk").inputId(3L).inputRole("lookback").build()));
        verify(chunks).selectLookbackChunks(any(),any(),any(),anyInt());
    }

    @Test
    void chunkCountAndPerChunkLimitsApplyBeforeSendingAndTracking() {
        var chunks=mock(ChunkMapper.class);
        when(chunks.selectLookbackChunks(any(),any(),any(),eq(3))).thenReturn(List.of(
                chunk(1,11,"oldest"),chunk(2,12,"second"),chunk(3,13,"newest")));
        var props=new MirrorProperties(); props.setLookbackMaxChunks(2); props.setPerChunkMaxChars(3);
        var refs=new ArrayList<SnapshotSourceRef>();
        var req=new MirrorLineage(owner(chunks,props)).buildRequest(UUID.randomUUID(),stats(),YearMonth.of(2026,11),refs);
        var field=req.getDescriptorForType().findFieldByName("records");
        var body=(String)req.getField(field);
        assertFalse(body.contains("old")); assertTrue(body.contains("sec")); assertTrue(body.contains("new"));
        assertFalse(body.contains("second")); assertFalse(body.contains("newest"));
        assertEquals(Set.of(2L,3L),refs.stream().filter(r->"chunk".equals(r.getInputKind()) && "lookback".equals(r.getInputRole())).map(SnapshotSourceRef::getInputId).collect(java.util.stream.Collectors.toSet()));
    }

    private static MirrorServiceImpl owner(ChunkMapper chunks,MirrorProperties props) {
        var owner=spy(new MirrorServiceImpl(mock(ProfileSnapshotMapper.class),mock(SnapshotInputMapper.class),mock(ProfileStatsMapper.class),mock(SettingsMapper.class),chunks,mock(AiGrpcClient.class),mock(GlossaryService.class),props,mock(TransactionTemplate.class),mock(SnapshotLineageService.class)));
        doReturn(new MirrorServiceImpl.PrevMirrorContext("",Set.of())).when(owner).buildPrevMirror(any(),nullable(YearMonth.class));
        doReturn(MirrorProfileProto.GenerateProfileRequest.getDefaultInstance()).when(owner).buildRequest(any(ProfileStatsDTO.class));
        return owner;
    }
    private static ProfileStatsDTO stats() { return ProfileStatsDTO.builder().moodStats(List.of()).build(); }
    private static Chunk chunk(long id,long record,String text) {
        return Chunk.builder().id(id).recordId(record).segment(text).createdAt(OffsetDateTime.parse("2026-11-12T00:00:00Z")).build();
    }
}

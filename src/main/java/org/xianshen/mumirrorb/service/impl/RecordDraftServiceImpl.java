package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.pipeline.ClassifyItemConverter;
import org.xianshen.mumirrorb.pipeline.TimeSubstitutionApplier;
import org.xianshen.mumirrorb.pojo.DTO.RecordDraftDTO;
import org.xianshen.mumirrorb.pojo.VO.SettingsVO;
import org.xianshen.mumirrorb.service.RecordDraftService;
import org.xianshen.mumirrorb.service.SettingsService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordDraftServiceImpl implements RecordDraftService {
    private static final ZoneId RECORD_ZONE = ZoneId.of("Asia/Shanghai");
    private final AiGrpcClient aiGrpcClient;
    private final SettingsService settingsService;

    @Override
    public RecordDraftDTO prepare(UUID userId, RecordDraftDTO input) {
        if (userId == null || input == null) throw new IllegalArgumentException("缺少用户或记录候选");
        String raw = input.getRawContent();
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("记录内容不能为空");
        if (raw.length() > 2000) throw new IllegalArgumentException("记录内容不能超过 2000 字");

        RecordDraftDTO draft = new RecordDraftDTO();
        draft.setSourceRecordId(input.getSourceRecordId());
        draft.setSourceRecordVersion(input.getSourceRecordVersion());
        draft.setRawContent(raw);
        draft.setFinalContent(input.getFinalContent() == null ? raw : input.getFinalContent());
        draft.setPrivateInput(input.isPrivateInput());
        draft.setAxesVersion(0);

        SettingsVO settings = settingsService.getSettings(userId);
        String requestedMode = input.getMode() == null || input.getMode().isBlank()
                ? settings.getDefaultRecordMode() : input.getMode();
        if (requestedMode == null || requestedMode.isBlank()) requestedMode = "ONLY_SAVE";
        if (!List.of("ONLY_SAVE", "AI_ASSISTED").contains(requestedMode))
            throw new IllegalArgumentException("记录模式只允许 ONLY_SAVE 或 AI_ASSISTED");

        // A genuinely private entry cannot enter any model path, even if an old UI sends AI_ASSISTED.
        if (input.isPrivateInput() || "ONLY_SAVE".equals(requestedMode)) {
            saveOnly(draft, input.isPrivateInput() ? "private input" : null);
            return draft;
        }

        draft.setMode("AI_ASSISTED");
        try {
            String referenceDate = LocalDate.now(RECORD_ZONE).toString();
            RecordProcessorProto.ClassifyResponse response = aiGrpcClient.classifySingle(userId, raw, null, referenceDate);
            if (response.getSkip() || response.getItemsCount() == 0)
                throw new IllegalStateException(response.getSkipReason().isBlank() ? "分类未返回候选" : response.getSkipReason());
            RecordProcessorProto.ClassifyItem item = response.getItems(0);
            String resolved = TimeSubstitutionApplier.apply(raw, item.getTimeSubstitutionsList());
            draft.setFinalContent(resolved);
            draft.setSegments(List.of(segment(item, resolved)));
            draft.setPreparationStatus("READY");
            draft.setPreparationError(null);
        } catch (Exception e) {
            log.warn("记录候选分类失败，降级为待确认的仅保存候选: {}", e.getMessage());
            saveOnly(draft, e.getMessage());
        }
        return draft;
    }

    private void saveOnly(RecordDraftDTO draft, String reason) {
        draft.setMode("ONLY_SAVE");
        draft.setFinalContent(draft.getRawContent());
        RecordDraftDTO.Segment segment = new RecordDraftDTO.Segment();
        segment.setDraftId(UUID.randomUUID());
        segment.setText(draft.getRawContent());
        segment.setMemoryEnabled(false);
        draft.setSegments(List.of(segment));
        draft.setPreparationStatus(reason == null ? "READY" : "SAVE_ONLY");
        draft.setPreparationError(reason);
    }

    private RecordDraftDTO.Segment segment(RecordProcessorProto.ClassifyItem item, String text) {
        RecordDraftDTO.Segment segment = new RecordDraftDTO.Segment();
        segment.setDraftId(UUID.randomUUID());
        segment.setText(text);
        segment.setTitle(item.getTitle());
        segment.setSummary(item.getSummary());
        segment.setContentType(ClassifyItemConverter.convertContentType(item.getContentType()));
        segment.setMoods(ClassifyItemConverter.convertMoods(item.getMoodsList()));
        segment.setKeywords(item.getKeywordsList());
        segment.setTags(new ArrayList<>());
        segment.setTaskStatus(ClassifyItemConverter.convertTaskStatus(item.getStatus()));
        segment.setMemoryEnabled(false);
        return segment;
    }
}

package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.grpc.gen.MirrorChatProto;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.pojo.DTO.RetrievedChunkDTO;
import org.xianshen.mumirrorb.pojo.VO.InspirationVO;
import org.xianshen.mumirrorb.service.InspirationService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 写作灵感服务实现（设计文档 6.8）
 *
 * <p>输入停顿 &gt;30s 由前端触发：当前输入 → Embed → pgvector 检索相关历史 →
 * 复用 Chat 生成 2-3 条提示。临时内容不存储（草稿与灵感均不落库）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspirationServiceImpl implements InspirationService {

    private static final int RELATED_LIMIT = 3;

    private final ChatSearchMapper searchMapper;
    private final AiGrpcClient aiGrpcClient;

    @Override
    public InspirationVO inspire(UUID userId, String draft) {
        String text = draft == null ? "" : draft.trim();
        if (text.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "草稿内容不能为空");
        }

        // 1. Embed 当前输入（复用 Embed）
        EmbeddingProto.EmbedResponse embed = aiGrpcClient.embed(userId, text);
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < embed.getVectorCount(); i++) {
            if (i > 0) {
                vector.append(',');
            }
            vector.append(embed.getVector(i));
        }
        vector.append(']');

        // 2. pgvector 检索相关历史（纯向量，无衰减——灵感要的是语义相关而非时效）
        List<RetrievedChunkDTO> related = searchMapper.searchSemantic(
                userId, vector.toString(), false, 30.0, RELATED_LIMIT);

        // 3. 复用 Chat 生成 2-3 条提示
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户正在写日记，当前草稿：\n「").append(text).append("」\n");
        if (!related.isEmpty()) {
            prompt.append("TA 的相关历史记录：\n");
            for (RetrievedChunkDTO c : related) {
                prompt.append("- [").append(c.getCreatedAt()).append("] ")
                        .append(c.getTitle() == null || c.getTitle().isBlank() ? "" : c.getTitle() + "：")
                        .append(truncate(c.getContent(), 80)).append("\n");
            }
        }
        prompt.append("请基于历史给出 2-3 个具体可写的方向或角度（每条一行，以 - 开头），")
                .append("不要直接复述历史内容，不超过 60 字每条。若无相关历史，基于草稿本身给出延展角度。");

        MirrorChatProto.ChatRequest request = MirrorChatProto.ChatRequest.newBuilder()
                .setQuestion(prompt.toString())
                .build();
        String answer = aiGrpcClient.chatBlocking(userId, request);

        // 4. 解析为条目（每行一条，去 - 前缀）
        List<String> suggestions = new ArrayList<>();
        if (answer != null && !answer.isBlank()) {
            for (String line : answer.split("\n")) {
                String cleaned = line.trim().replaceAll("^[-*•\\d.、）)]+\\s*", "");
                if (!cleaned.isBlank() && !cleaned.contains("当前草稿")) {
                    suggestions.add(cleaned);
                }
                if (suggestions.size() >= 3) {
                    break;
                }
            }
        }
        if (suggestions.isEmpty()) {
            suggestions.add("暂时无法生成灵感，先随便写点什么吧");
        }

        // 5. 依据的历史（溯源）
        List<InspirationVO.SourceItem> sources = new ArrayList<>();
        for (RetrievedChunkDTO c : related) {
            sources.add(InspirationVO.SourceItem.builder()
                    .recordId(c.getRecordId())
                    .title(c.getTitle() != null && !c.getTitle().isBlank()
                            ? c.getTitle() : truncate(c.getContent(), 30))
                    .createdAt(c.getCreatedAt())
                    .build());
        }

        log.info("写作灵感已生成，用户: {}, suggestions: {}, sources: {}", userId, suggestions.size(), sources.size());
        return InspirationVO.builder()
                .suggestions(suggestions)
                .sources(sources)
                .build();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}

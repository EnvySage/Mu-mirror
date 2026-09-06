package org.xianshen.mumirrorb.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.config.VaultProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.VaultItemMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.VaultItem;
import org.xianshen.mumirrorb.vault.ContentExtractor;
import org.xianshen.mumirrorb.vault.VaultStorage;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * vault 消化管道服务（fix-batch B5：从 VaultServiceImpl 拆出的独立 Bean）
 *
 * <p>拆分原因（Y6）：VaultServiceImpl 内 {@code this.digestAsync(...)} 是自调用，
 * 不走代理，@Async 失效——上传接口被同步阻塞在消化管道上（PDF 抽文本可长达数秒）。
 * 拆成独立 Bean 后 upload() 跨 Bean 调用 {@code digestService.digestAsync(...)}，
 * @Async 代理生效。</p>
 *
 * <p>fix-batch B7 同步改管道语义（确认门禁，toolcalling-vault-design.md §3.3b）：</p>
 * <ul>
 *   <li>文本/PDF/docx：抽文本 → 虚 Record(vault) + chunk 落库（<b>不 embed</b>）→ extracted（停，等确认）</li>
 *   <li>图片：半消化 → extracted（如实，描述为空时前端强制输入——Y4）</li>
 *   <li>音视频：零消化 → skipped</li>
 *   <li>用户确认（confirmDigest）时才生成 key chunk（embed）+ 全文 chunk embed → confirmed</li>
 * </ul>
 *
 * <p>管道隔离不变：失败只改该文件 digest_status=failed，不炸主服务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigestService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final VaultItemMapper itemMapper;
    private final RecordMapper recordMapper;
    private final ChunkMapper chunkMapper;
    private final VaultStorage storage;
    private final VaultProperties props;
    private final AiGrpcClient aiGrpcClient;

    // ==================== 消化管道（B7 五态） ====================

    /**
     * 消化入口（@Async 独立 Bean 代理生效——B5 拆分点）
     *
     * <p>文本族/PDF/docx 全提取（不 embed）；图片半消化；音视频零消化 skipped。</p>
     */
    @Async
    @Transactional
    public void digestAsync(UUID userIdIgnored, Long itemId) {
        try {
            doDigest(itemId);
        } catch (Exception e) {
            // 管道隔离：失败只影响该文件状态，不炸主服务
            log.warn("vault 消化失败（管道隔离），item: {}, 原因: {}", itemId, e.getMessage());
            try {
                VaultItem patch = VaultItem.builder().id(itemId).digestStatus("failed").build();
                itemMapper.updateById(patch);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 提取分派（B7 五态语义）：
     * 文本族/PDF/docx → 抽文本落虚 Record + chunk（不 embed）→ extracted 停，等用户确认；
     * 图片 → extracted（如实，Y4：描述为空时 F 强制输入）；
     * 音视频 → skipped（零消化元数据卡）
     */
    private void doDigest(Long itemId) {
        VaultItem item = itemMapper.selectById(itemId);
        if (item == null || item.getDeletedAt() != null) {
            return;
        }
        if (item.getMime().startsWith("audio/")) {
            // 零消化：音视频只有元数据卡（ID3/时长），直接 skipped
            patchStatus(item, "skipped");
            log.info("vault 零消化完成（skipped），item: {}", itemId);
            return;
        }
        if (item.getMime().startsWith("image/")) {
            // 半消化（Y4 如实口径）：图片没有全文可索引，描述/EXIF 进确认环节，状态 extracted
            patchStatus(item, "extracted");
            log.info("vault 半消化完成（extracted，待确认），item: {}", itemId);
            return;
        }

        // 全提取：抽文本 → 虚拟 Record(vault) → chunk 挂 vault_item_id 落库（不 embed，确认门禁）
        org.xianshen.mumirrorb.pojo.DO.VaultBlob blob = storage.get(item.getStorageKey());
        if (blob == null || blob.getData() == null) {
            throw new IllegalStateException("本体缺失");
        }
        String text = ContentExtractor.extract(blob.getData(), item.getMime(), props.getDigestMaxChars());
        if (text == null || text.isBlank()) {
            patchStatus(item, "failed");
            log.warn("vault 全提取无文本产出，item: {}, mime: {}", itemId, item.getMime());
            return;
        }

        Record record = Record.builder()
                .userId(item.getUserId())
                .content(text)
                .source("vault")
                .status(org.xianshen.mumirrorb.common.enums.RecordStatus.DONE)
                .userReviewed(true)
                .createdAt(item.getCreatedAt())
                .updatedAt(OffsetDateTime.now(ZONE))
                .build();
        recordMapper.insert(record);

        Chunk chunk = Chunk.builder()
                .userId(item.getUserId())
                .recordId(record.getId())
                .vaultItemId(itemId)
                .content(text)
                .segment(text)
                .metadata(digestMetadata(item))
                .classifiedSegment(text)
                .userEdited(false)
                .createdAt(OffsetDateTime.now(ZONE))
                .build();
        chunkMapper.insert(chunk);
        item.setSourceChunkId(chunk.getId());
        item.setDigestStatus("extracted");
        itemMapper.updateById(item);
        log.info("vault 全提取完成（extracted，待确认；embed 留给 confirm），item: {}, 文本 {} 字符, chunk: {}",
                itemId, text.length(), chunk.getId());
    }

    // ==================== 确认门禁（B7：确认后才 embed） ====================

    /**
     * 确认消化（POST /vault/{id}/confirm 主逻辑）：
     * ① 更新元数据（key/description/category 用户可改） ② 生成 key chunk（embed=key+description+类型拼合，
     * contentType='note'，挂 vault_item_id——§3.3c key-embed） ③ 全文消化 chunk 这时才 embed
     * ④ 状态 → confirmed
     *
     * <p>embed 失败不阻断确认（向量可补），但状态不进 confirmed——保持 extracted，
     * 用户可重复确认补 embed（幂等：已 confirmed 直接返回）。</p>
     *
     * @return 更新后的资产（confirmed 语义由 digest_status 表达）
     */
    @Transactional
    public VaultItem confirmDigest(VaultItem item, String key, String description, String category) {
        if ("confirmed".equals(item.getDigestStatus())) {
            return item; // 幂等：重复确认直接返回
        }
        // ① 元数据更新（用户改后提交的 key/description/category）
        if (key != null && !key.isBlank()) {
            item.setOriginalName(key.trim());
        }
        if (description != null) {
            item.setDescription(description.isBlank() ? null : description.trim());
        }
        if (category != null && !category.isBlank()) {
            item.setCategory(category.trim().toLowerCase(Locale.ROOT));
        }
        itemMapper.updateById(item);

        // ② key chunk（§3.3c：embed 文本 = key + description + 类型拼合；contentType='note'；
        //    metadata.keyChunk='true' 是通用检索白名单标记——只有它进对话检索，全文 chunk 不进）
        String keyText = buildKeyText(item);
        try {
            Chunk keyChunk = Chunk.builder()
                    .userId(item.getUserId())
                    .recordId(fullTextRecordId(item))
                    .vaultItemId(item.getId())
                    .content(keyText)
                    .segment(keyText)
                    .metadata(keyChunkMetadata(item))
                    .classifiedSegment(keyText)
                    .userEdited(false)
                    .createdAt(OffsetDateTime.now(ZONE))
                    .build();
            EmbeddingProto.EmbedResponse embed = aiGrpcClient.embed(item.getUserId(), keyText);
            keyChunk.setEmbedding(embed.getVectorList());
            chunkMapper.insert(keyChunk);
            log.info("vault key chunk 已生成并 embed，item: {}, id: {}", item.getId(), keyChunk.getId());
        } catch (Exception e) {
            // key chunk 是可检索性的唯一载体：失败则不进 confirmed（保持 extracted 可重试）
            log.warn("vault key chunk 生成/embed 失败（保持 extracted 可重试），item: {}, 原因: {}",
                    item.getId(), e.getMessage());
            return item;
        }

        // ③ 全文消化 chunk 这时才 embed（确认门禁：未确认不进检索）
        embedFullTextChunks(item);

        // ④ 状态 → confirmed
        item.setDigestStatus("confirmed");
        itemMapper.updateById(item);
        log.info("vault 确认完成（confirmed，已可检索），item: {}", item.getId());
        return item;
    }

    /**
     * 全文消化 chunks 补 embed（确认后执行；失败不阻断——与既有口径一致，向量可后续补）
     */
    private void embedFullTextChunks(VaultItem item) {
        try {
            for (Chunk c : chunkMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                    .eq(Chunk::getVaultItemId, item.getId())
                    .isNull(Chunk::getEmbedding))) {
                String text = c.getSegment() == null ? c.getContent() : c.getSegment();
                if (text == null || text.isBlank()) {
                    continue;
                }
                try {
                    EmbeddingProto.EmbedResponse embed = aiGrpcClient.embed(item.getUserId(), text);
                    c.setEmbedding(embed.getVectorList());
                    chunkMapper.updateById(c);
                } catch (Exception e) {
                    log.warn("vault 全文 chunk embed 失败（不阻断），chunk: {}, 原因: {}", c.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("vault 全文 chunk embed 扫描失败（不阻断），item: {}, 原因: {}", item.getId(), e.getMessage());
        }
    }

    /**
     * key chunk embed 文本（§3.3c）："RAG 毕业论文：一份关于 RAG 检索的开题报告，PDF 文档"
     * = display_name + "：" + description + "，" + 类型名
     */
    private String buildKeyText(VaultItem item) {
        StringBuilder sb = new StringBuilder(item.getOriginalName() == null ? "" : item.getOriginalName());
        if (item.getDescription() != null && !item.getDescription().isBlank()) {
            sb.append("：").append(item.getDescription());
        }
        String typeLabel = keyTypeLabel(item.getMime(), item.getOriginalName());
        if (!typeLabel.isEmpty()) {
            sb.append("，").append(typeLabel);
        }
        return sb.toString();
    }

    private static String keyTypeLabel(String mime, String name) {
        if (mime == null) {
            return "";
        }
        return switch (mime) {
            case "application/pdf" -> "PDF 文档";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "Word 文档";
            case "text/plain" -> "文本";
            case "text/markdown" -> "Markdown 文档";
            case "text/csv" -> "CSV 表格";
            case "image/jpeg" -> "JPG 图片";
            case "image/png" -> "PNG 图片";
            case "image/webp" -> "WebP 图片";
            case "image/gif" -> "GIF 图片";
            case "audio/mpeg" -> "MP3 音频";
            case "audio/wav" -> "WAV 音频";
            default -> mime.startsWith("audio/") ? "音频" : name != null && name.indexOf('.') >= 0
                    ? name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) + " 文件" : "";
        };
    }

    /**
     * key chunk metadata：keyChunk='true' 是 ChatSearchMapper 白名单标记（B1：只放行 confirmed 资产
     * 的 key chunk 进通用检索）；contentType 恒 'note'（§3.3c 裁决）
     */
    private Map<String, Object> keyChunkMetadata(VaultItem item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", item.getOriginalName());
        metadata.put("summary", item.getDescription() == null
                ? "vault 资产：" + item.getOriginalName() : item.getDescription());
        metadata.put("contentType", "note");
        metadata.put("keyChunk", "true");
        metadata.put("vaultItemId", item.getId());
        metadata.put("vaultMime", item.getMime());
        return metadata;
    }

    /**
     * 消化 chunk metadata（提取阶段落库用；keyChunk 不置 true——未确认不进通用检索）
     */
    private Map<String, Object> digestMetadata(VaultItem item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", item.getOriginalName());
        metadata.put("summary", item.getDescription() == null
                ? "vault 资产：" + item.getOriginalName() : item.getDescription());
        metadata.put("contentType", item.getCategory() == null ? "note" : item.getCategory());
        metadata.put("vaultItemId", item.getId());
        metadata.put("vaultMime", item.getMime());
        return metadata;
    }

    /**
     * key chunk 挂靠的 record id：复用全文消化的虚 Record（同一 vault_item 的 key/全文
     * chunk 共享 record_id，删除时同一级联清）；全文未提取（图片）时 lazily 建一条
     */
    private Long fullTextRecordId(VaultItem item) {
        Chunk existing = chunkMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getVaultItemId, item.getId())
                .orderByAsc(Chunk::getId)
                .last("LIMIT 1"));
        if (existing != null && existing.getRecordId() != null) {
            return existing.getRecordId();
        }
        // 图片等无全文 chunk 的资产：key chunk 也需要一个 record 锚点（vault 虚 Record）
        Record record = Record.builder()
                .userId(item.getUserId())
                .content("vault 资产：" + item.getOriginalName())
                .source("vault")
                .status(org.xianshen.mumirrorb.common.enums.RecordStatus.DONE)
                .userReviewed(true)
                .createdAt(item.getCreatedAt())
                .updatedAt(OffsetDateTime.now(ZONE))
                .build();
        recordMapper.insert(record);
        return record.getId();
    }

    private void patchStatus(VaultItem item, String status) {
        item.setDigestStatus(status);
        itemMapper.updateById(item);
    }
}

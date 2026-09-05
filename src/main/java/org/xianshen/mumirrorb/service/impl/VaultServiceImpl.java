package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.config.VaultProperties;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.mapper.ChunkMapper;
import org.xianshen.mumirrorb.mapper.RecordMapper;
import org.xianshen.mumirrorb.mapper.UserTermMapper;
import org.xianshen.mumirrorb.mapper.VaultItemMapper;
import org.xianshen.mumirrorb.pojo.DO.Chunk;
import org.xianshen.mumirrorb.pojo.DO.Record;
import org.xianshen.mumirrorb.pojo.DO.UserTerm;
import org.xianshen.mumirrorb.pojo.DO.VaultBlob;
import org.xianshen.mumirrorb.pojo.DO.VaultItem;
import org.xianshen.mumirrorb.pojo.VO.VaultItemVO;
import org.xianshen.mumirrorb.service.VaultService;
import org.xianshen.mumirrorb.vault.ByteaVaultStorage;
import org.xianshen.mumirrorb.vault.ContentExtractor;
import org.xianshen.mumirrorb.vault.FileTypeDetector;
import org.xianshen.mumirrorb.vault.VaultStorage;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 用户资产保管服务实现（vault；toolcalling-vault-design.md 第 3 节）
 *
 * <p>安全面：magic bytes（不信扩展名）/ original_name 清洗（路径符号+控制字符+255 截断）/
 * SHA-256 同用户去重 / 单文件 20MB + 配额 500MB（VaultProperties 配置化）/
 * 全接口 ownership（非本人或已删一律 4041 不暴露存在性）/ 非图片下载 attachment / 硬删除级联清 chunks。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VaultServiceImpl implements VaultService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);

    private final VaultItemMapper itemMapper;
    private final RecordMapper recordMapper;
    private final ChunkMapper chunkMapper;
    private final UserTermMapper termMapper;
    private final VaultStorage storage;
    private final VaultProperties props;
    private final AiGrpcClient aiGrpcClient;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // ==================== 上传 ====================

    @Override
    @Transactional
    public VaultItemVO upload(UUID userId, MultipartFile file, String description) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件为空");
        }
        byte[] bytes = file.getBytes();

        // 1. 单文件上限（配置化）
        if (bytes.length > props.getMaxFileSizeBytes()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    humanSize(bytes.length) + " 超过单文件上限 " + humanSize(props.getMaxFileSizeBytes()));
        }
        // 2. 配额校验
        long used = itemMapper.sumAliveBytes(userId);
        if (used + bytes.length > props.getQuotaBytes()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "空间不足：已用 " + humanSize(used) + " / " + humanSize(props.getQuotaBytes()));
        }
        // 3. magic bytes 白名单（不信扩展名；svg/zip/exe/视频天然拒）
        String mime = FileTypeDetector.detect(bytes, file.getOriginalFilename());
        if (mime == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的文件类型（白名单外或类型不明）");
        }
        // 4. original_name 清洗（路径符号/控制字符/255 截断）
        String originalName = sanitizeName(file.getOriginalFilename());
        // 5. SHA-256 去重（同用户同内容拒绝；uq_vault_sha 唯一索引兜底）
        String sha256 = sha256Hex(bytes);
        VaultItem dup = itemMapper.selectOne(new LambdaQueryWrapper<VaultItem>()
                .eq(VaultItem::getUserId, userId)
                .eq(VaultItem::getSha256, sha256)
                .isNull(VaultItem::getDeletedAt));
        if (dup != null) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "已保存过相同内容的文件：" + dup.getOriginalName() + "（可改个描述重新上传，或直接用它）");
        }
        // 6. 元数据榨取（三层 key 第 1 层，零 LLM）
        String metaHint = ContentExtractor.extractMetadataHint(bytes, mime, originalName);

        // 7. 落元数据（storage_key 先占位 id=0，insert 回填后拼真实 key）
        VaultItem item = VaultItem.builder()
                .userId(userId)
                .originalName(originalName)
                .storageKey("pending")
                .sizeBytes((long) bytes.length)
                .mime(mime)
                .sha256(sha256)
                .category(guessCategory(mime))
                .description(firstNonBlank(description, metaHint))
                .digestStatus("pending")
                .createdAt(OffsetDateTime.now(ZONE))
                .build();
        itemMapper.insert(item);
        String storageKey = "v" + item.getId() + ":" + UUID.randomUUID()
                + "." + extOf(originalName);
        item.setStorageKey(storageKey);
        itemMapper.updateById(item);

        // 8. 本体落分表
        storage.put(storageKey, bytes);

        // 9. 消化异步（管道隔离；失败只改该文件状态）
        digestAsync(userId, item.getId());

        log.info("vault 上传成功，用户: {}, id: {}, mime: {}, size: {}",
                userId, item.getId(), mime, humanSize(bytes.length));
        return toVO(item, used + bytes.length, null);
    }

    // ==================== 列表 / 检索 ====================

    @Override
    @Transactional(readOnly = true)
    public List<VaultItemVO> list(UUID userId, String typeFilter) {
        String prefix = typePrefixOf(typeFilter);
        List<VaultItem> items = itemMapper.selectAliveByUser(userId).stream()
                .filter(i -> prefix == null || i.getMime().startsWith(prefix))
                .toList();
        long used = items.stream().mapToLong(i -> i.getSizeBytes() == null ? 0 : i.getSizeBytes()).sum();
        List<VaultItemVO> result = new ArrayList<>(items.size());
        for (VaultItem item : items) {
            result.add(toVO(item, used, null));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VaultItemVO> find(UUID userId, String query, String type, Integer days) {
        OffsetDateTime since = days != null && days > 0
                ? LocalDate.now(ZONE).minusDays(days).atStartOfDay(ZONE).toOffsetDateTime() : null;
        String prefix = typePrefixOf(type);

        Map<Long, VaultItemVO> hits = new LinkedHashMap<>();

        // ① 精确层：文件名/描述 ILIKE + 词典 term/别名联动（vault×词典咬合点）
        List<String> patterns = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            patterns.add("%" + likeEscape(query.trim()) + "%");
            extendPatternsWithGlossary(userId, query.trim(), patterns);
        }
        if (!patterns.isEmpty()) {
            for (VaultItem item : itemMapper.searchByKeyword(userId, patterns,
                    prefix == null ? null : prefix, prefix, since, props.getFindItemLimit())) {
                hits.putIfAbsent(item.getId(), toVO(item, null, "weak"));
            }
        }

        // ② 语义层：description embedding（无向量/无查询则跳过）
        if (prefix == null || prefix.startsWith("text") || prefix.startsWith("application")) {
            // 文本类才走语义/全文（图片音视频只有 ①③ 的类型窗兜底）
            if (query != null && !query.isBlank()) {
                try {
                    String vector = embedVector(userId, query);
                    for (Chunk c : chunkMapper.searchVaultBySimilarity(userId, vector, props.getFindItemLimit())) {
                        VaultItem item = itemMapper.selectAliveById(c.getVaultItemId(), userId);
                        if (item != null && typeMatch(item, prefix)) {
                            // 内容向量命中 = 强引用
                            hits.putIfAbsent(item.getId(), toVO(item, null, "strong"));
                        }
                    }
                } catch (Exception e) {
                    log.warn("vault 语义层检索失败（降级跳过），用户: {}, 原因: {}", userId, e.getMessage());
                }
            }
        }

        // ③ 兜底层：零 key（无命中且给了时间/类型）按类型+时间窗
        if (hits.isEmpty() && (prefix != null || since != null)) {
            for (VaultItem item : itemMapper.selectByTypeAndWindow(userId, prefix, since, props.getFindItemLimit())) {
                hits.putIfAbsent(item.getId(), toVO(item, null, "vague"));
            }
        }

        return new ArrayList<>(hits.values());
    }

    @Override
    @Transactional(readOnly = true)
    public VaultItemVO recall(UUID userId, Long itemId) {
        VaultItem item = requireAlive(itemId, userId);
        String quote = null;
        int chunkCount = 0;
        if (item.getSourceChunkId() != null) {
            Chunk chunk = chunkMapper.selectById(item.getSourceChunkId());
            if (chunk != null && chunk.getSegment() != null) {
                quote = chunk.getSegment().length() > 160
                        ? chunk.getSegment().substring(0, 160) + "…" : chunk.getSegment();
            }
        }
        Long chunks = chunkMapper.selectCount(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getVaultItemId, itemId));
        chunkCount = chunks == null ? 0 : chunks.intValue();
        return toVO(item, null, null, quote, chunkCount);
    }

    // ==================== 下载 / 预览 ====================

    @Override
    @Transactional(readOnly = true)
    public DownloadResult download(UUID userId, Long itemId) {
        return loadFile(userId, itemId);
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadResult preview(UUID userId, Long itemId) {
        return loadFile(userId, itemId);
    }

    private DownloadResult loadFile(UUID userId, Long itemId) {
        VaultItem item = requireAlive(itemId, userId);
        VaultBlob blob = storage.get(item.getStorageKey());
        if (blob == null || blob.getData() == null) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "文件本体缺失");
        }
        return new DownloadResult(toVO(item, null, null), blob.getData());
    }

    // ==================== 删除 / 补正 ====================

    @Override
    @Transactional
    public void delete(UUID userId, Long itemId) {
        VaultItem item = requireAlive(itemId, userId);
        // 1. 级联清消化 chunks（FK ON DELETE CASCADE 也会兜底；此处显式删 + 记审计位）
        chunkMapper.delete(new LambdaQueryWrapper<Chunk>().eq(Chunk::getVaultItemId, itemId));
        // 2. 先记删除瞬间（deleted_at 审计位），再做物理删（避免 source_chunk_id FK 因级联顺序报错）
        item.setDeletedAt(OffsetDateTime.now(ZONE));
        // 3. blob 删除
        storage.delete(item.getStorageKey());
        // 4. 物理删除（chunks 已清，source_chunk_id 引用不存在 → 先断开引用再删行）
        itemMapper.deleteById(itemId);
        log.info("vault 硬删除完成，用户: {}, id: {}, name: {}, 删除时刻: {}",
                userId, itemId, item.getOriginalName(), item.getDeletedAt());
    }

    @Override
    @Transactional
    public VaultItemVO update(UUID userId, Long itemId, String description, String category) {
        VaultItem item = requireAlive(itemId, userId);
        if (description != null) {
            if (description.length() > 500) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "描述最长 500 字");
            }
            item.setDescription(description.isBlank() ? null : description.trim());
        }
        if (category != null && !category.isBlank()) {
            item.setCategory(category.trim().toLowerCase(Locale.ROOT));
        }
        itemMapper.updateById(item);
        return toVO(item, null, null);
    }

    // ==================== 三档消化（异步，管道隔离） ====================

    @Override
    @Async
    @Transactional
    public void digestAsync(UUID userId, Long itemId) {
        try {
            doDigest(userId, itemId);
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
     * 三档分派：文本族/PDF/docx 全消化；图片半消化（用户描述即 key）；音视频零消化 skipped
     */
    private void doDigest(UUID userId, Long itemId) {
        VaultItem item = itemMapper.selectAliveById(itemId, userId);
        if (item == null) {
            return;
        }
        if (item.getMime().startsWith("image/") || item.getMime().startsWith("audio/")) {
            // 半消化/零消化：元数据卡保管；有用户描述即视为可检索（语义层 ② 用 description embedding）
            String status = item.getMime().startsWith("image/") ? "done" : "skipped";
            item.setDigestStatus(status);
            itemMapper.updateById(item);
            log.info("vault {} 消化完成（{}），item: {}",
                    item.getMime().startsWith("image/") ? "半" : "零", status, itemId);
            return;
        }

        // 全消化：抽文本 → Record(vault) → chunk 挂 vault_item_id → Embed
        VaultBlob blob = storage.get(item.getStorageKey());
        if (blob == null || blob.getData() == null) {
            throw new IllegalStateException("本体缺失");
        }
        String text = ContentExtractor.extract(blob.getData(), item.getMime(), props.getDigestMaxChars());
        if (text == null || text.isBlank()) {
            item.setDigestStatus("failed");
            itemMapper.updateById(item);
            log.warn("vault 全消化无文本产出，item: {}, mime: {}", itemId, item.getMime());
            return;
        }

        // 虚拟 Record（vault 内容进时间线？不进——source='vault' 隔离，列表口径 source='user' 不受影响）
        Record record = Record.builder()
                .userId(userId)
                .content(text)
                .source("vault")
                .status(org.xianshen.mumirrorb.common.enums.RecordStatus.DONE)
                .userReviewed(true)
                .createdAt(item.getCreatedAt())
                .updatedAt(OffsetDateTime.now(ZONE))
                .build();
        recordMapper.insert(record);

        Chunk chunk = Chunk.builder()
                .userId(userId)
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

        // Embed（失败不阻断，向量后续可补——与 confirm 口径一致）
        try {
            EmbeddingProto.EmbedResponse embed = aiGrpcClient.embed(userId, text);
            chunk.setEmbedding(embed.getVectorList());
            chunkMapper.updateById(chunk);
        } catch (Exception e) {
            log.warn("vault chunk Embed 失败（不阻断），item: {}, 原因: {}", itemId, e.getMessage());
        }

        item.setDigestStatus("done");
        itemMapper.updateById(item);
        log.info("vault 全消化完成，item: {}, 文本 {} 字符, chunk: {}", itemId, text.length(), chunk.getId());
    }

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

    // ==================== 内部工具 ====================

    /**
     * ownership 校验：非本人/已删/不存在一律 4041（不暴露存在性）
     */
    private VaultItem requireAlive(Long itemId, UUID userId) {
        VaultItem item = itemMapper.selectAliveById(itemId, userId);
        if (item == null) {
            throw new BusinessException(ResultCode.RECORD_NOT_FOUND, "资产不存在");
        }
        return item;
    }

    /**
     * original_name 清洗：去路径符号（/ \ .. :）、控制字符、空白归一、255 截断、空名兜底
     */
    public static String sanitizeName(String name) {
        String n = name == null ? "" : name;
        n = n.replace("\\", "/");
        n = n.substring(Math.max(n.lastIndexOf('/') + 1, 0)); // 只留文件名段
        StringBuilder sb = new StringBuilder(n.length());
        for (char c : n.toCharArray()) {
            if (c >= 32 && c != 127 && c != '/' && c != ':' && c != '"' && c != '\'' && c != '<' && c != '>' && c != '|') {
                sb.append(c);
            }
        }
        n = sb.toString().trim();
        while (n.contains("..")) {
            n = n.replace("..", ".");
        }
        if (n.isBlank()) {
            n = "unnamed";
        }
        return n.length() > 255 ? n.substring(0, 255) : n;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return (b == null || b.isBlank()) ? null : b.trim();
    }

    private static String guessCategory(String mime) {
        if (mime == null) {
            return "note";
        }
        if (mime.startsWith("image/")) {
            return "note";
        }
        if (mime.startsWith("audio/")) {
            return "note";
        }
        if (mime.equals("application/pdf") || mime.startsWith("application/vnd") || mime.startsWith("text/")) {
            return "learning"; // 文档默认学习资料（用户可在资产页改）
        }
        return "note";
    }

    /**
     * mime → 前端筛选大类
     */
    public static String kindOf(String mime) {
        if (mime == null) {
            return "document";
        }
        if (mime.startsWith("image/")) {
            return "image";
        }
        if (mime.startsWith("audio/")) {
            return "audio";
        }
        return "document";
    }

    /**
     * mime → 简短文件类型（前端图标 key）
     */
    public static String fileTypeOf(String mime) {
        if (mime == null) {
            return "file";
        }
        return switch (mime) {
            case "application/pdf" -> "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "text/plain" -> "txt";
            case "text/markdown" -> "md";
            case "text/csv" -> "csv";
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "audio/mpeg" -> "mp3";
            case "audio/wav" -> "wav";
            default -> mime.startsWith("audio/") ? "m4a" : "file";
        };
    }

    /**
     * 前端筛选参数 → mime 前缀
     */
    private static String typePrefixOf(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "document", "doc", "docs" -> null; // 文档类是 mime 异质集合，用 mime IN 语义在 filter 阶段处理
            case "image", "images" -> "image/";
            case "audio" -> "audio/";
            default -> null;
        };
    }

    private static boolean typeMatch(VaultItem item, String prefix) {
        return prefix == null || item.getMime().startsWith(prefix);
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "bin";
        return ext.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fGB", bytes / 1024.0 / 1024 / 1024);
        }
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }

    /**
     * 词典联动（精确层关键词扩展）：查 confirmed 词典里命中 query 的词条，把 term/别名并入 pattern。
     * 词典失败静默——vault 检索不依赖词典存活。
     */
    private void extendPatternsWithGlossary(UUID userId, String query, List<String> patterns) {
        try {
            for (UserTerm term : termMapper.selectConfirmedTop(userId, 30)) {
                if (term.getTerm() != null && query.contains(term.getTerm())) {
                    patterns.add("%" + likeEscape(term.getTerm()) + "%");
                }
                if (term.getAliases() != null) {
                    for (String alias : term.getAliases()) {
                        if (alias != null && !alias.isBlank() && query.contains(alias)) {
                            patterns.add("%" + likeEscape(alias) + "%");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("vault 精确层词典联动跳过: {}", e.getMessage());
        }
    }

    private String embedVector(UUID userId, String query) {
        EmbeddingProto.EmbedResponse embed = aiGrpcClient.embed(userId, query);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embed.getVectorCount(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embed.getVector(i));
        }
        return sb.append(']').toString();
    }

    private VaultItemVO toVO(VaultItem item, Long quotaUsed, String matchLayer) {
        return toVO(item, quotaUsed, matchLayer, null, null);
    }

    private VaultItemVO toVO(VaultItem item, Long quotaUsed, String matchLayer, String quote, Integer chunkCount) {
        return VaultItemVO.builder()
                .id(item.getId())
                .originalName(item.getOriginalName())
                .sizeBytes(item.getSizeBytes())
                .mime(item.getMime())
                .fileType(fileTypeOf(item.getMime()))
                .kind(kindOf(item.getMime()))
                .category(item.getCategory())
                .description(item.getDescription())
                .digestStatus(item.getDigestStatus())
                .quotaUsedBytes(quotaUsed)
                .quotaBytes(props.getQuotaBytes())
                .createdAt(item.getCreatedAt())
                .matchLayer(matchLayer)
                .quote(quote)
                .digestChunkCount(chunkCount)
                .build();
    }
}

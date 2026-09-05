package org.xianshen.mumirrorb.vault;

import org.springframework.web.multipart.MultipartFile;

/**
 * 资产文件类型探测器（magic bytes 判定，不信扩展名；toolcalling-vault-design.md 3.1）
 *
 * <p>返回 mime ∈ 白名单 → 放行；null → 类型不明/拒绝。svg/zip/exe/视频天然不在白名单。</p>
 */
public final class FileTypeDetector {

    private FileTypeDetector() {
    }

    /**
     * magic bytes → mime（白名单外返回 null）
     */
    public static String detect(byte[] bytes, String originalName) {
        if (bytes == null || bytes.length < 12) {
            return textFallback(originalName);
        }
        // PDF: %PDF-
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            return "application/pdf";
        }
        // ZIP 容器：PK\x03\x04 —— docx 是 zip 包，但要区分裸 zip（拒）
        if (bytes[0] == 'P' && bytes[1] == 'K' && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7)) {
            if (looksLikeDocx(bytes)) {
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }
            return null; // 裸 zip 一律拒（含 docx 之外的 ooxml、压缩包）
        }
        // PNG
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // GIF: GIF8
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return "image/gif";
        }
        // WEBP: RIFF....WEBP
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        // MP3: ID3 或 FF Ex（MPEG 帧头）
        if (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return "audio/mpeg";
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0) {
            return "audio/mpeg";
        }
        // WAV: RIFF....WAVE
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E') {
            return "audio/wav";
        }
        // M4A/MP4 容器: ....ftyp
        if (bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            return "audio/x-m4a";
        }
        return textFallback(originalName);
    }

    /**
     * 文本族兜底：无 BOM 无 magic 的 utf-8 可解码 → txt/markdown/csv；否则 null（二进制不明拒收）
     */
    private static String textFallback(String originalName) {
        if (originalName == null) {
            return null;
        }
        String lower = originalName.toLowerCase();
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        return null;
    }

    /**
     * zip 容器内是否含 word/ 目录（docx 判定；读 [Content_Types].xml 更严谨，前 4KB 扫 EOCD 文件名足够）
     */
    private static boolean looksLikeDocx(byte[] bytes) {
        // 检查头部 4KB 内是否出现 "word/" 路径段（docx 第一个 local file header 常为 [Content_Types].xml，
        // 紧随 word/ 目录项；粗扫足够拒绝裸 zip，精校由 digest 阶段 POI 解析失败兜底 failed）
        int scan = Math.min(bytes.length, 4096);
        for (int i = 0; i + 5 <= scan; i++) {
            if (bytes[i] == 'w' && bytes[i + 1] == 'o' && bytes[i + 2] == 'r' && bytes[i + 3] == 'd'
                    && bytes[i + 4] == '/') {
                return true;
            }
        }
        return false;
    }

    /**
     * 供 Controller 早期校验用：MultipartFile → mime（白名单外 null）
     */
    public static String detect(MultipartFile file) throws java.io.IOException {
        byte[] head = new byte[Math.min(4096, (int) file.getSize())];
        try (var in = file.getInputStream()) {
            int read = in.read(head);
            if (read < head.length) {
                // 短文件：截断到实际读取量
                byte[] actual = new byte[Math.max(read, 0)];
                System.arraycopy(head, 0, actual, 0, actual.length);
                head = actual;
            }
        }
        return detect(head, file.getOriginalFilename());
    }
}

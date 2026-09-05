package org.xianshen.mumirrorb.vault;

import org.springframework.web.multipart.MultipartFile;

/**
 * 资产文本抽取器（三档消化第 1 步；toolcalling-vault-design.md 3.3）
 *
 * <p>PDF→PDFBox / docx→POI / 文本族→直接解码；图片音视频返回 null（零/半消化不抽文本）。</p>
 */
public final class ContentExtractor {

    /** 全消化字符上限（VaultProperties.digestMaxChars，超限截断并告知） */
    private ContentExtractor() {
    }

    /**
     * 按类型抽文本；不支持类型返回 null
     *
     * @param bytes    文件字节
     * @param mime     magic bytes 判定后的 mime
     * @param maxChars 抽取字符上限
     * @return 文本（空文件返回 ""）；null = 该类型不抽文本
     */
    public static String extract(byte[] bytes, String mime, int maxChars) {
        if (mime == null) {
            return null;
        }
        return switch (mime) {
            case "application/pdf" -> extractPdf(bytes, maxChars);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    extractDocx(bytes, maxChars);
            case "text/plain", "text/markdown", "text/csv" -> extractText(bytes, maxChars);
            default -> null; // 图片/音视频：半消化/零消化
        };
    }

    /**
     * 元数据榨取（三层 key 第 1 层，零 LLM）：PDF 首页文本 / docx 首段 / 文本首行；图片音视频返回 null
     */
    public static String extractMetadataHint(byte[] bytes, String mime, String originalName) {
        if ("application/pdf".equals(mime)) {
            String text = extractPdf(bytes, 1200);
            return firstMeaningful(text, 200);
        }
        if (mime != null && mime.startsWith("application/vnd.openxmlformats")) {
            String text = extractDocx(bytes, 1200);
            return firstMeaningful(text, 200);
        }
        if (mime != null && mime.startsWith("text/")) {
            String text = extractText(bytes, 2000);
            return firstMeaningful(text, 200);
        }
        return null; // 图片 EXIF / 音频 ID3 解析留 digest 管道增强，首版文件名+用户描述兜底
    }

    private static String extractPdf(byte[] bytes, int maxChars) {
        try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(bytes)) {
            var pdfText = new org.apache.pdfbox.text.PDFTextStripper();
            pdfText.setEndPage(Math.min(doc.getNumberOfPages(), 50)); // 防 PDF 炸内存：最多前 50 页
            String text = pdfText.getText(doc);
            return truncate(text, maxChars);
        } catch (Exception e) {
            throw new IllegalStateException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    private static String extractDocx(byte[] bytes, int maxChars) {
        try (var zip = new java.io.ByteArrayInputStream(bytes);
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(zip)) {
            StringBuilder sb = new StringBuilder();
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append('\n');
                if (sb.length() > maxChars) {
                    break;
                }
            }
            return truncate(sb.toString(), maxChars);
        } catch (Exception e) {
            throw new IllegalStateException("docx 解析失败: " + e.getMessage(), e);
        }
    }

    private static String extractText(byte[] bytes, int maxChars) {
        return truncate(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), maxChars);
    }

    /**
     * 首个有意义行（跳过空行与超短行）
     */
    private static String firstMeaningful(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() >= 4) {
                return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
            }
        }
        return null;
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() > maxChars ? s.substring(0, maxChars) : s;
    }
}

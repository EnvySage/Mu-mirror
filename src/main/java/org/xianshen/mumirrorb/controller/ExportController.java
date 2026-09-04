package org.xianshen.mumirrorb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xianshen.mumirrorb.pojo.VO.ExportVO;
import org.xianshen.mumirrorb.service.ExportService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据导出控制器（设计文档 6.9 / 十章 / 裁决 #19：只导出不导入）
 */
@Tag(name = "数据导出", description = "全量数据导出（JSON / Markdown），自动排除 embedding 向量字段")
@RestController
@RequestMapping("/export")
@RequiredArgsConstructor
public class ExportController {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ExportService exportService;

    private UUID getCurrentUserId() {
        String userIdStr = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return UUID.fromString(userIdStr);
    }

    /**
     * 结构化备份导出（JSON 下载）
     */
    @Operation(
            summary = "导出 JSON",
            description = "全量结构化备份（记录+片段+画像+会话），自动排除 embedding 向量字段。只导出不导入（裁决 #19）。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "导出成功（JSON 文件下载）"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @GetMapping(value = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public void exportJson(HttpServletResponse response) throws IOException {
        UUID userId = getCurrentUserId();
        ExportVO data = exportService.exportJson(userId);
        String filename = "mu-mirror-export_" + FILE_TS.format(OffsetDateTime.now()) + ".json";
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(response.getWriter(), data);
    }

    /**
     * 人可读导出（Markdown 下载）
     */
    @Operation(
            summary = "导出 Markdown",
            description = "人可读日记/画像/对话导出，自动排除 embedding 向量字段。只导出不导入（裁决 #19）。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "导出成功（Markdown 文件下载）"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @GetMapping(value = "/markdown", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public void exportMarkdown(HttpServletResponse response) throws IOException {
        UUID userId = getCurrentUserId();
        ExportVO data = exportService.exportJson(userId);
        String markdown = renderMarkdown(data);
        String filename = "mu-mirror-export_" + FILE_TS.format(OffsetDateTime.now()) + ".md";
        response.setContentType("text/markdown;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.getOutputStream().write(markdown.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Markdown 渲染：按日期分组的日记 + 画像快照 + 会话
     */
    private String renderMarkdown(ExportVO data) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 镜子日记 · 数据导出\n\n");
        sb.append("- 导出时间：").append(data.getExportedAt()).append("\n");
        sb.append("- 用户：").append(data.getUsername() == null ? "-" : data.getUsername()).append("\n\n");

        sb.append("## 记录（").append(data.getRecords() == null ? 0 : data.getRecords().size()).append(" 条）\n\n");
        if (data.getRecords() != null) {
            String lastDate = "";
            for (ExportVO.RecordExportItem r : data.getRecords()) {
                String date = r.getCreatedAt() == null ? "-" : r.getCreatedAt().substring(0, Math.min(10, r.getCreatedAt().length()));
                if (!date.equals(lastDate)) {
                    sb.append("### ").append(date).append("\n\n");
                    lastDate = date;
                }
                if ("system".equals(r.getSource())) {
                    sb.append("> 每日总结\n\n");
                }
                sb.append(r.getContent()).append("\n\n");
                if (r.getChunks() != null) {
                    for (Map<String, Object> c : r.getChunks()) {
                        Object metadata = c.get("metadata");
                        Object segment = c.get("segment");
                        sb.append("- 片段：").append(segment == null ? "" : segment);
                        if (metadata instanceof Map<?, ?> md && md.get("title") != null) {
                            sb.append("（").append(md.get("title")).append("）");
                        }
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        sb.append("## 画像快照（").append(data.getProfiles() == null ? 0 : data.getProfiles().size()).append(" 份）\n\n");
        if (data.getProfiles() != null) {
            for (ExportVO.ProfileExportItem p : data.getProfiles()) {
                sb.append("### ").append(p.getSnapshotType()).append(" · ").append(p.getCreatedAt()).append("\n\n");
                if (p.getAnalysis() != null) {
                    for (Map.Entry<String, Object> e : p.getAnalysis().entrySet()) {
                        if (e.getValue() != null && !e.getValue().toString().isBlank()) {
                            sb.append("- **").append(e.getKey()).append("**：").append(e.getValue()).append("\n");
                        }
                    }
                }
                if (p.getUserTags() != null && !p.getUserTags().isEmpty()) {
                    sb.append("- 标签：").append(String.join("、", p.getUserTags())).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("## 会话（").append(data.getSessions() == null ? 0 : data.getSessions().size()).append(" 个）\n\n");
        if (data.getSessions() != null) {
            for (ExportVO.SessionExportItem s : data.getSessions()) {
                sb.append("### ").append(s.getTitle() == null ? "未命名会话" : s.getTitle())
                        .append(" · ").append(s.getCreatedAt()).append("\n\n");
                if (s.getMessages() != null) {
                    for (Map<String, Object> m : s.getMessages()) {
                        sb.append("**").append("user".equals(m.get("role")) ? "我" : "镜子").append("**：")
                                .append(m.get("content")).append("\n\n");
                    }
                }
            }
        }
        return sb.toString();
    }
}

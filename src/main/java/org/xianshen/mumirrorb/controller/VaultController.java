package org.xianshen.mumirrorb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.VaultItemVO;
import org.xianshen.mumirrorb.service.VaultService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户资产控制器（vault REST；toolcalling-vault-design.md 3.1/4.2/4.4）
 *
 * <p>安全：全接口 JWT + ownership（非本人/已删一律 4041）；
 * 非图片下载强制 Content-Disposition: attachment（svg 已被白名单挡在门外，防 XSS）。</p>
 */
@Tag(name = "我的资产", description = "vault 文件上传/列表/下载/预览/删除/补正")
@RestController
@RequestMapping("/vault")
@RequiredArgsConstructor
public class VaultController {

    private final VaultService vaultService;

    private UUID getCurrentUserId() {
        String userIdStr = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return UUID.fromString(userIdStr);
    }

    @Operation(
            summary = "上传文件",
            description = "multipart/form-data。单文件 ≤20MB、配额 500MB、magic bytes 白名单校验、SHA-256 去重。"
                    + "上传后异步消化（文本/PDF/docx 全消化，图片半消化，音视频零消化）。"
                    + "description 可空（placeholder：以后想怎么找到它？）。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "上传成功（data=文件卡）"),
            @ApiResponse(responseCode = "400", description = "超限/类型拒绝/重复内容"),
            @ApiResponse(responseCode = "401", description = "未登录")
    })
    @PostMapping("/upload")
    public R<VaultItemVO> upload(
            @Parameter(description = "文件本体", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "一句话描述（可空）", example = "开题报告")
            @RequestParam(value = "description", required = false) String description) throws java.io.IOException {
        UUID userId = getCurrentUserId();
        return R.ok("上传成功", vaultService.upload(userId, file, description));
    }

    @Operation(
            summary = "资产列表",
            description = "时间倒序文件卡（永不携带本体）；type=document|image|audio 筛选；含配额占用。"
    )
    @GetMapping
    public R<List<VaultItemVO>> list(
            @Parameter(description = "类型筛选", example = "document")
            @RequestParam(required = false) String type) {
        UUID userId = getCurrentUserId();
        return R.ok("查询成功", vaultService.list(userId, type));
    }

    @Operation(
            summary = "下载文件",
            description = "非图片类强制 attachment；非本人/已删 404。"
    )
    @GetMapping("/{id}/download")
    public ResponseEntity<ByteArrayResource> download(
            @Parameter(description = "资产ID", required = true, example = "1")
            @PathVariable Long id) {
        UUID userId = getCurrentUserId();
        VaultService.DownloadResult result = vaultService.download(userId, id);
        boolean inline = result.item().getMime().startsWith("image/");
        return fileResponse(result, inline);
    }

    @Operation(
            summary = "预览文件",
            description = "inline 输出（图片/PDF/文本内嵌；docx 等由前端降级'下载查看'）。非本人/已删 404。"
    )
    @GetMapping("/{id}/preview")
    public ResponseEntity<ByteArrayResource> preview(
            @Parameter(description = "资产ID", required = true, example = "1")
            @PathVariable Long id) {
        UUID userId = getCurrentUserId();
        VaultService.DownloadResult result = vaultService.preview(userId, id);
        return fileResponse(result, true);
    }

    @Operation(
            summary = "删除文件（硬删除）",
            description = "物理删除 + 级联清消化 chunks（向量库无孤儿）；deleted_at 留审计位。需前端二次确认。"
    )
    @DeleteMapping("/{id}")
    public R<Void> delete(
            @Parameter(description = "资产ID", required = true, example = "1")
            @PathVariable Long id) {
        UUID userId = getCurrentUserId();
        vaultService.delete(userId, id);
        return R.ok("已删除", null);
    }

    @Operation(
            summary = "补正描述/分类",
            description = "三层 key 第 3 层（用户补正）：改描述（以后想怎么找到它）/改分类。"
    )
    @PutMapping("/{id}")
    public R<VaultItemVO> update(
            @Parameter(description = "资产ID", required = true, example = "1")
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        UUID userId = getCurrentUserId();
        String description = body.get("description");
        String category = body.get("category");
        return R.ok("已更新", vaultService.update(userId, id, description, category));
    }

    @Operation(
            summary = "资产搜索（三层漏斗）",
            description = "①精确（文件名/描述/词典词）→②语义（描述 embedding）→③全文（消化 chunks 向量）；"
                    + "零 key 时按类型+时间窗兜底（\"昨天传的图片\"）。结果带 matchLayer: strong/weak/vague。"
    )
    @GetMapping("/search")
    public R<List<VaultItemVO>> search(
            @Parameter(description = "关键词（可空）", example = "开题报告")
            @RequestParam(required = false) String query,
            @Parameter(description = "类型筛选", example = "document")
            @RequestParam(required = false) String type,
            @Parameter(description = "最近 N 天（可空）", example = "7")
            @RequestParam(required = false) Integer days) {
        UUID userId = getCurrentUserId();
        return R.ok("查询成功", vaultService.find(userId, query, type, days));
    }

    /**
     * 文件响应：inline/attachment、mime、filename（RFC 5987 中文安全）、长度
     */
    private ResponseEntity<ByteArrayResource> fileResponse(VaultService.DownloadResult result, boolean inline) {
        String mime = result.item().getMime();
        String disposition = (inline && (mime.startsWith("image/") || mime.equals("application/pdf")
                || mime.startsWith("text/"))) ? "inline" : "attachment";
        String encodedName = URLEncoder.encode(result.item().getOriginalName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        ByteArrayResource resource = new ByteArrayResource(result.data()) {
            @Override
            public String getFilename() {
                return result.item().getOriginalName();
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType(mime))
                .contentLength(result.data().length)
                .body(resource);
    }
}

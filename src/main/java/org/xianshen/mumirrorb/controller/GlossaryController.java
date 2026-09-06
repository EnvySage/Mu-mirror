package org.xianshen.mumirrorb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.xianshen.mumirrorb.pojo.DTO.GlossaryCreateDTO;
import org.xianshen.mumirrorb.pojo.DTO.GlossaryUpdateDTO;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO.UserTermVO;
import org.xianshen.mumirrorb.service.GlossaryService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 个人词典控制器（lexicon-design.md 5c API）
 *
 * <p>GET /api/glossary（三组分好）· POST /api/glossary（手动新增 confirmed）·
 * PUT /api/glossary/{id}（编辑/更新解释）· DELETE /api/glossary/{id} ·
 * POST /api/glossary/{id}/confirm · POST /api/glossary/{id}/dismiss ·
 * POST /api/glossary/extract（手动触发抽取，懒人立即出候选）</p>
 */
@Tag(name = "个人词典", description = "用户个人词条的确认/编辑/忽略与候选抽取")
@RestController
@RequestMapping("/glossary")
@RequiredArgsConstructor
public class GlossaryController {

    private final GlossaryService glossaryService;

    private UUID getCurrentUserId() {
        String userIdStr = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return UUID.fromString(userIdStr);
    }

    @Operation(
            summary = "查询个人词典（三组分好）",
            description = "返回 pending（待确认，badge 角标数据源）/ confirmed（已生效）/ dismissed（已忽略）三组。"
                    + "confirmed 项附 queryHitCount（提问命中）与 contentHitCount（近30天相关记录数）。"
                    + "兼容取分组中某一组：?group=pending|confirmed|dismissed。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @GetMapping
    public R<Object> list(
            @Parameter(description = "只取某一组（可空=返回三组分好对象）", example = "pending")
            @RequestParam(required = false) String group) {
        UUID userId = getCurrentUserId();
        if (group != null && !group.isBlank()) {
            GlossaryGroupVO vo = glossaryService.listGrouped(userId);
            List<UserTermVO> data = switch (group.trim().toLowerCase()) {
                case "pending" -> vo.getPending();
                case "confirmed" -> vo.getConfirmed();
                case "dismissed" -> vo.getDismissed();
                default -> throw new org.xianshen.mumirrorb.common.exception.BusinessException(
                        org.xianshen.mumirrorb.common.enums.ResultCode.PARAM_ERROR, "group 取值非法");
            };
            return R.ok("查询成功", data);
        }
        return R.ok("查询成功", glossaryService.listGrouped(userId));
    }

    @Operation(
            summary = "手动新增词条（教镜子一个词）",
            description = "手动新增直接 confirmed 生效。词条与该用户已有词条重复返回参数错误。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "新增成功"),
            @ApiResponse(responseCode = "400", description = "参数错误/词条重复"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @PostMapping
    public R<UserTermVO> create(@Valid @RequestBody GlossaryCreateDTO dto) {
        UUID userId = getCurrentUserId();
        return R.ok("词条已生效", glossaryService.create(userId, dto));
    }

    @Operation(
            summary = "编辑词条",
            description = "修改词条/别名/解释。confirmed 词编辑解释后刷新 last_confirmed_at（重新计时）。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "400", description = "参数错误/词条重复"),
            @ApiResponse(responseCode = "404", description = "词条不存在"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @PutMapping("/{id}")
    public R<UserTermVO> update(
            @Parameter(description = "词条ID", required = true, example = "1")
            @PathVariable Long id,
            @Valid @RequestBody GlossaryUpdateDTO dto) {
        UUID userId = getCurrentUserId();
        return R.ok("更新成功", glossaryService.update(id, dto, userId));
    }

    @Operation(
            summary = "删除词条",
            description = "物理删除（与 dismiss 不同：dismiss 沉底保留 30 天可复活，删除真删）。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "词条不存在"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @DeleteMapping("/{id}")
    public R<Void> delete(
            @Parameter(description = "词条ID", required = true, example = "1")
            @PathVariable Long id) {
        UUID userId = getCurrentUserId();
        glossaryService.delete(id, userId);
        return R.ok("删除成功", null);
    }

    @Operation(
            summary = "确认候选",
            description = "pending/dismissed → confirmed。update 候选可带新解释建议与合并别名："
                    + "body {\"description\": \"新解释（可选）\", \"aliases\": [\"要并入的别名（可选）\"]}。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "确认成功"),
            @ApiResponse(responseCode = "400", description = "状态机不合法（已 confirmed）"),
            @ApiResponse(responseCode = "404", description = "词条不存在"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @PostMapping("/{id}/confirm")
    public R<UserTermVO> confirm(
            @Parameter(description = "词条ID", required = true, example = "1")
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        UUID userId = getCurrentUserId();
        String description = body == null ? null : strOf(body.get("description"));
        @SuppressWarnings("unchecked")
        List<String> aliases = body == null ? null : (List<String>) body.get("aliases");
        return R.ok("词条已生效", glossaryService.confirm(id, userId, description, aliases));
    }

    @Operation(
            summary = "忽略候选/停用词条",
            description = "pending/confirmed → dismissed。不删行（30 天后可重新浮现），沉底展示。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "操作成功"),
            @ApiResponse(responseCode = "400", description = "状态机不合法（已 dismissed）"),
            @ApiResponse(responseCode = "404", description = "词条不存在"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @PostMapping("/{id}/dismiss")
    public R<UserTermVO> dismiss(
            @Parameter(description = "词条ID", required = true, example = "1")
            @PathVariable Long id) {
        UUID userId = getCurrentUserId();
        return R.ok("已忽略", glossaryService.dismiss(id, userId));
    }

    @Operation(
            summary = "手动触发候选抽取",
            description = "近 14 天语料（status='done' AND source='user'，fix-batch B2 收口）→ AI 抽取词条候选"
                    + "落 pending（懒人立即出候选）。Python 侧未上线时返回 0（不报错）。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "抽取完成（data.candidates=候选列表）"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @PostMapping("/extract")
    public R<Map<String, Object>> extract() {
        UUID userId = getCurrentUserId();
        // fix-batch C5：响应对齐 F 契约 {candidates:[...]}（原 {created:n} 数字口径废弃；
        // candidates = 本次新增 pending 候选的完整词条卡，前端直接渲染候选列表）
        List<UserTermVO> created = glossaryService.extractForUser(userId);
        return R.ok("抽取完成", Map.of("candidates", created));
    }

    private static String strOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

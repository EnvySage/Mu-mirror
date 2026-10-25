package org.xianshen.mumirrorb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.xianshen.mumirrorb.pojo.DTO.TodoCommandDTO;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.TodoChainVO;
import org.xianshen.mumirrorb.pojo.VO.TodoItemVO;
import org.xianshen.mumirrorb.pojo.VO.TodoSuggestionVO;
import org.xianshen.mumirrorb.service.TodoRegistryService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Existing todo routes plus explicit direct create/update submissions. Identity comes from authentication. */
@Tag(name = "待办登记", description = "跨日记待办状态跟踪：建议裁决与删除")
@RestController
@RequestMapping("/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoRegistryService todoRegistryService;

    @PostMapping
    public R<TodoItemVO> create(@RequestBody TodoCommandDTO body) {
        return R.ok(todoRegistryService.createDirect(getCurrentUserId(), body));
    }

    @PatchMapping("/{id}")
    public R<TodoItemVO> update(@PathVariable Long id, @RequestBody TodoCommandDTO body) {
        if (body.getExpectedTargetVersion() == null)
            throw new BusinessException(400, "expectedTargetVersion 必填");
        return R.ok(todoRegistryService.updateDirect(getCurrentUserId(), id, body.getExpectedTargetVersion(), body));
    }

    private UUID getCurrentUserId() {
        String userIdStr = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return UUID.fromString(userIdStr);
    }

    @Operation(
            summary = "pending 建议列表（侧栏角标+建议卡数据）",
            description = "机器判别产生的待办状态变更建议（pending 等裁决）。"
                    + "角标数字 = suggestions 数组长度。"
                    + "evidenceRecordId 可跳记录详情；确认时才落 evidence 关联（机器猜的不落库）。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @GetMapping("/pending-suggestions")
    public R<TodoSuggestionVO.SuggestionListVO> pendingSuggestions() {
        UUID userId = getCurrentUserId();
        List<TodoSuggestionVO.SuggestionCard> cards = todoRegistryService.pendingSuggestions(userId);
        return R.ok("查询成功", TodoSuggestionVO.SuggestionListVO.builder()
                .suggestions(cards).build());
    }

    @Operation(
            summary = "裁决建议（确认/忽略）",
            description = "action=confirmed：更新 registry 当前状态，记录 evidence 关联与建议确认；来源片段保持只读。"
                    + "status 可选三态（用户可改 LLM 建议，缺省按 suggested_status）。"
                    + "action=dismissed：建议行 dismissed 永久静默（同一证据不再提示），todo 不动，关联不落。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "裁决成功"),
            @ApiResponse(responseCode = "400", description = "action/status 非法或建议已处理过"),
            @ApiResponse(responseCode = "404", description = "建议不存在（含非本人）"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @PostMapping("/suggestions/{id}/resolve")
    public R<Void> resolve(
            @Parameter(description = "建议ID", required = true, example = "1")
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        UUID userId = getCurrentUserId();
        String action = body == null ? null : body.get("action");
        String status = body == null ? null : body.get("status");
        todoRegistryService.resolve(id, userId, action, status);
        return R.ok("confirmed".equalsIgnoreCase(action) ? "待办状态已更新" : "已忽略", null);
    }

    @Operation(
            summary = "待办登记列表（全部待办入口）",
            description = "全量登记行（新→旧），带关联日记数与 pending 建议数。"
                    + "orphan=true 表示原片段已被删除（行保留、不进判别注入清单）。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @GetMapping
    public R<TodoItemVO.TodoListVO> list() {
        UUID userId = getCurrentUserId();
        List<TodoItemVO> todos = todoRegistryService.listAll(userId);
        return R.ok("查询成功", TodoItemVO.TodoListVO.builder().todos(todos).build());
    }

    @Operation(
            summary = "未完成待办证据链（open-chain 聚合）",
            description = "只返回未完成待办（current_status != completed 且非 orphan），按登记时间新→旧。"
                    + "每链 = origin（登记原始片段，理论必有）+ evidence[]（用户背书确认的后续证据，"
                    + "按片段时刻升序，confirmedAt=背书时刻）+ pendingSuggestionCount。"
                    + "excerpt 取 COALESCE(segment,content) 截 60 字符；"
                    + "currentStatus 取 registry.current_status（待办状态类读取统一 registry 口径）。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @GetMapping("/open-chain")
    public R<TodoChainVO.ChainListVO> openChain() {
        UUID userId = getCurrentUserId();
        List<TodoChainVO> chains = todoRegistryService.listOpenChains(userId);
        return R.ok(TodoChainVO.ChainListVO.builder().chains(chains).build());
    }

    @Operation(
            summary = "删除待办（软删）",
            description = "明确删除：版本检查后软删 registry，并作废 pending 建议。来源片段保持只读。"
                    + "删除后所有视图（清单/注入/证据链/统计）不可见，不再产生新建议、不可改状态；"
                    + "原始记录保留，终态可追溯。已删除幂等返回成功。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "已删除（含幂等）"),
            @ApiResponse(responseCode = "404", description = "待办不存在（含非本人）"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @DeleteMapping("/{id}")
    public R<Void> delete(
            @Parameter(description = "登记ID", required = true, example = "1")
            @PathVariable Long id,
            @RequestParam(required = false) Long expectedVersion) {
        UUID userId = getCurrentUserId();
        if (expectedVersion == null) todoRegistryService.deleteTodo(id, userId);
        else todoRegistryService.deleteTodo(id, userId, expectedVersion);
        return R.ok("待办已删除", null);
    }
}

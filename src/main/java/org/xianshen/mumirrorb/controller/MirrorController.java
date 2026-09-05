package org.xianshen.mumirrorb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.xianshen.mumirrorb.pojo.DTO.ChatRequestDTO;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.ChatSessionVO;
import org.xianshen.mumirrorb.pojo.VO.MirrorProfileVO;
import org.xianshen.mumirrorb.service.ChatService;
import org.xianshen.mumirrorb.service.MirrorService;

import java.util.List;
import java.util.UUID;

/**
 * 镜子画像控制器（设计文档 6.5 / 十章）
 */
@Tag(name = "镜子画像", description = "画像快照查询与生成")
@RestController
@RequestMapping("/mirror")
@RequiredArgsConstructor
public class MirrorController {

    private final MirrorService mirrorService;
    private final ChatService chatService;

    private UUID getCurrentUserId() {
        String userIdStr = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return UUID.fromString(userIdStr);
    }

    /**
     * 查询最新画像快照
     *
     * 优先返回最新 manual 快照，无则返回最新 monthly。
     * 从未生成过画像时返回空 VO（id=null），前端引导生成。
     */
    @Operation(
            summary = "查询最新画像",
            description = "返回最新画像快照（优先 manual，其次 monthly），含漂移信息。从未生成时返回空对象。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功",
                    content = @Content(schema = @Schema(implementation = MirrorProfileVO.class))),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @GetMapping
    public R<MirrorProfileVO> getMirror() {
        UUID userId = getCurrentUserId();
        return R.ok("查询成功", mirrorService.getMirror(userId));
    }

    /**
     * 生成 manual 画像快照
     *
     * 阻塞数秒到数十秒（含五维统计 + LLM 生成 + Embedding），前端需 loading 态。
     */
    @Operation(
            summary = "生成画像",
            description = "五维统计 + 最近对话 → LLM 生成六维分析 → 存快照并向量化。阻塞接口，前端需 loading 态。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "生成成功",
                    content = @Content(schema = @Schema(implementation = MirrorProfileVO.class))),
            @ApiResponse(responseCode = "400", description = "未配置 AI 模型"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效"),
            @ApiResponse(responseCode = "500", description = "AI 服务不可达或生成失败")
    })
    @PostMapping("/generate")
    public R<MirrorProfileVO> generate() {
        UUID userId = getCurrentUserId();
        MirrorProfileVO vo = mirrorService.generate(userId);
        return R.ok("画像已生成", vo);
    }

    // ==================== 对话（设计文档 6.6，T-B-4） ====================

    /**
     * 对话（SSE 流式）
     *
     * <p>事件序列（event name / data 为 JSON 字符串）：</p>
     * <ul>
     *   <li>meta: {"sessionId":"...","route":"HYBRID"} —— 意图路由结果</li>
     *   <li>delta: {"content":"..."} —— 回答增量（逐块）</li>
     *   <li>sources: [{"record_id":1,"quote":"...","date":"2026-09-03"}] —— 来源追溯</li>
     *   <li>done: {"sessionId":"...","route":"...","fallback":false} —— 结束标志</li>
     *   <li>error: {"message":"暂时无法回答"} —— AI 失败兜底</li>
     * </ul>
     *
     * <p>sessionId 不传则创建新会话。检索为空时推送"没有找到相关记录"，
     * AI 失败推送"暂时无法回答"，两者均落库 assistant 消息。</p>
     */
    @Operation(
            summary = "对话（SSE 流式）",
            description = "ExtractIntent 四路路由 → 检索 → 流式 Chat 透传。事件：meta/delta/sources/done/error。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "SSE 流建立成功（结果以事件推送）"),
            @ApiResponse(responseCode = "400", description = "提问为空"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequestDTO dto) {
        UUID userId = getCurrentUserId();
        // 长超时：LLM 流式回答最长 60s（7.4），加上检索与网络余量
        SseEmitter emitter = new SseEmitter(120_000L);
        // 客户端断开/超时时优雅终结，避免 Tomcat 转发 /error 触发安全链异常噪音
        emitter.onCompletion(() -> { });
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> { });
        chatService.chat(userId, dto, emitter);
        return emitter;
    }

    /**
     * 会话列表（updated_at 倒序，裁决 #9；不含消息，历史走 /{id}）
     */
    @Operation(summary = "会话列表", description = "当前用户全部会话，按更新时间倒序。不含消息内容。")
    @GetMapping("/sessions")
    public R<List<ChatSessionVO>> sessions() {
        UUID userId = getCurrentUserId();
        return R.ok("查询成功", chatService.listSessions(userId));
    }

    /**
     * 会话历史（全部消息时间正序，assistant 消息带 sources）
     */
    @Operation(summary = "会话历史", description = "返回指定会话的全部消息（时间正序），assistant 消息含来源追溯。")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "404", description = "会话不存在")
    })
    @GetMapping("/sessions/{id}")
    public R<ChatSessionVO> sessionDetail(
            @Parameter(description = "会话ID", required = true)
            @PathVariable UUID id) {
        UUID userId = getCurrentUserId();
        return R.ok("查询成功", chatService.getSession(id, userId));
    }

    /**
     * 删除会话（消息级联删除）
     */
    @Operation(summary = "删除会话", description = "删除会话及其全部消息（级联）。")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "会话不存在")
    })
    @DeleteMapping("/sessions/{id}")
    public R<Void> deleteSession(
            @Parameter(description = "会话ID", required = true)
            @PathVariable UUID id) {
        UUID userId = getCurrentUserId();
        chatService.deleteSession(id, userId);
        return R.ok("会话已删除", null);
    }
}

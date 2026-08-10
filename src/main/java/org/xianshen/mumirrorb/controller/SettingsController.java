package org.xianshen.mumirrorb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.pojo.DTO.SettingsDTO;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.SettingsVO;
import org.xianshen.mumirrorb.service.SettingsService;

import java.util.UUID;

/**
 * 用户配置控制器
 *
 * <p>提供 AI 模型配置的管理功能，包括：</p>
 * <ul>
 *   <li>获取配置（API Key 脱敏返回）</li>
 *   <li>更新配置（API Key 加密存储）</li>
 *   <li>测试 AI 连接</li>
 *   <li>测试数据库连接</li>
 * </ul>
 *
 * <p><strong>配置说明：</strong></p>
 * <ul>
 *   <li>每个用户一条配置记录，不存在则自动创建</li>
 *   <li>API Key 加密存储，返回时脱敏（如 sk-***）</li>
 *   <li>配置更新后，下次 gRPC 调用时自动使用新配置</li>
 * </ul>
 */
@Tag(name = "用户配置", description = "AI 模型配置管理")
@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    /**
     * 获取当前登录用户的UUID
     */
    private UUID getCurrentUserId() {
        String userIdStr = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return UUID.fromString(userIdStr);
    }

    /**
     * 获取当前用户的配置
     *
     * <p>API Key 字段返回脱敏后的值（如 sk-***）。</p>
     * <p>如果用户没有配置记录，会自动创建一条空配置返回。</p>
     *
     * @return 用户配置
     */
    @Operation(
            summary = "获取用户配置",
            description = "获取当前用户的 AI 模型配置。API Key 返回脱敏值。如无配置会自动创建空配置。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @GetMapping
    public R<SettingsVO> getSettings() {
        UUID userId = getCurrentUserId();
        SettingsVO settings = settingsService.getSettings(userId);
        return R.ok("获取成功", settings);
    }

    /**
     * 更新当前用户的配置
     *
     * <p>只更新非 null 的字段（部分更新）。</p>
     * <p>API Key 传明文，后端加密后存储。</p>
     *
     * @param dto 更新数据
     * @return 更新后的配置
     */
    @Operation(
            summary = "更新用户配置",
            description = "更新当前用户的 AI 模型配置。只更新传入的字段，API Key 传明文后端加密存储。"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "400", description = "参数错误"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @PutMapping
    public R<SettingsVO> updateSettings(@Valid @RequestBody SettingsDTO dto) {
        UUID userId = getCurrentUserId();
        SettingsVO settings = settingsService.updateSettings(dto, userId);
        return R.ok("配置已更新", settings);
    }

    /**
     * 测试 AI 连接
     *
     * <p>使用当前用户的 AI 配置测试连接是否正常。</p>
     *
     * @return 测试结果
     */
    @Operation(
            summary = "测试 AI 连接",
            description = "使用当前用户的 AI 配置测试连接是否正常"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "测试成功"),
            @ApiResponse(responseCode = "400", description = "未配置 AI 或配置错误"),
            @ApiResponse(responseCode = "401", description = "未登录或 Token 无效")
    })
    @PostMapping("/test-ai")
    public R<String> testAiConnection() {
        UUID userId = getCurrentUserId();
        String result = settingsService.testAiConnection(userId);
        return R.ok(result);
    }

    /**
     * 测试数据库连接
     *
     * <p>测试当前数据库连接是否正常。</p>
     *
     * @return 测试结果
     */
    @Operation(
            summary = "测试数据库连接",
            description = "测试当前数据库连接是否正常"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "连接正常"),
            @ApiResponse(responseCode = "500", description = "连接失败")
    })
    @PostMapping("/test-db")
    public R<String> testDbConnection() {
        String result = settingsService.testDbConnection();
        return R.ok(result);
    }
}

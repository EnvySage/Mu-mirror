package org.xianshen.mumirrorb.service;

import org.xianshen.mumirrorb.pojo.DTO.SettingsDTO;
import org.xianshen.mumirrorb.pojo.VO.SettingsVO;

import java.util.UUID;

/**
 * 用户配置服务接口
 *
 * <p>提供用户 AI 模型配置的 CRUD 操作，包括：</p>
 * <ul>
 *   <li>获取配置（API Key 脱敏返回）</li>
 *   <li>更新配置（API Key 加密存储）</li>
 *   <li>测试 AI 连接</li>
 *   <li>测试数据库连接</li>
 * </ul>
 *
 * <p><strong>配置更新流程：</strong></p>
 * <pre>
 * 用户改配置 → Java 存 user_settings 表
 *   → gRPC 调用时从数据库读取配置，放入请求中
 *   → Python 完全无状态
 * </pre>
 */
public interface SettingsService {

    /**
     * 获取当前用户的配置
     *
     * <p>API Key 字段返回脱敏后的值（如 sk-***）。</p>
     *
     * @param userId 当前用户ID
     * @return 配置视图对象
     */
    SettingsVO getSettings(UUID userId);

    /**
     * 更新当前用户的配置
     *
     * <p>只更新非 null 的字段（部分更新）。</p>
     * <p>API Key 字段传明文，后端加密后存储。</p>
     *
     * @param dto    更新数据（只传需要修改的字段）
     * @param userId 当前用户ID
     * @return 更新后的配置视图对象
     */
    SettingsVO updateSettings(SettingsDTO dto, UUID userId);

    /**
     * 测试 AI 连接
     *
     * <p>使用当前用户的 AI 配置，发送一个简单的测试请求。</p>
     *
     * @param userId 当前用户ID
     * @return 测试结果描述
     */
    String testAiConnection(UUID userId);

    /**
     * 测试数据库连接
     *
     * <p>测试当前数据库连接是否正常。</p>
     *
     * @return 测试结果描述
     */
    String testDbConnection();
}

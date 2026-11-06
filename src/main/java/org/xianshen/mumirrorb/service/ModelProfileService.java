package org.xianshen.mumirrorb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.enums.ResultCode;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.mapper.ModelProfileMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserModelProfile;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.DTO.ModelConnectionTestDTO;
import org.xianshen.mumirrorb.pojo.DTO.ModelProfileDTO;
import org.xianshen.mumirrorb.pojo.VO.ModelProfileVO;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModelProfileService {
    private final ModelProfileMapper profiles;
    private final SettingsMapper settings;
    private final AiGrpcClient ai;

    @Transactional
    public List<ModelProfileVO> list(UUID userId) {
        UserSettings current = lockedSettings(userId);
        importCurrent(current);
        return ownedProfiles(userId).stream().map(p -> view(p, current)).toList();
    }

    @Transactional
    public ModelProfileVO create(UUID userId, ModelProfileDTO dto) {
        UserSettings current = lockedSettings(userId);
        importCurrent(current);
        if (blank(dto.getAiApiKey())) throw invalid("请填写 API Key");
        UserModelProfile profile = UserModelProfile.builder().id(UUID.randomUUID()).userId(userId)
                .createdAt(OffsetDateTime.now()).build();
        fill(profile, dto);
        profiles.insert(profile);
        return view(profile, current);
    }

    @Transactional
    public ModelProfileVO update(UUID userId, UUID id, ModelProfileDTO dto) {
        UserSettings current = lockedSettings(userId);
        UserModelProfile profile = owned(userId, id);
        fill(profile, dto);
        if (blank(profile.getAiApiKey())) throw invalid("请填写 API Key");
        profiles.updateById(profile);
        return view(profile, current);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        UserSettings current = lockedSettings(userId);
        UserModelProfile profile = owned(userId, id);
        if (id.equals(current.getActiveModelProfileId())) throw invalid("请先应用其他配置，再删除当前使用的配置");
        profiles.deleteById(profile.getId());
    }

    @Transactional
    public ModelProfileVO activate(UUID userId, UUID id) {
        UserSettings current = lockedSettings(userId);
        UserModelProfile profile = owned(userId, id);
        // Do not replace a usable effective configuration with a corrupt stored key.
        validateConnection(profile.getAiProvider(), profile.getAiProtocol(), profile.getAiModel(), profile.getAiBaseUrl());
        decryptKey(profile.getAiApiKey());
        settings.update(null, new LambdaUpdateWrapper<UserSettings>()
                .eq(UserSettings::getUserId, userId)
                .set(UserSettings::getActiveModelProfileId, id)
                .set(UserSettings::getAiProvider, profile.getAiProvider())
                .set(UserSettings::getAiProtocol, profile.getAiProtocol())
                .set(UserSettings::getAiBaseUrl, profile.getAiBaseUrl())
                .set(UserSettings::getAiModel, profile.getAiModel())
                .set(UserSettings::getAiApiKey, profile.getAiApiKey())
                .set(UserSettings::getUpdatedAt, OffsetDateTime.now()));
        current.setActiveModelProfileId(id);
        current.setAiProvider(profile.getAiProvider());
        current.setAiProtocol(profile.getAiProtocol());
        current.setAiBaseUrl(profile.getAiBaseUrl());
        current.setAiModel(profile.getAiModel());
        current.setAiApiKey(profile.getAiApiKey());
        return view(profile, current);
    }

    /** Reads only. Never saves a draft, imports a profile, or changes effective settings. */
    @Transactional(readOnly = true)
    public String testDraft(UUID userId, ModelConnectionTestDTO dto) {
        validateConnection(dto.getAiProvider(), dto.getAiProtocol(), dto.getAiModel(), dto.getAiBaseUrl());
        // Check ownership even when a replacement key was submitted.
        UserModelProfile profile = dto.getProfileId() == null ? null : owned(userId, dto.getProfileId());
        String key = dto.getAiApiKey();
        if (blank(key)) {
            if (profile != null) key = decryptKey(profile.getAiApiKey());
            else if (dto.isUseCurrentKey()) {
                UserSettings current = settings.selectOne(new LambdaQueryWrapper<UserSettings>()
                        .eq(UserSettings::getUserId, userId));
                key = decryptKey(current == null ? null : current.getAiApiKey());
            } else throw invalid("请填写 API Key，或选择已保存的配置");
        }
        CommonProto.LlmConfig config = CommonProto.LlmConfig.newBuilder()
                .setProvider(dto.getAiProvider().trim()).setProtocol("anthropic".equals(dto.getAiProtocol())
                        ? CommonProto.AiProtocol.ANTHROPIC : CommonProto.AiProtocol.OPENAI)
                .setModel(dto.getAiModel().trim()).setBaseUrl(normalizeUrl(dto.getAiBaseUrl()))
                .setApiKey(key.trim()).build();
        org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto.ClassifyResponse result;
        try {
            result = ai.testLlmConnection(userId, config);
        } catch (Exception e) {
            // Provider/gRPC errors may contain authorization headers or the submitted key.
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "大模型连接失败，请检查协议、地址、模型名称和 API Key，并确认 AI 服务已启动");
        }
        if (result == null || result.getSkip() || result.getItemsCount() == 0)
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "大模型已响应，但未返回有效的连接测试结果");
        return "大模型连接正常，草稿配置未保存或应用";
    }

    private UserSettings lockedSettings(UUID userId) {
        settings.ensureExists(userId);
        return settings.selectForUpdate(userId);
    }

    private List<UserModelProfile> ownedProfiles(UUID userId) {
        return profiles.selectList(new LambdaQueryWrapper<UserModelProfile>().eq(UserModelProfile::getUserId, userId)
                .orderByAsc(UserModelProfile::getCreatedAt).orderByAsc(UserModelProfile::getId));
    }

    private UserModelProfile owned(UUID userId, UUID id) {
        UserModelProfile profile = profiles.selectOne(new LambdaQueryWrapper<UserModelProfile>()
                .eq(UserModelProfile::getUserId, userId).eq(UserModelProfile::getId, id));
        if (profile == null) throw new BusinessException(404, "模型配置不存在");
        return profile;
    }

    private void importCurrent(UserSettings current) {
        if (blank(current.getAiProvider()) || blank(current.getAiModel()) || blank(current.getAiApiKey())
                || !ownedProfiles(current.getUserId()).isEmpty()) return;
        OffsetDateTime now = OffsetDateTime.now();
        UserModelProfile imported = UserModelProfile.builder().id(UUID.randomUUID()).userId(current.getUserId())
                .name("当前配置").aiProvider(current.getAiProvider()).aiProtocol(current.getAiProtocol())
                .aiBaseUrl(current.getAiBaseUrl()).aiModel(current.getAiModel()).aiApiKey(current.getAiApiKey())
                .createdAt(now).updatedAt(now).build();
        profiles.insert(imported);
        settings.update(null, new LambdaUpdateWrapper<UserSettings>().eq(UserSettings::getUserId, current.getUserId())
                .set(UserSettings::getActiveModelProfileId, imported.getId()));
        current.setActiveModelProfileId(imported.getId());
    }

    private void fill(UserModelProfile profile, ModelProfileDTO dto) {
        if (blank(dto.getName()) || dto.getName().trim().length() > 80) throw invalid("请输入 1 至 80 字的配置名称");
        validateConnection(dto.getAiProvider(), dto.getAiProtocol(), dto.getAiModel(), dto.getAiBaseUrl());
        profile.setName(dto.getName().trim());
        profile.setAiProvider(dto.getAiProvider().trim());
        profile.setAiProtocol(dto.getAiProtocol());
        profile.setAiBaseUrl(normalizeUrl(dto.getAiBaseUrl()));
        profile.setAiModel(dto.getAiModel().trim());
        if (!blank(dto.getAiApiKey())) profile.setAiApiKey(CryptoUtils.encrypt(dto.getAiApiKey().trim()));
        profile.setUpdatedAt(OffsetDateTime.now());
    }

    private void validateConnection(String provider, String protocol, String model, String url) {
        if (blank(provider) || provider.trim().length() > 50) throw invalid("请填写提供商（最多 50 字）");
        if (!"openai".equals(protocol) && !"anthropic".equals(protocol)) throw invalid("请选择 OpenAI 或 Anthropic 协议");
        if (blank(model) || model.trim().length() > 100) throw invalid("请填写模型名称（最多 100 字）");
        if (blank(url)) throw invalid("请填写 API 地址");
        if (!blank(url)) {
            try {
                URI uri = URI.create(url.trim());
                if (url.length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getFragment() != null || uri.getRawQuery() != null
                        || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw invalid("API 地址需为有效的 HTTP 或 HTTPS 地址，且不包含密码、查询参数或片段"); }
        }
    }

    private String decryptKey(String encrypted) {
        if (blank(encrypted)) throw invalid("请填写 API Key");
        try {
            String key = CryptoUtils.decrypt(encrypted);
            if (blank(key)) throw invalid("请填写 API Key");
            return key;
        } catch (RuntimeException e) { throw invalid("已保存的 API Key 无法使用，请重新录入"); }
    }

    private ModelProfileVO view(UserModelProfile profile, UserSettings current) {
        boolean reentry = false;
        if (!blank(profile.getAiApiKey())) {
            try { CryptoUtils.decrypt(profile.getAiApiKey()); }
            catch (RuntimeException e) { reentry = true; }
        }
        boolean active = profile.getId().equals(current.getActiveModelProfileId());
        boolean matches = Objects.equals(profile.getAiProvider(), current.getAiProvider())
                && Objects.equals(profile.getAiProtocol(), current.getAiProtocol())
                && Objects.equals(profile.getAiBaseUrl(), current.getAiBaseUrl())
                && Objects.equals(profile.getAiModel(), current.getAiModel())
                && Objects.equals(profile.getAiApiKey(), current.getAiApiKey());
        return ModelProfileVO.builder().id(profile.getId()).name(profile.getName()).aiProvider(profile.getAiProvider())
                .aiProtocol(profile.getAiProtocol()).aiBaseUrl(profile.getAiBaseUrl()).aiModel(profile.getAiModel())
                .hasApiKey(!blank(profile.getAiApiKey())).apiKeyNeedsReentry(reentry)
                .active(active).unappliedChanges(active && !matches)
                .createdAt(profile.getCreatedAt()).updatedAt(profile.getUpdatedAt()).build();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalizeUrl(String value) { return value == null ? "" : value.trim(); }
    private static BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
}

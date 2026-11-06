package org.xianshen.mumirrorb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.ModelProfileMapper;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserModelProfile;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.DTO.ModelConnectionTestDTO;
import org.xianshen.mumirrorb.pojo.DTO.ModelProfileDTO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModelProfileServiceTest {
    @BeforeAll static void mybatisMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "unit"),
                UserSettings.class);
    }
    final ModelProfileMapper profiles = mock(ModelProfileMapper.class);
    final SettingsMapper settings = mock(SettingsMapper.class);
    final AiGrpcClient ai = mock(AiGrpcClient.class);
    final ModelProfileService service = new ModelProfileService(profiles, settings, ai);
    final UUID user = UUID.randomUUID();
    UserSettings current;
    UserModelProfile saved;

    @BeforeEach void setup() {
        current = UserSettings.builder().userId(user).aiProvider("current-provider").aiProtocol("anthropic")
                .aiModel("current-model").aiApiKey(CryptoUtils.encrypt("current-secret")).build();
        saved = UserModelProfile.builder().id(UUID.randomUUID()).userId(user).name("已保存")
                .aiProvider("saved-provider").aiProtocol("openai").aiModel("saved-model")
                .aiBaseUrl("https://example.test/v1").aiApiKey(CryptoUtils.encrypt("saved-secret")).build();
        when(settings.selectForUpdate(user)).thenReturn(current);
        when(profiles.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(saved));
        when(profiles.selectOne(any(LambdaQueryWrapper.class))).thenReturn(saved);
        when(ai.testLlmConnection(eq(user), any(CommonProto.LlmConfig.class)))
                .thenReturn(RecordProcessorProto.ClassifyResponse.newBuilder().addItems(
                        RecordProcessorProto.ClassifyItem.newBuilder().setTitle("test")).build());
    }

    ModelProfileDTO form() {
        var dto = new ModelProfileDTO(); dto.setName("第二模型"); dto.setAiProvider("new-provider");
        dto.setAiProtocol("openai"); dto.setAiModel("new-model"); dto.setAiBaseUrl("https://draft.test/v1");
        return dto;
    }
    ModelConnectionTestDTO draft() {
        var dto = new ModelConnectionTestDTO(); dto.setAiProvider("draft-provider"); dto.setAiProtocol("openai");
        dto.setAiModel("draft-model"); dto.setAiBaseUrl("https://draft.test/v1"); return dto;
    }

    @Test void createEncryptsTheKeyAndNeverActivatesOrReturnsSecrets() throws Exception {
        var dto = form(); dto.setAiApiKey("fresh-secret");
        var view = service.create(user, dto);
        var capture = ArgumentCaptor.forClass(UserModelProfile.class);
        verify(profiles).insert(capture.capture());
        assertEquals("fresh-secret", CryptoUtils.decrypt(capture.getValue().getAiApiKey()));
        assertNotEquals("fresh-secret", capture.getValue().getAiApiKey());
        assertFalse(view.isActive()); assertTrue(view.isHasApiKey());
        var json = new ObjectMapper().findAndRegisterModules().writeValueAsString(view);
        assertFalse(json.contains("fresh-secret")); assertFalse(json.contains("\"aiApiKey\""));
        assertFalse(json.contains(capture.getValue().getAiApiKey()));
        verify(settings, never()).update(any(), any());
    }

    @Test void blankKeyEditRetainsCiphertextAndRequiresExplicitReapply() {
        current.setActiveModelProfileId(saved.getId());
        var ciphertext = saved.getAiApiKey(); var dto = form(); dto.setAiApiKey("  ");
        var view = service.update(user, saved.getId(), dto);
        assertEquals(ciphertext, saved.getAiApiKey());
        assertEquals("current-model", current.getAiModel());
        assertTrue(view.isActive()); assertTrue(view.isUnappliedChanges());
        verify(settings, never()).update(any(), any());
    }

    @Test void lazyImportCopiesCiphertextWithoutChangingEffectiveConfiguration() {
        when(profiles.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        String ciphertext = current.getAiApiKey();
        service.list(user);
        var capture = ArgumentCaptor.forClass(UserModelProfile.class); verify(profiles).insert(capture.capture());
        assertEquals(ciphertext, capture.getValue().getAiApiKey()); assertEquals("当前配置", capture.getValue().getName());
        assertEquals(capture.getValue().getId(), current.getActiveModelProfileId());
        assertEquals("current-model", current.getAiModel());
        verify(settings).selectForUpdate(user);
    }

    @Test void draftUsesSubmittedConfigAndOwnedSavedKeyWithoutAnyWrite() {
        var dto = draft(); dto.setProfileId(saved.getId()); dto.setAiApiKey("");
        assertTrue(service.testDraft(user, dto).contains("连接正常"));
        var capture = ArgumentCaptor.forClass(CommonProto.LlmConfig.class);
        verify(ai).testLlmConnection(eq(user), capture.capture());
        assertEquals("draft-model", capture.getValue().getModel());
        assertEquals("draft-provider", capture.getValue().getProvider());
        assertEquals("https://draft.test/v1", capture.getValue().getBaseUrl());
        assertEquals("saved-secret", capture.getValue().getApiKey());
        verify(settings, never()).ensureExists(any()); verify(settings, never()).update(any(), any());
        verify(profiles, never()).insert(any(UserModelProfile.class)); verify(profiles, never()).updateById(any(UserModelProfile.class));
    }

    @Test void draftWithReplacementKeyStillRejectsAnUnownedProfile() {
        when(profiles.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        var dto = draft(); dto.setProfileId(UUID.randomUUID()); dto.setAiApiKey("replacement");
        assertEquals(404, assertThrows(BusinessException.class, () -> service.testDraft(user, dto)).getCode());
        verifyNoInteractions(ai);
    }

    @Test void draftWithoutProfileDoesNotSilentlyReuseCurrentKey() {
        assertThrows(BusinessException.class, () -> service.testDraft(user, draft()));
        verifyNoInteractions(ai);
        var dto = draft(); dto.setUseCurrentKey(true);
        when(settings.selectOne(any(LambdaQueryWrapper.class))).thenReturn(current);
        service.testDraft(user, dto);
        verify(ai).testLlmConnection(eq(user), argThat(c -> c.getApiKey().equals("current-secret")));
    }

    @Test void draftFailureDoesNotExposeProviderErrorsContainingSecrets() {
        when(ai.testLlmConnection(eq(user), any(CommonProto.LlmConfig.class)))
                .thenThrow(new RuntimeException("Authorization: Bearer secret-value"));
        var dto = draft(); dto.setAiApiKey("secret-value");
        var error = assertThrows(BusinessException.class, () -> service.testDraft(user, dto));
        assertFalse(error.getMessage().contains("secret-value"));
        verify(settings, never()).update(any(), any());
    }

    @Test void corruptSavedKeyIsReportedAndCannotActivate() {
        saved.setAiApiKey("corrupt");
        assertTrue(service.list(user).get(0).isApiKeyNeedsReentry());
        assertThrows(BusinessException.class, () -> service.activate(user, saved.getId()));
        verify(settings, never()).update(any(), any());
    }

    @Test void deletingCurrentProfileIsRejected() {
        current.setActiveModelProfileId(saved.getId());
        assertThrows(BusinessException.class, () -> service.delete(user, saved.getId()));
        verify(profiles, never()).deleteById(any(UUID.class));
    }

    @Test void activationCopiesTheEffectiveLlmAndReturnsAppliedMarker() {
        var view = service.activate(user, saved.getId());
        assertTrue(view.isActive()); assertFalse(view.isUnappliedChanges());
        assertEquals("saved-model", current.getAiModel());
        var capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(settings).update(isNull(), capture.capture());
        assertTrue(capture.getValue().getSqlSet().contains("active_model_profile_id"));
        assertFalse(capture.getValue().getSqlSet().contains("embedding"));
    }

    @Test void invalidProtocolOrEmbeddedCredentialsAreRejectedBeforeCallingProvider() {
        var dto = draft(); dto.setAiApiKey("key"); dto.setAiProtocol("unsupported");
        assertThrows(BusinessException.class, () -> service.testDraft(user, dto));
        dto.setAiProtocol("openai"); dto.setAiBaseUrl("https://user:password@example.test/v1");
        assertThrows(BusinessException.class, () -> service.testDraft(user, dto));
        verifyNoInteractions(ai);
    }
}

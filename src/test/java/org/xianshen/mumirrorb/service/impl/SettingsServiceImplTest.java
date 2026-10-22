package org.xianshen.mumirrorb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.xianshen.mumirrorb.common.utils.CryptoUtils;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.mapper.SettingsMapper;
import org.xianshen.mumirrorb.pojo.DO.UserSettings;
import org.xianshen.mumirrorb.pojo.VO.SettingsVO;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettingsServiceImplTest {

    @Test
    void getSettingsKeepsOtherConfigurationWhenSavedKeysCannotBeDecrypted() {
        SettingsMapper settingsMapper = mock(SettingsMapper.class);
        AiGrpcClient aiGrpcClient = mock(AiGrpcClient.class);
        UUID userId = UUID.randomUUID();
        UserSettings settings = UserSettings.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .aiProvider("mimo")
                .aiProtocol("anthropic")
                .aiApiKey("not-valid-ciphertext")
                .aiBaseUrl("https://ai.example.test")
                .aiModel("mimo-v2.6-flash")
                .embeddingSource("api")
                .embeddingApiKey("also-not-valid-ciphertext")
                .embeddingModel("qwen3.7-text-embedding")
                .defaultRecordMode("ONLY_SAVE")
                .build();
        when(settingsMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(settings);

        SettingsVO result = new SettingsServiceImpl(settingsMapper, aiGrpcClient).getSettings(userId);

        assertEquals("mimo", result.getAiProvider());
        assertEquals("mimo-v2.6-flash", result.getAiModel());
        assertEquals("https://ai.example.test", result.getAiBaseUrl());
        assertNull(result.getAiApiKey());
        assertTrue(result.isAiApiKeyNeedsReentry());
        assertEquals("qwen3.7-text-embedding", result.getEmbeddingModel());
        assertNull(result.getEmbeddingApiKey());
        assertTrue(result.isEmbeddingApiKeyNeedsReentry());
    }

    @Test
    void testAiConnectionCallsTheConfiguredLlmInsteadOfEmbeddingProbe() {
        SettingsMapper settingsMapper = mock(SettingsMapper.class);
        AiGrpcClient aiGrpcClient = mock(AiGrpcClient.class);
        UUID userId = UUID.randomUUID();
        UserSettings settings = UserSettings.builder()
                .userId(userId)
                .aiProvider("mimo")
                .aiProtocol("anthropic")
                .aiApiKey(CryptoUtils.encrypt("test-key"))
                .aiModel("mimo-v2.6-flash")
                .build();
        when(settingsMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(settings);
        when(aiGrpcClient.testLlmConnection(userId)).thenReturn(
                RecordProcessorProto.ClassifyResponse.newBuilder()
                        .addItems(RecordProcessorProto.ClassifyItem.newBuilder().setTitle("连接测试"))
                        .build());

        String result = new SettingsServiceImpl(settingsMapper, aiGrpcClient).testAiConnection(userId);

        assertTrue(result.contains("大模型连接正常"));
        verify(aiGrpcClient).testLlmConnection(userId);
        verify(aiGrpcClient, never()).getModelInfo(userId);
    }
}

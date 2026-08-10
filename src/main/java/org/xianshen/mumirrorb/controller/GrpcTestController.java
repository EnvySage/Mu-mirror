package org.xianshen.mumirrorb.controller;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.grpc.AiGrpcClient;
import org.xianshen.mumirrorb.grpc.gen.EmbeddingProto;
import org.xianshen.mumirrorb.grpc.gen.RecordProcessorProto;
import org.xianshen.mumirrorb.pojo.R;

import java.util.HashMap;
import java.util.Map;

/**
 * gRPC 测试端点（仅开发环境使用）
 *
 * 用于测试 Java → Python AI 服务的 gRPC 连接是否正常。
 * 测试完成后应删除或加权限控制。
 */
@Slf4j
@RestController
@RequestMapping("/grpc-test")
@RequiredArgsConstructor
public class GrpcTestController {

    private final AiGrpcClient aiGrpcClient;

    /**
     * 测试 Classify（AI 分类）
     *
     * POST /api/grpc-test/classify
     * Body: { "content": "今天学了Spring Security，感觉有点难" }
     */
    @PostMapping("/classify")
    public R<Map<String, Object>> testClassify(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return R.fail(400, "content 不能为空");
        }

        try {
            RecordProcessorProto.ClassifyResponse response = aiGrpcClient.classify(content);

            Map<String, Object> result = new HashMap<>();
            result.put("skip", response.getSkip());
            result.put("skipReason", response.getSkipReason());
            result.put("title", response.getTitle());
            result.put("summary", response.getSummary());
            result.put("contentType", response.getContentType().name());
            result.put("moods", response.getMoodsList().stream().map(Enum::name).toList());
            result.put("status", response.getStatus().name());
            result.put("keywords", response.getKeywordsList());

            return R.ok(result);
        } catch (StatusRuntimeException e) {
            log.error("gRPC Classify 失败", e);
            return R.fail(503, "AI 服务调用失败: " + e.getStatus().getDescription());
        }
    }

    /**
     * 测试 Embed（向量化）
     *
     * POST /api/grpc-test/embed
     * Body: { "text": "今天学习了Spring Security" }
     */
    @PostMapping("/embed")
    public R<Map<String, Object>> testEmbed(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return R.fail(400, "text 不能为空");
        }

        try {
            EmbeddingProto.EmbedResponse response = aiGrpcClient.embed(text);

            Map<String, Object> result = new HashMap<>();
            result.put("dimension", response.getDimension());
            result.put("modelName", response.getModelName());
            result.put("vectorPreview", response.getVectorList().subList(0, Math.min(5, response.getVectorList().size())));
            result.put("vectorSize", response.getVectorList().size());

            return R.ok(result);
        } catch (StatusRuntimeException e) {
            log.error("gRPC Embed 失败", e);
            return R.fail(503, "AI 服务调用失败: " + e.getStatus().getDescription());
        }
    }

    /**
     * 测试 GetModelInfo（模型信息）
     *
     * GET /api/grpc-test/model-info
     */
    @GetMapping("/model-info")
    public R<Map<String, Object>> testModelInfo() {
        try {
            EmbeddingProto.ModelInfoResponse response = aiGrpcClient.getModelInfo();

            Map<String, Object> result = new HashMap<>();
            result.put("modelName", response.getModelName());
            result.put("source", response.getSource());
            result.put("dimension", response.getDimension());
            result.put("available", response.getAvailable());

            return R.ok(result);
        } catch (StatusRuntimeException e) {
            log.error("gRPC GetModelInfo 失败", e);
            return R.fail(503, "AI 服务调用失败: " + e.getStatus().getDescription());
        }
    }
}

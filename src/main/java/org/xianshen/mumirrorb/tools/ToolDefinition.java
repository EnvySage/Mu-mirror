package org.xianshen.mumirrorb.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具定义（注册表单一定真源；proto ToolSpec 的 Java 侧形态）
 *
 * <p>name/description/args_schema 三元组随 PlanToolsRequest.tools 传给 Python（进 prompt），
 * Python 不硬编码工具清单。</p>
 *
 * @param name        工具名
 * @param description 功能描述（进 prompt）
 * @param argsSchema  参数说明（进 prompt，简化 JSON 文档）
 */
public record ToolDefinition(String name, String description, String argsSchema) {

    /**
     * → proto ToolSpec（定义在 common.proto）
     */
    public org.xianshen.mumirrorb.grpc.gen.CommonProto.ToolSpec toProto() {
        return org.xianshen.mumirrorb.grpc.gen.CommonProto.ToolSpec.newBuilder()
                .setName(name)
                .setDescription(description)
                .setArgsSchema(argsSchema)
                .build();
    }

    /**
     * → args JSON 文档（prompt 用）
     */
    public Map<String, Object> toPromptMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", name);
        m.put("description", description);
        m.put("args", argsSchema);
        return m;
    }
}

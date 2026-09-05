package org.xianshen.mumirrorb.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具注册表（toolcalling-vault-design.md 第 2 节：单一定真源）
 *
 * <p>所有 {@link ToolExecutor} Bean 自动收集；PlanTools 组装 ToolSpec 快照传 Python，
 * Executor 按 name 分发执行。未知工具名 → 跳过该步（宁可少用工具不可错用）。</p>
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, ToolExecutor> executors;

    public ToolRegistry(List<ToolExecutor> executorList) {
        this.executors = executorList.stream()
                .collect(Collectors.toMap(ToolExecutor::name, e -> e, (a, b) -> a, LinkedHashMap::new));
        log.info("ToolRegistry 注册 {} 只工具: {}", executors.size(), executors.keySet());
    }

    /**
     * 注册表快照（→ PlanToolsRequest.tools）
     */
    public List<org.xianshen.mumirrorb.grpc.gen.CommonProto.ToolSpec> toProtoSpecs() {
        return executors.values().stream()
                .map(ToolExecutor::definition)
                .map(ToolDefinition::toProto)
                .toList();
    }

    /**
     * 分发执行（未知工具返回 empty）
     */
    public java.util.Optional<ToolExecutor> get(String name) {
        return java.util.Optional.ofNullable(executors.get(name));
    }

    public java.util.Set<String> names() {
        return executors.keySet();
    }
}

package org.xianshen.mumirrorb.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.action.*;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.mapper.ActionProposalMapper;
import org.xianshen.mumirrorb.pojo.DO.ActionProposal;
import org.xianshen.mumirrorb.pojo.DTO.*;
import org.xianshen.mumirrorb.pojo.VO.ActionProposalVO;
import org.xianshen.mumirrorb.service.ActionProposalService;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ActionProposalServiceImpl implements ActionProposalService {
    private final ActionProposalMapper mapper;
    private final Map<String, ActionHandler> handlers;
    private static final ConfirmationPermit CONFIRMATION_PERMIT = new ConfirmationPermit();

    /** Only this enclosing service can construct a permit. It is never serialized or returned. */
    public static final class ConfirmationPermit { private ConfirmationPermit() {} }

    public ActionProposalServiceImpl(ActionProposalMapper mapper, List<ActionHandler> handlers) {
        this.mapper = mapper;
        Map<String, ActionHandler> registry = new HashMap<>();
        for (ActionHandler handler : handlers) {
            for (String kind : handler.kinds()) {
                text(kind, "kind", 100);
                if (registry.putIfAbsent(kind, handler) != null)
                    throw new IllegalArgumentException("Duplicate action kind: " + kind);
            }
        }
        this.handlers = Map.copyOf(registry);
    }

    @Override
    @Transactional
    public ActionProposalVO prepare(UUID userId, ActionPrepareDTO body) {
        user(userId);
        if (body == null) throw invalid("缺少候选内容");
        text(body.getPrepareKey(), "prepareKey", 240);
        // Check the durable key before revalidating changed targets: cancellation is permanent.
        ActionProposal existing = mapper.findByPrepareKey(userId, body.getPrepareKey());
        if (existing != null) return ActionProposalVO.from(existing);
        ActionHandler handler = handler(body.getKind());
        payload(body.getPayload());
        source(userId, body.getSourceSessionId(), body.getSourceMessageId());
        JsonNode preview = preview(handler, userId, body.getKind(), body.getPayload());
        ActionProposal proposal = new ActionProposal();
        proposal.setId(UUID.randomUUID());
        proposal.setUserId(userId);
        proposal.setKind(body.getKind());
        proposal.setPrepareKey(body.getPrepareKey());
        proposal.setState("PENDING");
        proposal.setVersion(1);
        proposal.setPreparationStatus("READY");
        proposal.setPayload(body.getPayload().deepCopy());
        proposal.setPreview(preview);
        proposal.setTargetVersions(preview.get("targetVersions").deepCopy());
        proposal.setSourceSessionId(body.getSourceSessionId());
        proposal.setSourceMessageId(body.getSourceMessageId());
        proposal.setCreatedAt(OffsetDateTime.now());
        proposal.setUpdatedAt(proposal.getCreatedAt());
        if (mapper.insertIfAbsent(proposal) == 0) {
            // PostgreSQL READ COMMITTED sees the concurrent winner after ON CONFLICT waits.
            ActionProposal winner = mapper.findByPrepareKey(userId, body.getPrepareKey());
            if (winner == null) throw new IllegalStateException("Preparation conflict without durable card");
            return ActionProposalVO.from(winner);
        }
        return ActionProposalVO.from(proposal);
    }

    @Override
    @Transactional(noRollbackFor = ActionConflictException.class)
    public ActionProposalVO edit(UUID userId, UUID actionId, ActionEditDTO body) {
        ActionProposal proposal = owned(userId, actionId, true);
        if (body == null) throw invalid("缺少修改内容");
        pending(proposal, body.getExpectedVersion());
        ready(proposal);
        payload(body.getPayload());
        JsonNode preview = preview(handler(proposal.getKind()), userId, proposal.getKind(), body.getPayload());
        int version = proposal.getVersion();
        proposal.setPayload(body.getPayload().deepCopy());
        proposal.setPreview(preview);
        proposal.setTargetVersions(preview.get("targetVersions").deepCopy());
        save(proposal, version);
        return ActionProposalVO.from(proposal);
    }

    @Override
    @Transactional(noRollbackFor = ActionConflictException.class)
    public ActionProposalVO confirm(UUID userId, UUID actionId, ActionConfirmDTO body) {
        ActionProposal proposal = owned(userId, actionId, true);
        if (body == null) throw invalid("缺少确认内容");
        text(body.getRequestId(), "requestId", 240);
        version(body.getExpectedVersion());
        // The action row, not requestId, is the execution gate for every retry/device.
        if (Set.of("EXECUTING", "SUCCEEDED", "FAILED").contains(proposal.getState()))
            return ActionProposalVO.from(proposal);
        pending(proposal, body.getExpectedVersion());
        ready(proposal);
        ActionHandler handler = handler(proposal.getKind());
        JsonNode current = preview(handler, userId, proposal.getKind(), proposal.getPayload());
        if (!proposal.getTargetVersions().equals(current.get("targetVersions"))) {
            int version = proposal.getVersion();
            proposal.setState("STALE");
            proposal.setErrorCode("TARGET_VERSION_CHANGED");
            proposal.setPreview(current);
            save(proposal, version);
            throw new ActionConflictException("目标已变化，请查看最新信息并重新准备", proposal);
        }
        int version = proposal.getVersion();
        proposal.setState("EXECUTING");
        proposal.setRequestId(body.getRequestId());
        proposal.setExecutionVersion(version + 1);
        save(proposal, version);
        // Exceptions roll back BOTH domain SQL and proposal; no success is fabricated.
        ActionExecution execution = Objects.requireNonNull(handler.execute(
                AuthorizedAction.issue(CONFIRMATION_PERMIT, proposal)), "handler execution");
        version = proposal.getVersion();
        proposal.setState(execution.state());
        proposal.setResult(execution.result().deepCopy());
        proposal.setErrorCode(execution.errorCode());
        save(proposal, version);
        return ActionProposalVO.from(proposal);
    }

    @Override
    @Transactional(noRollbackFor = ActionConflictException.class)
    public ActionProposalVO cancel(UUID userId, UUID actionId, int expectedVersion) {
        ActionProposal proposal = owned(userId, actionId, true);
        if ("CANCELLED".equals(proposal.getState())) return ActionProposalVO.from(proposal);
        pending(proposal, expectedVersion);
        int version = proposal.getVersion();
        proposal.setState("CANCELLED");
        save(proposal, version);
        return ActionProposalVO.from(proposal);
    }

    @Override
    public ActionProposalVO get(UUID userId, UUID actionId) { return ActionProposalVO.from(owned(userId, actionId, false)); }

    @Override
    public List<ActionProposalVO> listForSession(UUID userId, UUID sessionId) {
        user(userId);
        if (sessionId == null) throw invalid("缺少 sessionId");
        source(userId, sessionId, null);
        return mapper.listForSession(userId, sessionId).stream().map(ActionProposalVO::from).toList();
    }

    @Override
    @Transactional
    public void completeAsync(UUID actionId, int executionVersion, JsonNode result) {
        if (actionId == null || executionVersion < 1) throw invalid("无效的执行标识");
        ActionProposal proposal = mapper.lockForCompletion(actionId);
        // Old/later duplicate worker callbacks cannot overwrite cancellation or a terminal result.
        if (proposal == null || !"EXECUTING".equals(proposal.getState())
                || !Objects.equals(proposal.getExecutionVersion(), executionVersion)) return;
        payload(result);
        String state = result.path("state").asText("SUCCEEDED");
        ActionExecution execution = new ActionExecution(state, result, result.path("errorCode").asText(null));
        if ("EXECUTING".equals(state)) throw invalid("后台完成结果必须为终态");
        int version = proposal.getVersion();
        proposal.setState(state);
        ObjectNode actual = (ObjectNode) execution.result().deepCopy();
        if ("SAVED_ONLY_FALLBACK".equals(actual.path("outcome").asText()))
            actual.put("message", "已仅保存，未加入记忆");
        proposal.setResult(actual);
        proposal.setErrorCode(execution.errorCode());
        save(proposal, version);
    }

    private ActionProposal owned(UUID userId, UUID actionId, boolean lock) {
        user(userId);
        if (actionId == null) throw invalid("缺少操作卡 ID");
        ActionProposal proposal = lock ? mapper.lockOwned(userId, actionId) : mapper.findOwned(userId, actionId);
        if (proposal == null || !userId.equals(proposal.getUserId())) throw new BusinessException(4041, "操作卡不存在");
        return proposal;
    }

    private void source(UUID userId, UUID sessionId, Long messageId) {
        if (messageId != null && sessionId == null) throw invalid("消息来源必须包含会话");
        if (sessionId != null && !mapper.sessionOwned(userId, sessionId)) throw new BusinessException(4041, "会话不存在");
        if (messageId != null && !mapper.sourceMessageOwned(userId, sessionId, messageId))
            throw new BusinessException(4041, "用户消息不存在");
    }

    private ActionHandler handler(String kind) {
        if (kind == null || !handlers.containsKey(kind)) throw invalid("不支持的操作类型");
        return handlers.get(kind);
    }

    private JsonNode preview(ActionHandler handler, UUID userId, String kind, JsonNode payload) {
        JsonNode preview = handler.validateAndPreview(userId, kind, payload.deepCopy());
        if (preview == null || !preview.isObject() || !preview.path("targetVersions").isObject())
            throw new IllegalStateException("Action preview requires server-derived targetVersions");
        return preview.deepCopy();
    }

    private void pending(ActionProposal proposal, Integer expectedVersion) {
        version(expectedVersion);
        if (expectedVersion != proposal.getVersion()) throw new ActionConflictException("操作卡已更新，请重新查看后确认", proposal);
        if (!"PENDING".equals(proposal.getState())) throw new ActionConflictException("操作卡已不可修改或确认", proposal);
    }

    private void ready(ActionProposal proposal) {
        if (!"READY".equals(proposal.getPreparationStatus())) throw new ActionConflictException("候选尚未准备完成", proposal);
    }

    private void save(ActionProposal proposal, int expectedVersion) {
        proposal.setVersion(expectedVersion + 1);
        proposal.setUpdatedAt(OffsetDateTime.now());
        if (mapper.save(proposal, expectedVersion) != 1) throw new IllegalStateException("Action version update lost its lock");
    }

    private static void user(UUID id) { if (id == null) throw new BusinessException(401, "请先登录"); }
    private static void version(Integer value) { if (value == null || value < 1) throw invalid("expectedVersion 必须为正整数"); }
    private static void payload(JsonNode node) { if (node == null || !node.isObject()) throw invalid("内容必须为 JSON 对象"); }
    private static void text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw invalid(field + " 不合法");
    }
    private static BusinessException invalid(String message) { return new BusinessException(400, message); }
}

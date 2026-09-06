package org.xianshen.mumirrorb.service;

import org.springframework.web.multipart.MultipartFile;
import org.xianshen.mumirrorb.pojo.VO.VaultItemVO;

import java.util.List;
import java.util.UUID;

/**
 * 用户资产保管服务（vault，toolcalling-vault-design.md 第 3 节）
 *
 * <p>核心裁决：Postgres BYTEA 直存；SHA-256 去重；magic bytes 校验；全接口 ownership 404；
 * 三层检索漏斗（精确→语义→全文）；三档消化管道隔离。</p>
 */
public interface VaultService {

    /**
     * 上传文件（显式动作免确认）
     *
     * @return 落库后的资产 VO（digest 异步启动，状态 pending→done/skipped/failed）
     */
    VaultItemVO upload(UUID userId, MultipartFile file, String description) throws java.io.IOException;

    /**
     * 资产列表（我的资产页，时间倒序；含配额）
     */
    List<VaultItemVO> list(UUID userId, String typeFilter);

    /**
     * 下载本体（非图片类强制 attachment）；非本人/已删 → 4041
     */
    DownloadResult download(UUID userId, Long itemId);

    /**
     * 预览（inline；仅图片/PDF/文本，其余降级下载语义）
     */
    DownloadResult preview(UUID userId, Long itemId);

    /**
     * 硬删除（deleted_at 审计位 + 级联清消化 chunks + blob 删除）
     *
     * <p>fix-batch B6（Q2 三层防误删）：资产页路径需请求头 {@code X-Confirm-Name} =
     * 文件名后四位（不符 400"输入的文件名后四位不符"）；对话内路径（内部调用/工具）免校验
     * （有内联确认卡）。校验在 Controller 层，本方法语义不变（真删）。</p>
     */
    void delete(UUID userId, Long itemId);

    /**
     * 用户补正：改描述 / 重命名（三层 key 第 3 层）
     */
    VaultItemVO update(UUID userId, Long itemId, String description, String category);

    /**
     * 确认消化（fix-batch B7，toolcalling-vault-design.md §3.3b 确认门禁）：
     * body {key, description, category}（用户可改后提交）→ ①更新元数据 ②生成 key chunk
     * （embed=key+description+类型拼合，contentType='note'，挂 vault_item_id）③全文消化
     * chunks 这时才 embed ④digest_status → confirmed。异步执行，接口层返回 202 语义。
     *
     * <p>幂等：已 confirmed 直接返回当前状态。key chunk 生成失败保持 extracted 可重试。</p>
     *
     * @param key         展示名/文件名（可空=保持现名）
     * @param description 描述（可空=清空）
     * @param category    分类（可空=保持）
     */
    VaultItemVO confirm(UUID userId, Long itemId, String key, String description, String category);

    /**
     * 三层漏斗检索（find_item 工具 + 资产页搜索共用）
     *
     * <p>① 精确（词典 term/别名 + 文件名/描述 ILIKE）→ ② 语义（description embedding）→
     * ③ 全文（消化 chunks 向量）。去重合并，引用强度分档标注。</p>
     *
     * @return 命中资产列表（带 matchLayer）
     */
    List<VaultItemVO> find(UUID userId, String query, String type, Integer days);

    /**
     * 单文件详情（recall_item 工具：详情 + 引用摘录 = source_chunk 文本）
     */
    VaultItemVO recall(UUID userId, Long itemId);

    /**
     * 取文件名（B6 防误删校验用；非本人/已删 4041 不暴露存在性）
     */
    String requireName(UUID userId, Long itemId);

    /**
     * 三档消化（上传后异步；管道隔离：失败只改该文件 digest_status=failed）
     *
     * <p>fix-batch B5：实现已拆到独立 {@link org.xianshen.mumirrorb.service.impl.DigestService}
     * Bean（@Async 代理生效，修自调用失效）；本方法保留为兼容委派。</p>
     *
     * <p>fix-batch B7 五态：文本/PDF 抽文本落 chunk（不 embed）→ extracted 停（确认门禁）；
     * 图片 → extracted（Y4 如实）；音视频 → skipped。确认后才 embed 进检索。</p>
     */
    void digestAsync(UUID userId, Long itemId);

    /**
     * 下载/预览结果载体
     */
    record DownloadResult(VaultItemVO item, byte[] data) {
    }
}

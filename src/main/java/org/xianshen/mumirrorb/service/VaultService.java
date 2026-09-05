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
     */
    void delete(UUID userId, Long itemId);

    /**
     * 用户补正：改描述 / 重命名（三层 key 第 3 层）
     */
    VaultItemVO update(UUID userId, Long itemId, String description, String category);

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
     * 三档消化（上传后异步；管道隔离：失败只改该文件 digest_status=failed）
     *
     * <p>全消化：抽文本→Record(vault 关联)→单 chunk→Embed；半消化：用户描述必填+EXIF 留待；
     * 零消化：音视频元数据卡 digest_status=skipped。</p>
     */
    void digestAsync(UUID userId, Long itemId);

    /**
     * 下载/预览结果载体
     */
    record DownloadResult(VaultItemVO item, byte[] data) {
    }
}

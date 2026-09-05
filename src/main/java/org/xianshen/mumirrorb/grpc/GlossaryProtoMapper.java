package org.xianshen.mumirrorb.grpc;

import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import org.xianshen.mumirrorb.pojo.VO.GlossaryGroupVO.UserTermVO;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 个人词典 VO → proto GlossaryTerm 映射（B 侧统一出口，避免 AiGrpcClient 与 GlossaryServiceImpl 重复）
 *
 * <p>confirmed_at 格式 yyyy-MM-dd（ISO 日期，Python prompt 标注"x月确认"）。</p>
 */
public final class GlossaryProtoMapper {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private GlossaryProtoMapper() {
    }

    /**
     * 单词 VO → proto
     */
    public static CommonProto.GlossaryTerm toProto(UserTermVO vo) {
        CommonProto.GlossaryTerm.Builder builder = CommonProto.GlossaryTerm.newBuilder()
                .setTerm(vo.getTerm() == null ? "" : vo.getTerm())
                .setDescription(vo.getDescription() == null ? "" : vo.getDescription());
        if (vo.getAliases() != null) {
            builder.addAllAliases(vo.getAliases());
        }
        OffsetDateTime confirmedAt = vo.getLastConfirmedAt();
        if (confirmedAt != null) {
            builder.setConfirmedAt(confirmedAt.format(DAY));
        }
        return builder.build();
    }

    /**
     * 批量 VO → proto（null 安全）
     */
    public static List<CommonProto.GlossaryTerm> toProtoList(List<UserTermVO> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        return terms.stream().map(GlossaryProtoMapper::toProto).toList();
    }
}

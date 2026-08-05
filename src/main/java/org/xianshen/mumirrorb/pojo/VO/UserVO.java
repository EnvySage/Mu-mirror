package org.xianshen.mumirrorb.pojo.VO;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 用户视图对象（返回给前端）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private String id;

    private String username;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private OffsetDateTime createdAt;
}

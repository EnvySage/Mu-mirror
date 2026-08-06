package org.xianshen.mumirrorb.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.pojo.DTO.RecordDTO;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.pojo.VO.RecordVO;
import org.xianshen.mumirrorb.service.RecordService;

/**
 * 记录控制器
 */
@RestController
@RequestMapping("/records")
@RequiredArgsConstructor
public class RecordController {

    private final RecordService recordService;

    /**
     * 创建记录
     *
     * POST /api/records
     * Body: { "content": "今天学了Spring Security..." }
     */
    @PostMapping
    public R<RecordVO> create(@Valid @RequestBody RecordDTO dto) {
        String userId = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        RecordVO record = recordService.create(dto, userId);
        return R.ok("记录已提交", record);
    }
}

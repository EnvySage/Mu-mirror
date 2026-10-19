package org.xianshen.mumirrorb.controller;
import jakarta.validation.Valid; import lombok.RequiredArgsConstructor; import org.springframework.security.core.context.SecurityContextHolder; import org.springframework.web.bind.annotation.*; import org.xianshen.mumirrorb.pojo.DTO.GrowthAdjustmentDTO; import org.xianshen.mumirrorb.pojo.R; import org.xianshen.mumirrorb.pojo.VO.GrowthOverviewVO; import org.xianshen.mumirrorb.service.GrowthService; import java.util.*;
@RestController @RequestMapping("/growth") @RequiredArgsConstructor public class GrowthController { private final GrowthService growth;
 private UUID user(){return UUID.fromString((String)SecurityContextHolder.getContext().getAuthentication().getPrincipal());}
 @GetMapping public R<GrowthOverviewVO> overview(){return R.ok("查询成功",growth.overview(user()));}
 @GetMapping("/axes") public R<Map<String,Object>> axes(){var a=growth.axes(user());return R.ok("查询成功",Map.of("version",growth.axesVersion(user()),"axes",a,"config",growth.overview(user()).getConfig()));}
 @GetMapping("/entries") public R<List<?>> entries(){return R.ok("查询成功",(List<?>)growth.entries(user()));}
 @PutMapping("/axes") public R<Map<String,Object>> updateAxes(@RequestBody Map<String,Object> body){long version=((Number)body.get("version")).longValue();var axes=new com.fasterxml.jackson.databind.ObjectMapper().convertValue(body.get("axes"),new com.fasterxml.jackson.core.type.TypeReference<List<org.xianshen.mumirrorb.config.GrowthProperties.Axis>>(){});var updated=growth.updateAxes(user(),version,axes);return R.ok("维度已更新",Map.of("version",growth.axesVersion(user()),"axes",updated));}
 @PostMapping("/manual") public R<GrowthOverviewVO> manual(@Valid @RequestBody GrowthAdjustmentDTO dto){return R.ok("成长已记录",growth.adjustManual(user(),dto));}
}

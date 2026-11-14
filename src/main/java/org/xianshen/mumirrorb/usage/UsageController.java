package org.xianshen.mumirrorb.usage;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.pojo.R;
import java.util.*;

@RestController
@RequestMapping("/ai-usage")
@RequiredArgsConstructor
public class UsageController {
    private final UsageQueries queries;
    private final UsageStore store;
    private UUID user() {return UUID.fromString((String)SecurityContextHolder.getContext().getAuthentication().getPrincipal());}
    @GetMapping("/summary")
    public R<Map<String,Object>> summary(@RequestParam(defaultValue="today") String period,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to,
            @RequestParam(required=false) String scene,@RequestParam(required=false) String provider,@RequestParam(required=false) String model) {
        store.expirePending();return R.ok(queries.summary(user(),period,from,to,scene,provider,model));
    }
    @GetMapping("/tasks")
    public R<Map<String,Object>> tasks(@RequestParam(defaultValue="30") int limit,@RequestParam(required=false) String before,
            @RequestParam(required=false) String scene,@RequestParam(required=false) String provider,
            @RequestParam(required=false) String model,@RequestParam(required=false) String from,@RequestParam(required=false) String to,
            @RequestParam(defaultValue="all") String period) {
        store.expirePending();return R.ok(queries.tasks(user(),limit,before,scene,provider,model,from,to,period));
    }
    @GetMapping("/tasks/{id}")
    public R<Map<String,Object>> detail(@PathVariable UUID id) {
        store.expirePending();var task=queries.detail(user(),id);
        if(task==null)throw new BusinessException(404,"用量任务不存在");return R.ok(task);
    }
}

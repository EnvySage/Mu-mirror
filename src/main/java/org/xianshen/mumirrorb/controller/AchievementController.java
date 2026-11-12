package org.xianshen.mumirrorb.controller;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.achievement.*;
import org.xianshen.mumirrorb.pojo.R;
import java.util.*;

@RestController
@RequestMapping("/achievements")
public class AchievementController {
    private final AchievementService service;
    private final AchievementActivity activities;
    public AchievementController(AchievementService service,AchievementActivity activities) {
        this.service=service; this.activities=activities;
    }
    private UUID user() { return UUID.fromString((String)SecurityContextHolder.getContext().getAuthentication().getPrincipal()); }
    public record ShowcaseInput(int capacity,List<String> codes,long expectedVersion) {}
    public record ActivityInput(String kind,String subjectId,String otherId,String value,String contextId) {}
    public record Acknowledgement(List<String> codes,List<AchievementService.Notification> notifications) {}
    @GetMapping public R<?> collection() { return R.ok(service.collection(user())); }
    @GetMapping("/pending") public R<?> pending() { return R.ok(service.pending(user())); }
    @PostMapping("/acknowledge") public R<?> acknowledge(@RequestBody Acknowledgement input) {
        if (input.notifications()!=null) service.acknowledgeNotifications(user(),input.notifications());
        else service.acknowledge(user(),input.codes());
        return R.ok("已确认展示");
    }
    @GetMapping("/showcase") public R<?> showcase() { return R.ok(service.showcase(user())); }
    @PutMapping("/showcase") public R<?> showcase(@RequestBody ShowcaseInput input) {
        return R.ok(service.saveShowcase(user(),input.capacity(),input.codes(),input.expectedVersion()));
    }
    @PostMapping("/activity") public R<?> activity(@RequestBody ActivityInput input) {
        activities.report(user(),input.kind(),input.subjectId(),input.otherId(),input.value(),input.contextId()); return R.ok("已记录");
    }
}

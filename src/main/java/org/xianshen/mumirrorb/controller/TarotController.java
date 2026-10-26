package org.xianshen.mumirrorb.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.service.TarotService;
import org.xianshen.mumirrorb.tarot.TarotCatalog;
import java.util.UUID;
import static org.xianshen.mumirrorb.tarot.TarotTypes.*;

@RestController @RequestMapping("/tarot") @RequiredArgsConstructor
public class TarotController {
    private final TarotService tarot; private final TarotCatalog catalog;
    private UUID user(){return UUID.fromString((String)SecurityContextHolder.getContext().getAuthentication().getPrincipal());}
    @GetMapping("/cards") public R<CatalogView> cards(){return R.ok("查询成功",catalog.view());}
    @GetMapping("/daily") public R<DailyView> daily(){return R.ok("查询成功",tarot.daily(user()));}
    @PostMapping("/daily/draw") public R<DailyView> draw(@RequestBody DrawInput input){return R.ok("抽取成功",tarot.drawDaily(user(),input.clientRequestId()));}
    @GetMapping("/daily/requests/{requestId}") public R<DailyDraw> request(@PathVariable UUID requestId){return R.ok("查询成功",tarot.findDailyRequest(user(),requestId));}
    @PostMapping("/readings") public R<ReadingView> reading(@RequestBody DrawInput input){return R.ok("抽取成功",tarot.createReading(user(),input.clientRequestId()));}
    @GetMapping("/readings/latest") public R<ReadingView> latest(){return R.ok("查询成功",tarot.latestReading(user()));}
    @GetMapping("/readings/{readingId}") public R<ReadingView> reading(@PathVariable UUID readingId){return R.ok("查询成功",tarot.getReading(user(),readingId));}
    @GetMapping("/collection") public R<CollectionView> collection(){return R.ok("查询成功",tarot.collection(user()));}
    @GetMapping("/preferences") public R<Preferences> preferences(){return R.ok("查询成功",tarot.preferences(user()));}
    @PutMapping("/preferences") public R<Preferences> preferences(@RequestBody PreferencesInput input){return R.ok("设置已保存",tarot.updatePreferences(user(),input));}
}

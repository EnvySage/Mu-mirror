package org.xianshen.mumirrorb.controller;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.pojo.R;
import org.xianshen.mumirrorb.service.VaultReviewService;
import java.util.UUID;

@RestController
@RequestMapping("/vault")
public class VaultReviewController {
    private final VaultReviewService review;
    public VaultReviewController(VaultReviewService review) { this.review = review; }

    @GetMapping("/{id}/chunks")
    public R<?> chunks(@PathVariable long id) {
        UUID user = UUID.fromString((String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal());
        return R.ok(review.chunks(user, id));
    }
}

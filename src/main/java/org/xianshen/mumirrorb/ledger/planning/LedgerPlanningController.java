package org.xianshen.mumirrorb.ledger.planning;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.xianshen.mumirrorb.pojo.R;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.xianshen.mumirrorb.ledger.planning.PlanningModels.*;

@RestController
@RequestMapping("/ledger")
@RequiredArgsConstructor
public class LedgerPlanningController {
    private final LedgerWishService wishes;
    private final LedgerRecurringService recurring;

    @GetMapping("/wishes")
    public R<List<WishView>> wishes() {
        return R.ok(wishes.list(owner()));
    }

    @PostMapping("/wishes")
    public R<WishView> createWish(@RequestBody WishInput input) {
        return R.ok(wishes.create(owner(), input));
    }

    @PutMapping("/wishes/{id}")
    public R<WishView> updateWishAmounts(@PathVariable UUID id, @RequestBody JsonNode input) {
        return R.ok(wishes.updateAmounts(owner(), id, input));
    }

    @DeleteMapping("/wishes/{id}")
    public R<Void> deleteWish(@PathVariable UUID id) {
        wishes.delete(owner(), id);
        return R.ok();
    }

    @GetMapping("/wish-events")
    public R<List<Achievement>> wishEvents(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return R.ok(wishes.events(owner(), start, end));
    }

    @GetMapping("/recurring")
    public R<List<RecurringRule>> recurringRules() {
        return R.ok(recurring.list(owner()));
    }

    @PostMapping("/recurring")
    public R<RecurringRule> createRecurringRule(@RequestBody RecurringInput input) {
        return R.ok(recurring.create(owner(), input));
    }

    @DeleteMapping("/recurring/{id}")
    public R<Void> deleteRecurringRule(@PathVariable UUID id) {
        recurring.delete(owner(), id);
        return R.ok();
    }

    @GetMapping("/recurring/due")
    public R<List<DueBill>> pendingBills() {
        return R.ok(recurring.pending(owner()));
    }

    @PostMapping("/recurring/due/{id}/confirm")
    public R<DueBill> confirmBill(@PathVariable UUID id, @RequestBody(required = false) ConfirmBill input) {
        return R.ok(recurring.confirm(owner(), id, input));
    }

    @PostMapping("/recurring/due/{id}/skip")
    public R<DueBill> skipBill(@PathVariable UUID id) {
        return R.ok(recurring.skip(owner(), id));
    }

    private static UUID owner() {
        return UUID.fromString((String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }
}

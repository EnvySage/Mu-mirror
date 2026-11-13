package org.xianshen.mumirrorb.ledger.planning;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.ledger.LedgerService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.xianshen.mumirrorb.ledger.planning.LedgerWishRepository.Rule;
import static org.xianshen.mumirrorb.ledger.planning.PlanningDates.Window;
import static org.xianshen.mumirrorb.ledger.planning.PlanningModels.*;

@Service
@RequiredArgsConstructor
public class LedgerWishService {
    private static final Set<String> EDITABLE_AMOUNTS = Set.of("targetAmount", "budget", "categoryBudgets", "price");
    private static final int MAX_WINDOWS_PER_REFRESH = 366;

    private final LedgerWishRepository wishes;
    private final LedgerService ledger;

    @Transactional
    public List<WishView> list(UUID owner) {
        LocalDate today = PlanningDates.today();
        return wishes.lockActive(owner).stream().map(rule -> view(owner, rule, today, reconcile(owner, rule, today))).toList();
    }

    @Transactional
    public WishView create(UUID owner, WishInput input) {
        LocalDate today = PlanningDates.today();
        if (input == null || input.kind() == null || input.metric() == null || input.windowMode() == null
                || input.windowDays() < 7 || input.windowDays() > 3660) {
            throw new BusinessException(400, "请选择有效的愿望类型、指标和至少七天的统计窗口");
        }
        if (input.kind() == WishKind.RECURRING && input.windowMode() != WindowMode.FIXED) {
            throw new BusinessException(400, "周期愿望使用固定周期");
        }
        Map<String, BigDecimal> categories = validateBudgets(input.categoryBudgets());
        BigDecimal budget = validateBudget(input.metric(), input.budget(), categories);
        if (!categories.isEmpty()) {
            Set<String> available = ledger.categories(owner).stream().map(c -> c.id()).collect(Collectors.toSet());
            if (!available.containsAll(categories.keySet())) throw new BusinessException(400, "账目分类不存在");
        }
        if (input.evidenceId() != null && !wishes.ownsImage(owner, input.evidenceId())) {
            throw new BusinessException(404, "愿望图片不存在");
        }
        Rule rule = new Rule(UUID.randomUUID(), PlanningValidation.title(input.title()), PlanningValidation.link(input.link()),
                input.evidenceId(), input.price() == null ? null : PlanningValidation.money(input.price(), false),
                input.kind(), input.metric(), PlanningValidation.start(input.startOn(), today, 10),
                input.windowDays(), input.windowMode(), PlanningValidation.money(input.targetAmount(), false),
                budget, categories, null);
        wishes.insert(owner, rule);
        return view(owner, rule, today, reconcile(owner, rule, today));
    }

    @Transactional
    public WishView updateAmounts(UUID owner, UUID id, JsonNode input) {
        if (input == null || !input.isObject() || input.isEmpty()) {
            throw new BusinessException(400, "请选择要修改的金额");
        }
        input.fieldNames().forEachRemaining(field -> {
            if (!EDITABLE_AMOUNTS.contains(field)) {
                throw new BusinessException(400, "已创建的愿望只允许修改金额，其他规则需重新创建");
            }
        });
        Rule rule = wishes.lock(owner, id);
        BigDecimal target = input.has("targetAmount")
                ? PlanningValidation.money(decimal(input.get("targetAmount")), false) : rule.targetAmount();
        BigDecimal budget = input.has("budget") ? decimal(input.get("budget")) : rule.budget();
        Map<String, BigDecimal> categories = input.has("categoryBudgets")
                ? validateBudgets(parseCategoryBudgets(input.get("categoryBudgets"))) : rule.categoryBudgets();
        if (!categories.keySet().equals(rule.categoryBudgets().keySet()) || (budget == null) != (rule.budget() == null)) {
            throw new BusinessException(400, "只能调整原有预算的金额");
        }
        BigDecimal price = input.has("price") ? decimal(input.get("price")) : rule.price();
        Rule updated = new Rule(rule.id(), rule.title(), rule.link(), rule.evidenceId(),
                price == null ? null : PlanningValidation.money(price, false), rule.kind(), rule.metric(),
                rule.startOn(), rule.windowDays(), rule.windowMode(), target,
                validateBudget(rule.metric(), budget, categories), categories, rule.evaluatedThrough());
        LocalDate today = PlanningDates.today();
        Achievement previous = reconcile(owner, rule, today);
        wishes.changeAmounts(owner, updated);
        Achievement achievement = evaluateActiveAfterEdit(owner, updated, today, previous);
        return view(owner, updated, today, achievement);
    }

    @Transactional
    public void delete(UUID owner, UUID id) {
        reconcile(owner, wishes.lock(owner, id), PlanningDates.today());
        wishes.delete(owner, id);
    }

    @Transactional
    public List<Achievement> events(UUID owner, LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > 366) {
            throw new BusinessException(400, "请选择不超过一年的有效日期范围");
        }
        reconcileAll(owner);
        return wishes.events(owner, new Window(start, end));
    }

    @Transactional
    public void refresh(UUID owner) {
        reconcileAll(owner);
    }

    private void reconcileAll(UUID owner) {
        LocalDate today = PlanningDates.today();
        wishes.lockActive(owner).forEach(rule -> reconcile(owner, rule, today));
    }

    private Achievement reconcile(UUID owner, Rule rule, LocalDate today) {
        Achievement latest = wishes.latest(owner, rule.id());
        LocalDate closed = today.minusDays(1);
        if ((latest != null && rule.kind() == WishKind.ONCE) || closed.isBefore(rule.startOn())
                || (rule.evaluatedThrough() != null && !closed.isAfter(rule.evaluatedThrough()))) {
            return latest;
        }
        LocalDate cursor = rule.evaluatedThrough() == null ? rule.startOn() : rule.evaluatedThrough().plusDays(1);
        cursor = firstEligibleEnd(rule, cursor);
        LocalDate checked = rule.evaluatedThrough();
        int evaluated = 0;
        while (!cursor.isAfter(closed) && evaluated++ < MAX_WINDOWS_PER_REFRESH) {
            Window window = window(rule, cursor);
            if (latest == null || !latest.periodStart().equals(window.start())) {
                var measure = wishes.measure(owner, rule, window.start(), cursor);
                if (measure.amount().compareTo(rule.targetAmount()) >= 0) {
                    latest = wishes.save(owner, rule, window, cursor, measure);
                    if (rule.kind() == WishKind.ONCE) break;
                }
            }
            checked = cursor;
            boolean completePeriod = rule.windowMode() == WindowMode.FIXED
                    && (rule.metric() != Metric.INCOME || latest != null && latest.periodStart().equals(window.start()));
            cursor = firstEligibleEnd(rule, completePeriod ? window.end().plusDays(1) : cursor.plusDays(1));
        }
        // A missed scheduler run is recovered by replaying closed windows in chronological order.
        LocalDate through = evaluated > MAX_WINDOWS_PER_REFRESH ? checked : closed;
        if (through != null) wishes.markChecked(owner, rule.id(), through);
        return latest;
    }

    private Achievement evaluateActiveAfterEdit(UUID owner, Rule rule, LocalDate today, Achievement previous) {
        if ((previous != null && rule.kind() == WishKind.ONCE) || today.isBefore(rule.startOn())) return previous;
        LocalDate closed = today.minusDays(1);
        if (closed.isBefore(rule.startOn())) return previous;
        Window candidate;
        if (rule.windowMode() == WindowMode.ROLLING) {
            if (closed.isBefore(rule.startOn().plusDays(rule.windowDays() - 1L))) return previous;
            candidate = window(rule, closed);
        } else {
            candidate = window(rule, today);
            if (rule.metric() != Metric.INCOME || closed.isBefore(candidate.start())
                    || previous != null && previous.periodStart().equals(candidate.start())) return previous;
        }
        var result = wishes.measure(owner, rule, candidate.start(), closed);
        return result.amount().compareTo(rule.targetAmount()) >= 0
                ? wishes.save(owner, rule, candidate, closed, result) : previous;
    }

    private WishView view(UUID owner, Rule rule, LocalDate today, Achievement latest) {
        Window current = window(rule, today.isBefore(rule.startOn()) ? rule.startOn() : today);
        Achievement achieved = latest != null && (rule.kind() == WishKind.ONCE
                || latest.periodStart().equals(current.start())) ? latest : null;
        WishState state = today.isBefore(rule.startOn()) ? WishState.NOT_STARTED : WishState.ACTIVE;
        BigDecimal progress;
        BigDecimal target = rule.targetAmount();
        if (achieved != null) {
            state = WishState.ACHIEVED;
            current = new Window(achieved.periodStart(), achieved.periodEnd());
            progress = achieved.amount();
            target = achieved.targetAmount();
        } else {
            progress = state == WishState.NOT_STARTED ? BigDecimal.ZERO
                    : wishes.measure(owner, rule, current.start(), today).amount();
        }
        BigDecimal ratio = progress.max(BigDecimal.ZERO).divide(target, 4, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        return new WishView(rule.id(), rule.title(), rule.link(), rule.evidenceId(), rule.price(), rule.kind(), rule.metric(),
                rule.startOn(), rule.windowDays(), rule.windowMode(), rule.targetAmount(), rule.budget(), rule.categoryBudgets(),
                state, progress, ratio, current.start(), current.end(), achieved);
    }

    private static LocalDate firstEligibleEnd(Rule rule, LocalDate candidate) {
        if (rule.windowMode() == WindowMode.ROLLING) {
            LocalDate first = rule.startOn().plusDays(rule.windowDays() - 1L);
            return candidate.isBefore(first) ? first : candidate;
        }
        return rule.metric() == Metric.INCOME ? candidate : window(rule, candidate).end();
    }

    private static Window window(Rule rule, LocalDate date) {
        return rule.windowMode() == WindowMode.ROLLING
                ? PlanningDates.rollingWindow(rule.startOn(), rule.windowDays(), date)
                : PlanningDates.fixedWindow(rule.startOn(), rule.windowDays(), date);
    }

    private static Map<String, BigDecimal> validateBudgets(Map<String, BigDecimal> values) {
        if (values == null || values.isEmpty()) return Map.of();
        if (values.size() > 100) throw new BusinessException(400, "分类预算最多 100 项");
        Map<String, BigDecimal> checked = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 64) throw new BusinessException(400, "无效分类");
            checked.put(key, PlanningValidation.money(value, true));
        });
        return Map.copyOf(checked);
    }

    private static BigDecimal validateBudget(Metric metric, BigDecimal budget, Map<String, BigDecimal> categories) {
        if (metric == Metric.SAVINGS) {
            if ((budget != null) == !categories.isEmpty()) {
                throw new BusinessException(400, "请在总预算和分类预算中选择一种");
            }
        } else if (budget != null || !categories.isEmpty()) {
            throw new BusinessException(400, "此指标只需填写达标金额");
        }
        return budget == null ? null : PlanningValidation.money(budget, true);
    }

    private static Map<String, BigDecimal> parseCategoryBudgets(JsonNode input) {
        if (input == null || !input.isObject()) throw new BusinessException(400, "分类预算格式无效");
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        input.fields().forEachRemaining(field -> values.put(field.getKey(), decimal(field.getValue())));
        return values;
    }

    private static BigDecimal decimal(JsonNode input) {
        if (input == null || input.isNull()) return null;
        if (input.isNumber() || input.isTextual()) {
            try {
                return new BigDecimal(input.asText());
            } catch (NumberFormatException ignored) {
                // Use a field-level business error rather than exposing parser details.
            }
        }
        throw new BusinessException(400, "请输入有效金额");
    }
}

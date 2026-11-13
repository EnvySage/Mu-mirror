package org.xianshen.mumirrorb.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LedgerModels {
    private LedgerModels() {}

    public record Category(String id, String name, boolean preset) {}

    public record CategoryInput(String name) {}

    public record FlowInput(UUID id, String kind, BigDecimal amount, String categoryId,
                            LocalDate occurredOn, String note, UUID relatedExpenseId, Long evidenceId) {}

    public record EntryInput(UUID requestId, String title, String description, List<FlowInput> flows,
                             String diaryMode, String diaryText) {}

    public record AppendInput(UUID requestId, List<FlowInput> flows) {}

    public record Flow(UUID id, String kind, BigDecimal amount, String categoryId, String categoryName,
                       LocalDate occurredOn, String note, UUID relatedExpenseId, Long evidenceId) {}

    public record Entry(UUID id, String title, String description, LocalDate recordDate,
                        BigDecimal expense, BigDecimal income, BigDecimal recovery, BigDecimal netExpense,
                        List<Flow> flows, List<Long> diaryIds, UUID diaryActionId) {}

    public record CategoryTotal(String categoryId, String name, BigDecimal expense, BigDecimal recovery,
                                BigDecimal netExpense) {}

    public record DayTotal(LocalDate date, BigDecimal expense, BigDecimal income, BigDecimal recovery,
                           BigDecimal netExpense, int flowCount) {}

    public record Brief(LocalDate start, LocalDate end, BigDecimal expense, BigDecimal income,
                        BigDecimal recovery, BigDecimal netExpense, int flowCount,
                        List<CategoryTotal> categories, List<DayTotal> days) {}

    public record DiaryLinks(List<UUID> recordIds) {}

    public record RecognizeInput(Long vaultItemId) {}

    public record Recognition(String status, com.fasterxml.jackson.databind.JsonNode draft, String message) {}
}

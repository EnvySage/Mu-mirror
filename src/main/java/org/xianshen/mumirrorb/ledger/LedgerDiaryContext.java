package org.xianshen.mumirrorb.ledger;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class LedgerDiaryContext {
    private final LedgerQueries ledger;

    public LedgerDiaryContext(LedgerQueries ledger) {
        this.ledger = ledger;
    }

    public String withLinkedFacts(UUID owner, Long diaryId, String text) {
        if (diaryId == null || text.length() >= 1900) return text;
        var material = new StringBuilder(text);
        for (var entry : ledger.linked(owner, diaryId)) {
            var facts = "\n\n关联账目（账本数据）\n" + LedgerDiaryService.template(entry);
            int remaining = 2000 - material.length();
            if (remaining < 100) break;
            material.append(facts, 0, Math.min(remaining, facts.length()));
        }
        return material.toString();
    }
}

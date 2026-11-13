package org.xianshen.mumirrorb.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.xianshen.mumirrorb.common.exception.BusinessException;
import org.xianshen.mumirrorb.pojo.R;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;
import static org.xianshen.mumirrorb.ledger.LedgerModels.*;

@RestController
@RequestMapping("/ledger")
public class LedgerController {
    private final LedgerService ledger;
    private final LedgerQueries queries;
    private final LedgerReceiptService receipts;
    private final ObjectMapper json;

    public LedgerController(LedgerService ledger, LedgerQueries queries, LedgerReceiptService receipts,
                            ObjectMapper json) {
        this.ledger = ledger;
        this.queries = queries;
        this.receipts = receipts;
        this.json = json;
    }

    @GetMapping("/categories")
    public R<List<Category>> categories() {
        return R.ok(ledger.categories(owner()));
    }

    @PostMapping("/categories")
    public R<Category> addCategory(@RequestBody CategoryInput input) {
        return R.ok(ledger.addCategory(owner(), input.name()));
    }

    @GetMapping("/records")
    public R<List<Entry>> records(@RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate start,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate end,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) Integer limit) {
        return R.ok(queries.list(owner(), start, end, q, limit));
    }

    @GetMapping("/records/{id}")
    public R<Entry> record(@PathVariable UUID id) {
        return R.ok(queries.get(owner(), id));
    }

    @PostMapping("/records")
    public R<Entry> create(@RequestBody EntryInput input) {
        return R.ok(ledger.create(owner(), input));
    }

    @PutMapping("/records/{id}")
    public R<Entry> update(@PathVariable UUID id, @RequestBody EntryInput input) {
        return R.ok(ledger.update(owner(), id, input));
    }

    @PostMapping("/records/{id}/flows")
    public R<Entry> append(@PathVariable UUID id, @RequestBody AppendInput input) {
        return R.ok(ledger.append(owner(), id, input));
    }

    @DeleteMapping("/records/{id}")
    public R<Void> delete(@PathVariable UUID id) {
        ledger.delete(owner(), id);
        return R.ok();
    }

    @DeleteMapping("/records/{id}/flows/{flowId}")
    public R<Void> deleteFlow(@PathVariable UUID id, @PathVariable UUID flowId) {
        ledger.deleteFlow(owner(), id, flowId);
        return R.ok();
    }

    @GetMapping("/brief")
    public R<Brief> brief(@RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate start,
                          @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate end) {
        return R.ok(queries.brief(owner(), start, end));
    }

    @GetMapping("/diaries/{diaryId}/records")
    public R<List<Entry>> diaryLinks(@PathVariable long diaryId) {
        return R.ok(ledger.diaryLinks(owner(), diaryId));
    }

    @PutMapping("/diaries/{diaryId}/records")
    public R<List<Entry>> linkDiary(@PathVariable long diaryId, @RequestBody DiaryLinks input) {
        return R.ok(ledger.replaceDiaryLinks(owner(), diaryId, input.recordIds()));
    }

    @PostMapping("/recognize")
    public R<Recognition> recognize(@RequestBody RecognizeInput input) {
        return R.ok(receipts.recognize(owner(), input.vaultItemId()));
    }

    @GetMapping("/export")
    public void export(@RequestParam(defaultValue = "csv") String format,
                       @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate start,
                       @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate end,
                       HttpServletResponse response) throws IOException {
        if (!List.of("csv", "json").contains(format)) throw new BusinessException(400, "请选择 CSV 或 JSON");
        var range = LedgerQueries.range(start, end, 3660);
        var entries = queries.export(owner(), range.start(), range.end());
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=ledger-" + range.start() + "-" + range.end() + "." + format);
        if ("json".equals(format)) {
            response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), Map.of("start", range.start(), "end", range.end(),
                    "currency", "CNY", "records", entries));
            return;
        }
        response.setContentType("text/csv");
        PrintWriter out = response.getWriter();
        out.write("\uFEFFrecord_id,record_title,flow_id,date,kind,amount,currency,category,note,related_expense_id,evidence_id\r\n");
        for (var entry : entries) {
            for (var flow : entry.flows()) {
                out.write(String.join(",", entry.id().toString(), csv(entry.title()), flow.id().toString(),
                        flow.occurredOn().toString(), flow.kind(), flow.amount().toPlainString(), "CNY",
                        csv(flow.categoryName()), csv(flow.note()), value(flow.relatedExpenseId()), value(flow.evidenceId())));
                out.write("\r\n");
            }
        }
        out.flush();
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        String stripped = text.stripLeading();
        if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static UUID owner() {
        return UUID.fromString((String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }
}

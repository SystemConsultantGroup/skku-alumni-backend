package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.CursorPageResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only reconciliation views; corrections use the existing payment APIs. */
@RestController
@RequestMapping("/api/v1/admin/payment-transition")
@RequiredArgsConstructor
public class AdminPaymentTransitionController {
    private final JdbcTemplate jdbc;

    @GetMapping("/histories")
    public CursorPageResponse<Map<String, Object>> histories(@RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size) {
        int limit = Math.max(1, Math.min(size, 100));
        List<Map<String, Object>> rows = jdbc.query("""
                select h.id, h.user_id, h.officer_term_id, h.payment_status as history_status,
                       h.deleted_at as history_deleted_at, p.id as payment_id, p.status as payment_status,
                       p.amount, p.paid_at, p.deleted_at as payment_deleted_at
                from officer_histories h left join payment_records p
                  on p.user_id = h.user_id and p.officer_term_id = h.officer_term_id
                where (? is null or h.id < ?) and
                  (p.id is null or h.payment_status <> p.status or h.deleted_at is not null or p.deleted_at is not null)
                order by h.id desc limit ?
                """, JdbcResponseMapper.INSTANCE, cursor, cursor, limit + 1);
        return page(rows, limit);
    }

    @GetMapping("/orphan-payments")
    public CursorPageResponse<Map<String, Object>> orphanPayments(@RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size) {
        int limit = Math.max(1, Math.min(size, 100));
        List<Map<String, Object>> rows = jdbc.query("""
                select p.id, p.user_id, p.officer_term_id, p.status, p.amount, p.paid_at, p.deleted_at
                from payment_records p left join officer_histories h
                  on h.user_id = p.user_id and h.officer_term_id = p.officer_term_id
                where h.id is null and (? is null or p.id < ?)
                order by p.id desc limit ?
                """, JdbcResponseMapper.INSTANCE, cursor, cursor, limit + 1);
        return page(rows, limit);
    }

    private CursorPageResponse<Map<String, Object>> page(List<Map<String, Object>> rows, int limit) {
        boolean hasNext = rows.size() > limit;
        List<Map<String, Object>> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        Long next = hasNext ? ((Number) items.getLast().get("id")).longValue() : null;
        return new CursorPageResponse<>(items, next, hasNext);
    }
}

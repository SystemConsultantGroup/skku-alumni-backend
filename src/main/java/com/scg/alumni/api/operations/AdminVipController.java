package com.scg.alumni.api.operations;

import com.scg.alumni.domain.organization.VipQualificationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/members/{memberId}/vip")
@RequiredArgsConstructor
public class AdminVipController {
    private final VipQualificationService vip;
    private final AdminAuditLog audit;

    @PostMapping
    @Transactional
    public Map<String, Object> grant(@PathVariable long memberId) {
        vip.grant(memberId);
        audit.record("GRANT_VIP", "user", memberId);
        return Map.of("memberId", memberId, "active", true);
    }

    @PostMapping("/revoke")
    @Transactional
    public Map<String, Object> revoke(@PathVariable long memberId) {
        vip.revoke(memberId);
        audit.record("REVOKE_VIP", "user", memberId);
        return Map.of("memberId", memberId, "active", false);
    }

    @GetMapping
    public Map<String, Object> history(@PathVariable long memberId, @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size) {
        int pageSize = Math.max(1, Math.min(size, 100));
        List<Map<String, Object>> rows = vip.history(memberId, cursor, pageSize);
        boolean hasNext = rows.size() > pageSize;
        List<Map<String, Object>> items = hasNext ? rows.subList(0, pageSize) : rows;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("hasNext", hasNext);
        result.put("nextCursor", hasNext ? items.get(items.size() - 1).get("id") : null);
        return result;
    }
}

package com.scg.alumni.api.operations;

import com.scg.alumni.domain.organization.AlumniOrganizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/alumni-organizations")
@RequiredArgsConstructor
public class AdminAlumniOrganizationController {
    private final AlumniOrganizationService organizations;
    private final AdminAuditLog audit;

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size) {
        int pageSize = Math.max(1, Math.min(size, 100));
        return page(organizations.organizations(cursor, pageSize), pageSize);
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody OrganizationRequest request) {
        long id = organizations.create(request.name(), request.description());
        audit.record("CREATE_ALUMNI_ORGANIZATION", "alumni_organization", id);
        return Map.of("id", id);
    }

    @PatchMapping("/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody OrganizationRequest request) {
        organizations.update(id, request.name(), request.description());
        audit.record("UPDATE_ALUMNI_ORGANIZATION", "alumni_organization", id);
        return Map.of("id", id);
    }

    @PostMapping("/{id}/officers")
    @Transactional
    public Map<String, Object> addOfficer(@PathVariable long id, @Valid @RequestBody OfficerRequest request) {
        long officerId = organizations.addOfficer(id, request.userId(), request.title(), request.president(),
                request.startedAt(), request.endedAt());
        audit.record("ADD_ALUMNI_OFFICER", "alumni_organization_officer", officerId);
        return Map.of("id", officerId);
    }

    @GetMapping("/{id}/officers")
    public Map<String, Object> officers(@PathVariable long id, @RequestParam(defaultValue = "true") boolean current,
            @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int size) {
        int pageSize = Math.max(1, Math.min(size, 100));
        List<Map<String, Object>> rows = organizations.officers(id, cursor, pageSize, current);
        return page(rows, pageSize);
    }

    private Map<String, Object> page(List<Map<String, Object>> rows, int pageSize) {
        boolean hasNext = rows.size() > pageSize;
        List<Map<String, Object>> page = hasNext ? rows.subList(0, pageSize) : rows;
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("items", page);
        result.put("hasNext", hasNext);
        result.put("nextCursor", hasNext ? page.get(page.size() - 1).get("id") : null);
        return result;
    }

    @PostMapping("/{id}/officers/{officerId}/expire")
    @Transactional
    public Map<String, Object> expire(@PathVariable long id, @PathVariable long officerId) {
        organizations.expire(id, officerId);
        audit.record("EXPIRE_ALUMNI_OFFICER", "alumni_organization_officer", officerId);
        return Map.of("id", officerId);
    }

    @PostMapping("/{id}/officers/{officerId}/replace")
    @Transactional
    public Map<String, Object> replaceOfficer(@PathVariable long id, @PathVariable long officerId,
            @Valid @RequestBody ReplaceOfficerRequest request) {
        long replacementId = organizations.replaceOfficer(id, officerId, request.title(), request.president(),
                request.startedAt(), request.endedAt());
        audit.record("REPLACE_ALUMNI_OFFICER", "alumni_organization_officer", officerId);
        return Map.of("id", replacementId, "previousId", officerId);
    }

    public record ReplaceOfficerRequest(@Size(max = 255) String title, boolean president,
            LocalDate startedAt, LocalDate endedAt) { }

    @PostMapping("/{id}/dissolve")
    @Transactional
    public Map<String, Object> dissolve(@PathVariable long id) {
        organizations.dissolve(id);
        audit.record("DISSOLVE_ALUMNI_ORGANIZATION", "alumni_organization", id);
        return Map.of("id", id);
    }

    public record OrganizationRequest(@NotBlank @Size(max = 255) String name, @Size(max = 1000) String description) { }
    public record OfficerRequest(@NotNull Long userId, @Size(max = 255) String title,
            boolean president, LocalDate startedAt, LocalDate endedAt) { }
}

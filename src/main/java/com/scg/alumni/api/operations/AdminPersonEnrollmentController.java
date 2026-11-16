package com.scg.alumni.api.operations;

import com.scg.alumni.domain.organization.AdministrativePersonService;
import com.scg.alumni.domain.organization.AlumniOrganizationService;
import com.scg.alumni.domain.organization.VipQualificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminPersonEnrollmentController {
    private final AdministrativePersonService people;
    private final AlumniOrganizationService organizations;
    private final VipQualificationService vip;
    private final AdminAuditLog audit;

    @GetMapping("/person-candidates")
    public List<Map<String, Object>> candidates(@RequestParam(required = false) String name,
            @RequestParam(required = false) String studentId, @RequestParam(required = false) String phone) {
        return people.candidates(name, studentId, phone);
    }

    @PostMapping("/alumni-organizations/{id}/enroll")
    @Transactional
    public Map<String, Object> enrollOfficer(@PathVariable long id, @Valid @RequestBody Enrollment request) {
        long userId = resolvePerson(request);
        if (request.title() == null || request.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title is required");
        }
        long appointmentId = organizations.addOfficer(id, userId, request.title(), request.president(),
                request.startedAt(), request.endedAt());
        audit.record("ENROLL_ALUMNI_OFFICER", "alumni_organization_officer", appointmentId);
        return result(userId, appointmentId, request.existingUserId() == null);
    }

    @PostMapping("/vip-enrollments")
    @Transactional
    public Map<String, Object> enrollVip(@Valid @RequestBody Enrollment request) {
        long userId = resolvePerson(request);
        vip.grant(userId);
        audit.record("ENROLL_VIP", "user", userId);
        return result(userId, null, request.existingUserId() == null);
    }

    private long resolvePerson(Enrollment request) {
        return request.existingUserId() != null ? request.existingUserId()
                : people.create(request.name(), request.studentId(), request.phone(), request.confirmedNoMatch());
    }

    private Map<String, Object> result(long userId, Long appointmentId, boolean created) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("appointmentId", appointmentId);
        result.put("created", created);
        if (created) result.put("initialLoginId", people.loginId(userId));
        return result;
    }

    public record Enrollment(Long existingUserId, @Size(max = 255) String name,
            @Size(max = 255) String studentId, @Size(max = 50) String phone,
            boolean confirmedNoMatch, @Size(max = 255) String title, boolean president,
            LocalDate startedAt, LocalDate endedAt) { }
}

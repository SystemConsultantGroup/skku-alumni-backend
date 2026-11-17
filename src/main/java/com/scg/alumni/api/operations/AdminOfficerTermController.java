package com.scg.alumni.api.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/officer-terms")
public class AdminOfficerTermController {
    private final OfficerTermManagementService terms;

    @GetMapping("/options")
    public List<OfficerTermManagementService.TermOption> options() { return terms.options(); }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody CreateRequest request) {
        return terms.create(request.generation(), request.phase(), request.startedAt(), request.endedAt());
    }

    @PostMapping("/{id}/activate")
    public Map<String, Object> activate(@PathVariable long id) {
        return terms.activate(id);
    }

    public record CreateRequest(@NotNull @Positive Integer generation, @NotNull @Positive Integer phase,
                                @NotNull LocalDate startedAt, @NotNull LocalDate endedAt) { }
}

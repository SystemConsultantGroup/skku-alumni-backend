package com.scg.alumni.api.search;

import com.scg.alumni.global.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class UnifiedSearchController {
    private final UnifiedSearchService service;

    @GetMapping
    public UnifiedSearchService.Results search(@RequestParam String keyword,
            @RequestParam(required = false) Long postCursor,
            @RequestParam(required = false) Integer postRank,
            @RequestParam(required = false) String personCursor,
            @RequestParam(defaultValue = "10") int size) {
        return service.search(keyword, AuthContext.currentMemberId(), postCursor, postRank, personCursor, size);
    }
}

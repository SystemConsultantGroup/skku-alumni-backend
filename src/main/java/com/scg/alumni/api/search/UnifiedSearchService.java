package com.scg.alumni.api.search;

import com.scg.alumni.domain.officer.OfficerTermPolicyProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UnifiedSearchService {
    private final UnifiedSearchRepository repository;
    private final OfficerTermPolicyProperties policy;
    private static final List<Shortcut> SHORTCUTS = List.of(
            new Shortcut("병원 혜택", "/benefits", "병원 의료 혜택"),
            new Shortcut("주차권 혜택", "/benefits", "주차 주차권 혜택"),
            new Shortcut("경조사 혜택", "/benefits", "경조사 축하 조문 혜택"),
            new Shortcut("임원정보", "/members", "임원 동문 회원 주소록"),
            new Shortcut("커뮤니티", "/community", "커뮤니티 게시판"),
            new Shortcut("비즈니스", "/business", "비즈니스 사업"));

    public Results search(String keyword, long viewer, Long postCursor, String personCursor, int requestedSize) {
        return search(keyword, viewer, postCursor, null, personCursor, requestedSize);
    }

    public Results search(String keyword, long viewer, Long postCursor, Integer postRank, String personCursor, int requestedSize) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어를 확인하세요.");
        String query = keyword.trim().toLowerCase(Locale.ROOT);
        int size = Math.max(1, Math.min(requestedSize, 100));
        Integer group = null;
        Long id = null;
        if (personCursor != null) {
            try {
                String[] parts = personCursor.split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                group = Integer.valueOf(parts[0]); id = Long.valueOf(parts[1]);
                if ((group != 0 && group != 1) || id <= 0) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "인물 커서가 올바르지 않습니다.");
            }
        }
        if (postRank != null && (postCursor == null || postRank < 1 || postRank > 3))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "게시물 커서가 올바르지 않습니다.");
        var posts = repository.posts(query, viewer, postCursor, postRank, size + 1);
        var people = repository.people(query, viewer, LocalDate.now(policy.zone()), group, id, size + 1);
        boolean morePosts = posts.size() > size, morePeople = people.size() > size;
        var postItems = posts.subList(0, Math.min(size, posts.size()));
        var personItems = people.subList(0, Math.min(size, people.size()));
        Long nextPost = morePosts ? ((Number) postItems.getLast().get("id")).longValue() : null;
        Integer nextPostRank = morePosts ? ((Number) postItems.getLast().get("relevance")).intValue() : null;
        String nextPerson = morePeople ? personItems.getLast().get("officer") + ":" + personItems.getLast().get("id") : null;
        return new Results(SHORTCUTS.stream().filter(item -> item.keywords().contains(query)).toList(),
                postItems, nextPost, nextPostRank, personItems, nextPerson);
    }

    public record Shortcut(String name, String path, String keywords) { }
    public record Results(List<Shortcut> shortcuts, List<Map<String, Object>> posts, Long nextPostCursor, Integer nextPostRank,
                          List<Map<String, Object>> people, String nextPersonCursor) { }
}

package com.scg.alumni.api.member;

import com.scg.alumni.api.common.CursorPageResponse;
import com.scg.alumni.domain.member.Member;
import com.scg.alumni.domain.member.MemberRepository;
import com.scg.alumni.domain.member.MemberStatus;
import com.scg.alumni.domain.officer.OfficerPaymentStatus;
import com.scg.alumni.domain.officer.OfficerTerm;
import java.time.LocalDate;
import java.time.ZoneId;
import com.scg.alumni.global.security.AuthContext;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberDirectoryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final MemberRepository memberRepository;
    private final JdbcTemplate jdbcTemplate;

    public CursorPageResponse<MemberSummaryResponse> search(
            String keyword,
            String searchType,
            Long majorId,
            Long industryId,
            Integer admissionYear,
            Long officerTermId,
            Long officerRoleId,
            String region,
            String companyName,
            Long hobbyId,
            Long cursor,
            Integer size
    ) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        int pageSize = normalizeSize(size);
        List<Long> blockedMemberIds = blockedMemberIds();
        String normalizedSearchType = normalizeSearchType(searchType);
        AdmissionSearch admissionSearch = normalizeAdmissionSearch(keyword, normalizedSearchType);
        List<Member> fetchedMembers = memberRepository.searchCurrentPaidDirectory(
                normalizeKeyword(keyword, normalizedSearchType),
                normalizedSearchType,
                admissionSearch.yearKeyword(),
                admissionSearch.studentIdKeyword(),
                majorId,
                industryId,
                admissionYear,
                officerTermId,
                officerRoleId,
                normalizeLike(region),
                normalizeLike(companyName),
                hobbyId,
                cursor,
                blockedMemberIds,
                MemberStatus.ACTIVE,
                OfficerPaymentStatus.PAID,
                today,
                today.minusDays(OfficerTerm.GRACE_DAYS),
                PageRequest.of(0, pageSize + 1)
        );

        boolean hasNext = fetchedMembers.size() > pageSize;
        List<Member> pageMembers = hasNext ? fetchedMembers.subList(0, pageSize) : fetchedMembers;
        List<MemberSummaryResponse> items = pageMembers.stream()
                .map(MemberSummaryResponse::from)
                .toList();
        Long nextCursor = hasNext && !items.isEmpty() ? items.get(items.size() - 1).id() : null;

        return new CursorPageResponse<>(items, nextCursor, hasNext);
    }

    /**
     * 지금 주소록에 보이는 임원 수.
     *
     * <p>목록을 한 페이지 받아 그 길이를 세면 늘 페이지 크기가 나온다. 홈 화면이
     * 인원수로 내보이는 자리라 실제 수를 따로 센다.
     */
    public long countVisibleOfficers() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        return memberRepository.countCurrentPaidDirectory(
                blockedMemberIds(),
                MemberStatus.ACTIVE,
                OfficerPaymentStatus.PAID,
                today,
                today.minusDays(OfficerTerm.GRACE_DAYS));
    }

    /** 내가 차단한 사람들. 비어 있으면 {@code not in ()} 이 문법 오류라 없는 id 를 넣는다. */
    private List<Long> blockedMemberIds() {
        Long currentUserId = AuthContext.currentMemberIdOrNull();
        if (currentUserId == null) {
            return List.of(-1L);
        }
        List<Long> blocked = jdbcTemplate.queryForList(
                "select blocked_id from user_blocks where blocker_id = ? and deleted_at is null", Long.class, currentUserId);
        return blocked.isEmpty() ? List.of(-1L) : blocked;
    }

    private int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private String normalizeLike(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return "%" + value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT) + "%";
    }

    private String normalizeKeyword(String value, String searchType) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if ("term".equals(searchType)) {
            normalized = normalized.replace("제", "");
        }
        if ("admission".equals(searchType)) {
            normalized = normalized.replace("학번", "");
        }
        if ("role".equals(searchType)) {
            return normalized;
        }
        return "%" + normalized + "%";
    }

    private AdmissionSearch normalizeAdmissionSearch(String value, String searchType) {
        if (!"admission".equals(searchType) || !StringUtils.hasText(value)) {
            return new AdmissionSearch("__no_match__", "__no_match__");
        }
        String normalized = value.replaceAll("\\s+", "").replace("학번", "");
        if (normalized.matches("\\d{2}")) {
            return new AdmissionSearch("%" + normalized, "__no_match__");
        }
        if (normalized.matches("\\d{4}")) {
            return new AdmissionSearch(normalized, "__no_match__");
        }
        return new AdmissionSearch("__no_match__", normalized.toLowerCase(Locale.ROOT));
    }

    private record AdmissionSearch(String yearKeyword, String studentIdKeyword) {
    }

    private String normalizeSearchType(String value) {
        if (!StringUtils.hasText(value)) {
            return "all";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "major", "industry", "admission", "term", "role", "region" -> value.trim().toLowerCase(Locale.ROOT);
            default -> "all";
        };
    }
}

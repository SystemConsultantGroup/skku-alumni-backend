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
    /** 홈의 "최근 임원" 처럼 직급이 아니라 최근에 들어온 순서를 보여줄 때. */
    private static final String RECENT_SORT = "recent";

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
            Integer size,
            String sort
    ) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        int pageSize = normalizeSize(size);
        boolean orderByRole = !RECENT_SORT.equals(normalizeSort(sort));
        List<Long> blockedMemberIds = blockedMemberIds();
        String normalizedSearchType = normalizeSearchType(searchType);
        AdmissionSearch admissionSearch = normalizeAdmissionSearch(keyword, normalizedSearchType);
        List<Member> fetchedMembers = memberRepository.searchCurrentPaidDirectory(
                normalizeKeyword(keyword, normalizedSearchType),
                normalizedSearchType,
                admissionSearch.yearKeyword(),
                admissionSearch.studentIdKeyword(),
                equivalentMajorIds(majorId),
                industryId,
                admissionYear,
                officerTermId,
                officerRoleId,
                normalizeLike(region),
                normalizeLike(companyName),
                hobbyId,
                cursor,
                orderByRole ? directoryRoleSortOrder(cursor, today) : null,
                orderByRole ? 1 : 0,
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
     * 목록을 무엇으로 줄 세울지.
     *
     * <p>기본은 직급 순이다. 임원정보 화면은 회장부터 보이는 편이 낫다. 홈의
     * "최근 임원" 자리만 이름 그대로 최근 순을 따로 청한다.
     */
    private String normalizeSort(String value) {
        if (!StringUtils.hasText(value)) {
            return "role";
        }
        return RECENT_SORT.equalsIgnoreCase(value.trim()) ? RECENT_SORT : "role";
    }

    /**
     * 커서 회원이 서 있던 직급 자리.
     *
     * <p>목록이 직급 순이라 다음 쪽은 "직급이 더 낮거나, 직급이 같고 id 가 더
     * 작은" 자리에서 이어진다. 커서로 오는 값은 회원 id 하나뿐이므로 그 회원의
     * 직급을 여기서 다시 찾는다.
     *
     * <p>커서를 받은 사이에 그 회원의 납부 기록이 사라졌으면 직급 자리를 알 수
     * 없다. 그때는 예전처럼 id 만으로 이어 붙인다 — 순서는 어긋나도 같은 사람이
     * 두 번 나오거나 "더 보기" 가 제자리를 맴도는 일은 없다.
     */
    private Integer directoryRoleSortOrder(Long cursor, LocalDate today) {
        if (cursor == null) {
            return null;
        }
        List<Integer> sortOrders = memberRepository.findDirectoryRoleSortOrders(
                cursor,
                OfficerPaymentStatus.PAID,
                today,
                today.minusDays(OfficerTerm.GRACE_DAYS),
                PageRequest.of(0, 1));
        return sortOrders.isEmpty() ? null : sortOrders.get(0);
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

    /**
     * 고른 학과와 같은 학과로 볼 학과 id 들.
     *
     * <p>목록의 학과 칸에는 이름이 바뀐 옛 학과도 그대로 남겨 둔다. 졸업할 때의
     * 이름으로 찾는 사람이 있기 때문이다. 그런데 지금까지는 한쪽 방향만 봤다 —
     * '법학과'를 고르면 '법률학과'로 등록된 동문까지 나오지만, '법률학과'를 고르면
     * '법학과'로 등록된 동기는 빠졌다. 옛 이름으로 찾는 사람을 위해 남겨 둔 항목이
     * 정작 그 사람에게 절반만 보여주고 있었다.
     *
     * <p>대표 학과를 먼저 찾고, 그 대표를 가리키는 학과를 모두 같은 학과로 본다.
     */
    private List<Long> equivalentMajorIds(Long majorId) {
        if (majorId == null) {
            return null;
        }
        // 대표 학과가 없으면 이 칸은 비어 있다. findFirst() 는 원소가 null 이면
        // NoSuchElement 가 아니라 NPE 를 던지므로 목록에서 직접 꺼낸다.
        List<Long> displayMajorIds = jdbcTemplate.query(
                "select display_major_id from majors where id = ?",
                (resultSet, rowNum) -> resultSet.getObject("display_major_id", Long.class), majorId);
        Long displayMajorId = displayMajorIds.isEmpty() ? null : displayMajorIds.get(0);
        Long representativeId = displayMajorId == null ? majorId : displayMajorId;

        List<Long> ids = new java.util.ArrayList<>();
        ids.add(representativeId);
        ids.addAll(jdbcTemplate.queryForList(
                "select id from majors where display_major_id = ?", Long.class, representativeId));
        return ids;
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

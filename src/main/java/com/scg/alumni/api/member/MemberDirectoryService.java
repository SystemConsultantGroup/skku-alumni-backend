package com.scg.alumni.api.member;

import com.scg.alumni.api.common.CursorPageResponse;
import com.scg.alumni.domain.member.Member;
import com.scg.alumni.domain.member.MemberRepository;
import com.scg.alumni.domain.member.MemberStatus;
import com.scg.alumni.domain.officer.OfficerPaymentStatus;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberDirectoryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final MemberRepository memberRepository;

    public CursorPageResponse<MemberSummaryResponse> search(
            String keyword,
            Long majorId,
            Long industryId,
            String companyName,
            Long hobbyId,
            Long cursor,
            Integer size
    ) {
        int pageSize = normalizeSize(size);
        List<Member> fetchedMembers = memberRepository.searchCurrentPaidDirectory(
                normalizeLike(keyword),
                majorId,
                industryId,
                normalizeLike(companyName),
                hobbyId,
                cursor,
                MemberStatus.ACTIVE,
                OfficerPaymentStatus.PAID,
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
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }
}

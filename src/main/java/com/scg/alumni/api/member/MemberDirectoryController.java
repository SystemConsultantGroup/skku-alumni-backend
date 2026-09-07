package com.scg.alumni.api.member;

import com.scg.alumni.api.common.CursorPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members")
public class MemberDirectoryController {

    private final MemberDirectoryService memberDirectoryService;

    /** 홈 화면이 "현재 이용 가능 임원" 으로 내보이는 수. */
    @GetMapping("/count")
    public MemberCountResponse count() {
        return new MemberCountResponse(memberDirectoryService.countVisibleOfficers());
    }

    public record MemberCountResponse(long count) {
    }

    @GetMapping
    public CursorPageResponse<MemberSummaryResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String searchType,
            @RequestParam(required = false) Long majorId,
            @RequestParam(required = false) Long industryId,
            @RequestParam(required = false) Integer admissionYear,
            @RequestParam(required = false) Long officerTermId,
            @RequestParam(required = false) Long officerRoleId,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) Long hobbyId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        return memberDirectoryService.search(keyword, searchType, majorId, industryId, admissionYear, officerTermId,
                officerRoleId, region, companyName, hobbyId, cursor, size);
    }
}

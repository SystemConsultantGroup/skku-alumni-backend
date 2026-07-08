package com.scg.alumni.api.community;

import com.scg.alumni.api.common.CursorPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/community/posts")
public class CommunityPostController {

    private final CommunityPostService communityPostService;

    @GetMapping
    public CursorPageResponse<CommunityPostSummaryResponse> findFeed(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        return communityPostService.findFeed(cursor, size);
    }
}

package com.scg.alumni.api.community;

import com.scg.alumni.api.common.CursorPageResponse;
import com.scg.alumni.domain.community.CommunityPost;
import com.scg.alumni.domain.community.CommunityPostRepository;
import com.scg.alumni.domain.community.PostKind;
import com.scg.alumni.domain.community.PostStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityPostService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final CommunityPostRepository communityPostRepository;

    public CursorPageResponse<CommunityPostSummaryResponse> findFeed(Long cursor, Integer size) {
        int pageSize = normalizeSize(size);
        List<CommunityPost> fetchedPosts = communityPostRepository.findFeed(
                PostStatus.PUBLISHED,
                PostKind.COMMUNITY,
                cursor,
                PageRequest.of(0, pageSize + 1)
        );

        boolean hasNext = fetchedPosts.size() > pageSize;
        List<CommunityPost> pagePosts = hasNext ? fetchedPosts.subList(0, pageSize) : fetchedPosts;
        List<CommunityPostSummaryResponse> items = pagePosts.stream()
                .map(CommunityPostSummaryResponse::from)
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
}

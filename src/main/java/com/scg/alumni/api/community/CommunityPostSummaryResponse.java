package com.scg.alumni.api.community;

import com.scg.alumni.domain.community.CommunityPost;

public record CommunityPostSummaryResponse(
        Long id,
        String title,
        String body,
        String thumbnailUrl,
        String authorName,
        String authorCompanyName
) {

    public static CommunityPostSummaryResponse from(CommunityPost post) {
        return new CommunityPostSummaryResponse(
                post.getId(),
                post.getTitle(),
                post.getBody(),
                post.getThumbnailUrl(),
                post.getAuthor().getName(),
                post.getAuthor().getCompany() == null ? null : post.getAuthor().getCompany().getName()
        );
    }
}

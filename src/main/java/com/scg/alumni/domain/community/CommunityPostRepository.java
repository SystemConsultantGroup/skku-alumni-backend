package com.scg.alumni.domain.community;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {

    @Query("""
            select post
            from CommunityPost post
            where post.status = :status
              and (:cursorId is null or post.id < :cursorId)
              and post.postKind = :postKind
            order by post.id desc
            """)
    List<CommunityPost> findFeed(
            @Param("status") PostStatus status,
            @Param("postKind") PostKind postKind,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );
}

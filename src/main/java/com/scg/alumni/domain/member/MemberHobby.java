package com.scg.alumni.domain.member;

import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;
import static lombok.AccessLevel.PROTECTED;

import com.scg.alumni.global.domain.BaseTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "user_hobbies",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_hobbies_user_hobby", columnNames = {"user_id", "hobby_id"})
)
@NoArgsConstructor(access = PROTECTED)
public class MemberHobby extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "hobby_id", nullable = false)
    private Hobby hobby;

    /** 관리자가 지운 취미 연결. 값이 있으면 조회에서 제외한다. */
    private java.time.LocalDateTime deletedAt;
}

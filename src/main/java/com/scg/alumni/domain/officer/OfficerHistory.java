package com.scg.alumni.domain.officer;

import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;
import static lombok.AccessLevel.PROTECTED;

import com.scg.alumni.domain.member.Member;
import com.scg.alumni.global.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Formula;

@Getter
@Entity
@Table(name = "officer_histories")
@NoArgsConstructor(access = PROTECTED)
public class OfficerHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "officer_term_id", nullable = false)
    private OfficerTerm officerTerm;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "officer_role_id", nullable = false)
    private OfficerRole officerRole;

    @Column(nullable = false)
    private LocalDate startedAt;

    private LocalDate endedAt;

    /** 관리자가 지운 임원 이력. 값이 있으면 조회에서 제외한다. */
    private java.time.LocalDateTime deletedAt;

    @Formula("(select count(*) from officer_history_approvals a where a.officer_history_id = {alias}.id)")
    private long approvalCount;

    /** 현행 임기이거나, 막 끝난 임기라도 유예 기간 안이면 유효한 납부로 본다. */
    public boolean isCurrent(LocalDate today) {
        return !officerTerm.getStartedAt().isAfter(today) && !officerTerm.getEndedAt().isBefore(today)
                && !startedAt.isAfter(today) && (endedAt == null || !endedAt.isBefore(today));
    }
}

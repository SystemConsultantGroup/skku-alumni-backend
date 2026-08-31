package com.scg.alumni.domain.officer;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;
import static lombok.AccessLevel.PROTECTED;

import com.scg.alumni.domain.member.Member;
import com.scg.alumni.global.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    @Enumerated(STRING)
    @Column(nullable = false)
    private OfficerPaymentStatus paymentStatus;

    /** 관리자가 지운 임원 이력. 값이 있으면 조회에서 제외한다. */
    private java.time.LocalDateTime deletedAt;

    /** 현행 임기이거나, 막 끝난 임기라도 유예 기간 안이면 유효한 납부로 본다. */
    public boolean isCurrentPaid() {
        return paymentStatus == OfficerPaymentStatus.PAID
                && officerTerm.isAccessible(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
    }
}

package com.scg.alumni.domain.officer;

import static jakarta.persistence.GenerationType.IDENTITY;
import static lombok.AccessLevel.PROTECTED;

import com.scg.alumni.global.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "officer_terms")
@NoArgsConstructor(access = PROTECTED)
public class OfficerTerm extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int generation;

    @Column(nullable = false)
    private int phase;

    @Column(nullable = false)
    private LocalDate startedAt;

    @Column(nullable = false)
    private LocalDate endedAt;

    @Column(nullable = false)
    private boolean currentTerm;

    /**
     * 임기가 바뀐 직후 회비를 내기 전까지 서비스가 막히지 않도록 두는 유예 기간(일).
     *
     * <p>회비는 임기가 넘어간 뒤에 걷힌다. 4월 30일에 임기가 끝나자마자 명단이
     * 사라지면 납부 전까지 아무것도 못 하게 되므로, 회의에서 "봐주는 기간"을
     * 두기로 했다.
     */
    public static final int GRACE_DAYS = 30;

    /** 유예 기간까지 감안해 이 임기의 납부 기록이 아직 유효한지. */
    public boolean isAccessible(java.time.LocalDate today) {
        return !startedAt.isAfter(today) && !endedAt.isBefore(today.minusDays(GRACE_DAYS));
    }

    public String getDisplayName() {
        return generation + "대 " + phase + "기";
    }
}

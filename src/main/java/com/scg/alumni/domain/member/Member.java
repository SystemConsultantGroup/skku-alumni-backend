package com.scg.alumni.domain.member;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;
import static lombok.AccessLevel.PROTECTED;

import com.scg.alumni.domain.academic.Major;
import com.scg.alumni.domain.officer.OfficerHistory;
import com.scg.alumni.global.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(nullable = true, unique = true)
    private String studentId;

    /** 학교 포털 아이디. 사무처가 명단에 적어 넣는 참고값이고, 로그인에는 쓰지 않는다. */
    @Column(unique = true)
    private String kingoId;

    /** 회원이 직접 정한 로그인 아이디. 비어 있으면 아직 계정을 등록하지 않은 회원이다. */
    @Column(unique = true)
    private String loginId;

    private String name;

    @Column(nullable = true)
    private String password;

    private LocalDate birthDate;

    @Enumerated(STRING)
    private Gender gender;

    @Enumerated(STRING)
    private MemberCategory category;

    @Enumerated(STRING)
    private DegreeType degree;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "major_id")
    private Major major;

    private Integer admissionYear;

    private Integer graduationYear;

    private String nationality;

    private String homeZipcode;

    private String homeAddress1;

    private String homeAddress2;

    private String workZipcode;

    private String workAddress1;

    private String workAddress2;

    @Column(nullable = false)
    private boolean homeAddressPublic;

    @Column(nullable = false)
    private boolean notificationEnabled;

    private String phone;

    private String email;

    @Column(nullable = false)
    private boolean emailReceive = true;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "industry_id")
    private Industry industry;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    private String jobTitle;

    @Enumerated(STRING)
    @Column(nullable = false)
    private MemberStatus status;

    @Column(columnDefinition = "text")
    private String prText;

    /** 관리자가 지운 회원. 본인 탈퇴(status = WITHDRAWN)와 달리 데이터를 남겨둔다. */
    private java.time.LocalDateTime deletedAt;

    @OneToMany(mappedBy = "member")
    private Set<MemberHobby> hobbies = new LinkedHashSet<>();

    @OneToMany(mappedBy = "member")
    private List<OfficerHistory> officerHistories = new ArrayList<>();

    /** 현직 승인 이력 중 최신 임기의 직책을 표시한다. ACTIVE 계정은 기존 이력을 인정한다. */
    public Optional<OfficerHistory> currentOfficerHistory(java.time.LocalDate today) {
        return officerHistories.stream()
                .filter(history -> history.getDeletedAt() == null)
                .filter(history -> status == MemberStatus.ACTIVE || history.getApprovalCount() > 0)
                .filter(history -> history.isCurrent(today))
                .max(java.util.Comparator.comparing(history -> history.getOfficerTerm().getStartedAt()));
    }
}

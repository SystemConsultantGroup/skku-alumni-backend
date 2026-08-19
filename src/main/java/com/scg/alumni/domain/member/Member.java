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

    @Column(unique = true)
    private String kingoId;

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

    @OneToMany(mappedBy = "member")
    private Set<MemberHobby> hobbies = new LinkedHashSet<>();

    @OneToMany(mappedBy = "member")
    private List<OfficerHistory> officerHistories = new ArrayList<>();

    /**
     * 지금 유효한 납부 이력. 유예 기간 때문에 직전 임기와 현행 임기가 함께
     * 유효할 수 있으므로, 둘 다 냈다면 최신 임기 쪽을 보여준다.
     */
    public Optional<OfficerHistory> currentPaidOfficerHistory() {
        return officerHistories.stream()
                .filter(OfficerHistory::isCurrentPaid)
                .max(java.util.Comparator.comparing(history -> history.getOfficerTerm().getStartedAt()));
    }
}

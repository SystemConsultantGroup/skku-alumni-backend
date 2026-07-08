package com.scg.alumni.domain.academic;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;
import static lombok.AccessLevel.PROTECTED;

import com.scg.alumni.global.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "majors")
@NoArgsConstructor(access = PROTECTED)
public class Major extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String normalizedName;

    @Enumerated(STRING)
    @Column(nullable = false)
    private MajorStatus status;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "college_id")
    private College college;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "display_major_id")
    private Major displayMajor;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "parent_id")
    private Major parentMajor;

    public String getDisplayName() {
        if (displayMajor == null) {
            return name;
        }
        return displayMajor.getName();
    }
}

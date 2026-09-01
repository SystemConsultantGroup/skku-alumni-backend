package com.scg.alumni.api.member;

import com.scg.alumni.domain.member.Hobby;
import com.scg.alumni.domain.member.Member;
import com.scg.alumni.domain.member.MemberHobby;
import com.scg.alumni.domain.officer.OfficerHistory;
import java.util.List;

public record MemberSummaryResponse(
        Long id,
        String name,
        String majorName,
        Integer admissionYear,
        Integer graduationYear,
        String companyName,
        String jobTitle,
        String industryName,
        String officerRoleName,
        String officerTermName,
        List<String> hobbies,
        String prText
) {

    public static MemberSummaryResponse from(Member member) {
        OfficerHistory currentHistory = member.currentPaidOfficerHistory().orElse(null);

        return new MemberSummaryResponse(
                member.getId(),
                member.getName(),
                member.getMajor().getDisplayName(),
                member.getAdmissionYear(),
                member.getGraduationYear(),
                member.getCompany() == null ? null : member.getCompany().getName(),
                member.getJobTitle(),
                member.getIndustry() == null ? null : member.getIndustry().getName(),
                currentHistory == null ? null : currentHistory.getOfficerRole().getName(),
                currentHistory == null ? null : currentHistory.getOfficerTerm().getDisplayName(),
                member.getHobbies().stream()
                        .filter(memberHobby -> memberHobby.getDeletedAt() == null)
                        .map(MemberHobby::getHobby)
                        .map(Hobby::getName)
                        .sorted()
                        .toList(),
                member.getPrText()
        );
    }
}

package com.scg.alumni.domain.member;

import com.scg.alumni.domain.officer.OfficerPaymentStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

    @Query("""
            select m
            from Member m
            left join m.major.displayMajor displayMajor
            left join m.company company
            left join m.industry industry
            where m.status = :memberStatus
              and (:cursorId is null or m.id < :cursorId)
              and m.id not in :blockedMemberIds
              and exists (
                  select h.id
                  from OfficerHistory h
                  join h.officerTerm term
                  where h.member = m
                    and h.paymentStatus = :paymentStatus
                    and term.currentTerm = true
                    and (:officerTermId is null or term.id = :officerTermId)
                    and (:officerRoleId is null or h.officerRole.id = :officerRoleId)
              )
              and (
                  :keyword is null
                  or (:searchType = 'all' and (
                      replace(lower(m.name), ' ', '') like :keyword
                      or replace(lower(m.major.name), ' ', '') like :keyword
                      or replace(lower(coalesce(displayMajor.name, '')), ' ', '') like :keyword
                      or replace(lower(coalesce(m.studentId, '')), ' ', '') like :keyword
                      or str(m.admissionYear) like :keyword
                      or replace(lower(coalesce(company.name, '')), ' ', '') like :keyword
                      or replace(lower(coalesce(m.jobTitle, '')), ' ', '') like :keyword
                      or replace(lower(coalesce(industry.name, '')), ' ', '') like :keyword
                      or exists (select allHistory.id from OfficerHistory allHistory where allHistory.member = m and allHistory.paymentStatus = :paymentStatus and allHistory.officerTerm.currentTerm = true and (replace(lower(allHistory.officerRole.name), ' ', '') like :keyword or replace(lower(concat(str(allHistory.officerTerm.generation), '대', str(allHistory.officerTerm.phase), '기')), ' ', '') like :keyword))
                      or replace(lower(concat(coalesce(m.workAddress1, ''), coalesce(m.workAddress2, ''))), ' ', '') like :keyword
                      or (m.homeAddressPublic = true and replace(lower(concat(coalesce(m.homeAddress1, ''), coalesce(m.homeAddress2, ''))), ' ', '') like :keyword)
                  ))
                  or (:searchType = 'major' and (replace(lower(m.major.name), ' ', '') like :keyword or replace(lower(coalesce(displayMajor.name, '')), ' ', '') like :keyword))
                  or (:searchType = 'industry' and replace(lower(coalesce(industry.name, '')), ' ', '') like :keyword)
                  or (:searchType = 'admission' and (replace(lower(coalesce(m.studentId, '')), ' ', '') like :studentIdKeyword or str(m.admissionYear) like :admissionYearKeyword))
                  or (:searchType = 'term' and exists (select termHistory.id from OfficerHistory termHistory where termHistory.member = m and termHistory.paymentStatus = :paymentStatus and termHistory.officerTerm.currentTerm = true and replace(lower(concat(str(termHistory.officerTerm.generation), '대', str(termHistory.officerTerm.phase), '기')), ' ', '') like :keyword))
                  or (:searchType = 'role' and exists (select roleHistory.id from OfficerHistory roleHistory where roleHistory.member = m and roleHistory.paymentStatus = :paymentStatus and roleHistory.officerTerm.currentTerm = true and replace(lower(roleHistory.officerRole.name), ' ', '') like :keyword))
                  or (:searchType = 'region' and (replace(lower(concat(coalesce(m.workAddress1, ''), coalesce(m.workAddress2, ''))), ' ', '') like :keyword or (m.homeAddressPublic = true and replace(lower(concat(coalesce(m.homeAddress1, ''), coalesce(m.homeAddress2, ''))), ' ', '') like :keyword)))
              )
              and (:majorId is null or m.major.id = :majorId or displayMajor.id = :majorId)
              and (:industryId is null or industry.id = :industryId)
              and (:admissionYear is null or m.admissionYear = :admissionYear)
              and (
                  :region is null
                  or replace(lower(concat(coalesce(m.workAddress1, ''), coalesce(m.workAddress2, ''))), ' ', '') like :region
                  or (m.homeAddressPublic = true and replace(lower(concat(coalesce(m.homeAddress1, ''), coalesce(m.homeAddress2, ''))), ' ', '') like :region)
              )
              and (:companyName is null or replace(lower(coalesce(company.name, '')), ' ', '') like :companyName)
              and (
                  :hobbyId is null
                  or exists (
                      select memberHobby.id
                      from MemberHobby memberHobby
                      where memberHobby.member = m
                        and memberHobby.hobby.id = :hobbyId
                  )
              )
            order by m.id desc
            """)
    List<Member> searchCurrentPaidDirectory(
            @Param("keyword") String keyword,
            @Param("searchType") String searchType,
            @Param("admissionYearKeyword") String admissionYearKeyword,
            @Param("studentIdKeyword") String studentIdKeyword,
            @Param("majorId") Long majorId,
            @Param("industryId") Long industryId,
            @Param("admissionYear") Integer admissionYear,
            @Param("officerTermId") Long officerTermId,
            @Param("officerRoleId") Long officerRoleId,
            @Param("region") String region,
            @Param("companyName") String companyName,
            @Param("hobbyId") Long hobbyId,
            @Param("cursorId") Long cursorId,
            @Param("blockedMemberIds") List<Long> blockedMemberIds,
            @Param("memberStatus") MemberStatus memberStatus,
            @Param("paymentStatus") OfficerPaymentStatus paymentStatus,
            Pageable pageable
    );
}

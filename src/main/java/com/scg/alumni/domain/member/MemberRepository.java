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
              and exists (
                  select h.id
                  from OfficerHistory h
                  join h.officerTerm term
                  where h.member = m
                    and h.paymentStatus = :paymentStatus
                    and term.currentTerm = true
              )
              and (
                  :keyword is null
                  or lower(m.name) like :keyword
                  or lower(coalesce(company.name, '')) like :keyword
                  or lower(coalesce(m.jobTitle, '')) like :keyword
                  or lower(coalesce(industry.name, '')) like :keyword
              )
              and (:majorId is null or m.major.id = :majorId or displayMajor.id = :majorId)
              and (:industryId is null or industry.id = :industryId)
              and (:companyName is null or lower(coalesce(company.name, '')) like :companyName)
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
            @Param("majorId") Long majorId,
            @Param("industryId") Long industryId,
            @Param("companyName") String companyName,
            @Param("hobbyId") Long hobbyId,
            @Param("cursorId") Long cursorId,
            @Param("memberStatus") MemberStatus memberStatus,
            @Param("paymentStatus") OfficerPaymentStatus paymentStatus,
            Pageable pageable
    );
}

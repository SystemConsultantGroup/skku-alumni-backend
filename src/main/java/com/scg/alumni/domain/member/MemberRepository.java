package com.scg.alumni.domain.member;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * 주소록 목록.
     *
     * <p>임원정보 화면의 순서는 직급이 높은 사람부터다. 회장·부회장을 찾으려고
     * 이름을 모르는 채 목록을 내려가는 사용자가 많은데, 가입한 순서(id 역순)로
     * 늘어놓으면 회장이 몇 페이지 뒤에 묻힌다. {@code officer_roles.sort_order} 는
     * 회장 10, 부회장 20 … 처럼 높은 자리일수록 작은 값이라 오름차순이 곧 직급 순이다.
     *
     * <p>정렬 직급은 카드와 같이 승인된 현직 이력 중 최신 임기를 기준으로 한다.
     *
     * <p>홈의 "최근 임원" 자리는 직급이 아니라 최근에 들어온 순서를 보여주는 곳이라
     * {@code roleOrderWeight} 를 0 으로 받는다. 그러면 직급 칸이 모두 같은 값이 되어
     * 순서는 id 역순만 남는다.
     *
     * <p>학과는 저장된 이름으로 비교하지 않는다. 저장된 이름에는 야간 표기가 남아 있어
     * 검색어 '(야)' 가 야간 졸업생만 골라낸다. 검색어에 걸리는 학과 id 는
     * {@code MajorCatalog} 가 야간 표기를 지운 이름으로 미리 골라 {@code keywordMajorIds} 로 넘긴다.
     */
    @Query("""
            select m
            from Member m
            left join m.major major
            left join m.company company
            left join m.industry industry
            join OfficerHistory currentHistory
                on currentHistory.member = m
               and currentHistory.deletedAt is null
               and currentHistory.approvalCount > 0
               and currentHistory.startedAt <= :today
               and (currentHistory.endedAt is null or currentHistory.endedAt >= :today)
               and currentHistory.officerTerm.startedAt <= :today
               and currentHistory.officerTerm.endedAt >= :graceFloor
               and currentHistory.officerTerm.endedAt >= :today
               and currentHistory.officerTerm.startedAt = (
                       select max(latestHistory.officerTerm.startedAt)
                       from OfficerHistory latestHistory
                       where latestHistory.member = m
                         and latestHistory.deletedAt is null
                         and latestHistory.approvalCount > 0
                         and latestHistory.startedAt <= :today
                         and (latestHistory.endedAt is null or latestHistory.endedAt >= :today)
                         and latestHistory.officerTerm.startedAt <= :today
                         and latestHistory.officerTerm.endedAt >= :graceFloor
                         and latestHistory.officerTerm.endedAt >= :today
                   )
            where m.status <> com.scg.alumni.domain.member.MemberStatus.WITHDRAWN
              and m.deletedAt is null
              and (
                  :cursorId is null
                  or (:cursorSortOrder is null and m.id < :cursorId)
                  or currentHistory.officerRole.sortOrder > :cursorSortOrder
                  or (currentHistory.officerRole.sortOrder = :cursorSortOrder and m.id < :cursorId)
              )
              and m.id not in :blockedMemberIds
              and exists (
                  select h.id
                  from OfficerHistory h
                  join h.officerTerm term
                  where h.member = m
                    and h.deletedAt is null
                    and h.approvalCount > 0
                    and h.startedAt <= :today
                    and (h.endedAt is null or h.endedAt >= :today)
                    and term.startedAt <= :today and term.endedAt >= :graceFloor
                    and term.endedAt >= :today
                    and (:officerTermId is null or term.id = :officerTermId)
                    and (:officerRoleId is null or h.officerRole.id = :officerRoleId)
              )
              and (
                  :keyword is null
                  or (:searchType = 'all' and (
                      replace(lower(m.name), ' ', '') like :keyword
                      or major.id in :keywordMajorIds
                      or replace(lower(coalesce(m.studentId, '')), ' ', '') like :keyword
                      or str(m.admissionYear) like :keyword
                      or replace(lower(coalesce(company.name, '')), ' ', '') like :keyword
                      or replace(lower(coalesce(m.jobTitle, '')), ' ', '') like :keyword
                      or replace(lower(coalesce(industry.name, '')), ' ', '') like :keyword
                      or exists (select allHistory.id from OfficerHistory allHistory where allHistory.member = m and allHistory.deletedAt is null and allHistory.approvalCount > 0 and allHistory.startedAt <= :today and (allHistory.endedAt is null or allHistory.endedAt >= :today) and allHistory.officerTerm.startedAt <= :today and allHistory.officerTerm.endedAt >= :today and (replace(lower(allHistory.officerRole.name), ' ', '') like :keyword or replace(lower(concat(str(allHistory.officerTerm.generation), '대', str(allHistory.officerTerm.phase), '기')), ' ', '') like :keyword))
                      or replace(lower(concat(coalesce(m.workAddress1, ''), coalesce(m.workAddress2, ''))), ' ', '') like :keyword
                      or (m.homeAddressPublic = true and replace(lower(concat(coalesce(m.homeAddress1, ''), coalesce(m.homeAddress2, ''))), ' ', '') like :keyword)
                  ))
                  or (:searchType = 'major' and major.id in :keywordMajorIds)
                  or (:searchType = 'industry' and replace(lower(coalesce(industry.name, '')), ' ', '') like :keyword)
                  or (:searchType = 'admission' and (replace(lower(coalesce(m.studentId, '')), ' ', '') like :studentIdKeyword or str(m.admissionYear) like :admissionYearKeyword))
                  or (:searchType = 'term' and exists (select termHistory.id from OfficerHistory termHistory where termHistory.member = m and termHistory.deletedAt is null and termHistory.approvalCount > 0 and termHistory.startedAt <= :today and (termHistory.endedAt is null or termHistory.endedAt >= :today) and termHistory.officerTerm.startedAt <= :today and termHistory.officerTerm.endedAt >= :today and replace(lower(concat(str(termHistory.officerTerm.generation), '대', str(termHistory.officerTerm.phase), '기')), ' ', '') like :keyword))
                  or (:searchType = 'role' and exists (select roleHistory.id from OfficerHistory roleHistory where roleHistory.member = m and roleHistory.deletedAt is null and roleHistory.approvalCount > 0 and roleHistory.startedAt <= :today and (roleHistory.endedAt is null or roleHistory.endedAt >= :today) and roleHistory.officerTerm.startedAt <= :today and roleHistory.officerTerm.endedAt >= :today and replace(lower(roleHistory.officerRole.name), ' ', '') like :keyword))
                  or (:searchType = 'region' and (replace(lower(concat(coalesce(m.workAddress1, ''), coalesce(m.workAddress2, ''))), ' ', '') like :keyword or (m.homeAddressPublic = true and replace(lower(concat(coalesce(m.homeAddress1, ''), coalesce(m.homeAddress2, ''))), ' ', '') like :keyword)))
              )
              and (:majorIds is null or major.id in :majorIds)
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
                        and memberHobby.deletedAt is null
                        and memberHobby.hobby.id = :hobbyId
                  )
              )
            order by currentHistory.officerRole.sortOrder * :roleOrderWeight asc, m.id desc
            """)
    List<Member> searchCurrentPaidDirectory(
            @Param("keyword") String keyword,
            @Param("searchType") String searchType,
            @Param("admissionYearKeyword") String admissionYearKeyword,
            @Param("studentIdKeyword") String studentIdKeyword,
            @Param("keywordMajorIds") List<Long> keywordMajorIds,
            @Param("majorIds") List<Long> majorIds,
            @Param("industryId") Long industryId,
            @Param("admissionYear") Integer admissionYear,
            @Param("officerTermId") Long officerTermId,
            @Param("officerRoleId") Long officerRoleId,
            @Param("region") String region,
            @Param("companyName") String companyName,
            @Param("hobbyId") Long hobbyId,
            @Param("cursorId") Long cursorId,
            @Param("cursorSortOrder") Integer cursorSortOrder,
            @Param("roleOrderWeight") int roleOrderWeight,
            @Param("blockedMemberIds") List<Long> blockedMemberIds,
            @Param("today") java.time.LocalDate today,
            @Param("graceFloor") java.time.LocalDate graceFloor,
            Pageable pageable
    );

    /**
     * 커서로 받은 회원이 목록에서 서 있던 직급 자리.
     *
     * <p>목록이 직급 순으로 바뀌면서 "마지막으로 본 id 다음부터"만으로는 다음 쪽을
     * 집을 수 없다. 직급이 같은 구간 안에서만 id 가 순서를 정하기 때문이다. 커서
     * 회원의 직급 값을 함께 알아야 (직급, id) 두 칸짜리 자리를 찾을 수 있다.
     *
     * <p>정렬에 쓰는 기록과 같은 것을 골라야 하므로 시작일이 늦은 임기부터 준다.
     */
    @Query("""
            select history.officerRole.sortOrder
            from OfficerHistory history
            where history.member.id = :memberId
              and history.approvalCount > 0
              and history.deletedAt is null
              and history.startedAt <= :today
              and (history.endedAt is null or history.endedAt >= :today)
              and history.officerTerm.startedAt <= :today
              and history.officerTerm.endedAt >= :graceFloor
              and history.officerTerm.endedAt >= :today
            order by history.officerTerm.startedAt desc
            """)
    List<Integer> findDirectoryRoleSortOrders(
            @Param("memberId") Long memberId,
            @Param("today") java.time.LocalDate today,
            @Param("graceFloor") java.time.LocalDate graceFloor,
            Pageable pageable
    );

    /**
     * 지금 주소록에 보이는 임원 수.
     *
     * <p>검색 조건 없이 {@code searchCurrentPaidDirectory} 와 같은 뼈대만 센다 —
     * 승인된 현직 임원 중 내가 차단하지 않은 사람을 납부 여부와 무관하게 센다.
     */
    @Query("""
            select count(m)
            from Member m
            where m.status <> com.scg.alumni.domain.member.MemberStatus.WITHDRAWN
              and m.deletedAt is null
              and m.id not in :blockedMemberIds
              and exists (
                  select h.id
                  from OfficerHistory h
                  join h.officerTerm term
                  where h.member = m
                    and h.deletedAt is null
                    and h.approvalCount > 0
                    and h.startedAt <= :today
                    and (h.endedAt is null or h.endedAt >= :today)
                    and term.startedAt <= :today and term.endedAt >= :graceFloor
                    and term.endedAt >= :today
              )
            """)
    long countCurrentPaidDirectory(
            @Param("blockedMemberIds") List<Long> blockedMemberIds,
            @Param("today") java.time.LocalDate today,
            @Param("graceFloor") java.time.LocalDate graceFloor
    );
}

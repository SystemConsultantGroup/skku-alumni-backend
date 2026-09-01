package com.scg.alumni.api.operations;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 회원에게 현행 임기의 임원 직책과 회비 내역을 붙인다.
 *
 * <p>임원 신청을 승인할 때와 엑셀로 명단을 부을 때가 같은 일을 한다. 한쪽에만
 * 회비 내역을 만들면 회원 목록의 회비 칸이 비어 승인 화면에서 활성화가 막힌다.
 *
 * <p>회비 금액은 officer_roles 에 있다. 회칙에 따라 바뀌는 값이라 코드에 박아두면
 * 조정할 때마다 배포해야 한다.
 */
@Component
@RequiredArgsConstructor
public class OfficerAssignment {

    private final JdbcTemplate jdbcTemplate;

    /** 직책 이름으로 id를 찾는다. 공백과 대소문자는 무시한다. */
    public Long findRoleId(String roleName) {
        if (!StringUtils.hasText(roleName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "임원 직책이 필요합니다.");
        }
        return findRoleIdOrNull(roleName)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "일치하는 임원 직급을 찾을 수 없습니다."));
    }

    public java.util.Optional<Long> findRoleIdOrNull(String roleName) {
        if (!StringUtils.hasText(roleName)) {
            return java.util.Optional.empty();
        }
        return jdbcTemplate.query("""
                select id
                from officer_roles
                where lower(replace(name, ' ', '')) = ?
                order by id
                limit 1
                """, (resultSet, rowNum) -> resultSet.getLong("id"), normalize(roleName))
                .stream()
                .findFirst();
    }

    public Long findCurrentTermId() {
        return findCurrentTermIdOrNull()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "현행 임기를 찾을 수 없습니다."));
    }

    public java.util.Optional<Long> findCurrentTermIdOrNull() {
        return jdbcTemplate.query("""
                select id
                from officer_terms
                where current_term = true
                order by id desc
                limit 1
                """, (resultSet, rowNum) -> resultSet.getLong("id"))
                .stream()
                .findFirst();
    }

    /**
     * 임원 이력을 만들거나 직책을 고친다.
     *
     * <p>이미 있는 임기면 직책만 바꾸고 납부 여부는 그대로 둔다. 명단을 다시
     * 부었다고 이미 확인한 납부가 미납으로 돌아가면 안 된다.
     */
    public void ensureHistory(Long memberId, Long officerTermId, Long officerRoleId) {
        int updated = jdbcTemplate.update("""
                update officer_histories
                set officer_role_id = ?, deleted_at = null, updated_at = CURRENT_TIMESTAMP
                where user_id = ? and officer_term_id = ?
                """, officerRoleId, memberId, officerTermId);
        if (updated > 0) {
            return;
        }
        jdbcTemplate.update("""
                insert into officer_histories (
                    user_id, officer_term_id, officer_role_id, started_at, payment_status, created_at, updated_at
                )
                select ?, id, ?, started_at, 'UNPAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                from officer_terms
                where id = ?
                """, memberId, officerRoleId, officerTermId);
    }

    /** 회비 내역이 없으면 직책별 기여금으로 만든다. 이미 있으면 금액을 건드리지 않는다. */
    public void ensurePaymentRecord(Long memberId, Long officerTermId, Long officerRoleId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from payment_records
                where user_id = ? and officer_term_id = ?
                """, Integer.class, memberId, officerTermId);
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    update payment_records
                    set deleted_at = null, updated_at = CURRENT_TIMESTAMP
                    where user_id = ? and officer_term_id = ? and deleted_at is not null
                    """, memberId, officerTermId);
            return;
        }
        jdbcTemplate.update("""
                insert into payment_records (
                    user_id, officer_term_id, amount, status, created_at, updated_at
                ) values (?, ?, ?, 'UNPAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, memberId, officerTermId, duesAmount(officerRoleId));
    }

    /** 직책과 회비를 함께 붙인다. */
    public void assign(Long memberId, Long officerTermId, Long officerRoleId) {
        ensureHistory(memberId, officerTermId, officerRoleId);
        ensurePaymentRecord(memberId, officerTermId, officerRoleId);
    }

    /**
     * 직책별 임원 기여금.
     *
     * <p>0은 "정해진 금액 없음"이다. 회장처럼 약정으로 정하는 직책이 여기 해당하고,
     * 이 경우 사무처가 납부를 등록할 때 금액을 직접 넣는다.
     */
    public int duesAmount(Long officerRoleId) {
        Integer amount = jdbcTemplate.query(
                "select dues_amount from officer_roles where id = ?",
                (resultSet, rowNum) -> resultSet.getInt(1), officerRoleId)
                .stream()
                .findFirst()
                .orElse(0);
        return amount == null ? 0 : amount;
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}

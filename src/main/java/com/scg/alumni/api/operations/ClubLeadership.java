package com.scg.alumni.api.operations;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 동호회의 회장·매니저가 누구인지를 한 곳에서 정한다.
 *
 * <p>권한과 화면은 모두 {@code clubs.president_user_id} / {@code clubs.manager_user_id}
 * 를 본다. {@code club_members.club_role} 은 그 결과를 목록에 보여주기 위한 사본이다.
 * 사본만 고치면 "회장" 이라고 적혀 있는데 관리 버튼은 없고 동호회 목록에는 옛 회장이
 * 남는 상태가 된다.
 *
 * <p>회원 앱(회장이 후임을 지목)과 관리자 앱(사무처가 역할을 고침)이 같은 일을 하므로
 * 규칙을 여기 모은다.
 */
@Component
@RequiredArgsConstructor
public class ClubLeadership {

    public static final String PRESIDENT = "PRESIDENT";
    public static final String MANAGER = "MANAGER";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 회원에게 동호회 역할을 준다.
     *
     * <p>회장과 매니저는 각각 한 명이라, 새로 앉히면 앞사람은 일반 회원이 된다.
     * 한 사람이 두 자리를 겸할 수 없으므로 다른 자리에 있었다면 그 자리는 비운다.
     */
    public void assign(Long clubId, Long userId, String clubRole) {
        if (PRESIDENT.equals(clubRole)) {
            jdbcTemplate.update("""
                    update clubs
                    set president_user_id = ?,
                        manager_user_id = case when manager_user_id = ? then null else manager_user_id end,
                        updated_at = CURRENT_TIMESTAMP
                    where id = ?
                    """, userId, userId, clubId);
        } else if (MANAGER.equals(clubRole)) {
            jdbcTemplate.update("""
                    update clubs
                    set manager_user_id = ?,
                        president_user_id = case when president_user_id = ? then null else president_user_id end,
                        updated_at = CURRENT_TIMESTAMP
                    where id = ?
                    """, userId, userId, clubId);
        } else {
            resign(clubId, userId);
            return;
        }
        synchronizeRoles(clubId);
    }

    /** 그 회원이 맡고 있던 자리를 비운다. 맡은 자리가 없으면 아무 일도 하지 않는다. */
    public void resign(Long clubId, Long userId) {
        jdbcTemplate.update("""
                update clubs
                set president_user_id = case when president_user_id = ? then null else president_user_id end,
                    manager_user_id = case when manager_user_id = ? then null else manager_user_id end,
                    updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, userId, userId, clubId);
        synchronizeRoles(clubId);
    }

    /** 그 회원이 어느 동호회에서든 맡고 있던 자리를 모두 비운다. */
    public void resignEverywhere(Long userId) {
        jdbcTemplate.queryForList("""
                select id from clubs where president_user_id = ? or manager_user_id = ?
                """, Long.class, userId, userId)
                .forEach(clubId -> resign(clubId, userId));
    }

    /** 그 회원이 자리를 맡고 있는 동호회 이름과 역할. 지우기 전에 무엇이 비는지 알리기 위한 것. */
    public java.util.List<String> leaderships(Long userId) {
        return jdbcTemplate.query("""
                select name, case when president_user_id = ? then '회장' else '총무' end as role_name
                from clubs
                where president_user_id = ? or manager_user_id = ?
                order by id
                """, (resultSet, rowNum) -> resultSet.getString("name") + " " + resultSet.getString("role_name"),
                userId, userId, userId);
    }

    /** clubs 의 두 칸을 club_members.club_role 에 옮겨 적는다. */
    public void synchronizeRoles(Long clubId) {
        jdbcTemplate.update("""
                update club_members set club_role = 'MEMBER'
                where club_id = ? and left_at is null
                """, clubId);
        jdbcTemplate.update("""
                update club_members set club_role = 'PRESIDENT'
                where club_id = ? and left_at is null
                  and user_id = (select president_user_id from clubs where id = ?)
                """, clubId, clubId);
        jdbcTemplate.update("""
                update club_members set club_role = 'MANAGER'
                where club_id = ? and left_at is null
                  and user_id = (select manager_user_id from clubs where id = ?)
                """, clubId, clubId);
    }
}

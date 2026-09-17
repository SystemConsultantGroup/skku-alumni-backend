package com.scg.alumni.domain.academic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 회원 쪽에서 학과를 고르고 찾는 자리가 함께 쓰는 학과 목록.
 *
 * <p>야간 표기는 관리자에게만 보인다. 본인에게도 동문에게도 보이지 않는다.
 * 이름만 지워서는 부족하다 — 목록에 '(야)경제학과' 가 따로 있거나, 검색어 '(야)' 가
 * 저장된 이름에 걸리면 결과 자체가 야간 졸업생 명단이 된다. 그래서 회원 쪽의
 * 목록·필터·검색은 모두 야간 표기를 지운 이름을 기준으로 삼고, 주간·야간 학과를
 * 한 학과로 묶는다.
 *
 * <p>학과 수가 수백 개 수준이라 전부 읽어 자바에서 묶는다.
 * 규칙 자체는 {@link MajorNames} 에 있다.
 */
@Component
@RequiredArgsConstructor
public class MajorCatalog {

    /** 빈 목록이면 in () 가 문법 오류다. 어디에도 없는 id 를 넣어 아무도 찾지 않게 한다. */
    public static final long NO_MAJOR = -1L;

    private final JdbcTemplate jdbcTemplate;

    private record Row(long id, String name, String normalizedName, String status, Long displayMajorId) {

        boolean night() {
            return !MajorNames.stripNightMarkers(name, name).equals(name.trim());
        }
    }

    /** 관리자가 보는 목록. 저장된 이름 그대로다. */
    public List<Map<String, Object>> adminOptions() {
        return rows().stream().map(row -> option(row.id(), row.name(), row.normalizedName(), row.status(),
                row.displayMajorId())).toList();
    }

    /**
     * 회원 쪽 화면이 고르는 목록.
     *
     * <p>야간 표기를 지우고, 지운 이름이 주간 학과와 겹치면 주간 학과 하나만 남긴다.
     * 사라진 야간 학과를 가리키던 대표 학과 연결은 남은 학과로 옮긴다.
     */
    public List<Map<String, Object>> memberOptions() {
        List<Row> rows = rows();
        Map<String, Row> survivorByKey = new LinkedHashMap<>();
        for (Row row : rows) {
            String key = MajorNames.canonicalKey(row.name());
            Row existing = survivorByKey.get(key);
            if (existing == null || (existing.night() && !row.night())) {
                survivorByKey.put(key, row);
            }
        }
        Map<Long, Long> survivorId = new HashMap<>();
        for (Row row : rows) {
            survivorId.put(row.id(), survivorByKey.get(MajorNames.canonicalKey(row.name())).id());
        }
        return survivorByKey.values().stream()
                .map(row -> option(
                        row.id(),
                        MajorNames.stripNightMarkers(row.name(), row.name()),
                        row.normalizedName() == null ? null
                                : MajorNames.stripNightMarkers(row.normalizedName(), row.normalizedName()),
                        row.status(),
                        row.displayMajorId() == null ? null : survivorId.getOrDefault(row.displayMajorId(), row.displayMajorId())))
                .sorted(Comparator.comparing(option -> (String) option.get("name")))
                .toList();
    }

    /**
     * 고른 학과와 같은 학과로 볼 학과 id 들.
     *
     * <p>회원에게 보이는 이름이 같으면 같은 학과다. 이름이 바뀐 학과(대표 학과 연결)와
     * 야간 학과가 함께 묶인다.
     */
    public List<Long> sameMajorIds(Long majorId) {
        if (majorId == null) {
            return null;
        }
        List<Row> rows = rows();
        Map<Long, Row> byId = byId(rows);
        Row target = byId.get(majorId);
        if (target == null) {
            return List.of(majorId);
        }
        String wanted = MajorNames.canonicalKey(shownName(target, byId));
        List<Long> ids = new ArrayList<>();
        for (Row row : rows) {
            if (wanted.equals(MajorNames.canonicalKey(shownName(row, byId)))) {
                ids.add(row.id());
            }
        }
        return ids;
    }

    /**
     * 검색어가 학과명에 걸리는 학과 id 들.
     *
     * <p>야간 표기를 지운 이름끼리 비교한다. 검색어에 '(야)' 를 넣어도 야간 학과만
     * 골라지지 않는다.
     */
    public List<Long> idsMatching(String keyword) {
        String wanted = keyword == null ? "" : MajorNames.canonicalKey(keyword);
        if (wanted.isEmpty()) {
            return List.of(NO_MAJOR);
        }
        List<Row> rows = rows();
        Map<Long, Row> byId = byId(rows);
        List<Long> ids = new ArrayList<>();
        for (Row row : rows) {
            if (MajorNames.canonicalKey(row.name()).contains(wanted)
                    || MajorNames.canonicalKey(shownName(row, byId)).contains(wanted)) {
                ids.add(row.id());
            }
        }
        return ids.isEmpty() ? List.of(NO_MAJOR) : ids;
    }

    private List<Row> rows() {
        return jdbcTemplate.query("""
                select id, name, normalized_name, status, display_major_id
                from majors
                order by name, id
                """, (resultSet, rowNum) -> new Row(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("normalized_name"),
                resultSet.getString("status"),
                resultSet.getObject("display_major_id", Long.class)));
    }

    private static Map<Long, Row> byId(List<Row> rows) {
        Map<Long, Row> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.id(), row));
        return byId;
    }

    /** 그 학과가 회원에게 내보이는 이름. 이어받은 학과가 있으면 그 이름을 쓴다. */
    private static String shownName(Row row, Map<Long, Row> byId) {
        Row display = row.displayMajorId() == null ? null : byId.get(row.displayMajorId());
        return display == null ? row.name() : display.name();
    }

    private static Map<String, Object> option(long id, String name, String normalizedName, String status,
            Long displayMajorId) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", id);
        option.put("name", name);
        option.put("normalizedName", normalizedName);
        option.put("status", status);
        option.put("displayMajorId", displayMajorId);
        return option;
    }
}

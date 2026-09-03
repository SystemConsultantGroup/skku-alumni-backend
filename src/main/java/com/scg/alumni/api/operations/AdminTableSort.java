package com.scg.alumni.api.operations;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관리 표의 칸 정렬.
 *
 * <p>정렬 기준을 문자열로 받아 그대로 SQL 에 붙이면 주입 통로가 된다. 화면이
 * 보낼 수 있는 값을 미리 정해두고, 그 목록에 있는 것만 SQL 조각으로 바꾼다.
 *
 * <p>정렬이 붙는 표는 커서 대신 건너뛴 개수로 쪽을 넘긴다. 커서는 "마지막으로 본
 * id 다음부터"라는 뜻이라 id 순서일 때만 성립한다. 이름순으로 정렬해 놓고 id
 * 커서를 쓰면 다음 쪽이 엉뚱한 자리에서 시작한다.
 *
 * <p>전체 개수는 세지 않는다. 한 줄 더 읽어보고 다음 쪽이 있는지만 판단한다.
 */
final class AdminTableSort {

    private AdminTableSort() {
    }

    /**
     * 정렬 조각을 만든다.
     *
     * @param columns  화면이 보낼 수 있는 정렬 기준과 그에 해당하는 SQL 식
     * @param sort     화면이 고른 기준
     * @param order    asc 또는 desc
     * @param fallback 기준이 같을 때 줄 순서를 고정할 식 (보통 기본키)
     */
    static String orderBy(Map<String, String> columns, String sort, String order, String fallback) {
        String expression = lookup(columns, sort);
        if (expression == null) {
            if (StringUtils.hasText(sort)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정렬할 수 없는 항목입니다.");
            }
            return "order by " + fallback + " desc";
        }
        String direction = "asc".equals(normalize(order)) ? "asc" : "desc";
        // 값이 같은 줄이 쪽마다 다른 순서로 나오면 같은 회원이 두 번 보이거나 빠진다.
        // 기준값이 같을 때의 순서를 기본키로 고정한다.
        return "order by " + expression + " " + direction + ", " + fallback + " desc";
    }

    /**
     * 정렬 기준을 대소문자 구분 없이 찾는다.
     *
     * <p>목록의 키는 화면이 쓰는 이름 그대로라 memberCount 처럼 낙타등이다. 받은 값만
     * 소문자로 바꿔 맞추면 낙타등 키는 영영 걸리지 않고, 화면은 멀쩡한 칸을 눌렀는데
     * "정렬할 수 없는 항목" 만 돌려받는다. 양쪽을 같은 방식으로 눕혀 놓고 맞춘다.
     */
    private static String lookup(Map<String, String> columns, String sort) {
        String wanted = normalize(sort);
        if (wanted.isEmpty()) {
            return null;
        }
        return columns.entrySet().stream()
                .filter(column -> normalize(column.getKey()).equals(wanted))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /** 비어 있는 값은 어느 방향으로 정렬하든 뒤로 보낸다. 빈칸이 위에 쌓이면 표를 읽을 수 없다. */
    static String nullsLast(String expression) {
        return "case when " + expression + " is null then 1 else 0 end, " + expression;
    }

    static int offset(Long cursor) {
        return cursor == null || cursor < 0 ? 0 : cursor.intValue();
    }

    /**
     * 한 줄 더 읽어온 결과를 쪽으로 자른다.
     *
     * <p>다음 커서는 "여기까지 봤다"는 개수다.
     */
    static com.scg.alumni.api.common.CursorPageResponse<Map<String, Object>> page(
            List<Map<String, Object>> fetchedRows, Long cursor, Integer requestedSize) {
        int pageSize = CursorPageFactory.queryLimit(requestedSize) - 1;
        boolean hasNext = fetchedRows.size() > pageSize;
        List<Map<String, Object>> items = hasNext ? fetchedRows.subList(0, pageSize) : fetchedRows;
        Long nextCursor = hasNext ? (long) (offset(cursor) + items.size()) : null;
        return new com.scg.alumni.api.common.CursorPageResponse<>(items, nextCursor, hasNext);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

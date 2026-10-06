package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.PageResponse;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 어드민 표의 쪽 넘김.
 *
 * <p>화면이 고를 수 있는 쪽 크기를 정해 두고 그 밖의 값은 기본값으로 돌린다. 크기를
 * 그대로 받으면 한 번의 호출로 표 전체를 읽어 가는 통로가 된다.
 *
 * <p>쪽 번호 방식이라 전체 개수를 센다. 필터를 건 결과가 몇 쪽인지 알아야 번호를
 * 그릴 수 있기 때문이다. 개수는 조회와 같은 조건을 그대로 감싸서 세므로 둘이 어긋나지
 * 않는다.
 */
final class AdminPaging {

    static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100);
    static final int DEFAULT_PAGE_SIZE = 20;

    private AdminPaging() {
    }

    static int size(Integer requested) {
        return requested != null && PAGE_SIZES.contains(requested) ? requested : DEFAULT_PAGE_SIZE;
    }

    /**
     * 한 쪽을 읽는다.
     *
     * @param select  where 까지 붙은 조회문. 정렬과 limit 는 붙이지 않는다.
     * @param orderBy {@code order by ...} 조각
     * @param args    select 안의 ? 에 들어갈 값
     */
    static PageResponse<Map<String, Object>> query(
            JdbcTemplate jdbc, String select, String orderBy, Object[] args, Integer requestedPage, Integer requestedSize) {
        int size = size(requestedSize);
        Long total = jdbc.queryForObject("select count(*) from (" + select + ") counted", Long.class, args);
        long totalElements = total == null ? 0 : total;
        int totalPages = (int) Math.max(1, (totalElements + size - 1) / size);
        // 마지막 쪽의 마지막 줄을 지우고 나면 그 쪽이 사라진다. 빈 쪽을 보여주는 대신 끝 쪽으로 당긴다.
        int page = Math.min(Math.max(requestedPage == null ? 1 : requestedPage, 1), totalPages);

        Object[] pageArgs = java.util.Arrays.copyOf(args, args.length + 2);
        pageArgs[args.length] = size;
        pageArgs[args.length + 1] = (long) (page - 1) * size;
        List<Map<String, Object>> rows = jdbc.query(
                select + "\n" + orderBy + "\nlimit ? offset ?", JdbcResponseMapper.INSTANCE, pageArgs);
        return new PageResponse<>(rows, page, size, totalElements, totalPages);
    }

    static Object[] args(Object... values) {
        return values;
    }
}

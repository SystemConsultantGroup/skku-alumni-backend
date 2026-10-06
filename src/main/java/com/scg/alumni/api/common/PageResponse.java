package com.scg.alumni.api.common;

import java.util.List;

/**
 * 쪽 번호로 넘기는 목록 응답. 어드민 표가 쓴다.
 *
 * @param page          지금 쪽 (1부터). 요청한 쪽이 끝을 넘으면 마지막 쪽으로 당겨서 돌려준다.
 * @param size          한 쪽에 담은 최대 줄 수
 * @param totalElements 필터를 건 뒤의 전체 줄 수
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
